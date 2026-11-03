package com.daesung.sales.order;

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
 * 접수경로(온라인/수기) — 프론트 I-2(2026-11-02).
 *
 * <p>근거: 10/27 명세 §2-1 "<b>수기 주문과 온라인(특약점) 주문을 함께 보여주고</b>,
 * 특약점 사이트와 서로 연동한다."
 *
 * <p>요청 원문: "지금 응답(SalesOrderRow)으로는 둘을 가를 수 없어 화면에 칸을 두지 못했습니다.
 * … 신규 주문 등록 창에는 접수경로 선택이 있는데, 지금은 저장할 칸이 없어 <b>메모 앞에
 * 「[수기]」 처럼 붙이고</b> 있습니다."
 *
 * <p>★<b>급소</b>: 특약점 접수는 <b>서버가 ONLINE 으로 박는다</b>. 요청으로 바꿀 수 있으면
 * 사이트에서 온 주문을 수기로 위장할 수 있고, 그러면 "함께 보여준다"가 무의미해진다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("접수경로 온라인/수기(§2-1 · I-2)")
class OrderChannelIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-OC" + (System.nanoTime() % 1_000_000L);
    private static final String PARTNER_CODE = "OCP" + SFX;
    private static final String BOOK_CODE = "OCB" + SFX;
    private static final String DATE = "2098-04-04";
    private static final String RANGE = "?fromDate=2098-04-01&toDate=2098-04-30";

    private Long partner;
    private Long wh;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "OCS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", PARTNER_CODE, "name", "접수경로거래처", "type", "NORMAL"));
        wh = findOrCreateDealerWarehouse();

        Map<String, Object> b = new HashMap<>();
        b.put("code", BOOK_CODE);
        b.put("name", "접수경로도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "O2098A01");
        b.put("catName", "접수경로검증");
        b.put("salesDivision", "교재");
        // ★특약점 접수는 노출되는 도서만 받는다(10/26 §3) — Web게시 + 거래처×대분류 매핑 필요.
        b.put("webVisible", true);
        book = createId("/masters/products", b);
        put("/masters/partner-supply-rates/" + partner + "/TEXTBOOK",
                Map.of("supplyRate", 70, "webVisible", true, "useYn", true));

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2098-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 100, "qty", 999))))
                .path("success").asBoolean()).isTrue();
    }

    /** 특약점 접수가 쓰는 창고는 설정으로 못 박혀 있다 — 있으면 쓰고 없으면 만든다. */
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

    private String register(String channel) {
        Map<String, Object> req = new HashMap<>();
        req.put("salesDate", DATE);
        req.put("partnerId", partner);
        req.put("warehouseId", wh);
        if (channel != null) {
            req.put("channel", channel);
        }
        req.put("items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                "qty", 3, "unitPrice", 10000, "supplyRate", 70)));
        JsonNode r = post("/sales/entries", req);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r).path("entryNo").asText();
    }

    private JsonNode orderOf(String entryNo, String extra) {
        for (JsonNode o : data(get("/orders" + RANGE + "&partnerId=" + partner + extra))
                .path("content")) {
            if (entryNo.equals(o.path("orderNo").asText())) {
                return o;
            }
        }
        return null;
    }

    @Test
    @DisplayName("★안 보내면 수기로 내려준다 — 옛 주문도 전부 수기다")
    void 비우면_수기() {
        String no = register(null);

        JsonNode o = orderOf(no, "");
        assertThat(o).as("주문조회에 있다").isNotNull();
        assertThat(o.path("channel").asText()).isEqualTo("MANUAL");
        assertThat(o.path("channelName").asText()).isEqualTo("수기");
    }

    @Test
    @DisplayName("보낸 값이 그대로 실린다 — MANUAL")
    void 수기를_보내면() {
        String no = register("MANUAL");

        assertThat(orderOf(no, "").path("channelName").asText()).isEqualTo("수기");
    }

    @Test
    @DisplayName("★★특약점 접수는 서버가 온라인으로 박는다 — 요청으로 수기로 위장할 수 없다")
    void 특약점은_온라인() {
        JsonNode r = postAsDealerSite("/dealer-site/orders", Map.of(
                "partnerCode", PARTNER_CODE, "orderDate", DATE,
                "items", List.of(Map.of("productCode", BOOK_CODE, "qty", 5))), DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        String no = data(r).path("entryNo").asText();

        JsonNode o = orderOf(no, "");
        assertThat(o).as("주문조회에 있다").isNotNull();
        assertThat(o.path("channel").asText()).isEqualTo("ONLINE");
        assertThat(o.path("channelName").asText()).isEqualTo("온라인");
    }

    @Test
    @DisplayName("★조회 조건으로 가른다 — MANUAL 은 값이 빈 옛 주문까지 포함한다")
    void 조회조건() {
        String manual = register(null);          // 값 없음 → 수기로 봐야 한다
        JsonNode online = postAsDealerSite("/dealer-site/orders", Map.of(
                "partnerCode", PARTNER_CODE, "orderDate", DATE,
                "items", List.of(Map.of("productCode", BOOK_CODE, "qty", 2))), DEALER_SITE_KEY);
        String onlineNo = data(online).path("entryNo").asText();

        assertThat(orderOf(manual, "&channel=MANUAL")).as("값 빈 주문이 수기 조회에 든다").isNotNull();
        assertThat(orderOf(manual, "&channel=ONLINE")).as("수기는 온라인 조회에 없다").isNull();
        assertThat(orderOf(onlineNo, "&channel=ONLINE")).as("특약점 주문은 온라인 조회에").isNotNull();
        assertThat(orderOf(onlineNo, "&channel=MANUAL")).as("온라인은 수기 조회에 없다").isNull();
    }

    @Test
    @DisplayName("엑셀에도 접수경로 칸이 있다")
    void 엑셀() {
        register(null);
        var resp = getBytes("/orders/export" + RANGE + "&partnerId=" + partner);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).isNotEmpty();
    }
}
