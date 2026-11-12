package com.daesung.sales.dsre.gateway;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 지사(DSRE2 {@code tbl_cust_info}). 주문 등록의 {@code CUST_CD} 는 <b>이 코드</b>다.
 *
 * <p>★우리 거래처 마스터로는 대신할 수 없다. 우리 {@code partners.code} 는 <b>매출코드</b>
 * (예: {@code A26D153})이고, 주문이 요구하는 것은 DSRE2 <b>지사코드</b>(예: {@code 70501})다.
 * 둘은 같은 거래처를 가리키지만 값이 다르다 — 더프 매핑에서도 같은 혼동이 있었다.
 * 그래서 {@code machulCode} 를 함께 내려, 화면이 우리 마스터와 이어 볼 수 있게 한다.
 */
@Schema(name = "BranchRow", description = "지사(주문 등록용)")
public record BranchRow(
        @Schema(description = "지사코드(DSRE2 CUST_CD) — 주문 등록에 쓰는 값", example = "70501")
        String custCode,
        @Schema(description = "지사명") String custName,
        @Schema(description = "지사 풀네임") String custFullName,
        @Schema(description = "관할/도시명") String cityName,
        @Schema(description = "매출코드 — 우리 거래처 마스터의 코드와 같은 값", example = "A26D153")
        String machulCode,

        /**
         * <b>만료 지사인가</b>(DSRE {@code tbl_cust_info.END_GUBUN = 'Y'}, 기본 N).
         *
         * <p>‼️DSRE 에는 <b>만료 날짜 칸이 없다</b> — 이 플래그가 전부다.
         * <p>‼️거래처명 앞의 {@code [만료]} 표시는 기준이 <b>아니다</b>(특약점팀 실측 2026-11-12:
         * Y 인데 표시 없는 곳 10곳 · 표시는 있는데 N 인 곳 1곳).
         */
        @Schema(description = "만료 지사 여부(DSRE END_GUBUN='Y'). DSRE 에는 만료일 칸이 없다",
                example = "false")
        boolean expired
) {
}
