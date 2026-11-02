package com.daesung.sales.inventory;

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
 * 마감된 달에도 재고 <b>등록은 막지 않고 경고만</b> 싣는다.
 *
 * <p>근거: 발주처 확정(2026-10-23) — "마감된 달 처리 : 입고·대체·폐기 등록 <b>차단 안 함,
 * 안내 메시지만</b>". 재고 음수를 막지 않기로 한 것(2026-08-31)과 같은 결이다 —
 * 막으면 처리가 안 되는 게 아니라 <b>시스템 밖에서 처리된다</b>.
 *
 * <p>‼️2026-10-22에 "등록은 되는데 취소는 안 되는 비대칭이 나쁘다"는 판단으로 막았다가 되돌렸다.
 * 그건 <b>회신 대기 중이던 항목</b>이었고, 우리가 정할 일이 아니었다.
 *
 * <p>★<b>수정·취소·삭제는 그대로 막는다.</b> 되돌림은 마감 숫자를 흔든다 —
 * 그래서 이 테스트는 "등록은 되고 되돌림은 막힌다"를 <b>같이</b> 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("마감월 재고 등록 — 차단 아님, 경고만")
class StockPeriodLockIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PL" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2096;
    private static final int MONTH = 4;
    private static final String DATE = YEAR + "-04-10";

    private Long warehouse;
    private Long warehouse2;
    private Long book;
    private Long supplier;

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients",
                Map.of("code", "PLS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "PLW" + SFX, "name", "마감검증창고", "type", "MAIN"));
        warehouse2 = createId("/masters/warehouses",
                Map.of("code", "PLX" + SFX, "name", "마감검증창고2", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "PLB" + SFX);
        b.put("name", "마감검증 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "P2096A01");
        b.put("catName", "마감검증");
        b.put("taxFree", false);
        b.put("useYn", true);
        b.put("set", false);
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        assertThat(inbound(YEAR + "-03-02").path("success").asBoolean()).isTrue();
    }

    private JsonNode inbound(String date) {
        return post("/stock/inbound", Map.of(
                "processedDate", date, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 500))));
    }

    /** 성공했고, PERIOD_LOCKED 경고가 실려 있어야 한다. */
    private static void assertWarnedNotBlocked(JsonNode r, String what) {
        assertThat(r.path("success").asBoolean()).as("%s 는 막히면 안 된다: %s", what, r).isTrue();
        boolean warned = false;
        for (JsonNode w : r.path("data").path("warnings")) {
            if ("PERIOD_LOCKED".equals(w.path("code").asText())) {
                warned = true;
            }
        }
        assertThat(warned).as("%s 에 PERIOD_LOCKED 경고가 실려야 한다: %s", what, r).isTrue();
    }

    @Test
    @DisplayName("★입고·이고·폐기·실사 등록이 마감월에도 되고, 경고가 실린다")
    void 마감월에도_등록된다() {
        post("/closing/periods/lock", Map.of("year", YEAR, "month", MONTH, "memo", "재고등록 경고 검증"));
        try {
            assertWarnedNotBlocked(inbound(DATE), "입고");

            assertWarnedNotBlocked(post("/stock/transfer", Map.of(
                    "processedDate", DATE, "fromWarehouseId", warehouse, "toWarehouseId", warehouse2,
                    "items", List.of(Map.of("productId", book, "qty", 10)))), "이고");

            assertWarnedNotBlocked(post("/disposals", Map.of(
                    "processedDate", DATE, "warehouseId", warehouse,
                    "items", List.of(Map.of("productId", book, "qty", 5, "reason", "파손")))), "폐기");

            assertWarnedNotBlocked(post("/stock/stocktakes", Map.of(
                    "stocktakeDate", DATE, "warehouseId", warehouse,
                    "items", List.of(Map.of("productId", book, "countedQty", 480)))), "실사");
        } finally {
            post("/closing/periods/unlock", Map.of("year", YEAR, "month", MONTH, "memo", "테스트 정리"));
        }
    }

    @Test
    @DisplayName("‼️되돌림(전표 삭제)은 그대로 막힌다 — 마감 숫자를 흔들기 때문")
    void 되돌림은_막힌다() {
        JsonNode in = inbound(YEAR + "-06-05");
        assertThat(in.path("success").asBoolean()).isTrue();
        String no = data(in).path("inboundNo").asText();

        post("/closing/periods/lock", Map.of("year", YEAR, "month", 6, "memo", "되돌림 차단 검증"));
        try {
            JsonNode del = del("/stock/vouchers/" + no, Map.of("reason", "마감 후 시도"));
            assertThat(del.path("success").asBoolean()).as("삭제는 막혀야 한다: %s", del).isFalse();
            assertThat(del.path("error").path("code").asText()).isEqualTo("PERIOD_LOCKED");
        } finally {
            post("/closing/periods/unlock", Map.of("year", YEAR, "month", 6, "memo", "테스트 정리"));
        }
    }

    @Test
    @DisplayName("마감 안 된 달은 경고도 없다 — 아무 때나 뜨면 경고가 무시된다")
    void 마감_아니면_경고없다() {
        JsonNode r = inbound(YEAR + "-05-10");
        assertThat(r.path("success").asBoolean()).isTrue();
        for (JsonNode w : r.path("data").path("warnings")) {
            assertThat(w.path("code").asText()).isNotEqualTo("PERIOD_LOCKED");
        }
    }
}
