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
 * "신고대상 = 기간 내 매출 금액 합 ≠ 0 인 거래처, <b>거래처코드 'E110000' 미만</b>(레거시 기준)."
 *
 * <p>‼️<b>이 플래그는 조회 필터가 아니라 일괄 메일·PDF 대상을 추리는 스위치다.</b>
 * 레거시 「신고대상」 체크박스는 <b>거래처 콤보 목록과 일괄전송 버튼만</b> 바꾸고
 * ({@code CommonDB.GetCustList_Sales}), 장부 본문의 거래처 조건은 「전체 거래처」면
 * <b>빈 문자열</b>이다({@code 외상매출장조회.vb:181}). 상한 {@code E110000} 은 레거시
 * 전체 소스에서 <b>그 목록 함수 한 곳</b>에만 있다.
 *
 * <p>★<b>2026-11-24 정정</b>: 이 테스트는 원래 「조회에서도 상한을 건다」를 못박고 있었다.
 * 그게 결함이었다 — 특강 거래처 14곳·33.8억이 통째로 안 보였다(발주처 2026-11-23 9번).
 * 이제 <b>조회는 걸지 않고</b>, 메일 대상 선정에만 쓴다. 아래 두 테스트가 양쪽을 못박는다.
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
    @DisplayName("★★**조회는 코드 상한을 걸지 않는다** — 신고대상을 켜도 그 위가 안 빠진다")
    void 조회는_상한을_걸지_않는다() {
        List<Long> on = ids(true);
        List<Long> off = ids(false);

        assertThat(on)
                .as("‼️다르면 「신고대상」이 또 조회 필터로 돌아간 것이다")
                .isEqualTo(off);
        // ‼️셋 다 같은 기간에 매출이 있다. 레거시 장부 본문엔 거래처 조건이 없으므로 셋 다 나온다.
        assertThat(on).as("상한 아래").contains(inRange);
        assertThat(on).as("'E110000' 초과도 조회에는 나온다 — 특강 거래처가 이 모양이다")
                .contains(overRange);
        assertThat(on).as("'E110000' 정확히 일치도 조회에는 나온다").contains(atBoundary);
    }

    @Test
    @DisplayName("‼️**메일 대상**은 코드 상한을 건다 — 화면에 없던 곳에 명세서가 나가면 되돌릴 수 없다")
    void 메일대상은_상한을_건다() {
        // 거래처를 안 고르면 서버가 대상을 추린다. 상한 초과만 골라 보내면 "대상 없음"이어야 한다.
        JsonNode res = post("/closing/ar-ledger/email"
                + RANGE + "&onlyReportable=true&partnerIds=" + overRange, Map.of());
        assertThat(res.path("success").asBoolean())
                .as("‼️성공하면 상한 초과 거래처에 메일이 나간 것이다: %s", res)
                .isFalse();
    }
}
