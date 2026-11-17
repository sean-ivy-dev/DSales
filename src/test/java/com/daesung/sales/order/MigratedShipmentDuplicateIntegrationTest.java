package com.daesung.sales.order;

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
 * 이관이 <b>한 묶음을 발송 여럿으로 쪼개</b> 넣어 수량·금액이 중복되던 것(2026-11-17).
 *
 * <h3>무엇이 틀렸나 — 프론트 지적 §1</h3>
 * 10/31 자사몰 실측: 발송 <b>16건</b>이 (학교 89001 · 기타고사)로 전부 같고 거래순번만 1~16.
 * 그런데 그 날 매출 198줄은 거래순번이 <b>전부 1</b>이라 <b>줄을 발송별로 가를 축이 없다.</b>
 * → 조회가 발송마다 198줄을 전부 붙여 <b>같은 값이 16번</b> 더해졌고,
 * 금액이 <b>4.66억 → 17.3억(3.7배)</b>으로 보였다.
 *
 * <h3>고친 방식 — (가) + (나)</h3>
 * <ul>
 *   <li><b>주문조회</b>: 등록번호 없는 발송은 (거래일·거래처·학교·분류) 네 칸을 주문 키로
 *       써서 <b>한 줄로 합친다</b>({@code OrderProgress.orderKey}).</li>
 *   <li><b>줄 부착</b>: 같은 키를 공유하는 발송이 여럿이면 <b>대표(가장 작은 id)에만</b>
 *       붙인다({@code WorkService.buildWorkOrders}). 키만 합치고 이걸 안 하면
 *       <b>합친 줄에서 그대로 중복</b>된다.</li>
 * </ul>
 *
 * <p>‼️이 테스트가 없으면 중복이 조용히 되살아난다 — 합계가 틀려도 화면은 정상으로 보인다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("이관 발송 중복 — 수량·금액이 발송 수만큼 부풀지 않는다(2026-11-17)")
class MigratedShipmentDuplicateIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "MD" + (System.nanoTime() % 1_000_000L);
    private static final String DAY = "2064-07-07";
    private static final String SCHOOL = "MDS" + (System.nanoTime() % 100_000L);

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
                Map.of("code", "MDS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "MDP" + SFX, "name", "중복검증거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "MDW" + SFX, "name", "물류", "type", "MAIN"));
        Map<String, Object> b = new HashMap<>();
        b.put("code", "MDB" + SFX);
        b.put("name", "중복검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "M2064A01");
        b.put("catName", "중복검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);
        assertThat(post("/stock/inbound", Map.of("processedDate", "2064-01-05",
                "supplierClientId", sup, "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 500))))
                .path("success").asBoolean()).isTrue();

        // 매출 1건 — 수량 20. 발송 1건이 생긴다.
        assertThat(post("/sales/entries", Map.of("salesDate", DAY, "partnerId", partner,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 20, "unitPrice", 10000, "supplyRate", 70,
                        "schoolCode", SCHOOL, "schoolName", "중복검증학교"))))
                .path("success").asBoolean()).isTrue();

        // ★이관과 같은 모양을 만든다 — 등록번호를 비우고, **같은 네 칸 키의 발송을 2건 더** 넣는다.
        //   거래순번만 2·3 으로 다르다(운영의 10/31 자사몴 16건이 이 모양이다).
        splitLikeMigration();
    }

    @Test
    @DisplayName("★★주문조회 — 발송 3건이 **한 줄**로 합쳐지고 수량이 20 그대로다(60 이 아니다)")
    void 주문조회_합쳐지고_중복없다() {
        JsonNode mine = null;
        for (JsonNode r : data(get("/orders?fromDate=" + DAY + "&toDate=" + DAY
                + "&partnerId=" + partner + "&size=50")).path("content")) {
            mine = r;
        }
        assertThat(mine).as("주문 줄을 찾아야 한다").isNotNull();

        assertThat(mine.path("shipmentIds")).as("갈린 발송 3건이 한 줄에 담긴다").hasSize(3);
        assertThat(mine.path("totalQty").asLong())
                .as("‼️60 이면 발송 수만큼 중복된 것이다: %s", mine).isEqualTo(20);
        assertThat(mine.path("totalAmount").asLong())
                .as("금액도 한 번만: 10,000 × 70%% × 20").isEqualTo(140_000);
        assertThat(mine.path("lines")).as("줄도 한 번만").hasSize(1);
    }

    @Test
    @DisplayName("★작업요청서 — 발송 3건이 **그대로 3줄**이고, 줄은 대표 1건에만 붙는다")
    void 작업요청서_발송단위유지_줄은한번() {
        handOverToLogistics(DAY, DAY, partner);
        JsonNode rows = data(get("/logistics/work-orders?fromDate=" + DAY + "&toDate=" + DAY
                + "&partnerId=" + partner + "&size=50")).path("content");

        assertThat(rows).as("발송 단위를 유지해야 출력·발송 처리를 할 수 있다").hasSize(3);

        long withLines = 0;
        long qtySum = 0;
        for (JsonNode r : rows) {
            if (r.path("lines").size() > 0) {
                withLines++;
            }
            qtySum += r.path("totalQty").asLong();
        }
        assertThat(withLines).as("줄이 붙은 건은 **하나뿐**이어야 한다: %s", rows).isEqualTo(1);
        assertThat(qtySum).as("‼️합계가 60 이면 중복이다").isEqualTo(20);
    }

    @Test
    @DisplayName("★★발송완료를 대표에만 걸어도 **형제까지 함께** 넘어간다 — 안 그러면 영원히 발송준비중")
    void 발송완료가_형제까지() {
        handOverToLogistics(DAY, DAY, partner);
        JsonNode rows = data(get("/logistics/work-orders?fromDate=" + DAY + "&toDate=" + DAY
                + "&partnerId=" + partner + "&size=50")).path("content");
        long rep = -1;
        for (JsonNode r : rows) {
            if (r.path("lines").size() > 0) {
                rep = r.path("id").asLong();   // 줄이 붙은 것이 대표다
            }
        }
        assertThat(rep).as("대표 발송을 찾아야 한다").isPositive();

        assertThat(put("/logistics/work-orders/" + rep + "/shipping",
                Map.of("boxCount", 1, "sentDate", DAY)).path("success").asBoolean()).isTrue();

        // ‼️형제까지 발송일이 찍혀야 한다. 대표만 찍히면 주문조회가 「가장 덜 진행된 발송」을
        //   보여주므로 그 주문이 **계속 발송준비중**으로 남는다(운영 73건이 그 상태였다).
        for (var sh : shipmentRepository.findAll()) {
            if (sh.getDeletedAt() == null && DAY.equals(String.valueOf(sh.getTradeDate()))
                    && partner.equals(sh.getPartner().getId())) {
                assertThat(sh.getSentDate())
                        .as("발송 %d 에도 발송일이 찍혀야 한다", sh.getId()).isNotNull();
            }
        }

        JsonNode mine = null;
        for (JsonNode r : data(get("/orders?fromDate=" + DAY + "&toDate=" + DAY
                + "&partnerId=" + partner + "&size=50")).path("content")) {
            mine = r;
        }
        assertThat(mine.path("progressState").asText())
                .as("주문도 발송완료로 보여야 한다: %s", mine).isEqualTo("SHIPPED");

        // ★송장·박스는 **대표에만** 둔다 — 형제에 같은 송장을 복사하면 없는 운송장이 생긴다.
        long withBox = shipmentRepository.findAll().stream()
                .filter(x -> x.getDeletedAt() == null)
                .filter(x -> DAY.equals(String.valueOf(x.getTradeDate())))
                .filter(x -> partner.equals(x.getPartner().getId()))
                .filter(x -> x.getBoxCount() > 0)
                .count();
        assertThat(withBox).as("박스수는 대표 1건에만").isEqualTo(1);
    }

    @Test
    @DisplayName("★주문번호 칸에 내부 id 를 그대로 보여주지 않는다 — orderLabel 로 `#발송id`")
    void 표시용_주문번호() {
        JsonNode mine = null;
        for (JsonNode r : data(get("/orders?fromDate=" + DAY + "&toDate=" + DAY
                + "&partnerId=" + partner + "&size=50")).path("content")) {
            mine = r;
        }
        assertThat(mine).isNotNull();
        // 키는 네 칸(호출에 쓴다)
        assertThat(mine.path("orderNo").asText()).contains("|");
        // 표시는 `#발송id` — 거래처 내부 id 가 보이면 안 된다
        assertThat(mine.path("orderLabel").asText())
                .as("표시용: %s", mine).startsWith("#").doesNotContain("|");
    }

    @Test
    @DisplayName("★★대표가 출력됐으면 주문조회도 **발송준비중**으로 보인다 — 형제 때문에 접수완료로 보이면 안 된다")
    void 대표상태를_따른다() {
        String day = "2064-08-08";
        // 매출 1건 → 발송 1건, 그 뒤 같은 네 칸 키 발송 2건을 더 넣는다(이관 모양).
        assertThat(post("/sales/entries", Map.of("salesDate", day, "partnerId", partner,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 5, "unitPrice", 10000, "supplyRate", 70,
                        "schoolCode", SCHOOL, "schoolName", "중복검증학교"))))
                .path("success").asBoolean()).isTrue();
        splitAt(day);

        // 대표(줄이 붙은 발송)만 출력한다 — 형제는 접수완료로 남는다.
        handOverToLogistics(day, day, partner);
        long rep = -1;
        for (JsonNode r : data(get("/logistics/work-orders?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner + "&size=50")).path("content")) {
            if (r.path("lines").size() > 0) {
                rep = r.path("id").asLong();
            }
        }
        assertThat(rep).isPositive();
        assertThat(post("/logistics/work-orders/" + rep + "/print", null)
                .path("success").asBoolean()).isTrue();

        JsonNode mine = null;
        for (JsonNode r : data(get("/orders?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner + "&size=50")).path("content")) {
            mine = r;
        }
        // ‼️「가장 덜 진행된 것」을 쓰면 형제(접수완료) 때문에 RECEIVED 로 보인다 —
        //   그러면 물류가 이미 출력한 주문을 "고쳐도 된다"고 보여주는 셈이다(운영 #97599).
        assertThat(mine.path("progressState").asText())
                .as("대표 상태를 따라야 한다: %s", mine).isEqualTo("READY_TO_SHIP");
    }

    /** 그 날 발송의 등록번호를 비우고 같은 네 칸 키 발송을 2건 더 넣는다. */
    private void splitAt(String day) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(st -> {
                    em.createNativeQuery("UPDATE shipment SET entry_no = NULL"
                                    + " WHERE trade_date = ?1 AND partner_id = ?2")
                            .setParameter(1, java.sql.Date.valueOf(day))
                            .setParameter(2, partner).executeUpdate();
                    for (int seq = 2; seq <= 3; seq++) {
                        em.createNativeQuery("INSERT INTO shipment"
                                        + " (trade_class, trade_date, trade_seq, partner_id,"
                                        + "  school_code, school_name, created_at, created_by)"
                                        + " VALUES ('교재', ?1, ?2, ?3, ?4, '중복검증학교', NOW(), 'migration')")
                                .setParameter(1, java.sql.Date.valueOf(day))
                                .setParameter(2, seq).setParameter(3, partner)
                                .setParameter(4, SCHOOL).executeUpdate();
                    }
                    em.clear();
                });
    }

    /**
     * 이관과 같은 모양 — 그 날 발송의 등록번호를 비우고, 같은 네 칸 키의 발송을 2건 더 넣는다.
     *
     * <p>‼️운영의 10/31 자사몰이 정확히 이 모양이다(발송 16건 · 거래순번 1~16 ·
     * 등록번호 없음 · 매출 거래순번 전부 1).
     */
    private void splitLikeMigration() {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(st -> {
                    em.createNativeQuery("UPDATE shipment SET entry_no = NULL"
                                    + " WHERE trade_date = ?1 AND partner_id = ?2")
                            .setParameter(1, java.sql.Date.valueOf(DAY))
                            .setParameter(2, partner)
                            .executeUpdate();
                    // 같은 네 칸 키 · 거래순번만 다른 발송 2건
                    for (int seq = 2; seq <= 3; seq++) {
                        em.createNativeQuery("INSERT INTO shipment"
                                        + " (trade_class, trade_date, trade_seq, partner_id,"
                                        + "  school_code, school_name, created_at, created_by)"
                                        + " VALUES ('교재', ?1, ?2, ?3, ?4, '중복검증학교', NOW(), 'migration')")
                                .setParameter(1, java.sql.Date.valueOf(DAY))
                                .setParameter(2, seq)
                                .setParameter(3, partner)
                                .setParameter(4, SCHOOL)
                                .executeUpdate();
                    }
                    em.clear();
                });
        assertThat(shipmentRepository.findAll().stream()
                .filter(s -> s.getDeletedAt() == null)
                .filter(s -> DAY.equals(String.valueOf(s.getTradeDate())))
                .filter(s -> partner.equals(s.getPartner().getId()))
                .count()).as("발송 3건이 준비돼야 한다").isEqualTo(3);
    }
}
