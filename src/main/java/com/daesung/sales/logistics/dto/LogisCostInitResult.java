package com.daesung.sales.logistics.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 빈 시행 일괄 추가 결과(F-39 「DSRE 상품 가져오기」).
 *
 * <p>레거시는 {@code "n개의 항목 추가 완료"} 메시지만 띄웠다. 화면이 그 문구를 그대로
 * 쓸 수 있도록 <b>추가한 수</b>를 준다.
 */
@Schema(description = "빈 시행 일괄 추가 결과")
public record LogisCostInitResult(
        @Schema(description = "기본값으로 새로 추가한 시행 수. 추가할 것이 없으면 0", example = "17")
        int added) {
}
