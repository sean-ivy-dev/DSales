package com.daesung.sales.sale;

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
 * 특강 제외 — 작업요청서·출고/반품 조회에 안 나와야 함. 근거: 10/27 검수 피드백 §3-4·4-12
 * "레거시에서 '특강'으로 분류된 상품은 작업요청서와 출고/반품 조회에 나오지 않는다.
 * 통합 매출 조회에만 기록한다."
 *
 * <p>매출만(onlySales)과 같은 방식(쓰기 측에서 Shipment 생성을 스킵)으로 구현했다 —
 * 재고는 평소대로 빠지지만, 물류 작업 단위(Shipment)는 안 만든다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("특강 제외(10/27 피드백 §3-4·4-12)")
class SpecialLectureExclusionIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SL" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2097;

    private Long partner;
    private Long wh;
    private Long sup;
    private Long lecture;    // 특강 상품
    private Long textbook;   // 일반(교재) 상품

    @BeforeAll
    void seed() {
        token();
        sup = createId("/masters/clients",
                Map.of("code", "SLS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "SLP" + SFX, "name", "특강거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "SLW" + SFX, "name", "특강창고", "type", "MAIN"));

        // 특강 세부구분 신설 — 대분류 SPECIAL_LECTURE.
        Long lectureDivision = data(post("/masters/sales-divisions", Map.of(
                "code", "SLD" + SFX, "name", "특강구분" + SFX, "majorCategory", "SPECIAL_LECTURE")))
                .path("id").asLong();
        // 비교용 교재 세부구분(기존 기본 교재 축 재사용 대신 전용 신설 — 격리).
        Long textbookDivision = data(post("/masters/sales-divisions", Map.of(
                "code", "SLT" + SFX, "name", "교재구분" + SFX, "majorCategory", "TEXTBOOK")))
                .path("id").asLong();

        Map<String, Object> l = new HashMap<>();
        l.put("code", "SLB" + SFX);
        l.put("name", "특강도서");
        l.put("contentType", "SELF");
        l.put("price", 10000);
        l.put("supplyRate", 70);
        l.put("catCode", "L" + YEAR + "A");
        l.put("catName", "특강분류");
        l.put("salesDivision", "SLD" + SFX);
        lecture = createId("/masters/products", l);

        Map<String, Object> t = new HashMap<>(l);
        t.put("code", "TXB" + SFX);
        t.put("name", "일반도서");
        t.put("salesDivision", "SLT" + SFX);
        textbook = createId("/masters/products", t);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(
                        Map.of("productId", lecture, "unitCost", 3000, "qty", 500),
                        Map.of("productId", textbook, "unitCost", 3000, "qty", 500))));
    }

    private JsonNode sell(Long product, String date, int qty) {
        Map<String, Object> body = new HashMap<>();
        body.put("salesDate", date);
        body.put("partnerId", partner);
        body.put("warehouseId", wh);
        body.put("items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                "unitPrice", 10000, "supplyRate", 70, "qty", qty)));
        return post("/sales/entries", body);
    }

    @Test
    @DisplayName("★특강은 재고는 빠지지만 작업요청서는 안 생긴다")
    void 특강_작업요청서_미생성() {
        // ‼️절대값으로 비교하지 않는다 — 같은 클래스의 다른 테스트가 같은 lecture 상품을
        // 더 팔 수 있어 실행 순서에 따라 잔량이 달라진다(테스트 격리 규율).
        int before = data(sell(lecture, YEAR + "-02-09", 1)).path("items").get(0).path("stockBalance").asInt();

        JsonNode r = sell(lecture, YEAR + "-02-10", 4);
        assertThat(r.path("success").asBoolean()).as("특강도 매출은 정상 등록돼야: %s", r).isTrue();
        assertThat(data(r).path("items").get(0).path("stockBalance").asInt())
                .as("재고는 평소대로 빠진다").isEqualTo(before - 4);

        JsonNode orders = data(get("/logistics/work-orders?fromDate=" + YEAR + "-01-01&toDate="
                + YEAR + "-12-31&partnerId=" + partner)).path("content");
        boolean hasLectureLine = false;
        for (JsonNode o : orders) {
            for (JsonNode line : o.path("lines")) {
                if (line.path("productId").asLong() == lecture) {
                    hasLectureLine = true;
                }
            }
        }
        assertThat(hasLectureLine).as("특강 줄이 작업요청서에 있으면 안 됨: %s", orders).isFalse();
    }

    @Test
    @DisplayName("★특강은 통합 매출 조회에는 그대로 나온다 — 매출 자체가 사라지면 안 됨")
    void 특강_매출조회에는_나온다() {
        sell(lecture, YEAR + "-03-10", 2);

        JsonNode sales = data(get("/sales?startDate=" + YEAR + "-01-01&endDate=" + YEAR + "-12-31"
                + "&partnerId=" + partner)).path("content");
        boolean found = false;
        for (JsonNode s : sales) {
            if (s.path("productId").asLong() == lecture) {
                found = true;
            }
        }
        assertThat(found).as("특강 매출은 통합 매출 조회엔 떠야: %s", sales).isTrue();
    }

    @Test
    @DisplayName("★같은 날·거래처·학교·거래분류로 특강+일반이 섞여도 특강이 Shipment에 안 묻어간다")
    void 특강_일반_복합키_충돌_없음() {
        // 작업요청서 묶음 키는 (거래일자·거래처·학교·거래분류) — 학교코드를 안 주면 둘 다 null로
        // 같은 묶음에 들어갈 수 있는 상황을 의도적으로 만든다(가장 충돌 가능성이 큰 조합).
        String date = YEAR + "-04-15";
        // 일반 매출이 먼저 Shipment를 만든다.
        JsonNode normalResult = sell(textbook, date, 6);
        assertThat(normalResult.path("success").asBoolean()).isTrue();
        // 같은 날·거래처로 특강 매출 — Shipment를 스킵해야 하므로 위 Shipment에 묻어가면 안 된다.
        JsonNode lectureResult = sell(lecture, date, 9);
        assertThat(lectureResult.path("success").asBoolean()).isTrue();

        JsonNode orders = data(get("/logistics/work-orders?fromDate=" + YEAR + "-01-01&toDate="
                + YEAR + "-12-31&partnerId=" + partner)).path("content");
        for (JsonNode o : orders) {
            if (!date.equals(o.path("tradeDate").asText())) {
                continue;
            }
            int lectureQtyInLines = 0;
            for (JsonNode line : o.path("lines")) {
                if (line.path("productId").asLong() == lecture) {
                    lectureQtyInLines += line.path("qty").asInt();
                }
            }
            assertThat(lectureQtyInLines)
                    .as("같은 날 작업요청서 줄에 특강 수량이 섞이면 안 됨: %s", o).isZero();
        }
    }
}
