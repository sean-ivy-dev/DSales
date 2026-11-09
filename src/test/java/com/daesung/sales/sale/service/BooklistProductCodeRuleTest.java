package com.daesung.sales.sale.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 교재 일괄등록의 <b>상품코드 복원 규칙</b>.
 *
 * <p>‼️2026-11-09 운영덤프 실측 — DSRE (분류코드, 도서코드)를 <b>그대로 이어 붙여</b> 찾고 있어
 * <b>매칭이 0건</b>이었다(26조합 전부 UNMAPPED). 이관이 출처 접두어를 붙였기 때문이다
 * ({@code 2_convert.py}: {@code code = "{DL|DT}-{catCode}-{bookCode}"}).
 * 접두어를 복원하면 <b>22/26</b> 이 붙는다(나머지 4는 DSRE 쪽 도서명도 비어 있는 유령 코드).
 *
 * <p>★<b>순서가 규칙이다.</b> 같은 (분류, 도서코드)가 양쪽 법인에 다 있는 경우가 8건 실재하고,
 * 특약점 도서신청에는 법인 구분이 없다. 연구소가 주 법인이라 {@code DL-} 를 먼저 본다 —
 * 순서가 뒤집히면 같은 신청이 다른 법인 상품으로 매출이 선다.
 *
 * <p>★법인 분리(F-01) 뒤에는 <b>고른 법인의 접두어만</b> 봐야 한다. 그때 이 테스트도 함께 바뀐다.
 */
@DisplayName("교재 일괄등록 — DSRE 분류·도서코드에 출처 접두어를 복원한다")
class BooklistProductCodeRuleTest {

    @Test
    @DisplayName("접두어 2종을 **DL- 먼저** 만든다 — 레거시 실값(H2017C/03)로 고정")
    void 후보코드_순서() {
        assertThat(BulkSalesImportService.candidateCodes("H2017C", "03"))
                .containsExactly("DL-H2017C-03", "DT-H2017C-03");
    }

    @Test
    @DisplayName("‼️접두어 없는 옛 방식('H2017C03')을 만들면 안 된다 — 그게 매칭 0건의 원인이었다")
    void 옛방식은_없다() {
        assertThat(BulkSalesImportService.candidateCodes("H2017C", "03"))
                .doesNotContain("H2017C03", "H2017C-03");
    }

    @Test
    @DisplayName("코드가 비면 후보도 없다 — 빈 문자열로 조회하지 않는다")
    void 코드없으면_빈목록() {
        assertThat(BulkSalesImportService.candidateCodes(null, "03")).isEmpty();
        assertThat(BulkSalesImportService.candidateCodes("H2017C", null)).isEmpty();
    }
}
