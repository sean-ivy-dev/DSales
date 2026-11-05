package com.daesung.sales.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 재고현황 매입·반품 자동집계 전환일(11/05 §4, V117) — DB 설정값 CRUD + 실제 전환 동작.
 *
 * <p>‼️<b>이 설정은 싱글톤(전역) 행이다</b> — 공유 Testcontainers DB에서 다른 테스트 클래스와
 * 같은 행을 본다. {@link #resetCutover()}를 {@code @AfterEach}로 걸어 **항상 null로
 * 되돌린다**(성공·실패 상관없이) — 안 그러면 이 테스트가 끝난 뒤 다른 재고현황 테스트가
 * "자동집계가 갑자기 켜진" 상태에서 돌아 전부 깨진다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("재고현황 매입·반품 자동집계 전환일 설정(V117)")
class DealerStockCutoverSettingIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CO" + (System.nanoTime() % 1_000_000L);

    private String partnerCode;
    private Long partnerId;

    @BeforeAll
    void seed() {
        token();
        partnerCode = "COP" + SFX;
        partnerId = createId("/masters/clients",
                Map.of("code", partnerCode, "name", "전환일테스트거래처", "type", "NORMAL"));
        put("/masters/partner-supply-rates/" + partnerId + "/TEXTBOOK",
                Map.of("supplyRate", 70, "webVisible", true, "useYn", true));
    }

    @AfterEach
    void resetCutover() {
        // ‼️Map.of()는 null 값을 못 담아 {} (키 없음)로 직렬화된다 — 명시적으로
        // cutoverDate:null 을 보내야 "지운다"는 의도가 분명하다. 실패하면 바로 터지게
        // 반환값도 확인한다(전역 싱글톤 — 조용히 실패하면 이후 모든 재고현황 테스트가 깨진다).
        // ★cutoverDate가 null이면 응답 직렬화에서 그 필드 자체가 빠진다(NON_NULL) —
        //   .path(..).isNull()은 '없는 필드'에도 false를 주므로 쓰면 안 된다.
        //   항상 찍히는 boolean 필드로 확인한다.
        JsonNode r = put("/masters/partner-product-stock/cutover-date",
                java.util.Collections.singletonMap("cutoverDate", null));
        if (!r.path("success").asBoolean() || data(r).path("autoAggregationEnabled").asBoolean()) {
            throw new IllegalStateException("cutover 설정 초기화 실패(전역 싱글톤 오염 위험): " + r);
        }
    }

    private Long textbook(String code) {
        return createId("/masters/products", Map.of("code", code, "name", code + "교재",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", "A2094A", "catName", "전환일테스트분류",
                "webVisible", true));
    }

    @Test
    @DisplayName("기본값은 전환 전(null) — 자동집계 비활성")
    void 기본값_전환전() {
        JsonNode r = get("/masters/partner-product-stock/cutover-date");
        assertThat(r.path("success").asBoolean()).isTrue();
        assertThat(data(r).has("cutoverDate")).as("null이면 필드 자체가 응답에서 빠진다").isFalse();
        assertThat(data(r).path("autoAggregationEnabled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★전환일을 넣으면 즉시 반영된다(배포 불필요) — PUT 직후 GET에 바로 보인다")
    void 전환일설정_즉시반영() {
        JsonNode put = put("/masters/partner-product-stock/cutover-date",
                Map.of("cutoverDate", "2026-12-01"));
        assertThat(put.path("success").asBoolean()).as("%s", put).isTrue();
        assertThat(data(put).path("cutoverDate").asText()).isEqualTo("2026-12-01");
        assertThat(data(put).path("autoAggregationEnabled").asBoolean()).isTrue();

        JsonNode get = get("/masters/partner-product-stock/cutover-date");
        assertThat(data(get).path("cutoverDate").asText()).isEqualTo("2026-12-01");
    }

    @Test
    @DisplayName("★★전환 후 — 매입·반품 자동집계가 실제로 더해진다, 수기입력은 무시된다")
    void 전환후_자동집계() {
        String code = "COB" + SFX;
        Long productId = textbook(code);

        // 이관 시작값(베이스라인) 시딩.
        put("/masters/partner-product-stock/cutover-date", Map.of("cutoverDate", LocalDate.now().minusDays(10).toString()));

        JsonNode seed = post("/masters/partner-product-stock/seed-baseline",
                Map.of("items", java.util.List.of(Map.of(
                        "partnerCode", partnerCode, "productCode", code,
                        "purchaseQty", 100, "returnQty", 10, "stockQty", 20))));
        assertThat(data(seed).path("succeeded").asInt()).isEqualTo(1);

        // 기준일 이후 정상출고 5건 추가 — 자동집계로 매입에 더해져야 한다.
        Long wh = createId("/masters/warehouses",
                Map.of("code", "COW" + SFX, "name", "전환일창고", "type", "MAIN"));
        Long sup = createId("/masters/clients",
                Map.of("code", "COSUP" + SFX, "name", "전환일입고처", "type", "NORMAL"));
        post("/stock/inbound", Map.of("processedDate", LocalDate.now().toString(), "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", java.util.List.of(Map.of("productId", productId, "unitCost", 1000, "qty", 100))));
        post("/sales/entries", Map.of("salesDate", LocalDate.now().toString(),
                "partnerId", partnerId, "warehouseId", wh,
                "items", java.util.List.of(Map.of("productId", productId, "shipmentType", "NORMAL_SHIP", "qty", 7))));

        JsonNode list = getAsDealerSite("/dealer-site/stock?partnerCode=" + partnerCode, DEALER_SITE_KEY);
        JsonNode row = null;
        for (JsonNode r : list.path("data")) {
            if (code.equals(r.path("productCode").asText())) {
                row = r;
            }
        }
        assertThat(row).as("자동집계 대상 도서가 목록에 있어야 한다").isNotNull();
        assertThat(row.path("purchaseQty").asInt()).as("100(시작값)+7(기준일 이후 정상출고)").isEqualTo(107);
        assertThat(row.path("returnQty").asInt()).as("반품은 그대로 10(추가 반품 없음)").isEqualTo(10);

        // 특약점이 매입을 수기로 고쳐 보내도 cutover 후엔 무시된다(재고만 반영).
        JsonNode saveAttempt = postAsDealerSite("/dealer-site/stock", Map.of(
                "partnerCode", partnerCode, "productCode", code,
                "purchaseQty", 9999, "returnQty", 9999, "stockQty", 30), DEALER_SITE_KEY);
        assertThat(saveAttempt.path("success").asBoolean()).as("%s", saveAttempt).isTrue();
        assertThat(data(saveAttempt).path("purchaseQty").asInt())
                .as("cutover 후엔 수기 매입 입력이 무시된다 — 그대로 107").isEqualTo(107);
        assertThat(data(saveAttempt).path("stockQty").asInt()).as("재고는 수기 입력 그대로 반영").isEqualTo(30);
    }
}
