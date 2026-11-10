package com.daesung.sales.sale.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.product.entity.ContentType;
import com.daesung.sales.product.entity.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 매출일괄등록(더프) 줄별 <b>'세금' 체크</b>(프론트 요청 2026-11-10 §1-5).
 *
 * <p>레거시 {@code 매출가져오기.vb:783}: {@code tax = IIf(세금 = True, CLng(총금액 / 10), 0)} —
 * 체크한 줄만 <b>총금액 ÷ 10</b>(버림)이고, 합계는 총금액 + 세액이다.
 *
 * <p>★세액을 자동산출하지 않는다는 발주처 확정(2026-08-06)과 어긋나지 않는다 —
 * 담당자가 <b>체크한 줄에만</b> 붙는 수동 입력이고, 미체크는 0 이다.
 *
 * <p>‼️<b>면세 상품에 체크하면 거부</b>한다. 조용히 0 으로 무시하면 담당자는 세액이 붙었다고
 * 믿은 채 넘어간다(매출등록 쪽과 같은 규율).
 *
 * <p>⚠️등록 경로 전체는 DSRE 연동이 켜져야 돌아가므로(서비스가
 * {@code @ConditionalOnProperty(daesung.dsre.enabled)}) 통합테스트로 덮을 수 없다.
 * 그래서 <b>판정부만</b> 여기서 고정한다.
 */
@DisplayName("더프 일괄등록 — 줄별 세금 체크(§1-5)")
class DuffTaxCheckTest {

    private static Product product(String code, boolean taxFree) {
        return Product.create(code, taxFree ? "면세도서" : "과세도서", ContentType.SELF, false,
                10_000, taxFree, null, "A2026A", "검증", true, "교재", true, true, true);
    }

    private static Product taxable() {
        return product("TX-1", false);
    }

    private static Product taxFree() {
        return product("TF-1", true);
    }

    @Test
    @DisplayName("미체크는 0 — 세액은 자동산출하지 않는다")
    void 미체크는_0() {
        assertThat(DuffSalesImportService.taxOf(null, 123_456L, taxable(), "w")).isZero();
        assertThat(DuffSalesImportService.taxOf(false, 123_456L, taxable(), "w")).isZero();
    }

    @Test
    @DisplayName("체크하면 총금액 ÷ 10 — 버림(레거시 CLng)")
    void 체크하면_10분의1_버림() {
        assertThat(DuffSalesImportService.taxOf(true, 1_000L, taxable(), "w")).isEqualTo(100L);
        // 끝수는 버린다 — 999/10 = 99.9 → 99
        assertThat(DuffSalesImportService.taxOf(true, 999L, taxable(), "w")).isEqualTo(99L);
        assertThat(DuffSalesImportService.taxOf(true, 0L, taxable(), "w")).isZero();
    }

    @Test
    @DisplayName("‼️면세 상품에 체크하면 거부 — 조용히 0으로 넘기지 않는다")
    void 면세는_거부() {
        assertThatThrownBy(() -> DuffSalesImportService.taxOf(true, 1_000L, taxFree(), "수기 1번째 줄:"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("면세")
                .hasMessageContaining("수기 1번째 줄:")
                .hasMessageContaining("TF-1");
        // 미체크면 면세여도 문제없다(0원이 맞다).
        assertThat(DuffSalesImportService.taxOf(false, 1_000L, taxFree(), "w")).isZero();
    }
}
