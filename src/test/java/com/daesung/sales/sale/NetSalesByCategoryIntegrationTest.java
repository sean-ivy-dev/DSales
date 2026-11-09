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
 * 순매출 조회 — <b>분류별 집계</b>(P-19).
 *
 * <p>레거시 순매출조회의 집계 단위 라디오는 「○거래처별 ○분류별」이다
 * ({@code 순매출조회.Designer.vb} {@code RadioButton_Cust}·{@code RadioButton_Cat}).
 * 우리는 도서별·거래처별만 있어 <b>분류별이 빠져 있었다</b> —
 * {@code groupBy=CATEGORY} 를 보내도 도서별과 같은 행이 나왔다(운영 실측: 둘 다 507행).
 *
 * <p>★<b>이 테스트가 막는 것</b>: 분류별이 다시 도서별로 떨어지는 것.
 * 같은 분류의 도서 둘을 넣고 <b>한 줄로 묶이는지</b>를 본다 — 두 줄이면 도서별로 돈 것이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("순매출 분류별 집계 — 도서별로 떨어지지 않는다(P-19)")
class NetSalesByCategoryIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-NC" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2094-06-15";
    private static final String PERIOD = "fromDate=2094-01-01&toDate=2094-12-31";
    private static final String CAT = "S2094A01";

    private Long partnerA;
    private Long partnerB;
    private Long warehouse;
    private Long bookA;
    private Long bookB;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "NCS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partnerA = createId("/masters/clients",
                Map.of("code", "NCA" + SFX, "name", "분류검증A", "type", "NORMAL"));
        partnerB = createId("/masters/clients",
                Map.of("code", "NCB" + SFX, "name", "분류검증B", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "NCW" + SFX, "name", "창고", "type", "MAIN"));
        bookA = book("NC1" + SFX, "분류검증 1권");
        bookB = book("NC2" + SFX, "분류검증 2권");

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2094-01-05", "supplierClientId", sup,
                "destinationWarehouseId", warehouse,
                "items", List.of(
                        Map.of("productId", bookA, "qty", 1000, "unitCost", 1000),
                        Map.of("productId", bookB, "qty", 1000, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();

        // 같은 분류의 도서 둘 × 거래처 둘 — 분류별이면 **한 줄**, 도서별이면 두 줄이다.
        sell(partnerA, bookA, 10);
        sell(partnerA, bookB, 20);
        sell(partnerB, bookA, 30);
    }

    private Long book(String code, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", CAT);
        b.put("catName", "분류검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        return createId("/masters/products", b);
    }

    private void sell(Long partner, Long product, int qty) {
        assertThat(post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
    }

    /**
     * ‼️<b>거래처로 격리한다.</b> 분류코드는 고정 문자열이라 다른 테스트가 같은 값을 쓰면
     * 행이 늘어난다(전체 스위트에서 실제로 깨졌다). 거래처는 실행별 고유 접미어가 붙는다.
     */
    private JsonNode rows(String groupBy) {
        JsonNode res = get("/sales/net-summary?" + PERIOD
                + "&partnerIds=" + partnerA + "," + partnerB
                + "&groupBy=" + groupBy);
        assertThat(res.path("success").asBoolean()).as("조회(%s): %s", groupBy, res).isTrue();
        return res.path("data").path("rows");
    }

    @Test
    @DisplayName("‼️분류별은 같은 분류의 도서 2권을 **한 줄**로 묶는다 — 두 줄이면 도서별로 돈 것")
    void 분류별은_한줄() {
        JsonNode rows = rows("CATEGORY");
        assertThat(rows).as("분류 하나이므로 한 줄이어야 한다: %s", rows).hasSize(1);

        JsonNode r = rows.get(0);
        assertThat(r.path("catCode").asText()).isEqualTo(CAT);
        assertThat(r.path("catName").asText()).isEqualTo("분류검증");
        // 10 + 20 + 30 = 60부, 공급가 60 × 10,000 × 70% = 420,000
        assertThat(r.path("saleQty").asLong()).isEqualTo(60L);
        assertThat(r.path("saleAmount").asLong()).isEqualTo(420_000L);
        // 도서·거래처 칸은 비운다(섞인 값이다) — Jackson NON_NULL 이라 키가 없다.
        assertThat(r.has("productCode")).as("도서 칸이 남으면 안 된다: %s", r).isFalse();
        assertThat(r.has("partnerCode")).isFalse();
    }

    @Test
    @DisplayName("도서별은 종전대로 2줄 — 축만 바뀌고 금액 칸은 같다")
    void 도서별은_두줄() {
        JsonNode rows = rows("PRODUCT");
        assertThat(rows).hasSize(2);
        long sum = 0;
        for (JsonNode r : rows) {
            sum += r.path("saleQty").asLong();
        }
        assertThat(sum).as("축이 달라도 합은 같아야 한다").isEqualTo(60L);
    }

    @Test
    @DisplayName("거래처별은 2줄(거래처 2곳) — 합은 분류별과 같다")
    void 거래처별도_합이_같다() {
        JsonNode rows = rows("PARTNER");
        assertThat(rows).hasSize(2);
        long sum = 0;
        for (JsonNode r : rows) {
            sum += r.path("saleQty").asLong();
        }
        assertThat(sum).isEqualTo(60L);
    }

    @Test
    @DisplayName("‼️매입·이익 칸은 비운다 — 분류에 섞인 단가로 이익률을 내면 안 된다")
    void 분류별은_매입이익_비움() {
        JsonNode r = rows("CATEGORY").get(0);
        assertThat(r.has("purchaseUnitCost")).as("섞인 매입단가: %s", r).isFalse();
        assertThat(r.has("marginPct")).isFalse();
        assertThat(r.has("contentType")).as("한 분류에 자체·매입 교재가 섞인다").isFalse();
    }
}
