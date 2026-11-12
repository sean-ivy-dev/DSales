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
 * 특약점 재고현황(10/31 클라이언트 회신 §3, 10/26 §6 확정 + <b>11/05 §2 목록 범위 확정</b>).
 *
 * <pre>
 *   로직   현행 그대로 — 구매·반품·재고 수기 입력, 출고 = 구매−재고−반품(계산만)
 *   대상   교재만
 *   범위   (노출되는 교재) ∪ (그 특약점에 재고 이력이 있는 교재) ∪ (기간 무제한 매출 출고가 있는 교재)
 *         단, 사용여부(useYn) N인 도서는 제외
 * </pre>
 *
 * <p>★<b>11/05 §2</b> — 노출이 꺼진 뒤에도 특약점이 이미 입력한 재고 숫자가 화면에서
 * 사라지면 안 된다("이미 입력한 값을 잃어버린 것처럼 보인다"). 11/04 §4의 "최근 12개월"은
 * 11/05 회신으로 <b>기간 무제한</b>으로 바뀌었다. '출고' 기준은 매출만(DSRE 출고 제외,
 * 사용자 확정, DSRE off 환경 의존 방지) — {@code daesung.dealer-site.stock-history-source}.
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

    /** 특약점 재고현황 교재로 지정한 교재(모든 특약점 공통 목록, PM 2026-11-12). */
    private Long textbook(String code) {
        Long id = createId("/masters/products", Map.of("code", code, "name", code + "교재",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", "A2093A", "catName", "재고현황분류",
                "webVisible", true));
        listed(id, true);
        return id;
    }

    private Long mockExam(String code) {
        Long id = createId("/masters/products", Map.of("code", code, "name", code + "모의고사",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "모의고사", "catCode", "B2093A", "catName", "모의고사분류",
                "webVisible", true));
        listed(id, true);
        return id;
    }

    private void listed(Long productId, boolean on) {
        put("/masters/products/flags", Map.of("productIds", java.util.List.of(productId), "dealerStockListed", on));
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
    @DisplayName("★★[관리자] 이관 베이스라인 시딩은 노출 판정을 보지 않는다(11/05 §4) — "
            + "비노출 도서도 성공하고, 그 뒤 목록에 이력으로 나타난다")
    void 관리자_베이스라인시딩은_비노출도서도_성공() {
        String code = "STN" + SFX;
        createId("/masters/products", Map.of("code", code, "name", "비노출교재2",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", "A2093A", "catName", "재고현황분류",
                "webVisible", false));   // Web게시 N — /dealer-site/stock 이면 거부될 조건

        // 일반 특약점 경로는 거부된다(위 비노출도서_거부 와 같은 전제 재확인).
        JsonNode viaDealerSite = postAsDealerSite("/dealer-site/stock", Map.of("partnerCode", partnerCode,
                "productCode", code, "purchaseQty", 1, "returnQty", 0, "stockQty", 0), DEALER_SITE_KEY);
        assertThat(viaDealerSite.path("success").asBoolean()).isFalse();

        // 관리자 이관 베이스라인 시딩은 같은 비노출 도서도 성공한다.
        JsonNode seedResult = post("/masters/partner-product-stock/seed-baseline",
                Map.of("items", java.util.List.of(Map.of(
                        "partnerCode", partnerCode, "productCode", code,
                        "purchaseQty", 100, "returnQty", 8, "stockQty", 12))));
        assertThat(seedResult.path("success").asBoolean()).as("%s", seedResult).isTrue();
        assertThat(data(seedResult).path("requested").asInt()).isEqualTo(1);
        assertThat(data(seedResult).path("succeeded").asInt()).isEqualTo(1);
        assertThat(data(seedResult).path("failures")).isEmpty();

        // 시딩 후엔 재고 이력이 생겨 ①규칙으로 목록에 나타난다(값도 그대로).
        JsonNode row = rowOf(code);
        assertThat(row).as("비노출이어도 이관 이력이 생기면 목록에 남는다").isNotNull();
        assertThat(row.path("purchaseQty").asInt()).isEqualTo(100);
        assertThat(row.path("returnQty").asInt()).isEqualTo(8);
        assertThat(row.path("stockQty").asInt()).isEqualTo(12);
    }

    @Test
    @DisplayName("[관리자] 이관 베이스라인 시딩 — 거래처를 못 찾으면 그 건만 실패로 모으고 나머지는 진행")
    void 관리자_베이스라인시딩_일부실패는_나머지진행() {
        String okCode = "STO" + SFX;
        textbook(okCode);

        JsonNode seedResult = post("/masters/partner-product-stock/seed-baseline",
                Map.of("items", java.util.List.of(
                        Map.of("partnerCode", "없는거래처" + SFX, "productCode", okCode,
                                "purchaseQty", 1, "returnQty", 0, "stockQty", 0),
                        Map.of("partnerCode", partnerCode, "productCode", okCode,
                                "purchaseQty", 20, "returnQty", 0, "stockQty", 0))));

        assertThat(seedResult.path("success").asBoolean()).as("%s", seedResult).isTrue();
        assertThat(data(seedResult).path("requested").asInt()).isEqualTo(2);
        assertThat(data(seedResult).path("succeeded").asInt()).isEqualTo(1);
        assertThat(data(seedResult).path("failures")).hasSize(1);
        assertThat(rowOf(okCode).path("purchaseQty").asInt()).isEqualTo(20);
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

        // 재고현황 교재 지정을 끈다 — 더는 공통 목록이 아니다.
        listed(id, false);

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
        listed(id, false);

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
        listed(id, false);
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
    @DisplayName("★★기간 무제한(11/05 §2) — 아주 오래된 매출 출고도 목록에 남는다")
    void 기간무제한_오래된출고도_포함() {
        String code = "STK" + SFX;
        Long id = textbook(code);
        listed(id, false);
        assertThat(rowOf(code)).as("비노출 + 이력 없음 → 아직 목록에 없다").isNull();

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

        assertThat(rowOf(code))
                .as("11/05 §2 — 기간 제한이 없어졌으니 2020년 출고도 목록에 남아야 한다").isNotNull();
    }

    @Test
    @DisplayName("★★사용여부(useYn) N인 도서는 이력이 있어도 목록에서 빠진다(11/05 §2)")
    void 사용여부N이면_이력있어도_제외() {
        String stockCode = "STL" + SFX;
        Long stockId = textbook(stockCode);
        postAsDealerSite("/dealer-site/stock", Map.of("partnerCode", partnerCode,
                "productCode", stockCode, "purchaseQty", 30, "returnQty", 0, "stockQty", 5), DEALER_SITE_KEY);
        assertThat(rowOf(stockCode)).as("사용여부 끄기 전엔 재고 이력으로 목록에 있다").isNotNull();

        String saleCode = "STM" + SFX;
        Long saleId = textbook(saleCode);
        listed(saleId, false);
        Long wh = createId("/masters/warehouses",
                Map.of("code", "STWN" + SFX, "name", "재고현황사용여부창고", "type", "MAIN"));
        Long sup = createId("/masters/clients",
                Map.of("code", "STSUPN" + SFX, "name", "재고현황사용여부입고처", "type", "NORMAL"));
        post("/stock/inbound", Map.of("processedDate", "2026-01-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", java.util.List.of(Map.of("productId", saleId, "unitCost", 1000, "qty", 100))));
        post("/sales/entries", Map.of("salesDate", "2026-01-10",
                "partnerId", partnerId, "warehouseId", wh,
                "items", java.util.List.of(Map.of("productId", saleId, "shipmentType", "NORMAL_SHIP", "qty", 5))));
        assertThat(rowOf(saleCode)).as("사용여부 끄기 전엔 매출 출고 이력으로 목록에 있다").isNotNull();

        // 두 도서 모두 사용여부를 끈다 — 이력이 있어도 이제는 빠져야 한다.
        put("/masters/products/flags", Map.of("productIds", java.util.List.of(stockId, saleId), "useYn", false));

        assertThat(rowOf(stockCode)).as("재고 이력이 있어도 사용여부 N이면 제외").isNull();
        assertThat(rowOf(saleCode)).as("매출 출고 이력이 있어도 사용여부 N이면 제외").isNull();
    }

    @Test
    @DisplayName("★★★모든 특약점에 같은 목록 — 신청 노출 설정이 없는 특약점에도 지정 교재가 보이고 입력된다(PM 11/12)")
    void 노출설정없는_특약점도_지정교재가_보인다() {
        // 거래처×대분류 노출 설정을 하나도 하지 않은 특약점 — 예전엔 재고 기록이 없으면 교재가 0종이었다.
        String bare = "STQ" + SFX;
        createId("/masters/clients", Map.of("code", bare, "name", "설정없는특약점", "type", "NORMAL"));
        String code = "STR" + SFX;
        textbook(code);

        boolean seen = false;
        for (JsonNode r : getAsDealerSite("/dealer-site/stock?partnerCode=" + bare, DEALER_SITE_KEY).path("data")) {
            seen |= code.equals(r.path("productCode").asText());
        }
        assertThat(seen).as("재고 기록이 없어도 지정 교재는 보여야 한다 — 그래야 새 교재 재고를 입력한다").isTrue();

        JsonNode r = postAsDealerSite("/dealer-site/stock", Map.of("partnerCode", bare,
                "productCode", code, "purchaseQty", 0, "returnQty", 0, "stockQty", 4), DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("입력도 된다: %s", r).isTrue();
    }

    @Test
    @DisplayName("★★★신청 노출(Web게시+거래처 설정)과 별개 축 — 지정 안 한 교재는 노출 설정이 켜져 있어도 안 보인다(PM 11/12)")
    void 신청노출과_별개축() {
        // 이 클래스의 특약점은 교재 대분류 노출이 켜져 있다 = 신청 화면엔 보이는 교재.
        String code = "STU" + SFX;
        Long id = textbook(code);
        listed(id, false);
        assertThat(rowOf(code)).as("신청 화면엔 보여도 재고현황 교재가 아니면 안 보인다 — 85종이 다 뜨던 원인").isNull();

        // 반대로 Web게시를 꺼도 지정 교재면 재고현황엔 남는다(신청 화면 기준과 섞지 않는다).
        String code2 = "STV" + SFX;
        Long id2 = textbook(code2);
        put("/masters/products/flags", Map.of("productIds", java.util.List.of(id2), "webVisible", false));
        assertThat(rowOf(code2)).as("Web게시는 신청 화면 기준이다 — 재고현황 목록과 무관").isNotNull();
    }
}
