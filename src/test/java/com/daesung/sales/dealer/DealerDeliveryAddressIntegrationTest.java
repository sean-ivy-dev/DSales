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
 * 특약점 주문의 <b>배송지</b>(요청 2026-11-03 §2)·<b>줄 세부구분·분류명</b>(§4)·
 * <b>수정 때 담당자 유지</b>(확인 d).
 *
 * <p>§2 — "신청 화면은 학교 주소를 미리 채우고 「배송지 정보가 다를 경우 직접 수정」하게
 * 되어 있다. 지금은 받는 칸이 없어 저장되지 않는다. 주문별로 저장해 작업요청서에서 그
 * 배송지로 나가게 해 주시고, 목록에도 실어 주세요."
 *
 * <p>확인 d — "사이트의 [조회/수정]은 수량만 고치고 담당자 칸은 보내지 않습니다.
 * 이때 신청 때 저장된 담당자가 그대로 유지되는지 확인 부탁드립니다."
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("특약점 배송지·담당자 유지(2026-11-03 §2·§4·확인 d)")
class DealerDeliveryAddressIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DA2" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2091-03-03";

    private String partnerCode;
    private String bookCode;

    @BeforeAll
    void seed() {
        token();
        partnerCode = "DA2P" + SFX;
        Long partner = createId("/masters/clients", Map.of(
                "code", partnerCode, "name", "배송지거래처", "type", "NORMAL",
                "bossName", "마스터대표", "tel1", "02-000-0000", "email1", "master@example.com"));
        Long wh = findOrCreateDealerWarehouse();

        bookCode = "DA2B" + SFX;
        createId("/masters/products", Map.of("code", bookCode, "name", "배송지도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", "A2091A", "catName", "배송지분류",
                "webVisible", true));
        put("/masters/partner-supply-rates/" + partner + "/TEXTBOOK",
                Map.of("supplyRate", 70, "webVisible", true, "useYn", true));

        Long sup = createId("/masters/clients",
                Map.of("code", "DA2S" + SFX, "name", "인쇄", "type", "NORMAL"));
        Long bookId = null;
        for (JsonNode p : data(get("/masters/products?keyword=" + bookCode)).path("content")) {
            if (bookCode.equals(p.path("code").asText())) {
                bookId = p.path("id").asLong();
            }
        }
        post("/stock/inbound", Map.of("processedDate", DATE, "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", bookId, "unitCost", 100, "qty", 500))));
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

    private JsonNode myOrder(String entryNo) {
        JsonNode r = getAsDealerSite("/dealer-site/orders?partnerCode=" + partnerCode
                + "&fromDate=" + DATE + "&toDate=" + DATE, DEALER_SITE_KEY);
        for (JsonNode o : data(r).path("content")) {
            if (entryNo.equals(o.path("orderNo").asText())) {
                return o;
            }
        }
        throw new AssertionError("주문 " + entryNo + " 를 목록에서 못 찾음: " + r);
    }

    private String accept(Map<String, Object> extra, int qty) {
        Map<String, Object> req = new HashMap<>();
        req.put("partnerCode", partnerCode);
        req.put("orderDate", DATE);
        req.putAll(extra);
        req.put("items", List.of(Map.of("productCode", bookCode, "qty", qty)));
        JsonNode r = postAsDealerSite("/dealer-site/orders", req, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r).path("entryNo").asText();
    }

    @Test
    @DisplayName("★배송지를 보내면 주문별로 저장되고 목록·작업요청서에 실린다(§2)")
    void 배송지_저장과_노출() {
        String entryNo = accept(Map.of(
                "zipCode", "06236", "address", "서울 강남구 테헤란로 1 3층",
                "schoolCode", "SCH" + SFX, "schoolName", "테스트학교"), 3);

        // 목록(사이트 [조회/수정] 화면이 이걸로 배송지를 보여주고 고친다)
        JsonNode row = myOrder(entryNo);
        assertThat(row.path("zipCode").asText()).isEqualTo("06236");
        assertThat(row.path("address").asText()).isEqualTo("서울 강남구 테헤란로 1 3층");

        // 작업요청서(물류가 이 주소로 보낸다)
        JsonNode wo = null;
        for (JsonNode w : data(get("/logistics/work-orders?fromDate=" + DATE + "&toDate=" + DATE))
                .path("content")) {
            if (entryNo.equals(w.path("entryNo").asText())) {
                wo = w;
            }
        }
        assertThat(wo).as("작업요청서를 찾았다").isNotNull();
        assertThat(wo.path("zipCode").asText()).isEqualTo("06236");
        assertThat(wo.path("address").asText()).isEqualTo("서울 강남구 테헤란로 1 3층");
    }

    @Test
    @DisplayName("★주문 줄에 세부구분·분류명이 실린다 — 화면 표시가 「세부구분/분류명/도서명」(§4)")
    void 줄에_세부구분과_분류명() {
        String entryNo = accept(Map.of(), 1);

        JsonNode lines = myOrder(entryNo).path("lines");
        assertThat(lines).isNotEmpty();
        JsonNode line = lines.get(0);
        assertThat(line.path("salesDivision").asText()).isEqualTo("교재");
        assertThat(line.path("catName").asText()).isEqualTo("배송지분류");
        assertThat(line.path("productName").asText()).isEqualTo("배송지도서");
    }

    @Test
    @DisplayName("★★수정에서 담당자·배송지를 안 보내도 신청 때 값이 유지된다(확인 d)")
    void 수정시_담당자와_배송지_유지() {
        String entryNo = accept(Map.of(
                "receiverName", "박선생", "receiverPhone", "010-1111-2222",
                "receiverEmail", "teacher@example.com",
                "zipCode", "12345", "address", "부산 해운대구 1"), 2);

        JsonNode before = myOrder(entryNo);
        assertThat(before.path("receiverName").asText()).isEqualTo("박선생");

        // 사이트 [조회/수정] — 수량만 바꾸고 담당자·배송지는 보내지 않는다.
        Map<String, Object> edit = new HashMap<>();
        edit.put("partnerCode", partnerCode);
        edit.put("orderDate", DATE);
        edit.put("items", List.of(Map.of("productCode", bookCode, "qty", 5)));
        JsonNode r = putAsDealerSite("/dealer-site/orders/" + entryNo, edit, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        JsonNode after = myOrder(entryNo);
        assertThat(after.path("totalQty").asLong()).as("수량은 바뀐다").isEqualTo(5);
        // ‼️마스터 대표자("마스터대표")로 되돌아가면 안 된다 — 사용자가 고친 값이 사라지는 것이다.
        assertThat(after.path("receiverName").asText())
                .as("신청 때 고친 담당자가 유지돼야").isEqualTo("박선생");
        assertThat(after.path("receiverPhone").asText()).isEqualTo("010-1111-2222");
        assertThat(after.path("receiverEmail").asText()).isEqualTo("teacher@example.com");
        assertThat(after.path("zipCode").asText()).as("배송지도 유지").isEqualTo("12345");
        assertThat(after.path("address").asText()).isEqualTo("부산 해운대구 1");
    }
}
