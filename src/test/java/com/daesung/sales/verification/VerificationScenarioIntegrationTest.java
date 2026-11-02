package com.daesung.sales.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.common.sequence.SequenceService;
import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 검수 시나리오 S-2 / S-5 / S-6 — 검수 당일 발주처 입회 하에 제시할 근거.
 *
 * <p>구현은 이미 있었으나 "통과 기준을 증명하는 실행 결과"가 없던 자리를 채운다.
 * 각 테스트는 검수표의 통과 기준 문구를 그대로 검증한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("검수 시나리오(S-2 원자성 · S-5 수불부 방정식 · S-6 채번 동시성)")
class VerificationScenarioIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private SequenceService sequenceService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long supplier;
    private Long partner;
    private Long mainWh;
    private Long consignWh;

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients", Map.of("code", "VS-SUP", "name", "검수인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "VS-CUST", "name", "검수특약점", "type", "CONSIGN"));
        mainWh = createId("/masters/warehouses", Map.of("code", "VS-M", "name", "검수물류창고", "type", "MAIN"));
        consignWh = createId("/masters/warehouses",
                Map.of("code", "VS-C", "name", "검수위탁창고", "type", "CONSIGN", "ownerClientId", partner));
    }

    // ── S-2. 위탁출고 자동이고 원자성 ─────────────────────────────────────

    @Test
    @DisplayName("S-2 정상: 위탁출고 저장 시 이고 이벤트(출발·도착)와 미결원장이 동시에 생성된다")
    void s2_정상시_동시생성() {
        Long p = product("VS-S2A");
        inbound(p, 500);

        JsonNode res = post("/consignment/out", Map.of(
                "processedDate", "2026-06-10", "partnerId", partner,
                "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", p, "qty", 120))));
        assertThat(res.path("success").asBoolean()).as("위탁출고: %s", res).isTrue();

        assertThat(balance(mainWh, "VS-S2A")).as("물류창고 차감").isEqualTo(380);
        assertThat(balance(consignWh, "VS-S2A")).as("위탁창고 증가").isEqualTo(120);
        assertThat(transferTxnCount(p)).as("이고 이벤트 = 출발다리 + 도착다리").isEqualTo(2);
        assertThat(pendingCount(p)).as("미결원장 1건").isEqualTo(1);
    }

    @Test
    @DisplayName("S-2 실패: 저장 도중 실패하면 전부 롤백되어 이고 레코드가 남지 않는다")
    void s2_실패시_전량롤백() {
        Long ok = product("VS-S2B");
        Long missing = 999_999_999L;   // 존재하지 않는 상품 → 2번째 품목에서 실패
        inbound(ok, 500);

        long txnBefore = transferTxnCount(ok);
        long pendingBefore = pendingCount(ok);

        // 1번째 품목은 정상, 2번째에서 터진다. 앞 품목의 이고·미결이 남으면 원자성 위반.
        JsonNode res = post("/consignment/out", Map.of(
                "processedDate", "2026-06-11", "partnerId", partner,
                "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(
                        Map.of("productId", ok, "qty", 100),
                        Map.of("productId", missing, "qty", 10))));

        assertThat(res.path("success").asBoolean()).as("실패해야 함: %s", res).isFalse();
        assertThat(res.path("error").path("code").asText()).isEqualTo("NOT_FOUND");

        assertThat(balance(mainWh, "VS-S2B")).as("재고 원복(차감 안 됨)").isEqualTo(500);
        assertThat(transferTxnCount(ok)).as("이고 이벤트가 남으면 안 됨").isEqualTo(txnBefore);
        assertThat(pendingCount(ok)).as("미결원장이 남으면 안 됨").isEqualTo(pendingBefore);
        assertThat(consignBalanceOrZero(ok)).as("위탁창고에 잔재 없음").isZero();
    }

    // ── S-5. 제품수불부 재고 방정식 ───────────────────────────────────────

    @Test
    @DisplayName("S-5 자체교재: 이월+입고+이고+폐기+매출+무상+교사용+반품+조정 = 현재재고 = 캐시잔량")
    void s5_수불부_방정식_자체교재() {
        assertLedgerEquation(product("VS-S5A"), "VS-S5A");
    }

    @Test
    @DisplayName("S-5 교재 외 콘텐츠(외부콘텐츠)도 동일 방정식이 성립한다")
    void s5_수불부_방정식_외부콘텐츠() {
        Long p = createId("/masters/products", Map.of(
                "code", "VS-S5B", "name", "외부콘텐츠", "contentType", "EXTERNAL", "price", 20000,
                "salesDivision", "교재"));
        assertLedgerEquation(p, "VS-S5B");
    }

    /**
     * 8종 이벤트를 모두 발생시킨 뒤 수불부 방정식을 검증한다.
     * 이월을 실제로 태우려고 입고는 4월(조회기간 이전), 나머지는 5월에 넣는다.
     */
    private void assertLedgerEquation(Long p, String code) {
        // 4월: 입고 1000 → 5월 조회 시 이월로 잡힘
        post("/stock/inbound", Map.of(
                "processedDate", "2026-04-10", "supplierClientId", supplier, "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", p, "unitCost", 3000, "qty", 1000))));

        // 5월: 이고 200 / 매출 100 / 증정 50 / 교사용 30 / 반품 20 / 폐기 40 / 실사 조정
        post("/stock/transfer", Map.of(
                "processedDate", "2026-05-02", "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", p, "qty", 200, "reason", "S5"))));
        sale(p, "NORMAL_SHIP", 100, "2026-05-03");
        sale(p, "GIFT", 50, "2026-05-04");
        sale(p, "TEACHER_USE", 30, "2026-05-05");
        post("/sales/return-inbound", Map.of(
                "returnDate", "2026-05-06", "partnerId", partner, "warehouseId", mainWh,
                "items", List.of(Map.of("productId", p, "unitPrice", 20000, "supplyRate", 75,
                        "qty", 20, "memo", "S5 반품"))));
        post("/disposals", Map.of(
                "processedDate", "2026-05-07", "warehouseId", mainWh,
                "items", List.of(Map.of("productId", p, "qty", 40, "reason", "파손"))));
        // 1000−200−100−50−30+20−40 = 600 → 실사에서 590으로 맞춤(ADJUST −10)
        post("/stock/stocktakes", Map.of(
                "warehouseId", mainWh, "stocktakeDate", "2026-05-08", "memo", "S5 실사",
                "items", List.of(Map.of("productId", p, "countedQty", 590))));

        JsonNode row = ledgerRow(mainWh, code, "2026-05-01", "2026-05-31");
        long opening = row.path("opening").asLong();
        long sum = opening + row.path("inbound").asLong() + row.path("transfer").asLong()
                + row.path("bom").asLong() + row.path("dispose").asLong() + row.path("sale").asLong()
                + row.path("free").asLong() + row.path("teacher").asLong()
                + row.path("salesReturn").asLong() + row.path("adjust").asLong();

        assertThat(opening).as("이월(4월 입고분)").isEqualTo(1000);
        assertThat(row.path("salesReturn").asLong()).as("반품은 양수(재고 복구)").isEqualTo(20);
        assertThat(sum).as("방정식 좌변 = 현재재고: %s", row).isEqualTo(row.path("closing").asLong());
        assertThat(row.path("closing").asLong()).as("현재재고 = 캐시 잔량(대사)")
                .isEqualTo(row.path("cachedBalance").asLong());
        assertThat(row.path("reconciled").asBoolean()).as("이벤트합계=캐시 일치").isTrue();
        assertThat(row.path("closing").asLong()).as("검산값").isEqualTo(590);
    }

    // ── S-6. 채번 동시성 ─────────────────────────────────────────────────

    @Test
    @DisplayName("S-6 동시 100건 채번에 번호 중복·누락 0건")
    void s6_채번_동시100건() throws Exception {
        final int n = 100;
        long before = currentSeq(SequenceService.SEQ_INVOICE);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch gate = new CountDownLatch(1);
        List<Callable<Long>> tasks = IntStream.range(0, n)
                .<Callable<Long>>mapToObj(i -> () -> {
                    gate.await();   // 동시 출발
                    return sequenceService.next(SequenceService.SEQ_INVOICE);
                }).toList();

        List<Future<Long>> futures = tasks.stream().map(pool::submit).toList();
        gate.countDown();
        List<Long> issued = new java.util.ArrayList<>();
        for (Future<Long> f : futures) {
            issued.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(issued).hasSize(n);
        assertThat(issued.stream().collect(Collectors.toSet()))
                .as("중복 0건 — 같은 번호가 두 번 나오면 전표번호가 충돌한다").hasSize(n);

        long min = issued.stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = issued.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertThat(max - min + 1).as("누락 0건 — 발급 범위가 정확히 %d개여야 함", n).isEqualTo(n);
        assertThat(min).as("이전 값 다음부터 연속 발급").isEqualTo(before + 1);
        assertThat(currentSeq(SequenceService.SEQ_INVOICE)).isEqualTo(before + n);
    }

    // ── helpers ──

    private Long product(String code) {
        return createId("/masters/products",
                Map.of("code", code, "name", code, "contentType", "SELF", "price", 20000,
                        // ★위탁출고는 공급률이 없으면 등록이 막힌다(나갈 때 조건 확정).
                        "supplyRate", 75,
                "salesDivision", "교재"));
    }

    private void inbound(Long p, int qty) {
        post("/stock/inbound", Map.of(
                "processedDate", "2026-06-01", "supplierClientId", supplier, "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", p, "unitCost", 3000, "qty", qty))));
    }

    private void sale(Long p, String shipmentType, int qty, String date) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", mainWh,
                "items", List.of(Map.of("productId", p, "shipmentType", shipmentType,
                        "unitPrice", 20000, "supplyRate", 75, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("%s %d건: %s", shipmentType, qty, r).isTrue();
    }

    private JsonNode ledgerRow(Long wh, String code, String from, String to) {
        JsonNode rows = data(get("/stock/ledger?warehouseId=" + wh + "&fromDate=" + from + "&toDate=" + to)).path("content");
        for (JsonNode r : rows) {
            if (code.equals(r.path("productCode").asText())) {
                return r;
            }
        }
        throw new AssertionError("수불부 행 없음: " + code);
    }

    private int balance(Long wh, String code) {
        JsonNode rows = data(get("/stock/ledger?warehouseId=" + wh)).path("content");
        for (JsonNode r : rows) {
            if (code.equals(r.path("productCode").asText())) {
                return r.path("cachedBalance").asInt();
            }
        }
        throw new AssertionError("재고행 없음: " + code);
    }

    private long consignBalanceOrZero(Long productId) {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(qty),0) FROM inventory WHERE product_id=? AND warehouse_id=?",
                Long.class, productId, consignWh);
        return (v == null) ? 0L : v;
    }

    private long transferTxnCount(Long productId) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_txn WHERE product_id=? AND txn_type='TRANSFER'",
                Long.class, productId);
        return (v == null) ? 0L : v;
    }

    private long pendingCount(Long productId) {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consignment_out WHERE product_id=?", Long.class, productId);
        return (v == null) ? 0L : v;
    }

    private long currentSeq(String name) {
        Long v = jdbc.queryForObject(
                "SELECT seq_val FROM seq_registry WHERE seq_name=?", Long.class, name);
        return (v == null) ? 0L : v;
    }
}
