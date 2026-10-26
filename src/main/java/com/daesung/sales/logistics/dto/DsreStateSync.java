package com.daesung.sales.logistics.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 작업요청서 발송완료·발송취소 때 묶인 신청의 DSRE 진행상태를 함께 옮긴 결과(신청 1건). */
@Schema(name = "DsreStateSync", description = "신청별 DSRE 진행상태 반영 결과")
public record DsreStateSync(
        @Schema(description = "신청번호(DSRE reqCd)", example = "78331") int reqCd,
        @Schema(description = "DSRE 상태를 실제로 바꿨으면 true") boolean changed,
        @Schema(description = "처리 전 상태코드", example = "W") String fromCode,
        @Schema(description = "처리 전 상태명", example = "발송준비중") String fromName,
        @Schema(description = "처리 후 상태코드", example = "D") String toCode,
        @Schema(description = "처리 후 상태명", example = "발송완료") String toName,
        @Schema(description = "바꾸지 않았거나 일부만 바꾼 이유. 바꿨으면 null",
                example = "상품준비중 상태라 발송완료로 바꾸지 않았습니다.") String message) {
}
