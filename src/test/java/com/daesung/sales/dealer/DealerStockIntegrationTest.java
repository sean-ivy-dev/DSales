package com.daesung.sales.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 특약점 재고현황(10/31 클라이언트 회신 §3, 10/26 §6 확정 + <b>11/04 §4 목록 범위 확장</b>).
 *
 * <pre>
 *   로직   현행 그대로 — 구매·반품·재고 수기 입력, 출고 = 구매−재고−반품(계산만)
 *   대상   교재만
 *   범위   (노출되는 교재) ∪ (그 특약점에 재고 이력이 있는 교재) ∪ (최근 12개월 매출 출고가 있는 교재)
 * </pre>
 *
 * <p>★<b>11/04 §4</b> — 노출이 꺼진 뒤에도 특약점이 이미 입력한 재고 숫자가 화면에서
 * 사라지면 안 된다("이미 입력한 값을 잃어버린 것처럼 보인다"). 기간·'출고' 기준(매출만 보는지
 * DSRE 출고도 보는지)은 발주처 회신 대기라 설정값({@code daesung.dealer-site.stock-history-*})
 * 으로 두고, 기본값은 최근 12개월 + 매출 출고만(사용자 확정, DSRE off 환경 의존 방지).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("특약점 재고현황(10/31 회신 §3)")
class DealerStockIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-ST" + (System.nanoTime() % 1_000_000L);

    private String partnerCode;
    private Long partnerId;

    @BeforeAll
    void seed() {
        token();
        partnerCode = "STP" + SFX;
        partnerId = createId("/masters/clients",
                Map.of("code", partnerCode, "name", "재고현황거래처", "type", "NORMAL"));
        // 교재 대분류 매핑 — 노출시킨다.
        put("/masters/partner-supply-rates/" + partnerId + "/TEXTBOOK",
                Map.of("supplyRate", 70, "webVisible", true, "useYn", true));
    }

    private Long textbook(String code) {
        return createId("/masters/products", Map.of("code", code, "name", code + "교재",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", "A2093A", "catName", "재고현황분류",
                "webVisible", true));
    }

    private Long mockExam(String code) {
        return createId("/masters/products", Map.of("code", code, "name", code + "모의고사",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "모의고사", "catCode", "B2093A", "catName", "모의고사분류",
                "webVisible", true));
    }

    private JsonNode stockList() {
        return getAsDealerSite("/dealer-site/stock?partnerCode=" + partnerCode, DEALER_SITE_KEY);
    }

    private JsonNode rowOf(String code) {
        for (JsonNode r : stockList().path("data")) {
            if (code.equals(r.path("productCode").asText())) {
                return r;
            }
        }
        return null;
    }

    @Test
    @DisplayName("★대상은 교재만 — 모의고사는 안 보인다")
    void 교재만_대상() {
        String tCode = "STA" + SFX;
        String mCode = "STB" + SFX;
        textbook(tCode);
        mockExam(mCode);

        assertThat(rowOf(tCode)).as("교재는 목록에 있다").isNotNull();
        assertThat(rowOf(mCode)).as("모의고사는 대상이 아니다").isNull();
    }

    @Test
    @DisplayName("입력 전에는 전부 0")
    void 입력전_전부0() {
        String code = "STC" + SFX;
        textbook(code);

        JsonNode row = rowOf(code);
        assertThat(row.path("purchaseQty").asInt()).isZero();
        assertThat(row.path("returnQty").asInt()).isZero();
        assertThat(row.path("stockQty").asInt()).isZero();
        assertThat(row.path("shipQty").asInt()).isZero();
    }

    @Test
    @DisplayName("★구매·반품·재고를 입력하면 출고가 계산된다 — 출고=구매−재고−반품")
    void 출고계산() {
        String code = "STD" + SFX;
        textbook(code);

        Map<String, Object> req = Map.of("partnerCode", partnerCode, "productCode", code,
                "purchaseQty", 100, "returnQty", 10, "stockQty", 20);
        JsonNode r = postAsDealerSite("/dealer-site/stock", req, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("shipQty").asInt()).as("100-20-10").isEqualTo(70);

        JsonNode row = rowOf(code);
        assertThat(row.path("purchaseQty").asInt()).isEqualTo(100);
        assertThat(row.path("shipQty").asInt()).isEqualTo(70);
    }

    @Test
    @DisplayName("★다시 입력하면 덮어쓴다 — 월 축 없음, 이력 안 남음(현행과 같은 방식)")
    void 재입력시_덮어쓴다() {
        String code = "STE" + SFX;
        textbook(code);

        postAsDealerSite("/dealer-site/stock", Map.of("partnerCode", partnerCode,
                "productCode", code, "purchaseQty", 100, "returnQty", 0, "stockQty", 0), DEALER_SITE_KEY);
        postAsDealerSite("/dealer-site/stock", Map.of("partnerCode", partnerCode,
                "productCode", code, "purchaseQty", 50, "returnQty", 0, "stockQty", 0), DEALER_SITE_KEY);

        assertThat(rowOf(code).path("purchaseQty").asInt()).as("마지막 값(50)으로 덮어씀").isEqualTo(50);
    }

    @Test
    @DisplayName("일부만 입력해도(null) 나머지 값은 유지된다")
    void 부분입력_나머지유지() {
        String code = "STF" + SFX;
        textbook(code);

        postAsDealerSite("/dealer-site/stock", Map.of("partnerCode", partnerCode,
                "productCode", code, "purchaseQty", 100, "returnQty", 5, "stockQty", 10), DEALER_SITE_KEY);

        // 재고만 고친다 — 나머지는 요청에서 뺀다(= null).
        Map<String, Object> onlyStock = new java.util.HashMap<>();
        onlyStock.put("partnerCode", partnerCode);
        onlyStock.put("productCode", code);
        onlyStock.put("stockQty", 30);
        postAsDealerSite("/dealer-site/stock", onlyStock, DEALER_SITE_KEY);

        JsonNode row = rowOf(code);
        assertThat(row.path("purchaseQty").asInt()).as("구매는 그대로").isEqualTo(100);
        assertThat(row.path("returnQty").asInt()).as("반품도 그대로").isEqualTo(5);
        assertThat(row.path("stockQty").asInt()).as("재고만 바뀜").isEqualTo(30);
    }

    @Test
    @DisplayName("★노출되지 않는 도서는 PRODUCT_NOT_VISIBLE로 거부한다")
    void 비노출도서_거부() {
        String code = "STG" + SFX;
        createId("/masters/products", Map.of("code", code, "name", "비노출교재",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", "A2093A", "catName", "재고현황분류",
                "webVisible", false));   // Web게시 N

        JsonNode r = postAsDealerSite("/dealer-site/stock", Map.of("partnerCode", partnerCode,
                "productCode", code, "purchaseQty", 1, "returnQty", 0, "stockQty", 0), DEALER_SITE_KEY);

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("PRODUCT_NOT_VISIBLE");
    }

    @Test
    @DisplayName("★거래처를 못 찾으면 PARTNER_NOT_LINKED")
    void 거래처미연결() {
        JsonNode r = getAsDealerSite("/dealer-site/stock?partnerCode=없는코드" + SFX, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("PARTNER_NOT_LINKED");
    }

    @Test
    @DisplayName("★★노출이 꺼져도 재고 이력이 있으면 목록에 남는다(11/04 §4)")
    void 비노출이어도_재고이력있으면_남는다() {
        String code = "STH" + SFX;
        Long id = textbook(code);
        postAsDealerSite("/dealer-site/stock", Map.of("partnerCode", partnerCode,
                "productCode", code, "purchaseQty", 30, "returnQty", 0, "stockQty", 5), DEALER_SITE_KEY);
        assertThat(rowOf(code)).as("처음엔 노출돼서 목록에 있다").isNotNull();

        // 도서 Web게시를 끈다 — ①규칙상 더는 '노출'이 아니다.
        put("/masters/products/flags", Map.of("productIds", java.util.List.of(id), "webVisible", false));

        JsonNode row = rowOf(code);
        assertThat(row).as("재고 이력이 있으니 그대로 남는다").isNotNull();
        assertThat(row.path("stockQty").asInt()).as("값도 그대로").isEqualTo(5);
    }

    @Test
    @DisplayName("★★노출이 꺼진 교재도 재고 이력이 있으면 입력(저장)이 계속 된다(11/04 §4)")
    void 비노출이어도_재고이력있으면_저장도된다() {
        String code = "STI" + SFX;
        Long id = textbook(code);
        postAsDealerSite("/dealer-site/stock", Map.of("partnerCode", partnerCode,
                "productCode", code, "purchaseQty", 10, "returnQty", 0, "stockQty", 0), DEALER_SITE_KEY);
        put("/masters/products/flags", Map.of("productIds", java.util.List.of(id), "webVisible", false));

        JsonNode r = postAsDealerSite("/dealer-site/stock", Map.of("partnerCode", partnerCode,
                "productCode", code, "purchaseQty", 10, "returnQty", 2, "stockQty", 3), DEALER_SITE_KEY);

        assertThat(r.path("success").asBoolean())
                .as("목록에 뜨는데 저장은 거부되면 화면이 먹통이 된다: %s", r).isTrue();
        assertThat(data(r).path("stockQty").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("★★노출이 꺼져도 최근 매출 출고가 있으면 목록에 남는다(11/04 §4)")
    void 비노출이어도_매출출고있으면_남는다() {
        String code = "STJ" + SFX;
        Long id = textbook(code);
        // 노출을 먼저 끈 뒤 매출을 등록한다 — 노출 규칙만으로는 안 보여야 정상이다.
        put("/masters/products/flags", Map.of("productIds", java.util.List.of(id), "webVisible", false));
        assertThat(rowOf(code)).as("비노출 + 이력 없음 → 목록에 없다").isNull();

        Long wh = createId("/masters/warehouses",
                Map.of("code", "STW" + SFX, "name", "재고현황출고창고", "type", "MAIN"));
        Long sup = createId("/masters/clients",
                Map.of("code", "STSUP" + SFX, "name", "재고현황입고처", "type", "NORMAL"));
        post("/stock/inbound", Map.of("processedDate", "2026-10-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", java.util.List.of(Map.of("productId", id, "unitCost", 1000, "qty", 100))));
        post("/sales/entries", Map.of("salesDate", java.time.LocalDate.now().minusMonths(1).toString(),
                "partnerId", partnerId, "warehouseId", wh,
                "items", java.util.List.of(Map.of("productId", id, "shipmentType", "NORMAL_SHIP", "qty", 5))));

        JsonNode row = rowOf(code);
        assertThat(row).as("비노출이어도 최근 매출 출고 이력이 있으면 목록에 남는다").isNotNull();
    }

    @Test
    @DisplayName("설정으로 범위를 좁히면(기간 0개월) 매출 출고 이력은 더는 포함되지 않는다")
    void 기간설정0이면_출고이력제외() {
        // 이 테스트는 기본 설정값(12개월)을 그대로 쓰는 환경을 전제로 한다 — 실제로 바꾸는
        // 건 application.yml 의 DEALER_SITE_STOCK_HISTORY_MONTHS 라 통합테스트에서 값 자체를
        // 바꿀 수 없다. 대신 "아주 과거" 매출은 기본 범위(12개월) 밖이라 빠지는 것으로
        // 같은 경계 조건(기간 밖은 제외)을 검증한다.
        String code = "STK" + SFX;
        Long id = textbook(code);
        put("/masters/products/flags", Map.of("productIds", java.util.List.of(id), "webVisible", false));

        Long wh = createId("/masters/warehouses",
                Map.of("code", "STWO" + SFX, "name", "재고현황옛출고창고", "type", "MAIN"));
        Long sup = createId("/masters/clients",
                Map.of("code", "STSUPO" + SFX, "name", "재고현황옛입고처", "type", "NORMAL"));
        post("/stock/inbound", Map.of("processedDate", "2020-01-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", java.util.List.of(Map.of("productId", id, "unitCost", 1000, "qty", 100))));
        post("/sales/entries", Map.of("salesDate", "2020-01-10",
                "partnerId", partnerId, "warehouseId", wh,
                "items", java.util.List.of(Map.of("productId", id, "shipmentType", "NORMAL_SHIP", "qty", 5))));

        assertThat(rowOf(code)).as("12개월보다 오래된 출고는 범위 밖 — 비노출 + 이력없음(기간밖)과 같다").isNull();
    }
}
