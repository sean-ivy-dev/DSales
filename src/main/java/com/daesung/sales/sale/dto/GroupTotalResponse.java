package com.daesung.sales.sale.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 통합 매출 조회의 <b>묶음 합계</b> 한 줄 — 화면이 소계 행을 붙이는 데 쓴다(프론트 B-3).
 *
 * <p>지금까지는 화면이 전량을 받아 직접 더했다. 1~10월 27,187건에 <b>약 4분</b>이 걸려
 * 1만 건이 넘으면 소계를 못 붙였다(프론트 실측). DB가 세면 한 번이면 끝난다.
 *
 * <p>조건은 {@code GET /sales} 와 <b>완전히 같다</b> — 서버에서 같은 WHERE 절을 공유하므로
 * 목록과 소계가 다른 집합을 셀 수 없다.
 */
@Schema(name = "GroupTotal", description = "묶음 합계 한 줄")
public record GroupTotalResponse(

        @Schema(description = "묶음 키 — groupBy=PARTNER면 거래처 id, DATE면 거래일자",
                example = "1381")
        String key,

        @Schema(description = "묶음 이름 — 화면 소계 행에 그대로 쓴다", example = "강남 하람도서")
        String name,

        @Schema(description = "수량 합", example = "1250")
        long qty,

        @Schema(description = "공급가액 합", example = "8750000")
        long supplyAmount,

        @Schema(description = "세액 합", example = "0")
        long tax,

        @Schema(description = "합계금액(공급가액+세액) 합", example = "8750000")
        long totalAmount
) {
}
