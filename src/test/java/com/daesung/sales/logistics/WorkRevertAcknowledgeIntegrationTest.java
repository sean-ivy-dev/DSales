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
 * 작업요청서 되돌리기 · 확인 표시. 근거: 프론트 회신(2026-09-17) B-14 —
 *
 * <pre>
 *   "되돌리기는 아직 준비 중입니다 — 한번 올린 출력 기록은 내릴 수 없습니다."
 *   "'확인' 표시는 아직 준비 중입니다 — 지금은 출력 여부로만 진행 단계를 봅니다."
 * </pre>
 *
 * <p>★<b>되돌리기는 기록을 지우는 기능</b>이라, 여는 것 자체보다 <b>흔적을 남기는지</b>가 핵심이다.
 * 출력 시각의 의미는 "언제 처음 작업지시가 나갔나"인데 사유 없이 내릴 수 있으면
 * 나중에 "이 건은 왜 지시가 안 나간 걸로 되어 있나"에 아무도 답하지 못한다.
 * 그래서 사유를 강제하고 {@code status_history}에 남는 것까지 여기서 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("작업요청서 되돌리기·확인(2026-09-17 B-14)")
class WorkRevertAcknowledgeIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-RV" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2095;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long partner;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "RVS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "RVP" + SFX, "name", "되돌리기거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "RVW" + SFX, "name", "되돌리기창고", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of("code", "RVB" + SFX, "name", "되돌리기교재",
                "contentType", "SELF", "price", 10000, "supplyRate", 75, "salesDivision", "교재"));

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));
        post("/sales/entries", Map.of("salesDate", YEAR + "-02-10", "partnerId", partner,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", 30,
                        "schoolCode", "RV1" + SFX, "schoolName", "되돌리기고"))));

        // ★(가)안(2026-11-16) — 등록 직후는 「접수완료」라 작업요청서에 안 나온다.
        //   담당자가 상품준비중으로 넘긴 주문부터 나온다(발주처 확정).
        handOverToLogistics(YEAR + "-01-01", YEAR + "-12-31", partner);
    }

    /**
     * 발송 건 id + <b>출력·확인 표시를 내려 초기화</b>.
     *
     * <p>‼️이 클래스의 테스트는 발송 건 <b>하나</b>를 공유한다. 초기화하지 않으면 앞 테스트가
     * 켜 둔 표시를 뒤 테스트가 물려받아, 실행 순서에 따라 깨진다(실제로 겪었다 —
     * '출력됐지만 미확인'이 먼저 돌면 '처음 확인'이 false가 된다).
     */
    private Long shipmentId() {
        long id = data(get("/logistics/work-orders" + RANGE + "&partnerId=" + partner)).path("content")
                .get(0).path("id").asLong();
        post("/logistics/work-orders/" + id + "/print/revert", Map.of("reason", "테스트 초기화"));
        post("/logistics/work-orders/" + id + "/acknowledge/revert", Map.of("reason", "테스트 초기화"));
        return id;
    }

    private JsonNode row(Long id) {
        for (JsonNode r : data(get("/logistics/work-results" + RANGE + "&partnerId=" + partner)).path("content")) {
            if (r.path("id").asLong() == id) {
                return r;
            }
        }
        return null;
    }

    @Test
    @DisplayName("★출력을 되돌리면 미출력분으로 돌아온다")
    void 출력_되돌리기() {
        Long id = shipmentId();
        post("/logistics/work-orders/" + id + "/print", null);

        JsonNode r = post("/logistics/work-orders/" + id + "/print/revert",
                Map.of("reason", "수량 오기로 재출력 필요"));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).asBoolean()).as("실제로 내려갔다").isTrue();
        assertThat(row(id).path("printed").asBoolean()).isFalse();

        // 미출력 필터에 다시 잡힌다 — 되돌렸는데 목록에 안 나오면 재출력을 할 수 없다.
        boolean found = false;
        for (JsonNode o : data(get("/logistics/work-orders" + RANGE
                + "&partnerId=" + partner + "&printed=false")).path("content")) {
            found |= o.path("id").asLong() == id;
        }
        assertThat(found).as("미출력분 목록에 돌아온다").isTrue();

        // 되돌린 뒤 다시 출력하면 '최초 출력'으로 다시 선다(덮기 방지 규칙은 현재 값 기준이다).
        assertThat(data(post("/logistics/work-orders/" + id + "/print", null)).asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★사유 없이도 되돌릴 수 있다(9/27 A-3 — 작업요청서 사유칸 제거)")
    void 사유는_선택() {
        Long id = shipmentId();
        post("/logistics/work-orders/" + id + "/print", null);

        // 사유 칸이 빠진 화면은 빈 값이나 본문 없이 호출한다. 둘 다 통과해야 한다.
        JsonNode blank = post("/logistics/work-orders/" + id + "/print/revert", Map.of("reason", " "));
        assertThat(blank.path("success").asBoolean()).as("공백 사유: %s", blank).isTrue();
        assertThat(row(id).path("printed").asBoolean()).as("되돌려졌다").isFalse();

        post("/logistics/work-orders/" + id + "/print", null);
        JsonNode missing = post("/logistics/work-orders/" + id + "/print/revert", Map.of());
        assertThat(missing.path("success").asBoolean()).as("사유 누락: %s", missing).isTrue();

        // ‼️사유가 없어도 이력은 남는다 — 비워 두면 기록 누락과 구분이 안 된다.
        JsonNode hist = data(get("/audit/status-history?entityType=SHIPMENT&entityId=" + id));
        JsonNode rows = hist.has("content") ? hist.path("content") : hist;
        boolean noted = false;
        for (JsonNode h : rows) {
            noted |= h.path("reason").asText().contains("(사유 미입력)");
        }
        assertThat(noted).as("'(사유 미입력)'으로 남는다").isTrue();
    }

    @Test
    @DisplayName("★되돌린 기록이 상태변경 이력에 남는다 — 이게 없으면 그냥 지우는 것과 같다")
    void 이력에_남는다() {
        Long id = shipmentId();
        post("/logistics/work-orders/" + id + "/print", null);
        post("/logistics/work-orders/" + id + "/print/revert", Map.of("reason", "학교 변경으로 취소"));

        JsonNode hist = data(get("/audit/status-history?entityType=SHIPMENT&entityId=" + id));
        JsonNode rows = hist.has("content") ? hist.path("content") : hist;

        // ‼️정렬 순서에 기대지 않는다 — 이 발송 건에는 초기화분까지 여러 번의 되돌림이 쌓인다.
        //   "마지막 줄"을 집으면 정렬이 desc냐 asc냐에 따라 엉뚱한 줄을 본다(실제로 겪었다).
        JsonNode revert = null;
        for (JsonNode h : rows) {
            if ("printed".equals(h.path("field").asText())
                    && "false".equals(h.path("toStatus").asText())
                    && "학교 변경으로 취소".equals(h.path("reason").asText())) {
                revert = h;
            }
        }
        assertThat(revert).as("사유가 그대로 남은 되돌림 이력: %s", rows).isNotNull();
        assertThat(revert.path("fromStatus").asText()).as("출력됐던 상태에서 내려갔다").isEqualTo("true");
        assertThat(revert.path("changedBy").asText()).as("누가 내렸는지").isNotEmpty();
    }

    @Test
    @DisplayName("★확인 표시 — 출력 다음 단계로 따로 선다")
    void 확인_표시() {
        Long id = shipmentId();

        assertThat(data(post("/logistics/work-orders/" + id + "/acknowledge", null)).asBoolean())
                .as("처음 확인").isTrue();
        assertThat(data(post("/logistics/work-orders/" + id + "/acknowledge", null)).asBoolean())
                .as("재확인은 최초 기록을 덮지 않는다").isFalse();

        JsonNode row = row(id);
        assertThat(row.path("acknowledged").asBoolean()).isTrue();
        assertThat(row.path("acknowledgedBy").asText()).isNotEmpty();
        // ⚠️레거시 '완료'와 다른 축이다 — 확인했다고 완료가 켜지면 화면 의미가 달라진다.
        assertThat(row.path("completed").asBoolean()).as("완료는 여전히 false").isFalse();

        post("/logistics/work-orders/" + id + "/acknowledge/revert", Map.of("reason", "오확인"));
        assertThat(row(id).path("acknowledged").asBoolean()).as("되돌려진다").isFalse();
    }

    @Test
    @DisplayName("★'지시는 나갔는데 아직 확인 안 된 건' — printed=true & acknowledged=false")
    void 출력됐지만_미확인() {
        Long id = shipmentId();
        post("/logistics/work-orders/" + id + "/print", null);

        boolean found = false;
        for (JsonNode o : data(get("/logistics/work-orders" + RANGE + "&partnerId=" + partner
                + "&printed=true&acknowledged=false")).path("content")) {
            found |= o.path("id").asLong() == id;
        }
        assertThat(found).as("출력됐고 미확인이면 잡힌다").isTrue();

        post("/logistics/work-orders/" + id + "/acknowledge", null);
        for (JsonNode o : data(get("/logistics/work-orders" + RANGE + "&partnerId=" + partner
                + "&printed=true&acknowledged=false")).path("content")) {
            assertThat(o.path("id").asLong()).as("확인하면 빠진다").isNotEqualTo(id);
        }
    }
}
