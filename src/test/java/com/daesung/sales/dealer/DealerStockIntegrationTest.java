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
 * 특약점 재고현황(10/31 클라이언트 회신 §3, 10/26 §6 확정).
 *
 * <pre>
 *   로직   현행 그대로 — 구매·반품·재고 수기 입력, 출고 = 구매−재고−반품(계산만)
 *   대상   교재만
 *   범위   그 거래처에 노출되는 교재만(§1 노출 규칙 재사용)
 * </pre>
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
}
