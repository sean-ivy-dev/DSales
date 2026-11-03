package com.daesung.sales.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 특약점 사이트용 <b>송장 조회(10/26 §4)</b>·<b>거래명세서(§5)</b>.
 *
 * <p>§4 — "표시 조건: 배송구분 <b>택배</b> AND 진행상태 <b>발송완료(D)</b>."
 * §5 — "<b>조회·출력 전용</b>. 표시 대상: 매출에서 <b>발송완료(D)</b> 처리된 건부터.
 * 발송준비중까지는 내부에서 수정될 수 있으므로 표시하지 않는다. … 권한: <b>로그인한 거래처
 * 본인 건만</b>. URL이나 파라미터로 받은 거래처코드는 믿지 않는다(Phase 0 order IDOR과 같은 유형)."
 *
 * <p>★<b>급소는 권한이다.</b> 남의 주문번호를 넣었을 때 "권한 없음"이라고 답하면 그 주문이
 * 있다는 사실을 알려준다 — <b>없음과 같은 답</b>이어야 한다(PDF 보관함과 같은 규칙).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("특약점 사이트 송장·거래명세서(§4·§5)")
class DealerStatementIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DS" + (System.nanoTime() % 1_000_000L);
    private static final String MINE = "DSA" + SFX;
    private static final String OTHER = "DSB" + SFX;
    private static final String DATE = "2096-09-09";
    private static final String RANGE = "?fromDate=2096-09-01&toDate=2096-09-30";

    private Long partner;
    private Long otherPartner;
    private Long wh;
    private Long book;
    private String shippedNo;      // 발송완료 + 택배
    private String preparingNo;    // 아직 상품준비중

    @Autowired
    private ShipmentRepository shipmentRepository;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "DSS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", MINE, "name", "내거래처", "type", "NORMAL"));
        otherPartner = createId("/masters/clients",
                Map.of("code", OTHER, "name", "남의거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "DSW" + SFX, "name", "물류창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "DSB" + SFX);
        b.put("name", "명세서도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "D2096A01");
        b.put("catName", "명세서검증");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2096-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 100, "qty", 999))))
                .path("success").asBoolean()).isTrue();

        // ① 발송완료 + 택배 + 송장 2박스
        shippedNo = register(partner, "S001", "독재자학원");
        for (Shipment s : shipmentRepository.findByEntryNo(shippedNo)) {
            // 발송구분·수령인·박스수·발송일이 한 요청이다(`ShippingUpdateRequest`).
            assertThat(put("/logistics/work-orders/" + s.getId() + "/shipping",
                    Map.of("deliveryType", "COURIER", "receiverName", "홍길동",
                            "receiverPhone", "010-0000-0000")).path("success").asBoolean())
                    .as("택배로 지정").isTrue();
            assertThat(put("/logistics/work-orders/" + s.getId() + "/trackings",
                    Map.of("trackings", List.of(
                            Map.of("boxNo", 1, "courierName", "CJ대한통운", "trackingNo", "111111111111"),
                            Map.of("boxNo", 2, "courierName", "CJ대한통운", "trackingNo", "222222222222"))))
                    .path("success").asBoolean()).as("송장 2박스").isTrue();
            assertThat(put("/logistics/work-orders/" + s.getId() + "/shipping",
                    Map.of("boxCount", 2, "sentDate", DATE)).path("success").asBoolean())
                    .as("발송완료").isTrue();
        }

        // ② 아직 상품준비중(발송 안 함)
        preparingNo = register(partner, "S002", "준비중학원");
    }

    private String register(Long partnerId, String schoolCode, String schoolName) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", book);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", 5);
        item.put("schoolCode", schoolCode);
        item.put("schoolName", schoolName);
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partnerId, "warehouseId", wh,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r).path("entryNo").asText();
    }

    private JsonNode siteGet(String path, String key) {
        return getAsDealerSite(path, key);
    }

    @Test
    @DisplayName("키가 없으면 401 — 이 경로도 열려 있지 않다")
    void 키없으면_거부() {
        JsonNode r = siteGet("/dealer-site/orders/shipped?partnerCode=" + MINE, null);
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("★발송완료 건만 나온다 — 상품준비중은 안 보인다(§5)")
    void 발송완료만_보인다() {
        JsonNode r = siteGet("/dealer-site/orders/shipped?partnerCode=" + MINE
                + "&fromDate=2096-09-01&toDate=2096-09-30", DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        List<String> nos = new java.util.ArrayList<>();
        for (JsonNode o : data(r)) {
            nos.add(o.path("orderNo").asText());
        }
        assertThat(nos).as("발송완료 건은 있다").contains(shippedNo);
        assertThat(nos).as("상품준비중은 없다 — 내부에서 아직 바뀔 수 있다")
                .doesNotContain(preparingNo);
    }

    @Test
    @DisplayName("★송장이 박스 수만큼 실린다 — 택배 필터(§4 표시 조건)")
    void 송장_박스별() {
        JsonNode r = siteGet("/dealer-site/orders/shipped?partnerCode=" + MINE
                + "&fromDate=2096-09-01&toDate=2096-09-30&deliveryType=COURIER", DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        JsonNode mine = null;
        for (JsonNode o : data(r)) {
            if (shippedNo.equals(o.path("orderNo").asText())) {
                mine = o;
            }
        }
        assertThat(mine).as("택배 필터에 들어온다: %s", data(r)).isNotNull();
        assertThat(mine.path("deliveryType").asText()).isEqualTo("COURIER");
        assertThat(mine.path("trackings")).hasSize(2);
        assertThat(mine.path("trackings").get(0).path("boxNo").asInt()).isEqualTo(1);
        assertThat(mine.path("trackings").get(1).path("trackingNo").asText())
                .isEqualTo("222222222222");
    }

    @Test
    @DisplayName("화물로 걸러 부르면 택배 건은 안 나온다")
    void 화물필터() {
        JsonNode r = siteGet("/dealer-site/orders/shipped?partnerCode=" + MINE
                + "&fromDate=2096-09-01&toDate=2096-09-30&deliveryType=FREIGHT", DEALER_SITE_KEY);

        for (JsonNode o : data(r)) {
            assertThat(o.path("orderNo").asText()).isNotEqualTo(shippedNo);
        }
    }

    @Test
    @DisplayName("★거래명세서 — 발송완료 건은 기존 양식 그대로 나온다(§5 재사용)")
    void 거래명세서() {
        JsonNode r = siteGet("/dealer-site/orders/" + shippedNo + "/statement?partnerCode=" + MINE,
                DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        JsonNode d = data(r);
        assertThat(d.path("provider").path("name").asText()).as("공급자(자사)").isNotBlank();
        assertThat(d.path("receiver").path("name").asText()).as("공급받는자").contains("내거래처");
        // 유가/무가가 나뉘어 온다 — 백오피스 명세서와 **같은 양식**이다(§5 "기존 양식 재사용").
        assertThat(d.path("pricedLines")).as("유가 줄: %s", d).isNotEmpty();
        assertThat(d.path("schoolName").asText()).as("학교(원)명").isEqualTo("독재자학원");
        assertThat(d.path("totals").path("freeQty").isMissingNode()).as("무가 수량 칸이 있다").isFalse();
    }

    @Test
    @DisplayName("★★발송완료가 아니면 거부한다 — 발송준비중까지는 내부에서 바뀔 수 있다(§5)")
    void 발송전_명세서는_거부() {
        JsonNode r = siteGet("/dealer-site/orders/" + preparingNo + "/statement?partnerCode=" + MINE,
                DEALER_SITE_KEY);

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("발송완료된 주문부터");
    }

    @Test
    @DisplayName("★★남의 주문은 '없음'으로 답한다 — '권한 없음'이면 그 주문이 있다는 걸 알려준다")
    void 남의주문은_없음() {
        JsonNode r = siteGet("/dealer-site/orders/" + shippedNo + "/statement?partnerCode=" + OTHER,
                DEALER_SITE_KEY);

        assertThat(r.path("success").asBoolean()).isFalse();
        // 존재 자체를 숨긴다 — 실제로 있는 주문인데 '없음'과 같은 답이다.
        assertThat(r.path("error").path("message").asText()).contains("찾을 수 없습니다");
        assertThat(r.path("error").path("message").asText())
                .as("권한 얘기를 꺼내면 존재가 드러난다").doesNotContain("권한");
    }

    @Test
    @DisplayName("★거래처를 못 찾으면 PARTNER_NOT_LINKED — 특약점팀 전달(2026-11-03 확인 a)")
    void 거래처미연결_전용코드() {
        JsonNode r = siteGet("/dealer-site/orders/shipped?partnerCode=없는코드" + SFX
                + "&fromDate=2096-09-01&toDate=2096-09-30", DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText())
                .as("다른 /dealer-site/** 경로와 같은 코드여야 한다").isEqualTo("PARTNER_NOT_LINKED");
    }

    @Test
    @DisplayName("남의 거래처로 목록을 부르면 내 주문이 안 섞인다")
    void 남의_목록에는_없다() {
        JsonNode r = siteGet("/dealer-site/orders/shipped?partnerCode=" + OTHER
                + "&fromDate=2096-09-01&toDate=2096-09-30", DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        for (JsonNode o : data(r)) {
            assertThat(o.path("orderNo").asText()).isNotEqualTo(shippedNo);
        }
    }
}
