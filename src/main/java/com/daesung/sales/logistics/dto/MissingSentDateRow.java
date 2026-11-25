package com.daesung.sales.logistics.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 발송일이 빈 발송의 <b>연도별·진행상태별 건수</b> 한 줄.
 *
 * <p>이관 주문을 일괄 발송완료로 바꾸는 작업의 <b>대상 건수</b>다
 * (발주처 확정 2026-11-24 §1 — 발송일은 「각 주문의 거래일자와 같은 날」로 채우고,
 * 돌리기 전에 연도별 대상 건수를 먼저 제출).
 */
@Schema(name = "MissingSentDateRow", description = "발송일 빈 발송 — 연도·진행상태별 건수")
public record MissingSentDateRow(

        @Schema(description = "거래일자 연도. 거래일자가 비어 있으면 null", example = "2026")
        Integer year,

        @Schema(description = "진행상태 코드", example = "RECEIVED")
        String progressState,

        @Schema(description = "진행상태 표기", example = "접수완료")
        String progressStateName,

        @Schema(description = "건수", example = "641")
        long count
) {
}
