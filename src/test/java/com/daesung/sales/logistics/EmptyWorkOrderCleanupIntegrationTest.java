package com.daesung.sales.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
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
 * 매출을 지우면 <b>빈 작업요청서도 함께 걷힌다</b> — 등록번호가 없는 발송까지(2026-11-13).
 *
 * <p>‼️그전엔 {@code findKey} 로 <b>등록번호가 정확히 일치</b>하는 발송만 찾았다.
 * 그런데 <b>살아 있는 발송 34,517건 중 34,515건이 {@code entry_no} 가 비어 있고</b>
 * (이관분이 그 모양이다) 매출에는 등록번호가 있어 짝이 안 맞았다.
 * → 매출을 지워도 작업요청서가 「발송준비중」으로 남았다(A10A103 #97598 · A30D201 10/27 2건).
 *
 * <p>★<b>반대 사고가 더 나쁘다.</b> 등록번호 없는 발송은 여러 주문이 공유할 수 있어,
 * 그 주문 매출만 세고 지우면 <b>다른 주문의 물건이 남았는데 지시가 사라진다.</b>
 * 그래서 등록번호 없는 발송은 <b>등록번호를 가리지 않고</b> 남은 매출을 센다.
 * 이 테스트가 두 방향을 모두 못박는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("빈 작업요청서 정리 — 등록번호 없는 발송까지(2026-11-13)")
class EmptyWorkOrderCleanupIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "EW" + (System.nanoTime() % 1_000_000L);
    // ‼️**다른 테스트가 쓰지 않는 연도**를 쓴다. 2092 로 뒀더니 다른 테스트가 그 해 8월을
    //   마감해 등록이 PERIOD_LOCKED 로 막혔다(단독 실행은 통과, 전체 실행에서만 깨졌다).
    private static final String DATE = "2061-08-08";

    @Autowired
    private com.daesung.sales.logistics.repository.ShipmentRepository shipmentRepository;

    @Autowired
    private EntityManager em;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private Long partner;
    private Long warehouse;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "EWS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "EWW" + SFX, "name", "창고", "type", "MAIN"));
        partner = createId("/masters/clients",
                Map.of("code", "EWP" + SFX, "name", "빈지시검증거래처", "type", "NORMAL"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "EWB" + SFX);
        b.put("name", "빈지시 검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2061A01");
        b.put("catName", "빈지시검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2061-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 200, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★등록번호가 **비어 있는** 발송도 걷힌다 — 예전엔 그대로 남았다")
    void 등록번호_없는_발송도_걷힌다() {
        Long saleId = sell(DATE, 3);
        Long shipmentId = onlyShipment(DATE);

        // 이관분과 같은 모양을 만든다 — 발송의 등록번호를 비운다.
        clearEntryNo(shipmentId);

        assertThat(del("/sales/" + saleId, Map.of("reason", "빈 지시 정리 검증"))
                .path("success").asBoolean()).isTrue();

        assertThat(aliveShipments(DATE))
                .as("‼️매출이 다 없어졌는데 작업요청서가 남으면 물류가 쌀 것 없는 지시를 본다")
                .isZero();
    }

    @Test
    @DisplayName("‼️남은 매출이 있으면 **지우지 않는다** — 등록번호 없는 발송은 여러 주문이 공유한다")
    void 남은_매출이_있으면_안_지운다() {
        String day = "2061-09-09";
        Long first = sell(day, 2);
        Long shipmentId = onlyShipment(day);
        clearEntryNo(shipmentId);

        // 같은 날·같은 거래처로 **두 번째 주문** — 등록번호가 다르다.
        Long second = sell(day, 5);

        // 첫 주문만 지운다. 두 번째 물건이 아직 남았으니 지시는 살아 있어야 한다.
        assertThat(del("/sales/" + first, Map.of("reason", "일부만 삭제"))
                .path("success").asBoolean()).isTrue();

        assertThat(aliveShipments(day))
                .as("‼️여기서 0 이면 남의 주문 물건이 있는데 지시가 사라진 것이다")
                .isPositive();

        // 두 번째까지 지우면 그때 걷힌다.
        assertThat(del("/sales/" + second, Map.of("reason", "나머지 삭제"))
                .path("success").asBoolean()).isTrue();
        assertThat(aliveShipments(day)).as("이제는 쌀 것이 없다").isZero();
    }

    /** 매출 한 건 등록하고 그 id 를 돌려준다. */
    private Long sell(String date, int qty) {
        JsonNode res = post("/sales/entries", Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(res.path("success").asBoolean()).as("등록: %s", res).isTrue();
        for (JsonNode n : get("/sales?fromDate=" + date + "&toDate=" + date
                + "&partnerId=" + partner + "&size=50").path("data").path("content")) {
            if (n.path("qty").asInt() == qty) {
                return n.path("id").asLong();
            }
        }
        throw new AssertionError("등록한 매출을 못 찾았다");
    }

    /** 그 날 살아 있는 발송 건이 하나뿐임을 확인하고 id 를 돌려준다. */
    private Long onlyShipment(String date) {
        var list = shipmentRepository.findAll().stream()
                .filter(s -> s.getDeletedAt() == null)
                .filter(s -> date.equals(String.valueOf(s.getTradeDate())))
                .filter(s -> partner.equals(s.getPartner().getId()))
                .toList();
        assertThat(list).as("발송 건이 하나 생겨야 한다").hasSize(1);
        return list.get(0).getId();
    }

    /**
     * 이관분과 같은 모양 — 발송의 등록번호를 비운다.
     *
     * <p>★엔티티에 테스트 전용 메서드를 두지 않으려고 쿼리로 직접 비운다.
     * 운영 발송 34,515건이 이 상태다(이관이 등록번호 없이 넣었다).
     */
    private void clearEntryNo(Long shipmentId) {
        // ‼️같은 클래스 안의 @Transactional 은 프록시를 안 거쳐 **적용되지 않는다**
        //   (TransactionRequiredException 으로 실제로 깨졌다). 템플릿으로 직접 연다.
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(status -> {
                    em.createQuery("update Shipment s set s.entryNo = null where s.id = :id")
                            .setParameter("id", shipmentId)
                            .executeUpdate();
                    em.clear();
                });
    }

    private long aliveShipments(String date) {
        return shipmentRepository.findAll().stream()
                .filter(s -> s.getDeletedAt() == null)
                .filter(s -> date.equals(String.valueOf(s.getTradeDate())))
                .filter(s -> partner.equals(s.getPartner().getId()))
                .count();
    }
}
