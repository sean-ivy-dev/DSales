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

        @Schema(description = "발송 건수", example = "1217")
        long count,

        @Schema(description = """
                **주문 수**(발송을 묶은 수). 이관분만 셀 때만 채워진다 — 그 밖에는 `null`.

                ★이관분은 등록번호가 없어 발송이 **(일자 + 거래처 + 학교 + 분류)** 로 묶이고,
                그 묶음 하나가 주문 하나다. 한 주문에 발송이 여럿일 수 있어
                **주문 수 ≤ 발송 수** 다(11/23 목록: 641 주문 · 1,217 발송).""", example = "641")
        Long orderCount
) {
    /** 이관분이 아닌 집계 — 주문 수는 낼 수 없다. */
    public static MissingSentDateRow of(Integer year, String state, String label, long count) {
        return new MissingSentDateRow(year, state, label, count, null);
    }
}
