package com.daesung.sales.receivable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 외상매출장 '더프모만'(24p) — <b>기본 외상매출장과 컬럼이 통째로 다르다</b>.
 *
 * <p>정본 24p: "'더프모만' 체크 시 그리드 컬럼 구조 자체가 학교(원)별/학년별/시행월별
 * 세분화 구조로 <b>완전 전환</b>됨(조건부 스키마)". 그래서 {@link ArLedgerResponse}에
 * 필드를 얹지 않고 별도 응답으로 낸다 — 한 응답에 두 스키마를 우겨넣으면
 * 절반이 늘 비어 있는 DTO가 된다.
 *
 * <pre>
 *   [기본]   일자 · 구분 · 전표번호 · 적요 · 금액 · 잔액        ← ArLedgerResponse
 *   [더프모] 일자 · 학교(원)명 · 시행월 · 학년 · 처리 · 정가 · 공급률 · 수량 · 금액
 * </pre>
 *
 * <p>기본 장부는 <b>채권 러닝밸런스</b>(매출·반품·수금을 합산한 잔액)를 보는 화면이고,
 * 더프모는 <b>모의고사 매출의 세부 내역</b>을 보는 화면이라 잔액 개념이 없다.
 * 레거시도 두 그리드를 완전히 다른 SQL로 만든다(외상매출장조회.vb:603).
 */
@Schema(name = "DuffLedgerResponse", description = "외상매출장 더프모만(24p) — 모의고사 세부 명세")
public record DuffLedgerResponse(
        @Schema(description = "거래처 id") Long partnerId,
        @Schema(description = "거래처코드(우리 마스터, DSLab 매출코드 'A10A195' 꼴)")
        String partnerCode,

        @Schema(description = """
                **DSRE 거래처코드**('10003' 꼴). ★<b>PDF 파일명이 쓰는 값은 이쪽이다</b> —
                발주처 지정 `RESULT_{DSRE 거래처코드}_{거래처명}_{yyyyMMdd}-{yyyyMMdd}.pdf`,
                예) `RESULT_10003_강남 하람도서_20260801-20260831.pdf`.

                ‼️`partnerCode`(A10A195)와 **다른 체계**다. 처음에 그걸 실어 보냈는데
                그대로 쓰면 `RESULT_A10A195_…` 가 된다.
                비어 있으면 `POST /masters/clients/sync-dsre-code` 로 채운다.""")
        String partnerDsreCode,
        @Schema(description = "거래처명") String partnerName,
        @Schema(description = "조회 시작일") LocalDate fromDate,
        @Schema(description = "조회 종료일") LocalDate toDate,

        // ★제목·하단은 일반 외상매출장(§7-A)과 같은 양식이다(10/27 피드백 §7-B "제목·하단: 7-A와 같다").
        //   실물 PDF(RESULT_*.pdf) 하단도 "(주)대성학력개발연구소 / 입금계좌 : 신한은행 …"로 같다.
        @Schema(description = "공급자명(인쇄물 하단 왼쪽)") String supplierName,
        @Schema(description = "입금계좌 안내 문구(인쇄물 하단 오른쪽)") String bankAccount,
        @Schema(description = "명세 + 소계(학교(원) 계 · 월 계)") List<Row> rows,

        @Schema(description = """
                <b>요약표</b> — 처리 구분별 수량·금액과 총계(마지막 행).

                근거: 레거시 `외상매출장조회.vb:778` 가 명세와 **별개 SQL**로
                `GROUP BY rollup(처리)` 를 떠서 두 번째 그리드(`DataGridView_요약`)에 물린다.
                그리고 더프모만 PDF 는 그 둘을 같이 찍는다(`ExportPDF_요약추가`, 1684행) —
                요약이 없으면 RESULT_ 양식이 반쪽이다.""")
        List<SummaryRow> summary,

        @Schema(description = "전체 수량 합") long totalQty,
        @Schema(description = "전체 금액 합") long totalAmount
) {
    @Schema(name = "DuffLedgerSummaryRow", description = "처리 구분별 합계 1행")
    public record SummaryRow(
            @Schema(description = """
                    처리 구분('처리'·'비처리'). **미지정이면 null**(총계 행도 null 이라
                    `total` 로 가려 읽을 것). 총계 행은 **null** 이다 — 레거시 rollup 의
                    총계 행도 처리 칸이 비어 있다.

                    ‼️레거시엔 'SOLID' 구분이 하나 더 있었지만 <b>우리 모델엔 그 값이 없다</b>
                    (`sale.proc_type` 는 GRADED/미지정 두 갈래다). 없는 구분을 빈 행으로
                    만들어 두지 않는다 — 늘 0인 행은 "아직 안 팔렸다"로 잘못 읽힌다.""")
            String procType,
            @Schema(description = "총계 행인가") boolean total,
            @Schema(description = "수량 합") long qty,
            @Schema(description = "금액 합") long amount
    ) {
    }

    @Schema(name = "DuffLedgerRow")
    public record Row(
            @Schema(description = "행 종류", allowableValues = {"DETAIL", "SCHOOL_SUBTOTAL", "MONTH_SUBTOTAL"})
            String rowType,
            @Schema(description = "소계 표시명('학교(원) 계'·'월 계'). 명세행은 null") String label,
            @Schema(description = "거래일자(명세행)") LocalDate date,
            @Schema(description = "학교(원)명") String schoolName,
            @Schema(description = """
                    시행월(yyyy-MM). **2단 경로** —
                    ① 그 상품·회차의 **BOM 시행예정일**(1순위)
                    ② 없으면 **도서명 맨 앞의 'N월'**(2순위). 연도는 분류코드에서 온다
                       (판매일이 아니다 — 2026-01에 팔린 "11월 고2 수능"은 분류 M2025A… 의 2025-11 시행분).

                    ‼️처음엔 ①만 보고 비웠는데, 이관 데이터에 BOM 시행예정일을 넣은 상품이 없어
                    **전 행이 빈 칸**이었다. 운영 추출본 전수 확인 결과 2026년 더프 매출 5,112건 중
                    **4,983건(97%)**이 도서명에서 나오고, 남는 129건은 「고3 평가고사」 127건
                    (시행월 개념이 없는 상품)과 도서명 오기 2건이다 — **틀린 값이 찍히는 건은 0건**.

                    ★레거시({@code LEFT(bookName, CHARINDEX('월', bookName))})보다 좁게 잡는다.
                    그쪽은 첫 '월'까지 무조건 잘라 {@code 월례고사}가 '월'로 찍혔다. 우리는
                    **맨 앞 1~2자리 숫자 + '월'**이고 그 수가 1~12일 때만 쓴다.

                    둘 다 없으면 **비운다**. 빈 칸은 "BOM에 시행예정일을 넣어야 한다"는 신호다.""")
            String examMonth,
            @Schema(description = "학년(상품 마스터)") String grade,
            // ‼️'처리' 칸은 뺐다(10/27 피드백 §7-B "테스트 버전에서 추가된 '처리' 컬럼은 뺀다").
            //   발주처 양식은 8칸(일자·학교(원)명·시행월·학년·정가·공급률·수량·금액)이고
            //   실물 PDF(RESULT_*.pdf)에도 그 칸이 없다. 처리 구분별 집계는 **요약표**
            //   (`Summary.procType`)에 그대로 남아 있다 — 거긴 레거시도 따로 내는 블록이다.
            @Schema(description = "정가") Integer unitPrice,
            @Schema(description = "공급률(%)") Integer supplyRate,
            @Schema(description = "수량") long qty,
            @Schema(description = "금액(공급가)") long amount
    ) {
        /** 소계 행 — 수량·금액과 표시명만 채운다(레거시도 나머지 칸을 비운다). */
        public static Row subtotal(String rowType, String label, String schoolName,
                                   long qty, long amount) {
            return new Row(rowType, label, null, schoolName, null, null, null, null, qty, amount);
        }
    }
}
