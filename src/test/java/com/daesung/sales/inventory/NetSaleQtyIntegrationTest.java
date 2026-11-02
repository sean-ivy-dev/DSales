package com.daesung.sales.inventory;

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
 * 제품수불부 ↔ 순매출조회 순매출수량 일치 고정 — 개발팀 점검(2026-09-11) P0-3.
 *
 * <p>같은 도서인데 두 화면이 다른 수를 냈다.
 * <pre>
 *   제품수불부 현황  3,029   매출 3,010 + 교사용 15 + 반품 4   ← 화면이 더한 값
 *   순매출 조회      3,006   매출 3,010 − 반품 4              ← 서버 집계
 * </pre>
 *
 * <p><b>정본은 3,006</b>이다. 순매출은 <b>매출 − 반품</b>이고, 교사용·증정은 무가라 들어가지 않는다.
 * 수불부 응답은 원래 부호를 달고 있어(매출 음수·반품 양수) 서버 값 자체는 어긋난 적이 없다 —
 * 화면이 절대값을 더하면서 갈렸다.
 *
 * <p>★그래서 <b>서버가 {@code netSaleQty}를 직접 내려준다.</b> 화면이 다시 계산할 일이 없어야
 * 같은 사고가 안 난다. 계산식을 화면에 맡기면 화면 수만큼 정의가 생긴다(레거시가 그랬다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("순매출수량 일치(2026-09-11 점검 P0-3)")
class NetSaleQtyIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-NQ" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2076;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long wh;
    private Long book;
    private Long partner;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "NQS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "NQP" + SFX, "name", "순매출거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "NQW" + SFX, "name", "순매출창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "NQB" + SFX);
        b.put("name", "순매출도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "Q" + YEAR + "A");
        b.put("catName", "순매출분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 5000))));

        // 점검에서 나온 비율 그대로 — 매출 3,010 · 교사용 15 · 반품 4
        sale("NORMAL_SHIP", 3_010);
        sale("TEACHER_USE", 15);
        JsonNode ret = post("/sales/return-inbound", Map.of(
                "returnDate", YEAR + "-02-20", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitPrice", 10000,
                        "supplyRate", 70, "qty", 4))));
        assertThat(ret.path("success").asBoolean()).as("반품입고: %s", ret).isTrue();
    }

    private void sale(String shipmentType, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", shipmentType,
                        "unitPrice", 10000, "supplyRate", 70, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private JsonNode ledgerRow() {
        for (JsonNode r : data(get("/stock/ledger" + RANGE + "&warehouseId=" + wh
                + "&keyword=NQB" + SFX + "&size=50")).path("content")) {
            if (("NQB" + SFX).equals(r.path("productCode").asText())) {
                return r;
            }
        }
        throw new IllegalStateException("수불부 행 없음");
    }

    @Test
    @DisplayName("★수불부 순매출수량 = 매출 − 반품 = 3,006 — 교사용 15는 무가라 안 들어간다")
    void 수불부_순매출수량() {
        JsonNode row = ledgerRow();

        assertThat(row.path("sale").asLong()).as("매출은 음수로 적힌다").isEqualTo(-3_010);
        assertThat(row.path("teacher").asLong()).isEqualTo(-15);
        assertThat(row.path("salesReturn").asLong()).as("반품은 양수").isEqualTo(4);

        assertThat(row.path("netSaleQty").asLong())
                .as("★3,029(셋을 더한 값)가 아니라 3,006이다").isEqualTo(3_006);
    }

    @Test
    @DisplayName("★순매출조회와 같은 값이어야 한다 — 두 화면이 갈리면 어느 쪽도 못 믿는다")
    void 순매출조회와_일치() {
        JsonNode rows = data(get("/sales/net-summary?fromDate=" + YEAR + "-01-01"
                + "&toDate=" + YEAR + "-12-31")).path("rows");

        long fromNetSales = 0;
        for (JsonNode r : rows) {
            if (("NQB" + SFX).equals(r.path("productCode").asText())) {
                fromNetSales = r.path("netQty").asLong();
            }
        }

        assertThat(fromNetSales).as("순매출조회 쪽").isEqualTo(3_006);
        assertThat(ledgerRow().path("netSaleQty").asLong())
                .as("수불부가 같은 수를 내야 한다").isEqualTo(fromNetSales);
    }

    @Test
    @DisplayName("재고 방정식은 그대로 성립 — 순매출수량은 보조 컬럼이지 재고 계산에 끼지 않는다")
    void 재고방정식() {
        JsonNode r = ledgerRow();

        long sum = r.path("opening").asLong() + r.path("inbound").asLong()
                + r.path("transfer").asLong() + r.path("bom").asLong()
                + r.path("dispose").asLong() + r.path("sale").asLong()
                + r.path("free").asLong() + r.path("teacher").asLong()
                + r.path("salesReturn").asLong() + r.path("adjust").asLong();

        assertThat(sum).as("부호를 달고 있으니 그냥 더하면 현재재고다").isEqualTo(r.path("closing").asLong());
        assertThat(r.path("reconciled").asBoolean()).as("캐시 잔량과도 맞는다").isTrue();
    }
}
