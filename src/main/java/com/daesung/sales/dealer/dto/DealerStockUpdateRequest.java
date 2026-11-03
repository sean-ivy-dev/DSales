package com.daesung.sales.dealer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/** 특약점 재고현황 입력 — 거래처×도서 한 건. */
public record DealerStockUpdateRequest(
        @Schema(description = "주문 거래처 코드", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String partnerCode,
        @Schema(description = "도서코드", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String productCode,
        @Schema(description = "구매. 미입력(null)이면 기존 값 유지") @PositiveOrZero Integer purchaseQty,
        @Schema(description = "반품. 미입력(null)이면 기존 값 유지") @PositiveOrZero Integer returnQty,
        @Schema(description = "재고. 미입력(null)이면 기존 값 유지") @PositiveOrZero Integer stockQty
) {
}
