package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 회차별 작업현황(구 IC회차별작업현황) 회귀 고정.
 * 근거: 레거시 IC회차별작업현황.vb — 분류×도서×회차 행, 포장구분(개별1/개별2/반별) 열.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("회차별 작업현황 통합테스트")
class RoundWorkStatusIntegrationTest extends IntegrationTestSupport {

    /** 실행별 고유 접두어 — 테스트 워커 JVM 재사용 시 코드 충돌 방지. */
    private static final String SFX = "-" + (System.nanoTime() % 1_000_000L);
    /** 분류코드는 [영문1자][연도4자][영문·숫자1~3자] 형식이라 뒤 3자만 실행별로 달리한다. */
    private static final String CAT = "R2026" + Long.toString(
            Math.abs(System.nanoTime()) % 46_656L, 36).toUpperCase(java.util.Locale.ROOT);

    private Long partner;
    private Long warehouse;
    private Long supplier;
    private Long product;

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients", Map.of("code", "RW-SUP" + SFX, "name", "회차인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "RW-CUST" + SFX, "name", "회차거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses", Map.of("code", "RW-WH" + SFX, "name", "회차창고", "type", "MAIN"));
        product = createId("/masters/products", Map.of(
                "code", "RW-BK" + SFX, "name", "회차도서", "contentType", "SELF", "price", 10000,
                "catCode", CAT, "catName", "회차분류",
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of(
                "processedDate", "2026-06-01", "supplierClientId", supplier, "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "unitCost", 3000, "qty", 1000))));
    }

    private void sale(int round, String packType, int qty) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 75);
        item.put("qty", qty);
        item.put("round", round);
        if (packType != null) {
            item.put("packType", packType);
        }
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2026-11-10", "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출등록: %s", r).isTrue();
    }

    @Test
    @DisplayName("포장구분별 수량이 회차 행에 개별1·개별2·반별로 펼쳐진다")
    void 회차별_포장구분_집계() {
        sale(1, "INDIVIDUAL_1", 10);
        sale(1, "INDIVIDUAL_1", 5);      // 같은 회차·같은 구분 → 합산
        sale(1, "CLASS_BUNDLE", 20);
        sale(2, "INDIVIDUAL_2", 7);
        sale(0, "INDIVIDUAL_1", 99);     // 회차 0 → 레거시와 동일하게 제외

        JsonNode rows = data(get("/sales/round-work-status"
                + "?fromDate=2026-11-01&toDate=2026-11-30&catCode=" + CAT));
        assertThat(rows).as("회차 1·2 두 행만(회차 0 제외)").hasSize(2);

        JsonNode r1 = rows.get(0);
        assertThat(r1.path("bookRound").asInt()).isEqualTo(1);
        assertThat(r1.path("catName").asText()).isEqualTo("회차분류");
        assertThat(r1.path("productName").asText()).isEqualTo("회차도서");
        assertThat(r1.path("individual1").asLong()).as("개별1 = 10+5").isEqualTo(15);
        assertThat(r1.path("classBundle").asLong()).as("반별").isEqualTo(20);
        assertThat(r1.path("individual2").asLong()).isZero();
        assertThat(r1.path("total").asLong()).as("합계").isEqualTo(35);

        JsonNode r2 = rows.get(1);
        assertThat(r2.path("bookRound").asInt()).isEqualTo(2);
        assertThat(r2.path("individual2").asLong()).isEqualTo(7);
        assertThat(r2.path("total").asLong()).isEqualTo(7);
    }

    @Test
    @DisplayName("취소된 매출은 작업현황에서 빠진다")
    void 취소건_제외() {
        sale(9, "CLASS_BUNDLE", 40);

        JsonNode before = data(get("/sales/round-work-status"
                + "?fromDate=2026-11-01&toDate=2026-11-30&catCode=" + CAT));
        JsonNode target = null;
        for (JsonNode r : before) {
            if (r.path("bookRound").asInt() == 9) {
                target = r;
            }
        }
        assertThat(target).isNotNull();
        assertThat(target.path("classBundle").asLong()).isEqualTo(40);

        // 회차 9짜리 매출을 정확히 찾아 취소(목록 첫 건은 다른 회차일 수 있다)
        Long saleId = null;
        for (JsonNode s : data(get("/sales?startDate=2026-11-01&endDate=2026-11-30&size=200&partnerId=" + partner))
                .path("content")) {
            if (s.path("bookRound").asInt() == 9) {
                saleId = s.path("id").asLong();
            }
        }
        assertThat(saleId).as("회차 9 매출을 찾아야 함").isNotNull();
        JsonNode cancel = post("/sales/" + saleId + "/cancel", Map.of());
        assertThat(cancel.path("success").asBoolean()).as("취소: %s", cancel).isTrue();

        JsonNode after = data(get("/sales/round-work-status"
                + "?fromDate=2026-11-01&toDate=2026-11-30&catCode=" + CAT));
        for (JsonNode r : after) {
            assertThat(r.path("bookRound").asInt()).as("취소 건의 회차 행은 사라져야 함").isNotEqualTo(9);
        }
    }
}
