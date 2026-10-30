package com.daesung.sales.logistics;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 작업결과가 발송↔매출을 <b>매출 행의 거래분류</b>로 잇는다(프론트 실측 2026-10-22 ②).
 *
 * <p>‼️예전엔 <b>상품의 세부구분</b>과 맞췄다. 둘은 다른 축이라 거의 안 붙었다 —
 * 2026년 발송 2,219건 중 341건만 매칭됐고, 나머지는 수량 0 · 출고창고 공란으로 나왔다.
 * 창고가 비면 물류 담당자(본사물류창고 고정) 필터에서 통째로 빠져 화면이 거의 비었다.
 *
 * <p>★한 발송에 <b>세부구분이 다른 상품</b>이 섞여 들어가는 것이 핵심이다.
 * 실데이터에서 발송 '기타고사'에 상품 'D모의고사'가 65,081건 붙어 있었다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("작업결과 조인 키 — 매출의 거래분류로 잇는다")
class WorkResultJoinKeyIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-JK" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2093-05-20";
    private static final String RANGE = "?fromDate=" + DATE + "&toDate=" + DATE
            + "&page=0&size=50";

    @Autowired
    private JdbcTemplate jdbc;

    private Long partner;
    private Long warehouse;
    private Long bookA;   // 세부구분 '교재'
    private Long bookB;   // 세부구분 '기타고사' — 같은 발송에 섞인다

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "JKS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "JKP" + SFX, "name", "조인키거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "JKW" + SFX, "name", "본사물류창고", "type", "MAIN"));

        bookA = book("JKA" + SFX, "교재");
        bookB = book("JKB" + SFX, "기타고사");

        for (Long id : List.of(bookA, bookB)) {
            assertThat(post("/stock/inbound", Map.of(
                    "processedDate", "2093-01-05", "supplierClientId", supplier,
                    "destinationWarehouseId", warehouse,
                    "items", List.of(Map.of("productId", id, "unitCost", 1000, "qty", 500))))
                    .path("success").asBoolean()).isTrue();
        }

        // 같은 날·같은 거래처·같은 학교로 **세부구분이 다른 두 상품**을 판다.
        sell(bookA, 40);
        sell(bookB, 25);

        // ★이관분 흉내 — 레거시는 발송 한 건에 묶고 매출 행에 그 거래분류를 적어 둔다.
        //   화면 등록은 상품 세부구분으로 발송을 나누므로, 여기서 손으로 합쳐 레거시 모양을 만든다.
        jdbc.update("UPDATE sales SET trade_class = '교재' WHERE partner_id = ? AND sales_date = ?",
                partner, java.sql.Date.valueOf(DATE));
        jdbc.update("DELETE FROM shipment WHERE partner_id = ? AND trade_class <> '교재'", partner);
        jdbc.update("UPDATE shipment SET trade_class = '교재' WHERE partner_id = ?", partner);
        // ‼️**주문 식별자도 비운다** — 이관분에는 없는 값이다(10/27 §8-2 로 발송이 주문 단위로
        //   갈리기 전에 만들어진 건이라 entry_no 가 NULL 이다). 비우지 않으면 "이관분 흉내"가
        //   아니라 "새로 등록한 건"이 돼, 네 칸 키로 떨어지는 과거 데이터 경로를 못 지킨다.
        jdbc.update("UPDATE shipment SET entry_no = NULL WHERE partner_id = ?", partner);
    }

    private Long book(String code, String division) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", code + " 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "J2093A01");
        b.put("catName", "조인키");
        b.put("taxFree", false);
        b.put("useYn", true);
        b.put("set", false);
        b.put("salesDivision", division);
        return createId("/masters/products", b);
    }

    private void sell(Long product, int qty) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", qty);
        item.put("schoolCode", "JKSCH");
        item.put("schoolName", "조인키학교");
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출: %s", r).isTrue();
    }

    @Test
    @DisplayName("★세부구분이 섞인 발송도 수량·출고창고가 채워진다")
    void 섞여도_붙는다() {
        JsonNode rows = data(get("/logistics/work-results" + RANGE)).path("content");
        java.util.List<JsonNode> mines = new java.util.ArrayList<>();
        for (JsonNode r : rows) {
            if ("조인키거래처".equals(r.path("partnerName").asText())) {
                mines.add(r);
            }
        }
        assertThat(mines).as("발송 행이 있다: %s", rows).isNotEmpty();
        JsonNode mine = mines.get(mines.size() - 1);
        assertThat(mines).as("이관분은 한 건으로 합쳐져 있다").hasSize(1);

        // ‼️예전엔 여기가 0 이었다 — 상품 세부구분('교재'·'기타고사')이 발송('교재')과 안 맞아서다.
        //   ★10/27 명세 §8-2 로 발송이 **주문 단위**로 갈렸지만, 이 시드는 **이관분**이라
        //   entry_no 가 NULL 이다 → 예전처럼 **네 칸(일자·거래처·학교·분류)으로 묶인다**.
        //   즉 과거 데이터가 주문 단위 전환 뒤에도 그대로 보이는지를 함께 지킨다.
        long total = mines.stream().mapToLong(r -> r.path("totalQty").asLong()).sum();
        assertThat(total).as("두 상품 수량이 모두 잡힌다(행이 갈려도 합은 같다)").isEqualTo(65);

        // 창고가 채워져야 물류 담당자 필터(본사물류창고 고정)를 통과한다 — 화면이 비던 진짜 원인.
        assertThat(mine.path("warehouseName").asText()).as("출고창고").contains("본사물류창고");
    }
}
