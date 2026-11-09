package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 수량 0 매출 — <b>막지 않고 알림만</b>(F-17).
 *
 * <p>발주처 원문: "금액 자동계산이어도 수정·직접입력 모두 가능. <b>매출만 있는 상품은 수량 없이
 * 금액만 등록함.</b> <b>수량 1 이상은 알림만 띄우고 등록은 허용</b>
 * (레거시는 수량 0 + 금액 등록 가능 — 9장 노란 원)".
 *
 * <p>★<b>등록을 허용하는 것만으로는 부족하다.</b> 수량 0인데
 * <ul>
 *   <li>재고 이벤트를 남기면 수불부에 0짜리 줄이 쌓이고,</li>
 *   <li>작업지시를 만들면 <b>"0부를 보내라"</b>가 물류 대기목록에 영원히 남는다.</li>
 * </ul>
 * 그래서 둘 다 만들지 않고 경고로만 알린다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// ‼️**순서를 고정한다.** `정상수량은_그대로` 가 먼저 돌면 작업지시가 생겨
//   `수량0_작업지시없음` 이 그 건을 보고 깨진다. 메서드 순서는 보장되지 않는다.
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@DisplayName("수량 0 매출 — 등록 허용, 재고·작업지시 없음(F-17)")
class ZeroQtySaleIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-ZQ" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2099-03-15";

    private Long partner;
    private Long warehouse;
    private Long product;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "ZQS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "ZQP" + SFX, "name", "수량0 거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "ZQW" + SFX, "name", "창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "ZQB" + SFX);
        b.put("name", "수량0 검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2099A01");
        b.put("catName", "수량0검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        product = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2099-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "qty", 100, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @Order(1)
    @DisplayName("수량 0 + 금액 직접입력 — 등록되고 경고가 함께 온다")
    void 수량0_등록허용() {
        JsonNode res = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of(
                        "productId", product, "shipmentType", "NORMAL_SHIP",
                        "qty", 0, "unitPrice", 10000, "supplyRate", 70,
                        // 매출만 있는 상품 — 금액은 직접 넣는다
                        "supplyAmount", 500_000))));
        assertThat(res.path("success").asBoolean())
                .as("‼️수량 0을 막으면 안 된다(F-17): %s", res).isTrue();

        // 금액은 직접 넣은 값이 그대로
        JsonNode line = res.path("data").path("lines").get(0);
        if (line != null && line.has("supplyAmount")) {
            assertThat(line.path("supplyAmount").asLong()).isEqualTo(500_000L);
        }

        // ★경고가 와야 한다 — "알림만 띄우고 등록은 허용"
        boolean zeroWarn = false;
        for (JsonNode w : res.path("data").path("warnings")) {
            if ("ZERO_QTY".equals(w.path("code").asText())) {
                zeroWarn = true;
                assertThat(w.path("message").asText()).contains("수량이 0");
            }
        }
        assertThat(zeroWarn).as("ZERO_QTY 경고: %s", res.path("data").path("warnings")).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("‼️수량 0은 재고를 건드리지 않는다 — 입고분 100이 그대로")
    void 수량0_재고불변() {
        JsonNode ledger = get("/stock/ledger?fromDate=2099-01-01&toDate=2099-12-31"
                + "&productId=" + product + "&warehouseId=" + warehouse);
        assertThat(ledger.path("success").asBoolean()).as("수불부: %s", ledger).isTrue();

        // 매출 칸이 0이어야 한다(수량 0짜리 출고 이벤트가 없다).
        JsonNode rows = ledger.path("data").isArray()
                ? ledger.path("data") : ledger.path("data").path("rows");
        boolean checked = false;
        for (JsonNode r : rows) {
            if (product.equals(r.path("productId").asLong())) {
                assertThat(r.path("salesQty").asInt(0)).as("매출 수량 칸: %s", r).isZero();
                checked = true;
            }
        }
        // 수불부노출이 꺼져 있으면 행 자체가 없다 — 그때는 재고 이벤트 목록으로 본다.
        if (!checked) {
            JsonNode recs = get("/stock/records?fromDate=2099-01-01&toDate=2099-12-31"
                    + "&productIds=" + product);
            int outbound = 0;
            for (JsonNode n : recs.path("data").path("content")) {
                if ("OUTBOUND".equals(n.path("txnType").asText())) {
                    outbound++;
                }
            }
            assertThat(outbound).as("수량 0 출고 이벤트가 생기면 안 된다: %s", recs).isZero();
        }
    }

    @Test
    @Order(3)
    @DisplayName("‼️수량 0은 작업요청서를 만들지 않는다 — 0부 출고 지시가 물류로 가면 안 된다")
    void 수량0_작업지시없음() {
        JsonNode res = get("/logistics/work-orders?fromDate=" + DATE + "&toDate=" + DATE
                + "&page=0&size=50");
        assertThat(res.path("success").asBoolean()).as("작업요청서: %s", res).isTrue();
        for (JsonNode n : res.path("data").path("content")) {
            assertThat(n.path("partnerCode").asText())
                    .as("수량 0 매출로 작업지시가 생겼다: %s", n)
                    .isNotEqualTo("ZQP" + SFX);
        }
    }

    @Test
    @Order(4)
    @DisplayName("수량 1 이상은 종전대로 — 재고도 빠지고 작업지시도 생긴다")
    void 정상수량은_그대로() {
        JsonNode res = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of(
                        "productId", product, "shipmentType", "NORMAL_SHIP",
                        "qty", 7, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(res.path("success").asBoolean()).as("정상 등록: %s", res).isTrue();

        // 이번엔 ZERO_QTY 경고가 없어야 한다
        for (JsonNode w : res.path("data").path("warnings")) {
            assertThat(w.path("code").asText()).isNotEqualTo("ZERO_QTY");
        }

        // 작업지시가 생긴다
        JsonNode wo = get("/logistics/work-orders?fromDate=" + DATE + "&toDate=" + DATE
                + "&page=0&size=50");
        boolean found = false;
        for (JsonNode n : wo.path("data").path("content")) {
            if (("ZQP" + SFX).equals(n.path("partnerCode").asText())) {
                found = true;
            }
        }
        assertThat(found).as("정상 수량은 작업지시가 생겨야 한다: %s", wo).isTrue();
    }
}
