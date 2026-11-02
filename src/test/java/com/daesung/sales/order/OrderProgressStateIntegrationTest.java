package com.daesung.sales.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 주문조회 <b>선택 건 상태변경</b> — 접수완료·상품검수·상품준비중(§2-8 · 프론트 A-4).
 *
 * <p>정본 §2-8 — "선택지를 <b>접수완료 / 상품검수 / 상품준비중</b>만 남긴다.
 * 발송준비중·발송완료는 물류 영역이므로 <b>[작업요청서]에서만</b> 처리한다."
 * 발주처 1차회신 #4 — "진행상태 6단계, <b>상품검수·취소 포함</b>(사용빈도는 거의 없으나 일단 포함)".
 *
 * <p>★<b>급소 둘</b> —
 * ① 발송 축(발송준비중·발송완료)을 목표로 주면 <b>거부</b>해야 한다. 열어 두면
 *    접수완료가 곧바로 발송완료가 되는 주문이 생긴다.
 * ② 이미 출력·발송된 주문은 <b>건별로 거부</b>해야 한다. 접수 단계를 적어도 진행상태는
 *    출력·발송을 먼저 보므로 <b>화면이 안 바뀐다</b> — 조용히 성공으로 답하면 담당자는
 *    바뀐 줄 안다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("주문 상태변경(접수 단계 · §2-8 · A-4)")
class OrderProgressStateIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PS" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2095-08-20";
    private static final String RANGE = "?fromDate=2095-08-01&toDate=2095-08-31";

    private Long partner;
    private Long wh;
    private Long book;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "PSS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "PSP" + SFX, "name", "상태변경거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "PSW" + SFX, "name", "물류창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "PSB" + SFX);
        b.put("name", "상태변경도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "P2095A01");
        b.put("catName", "상태변경검증");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2095-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 100, "qty", 999))))
                .path("success").asBoolean()).isTrue();
    }

    private String register() {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", book);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", 3);
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", wh,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r).path("entryNo").asText();
    }

    private JsonNode change(List<String> orderNos, String to) {
        Map<String, Object> req = new HashMap<>();
        req.put("orderNos", orderNos);
        req.put("toState", to);
        req.put("reason", "검증");
        return post("/orders/progress-state", req);
    }

    private String stateOf(String orderNo) {
        for (JsonNode o : data(get("/orders" + RANGE + "&partnerId=" + partner)).path("content")) {
            if (orderNo.equals(o.path("orderNo").asText())) {
                return o.path("progressState").asText();
            }
        }
        return null;
    }

    @Test
    @DisplayName("★세 상태 사이를 오간다 — 접수완료→상품검수→상품준비중, 되돌리기도 된다")
    void 접수단계_전이() {
        String no = register();
        assertThat(stateOf(no)).as("수기 등록은 상품준비중에서 시작").isEqualTo("PREPARING");

        assertThat(change(List.of(no), "RECEIVED").path("success").asBoolean()).isTrue();
        assertThat(stateOf(no)).isEqualTo("RECEIVED");

        assertThat(change(List.of(no), "INSPECTING").path("success").asBoolean()).isTrue();
        assertThat(stateOf(no)).isEqualTo("INSPECTING");

        assertThat(change(List.of(no), "PREPARING").path("success").asBoolean()).isTrue();
        assertThat(stateOf(no)).isEqualTo("PREPARING");
    }

    @Test
    @DisplayName("★접수완료 → 상품준비중 바로 가기 — 평상시 흐름(상품검수는 거의 안 쓴다)")
    void 한칸씩이_아니다() {
        String no = register();
        change(List.of(no), "RECEIVED");

        JsonNode r = change(List.of(no), "PREPARING");
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("changed").asInt()).isEqualTo(1);
        assertThat(stateOf(no)).isEqualTo("PREPARING");
    }

    @Test
    @DisplayName("★★발송 축을 목표로 주면 거부한다(400) — 작업요청서 소관이다")
    void 발송축은_거부() {
        String no = register();

        for (String to : List.of("READY_TO_SHIP", "SHIPPED", "CANCELED")) {
            JsonNode r = change(List.of(no), to);
            assertThat(r.path("success").asBoolean()).as("%s 는 거부: %s", to, r).isFalse();
            assertThat(r.path("error").path("message").asText())
                    .contains("접수완료·상품검수·상품준비중");
        }
        assertThat(stateOf(no)).as("상태가 그대로").isEqualTo("PREPARING");
    }

    @Test
    @DisplayName("★★이미 출력된 주문은 건별로 거부한다 — 조용히 성공으로 답하면 안 바뀐 걸 모른다")
    void 물류가_진행한건_거부() {
        String no = register();
        for (Shipment s : shipmentRepository.findByEntryNo(no)) {
            assertThat(post("/logistics/work-orders/" + s.getId() + "/print", Map.of())
                    .path("success").asBoolean()).isTrue();
        }
        assertThat(stateOf(no)).isEqualTo("READY_TO_SHIP");

        JsonNode r = change(List.of(no), "RECEIVED");
        // 요청 자체는 200 — 건별 결과로 알린다(한 건 때문에 전체를 실패시키지 않는다).
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("changed").asInt()).isZero();
        assertThat(data(r).path("skipped").asInt()).isEqualTo(1);
        assertThat(data(r).path("results").get(0).path("message").asText())
                .contains("작업요청서");
        assertThat(stateOf(no)).as("바뀌지 않았다").isEqualTo("READY_TO_SHIP");
    }

    @Test
    @DisplayName("★한 건이 안 된다고 전체를 실패시키지 않는다 — 100건 중 1건 때문에 다시 고를 수 없다")
    void 부분실패() {
        String ok = register();
        String printed = register();
        for (Shipment s : shipmentRepository.findByEntryNo(printed)) {
            post("/logistics/work-orders/" + s.getId() + "/print", Map.of());
        }

        JsonNode r = change(List.of(ok, printed, "없는주문번호" + SFX), "INSPECTING");
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("requested").asInt()).isEqualTo(3);
        assertThat(data(r).path("changed").asInt()).as("되는 것만 바뀐다").isEqualTo(1);
        assertThat(data(r).path("skipped").asInt()).isEqualTo(2);

        assertThat(stateOf(ok)).isEqualTo("INSPECTING");
        assertThat(stateOf(printed)).isEqualTo("READY_TO_SHIP");
    }

    @Test
    @DisplayName("같은 상태로 다시 바꾸면 건너뛰고 사유를 말한다")
    void 같은상태는_건너뜀() {
        String no = register();

        JsonNode r = change(List.of(no), "PREPARING");
        assertThat(data(r).path("changed").asInt()).isZero();
        assertThat(data(r).path("results").get(0).path("message").asText()).contains("이미");
    }

    @Test
    @DisplayName("★이력이 남는다 — 되돌린 사실이 어디에도 없으면 감사에서 설명할 수 없다")
    void 이력이_남는다() {
        String no = register();
        change(List.of(no), "RECEIVED");

        List<Long> ids = new ArrayList<>();
        for (Shipment s : shipmentRepository.findByEntryNo(no)) {
            ids.add(s.getId());
        }
        assertThat(ids).isNotEmpty();

        JsonNode hist = data(get("/audit/status-history?entityType=SHIPMENT&entityId=" + ids.get(0)));
        boolean found = false;
        for (JsonNode h : hist.path("content")) {
            if ("progressState".equals(h.path("field").asText())
                    && "RECEIVED".equals(h.path("toStatus").asText())) {
                found = true;
            }
        }
        assertThat(found).as("진행상태 변경 이력: %s", hist).isTrue();
    }
}
