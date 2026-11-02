package com.daesung.sales.receivable;

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
 * 외상매출장 <b>신고대상</b> 판정.
 *
 * <p>근거: 화면 담당 전달 2026-10-23 §3③ —
 * "신고대상 = 기간 내 매출 금액 합 ≠ 0 인 거래처, <b>거래처코드 'E110000' 미만</b>(레거시 기준).
 * 화면 목록은 이 기준으로 맞췄습니다."
 *
 * <p>‼️이 플래그는 단순 조회 필터가 아니라 <b>일괄 메일·PDF 대상</b>을 추리는 스위치다.
 * 서버가 화면보다 넓게 주면 <b>화면에 안 보이던 거래처에 메일이 나간다</b> — 되돌릴 수 없다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출장 신고대상(거래처코드 상한)")
class ReportableTargetIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-RT" + (System.nanoTime() % 100_000L);
    private static final int YEAR = 2088;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long inRange;     // 'E110000' 미만 — 대상
    private Long atBoundary;  // 정확히 'E110000' — 대상 아님(미만이 조건이다)
    private Long overRange;   // 'E110000' 초과 — 대상 아님

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "A00" + SFX, "name", "인쇄", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "RTW" + SFX, "name", "물류", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of(
                "code", "RTB" + SFX, "name", "신고대상검증도서", "contentType", "SELF",
                "price", 10000, "supplyRate", 70, "catCode", "R2088A01", "catName", "신고검증",
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-02",
                "supplierClientId", sup, "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 999))));

        // ‼️세 거래처 모두 **같은 기간에 매출이 있다.** 그래야 걸러지는 이유가
        //   "거래가 없어서"가 아니라 **코드 상한**임이 드러난다.
        inRange = partnerWithSale("A10Z" + SFX, wh, book);
        atBoundary = partnerWithSale("E110000", wh, book);
        overRange = partnerWithSale("E99Z" + SFX, wh, book);
    }

    private Long partnerWithSale(String code, Long wh, Long book) {
        Long id = createId("/masters/clients",
                Map.of("code", code, "name", "신고검증" + code, "type", "NORMAL"));
        Map<String, Object> item = new HashMap<>();
        item.put("productId", book);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", 3);
        assertThat(post("/sales/entries", Map.of(
                "salesDate", YEAR + "-05-10", "partnerId", id, "warehouseId", wh,
                "items", List.of(item))).path("success").asBoolean()).isTrue();
        return id;
    }

    private List<Long> ids(boolean onlyReportable) {
        JsonNode page = data(get("/closing/ar-ledger/all" + RANGE
                + "&size=200&onlyReportable=" + onlyReportable));
        List<Long> out = new java.util.ArrayList<>();
        for (JsonNode r : page.path("content")) {
            out.add(r.path("partnerId").asLong());
        }
        return out;
    }

    @Test
    @DisplayName("★신고대상은 거래처코드 'E110000' 미만만 — 매출이 있어도 그 위는 빠진다")
    void 코드상한() {
        List<Long> target = ids(true);

        assertThat(target).as("상한 아래는 대상").contains(inRange);
        // ‼️셋 다 같은 기간에 매출이 있다. 그런데도 빠지는 것이 코드 조건의 증거다.
        assertThat(target).as("'E110000' 초과는 제외").doesNotContain(overRange);
        assertThat(target).as("'E110000' 정확히 일치도 제외 — 조건은 '미만'이다")
                .doesNotContain(atBoundary);
    }

    @Test
    @DisplayName("‼️신고대상을 안 켜면 코드 상한도 안 건다 — 조회는 전부 보여야 한다")
    void 평소조회는_그대로() {
        List<Long> all = ids(false);
        assertThat(all).contains(inRange, atBoundary, overRange);
    }
}
