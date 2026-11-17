package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 매출 삭제 가드 — <b>네 상태 전부</b>에서 단건·통째 삭제가 거부되는지(프론트 요청 2026-11-17 §3).
 *
 * <h3>‼️프론트가 짚은 결함 — 가드가 발송 건을 <b>못 찾으면 그냥 통과</b>했다</h3>
 * 가드는 {@code findKey}(일자·거래처·학교·분류·<b>등록번호</b>)로 발송을 찾는데,
 * <b>등록번호가 정확히 일치</b>해야 찾는다. 그런데 <b>매출에는 등록번호가 있고 발송에는 없는</b>
 * 짝이 운영에 흔하다(이관분이 그 모양 — 살아 있는 발송 34,517건 중 34,515건이 비어 있다).
 * → 발송이 <b>발송준비중인데도</b> 가드가 "발송 건 없음"으로 보고 <b>삭제를 허용</b>했다.
 * 프론트가 운영에서 그 사례(#97598)를 찾아냈다.
 *
 * <p>★<b>빈 작업요청서 때와 같은 함정</b>이다({@code ShipmentRepository.findForCleanup} 주석).
 * 그때 넓힌 조회를 가드에서는 쓰지 않았다.
 *
 * <h3>갈라야 하는 두 경우(프론트 §3)</h3>
 * <ul>
 *   <li><b>발송 건이 없는 매출</b>(매출만·특강·창고없음) → <b>삭제 허용</b>.
 *       물류가 넘겨받을 것이 없다.</li>
 *   <li><b>발송 건을 못 찾음</b>(발송은 있는데 키가 안 맞음) → <b>거부</b>.
 *       모른다는 사실이 허가로 바뀌면 안 된다.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("매출 삭제 가드 — 네 상태 · 발송 못 찾음(2026-11-17)")
class DeleteGuardStateIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "DG" + (System.nanoTime() % 1_000_000L);

    @Autowired
    private com.daesung.sales.logistics.repository.ShipmentRepository shipmentRepository;

    @Autowired
    private EntityManager em;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private Long partner;
    private Long wh;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "DGS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "DGP" + SFX, "name", "삭제가드거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "DGW" + SFX, "name", "물류", "type", "MAIN"));
        Map<String, Object> b = new HashMap<>();
        b.put("code", "DGB" + SFX);
        b.put("name", "삭제가드도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "G2056A01");
        b.put("catName", "삭제가드");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);
        assertThat(post("/stock/inbound", Map.of("processedDate", "2056-01-05",
                "supplierClientId", sup, "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 900))))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★네 상태 전부에서 **단건·통째 삭제가 모두 거부**된다")
    void 네상태_모두_거부() {
        record Case(String day, String to, String label) { }
        List<Case> cases = List.of(
                new Case("2056-03-01", "INSPECTING", "상품검수"),
                new Case("2056-03-02", "PREPARING", "상품준비중"),
                new Case("2056-03-03", "PRINT", "발송준비중"),
                new Case("2056-03-04", "SENT", "발송완료"));

        for (Case c : cases) {
            String entryNo = register(c.day(), 4);
            moveTo(entryNo, c.day(), c.to());

            // ① 통째 삭제
            JsonNode whole = del("/sales/entries/" + entryNo, Map.of("reason", "거부 확인"));
            assertThat(whole.path("success").asBoolean())
                    .as("%s — 통째 삭제는 거부돼야 한다: %s", c.label(), whole).isFalse();

            // ② 단건 삭제
            long saleId = firstSaleId(c.day());
            JsonNode one = del("/sales/" + saleId, Map.of("reason", "거부 확인"));
            assertThat(one.path("success").asBoolean())
                    .as("%s — 단건 삭제도 거부돼야 한다: %s", c.label(), one).isFalse();

            assertThat(liveQty(c.day())).as("%s — 줄이 그대로 남아야 한다", c.label()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("★이관분(매출·발송 둘 다 등록번호 없음)은 진행됐으면 거부한다")
    void 이관분도_거부() {
        String day = "2056-04-01";
        String entryNo = register(day, 6);
        moveTo(entryNo, day, "PRINT");          // 발송준비중

        // ★이관분과 같은 모양 — **매출과 발송 양쪽** 등록번호를 비운다.
        //   운영의 이관분이 이 모양이다(매출 entry_no NULL · 발송 entry_no NULL).
        clearEntryNoOf(day);
        clearSaleEntryNoOf(day);

        long saleId = firstSaleId(day);
        JsonNode one = del("/sales/" + saleId, Map.of("reason", "결함 재현"));
        assertThat(one.path("success").asBoolean())
                .as("‼️진행된 발송이 있는 이관분이 지워지면 안 된다: %s", one).isFalse();
        assertThat(liveQty(day)).as("줄이 그대로").isEqualTo(6);
    }

    /**
     * ‼️<b>삭제한 테스트에 대한 기록</b> — 11/16 에 「매출엔 등록번호가 있고 발송엔 없는」
     * 조합을 거부로 고정했는데, <b>그 조합이 바로 「거슬러 넣은 반품」이었다.</b>
     * 운영 실측에서 그 조합은 1건이고 그것이 반품이었다(프론트 지적 2026-11-17 §5).
     * 발송은 {@code ensureFor} 가 매출의 등록번호로 만들므로 <b>같은 주문에서 둘이 어긋날 수
     * 없다</b> — 그 테스트는 현실에 없는 시나리오를 거부로 굳혀 **오탐을 만들었다.**
     * 지켜야 할 규칙은 위 {@code 이관분도_거부}(둘 다 비어 있을 때)다.
     */

    @Test
    @DisplayName("‼️「매출만(배송X)」은 그대로 지워진다 — '발송 없음'과 '못 찾음'을 갈라야 한다")
    void 매출만은_허용() {
        String day = "2056-05-01";
        JsonNode d = data(post("/sales/entries", Map.of("salesDate", day, "partnerId", partner,
                "warehouseId", wh, "onlySales", true,
                "items", List.of(item(3)))));
        String entryNo = d.path("entryNo").asText();
        assertThat(shipmentsOf(day)).as("발송 건이 아예 없다").isEmpty();

        assertThat(del("/sales/entries/" + entryNo, Map.of("reason", "매출만 삭제"))
                .path("success").asBoolean()).as("%s", d).isTrue();
        assertThat(liveQty(day)).isZero();
    }

    @Test
    @DisplayName("★★발송완료 건의 **수량**은 못 고친다 — 재고가 없는 물건을 되돌리게 된다")
    void 발송완료_수량수정_거부() {
        String day = "2056-06-01";
        register(day, 40);
        moveTo(entryNoOf(day), day, "SENT");

        long saleId = firstSaleId(day);
        JsonNode r = patch("/sales/" + saleId, Map.of("qty", 10));
        assertThat(r.path("success").asBoolean())
                .as("‼️여기서 성공하면 재고 30부가 창고에 없는 채로 돌아온다: %s", r).isFalse();
        // ★발주처가 지정한 문구를 그대로 쓴다(요청 2026-11-17 §4①) — 화면이 이걸 띄운다.
        assertThat(r.path("error").path("message").asText())
                .isEqualTo("주문이 접수완료 상태가 아니라 수량을 바꿀 수 없습니다. "
                        + "수량을 고치려면 주문 조회/등록에서 접수완료로 되돌린 뒤 수정해 주시기 바랍니다.");
        assertThat(liveQty(day)).as("수량이 그대로여야 한다").isEqualTo(40);
    }

    @Test
    @DisplayName("‼️금액·공급률은 발송완료 뒤에도 고칠 수 있다 — 바로잡는 길을 막지 않는다")
    void 발송완료_금액수정_허용() {
        String day = "2056-06-02";
        register(day, 5);
        moveTo(entryNoOf(day), day, "SENT");

        long saleId = firstSaleId(day);
        // ★재고를 건드리지 않는 칸이라 통과해야 한다. 여기서 막으면 발송 뒤 금액 오타를
        //   고칠 길이 없어진다(프론트가 [수정]을 잠그지 않은 이유).
        JsonNode r = patch("/sales/" + saleId, Map.of("supplyRate", 60, "memo", "공급률 정정"));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(liveQty(day)).as("수량은 그대로").isEqualTo(5);
    }

    @Test
    @DisplayName("같은 수량으로 다시 보내면 통과한다 — '바뀌지 않았으면' 막을 이유가 없다")
    void 같은수량은_통과() {
        String day = "2056-06-03";
        register(day, 7);
        moveTo(entryNoOf(day), day, "SENT");

        long saleId = firstSaleId(day);
        JsonNode r = patch("/sales/" + saleId, Map.of("qty", 7, "memo", "메모만 고침"));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(liveQty(day)).isEqualTo(7);
    }

    @Test
    @DisplayName("★★이관 발송이 있는 날짜·거래처로 **거슬러 넣은 반품**은 막히지 않는다(프론트 §5)")
    void 거슬러넣은_반품은_통과() {
        String day = "2056-08-08";
        // ① 그 날짜·거래처·학교·분류로 **진행된 이관 발송**을 만든다(등록번호 없음).
        register(day, 5);
        moveTo(entryNoOf(day), day, "PRINT");     // 발송준비중까지 진행
        clearEntryNoOf(day);                      // 이관분과 같은 모양

        // ② 같은 날짜·거래처로 **반품**을 넣는다 — 반품은 발송을 만들지 않는다.
        JsonNode r = post("/sales/return-inbound", Map.of(
                "returnDate", day, "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 2,
                        "unitPrice", 10000, "supplyRate", 70))));
        assertThat(r.path("success").asBoolean()).as("반품 등록: %s", r).isTrue();

        long returnSaleId = -1;
        for (JsonNode n : data(get("/sales?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner + "&size=50")).path("content")) {
            if ("반품".equals(n.path("shipmentTypeName").asText())) {
                returnSaleId = n.path("id").asLong();
            }
        }
        assertThat(returnSaleId).as("반품 줄을 찾아야 한다").isPositive();

        // ③ ‼️반품 줄의 수량 수정·삭제가 막히면 안 된다.
        //   가드가 「등록번호 빈 발송」까지 넓게 보면, 같은 네 칸의 **이관 발송**이 걸려
        //   주문과 무관한 반품이 막힌다(발주처 §4③ "주문과 연결되지 않은 매출은 제한 제외").
        JsonNode upd = patch("/sales/" + returnSaleId, Map.of("qty", 1));
        assertThat(upd.path("success").asBoolean())
                .as("‼️반품 수량 수정이 막혔다 — 이관 발송에 걸린 것이다: %s", upd).isTrue();

        JsonNode del = del("/sales/" + returnSaleId, Map.of("reason", "반품 취소"));
        assertThat(del.path("success").asBoolean())
                .as("‼️반품 삭제가 막혔다: %s", del).isTrue();
    }

    @Test
    @DisplayName("★★여러 건 삭제 — 하나라도 막히면 **아무것도 지우지 않는다**(발주처 §2③)")
    void 일괄삭제_하나막히면_전체중단() {
        String okDay = "2056-09-01";
        String blockedDay = "2056-09-02";
        register(okDay, 3);                       // 접수완료 — 지울 수 있다
        String b = register(blockedDay, 4);
        moveTo(b, blockedDay, "PRINT");           // 발송준비중 — 막힌다

        long okId = firstSaleId(okDay);
        long blockedId = firstSaleId(blockedDay);

        JsonNode r = post("/sales/bulk-delete", Map.of(
                "saleIds", List.of(okId, blockedId), "reason", "일괄 취소"));
        assertThat(r.path("success").asBoolean()).as("막혀도 200: %s", r).isTrue();
        JsonNode d = data(r);
        assertThat(d.path("requested").asInt()).isEqualTo(2);
        assertThat(d.path("deleted").asInt())
                .as("‼️0 이어야 한다 — 하나라도 막히면 아무것도 지우지 않는다").isZero();
        assertThat(d.path("blocked").asInt()).isEqualTo(1);
        assertThat(d.path("results")).as("막힌 건만 담는다").hasSize(1);
        assertThat(d.path("results").get(0).path("saleId").asLong()).isEqualTo(blockedId);
        assertThat(d.path("results").get(0).path("message").asText()).isNotEmpty();

        // ★지울 수 있던 건도 **그대로 남아 있어야** 한다
        assertThat(liveQty(okDay)).as("통과 건도 안 지워졌다").isEqualTo(3);
        assertThat(liveQty(blockedDay)).isEqualTo(4);
    }

    @Test
    @DisplayName("여러 건 삭제 — 전부 통과하면 한 번에 지워진다. 사유는 비워도 된다")
    void 일괄삭제_전부통과() {
        String day = "2056-09-03";
        register(day, 2);
        register(day, 7);
        long[] ids = data(get("/sales?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner + "&size=50")).path("content")
                .findValuesAsText("id").stream().mapToLong(Long::parseLong).toArray();
        assertThat(ids).hasSize(2);

        // ★사유를 안 보낸다(단건 삭제와 같은 규칙 — 프론트 §2-1)
        JsonNode d = data(post("/sales/bulk-delete",
                Map.of("saleIds", List.of(ids[0], ids[1]))));
        assertThat(d.path("deleted").asInt()).isEqualTo(2);
        assertThat(d.path("blocked").asInt()).isZero();
        assertThat(d.path("results")).isEmpty();
        assertThat(liveQty(day)).isZero();
    }

    // ───────────────────────── 도우미

    /** 그 날 **매출** 줄의 등록번호를 비운다(이관분 모양). */
    private void clearSaleEntryNoOf(String day) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(st -> {
                    em.createQuery("update Sale s set s.entryNo = null"
                                    + " where s.salesDate = :d and s.partner.id = :p")
                            .setParameter("d", java.time.LocalDate.parse(day))
                            .setParameter("p", partner)
                            .executeUpdate();
                    em.clear();
                });
    }

    /** 그 날 발송들의 등록번호를 비운다(이관분 모양). */
    private void clearEntryNoOf(String day) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(st -> {
                    em.createQuery("update Shipment s set s.entryNo = null"
                                    + " where s.tradeDate = :d and s.partner.id = :p")
                            .setParameter("d", java.time.LocalDate.parse(day))
                            .setParameter("p", partner)
                            .executeUpdate();
                    em.clear();
                });
    }

    /** 그 날 등록한 주문의 등록번호. */
    private String entryNoOf(String day) {
        for (JsonNode n : data(get("/sales?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner + "&size=10")).path("content")) {
            String e = n.path("entryNo").asText(null);
            if (e != null && !e.isBlank()) {
                return e;
            }
        }
        throw new AssertionError("등록번호를 못 찾았다: " + day);
    }

    private Map<String, Object> item(int qty) {
        return Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", qty,
                "unitPrice", 10000, "supplyRate", 70);
    }

    private String register(String day, int qty) {
        JsonNode r = post("/sales/entries", Map.of("salesDate", day, "partnerId", partner,
                "warehouseId", wh, "items", List.of(item(qty))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r).path("entryNo").asText();
    }

    /** 그 주문을 목표 상태로 옮긴다. PRINT·SENT 는 물류 조작이라 작업요청서 API 를 쓴다. */
    private void moveTo(String entryNo, String day, String to) {
        if ("INSPECTING".equals(to) || "PREPARING".equals(to)) {
            assertThat(data(post("/orders/progress-state", Map.of("orderNos", List.of(entryNo),
                    "toState", to, "reason", "상태 준비"))).path("changed").asInt())
                    .as("%s 로 바뀌어야 한다", to).isEqualTo(1);
            return;
        }
        // 물류 축 — 작업요청서에 보이게 먼저 상품준비중으로 넘긴다.
        handOverToLogistics(day, day, partner);
        long shipmentId = data(get("/logistics/work-orders?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner)).path("content").get(0).path("id").asLong();
        if ("PRINT".equals(to)) {
            assertThat(post("/logistics/work-orders/" + shipmentId + "/print", null)
                    .path("success").asBoolean()).isTrue();
        } else {
            assertThat(put("/logistics/work-orders/" + shipmentId + "/shipping",
                    Map.of("boxCount", 1, "sentDate", day)).path("success").asBoolean()).isTrue();
        }
    }

    /** 이관분과 같은 모양 — 그 날 발송들의 등록번호를 비운다. */
    private void clearShipmentEntryNo(String day) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(st -> {
                    em.createQuery("update Shipment s set s.entryNo = null"
                                    + " where s.tradeDate = :d and s.partner.id = :p")
                            .setParameter("d", java.time.LocalDate.parse(day))
                            .setParameter("p", partner)
                            .executeUpdate();
                    em.clear();
                });
    }

    private List<com.daesung.sales.logistics.entity.Shipment> shipmentsOf(String day) {
        return shipmentRepository.findAll().stream()
                .filter(s -> s.getDeletedAt() == null)
                .filter(s -> day.equals(String.valueOf(s.getTradeDate())))
                .filter(s -> partner.equals(s.getPartner().getId()))
                .toList();
    }

    private long firstSaleId(String day) {
        return data(get("/sales?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner + "&size=10")).path("content").get(0).path("id").asLong();
    }

    private long liveQty(String day) {
        long sum = 0;
        for (JsonNode l : data(get("/sales?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner + "&size=50")).path("content")) {
            sum += l.path("qty").asLong();
        }
        return sum;
    }
}
