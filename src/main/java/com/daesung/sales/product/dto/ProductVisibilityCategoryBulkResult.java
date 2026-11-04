package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 거래처별 노출 분류 단위 일괄 적용 결과 — 도서 수 × 거래처 수 조합 전체의 합계. */
@Schema(name = "ProductVisibilityCategoryBulkResult", description = "분류 단위 일괄 적용 결과")
public record ProductVisibilityCategoryBulkResult(
        @Schema(description = "대상이 된 도서 수") int productCount,
        @Schema(description = "요청한 거래처 수(중복 제거 후)") int partnerCount,
        @Schema(description = "새로 '열기'로 지정한 조합 건수") int opened,
        @Schema(description = "'열기' 지정을 지운 조합 건수") int closed,
        @Schema(description = "이미 그 상태여서 바뀐 것이 없는 조합 건수") int unchanged
) {
}
