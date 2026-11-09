package com.daesung.sales.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * <b>CJ 송장 양식</b> 일괄 등록(F-30).
 *
 * <p>발주처 검수: *"[송장 일괄 업로드] 양식이 CJ 송장 양식이 아님 … 매칭 기준도 '작업번호'"*.
 * CJ 가 돌려주는 파일은 11열이고 <b>우리 작업번호가 없다</b> —
 * No · 상태 · 운송장번호 · 보내는분 · 내품수량 · 받는분 · 받는분전화번호 ·
 * 받는분주소 · 상품명 · 단품명 · 배송메시지.
 *
 * <p>★이 테스트가 지키는 것 둘:
 * <ul>
 *   <li><b>같은 받는분의 여러 행은 박스별 송장</b>으로 묶인다(CJ 는 박스마다 한 줄을 준다)</li>
 *   <li><b>후보가 둘이면 거부한다</b> — 아무 쪽에 붙이면 <b>남의 송장이 붙는다</b></li>
 * </ul>
 *
 * <p>‼️실제 양식 파일에는 진짜 수령인 이름·휴대폰·주소가 있다. 여기 픽스처는
 * <b>전부 만든 값</b>이다 — 실데이터를 테스트에 넣지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CJ 송장 양식 일괄 등록 — 받는분·연락처로 매칭(F-30)")
class CjTrackingUploadIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CJ" + (System.nanoTime() % 1_000_000L);
    private static final String PATH = "/logistics/work-orders/tracking/upload";
    /** ★매칭 범위가 최근 90일이라 **오늘** 거래일로 만든다. */
    private static final String TODAY = java.time.LocalDate.now().toString();

    private static final String RECEIVER = "검증수령" + (System.nanoTime() % 1000L);
    private static final String PHONE = "010-7777-0001";

    private long shipmentId;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "CJS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        Long partner = createId("/masters/clients",
                Map.of("code", "CJP" + SFX, "name", "CJ검증거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "CJW" + SFX, "name", "물류", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of("code", "CJB" + SFX, "name", "CJ검증교재",
                "contentType", "SELF", "price", 10000, "supplyRate", 75, "salesDivision", "교재"));
        assertThat(post("/stock/inbound", Map.of("processedDate", TODAY, "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))))
                .path("success").asBoolean()).isTrue();
        assertThat(post("/sales/entries", Map.of("salesDate", TODAY, "partnerId", partner,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", 10,
                        "schoolCode", "30001", "schoolName", "CJ검증고"))))
                .path("success").asBoolean()).isTrue();

        JsonNode orders = data(get("/logistics/work-orders?fromDate=" + TODAY + "&toDate=" + TODAY
                + "&page=0&size=50")).path("content");
        for (JsonNode o : orders) {
            if (("CJP" + SFX).equals(o.path("partnerCode").asText())) {
                shipmentId = o.path("id").asLong();
            }
        }
        assertThat(shipmentId).as("발송 건이 생겨야 한다: %s", orders).isNotZero();

        // 택배 + 수령인 정보를 넣는다 — CJ 매칭 키다.
        assertThat(put("/logistics/work-orders/" + shipmentId + "/shipping", Map.of(
                "deliveryType", "COURIER", "boxCount", 3,
                "receiverName", RECEIVER, "receiverPhone", PHONE))
                .path("success").asBoolean()).isTrue();
    }

    /** CJ 양식 11열 xlsx 를 만든다. 값은 전부 만든 것이다(실데이터 금지). */
    private static byte[] cjFile(List<String[]> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Sheet1");
            String[] header = {"No", "상태", "운송장번호", "보내는분", "내품수량", "받는분",
                    "받는분전화번호", "받는분주소", "상품명", "단품명", "배송메시지"};
            Row h = s.createRow(0);
            for (int i = 0; i < header.length; i++) {
                h.createCell(i).setCellValue(header[i]);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = s.createRow(r + 1);
                String[] v = rows.get(r);
                for (int c = 0; c < v.length; c++) {
                    row.createCell(c).setCellValue(v[c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String[] cjRow(String no, String tracking, String receiver, String phone) {
        return new String[]{no, "출력확정", tracking, "대성학력개발", "10", receiver, phone,
                "서울시 어딘가 1길 1", "CJ검증교재", "", ""};
    }

    @Test
    @DisplayName("‼️같은 받는분 3행 = **박스별 송장 3개**로 등록된다")
    void 박스별_송장() {
        byte[] f = cjFile(List.<String[]>of(
                cjRow("1", "6000-1111-0001", RECEIVER, PHONE),
                cjRow("2", "6000-1111-0002", RECEIVER, PHONE),
                cjRow("3", "6000-1111-0003", RECEIVER, PHONE)));

        JsonNode res = multipart(PATH, f, "cj.xlsx");
        assertThat(res.path("success").asBoolean()).as("업로드: %s", res).isTrue();
        assertThat(data(res).path("updated").asInt()).as("3행 모두 반영: %s", data(res)).isEqualTo(3);
        assertThat(data(res).path("failed").asInt()).isZero();

        JsonNode o = orderById();
        JsonNode ts = o.path("trackings");
        assertThat(ts).as("박스별 송장 3개: %s", o).hasSize(3);
        // 택배사는 CJ 고정(CJ 가 돌려준 파일이다)
        assertThat(ts.get(0).path("courierName").asText()).isEqualTo("CJ대한통운");
        // 박스 1..3
        assertThat(ts.get(0).path("boxNo").asInt()).isEqualTo(1);
        assertThat(ts.get(2).path("boxNo").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("‼️받는분으로 찾지 못하면 **그 행만** ERROR — 나머지는 반영된다")
    void 못찾으면_그행만_실패() {
        byte[] f = cjFile(List.<String[]>of(
                cjRow("1", "6000-2222-0001", RECEIVER, PHONE),
                cjRow("2", "6000-2222-0002", "없는수령인" + SFX, "010-0000-0000")));

        JsonNode res = multipart(PATH, f, "cj.xlsx");
        assertThat(res.path("success").asBoolean()).isTrue();
        assertThat(data(res).path("updated").asInt()).as("있는 건은 반영: %s", data(res)).isEqualTo(1);
        assertThat(data(res).path("failed").asInt()).isEqualTo(1);

        boolean sawReason = false;
        for (JsonNode l : data(res).path("lines")) {
            if ("ERROR".equals(l.path("status").asText())) {
                assertThat(l.path("message").asText()).contains("찾지 못했습니다");
                sawReason = true;
            }
        }
        assertThat(sawReason).as("사유가 있어야 담당자가 손으로 넣을 수 있다: %s", data(res)).isTrue();
    }

    @Test
    @DisplayName("운송장번호가 빈 행은 설명행으로 보고 건너뛴다 — 오류로 세지 않는다")
    void 빈_운송장은_스킵() {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        rows.add(new String[]{"", "", "", "", "", "", "", "", "", "", ""});
        rows.add(cjRow("1", "6000-3333-0001", RECEIVER, PHONE));
        byte[] f = cjFile(rows);

        JsonNode res = multipart(PATH, f, "cj.xlsx");
        assertThat(data(res).path("updated").asInt()).isEqualTo(1);
        assertThat(data(res).path("failed").asInt()).as("빈 행을 오류로 세면 안 된다").isZero();
    }

    @Test
    @DisplayName("받는분은 있고 연락처가 없으면 ERROR — 그 둘이 매칭 키다")
    void 연락처없으면_오류() {
        byte[] f = cjFile(List.<String[]>of(cjRow("1", "6000-4444-0001", RECEIVER, "")));
        JsonNode res = multipart(PATH, f, "cj.xlsx");
        assertThat(data(res).path("failed").asInt()).isEqualTo(1);
        assertThat(data(res).path("lines").get(0).path("message").asText())
                .contains("받는분전화번호");
    }

    @Test
    @DisplayName("우리 양식(발송건ID)도 **그대로 동작한다** — 머리글로 가린다")
    void 우리양식도_유지() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet();
            Row h = s.createRow(0);
            h.createCell(0).setCellValue("발송건ID");
            h.createCell(1).setCellValue("택배사");
            h.createCell(2).setCellValue("송장번호");
            Row r = s.createRow(1);
            r.createCell(0).setCellValue(shipmentId);
            r.createCell(1).setCellValue("대신택배");
            r.createCell(2).setCellValue("9900-0000-0001");
            wb.write(out);

            JsonNode res = multipart(PATH, out.toByteArray(), "own.xlsx");
            assertThat(res.path("success").asBoolean()).as("우리 양식: %s", res).isTrue();
            assertThat(data(res).path("updated").asInt()).isEqualTo(1);
            assertThat(orderById().path("courierName").asText()).isEqualTo("대신택배");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode orderById() {
        for (JsonNode o : data(get("/logistics/work-orders?fromDate=" + TODAY + "&toDate=" + TODAY
                + "&page=0&size=50")).path("content")) {
            if (o.path("id").asLong() == shipmentId) {
                return o;
            }
        }
        throw new AssertionError("발송 건을 못 찾음 id=" + shipmentId);
    }
}
