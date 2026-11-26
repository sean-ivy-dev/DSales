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
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 발송일 빈 발송 — <b>이관분만</b> 세고 <b>주문 수</b>도 낸다(발주처 2026-11-26).
 *
 * <p>“이관분만 연도별 <b>주문 수 · 발송 수</b>. 신규 등록 주문 · [TEST] · ZZT1111 제외.”
 *
 * <h3>★두 가지를 못박는다</h3>
 * <ul>
 *   <li><b>이관분 판별 = 등록번호가 없다.</b> 11/23 에 드린 641건 목록의 기준과 같다
 *       (그 641건은 전부 "접수경로 수기 · 등록번호 없음"). 신규 등록은 매출등록번호가 붙는다.</li>
 *   <li><b>주문 수 = 발송 묶음 수.</b> 등록번호가 비면 발송은
 *       (일자 + 거래처 + 학교 + 분류)로 묶이고, 그 묶음 하나가 주문 하나다.
 *       같은 묶음의 발송이 여럿이면 <b>주문 1 · 발송 N</b> 이어야 한다.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("발송일 빈 발송 — 이관분 주문 수·발송 수(2026-11-26)")
class MigratedMissingSentDateIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "MG" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2069;
    private static final String DAY = YEAR + "-04-04";

    @Autowired
    private com.daesung.sales.logistics.repository.ShipmentRepository shipmentRepository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private Long partner;
    private Long testPartner;
    private Long warehouse;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "MGS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "MGW" + SFX, "name", "창고", "type", "MAIN"));
        partner = createId("/masters/clients",
                Map.of("code", "MGP" + SFX, "name", "이관검증거래처", "type", "NORMAL"));
        // ‼️제외 대상 — 이름이 [TEST] 로 시작하면 빠져야 한다.
        testPartner = createId("/masters/clients",
                Map.of("code", "MGT" + SFX, "name", "[TEST] 제외되어야 함", "type", "NORMAL"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "MGB" + SFX);
        b.put("name", "이관검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S" + YEAR + "A01");
        b.put("catName", "이관검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 500, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();

        // ‼️발송을 **여기서** 만든다 — 테스트 메서드 안에서 만들면 먼저 도는 다른 메서드가 빈 집계를 본다.
        // ① 이관분 흉내 — 같은 날·같은 거래처로 두 건, 등록번호를 비운다(= 한 묶음).
        sell(partner, 3);
        sell(partner, 5);
        clearEntryNo(partner);
        // ② 신규 등록분 — 등록번호가 그대로 있다. 빠져야 한다.
        sell(partner, 7);
        // ③ [TEST] 거래처 — 등록번호를 비워도 빠져야 한다.
        sell(testPartner, 9);
        clearEntryNo(testPartner);
    }

    @Test
    @DisplayName("★★등록번호 **없는** 발송만 세고, 묶음 하나를 **주문 1 · 발송 N** 으로 센다")
    void 이관분만_주문수와_발송수() {
        JsonNode mineRes = get("/logistics/work-orders/missing-sent-date?migratedOnly=true");
        JsonNode mine = rowOf(mineRes);
        assertThat(mine).as("이관분 집계에 그 해가 없다: %s", mineRes).isNotNull();
        assertThat(mine.path("orderCount").asLong())
                .as("‼️묶음 하나는 주문 1 — 발송 수를 그대로 주문 수로 쓰면 2가 된다")
                .isEqualTo(1L);
        assertThat(mine.path("count").asLong())
                .as("‼️발송은 2건이다(③ 신규분·[TEST] 는 빠진다)")
                .isEqualTo(2L);

        // 전체(이관분 아님) 집계는 신규 등록분·[TEST] 까지 센다 — 주문 수는 내지 않는다.
        JsonNode all = rowOf(get("/logistics/work-orders/missing-sent-date"));
        assertThat(all.path("count").asLong())
                .as("전체는 4건(이관 2 + 신규 1 + [TEST] 1)").isEqualTo(4L);
        // 비어 있으면 응답에서 빠진다(없는 값을 0 으로 내보내면 "주문이 0건"으로 읽힌다).
        assertThat(all.path("orderCount").isNumber())
                .as("‼️이관분이 아닐 땐 주문 수를 낼 수 없다 — 0 으로 내보내면 거짓이 된다").isFalse();
    }

    /**
     * ‼️<b>기간으로 좁힐 수 있어야 한다</b> — 11/23 에 드린 641건 목록이 <b>1~10월</b> 이라,
     * 연도 전체로 세면 11~12월이 더해져 숫자가 어긋난다(운영 실측 641 ↔ 654).
     */
    @Test
    @DisplayName("★기간으로 좁힌다 — 목록과 숫자를 맞추려면 기간부터 같아야 한다")
    void 기간으로_좁힌다() {
        JsonNode inRange = rowOf(get("/logistics/work-orders/missing-sent-date"
                + "?migratedOnly=true&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
        assertThat(inRange).as("기간 안이면 나와야 한다").isNotNull();
        assertThat(inRange.path("count").asLong()).isEqualTo(2L);

        JsonNode outOfRange = rowOf(get("/logistics/work-orders/missing-sent-date"
                + "?migratedOnly=true&fromDate=" + YEAR + "-05-01&toDate=" + YEAR + "-12-31"));
        assertThat(outOfRange)
                .as("‼️4월 건인데 5월부터로 좁혔는데도 나왔다 — 기간이 안 먹는 것이다").isNull();
    }

    /**
     * ‼️<b>목록과 집계가 갈리면 둘 다 못 믿는다</b> — 같은 조건·같은 묶음을 쓰는지 못박는다.
     *
     * <p>대상 목록은 "돌리기 전에 무엇이 바뀌나"를 보여주고, 돌린 뒤 같은 경로로
     * 다시 받아 사라졌는지 확인하는 용도다(발주처 2026-11-26).
     */
    @Test
    @DisplayName("★★대상 목록 — **집계와 같은 줄 수·같은 발송 수**(갈리면 둘 다 못 믿는다)")
    void 대상목록이_집계와_맞는다() {
        JsonNode list = data(get("/logistics/work-orders/missing-sent-date/detail"
                + "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
        assertThat(list.size()).as("주문 1건(묶음 하나): %s", list).isEqualTo(1);

        JsonNode row = list.get(0);
        assertThat(row.path("shipmentCount").asLong())
                .as("‼️한 주문에 발송 2건 — 1 로 나오면 묶음이 아니라 발송을 센 것이다").isEqualTo(2L);
        assertThat(row.path("partnerCode").asText()).isEqualTo("MGP" + SFX);
        assertThat(row.path("tradeDate").asText()).isEqualTo(DAY);
        assertThat(row.path("progressStateName").asText()).isEqualTo("접수완료");

        // 집계와 맞아야 한다 — 줄 수 = 주문 수, 발송 합 = 발송 수.
        JsonNode agg = rowOf(get("/logistics/work-orders/missing-sent-date?migratedOnly=true"
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
        long listShipments = 0;
        for (JsonNode n : list) {
            listShipments += n.path("shipmentCount").asLong();
        }
        assertThat((long) list.size()).as("목록 줄 수 = 집계 주문 수")
                .isEqualTo(agg.path("orderCount").asLong());
        assertThat(listShipments).as("목록 발송 합 = 집계 발송 수")
                .isEqualTo(agg.path("count").asLong());
    }

    /** 그 해 줄 하나를 꺼낸다(상태는 하나만 생긴다). */
    private JsonNode rowOf(JsonNode res) {
        for (JsonNode n : data(res)) {
            if (n.path("year").asInt() == YEAR) {
                return n;
            }
        }
        return null;
    }

    private void sell(Long p, int qty) {
        assertThat(post("/sales/entries", Map.of(
                "salesDate", DAY, "partnerId", p, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
    }

    /**
     * 이관분과 같은 모양 — 그 거래처 발송의 등록번호를 비운다.
     *
     * <p>‼️같은 클래스 안의 {@code @Transactional} 은 프록시를 안 거쳐 <b>적용되지 않는다</b>.
     * 템플릿으로 직접 연다({@code EmptyWorkOrderCleanupIntegrationTest} 와 같은 규율).
     */
    private void clearEntryNo(Long partnerId) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(status -> shipmentRepository.findAll().stream()
                        .filter(s -> s.getDeletedAt() == null)
                        .filter(s -> DAY.equals(String.valueOf(s.getTradeDate())))
                        .filter(s -> partnerId.equals(s.getPartner().getId()))
                        .forEach(s -> shipmentRepository.clearEntryNoForTest(s.getId())));
    }
}
