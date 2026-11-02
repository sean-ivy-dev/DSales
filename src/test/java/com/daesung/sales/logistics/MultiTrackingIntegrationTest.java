package com.daesung.sales.logistics;

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
 * 박스별 송장 — 10/27 명세 §8-3, 프론트 E-2.
 *
 * <p>명세: "주문은 한 줄이지만 <b>송장번호는 박스 수만큼 여러 개</b> 등록할 수 있게 한다."
 * PPT 18: "송장은 박스에 따라 여러 개가 등록 가능하도록."
 *
 * <p>‼️레거시에는 송장 개념이 <b>아예 없다</b>({@code sendData} 에 BoxCount 만 있고 송장
 * 컬럼이 없다). V44 에서 우리가 신설한 축이라 따를 선례가 없고, 위 두 문장이 유일한 기준이다.
 *
 * <p>고정하는 것
 * <ul>
 *   <li>박스 수만큼 저장되고 <b>번호 순</b>으로 돌아온다</li>
 *   <li>기존 단일 필드는 <b>1번 박스를 비춘다</b> — 화면·엑셀이 아직 그걸 쓴다</li>
 *   <li>보낸 목록으로 <b>통째 교체</b>된다(줄이 줄면 사라진다)</li>
 *   <li>박스 번호가 겹치면 <b>사유와 함께 거부</b></li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// ‼️한 발송 건을 공유하므로 **순서를 고정**한다. 송장은 "통째 교체"라 테스트끼리 서로의
//   상태를 덮는다 — 순서가 흔들리면 번갈아 깨진다(실제로 그렇게 깨졌다).
//   발송 건을 테스트마다 새로 만들 수도 있지만, 매출·입고까지 매번 깔아야 해 비용이 크다.
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@DisplayName("박스별 송장(§8-3 · 프론트 E-2)")
class MultiTrackingIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-MT" + (System.nanoTime() % 1_000_000L);
    private static final String DAY = "2047-05-12";

    private Long shipmentId;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "MTS" + SFX, "name", "인쇄", "type", "NORMAL"));
        Long partner = createId("/masters/clients",
                Map.of("code", "MTP" + SFX, "name", "송장거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "MTW" + SFX, "name", "물류", "type", "MAIN"));
        Map<String, Object> b = new HashMap<>();
        b.put("code", "MTB" + SFX);
        b.put("name", "송장도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("salesDivision", "교재");
        Long book = createId("/masters/products", b);
        post("/stock/inbound", Map.of("processedDate", "2047-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 500))));
        post("/sales/entries", Map.of("salesDate", DAY, "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 30, "unitPrice", 10000, "supplyRate", 70))));

        shipmentId = data(get("/logistics/work-orders?fromDate=" + DAY + "&toDate=" + DAY
                + "&partnerId=" + partner)).path("content").get(0).path("id").asLong();
    }

    private JsonNode order() {
        for (JsonNode o : data(get("/logistics/work-orders?fromDate=" + DAY + "&toDate=" + DAY))
                .path("content")) {
            if (o.path("id").asLong() == shipmentId) {
                return o;
            }
        }
        throw new AssertionError("발송 건 없음");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("★박스 수만큼 송장을 저장한다 — 번호 순으로 돌아온다")
    void 박스별_저장() {
        JsonNode r = put("/logistics/work-orders/" + shipmentId + "/trackings", Map.of(
                "trackings", List.of(
                        Map.of("boxNo", 2, "courierName", "CJ대한통운", "trackingNo", "222"),
                        Map.of("boxNo", 1, "courierName", "CJ대한통운", "trackingNo", "111"),
                        Map.of("boxNo", 3, "courierName", "우체국", "trackingNo", "333"))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        JsonNode list = order().path("trackings");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).path("boxNo").asInt()).as("번호 순").isEqualTo(1);
        assertThat(list.get(0).path("trackingNo").asText()).isEqualTo("111");
        assertThat(list.get(2).path("trackingNo").asText()).isEqualTo("333");
        assertThat(list.get(2).path("courierName").asText()).isEqualTo("우체국");

        // ★기존 단일 필드는 1번 박스를 비춘다 — 화면·엑셀이 아직 이걸 쓴다.
        assertThat(order().path("trackingNo").asText()).isEqualTo("111");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("보낸 목록으로 통째 교체된다 — 줄이 줄면 사라진다")
    void 통째_교체() {
        put("/logistics/work-orders/" + shipmentId + "/trackings", Map.of(
                "trackings", List.of(
                        Map.of("boxNo", 1, "trackingNo", "A1"),
                        Map.of("boxNo", 2, "trackingNo", "A2"))));
        put("/logistics/work-orders/" + shipmentId + "/trackings", Map.of(
                "trackings", List.of(Map.of("boxNo", 1, "trackingNo", "B1"))));

        JsonNode list = order().path("trackings");
        assertThat(list).as("두 줄이 한 줄로 줄어든다").hasSize(1);
        assertThat(list.get(0).path("trackingNo").asText()).isEqualTo("B1");
        assertThat(order().path("trackingNo").asText()).as("거울도 따라간다").isEqualTo("B1");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("빈 배열이면 모두 지운다 — 잘못 넣은 것을 되돌리는 길")
    void 비우기() {
        put("/logistics/work-orders/" + shipmentId + "/trackings", Map.of(
                "trackings", List.of(Map.of("boxNo", 1, "trackingNo", "Z1"))));
        put("/logistics/work-orders/" + shipmentId + "/trackings", Map.of(
                "trackings", List.of()));

        assertThat(order().path("trackings")).isEmpty();
        assertThat(order().hasNonNull("trackingNo")).as("거울도 비워진다").isFalse();
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("★박스 번호가 겹치면 사유와 함께 거부한다")
    void 번호_중복() {
        JsonNode r = put("/logistics/work-orders/" + shipmentId + "/trackings", Map.of(
                "trackings", List.of(
                        Map.of("boxNo", 1, "trackingNo", "X1"),
                        Map.of("boxNo", 1, "trackingNo", "X2"))));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("겹칩니다");
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("기존 단건 입력도 그대로 동작한다 — 1번 박스로 들어간다")
    void 단건입력_호환() {
        put("/logistics/work-orders/" + shipmentId + "/trackings", Map.of("trackings", List.of()));
        put("/logistics/work-orders/" + shipmentId + "/shipping",
                Map.of("boxCount", 1, "courierName", "로젠", "trackingNo", "SINGLE-1"));

        JsonNode list = order().path("trackings");
        assertThat(list).as("단건도 박스 줄이 된다").hasSize(1);
        assertThat(list.get(0).path("boxNo").asInt()).isEqualTo(1);
        assertThat(list.get(0).path("trackingNo").asText()).isEqualTo("SINGLE-1");
    }
}
