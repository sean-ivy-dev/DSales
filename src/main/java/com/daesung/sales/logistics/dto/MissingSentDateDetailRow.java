package com.daesung.sales.logistics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 발송일 빈 <b>이관 주문</b> 한 줄 — 일괄 발송완료의 <b>대상 목록</b>이다
 * (발주처 2026-11-26 "지우기 전에 대상 목록 먼저").
 *
 * <p>★한 줄 = 주문 하나다. 이관분은 등록번호가 없어 발송이
 * <b>(일자 + 거래처 + 학교 + 분류)</b> 로 묶이고, 그 묶음이 주문이다.
 * 그래서 {@code shipmentCount} 가 1보다 큰 줄이 있다.
 */
@Schema(name = "MissingSentDateDetailRow", description = "발송일 빈 이관 주문 — 대상 목록 한 줄")
public record MissingSentDateDetailRow(

        @Schema(description = "주문일(거래일자)", example = "2026-01-02")
        LocalDate tradeDate,

        @Schema(description = "거래처코드", example = "A30D201")
        String partnerCode,

        @Schema(description = "거래처명", example = "리브커넥스(주)")
        String partnerName,

        @Schema(description = "학교코드(없으면 빈 문자열)")
        String schoolCode,

        @Schema(description = "학교명(없으면 빈 문자열)", example = "디랩 분당")
        String schoolName,

        @Schema(description = "분류(매출구분에서 따온 값)", example = "기타고사")
        String tradeClass,

        @Schema(description = "진행상태 코드", example = "READY_TO_SHIP")
        String progressState,

        @Schema(description = "진행상태 표기", example = "발송준비중")
        String progressStateName,

        @Schema(description = """
                그 주문에 달린 **발송 건수**. 1보다 큰 줄이 있다 —
                주문 수와 발송 수가 다른 이유다(11/23 목록: 641 주문 · 1,217 발송).""",
                example = "2")
        long shipmentCount
) {
}
