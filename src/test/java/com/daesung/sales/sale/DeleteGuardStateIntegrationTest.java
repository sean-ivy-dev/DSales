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
    @DisplayName("‼️★발송에 **등록번호가 없어도** 거부한다 — 못 찾으면 통과하던 결함(프론트 §3)")
    void 등록번호_없는_발송도_거부() {
        String day = "2056-04-01";
        String entryNo = register(day, 6);
        moveTo(entryNo, day, "PRINT");          // 발송준비중

        // 이관분과 같은 모양 — **발송의 등록번호를 비운다**(매출에는 그대로 있다).
        clearShipmentEntryNo(day);

        JsonNode whole = del("/sales/entries/" + entryNo, Map.of("reason", "결함 재현"));
        assertThat(whole.path("success").asBoolean())
                .as("‼️여기서 성공하면 발송준비중 주문이 지워진다: %s", whole).isFalse();

        long saleId = firstSaleId(day);
        JsonNode one = del("/sales/" + saleId, Map.of("reason", "결함 재현"));
        assertThat(one.path("success").asBoolean())
                .as("‼️단건도 막혀야 한다: %s", one).isFalse();

        assertThat(liveQty(day)).as("줄이 그대로").isEqualTo(6);
    }

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

    // ───────────────────────── 도우미

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
