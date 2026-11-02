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
 * 작업요청서 <b>삭제</b>(9/27 회의 항목 20 ②).
 *
 * <p>회의록 한 줄("확인되돌리기→삭제")만으로는 버튼 이름을 바꾸라는 것인지 기능을 바꾸라는
 * 것인지 갈렸는데, 레거시를 열어 확정했다 — {@code 작업요청서.vb:1181} 의 우클릭 '삭제'가
 * {@code sendData.isDelete = 2} 를 쓴다(주석: {@code 1:발주처에서 삭제 / 2:물류에서 삭제}).
 * <b>발송 건 자체를 없애는 동작</b>이다.
 *
 * <p>★<b>지우되 숨기지 않는다.</b> 레거시는 목록에서 빼지 않고 회색 취소선으로 남긴다
 * ({@code 768행}). 숨기면 "취소된 건"과 "원래 없던 건"이 구분되지 않아 담당자가
 * 같은 발송을 다시 만든다. 대신 출력·확인 같은 작업 대상에서는 빠진다(377·890·1016행).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("작업요청서 삭제(항목 20 ②)")
class WorkOrderDeleteIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-WD" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2098-04-09";
    private static final String RANGE = "?fromDate=2098-01-01&toDate=2098-12-31";

    private Long partner;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "WDS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "WDP" + SFX, "name", "삭제검증거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "WDW" + SFX, "name", "삭제검증창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "WDB" + SFX);
        b.put("name", "삭제검증 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "W2098A01");
        b.put("catName", "삭제검증");
        b.put("salesDivision", "교재");
        Long book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 500))));

        // ‼️발송 건은 (거래일자·거래처·학교·분류) 단위다. 테스트마다 **다른 학교**를 써서
        //   각자 자기 발송 건을 갖게 한다 — 하나를 공유하면 먼저 돈 테스트가 그걸 삭제해
        //   뒤 테스트는 "이미 삭제됨"을 만나 조용히 다른 것을 검증하게 된다(실제로 그렇게 깨졌다).
        for (String sch : List.of("WDS01", "WDS02", "WDS03", "WDS04")) {
            post("/sales/entries", Map.of(
                    "salesDate", DATE, "partnerId", partner, "warehouseId", wh,
                    "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                            "unitPrice", 10000, "supplyRate", 70, "qty", 10,
                            "schoolCode", sch, "schoolName", "삭제검증고" + sch))));
        }
    }

    private JsonNode row(String schoolCode) {
        for (JsonNode r : data(get("/logistics/work-orders" + RANGE + "&partnerId=" + partner)).path("content")) {
            if (schoolCode.equals(r.path("schoolCode").asText())) {
                return r;
            }
        }
        return null;
    }

    private Long shipmentId(String schoolCode) {
        JsonNode r = row(schoolCode);
        assertThat(r).as("발송 건이 만들어져야 한다: %s", schoolCode).isNotNull();
        return r.path("id").asLong();
    }

    @Test
    @DisplayName("★삭제해도 목록에 남는다 — 숨기면 '취소된 건'과 '원래 없던 건'이 구분되지 않는다")
    void 삭제해도_목록에_남는다() {
        Long id = shipmentId("WDS01");
        assertThat(row("WDS01").path("deleted").asBoolean()).as("처음엔 삭제 아님").isFalse();

        JsonNode r = del("/logistics/work-orders/" + id, Map.of("reason", "학교 요청 취소"));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).asBoolean()).isTrue();

        JsonNode after = row("WDS01");
        assertThat(after).as("목록에서 사라지면 안 된다").isNotNull();
        assertThat(after.path("id").asLong()).isEqualTo(id);
        assertThat(after.path("deleted").asBoolean()).as("취소선으로 표시할 근거").isTrue();
    }

    @Test
    @DisplayName("★삭제된 건은 출력·확인이 막힌다 — 레거시도 작업 루프에서 건너뛴다")
    void 삭제건은_작업대상_아님() {
        Long id = shipmentId("WDS02");
        del("/logistics/work-orders/" + id, Map.of());

        JsonNode print = post("/logistics/work-orders/" + id + "/print", null);
        JsonNode ack = post("/logistics/work-orders/" + id + "/acknowledge", null);

        assertThat(print.path("success").asBoolean()).as("출력: %s", print).isFalse();
        assertThat(print.path("error").path("message").asText()).contains("삭제된");
        assertThat(ack.path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("사유 없이도 삭제된다(A-3) — 대신 이력에 '(사유 미입력)'으로 남는다")
    void 사유는_선택() {
        Long id = shipmentId("WDS03");

        JsonNode r = del("/logistics/work-orders/" + id, Map.of());
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        JsonNode hist = data(get("/audit/status-history?entityType=SHIPMENT&entityId=" + id));
        JsonNode rows = hist.has("content") ? hist.path("content") : hist;
        boolean noted = false;
        for (JsonNode h : rows) {
            noted |= "deleted".equals(h.path("field").asText())
                    && h.path("reason").asText().contains("(사유 미입력)");
        }
        assertThat(noted).as("삭제 이력이 남고 사유 자리가 비어 있지 않다").isTrue();
    }

    @Test
    @DisplayName("이미 삭제된 건은 false — 두 번 눌러도 이력이 두 줄 생기지 않는다")
    void 중복_삭제() {
        Long id = shipmentId("WDS04");
        del("/logistics/work-orders/" + id, Map.of("reason", "첫 삭제"));

        JsonNode again = del("/logistics/work-orders/" + id, Map.of("reason", "두 번째"));

        assertThat(again.path("success").asBoolean()).isTrue();
        assertThat(data(again).asBoolean()).as("이미 원하는 상태라 false").isFalse();
    }
}
