package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 거래처별 노출 일괄 적용 결과. 모양은 거래처별 단가 일괄({@code PartnerSupplyRateBulkResult})과 맞춘다. */
@Schema(name = "ProductVisibilityBulkResult", description = "일괄 적용 결과")
public record ProductVisibilityBulkResult(
        @Schema(description = "도서코드") String productCode,
        @Schema(description = "요청한 거래처 수(중복 제거 후)") int requested,
        @Schema(description = "새로 '열기'로 지정한 건수") int opened,
        @Schema(description = "'열기' 지정을 지운 건수") int closed,
        @Schema(description = "이미 그 상태여서 바뀐 것이 없는 건수") int unchanged
) {
}
