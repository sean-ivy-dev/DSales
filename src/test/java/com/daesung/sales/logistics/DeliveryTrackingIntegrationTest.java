package com.daesung.sales.logistics;

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
 * 작업요청서(26p) 발송구분·수령인·송장 회귀 고정.
 *
 * <p>근거: 정본 26p [업데이트 2026-07-25 ★클라이언트 정정★] —
 * "작업 대기 리스트의 '상태변경' 옆에 '발송구분(택배/화물)' 필드 추가.
 * '택배' 선택 시 담당자 정보가 노출".
 *
 * <p>프론트는 이미 `발송구분`·`수령인` 컬럼을 그리고 있었는데 우리가 값을 주지 않았다.
 * 그 계약을 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("작업요청서 발송구분·송장(26p)")
class DeliveryTrackingIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DT" + (System.nanoTime() % 1_000_000L);
    private static final String RANGE = "?fromDate=2033-05-01&toDate=2033-05-31";

    private long courierShipment;
    private long freightShipment;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "DTS" + SFX, "name", "인쇄", "type", "NORMAL"));
        Long partner = createId("/masters/clients",
                Map.of("code", "DTP" + SFX, "name", "발송거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "DTW" + SFX, "name", "물류", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of("code", "DTA" + SFX, "name", "교재도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75, "salesDivision", "교재"));
        Long mock = createId("/masters/products", Map.of("code", "DTB" + SFX, "name", "모의도서",
                "contentType", "SELF", "price", 5000, "supplyRate", 75, "salesDivision", "모의고사"));
        post("/stock/inbound", Map.of("processedDate", "2033-05-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500),
                        Map.of("productId", mock, "unitCost", 1000, "qty", 500))));

        // 분류가 다르면 발송 건이 둘로 나뉜다 → 하나는 택배, 하나는 화물로 쓴다
        post("/sales/entries", Map.of("salesDate", "2033-05-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(
                        Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", 10,
                                "schoolCode", "20001", "schoolName", "발송고등학교"),
                        Map.of("productId", mock, "shipmentType", "NORMAL_SHIP", "qty", 5,
                                "schoolCode", "20001", "schoolName", "발송고등학교"))));

        JsonNode orders = data(get("/logistics/work-orders" + RANGE)).path("content");
        assertThat(orders).hasSize(2);
        courierShipment = orders.get(0).path("id").asLong();
        freightShipment = orders.get(1).path("id").asLong();
    }

    @Test
    @DisplayName("★★작업요청서 발송취소 — 발송일만 내리고 송장·수령인은 남긴다(2026-10-12 A-3)")
    void 작업요청서_발송취소() {
        // 발송완료 + 송장·수령인까지 채운 상태를 만든다.
        // ‼️송장은 발송구분이 '택배'인 건에만 들어간다(11/04 §3) — 실제 화면도 발송구분을
        //   먼저 택배로 바꾼 뒤 담당자정보·송장을 입력한다. 같은 요청에 함께 보내면
        //   updateDelivery 가 가드보다 먼저 반영돼 한 번에 된다.
        // ‼️freightShipment 는 '기본값_화물' 테스트가 "끝까지 아무도 안 건드린다"고 가정한다
        //   — 송장 전환이 필요한 이 테스트는 courierShipment 를 쓴다(이름대로 택배로 쓰인다).
        put("/logistics/work-orders/" + courierShipment + "/shipping", Map.of(
                "deliveryType", "COURIER",
                "sentDate", "2033-05-20", "boxCount", 7,
                "receiverName", "김수령", "receiverPhone", "010-9999-0000",
                "courierName", "CJ대한통운", "trackingNo", "1234567890"));
        assertThat(row(courierShipment).path("sentDate").asText()).isEqualTo("2033-05-20");

        JsonNode r = del("/logistics/work-orders/" + courierShipment + "/shipping",
                Map.of("reason", "오발송"));
        assertThat(r.path("success").asBoolean()).as("발송취소: %s", r).isTrue();
        assertThat(data(r).path("changed").asBoolean()).isTrue();

        JsonNode after = row(courierShipment);
        // ★발송일만 내려간다.
        assertThat(after.hasNonNull("sentDate")).as("발송완료가 풀려야 한다").isFalse();
        // ‼️송장·수령인·박스는 남는다 — 다시 보낼 때 필요하고, 송장번호는 복원할 수 없다.
        assertThat(after.path("trackingNo").asText()).isEqualTo("1234567890");
        assertThat(after.path("courierName").asText()).isEqualTo("CJ대한통운");
        assertThat(after.path("receiverName").asText()).isEqualTo("김수령");
        assertThat(after.path("boxCount").asInt()).isEqualTo(7);

        // 이미 발송 전이면 changed=false — 오류가 아니다.
        JsonNode twice = del("/logistics/work-orders/" + freightShipment + "/shipping",
                Map.of("reason", "두 번째"));
        assertThat(twice.path("success").asBoolean()).isTrue();
        assertThat(data(twice).path("changed").asBoolean()).isFalse();
    }

    /** 그 발송 건 한 줄. */
    private JsonNode row(long id) {
        for (JsonNode r : data(get("/logistics/work-orders" + RANGE)).path("content")) {
            if (r.path("id").asLong() == id) {
                return r;
            }
        }
        throw new AssertionError("발송 건 없음: " + id);
    }

    @Test
    @DisplayName("발송구분·수령인이 저장되고 코드와 한글명이 함께 나온다")
    void 발송구분_수령인() {
        put("/logistics/work-orders/" + courierShipment + "/shipping", Map.of(
                "deliveryType", "COURIER", "receiverName", "홍길동",
                "receiverPhone", "010-1234-5678", "boxCount", 3));

        JsonNode o = orderById(courierShipment);
        assertThat(o.path("deliveryType").asText()).isEqualTo("COURIER");
        assertThat(o.path("deliveryTypeName").asText()).isEqualTo("택배");
        assertThat(o.path("receiverName").asText()).isEqualTo("홍길동");
        assertThat(o.path("receiverPhone").asText()).isEqualTo("010-1234-5678");
        assertThat(o.path("boxCount").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("기본은 화물이다 — 담당자정보 없이 고르지 않은 건은 화물로 채워진다(10/27 피드백 §8-6)")
    void 기본값_화물() {
        // ★이전엔 "자동 기본값 없음"이 정책이었다(정본이 미해결로 남겼던 지점).
        //   10/27 피드백이 "기본은 화물"로 명시적으로 뒤집었다 — 담당자정보(수령인명·연락처)
        //   없이 발송구분을 한 번도 안 준 건은 이제 등록 시점에 FREIGHT로 채워진다.
        JsonNode o = orderById(freightShipment);
        assertThat(o.path("deliveryType").asText()).isEqualTo("FREIGHT");
        assertThat(o.path("deliveryTypeName").asText()).isEqualTo("화물");
    }

    /**
     * 화물 건은 <b>택배 칸을 비워 내린다</b>(#095).
     *
     * <p>‼️실측(2026-11-09) — 운영의 화물 9건 <b>전부 택배사가 'CJ대한통운'</b>으로 나왔다.
     * {@code CourierCompany.displayLabel} 이 빈 값에 기본값을 넣기 때문인데, 그 규칙은
     * <b>"과거 택배 건의 빈 택배사를 CJ로 간주"</b> 하려고 만든 것이라 화물에는 틀렸다.
     * 화물에 택배사가 찍히면 담당자는 그 건이 택배로 나간 줄로 읽는다.
     *
     * <p>★<b>미지정(null)은 비우지 않는다</b> — 택배일 수도 있고, 그 건에 들어 있는 값은
     * 담당자가 직접 넣은 것이다.
     */
    @Test
    @DisplayName("‼️화물 건은 택배사·송장·수령인을 **비워서** 내린다(#095)")
    void 화물은_택배칸_비움() {
        // 화물로 두고 택배 정보를 억지로 밀어 넣는다(과거 데이터가 그런 모양이다).
        // ★`기본값_화물` 테스트는 이 건이 FREIGHT 인지만 본다 — 여기서 FREIGHT 를 유지하므로
        //   순서가 어떻게 되든 그 테스트는 깨지지 않는다.
        put("/logistics/work-orders/" + freightShipment + "/shipping", Map.of(
                "deliveryType", "FREIGHT", "boxCount", 2,
                "receiverName", "화물수령", "receiverPhone", "010-0000-1111"));

        JsonNode o = orderById(freightShipment);
        assertThat(o.path("deliveryType").asText()).isEqualTo("FREIGHT");
        // ‼️Jackson NON_NULL 이라 null 은 **키가 없다** — has() 로 본다.
        assertThat(o.has("courierName"))
                .as("화물에 택배사가 찍히면 택배로 나간 줄로 읽는다: %s", o).isFalse();
        // ★수령인·연락처는 **남는다** — 화물에도 담당자정보가 있는 건이 정상이고
        //   (10/27 §8-6), 지우면 그 건을 누가 받는지 알 수 없다.
        assertThat(o.path("receiverName").asText()).isEqualTo("화물수령");
        assertThat(o.path("receiverPhone").asText()).isEqualTo("010-0000-1111");
        assertThat(o.path("deliveryTypeName").asText()).isEqualTo("화물");
        assertThat(o.path("boxCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("택배 건은 종전대로 — 빈 택배사는 기본값(CJ대한통운)으로 채운다")
    void 택배는_기본값_유지() {
        put("/logistics/work-orders/" + courierShipment + "/shipping", Map.of(
                "deliveryType", "COURIER", "receiverName", "김택배"));
        JsonNode o = orderById(courierShipment);
        assertThat(o.path("courierName").asText())
                .as("과거 택배 건의 빈 택배사는 CJ로 간주한다(10/31 §2)").isNotBlank();
        assertThat(o.path("receiverName").asText()).isEqualTo("김택배");
    }

    @Test
    @DisplayName("보내지 않은 항목은 건드리지 않는다 — 박스 수만 고쳐도 수령인이 남는다")
    void 부분수정_보존() {
        put("/logistics/work-orders/" + courierShipment + "/shipping", Map.of(
                "deliveryType", "COURIER", "receiverName", "김수령", "boxCount", 1));
        put("/logistics/work-orders/" + courierShipment + "/shipping", Map.of("boxCount", 9));

        JsonNode o = orderById(courierShipment);
        assertThat(o.path("boxCount").asInt()).isEqualTo(9);
        assertThat(o.path("receiverName").asText()).as("수령인이 지워지면 택배가 누구에게 가는지 모른다")
                .isEqualTo("김수령");
        assertThat(o.path("deliveryTypeName").asText()).isEqualTo("택배");
    }

    @Test
    @DisplayName("발송구분으로 목록을 거른다")
    void 발송구분_필터() {
        put("/logistics/work-orders/" + courierShipment + "/shipping", Map.of("deliveryType", "COURIER"));
        put("/logistics/work-orders/" + freightShipment + "/shipping", Map.of("deliveryType", "FREIGHT"));

        JsonNode courier = data(get("/logistics/work-orders" + RANGE + "&deliveryType=COURIER")).path("content");
        assertThat(courier).hasSize(1);
        assertThat(courier.get(0).path("id").asLong()).isEqualTo(courierShipment);

        JsonNode freight = data(get("/logistics/work-orders" + RANGE + "&deliveryType=FREIGHT")).path("content");
        assertThat(freight).hasSize(1);
        assertThat(freight.get(0).path("id").asLong()).isEqualTo(freightShipment);
    }

    @Test
    @DisplayName("송장 일괄 등록: 아는 행만 반영하고 모르는 행은 사유와 함께 돌려준다")
    void 송장_일괄등록() throws Exception {
        byte[] xlsx = trackingFile(new Object[][]{
                {courierShipment, "CJ대한통운", "123456789012"},
                {999_999_999L, "CJ대한통운", "999"},          // 없는 발송 건
                {freightShipment, "대신화물", ""},             // 송장번호 비어 있음
        });

        JsonNode r = data(uploadTracking(xlsx));
        assertThat(r.path("updated").asInt()).isEqualTo(1);
        assertThat(r.path("failed").asInt()).isEqualTo(2);

        // 반영된 건은 조회에 나온다
        JsonNode o = orderById(courierShipment);
        assertThat(o.path("trackingNo").asText()).isEqualTo("123456789012");
        assertThat(o.path("courierName").asText()).isEqualTo("CJ대한통운");

        // 실패 행은 사유가 붙는다 — 건수만 주면 어느 줄이 문제인지 모른다
        JsonNode lines = r.path("lines");
        boolean hasReason = false;
        for (JsonNode l : lines) {
            if ("ERROR".equals(l.path("status").asText())) {
                assertThat(l.path("message").asText()).isNotBlank();
                hasReason = true;
            }
        }
        assertThat(hasReason).isTrue();
    }

    @Test
    @DisplayName("★택배사 — 모르는 값은 PUT(단건)에서 400으로 거부한다")
    void 단건_모르는택배사_거부() {
        JsonNode r = put("/logistics/work-orders/" + courierShipment + "/shipping",
                Map.of("courierName", "우체국택배", "trackingNo", "T-1"));
        assertThat(r.path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★택배사 — CJ/대신택배 둘 다 저장된다(코드명·한글 라벨 모두 허용)")
    void 단건_택배사_코드와라벨() {
        put("/logistics/work-orders/" + courierShipment + "/shipping",
                Map.of("courierName", "CJ", "trackingNo", "T-2"));   // 코드명
        assertThat(orderById(courierShipment).path("courierName").asText()).isEqualTo("CJ대한통운");

        // ‼️freightShipment 는 건드리지 않는다('기본값_화물' 테스트가 쓴다) — courierShipment 재사용.
        put("/logistics/work-orders/" + courierShipment + "/shipping",
                Map.of("courierName", "대신택배", "trackingNo", "T-3"));   // 한글 라벨
        assertThat(orderById(courierShipment).path("courierName").asText()).isEqualTo("대신택배");
    }

    @Test
    @DisplayName("★★★발송구분 (가)안 — '화물' 명시만 거부, 미지정은 허용한다(11/04 §3)")
    void 가안_화물만_거부_미지정은_허용() {
        // 화물로 **명시**된 건은 거부.
        put("/logistics/work-orders/" + freightShipment + "/shipping", Map.of("deliveryType", "FREIGHT"));
        JsonNode rejected = put("/logistics/work-orders/" + freightShipment + "/shipping",
                Map.of("courierName", "CJ대한통운", "trackingNo", "FR-1"));
        assertThat(rejected.path("success").asBoolean()).as("화물 명시 + 송장 → 거부: %s", rejected).isFalse();
        assertThat(rejected.path("error").path("code").asText()).isEqualTo("TRACKING_NOT_ALLOWED");

        // 발송구분을 아예 비운 새 발송 건은 **미지정**이다 — (가)안은 이걸 허용한다.
        Long unspecified = freshUnspecifiedShipment();
        JsonNode allowed = put("/logistics/work-orders/" + unspecified + "/shipping",
                Map.of("courierName", "CJ대한통운", "trackingNo", "UN-1"));
        assertThat(allowed.path("success").asBoolean())
                .as("미지정 + 송장 → 허용(수기 등록 건은 발송구분이 비어 있을 수 있다): %s", allowed).isTrue();
        assertThat(orderById(unspecified).path("trackingNo").asText()).isEqualTo("UN-1");

        // 같은 요청에 발송구분을 택배로 함께 보내면(화면이 실제로 하는 조작) 통과한다.
        // ‼️courierShipment 를 쓴다 — freightShipment 는 '기본값_화물' 테스트가
        //   "끝까지 아무도 안 건드린다"고 가정하므로 여기서 COURIER로 못 바꾼다.
        JsonNode switched = put("/logistics/work-orders/" + courierShipment + "/shipping",
                Map.of("deliveryType", "COURIER", "courierName", "CJ대한통운", "trackingNo", "FR-2"));
        assertThat(switched.path("success").asBoolean()).as("택배로 바꾸며 함께 보내면 허용: %s", switched).isTrue();
    }

    @Test
    @DisplayName("★가안 — 박스별 송장(/trackings)·엑셀 업로드도 화물 명시 건을 거부한다")
    void 가안_다른경로도_화물명시_거부() throws Exception {
        put("/logistics/work-orders/" + freightShipment + "/shipping", Map.of("deliveryType", "FREIGHT"));

        JsonNode boxResult = put("/logistics/work-orders/" + freightShipment + "/trackings",
                Map.of("trackings", List.of(Map.of("boxNo", 1, "trackingNo", "B-1"))));
        assertThat(boxResult.path("success").asBoolean()).as("박스별 송장도 화물이면 거부: %s", boxResult).isFalse();
        assertThat(boxResult.path("error").path("code").asText()).isEqualTo("TRACKING_NOT_ALLOWED");

        // 엑셀 업로드는 한 행만 ERROR 로 남고, 나머지(courierShipment)는 반영된다.
        put("/logistics/work-orders/" + courierShipment + "/shipping", Map.of("deliveryType", "COURIER"));
        byte[] xlsx = trackingFile(new Object[][]{
                {freightShipment, "CJ대한통운", "EXCEL-FR"},
                {courierShipment, "CJ대한통운", "EXCEL-OK"},
        });
        JsonNode r = data(uploadTracking(xlsx));
        assertThat(r.path("updated").asInt()).as("화물 건만 실패, 나머지는 반영: %s", r).isEqualTo(1);
        assertThat(r.path("failed").asInt()).isEqualTo(1);
        boolean foundTrackingNotAllowed = false;
        for (JsonNode l : r.path("lines")) {
            if ("ERROR".equals(l.path("status").asText()) && l.path("message").asText().contains("화물")) {
                foundTrackingNotAllowed = true;
            }
        }
        assertThat(foundTrackingNotAllowed).as("화물 건 실패 사유가 담긴다: %s", r).isTrue();
    }

    @Test
    @DisplayName("★가안 — 송장 삭제(발송취소·빈 배열)는 화물 건에도 허용한다")
    void 가안_삭제는_화물에도_허용() {
        put("/logistics/work-orders/" + freightShipment + "/shipping", Map.of("deliveryType", "FREIGHT"));

        // 빈 배열(전부 지우기)은 막지 않는다 — 잘못 넣은 것을 되돌리는 길.
        JsonNode cleared = put("/logistics/work-orders/" + freightShipment + "/trackings",
                Map.of("trackings", List.of()));
        assertThat(cleared.path("success").asBoolean()).as("화물 건이라도 비우기는 허용: %s", cleared).isTrue();

        // 발송취소(DELETE /shipping)도 화물 건에 그대로 동작한다(송장 자체가 없어도 됨).
        put("/logistics/work-orders/" + freightShipment + "/shipping", Map.of("sentDate", "2033-06-01"));
        JsonNode revert = del("/logistics/work-orders/" + freightShipment + "/shipping",
                Map.of("reason", "화물 발송취소 확인"));
        assertThat(revert.path("success").asBoolean()).as("화물 건 발송취소도 허용: %s", revert).isTrue();
    }

    /**
     * 발송구분이 <b>진짜로 미지정(null)</b>인 발송 건.
     *
     * <p>‼️<b>일반 매출등록으로는 못 만든다</b> — {@code SaleService.createEntries} 는
     * {@code decideDeliveryTypeIfBlank} 를 등록 직후 항상 호출해, 담당자정보가 없으면
     * 그 즉시 FREIGHT로 확정한다(10/27 §8-6). null로 남는 유일한 경로는
     * <b>위탁출고</b>({@code ConsignmentService.consignmentOut} → {@code ensureForConsignment})다 —
     * 그쪽은 이 결정을 호출하지 않는다(물류가 작업요청서에서 직접 고르는 전제).
     */
    private Long freshUnspecifiedShipment() {
        String sfx = SFX + java.util.UUID.randomUUID().toString().substring(0, 4);
        Long partner = createId("/masters/clients",
                Map.of("code", "DTU" + sfx, "name", "미지정거래처", "type", "NORMAL"));
        Long main = createId("/masters/warehouses",
                Map.of("code", "DTUM" + sfx, "name", "미지정물류창고", "type", "MAIN"));
        Long consign = createId("/masters/warehouses", Map.of("code", "DTUC" + sfx, "name", "미지정위탁창고",
                "type", "CONSIGN", "ownerClientId", partner));
        Long book = createId("/masters/products", Map.of(
                "code", "DTUB" + sfx, "name", "미지정도서", "contentType", "SELF", "price", 10000,
                "supplyRate", 75, "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2033-05-01",
                "supplierClientId", partner, "destinationWarehouseId", main,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 100))));
        post("/consignment/out", Map.of("processedDate", "2033-05-11", "partnerId", partner,
                "fromWarehouseId", main, "toWarehouseId", consign,
                "items", List.of(Map.of("productId", book, "qty", 10,
                        "unitPrice", 10000, "supplyRate", 70))));
        JsonNode orders = data(get("/logistics/work-orders" + RANGE + "&partnerId=" + partner)).path("content");
        assertThat(orders.get(0).hasNonNull("deliveryType"))
                .as("위탁출고 발송 건은 발송구분이 정말 비어 있다(전제 확인): %s", orders.get(0)).isFalse();
        return orders.get(0).path("id").asLong();
    }

    @Test
    @DisplayName("★엑셀 일괄 — 택배사 칸이 비어 있으면 CJ대한통운으로 채운다(10/31 §2)")
    void 일괄_빈택배사는_CJ기본값() throws Exception {
        byte[] xlsx = trackingFile(new Object[][]{
                {courierShipment, "", "888888888888"},
        });
        JsonNode r = data(uploadTracking(xlsx));
        assertThat(r.path("updated").asInt()).isEqualTo(1);
        assertThat(orderById(courierShipment).path("courierName").asText()).isEqualTo("CJ대한통운");
    }

    @Test
    @DisplayName("★엑셀 일괄 — 목록에 없는 택배사는 그 행만 오류, 나머지는 반영된다")
    void 일괄_모르는택배사는_그행만오류() throws Exception {
        // 엑셀 업로드엔 발송구분 칸이 없다 — freightShipment는 '기본값_화물' 테스트가 쓰므로
        // 건드리지 않고, 정상행 대상은 courierShipment로 바꾼다(미리 택배로 전환해 둔다).
        put("/logistics/work-orders/" + courierShipment + "/shipping", Map.of("deliveryType", "COURIER"));
        byte[] xlsx = trackingFile(new Object[][]{
                {freightShipment, "우체국택배", "777777777777"},   // 모르는 값(발송구분과 무관하게 거부돼야 한다)
                {courierShipment, "CJ대한통운", "666666666666"},   // 정상
        });
        JsonNode r = data(uploadTracking(xlsx));
        assertThat(r.path("updated").asInt()).as("정상 행만 반영").isEqualTo(1);
        assertThat(r.path("failed").asInt()).isEqualTo(1);
        assertThat(orderById(courierShipment).path("trackingNo").asText()).isEqualTo("666666666666");

        boolean foundCourierError = false;
        for (JsonNode l : r.path("lines")) {
            if ("ERROR".equals(l.path("status").asText())
                    && l.path("message").asText().contains("택배사")) {
                foundCourierError = true;
            }
        }
        assertThat(foundCourierError).as("어느 이유로 실패했는지 알 수 있어야").isTrue();
    }

    @Test
    @DisplayName("송장 양식에 대조 키(발송건ID)가 들어 있다")
    void 송장양식_대조키() throws Exception {
        byte[] xlsx = getBytes("/logistics/work-orders/tracking/template"
                + "?fromDate=2033-05-01&toDate=2033-05-31").getBody();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            var header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("발송건ID");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("송장번호");
            // 담당자가 ID를 따로 채울 필요가 없도록 값이 이미 들어 있어야 한다
            assertThat(sheet.getRow(1).getCell(0).getNumericCellValue()).isGreaterThan(0);
        }
    }

    /** 3컬럼(발송건ID·택배사·송장번호) xlsx 생성. */
    private byte[] trackingFile(Object[][] rows) throws Exception {
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var out = new java.io.ByteArrayOutputStream()) {
            var sheet = wb.createSheet("송장");
            var head = sheet.createRow(0);
            head.createCell(0).setCellValue("발송건ID");
            head.createCell(1).setCellValue("택배사");
            head.createCell(2).setCellValue("송장번호");
            for (int i = 0; i < rows.length; i++) {
                var r = sheet.createRow(i + 1);
                r.createCell(0).setCellValue(((Number) rows[i][0]).doubleValue());
                r.createCell(1).setCellValue((String) rows[i][1]);
                r.createCell(2).setCellValue((String) rows[i][2]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private JsonNode uploadTracking(byte[] xlsx) throws Exception {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token());
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(xlsx) {
            @Override
            public String getFilename() {
                return "tracking.xlsx";
            }
        });
        var resp = rest.exchange("/api/v1/logistics/work-orders/tracking/upload",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body, headers), String.class);
        return om.readTree(resp.getBody());
    }

    private JsonNode orderById(long id) {
        for (JsonNode o : data(get("/logistics/work-orders" + RANGE)).path("content")) {
            if (o.path("id").asLong() == id) {
                return o;
            }
        }
        throw new AssertionError("발송 건을 찾지 못함: " + id);
    }
}
