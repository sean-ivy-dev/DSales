package com.daesung.sales.dealer;

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
 * 특약점 사이트의 <b>본인 주문 조회·수정·삭제</b>(10/26 §2).
 *
 * <p>근거: 10/27 §1 — "진행상태 제한·삭제 표시(§2)는 <b>새 탭 주문에 적용</b>한다."
 * 10/26 §2-3 — "<b>A(접수완료)에서만</b> 수정·삭제할 수 있다. G~C는 조회만 된다."
 * §2-4 — "주문 건의 수정 및 취소를 원하시는 경우 담당자에게 확인 바랍니다."
 *
 * <p>★<b>이 테스트가 빠뜨리면 안 되는 것</b> — 남의 거래처 주문을 건드릴 수 없는지,
 * 그리고 그 거부 메시지가 <b>상태가 달라 거부한 것과 같은 문구</b>인지(남의 주문이
 * 존재한다는 사실을 알려주면 안 된다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("특약점 본인 주문 조회·수정·삭제(§2)")
class DealerOrderSelfServiceIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DX" + (System.nanoTime() % 1_000_000L);
    private static final String MINE = "DXA" + SFX;
    private static final String OTHER = "DXB" + SFX;
    private static final String BOOK_CODE = "DXB" + SFX;
    private static final String DATE = "2097-06-06";
    private static final String ALERT = "주문 건의 수정 및 취소를 원하시는 경우 담당자에게 확인 바랍니다.";

    private Long wh;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "DXS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        Long mineId = createId("/masters/clients",
                Map.of("code", MINE, "name", "본인거래처", "type", "NORMAL"));
        Long otherId = createId("/masters/clients",
                Map.of("code", OTHER, "name", "남의거래처", "type", "NORMAL"));
        wh = findOrCreateDealerWarehouse();

        Map<String, Object> b = new HashMap<>();
        b.put("code", BOOK_CODE);
        b.put("name", "본인주문도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "X2097A01");
        b.put("catName", "본인주문검증");
        b.put("salesDivision", "교재");
        // ★노출 조건(10/26 §3)을 깔아 둔다 — 없으면 접수가 PRODUCT_NOT_VISIBLE 로 막힌다.
        b.put("webVisible", true);
        book = createId("/masters/products", b);
        for (Long pid : List.of(mineId, otherId)) {
            put("/masters/partner-supply-rates/" + pid + "/TEXTBOOK",
                    Map.of("supplyRate", 70, "webVisible", true, "useYn", true));
        }

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2097-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 100, "qty", 999))))
                .path("success").asBoolean()).isTrue();
    }

    private Long findOrCreateDealerWarehouse() {
        for (JsonNode w : data(get("/masters/warehouses?keyword=" + DEALER_WAREHOUSE_CODE))
                .path("content")) {
            if (DEALER_WAREHOUSE_CODE.equals(w.path("code").asText())) {
                return w.path("id").asLong();
            }
        }
        return createId("/masters/warehouses", Map.of(
                "code", DEALER_WAREHOUSE_CODE, "name", "특약점주문 물류창고", "type", "MAIN"));
    }

    private Map<String, Object> order(String partnerCode, int qty) {
        Map<String, Object> req = new HashMap<>();
        req.put("partnerCode", partnerCode);
        req.put("orderDate", DATE);
        req.put("items", List.of(Map.of("productCode", BOOK_CODE, "qty", qty)));
        return req;
    }

    private String register(String partnerCode, int qty) {
        JsonNode r = postAsDealerSite("/dealer-site/orders", order(partnerCode, qty), DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r).path("entryNo").asText();
    }

    @Test
    @DisplayName("★내 주문 목록 — 남의 거래처 주문은 안 섞인다")
    void 목록은_본인것만() {
        String mine = register(MINE, 3);
        register(OTHER, 5);

        JsonNode r = getAsDealerSite("/dealer-site/orders?partnerCode=" + MINE
                + "&fromDate=2097-01-01&toDate=2097-12-31", DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        List<String> nos = new java.util.ArrayList<>();
        for (JsonNode o : data(r).path("content")) {
            nos.add(o.path("orderNo").asText());
        }
        assertThat(nos).contains(mine);
        for (JsonNode o : data(r).path("content")) {
            assertThat(o.path("partnerCode").asText()).isEqualTo(MINE);
        }
    }

    @Test
    @DisplayName("★접수완료면 본인이 수정할 수 있다")
    void 접수완료면_수정() {
        String no = register(MINE, 3);

        JsonNode r = putAsDealerSite("/dealer-site/orders/" + no, order(MINE, 9), DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("items").get(0).path("qty").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("★★상품준비중이 되면 본인도 수정·삭제를 못 한다 — §2-4 문구 그대로")
    void 접수완료아니면_거부() {
        String no = register(MINE, 3);
        assertThat(post("/orders/progress-state", Map.of(
                "orderNos", List.of(no), "toState", "PREPARING")).path("success").asBoolean()).isTrue();

        JsonNode editR = putAsDealerSite("/dealer-site/orders/" + no, order(MINE, 9), DEALER_SITE_KEY);
        assertThat(editR.path("success").asBoolean()).isFalse();
        assertThat(editR.path("error").path("message").asText()).isEqualTo(ALERT);

        JsonNode delR = deleteAsDealerSite("/dealer-site/orders/" + no + "?partnerCode=" + MINE,
                DEALER_SITE_KEY);
        assertThat(delR.path("success").asBoolean()).isFalse();
        assertThat(delR.path("error").path("message").asText()).isEqualTo(ALERT);
    }

    @Test
    @DisplayName("★★★남의 거래처 주문은 수정·삭제 못 한다 — 상태 때문에 막힌 것과 같은 문구")
    void 남의주문은_같은문구로_거부() {
        String otherNo = register(OTHER, 3);   // 접수완료 상태(막 등록해서)

        JsonNode editR = putAsDealerSite("/dealer-site/orders/" + otherNo, order(MINE, 9),
                DEALER_SITE_KEY);
        assertThat(editR.path("success").asBoolean()).isFalse();
        assertThat(editR.path("error").path("message").asText())
                .as("남의 주문이라는 걸 알려주면 안 된다 — 상태 거부와 같은 문구")
                .isEqualTo(ALERT);

        JsonNode delR = deleteAsDealerSite("/dealer-site/orders/" + otherNo + "?partnerCode=" + MINE,
                DEALER_SITE_KEY);
        assertThat(delR.path("success").asBoolean()).isFalse();
        assertThat(delR.path("error").path("message").asText()).isEqualTo(ALERT);

        // 실제로 안 지워졌다 — 원래 주인으로 조회하면 그대로 있다.
        JsonNode stillThere = getAsDealerSite("/dealer-site/orders?partnerCode=" + OTHER
                + "&fromDate=2097-01-01&toDate=2097-12-31", DEALER_SITE_KEY);
        boolean found = false;
        for (JsonNode o : data(stillThere).path("content")) {
            if (otherNo.equals(o.path("orderNo").asText())) {
                found = true;
            }
        }
        assertThat(found).as("실제로는 안 지워졌다").isTrue();
    }

    @Test
    @DisplayName("★접수완료면 본인이 삭제할 수 있다")
    void 접수완료면_삭제() {
        String no = register(MINE, 3);

        JsonNode r = deleteAsDealerSite("/dealer-site/orders/" + no + "?partnerCode=" + MINE,
                DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    @Test
    @DisplayName("키 없으면 목록·수정·삭제 전부 401")
    void 키없으면_거부() {
        String no = register(MINE, 3);
        assertThat(getAsDealerSite("/dealer-site/orders?partnerCode=" + MINE, null)
                .path("success").asBoolean()).isFalse();
        assertThat(putAsDealerSite("/dealer-site/orders/" + no, order(MINE, 1), null)
                .path("success").asBoolean()).isFalse();
        assertThat(deleteAsDealerSite("/dealer-site/orders/" + no + "?partnerCode=" + MINE, null)
                .path("success").asBoolean()).isFalse();
    }
}
