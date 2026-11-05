package com.daesung.sales.sale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 월별매출액명세서(37p). 근거: 재무팀 파일 '연구소 월별매출액 명세서'.
 * 구분(대분류)×상품별 성적처리/비처리 인원·금액 + 계 + 과세매출액 + 부가세. 대분류 소계·총계 포함.
 * ★대분류는 세부구분 마스터에서 파생(발주처 회신 2026-08-20). 예전엔 cat_code 첫 글자였다.
 * 인원 = 매출 수량(모의고사는 응시인원), 금액 = 공급가액. 매출(SALE)만 집계(무상·반품 제외).
 * ⚠️ 성적처리 구분은 sale.proc_type(null=비처리). DSRE 모의고사 자동 인원/처리 import는 후속.
 */
public record MonthlyStatementResponse(
        @Schema(description = "연도", example = "2026") int year,
        @Schema(description = "월", example = "6") int month,
        @Schema(description = "명세 행(상세/대분류계/총계)") List<Row> rows
) {
    @Schema(name = "MonthlyStatementRow")
    public record Row(
            @Schema(description = "행 구분", allowableValues = {"GRAND_TOTAL", "MAJOR_SUBTOTAL", "DETAIL"})
            RowType rowType,
            @Schema(description = "대분류 코드(세부구분 마스터에서 파생). 미지정은 null") String majorCode,
            @Schema(description = "대분류 명칭(모의고사·교재 …). 미지정은 '미분류'") String majorName,
            @Schema(description = "분류명(상세행)") String catName,
            @Schema(description = "도서코드(상세행)") String bookCode,
            @Schema(description = "도서명(상세행)") String bookName,
            @Schema(description = "성적처리 인원") long gradedQty,
            @Schema(description = "성적처리 금액(공급가)") long gradedAmount,
            @Schema(description = "비처리 인원") long ungradedQty,
            @Schema(description = "비처리 금액(공급가)") long ungradedAmount,
            @Schema(description = "계 인원(성적처리+비처리)") long totalQty,
            @Schema(description = "계 금액(공급가)") long totalAmount,
            @Schema(description = "과세매출액(과세분 공급가)") long taxableAmount,
            @Schema(description = "부가세") long vat
    ) {
        public enum RowType {
            /** 총 계 */ GRAND_TOTAL,
            /** 대분류 계 */ MAJOR_SUBTOTAL,
            /**
             * 분류 계 — 근거: 발주처 검수 F-21. "집계 단계 추가: 대분류 &gt; 세부상품명 →
             * <b>대분류 &gt; 분류명 &gt; 세부상품명</b>. 지금 소계가 전부 도서명 기준이라 어떤
             * 상품인지 모름."
             *
             * <p>★F-20("세부상품명 중복 표시")도 이 행이 없어서 생긴 현상이다 — 같은 도서명이
             * 분류마다 별개 상품으로 존재할 수 있는데(V72: 도서코드 유니크 범위가
             * 분류코드+도서코드), 분류 구분선이 없어 "같은 이름이 여러 번"으로 보였다.
             */
            CAT_SUBTOTAL,
            /** 상품 상세 */ DETAIL
        }
    }
}
