package com.daesung.sales.sale;

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
 * 거래순번으로 주문을 불러와 <b>통째로 고치고 지우기</b> — 10/27 명세 §3-6, 프론트 B-4.
 *
 * <p>명세 §3-6: "거래처별로 매일 1번부터 매긴다. … <b>이 순번으로 들어가 수정할 수 있게</b>
 * 한다. 레거시 출고 화면의 '거래순번'과 같다(PPT 3 캡처)."
 *
 * <p>★레거시({@code UC_TabPages.vb:158~176})는 거래순번 드롭다운을
 * {@code SELECT DISTINCT tradeSeq … WHERE tradeDate LIKE ? AND tradeClass=? AND custCode=?}
 * 로 채우고 <b>max+1</b> 을 덧붙인다. 마지막 항목을 고르면 버튼이 "(등록)", 기존 번호를
 * 고르면 "(수정)"이 된다. 수정·삭제는 그 묶음을 통째로 지우고 다시 넣는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("주문 통째 수정·삭제(§3-6 · 프론트 B-4)")
class EntryEditIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-EE" + (System.nanoTime() % 1_000_000L);

    private Long partner;
    private Long wh;
    private Long bookA;
    private Long bookB;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "EES" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "EEP" + SFX, "name", "주문수정거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "EEW" + SFX, "name", "물류", "type", "MAIN"));
        bookA = createId("/masters/products", Map.of("code", "EEA" + SFX, "name", "주문수정도서A",
                "contentType", "SELF", "price", 10000, "supplyRate", 70, "salesDivision", "교재"));
        bookB = createId("/masters/products", Map.of("code", "EEB" + SFX, "name", "주문수정도서B",
                "contentType", "SELF", "price", 10000, "supplyRate", 70, "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2043-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", bookA, "unitCost", 3000, "qty", 900),
                        Map.of("productId", bookB, "unitCost", 3000, "qty", 900))));
    }

    private Map<String, Object> item(Long book, int qty) {
        return Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", qty,
                "unitPrice", 10000, "supplyRate", 70,
                "schoolCode", "EESCH" + SFX, "schoolName", "주문수정학교");
    }

    private JsonNode register(String date, Map<String, Object>... items) {
        JsonNode r = post("/sales/entries", Map.of("salesDate", date,
                "partnerId", partner, "warehouseId", wh, "items", List.of(items)));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r);
    }

    private long liveQty(String date) {
        long sum = 0;
        for (JsonNode l : data(get("/sales?fromDate=" + date + "&toDate=" + date
                + "&partnerId=" + partner + "&size=50")).path("content")) {
            sum += l.path("qty").asLong();
        }
        return sum;
    }

    @Test
    @DisplayName("★등록 응답에 등록번호·거래순번이 실린다(B-4③)")
    void 등록응답에_번호() {
        JsonNode d = register("2043-03-01", item(bookA, 5));
        assertThat(d.path("entryNo").asText()).as("%s", d).startsWith("E-20430301-");
        assertThat(d.path("tradeSeq").asInt()).isPositive();
    }

    @Test
    @DisplayName("★주문을 통째로 바꾼다 — 등록번호·거래순번은 그대로, 줄과 재고는 새것")
    void 통째_수정() {
        String date = "2043-03-02";
        JsonNode first = register(date, item(bookA, 10), item(bookB, 20));
        String entryNo = first.path("entryNo").asText();
        int tradeSeq = first.path("tradeSeq").asInt();
        assertThat(liveQty(date)).isEqualTo(30);

        // 줄을 통째로 바꾼다(도서A 3부 하나만 남긴다)
        JsonNode raw = put("/sales/entries/" + entryNo, Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", wh,
                "items", List.of(item(bookA, 3))));
        assertThat(raw.path("success").asBoolean()).as("%s", raw).isTrue();
        JsonNode after = data(raw);

        assertThat(after.path("entryNo").asText()).as("등록번호는 그대로").isEqualTo(entryNo);
        assertThat(after.path("tradeSeq").asInt()).as("거래순번도 그대로").isEqualTo(tradeSeq);
        assertThat(liveQty(date)).as("예전 줄은 사라지고 새 줄만 남는다").isEqualTo(3);
    }

    @Test
    @DisplayName("주문을 통째로 지운다 — 줄 전부와 작업요청서까지(B-4②)")
    void 통째_삭제() {
        String date = "2043-03-03";
        String entryNo = register(date, item(bookA, 10), item(bookB, 5)).path("entryNo").asText();
        assertThat(liveQty(date)).isEqualTo(15);

        JsonNode r = del("/sales/entries/" + entryNo);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(r.path("data").asInt()).as("지운 줄 수").isEqualTo(2);

        assertThat(liveQty(date)).as("줄이 모두 빠진다").isZero();
        for (JsonNode o : data(get("/logistics/work-orders?fromDate=" + date
                + "&toDate=" + date + "&partnerId=" + partner)).path("content")) {
            assertThat(o.path("deleted").asBoolean())
                    .as("빈 작업요청서가 살아 있으면 안 된다: %s", o).isTrue();
        }
    }

    @Test
    @DisplayName("★발송완료된 주문은 수정을 거부한다 — 나간 것과 장부가 갈린다")
    void 발송완료는_거부() {
        String date = "2043-03-04";
        String entryNo = register(date, item(bookA, 7)).path("entryNo").asText();
        // ★(가)안(2026-11-16) — 등록 직후는 「접수완료」라 작업요청서에 안 나온다.
        //   발송완료까지 가려면 먼저 상품준비중으로 넘긴다(운영에서 담당자가 하는 조작).
        handOverToLogistics(date, date, partner);
        long shipmentId = data(get("/logistics/work-orders?fromDate=" + date + "&toDate=" + date
                + "&partnerId=" + partner)).path("content").get(0).path("id").asLong();
        put("/logistics/work-orders/" + shipmentId + "/shipping",
                Map.of("boxCount", 1, "sentDate", date));

        JsonNode r = put("/sales/entries/" + entryNo, Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", wh,
                "items", List.of(item(bookA, 1))));

        assertThat(r.path("success").asBoolean()).as("%s", r).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("발송완료");
        assertThat(liveQty(date)).as("거부됐으니 원래 줄 그대로").isEqualTo(7);
    }

    @Test
    @DisplayName("없는 등록번호는 404 — 드롭다운에 없는 번호를 받은 것이다")
    void 없는_등록번호() {
        JsonNode r = del("/sales/entries/E-20430101-999999");
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("NOT_FOUND");
    }

    // ─────────────────────────────────────────────────────────────────
    //  삭제 기준 = 수정 기준(PM 전달 2026-11-16 §2 "의도 아님. 수정과 같은 기준으로")
    //  ‼️여기가 비어 있으면 가드가 있어도 **지켜지는지 아무도 모른다.**
    //    실제로 가드를 넣은 뒤 전 테스트가 통과했는데, 그건 올바르다는 뜻이 아니라
    //    **그 상황을 만드는 테스트가 없었다**는 뜻이었다.
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★발송완료된 주문은 **삭제도** 거부한다 — 수정만 막으면 '지우고 다시 넣기'로 우회된다")
    void 발송완료는_삭제도_거부() {
        String date = "2043-04-01";
        String entryNo = register(date, item(bookA, 7)).path("entryNo").asText();
        shipOut(date);

        JsonNode r = del("/sales/entries/" + entryNo, Map.of("reason", "지워 보기"));
        assertThat(r.path("success").asBoolean()).as("%s", r).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("발송완료");
        assertThat(liveQty(date)).as("거부됐으니 줄이 그대로 남아 있어야 한다").isEqualTo(7);
    }

    @Test
    @DisplayName("★상품준비중도 거부 — 접수완료로 되돌리면 그때 지워진다")
    void 상품준비중은_거부_되돌리면_삭제() {
        String date = "2043-04-02";
        String entryNo = register(date, item(bookA, 6)).path("entryNo").asText();
        assertThat(handOverToLogistics(date, date, partner)).as("상품준비중으로 넘긴다").isPositive();

        JsonNode r = del("/sales/entries/" + entryNo, Map.of("reason", "지워 보기"));
        assertThat(r.path("success").asBoolean()).as("%s", r).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("접수완료 상태일 때만");

        // 되돌리는 길이 실제로 통해야 한다 — 안 통하면 담당자가 지울 방법이 없다.
        assertThat(post("/orders/progress-state", Map.of(
                "orderNos", List.of(entryNo), "toState", "RECEIVED", "reason", "수정 위해 되돌림"))
                .path("success").asBoolean()).isTrue();
        assertThat(del("/sales/entries/" + entryNo, Map.of("reason", "되돌린 뒤 삭제"))
                .path("success").asBoolean()).as("접수완료면 지워진다").isTrue();
        assertThat(liveQty(date)).isZero();
    }

    @Test
    @DisplayName("★단건 삭제(`DELETE /sales/{id}`)도 같은 기준 — 줄을 하나씩 지워 빠져나갈 수 없다")
    void 단건삭제도_같은기준() {
        String date = "2043-04-03";
        register(date, item(bookA, 5), item(bookB, 5));
        shipOut(date);

        long saleId = data(get("/sales?fromDate=" + date + "&toDate=" + date
                + "&partnerId=" + partner + "&size=10")).path("content").get(0).path("id").asLong();
        JsonNode r = del("/sales/" + saleId, Map.of("reason", "한 줄만 지워 보기"));
        assertThat(r.path("success").asBoolean()).as("%s", r).isFalse();
        assertThat(liveQty(date)).as("한 줄도 안 빠졌다").isEqualTo(10);
    }

    @Test
    @DisplayName("‼️「매출만(배송X)」은 상태와 무관하게 지워진다 — 물류가 넘겨받을 것이 없다")
    void 매출만은_상태무관_삭제() {
        String date = "2043-04-04";
        JsonNode d = data(post("/sales/entries", Map.of("salesDate", date, "partnerId", partner,
                "warehouseId", wh, "onlySales", true, "items", List.of(item(bookA, 4)))));
        String entryNo = d.path("entryNo").asText();

        // 발송 건이 아예 없으므로 가드가 걸릴 상태 자체가 없다.
        assertThat(del("/sales/entries/" + entryNo, Map.of("reason", "매출만 삭제"))
                .path("success").asBoolean()).as("%s", d).isTrue();
        assertThat(liveQty(date)).isZero();
    }

    @Test
    @DisplayName("★접수경로(orderChannel)가 통합매출조회 줄에 실린다 — 비어 있으면 '수기'(PM §3)")
    void 접수경로가_실린다() {
        String date = "2043-04-05";
        register(date, item(bookA, 2));   // 매출등록 화면은 이 칸을 보내지 않는다
        JsonNode row = rowOf(date);
        // ‼️판매채널(salesChannel = 학교 마스터 값)과 **다른 칸**이다. 이름이 비슷해 섞기 쉽다.
        // ★저장값이 비어 있어도 MANUAL 로 내려야 한다 — 주문조회가 이미 그 규칙이다.
        //   한쪽만 비워 내리면 같은 주문이 화면마다 다르게 보인다.
        assertThat(row.path("orderChannel").asText()).as("%s", row).isEqualTo("MANUAL");
        assertThat(row.path("orderChannelName").asText()).isEqualTo("수기");
    }

    @Test
    @DisplayName("★★온라인 주문을 고쳐도 '수기'로 바뀌지 않는다 — PM §3 이 짚은 결함")
    void 수정해도_접수경로_유지() {
        String date = "2043-04-06";
        // 특약점 주문과 같은 모양 — 접수경로를 명시해 등록한다.
        JsonNode d = data(post("/sales/entries", Map.of("salesDate", date, "partnerId", partner,
                "warehouseId", wh, "channel", "ONLINE", "items", List.of(item(bookA, 3)))));
        String entryNo = d.path("entryNo").asText();
        assertThat(rowOf(date).path("orderChannel").asText()).isEqualTo("ONLINE");

        // ‼️매출 직접 입력 창은 channel 을 보내지 않는다 — 그 상태로 수정해 본다.
        //   그전에는 지우고 다시 만드는 과정에서 값이 사라져 '수기'가 됐다.
        assertThat(put("/sales/entries/" + entryNo, Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", wh,
                "items", List.of(item(bookA, 9)))).path("success").asBoolean()).isTrue();

        JsonNode after = rowOf(date);
        assertThat(after.path("orderChannel").asText())
                .as("고쳤다고 특약점 주문이 수기가 되면 안 된다: %s", after).isEqualTo("ONLINE");
        assertThat(after.path("qty").asInt()).as("수정 자체는 됐다").isEqualTo(9);
    }

    /** 그 날 그 거래처의 첫 매출 줄. */
    private JsonNode rowOf(String date) {
        return data(get("/sales?fromDate=" + date + "&toDate=" + date
                + "&partnerId=" + partner + "&size=10")).path("content").get(0);
    }

    /** 그 날 주문을 발송완료까지 보낸다 — 물류 인계 → 발송 처리. */
    private void shipOut(String date) {
        handOverToLogistics(date, date, partner);
        long shipmentId = data(get("/logistics/work-orders?fromDate=" + date + "&toDate=" + date
                + "&partnerId=" + partner)).path("content").get(0).path("id").asLong();
        assertThat(put("/logistics/work-orders/" + shipmentId + "/shipping",
                Map.of("boxCount", 1, "sentDate", date)).path("success").asBoolean())
                .as("발송 처리").isTrue();
    }

    @Test
    @DisplayName("「매출만(배송X)」 체크 상태가 조회에 그대로 실린다(B-4④)")
    void 매출만_체크상태() {
        String date = "2043-03-05";
        post("/sales/entries", Map.of("salesDate", date, "partnerId", partner,
                "warehouseId", wh, "onlySales", true, "items", List.of(item(bookA, 4))));

        JsonNode row = data(get("/sales?fromDate=" + date + "&toDate=" + date
                + "&partnerId=" + partner + "&size=10")).path("content").get(0);
        // ‼️"발송이 없다"로 유추하면 특강·창고없음 건까지 걸린다 — 체크한 사실을 저장해야 한다.
        assertThat(row.path("onlySales").asBoolean()).as("%s", row).isTrue();
    }
}
