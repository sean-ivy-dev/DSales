package com.daesung.sales.dealer.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.product.entity.ContentType;
import com.daesung.sales.product.entity.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재고현황 매입·반품 합산 공식(11/05 회신 §4) 회귀 고정 —
 * 매입 = 이관시작값 + 자동집계, 반품 = 이관시작값 + 자동집계, 출고 = 매입 − 재고 − 반품.
 */
@DisplayName("DealerStockRow.of — 매입·반품 합산 공식")
class DealerStockRowTest {

    private static Product product() {
        return Product.create("STR-1", "테스트도서", ContentType.SELF, false,
                10000, false, null, "A1", "분류", true,
                "교재", true, true, true);
    }

    @Test
    @DisplayName("cutover 전(자동집계 0) — 저장값 그대로")
    void cutover전_저장값그대로() {
        DealerStockRow row = DealerStockRow.of(product(), 100, 10, 20, 0, 0, null);
        assertThat(row.purchaseQty()).isEqualTo(100);
        assertThat(row.returnQty()).isEqualTo(10);
        assertThat(row.shipQty()).as("100-20-10").isEqualTo(70);
    }

    @Test
    @DisplayName("★cutover 후 — 이관 시작값 + 기준일 이후 자동집계가 더해진다")
    void cutover후_시작값에_자동집계가_더해진다() {
        // 이관 시작값: 매입 100 / 반품 10. 기준일 이후 자동집계: 매입 30 / 반품 5. 재고는 수기 20.
        DealerStockRow row = DealerStockRow.of(product(), 100, 10, 20, 30, 5, null);
        assertThat(row.purchaseQty()).as("100+30").isEqualTo(130);
        assertThat(row.returnQty()).as("10+5").isEqualTo(15);
        assertThat(row.stockQty()).isEqualTo(20);
        assertThat(row.shipQty()).as("130-20-15").isEqualTo(95);
    }

    @Test
    @DisplayName("이관 시작값이 없어도(0) 자동집계만으로 값이 생긴다")
    void 시작값없이_자동집계만() {
        DealerStockRow row = DealerStockRow.of(product(), 0, 0, 0, 12, 2, null);
        assertThat(row.purchaseQty()).isEqualTo(12);
        assertThat(row.returnQty()).isEqualTo(2);
        assertThat(row.shipQty()).as("12-0-2").isEqualTo(10);
    }

    /**
     * 출고 음수 — 발주처 2026-11-11 "저장·계산값은 변경하지 않고, 화면에서만 음수를 그대로
     * 보이지 않게 하고 재고를 확인해 달라는 안내로 표시".
     *
     * <p>실제 이관 대상 92행 중 3행이 이 경우다(성림도서 D:VOCA −12, 원영도서 수학1·수학2 각 −70).
     */
    @Test
    @DisplayName("‼️출고가 음수면 경고가 붙는다 — 단 shipQty 는 음수 그대로 둔다")
    void 출고음수면_경고가_붙는다() {
        // 성림도서 D:VOCA 와 같은 모양: 매입 0 · 반품 0 · 재고 12 → 출고 −12
        DealerStockRow row = DealerStockRow.of(product(), 0, 0, 12, 0, 0, null);

        assertThat(row.shipQty())
                .as("‼️계산값을 손대면 안 된다(발주처: 저장·계산값은 변경하지 않습니다)")
                .isEqualTo(-12);
        assertThat(row.shipQtyNegative()).isTrue();
        assertThat(row.shipQtyNotice())
                .as("화면이 음수 대신 띄울 문구")
                .isEqualTo(DealerStockRow.NEGATIVE_NOTICE)
                .contains("재고를 확인");
    }

    @Test
    @DisplayName("출고가 0 이면 경고가 아니다 — 음수일 때만 붙는다")
    void 출고0은_경고아님() {
        DealerStockRow row = DealerStockRow.of(product(), 10, 0, 10, 0, 0, null);
        assertThat(row.shipQty()).isZero();
        assertThat(row.shipQtyNegative()).isFalse();
        assertThat(row.shipQtyNotice())
                .as("문구가 null 이어야 응답에서 빠진다(Jackson NON_NULL)")
                .isNull();
    }

    @Test
    @DisplayName("양수 출고에는 경고가 없다")
    void 양수출고는_경고없음() {
        DealerStockRow row = DealerStockRow.of(product(), 100, 10, 20, 0, 0, null);
        assertThat(row.shipQtyNegative()).isFalse();
        assertThat(row.shipQtyNotice()).isNull();
    }
}
