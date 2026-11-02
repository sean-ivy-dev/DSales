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
 * 매출을 지우면 <b>빈 작업요청서가 남지 않는다</b> — 프론트 B-5.
 *
 * <p>지적 원문: "{@code DELETE /sales/{id}} 로 주문의 줄을 모두 지워도 묶여 있던 작업요청서가
 * <b>도서 줄 0개로 남습니다</b>({@code deleted=false}). 알림 배지에 그대로 잡힙니다 —
 * 시험에서 빈 건 2개를 지우자 21 → 19."
 *
 * <p>예전 코드는 "지시 목록은 매출에서 파생되니 자동으로 빠진다"고 두었다. 줄은 빠졌지만
 * <b>발송 건 자체가 남아</b> 물류가 쌀 것이 없는 지시를 받았다.
 *
 * <p>고정하는 것
 * <ul>
 *   <li>마지막 줄을 지우면 발송 건도 걷힌다(논리삭제 — 취소선으로 남는다)</li>
 *   <li><b>한 줄이라도 남으면 걷지 않는다</b> — 과하게 지우면 쌀 물건이 사라진다</li>
 *   <li><b>발송완료된 건은 건드리지 않는다</b> — 나간 사실은 매출을 지운다고 없어지지 않는다</li>
 *   <li>매출 <b>취소</b>도 같다(삭제만 지적됐지만 원인이 같다)</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("빈 작업요청서 정리(프론트 B-5)")
class EmptyWorkOrderCleanupIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-EW" + (System.nanoTime() % 1_000_000L);

    private Long partner;
    private Long wh;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "EWS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "EWP" + SFX, "name", "빈지시거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "EWW" + SFX, "name", "물류", "type", "MAIN"));
        book = createId("/masters/products", Map.of("code", "EWB" + SFX, "name", "빈지시도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70, "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2039-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 900))));
    }

    /** 매출 등록 한 번 → 그 줄들의 id. */
    private List<Long> sell(String date, int... qtys) {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int q : qtys) {
            items.add(Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", q,
                    "unitPrice", 10000, "supplyRate", 70,
                    "schoolCode", "EWSCH" + SFX, "schoolName", "빈지시학교"));
        }
        JsonNode r = post("/sales/entries", Map.of("salesDate", date,
                "partnerId", partner, "warehouseId", wh, "items", items));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        // 등록 응답엔 매출번호만 있고 id 가 없다 — 조회로 받아온다.
        List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode l : data(get("/sales?fromDate=" + date + "&toDate=" + date
                + "&partnerId=" + partner + "&size=50")).path("content")) {
            ids.add(l.path("id").asLong());
        }
        return ids;
    }

    private JsonNode orders(String date) {
        return data(get("/logistics/work-orders?fromDate=" + date + "&toDate=" + date
                + "&partnerId=" + partner)).path("content");
    }

    @Test
    @DisplayName("★마지막 줄을 지우면 작업요청서도 걷힌다")
    void 마지막줄_지우면_걷힌다() {
        String date = "2039-03-11";
        List<Long> ids = sell(date, 10);
        assertThat(orders(date)).as("등록하면 지시가 생긴다").hasSize(1);

        del("/sales/" + ids.get(0));

        JsonNode after = orders(date);
        // 행은 남되 **삭제 표시**가 붙는다(논리삭제 — 취소선으로 그린다).
        // 알림 배지는 삭제분을 세지 않으므로 빈 건이 더는 잡히지 않는다.
        for (JsonNode o : after) {
            assertThat(o.path("deleted").asBoolean())
                    .as("빈 지시가 살아 있으면 안 된다: %s", o).isTrue();
        }
    }

    @Test
    @DisplayName("한 줄이라도 남으면 걷지 않는다 — 과하게 지우면 쌀 물건이 사라진다")
    void 남으면_안걷는다() {
        String date = "2039-03-12";
        List<Long> ids = sell(date, 10, 20);   // 한 주문 두 줄
        assertThat(orders(date)).hasSize(1);

        del("/sales/" + ids.get(0));        // 한 줄만 지운다

        JsonNode after = orders(date);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).path("deleted").asBoolean())
                .as("줄이 남았으니 지시는 살아 있어야 한다").isFalse();
        assertThat(after.get(0).path("totalQty").asLong()).as("남은 줄의 수량").isEqualTo(20);
    }

    @Test
    @DisplayName("발송완료된 건은 매출을 지워도 그대로 둔다 — 나간 사실은 사라지지 않는다")
    void 발송완료는_안걷는다() {
        String date = "2039-03-13";
        List<Long> ids = sell(date, 10);
        long shipmentId = orders(date).get(0).path("id").asLong();

        // 발송정보 입력 = 발송완료 처리
        put("/logistics/work-orders/" + shipmentId + "/shipping",
                Map.of("boxCount", 1, "sentDate", date));

        del("/sales/" + ids.get(0));

        JsonNode after = orders(date);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).path("deleted").asBoolean())
                .as("발송완료 건은 걷지 않는다").isFalse();
    }

    @Test
    @DisplayName("매출 취소도 같다 — 지적은 삭제만이었지만 원인이 같다")
    void 취소도_걷힌다() {
        String date = "2039-03-14";
        List<Long> ids = sell(date, 10);
        assertThat(orders(date)).hasSize(1);

        post("/sales/" + ids.get(0) + "/cancel", Map.of("reason", "B-5 확인"));

        for (JsonNode o : orders(date)) {
            assertThat(o.path("deleted").asBoolean())
                    .as("취소로 비워진 지시도 걷힌다: %s", o).isTrue();
        }
    }
}
