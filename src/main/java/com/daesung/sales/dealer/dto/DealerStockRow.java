package com.daesung.sales.dealer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 특약점 재고현황 한 줄. 근거: 10/31 클라이언트 회신 §3.
 * "구매·반품·재고를 수기로 입력하고, 출고는 구매−재고−반품으로 계산해 표시만 한다."
 *
 * <p>★<b>매입·반품 자동집계(11/05 회신 §4)</b> — cutover 기준일이 설정된 뒤로는
 * {@code purchaseQty}·{@code returnQty}가 "이관 시작값 + 기준일 이후 매출프로그램 집계"다.
 * 기준일이 없으면(지금 운영 상태) 그 두 값은 그대로 수기 입력값이다 — {@code DealerStockService}
 * 가 어느 쪽인지를 가르고, 이 record 는 합산된 결과만 받는다.
 */
@Schema(name = "DealerStockRow", description = "특약점 재고현황 — 교재만, 노출되는 도서만")
public record DealerStockRow(
        @Schema(description = "도서코드") String productCode,
        @Schema(description = "도서명") String productName,
        @Schema(description = "분류명") String catName,
        @Schema(description = "매입(cutover 전: 수기입력 / cutover 후: 이관시작값+자동집계)")
        int purchaseQty,
        @Schema(description = "반품(cutover 전: 수기입력 / cutover 후: 이관시작값+자동집계)")
        int returnQty,
        @Schema(description = "재고(수기입력, 항상)") int stockQty,
        @Schema(description = "출고 = 매입 − 재고 − 반품(서버가 계산, 저장하지 않음)")
        int shipQty,
        @Schema(description = "재고 최종수정일(특약점이 재고를 마지막으로 저장한 날). "
                + "이관 행은 DSRE EDIT_WEB 값. 없으면 null. "
                + "★이관·자동집계로는 바뀌지 않는다 — 재고를 저장할 때만 바뀐다",
                example = "2026-11-05")
        java.time.LocalDate stockUpdatedOn
) {
    /**
     * @param basePurchase  이관 시작값(또는 cutover 전엔 저장된 수기입력값) 매입
     * @param baseReturn    같은 의미의 반품
     * @param stockQty      재고(항상 수기입력)
     * @param addedPurchase cutover 기준일 이후 매출프로그램 자동집계 매입(비활성 시 0)
     * @param addedReturn   같은 의미의 반품(비활성 시 0)
     * @param stockUpdatedOn 재고 최종수정일(없으면 null)
     */
    public static DealerStockRow of(com.daesung.sales.product.entity.Product p,
                                     int basePurchase, int baseReturn, int stockQty,
                                     int addedPurchase, int addedReturn,
                                     java.time.LocalDate stockUpdatedOn) {
        int purchase = basePurchase + addedPurchase;
        int ret = baseReturn + addedReturn;
        return new DealerStockRow(p.getCode(), p.getName(), p.getCatName(),
                purchase, ret, stockQty, purchase - stockQty - ret, stockUpdatedOn);
    }
}
