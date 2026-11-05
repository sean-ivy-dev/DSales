package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * [특약점 사이트] 재고현황 매입·반품 이관 베이스라인 시딩 — 거래처×도서 한 건.
 * 근거: 11/05 회신 §4 "매입 = 이관 시작값 + 기준일 이후 집계".
 */
@Schema(name = "PartnerProductStockSeedRequest", description = "재고현황 이관 베이스라인 시딩 — 거래처×도서 한 건")
public record PartnerProductStockSeedRequest(
        @Schema(description = "거래처코드", requiredMode = Schema.RequiredMode.REQUIRED, example = "A10A195")
        @NotBlank String partnerCode,
        @Schema(description = "도서코드", requiredMode = Schema.RequiredMode.REQUIRED, example = "DL-H2024A-01")
        @NotBlank String productCode,
        @Schema(description = "매입 이관 시작값") @PositiveOrZero Integer purchaseQty,
        @Schema(description = "반품 이관 시작값") @PositiveOrZero Integer returnQty,
        @Schema(description = "재고 이관 시작값") @PositiveOrZero Integer stockQty
) {
}
