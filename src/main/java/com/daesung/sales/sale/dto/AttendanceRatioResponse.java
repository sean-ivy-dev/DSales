package com.daesung.sales.sale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 응시현황 — <b>지역비율(보급률)</b> 표(F-22·F-23).
 *
 * <p>레거시 {@code 응시현황.vb} '지역비율' 라디오 그대로:
 * <pre>
 *   지역 │ 인원[1·2·3·계] │ 매출[1·2·3·계] │ 비율[1·2·3·계]
 * </pre>
 *
 * <p>‼️세 칸의 뜻이 이름과 다르다 — 레거시 SQL 로 확정한 것이다.
 * <ul>
 *   <li><b>인원 = 학교의 재학생 수</b>({@code schools.clsN_student_cnt}). 매출 수량이 아니다.</li>
 *   <li><b>매출 = 판매 부수</b>({@code sum(qty)}). 금액이 아니다 —
 *       레거시에서 금액 줄이 주석으로 막혀 있다.</li>
 *   <li><b>비율 = 부수 ÷ 재학생 × 100 = 보급률(%)</b>. 인원이 0이면 <b>null</b>(0으로 나누지 않는다).</li>
 * </ul>
 */
@Schema(name = "AttendanceRatioResponse", description = "응시현황 지역비율(보급률) — 인원·부수·비율")
public record AttendanceRatioResponse(

        @Schema(description = "조회 연도", example = "2026") int year,

        @Schema(description = "행 목록(지역 → 합계)") List<Row> rows
) {
    @Schema(name = "AttendanceRatioRow")
    public record Row(

            @Schema(description = "행 종류 REGION(지역)/TOTAL(합계)", example = "REGION")
            String rowType,

            @Schema(description = """
                    지역 표시. 레거시 그대로 — 지역구분의 도시명을 쓰고,
                    `특약점외`(거래처코드 90001 이상)는 **'기타'**, 합계행은 **'합계'**.""",
                    example = "수원")
            String region,

            @Schema(description = "지역구분 키(특약점_1서울 · 특약점외). 정렬·추적용", example = "특약점_3수원")
            String regionGroup,

            @Schema(description = "1학년 재학생 수") long studentCnt1,
            @Schema(description = "2학년 재학생 수") long studentCnt2,
            @Schema(description = "3학년 재학생 수") long studentCnt3,
            @Schema(description = "재학생 수 계") long studentCntTotal,

            @Schema(description = "1학년 판매 부수(**금액 아님**)") long bookCnt1,
            @Schema(description = "2학년 판매 부수") long bookCnt2,
            @Schema(description = "3학년 판매 부수") long bookCnt3,
            @Schema(description = "판매 부수 계") long bookCntTotal,

            @Schema(description = "1학년 보급률 % (부수÷재학생×100, 소수 2자리). 재학생 0이면 null")
            Double ratio1,
            @Schema(description = "2학년 보급률 %") Double ratio2,
            @Schema(description = "3학년 보급률 %") Double ratio3,
            @Schema(description = "보급률 계 %") Double ratioTotal,

            @Schema(description = "집계에 든 학교 수 — 비율이 이상할 때 모집단을 확인하는 칸")
            int schoolCount
    ) {
    }
}
