package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 도서별 노출 체크박스 변경 요청. */
public record ProductVisibilityRequest(
        @Schema(description = "true=열기(강제 노출) / false=닫기(강제 비노출)", example = "false")
        boolean visible,
        @Schema(description = "변경 사유(선택) — 변경 이력에 남는다") String reason
) {
}
