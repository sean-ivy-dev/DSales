package com.daesung.sales.sale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 응시현황(연도별) — <b>월별 수량·매출 크로스탭</b>. 조회구분에 따라 거래처 단위 / 학교 단위.
 *
 * <p>이름이 '응시현황'이라 시험 신청·응시율 표로 오해하기 쉬운데, 레거시 {@code 응시현황.vb} 실물은
 * {@code 1월[수량]·1월[매출] … 12월[수량]·12월[매출] + 합계} 구성이다.
 * 신청·응시율 같은 항목은 레거시 어디에도 없다.
 *
 * <p>레거시 두 화면은 <b>같은 쿼리의 rollup 깊이 차이</b>다 —
 * 지역/특약점({@code Refresh_DatagridView_특약점})은 {@code rollup(지역구분, custCode)},
 * 학교/원({@code Refresh_DatagridView_학교})은 {@code rollup(지역구분, custCode, schCode)}.
 * 그래서 경로를 둘로 두지 않고 {@code groupBy} 로 가른다(계산식을 두 곳에 두지 않는다).
 *
 * <p>★<b>매출이 없는 거래처·학교도 행이 나온다.</b> 레거시가 학교 전체를 만들고 매출을
 * {@code LEFT OUTER JOIN} 으로 붙이기 때문이다 — "안 판 학교"를 보는 것이 이 화면의 목적이다.
 *
 * <p>★<b>학교코드가 붙은 매출만</b> 센다. 학교에 붙지 않은 출고(본사 판매실·온라인몰 등)는
 * 어느 행에도 들어가지 않는다(레거시 조인 구조가 그렇다).
 */
@Schema(name = "AttendanceResponse", description = "응시현황(연도별) 월 크로스탭")
public record AttendanceResponse(
        @Schema(description = "조회 연도") int year,
        @Schema(description = """
                행 목록. `groupBy=PARTNER` 면 거래처 → 지역구분 소계 → 총계,
                `groupBy=SCHOOL` 면 학교 → 거래처 소계 → 지역구분 소계 → 총계.""")
        List<Row> rows
) {
    @Schema(name = "AttendanceRow")
    public record Row(
            @Schema(description = """
                    행 종류 — `SCHOOL`(학교) / `PARTNER`(거래처) /
                    `PARTNER_SUBTOTAL`(거래처 소계, 학교 단위에서만) /
                    `REGION_SUBTOTAL`(지역구분 소계) / `TOTAL`(총계).
                    레거시 라벨은 특약점 보기에서 소 계·총 계, 학교 보기에서 소 계·합 계·총 계다.""")
            String rowType,
            @Schema(description = "지역구분", example = "특약점_1서울") String regionGroup,
            @Schema(description = "거래처코드") String partnerCode,
            @Schema(description = "거래처명") String partnerName,
            @Schema(description = "지역(도시명)") String cityName,
            @Schema(description = "상세지역(레거시 custLoc)") String partnerLoc,
            @Schema(description = "학교코드 — 학교 단위 행에만") String schoolCode,
            @Schema(description = "학교명 — 학교 단위 행에만") String schoolName,
            @Schema(description = "거래처 안에서의 순번 — 학교 단위 행에만", example = "1")
            Integer seq,
            @Schema(description = "학교Y/N — 학교 단위 행에만. true=학교, false=학원")
            Boolean isSchool,
            @Schema(description = """
                    학교 수. 레거시 `[학년][학교]` 칸이다.
                    ‼️레거시 '전체' 칸은 `COUNT(cls1GCnt)+SUM(cls2GCnt)+SUM(cls3GCnt)` 로
                    **학교 수와 학급수 합이 뒤섞여** 있다(1학년만 COUNT). 그 뒤섞임은 옮기지 않고
                    **언제나 학교 수**로 낸다 — 학년을 바꿔도 학교 수가 달라지지 않는다.""")
            long schoolCount,
            @Schema(description = """
                    재학생 수. 레거시 `[학년][인원]` = `SUM(cls{n}SCnt)`.
                    학년을 지정하면 그 학년, 미지정이면 1~3학년 합이다.""")
            long studentCnt,
            @Schema(description = "월별 수량 1~12월") List<Long> monthlyQty,
            @Schema(description = "월별 매출 1~12월") List<Long> monthlyAmount,
            @Schema(description = "합계 수량") long totalQty,
            @Schema(description = "합계 매출") long totalAmount
    ) {
    }
}
