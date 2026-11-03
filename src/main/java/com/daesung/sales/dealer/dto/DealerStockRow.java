package com.daesung.sales.dealer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 특약점 재고현황 한 줄. 근거: 10/31 클라이언트 회신 §3.
 * "구매·반품·재고를 수기로 입력하고, 출고는 구매−재고−반품으로 계산해 표시만 한다."
 */
@Schema(name = "DealerStockRow", description = "특약점 재고현황 — 교재만, 노출되는 도서만")
public record DealerStockRow(
        @Schema(description = "도서코드") String productCode,
        @Schema(description = "도서명") String productName,
        @Schema(description = "분류명") String catName,
        @Schema(description = "구매(수기입력)") int purchaseQty,
        @Schema(description = "반품(수기입력)") int returnQty,
        @Schema(description = "재고(수기입력)") int stockQty,
        @Schema(description = "출고 = 구매 − 재고 − 반품(서버가 계산, 저장하지 않음)")
        int shipQty
) {
    public static DealerStockRow ofSaved(com.daesung.sales.product.entity.PartnerProductStock s) {
        var p = s.getProduct();
        return new DealerStockRow(p.getCode(), p.getName(), p.getCatName(),
                s.getPurchaseQty(), s.getReturnQty(), s.getStockQty(), s.shipQty());
    }

    /** 아직 입력한 적 없는 도서 — 전부 0. */
    public static DealerStockRow zero(com.daesung.sales.product.entity.Product p) {
        return new DealerStockRow(p.getCode(), p.getName(), p.getCatName(), 0, 0, 0, 0);
    }
}
