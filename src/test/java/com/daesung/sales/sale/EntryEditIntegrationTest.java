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
