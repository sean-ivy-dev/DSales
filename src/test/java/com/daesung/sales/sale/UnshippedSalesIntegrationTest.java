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
 * 미출고 매출 + 반품입고 구분. 근거: 테스트버전 피드백 1차(2026-09-17).
 *
 * <pre>
 *   화면13 — "화면기획안 필요기능의 '미출고 콘텐츠의 매출 등록'이 안 보임.
 *             실제 출고가 발생하지 않는 매출(문항사용료·학원매출 등)을 출고 등록 없이
 *             매출만 등록하는 경우이며, 거래명세서·물류 작업으로 연계되지 않아야 함"
 *   화면5  — "입고구분 드롭다운에 '반품입고' 옵션 자체가 없음(7/30 확정 미반영)"
 * </pre>
 *
 * <p>★<b>창고를 선택으로 열되 아무 때나 비울 수는 없다.</b> 재고가 움직이는 거래를
 * 창고 없이 받으면 재고를 조용히 안 건드리고 매출만 서서 장부가 갈라진다 —
 * 그건 지금까지 고쳐 온 "조용히 무시" 계열 사고와 같다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("미출고 매출 · 반품입고 구분(2026-09-17 피드백)")
class UnshippedSalesIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-US" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2096;

    private Long partner;
    private Long wh;
    private Long sup;
    private Long stocked;     // 재고관리 상품
    private Long unstocked;   // 재고 미관리(모의고사 등)

    @BeforeAll
    void seed() {
        token();
        sup = createId("/masters/clients",
                Map.of("code", "USS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "USP" + SFX, "name", "미출고거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "USW" + SFX, "name", "미출고창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "USB" + SFX);
        b.put("name", "재고도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "U" + YEAR + "A");
        b.put("catName", "미출고분류");
        b.put("salesDivision", "교재");
        stocked = createId("/masters/products", b);

        // 문항사용료처럼 실물이 없는 상품 — 재고를 세지 않는다.
        Map<String, Object> b2 = new HashMap<>(b);
        b2.put("code", "USF" + SFX);
        b2.put("name", "문항사용료");
        b2.put("salesDivision", "교재");
        b2.put("stockManaged", false);
        unstocked = createId("/masters/products", b2);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", stocked, "unitCost", 3000, "qty", 500))));
    }

    private JsonNode sell(Long product, Long warehouseId, int qty) {
        Map<String, Object> body = new HashMap<>();
        body.put("salesDate", YEAR + "-02-10");
        body.put("partnerId", partner);
        if (warehouseId != null) {
            body.put("warehouseId", warehouseId);
        }
        body.put("items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                "unitPrice", 10000, "supplyRate", 70, "qty", qty)));
        return post("/sales/entries", body);
    }

    @Test
    @DisplayName("★창고 없이 매출만 선다 — 문항사용료·학원매출 같은 미출고 건")
    void 미출고_매출() {
        JsonNode r = sell(unstocked, null, 3);

        assertThat(r.path("success").asBoolean()).as("창고 없이도 등록돼야: %s", r).isTrue();
        assertThat(data(r).path("items").get(0).path("supplyAmount").asLong())
                .as("매출은 정상적으로 선다").isEqualTo(3 * 7000);
    }

    @Test
    @DisplayName("★재고가 움직이는 매출은 창고가 없으면 400 — 조용히 안 빼면 장부가 갈라진다")
    void 재고상품은_창고필수() {
        JsonNode r = sell(stocked, null, 5);

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText())
                .contains("창고를 지정", "출고 없는 매출");
    }

    @Test
    @DisplayName("창고를 주면 종전대로 재고가 빠진다 — 기본 동작이 바뀌면 안 된다")
    void 창고_주면_종전대로() {
        // ‼️절대값으로 비교하지 않는다 — 다른 테스트가 같은 창고에 입고를 넣어
        //   실행 순서에 따라 잔량이 달라진다(테스트 격리 규율).
        int before = data(sell(stocked, wh, 1)).path("items").get(0).path("stockBalance").asInt();

        JsonNode r = sell(stocked, wh, 10);

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("items").get(0).path("stockBalance").asInt())
                .as("10부만큼 줄어야").isEqualTo(before - 10);
    }

    @Test
    @DisplayName("★반품입고 구분으로 입고할 수 있다 — 드롭다운에 없던 값")
    void 반품입고_구분() {
        JsonNode r = post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-03-01", "supplierClientId", sup,
                "destinationWarehouseId", wh, "inboundType", "RETURN",
                "items", List.of(Map.of("productId", stocked, "unitCost", 3000, "qty", 7))));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("inboundType").asText()).isEqualTo("RETURN");
    }

    @Test
    @DisplayName("★반품입고는 매입액에 안 들어간다 — 되돌아온 물건은 사 온 게 아니다")
    void 반품입고는_매입이_아니다() {
        String range = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

        // 매입입고와 반품입고를 각각 넣고, 매입 집계에 무엇이 잡히는지 본다.
        post("/stock/inbound", Map.of("processedDate", YEAR + "-04-01", "supplierClientId", sup,
                "destinationWarehouseId", wh, "inboundType", "PURCHASE",
                "items", List.of(Map.of("productId", stocked, "unitCost", 3000, "qty", 100))));
        post("/stock/inbound", Map.of("processedDate", YEAR + "-04-02", "supplierClientId", sup,
                "destinationWarehouseId", wh, "inboundType", "RETURN",
                "items", List.of(Map.of("productId", stocked, "unitCost", 3000, "qty", 100))));

        for (JsonNode row : data(get("/sales/net-summary" + range)).path("rows")) {
            if (("USB" + SFX).equals(row.path("productCode").asText())) {
                assertThat(row.path("inboundQty").asLong())
                        .as("매입입고 100만 — 반품입고는 빠진다").isEqualTo(100);
            }
        }
    }
}
