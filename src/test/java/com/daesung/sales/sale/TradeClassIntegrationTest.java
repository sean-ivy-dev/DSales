package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 거래분류 축(7p 출고/반품조회) 회귀 고정.
 *
 * <p>발주처 표준 구분값 4축(2026-08-05) 중 첫째. 근거: 레거시 {@code salesData.tradeType}
 * ({@code 조회.vb:1288} — {@code min(tradeType) as '거래분류'}).
 *
 * <p>★지키려는 것: 거래분류는 <b>구분(상세)에서 파생</b>된다는 것. 따로 저장하면 둘이 어긋날 수 있고,
 * 어긋나면 어느 쪽이 맞는지 알 수 없다. 그리고 매출 원장에 없는 값(입고·폐기)으로 거르면
 * <b>빈 결과</b>여야 한다 — 조건을 무시하면 '폐기'로 걸렀는데 매출이 나온다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("거래분류 축(7p)")
class TradeClassIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-TC" + (System.nanoTime() % 1_000_000L);
    private static final String RANGE = "fromDate=2039-04-01&toDate=2039-04-30";

    private Long partner;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "TCS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "TCP" + SFX, "name", "분류거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "TCW" + SFX, "name", "물류", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of("code", "TCB" + SFX, "name", "분류도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75, "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2039-04-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));

        // 세 가지 회계구분을 만든다 → 거래분류 매출/무상/반품
        sale(wh, book, "NORMAL_SHIP", 20);    // SALE  → 매출
        sale(wh, book, "TEACHER_USE", 5);     // FREE  → 무상
        post("/sales/return-inbound", Map.of(
                "returnDate", "2039-04-15", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitPrice", 10000,
                        "supplyRate", 75, "qty", 3))));   // RETURN → 반품
    }

    @Test
    @DisplayName("거래분류가 구분(상세)에서 파생된다 — 값이 서로 어긋날 수 없다")
    void 파생값() {
        for (JsonNode r : rows("&" + RANGE)) {
            String category = r.path("salesCategory").asText();
            String expected = switch (category) {
                case "SALE" -> "SALES";
                case "FREE" -> "FREE";
                case "RETURN" -> "RETURN";
                default -> throw new AssertionError("알 수 없는 구분: " + category);
            };
            assertThat(r.path("tradeClass").asText()).isEqualTo(expected);
        }
        // 한글 명칭도 함께 준다(프론트가 매핑표를 들고 있지 않게)
        assertThat(rows("&" + RANGE + "&tradeClass=RETURN").get(0).path("tradeClassName").asText())
                .isEqualTo("반품");
    }

    @Test
    @DisplayName("거래분류로 거른다 — 매출/무상/반품")
    void 거래분류_필터() {
        assertThat(rows("&" + RANGE + "&tradeClass=SALES")).hasSize(1);
        assertThat(rows("&" + RANGE + "&tradeClass=SALES").get(0).path("qty").asInt()).isEqualTo(20);
        assertThat(rows("&" + RANGE + "&tradeClass=FREE").get(0).path("qty").asInt()).isEqualTo(5);
        // ★반품은 음수로 내려온다(10/27 피드백 §3-2) — 합계 행이 그냥 더하기만 해도
        //   반품이 자동 상쇄되게 하려는 것. 저장(Sale.qty)은 여전히 양수 3이다.
        assertThat(rows("&" + RANGE + "&tradeClass=RETURN").get(0).path("qty").asInt()).isEqualTo(-3);
    }

    @Test
    @DisplayName("입고·폐기로 거르면 빈 결과 — 재고 원장의 거래라 매출 조회에 없다")
    void 재고측_값은_빈결과() {
        assertThat(rows("&" + RANGE + "&tradeClass=INBOUND"))
                .as("조건을 무시하고 전체를 주면 '폐기'로 걸렀는데 매출이 나온다").isEmpty();
        assertThat(rows("&" + RANGE + "&tradeClass=DISPOSE")).isEmpty();
    }

    @Test
    @DisplayName("두 축이 어긋나는 조합은 빈 결과 — 거래분류=매출 + 구분(상세)=반품")
    void 어긋나는_조합() {
        assertThat(rows("&" + RANGE + "&tradeClass=SALES&salesCategory=RETURN")).isEmpty();
        // 같은 뜻이면 정상 조회된다
        assertThat(rows("&" + RANGE + "&tradeClass=SALES&salesCategory=SALE")).hasSize(1);
    }

    private List<JsonNode> rows(String query) {
        JsonNode content = data(get("/sales?partnerId=" + partner + query)).path("content");
        List<JsonNode> out = new java.util.ArrayList<>();
        content.forEach(out::add);
        return out;
    }

    private void sale(Long wh, Long book, String shipmentType, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2039-04-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", shipmentType,
                        "unitPrice", 10000, "supplyRate", 75, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("매출등록 성공: %s", r).isTrue();
    }
}
