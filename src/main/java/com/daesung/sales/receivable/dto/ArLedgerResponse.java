package com.daesung.sales.receivable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 외상매출장(24p) — <b>도서 단위 명세 + 러닝밸런스</b>.
 * 근거: 레거시 외상매출장조회.vb {@code Refresh_DataGridView()}(기본 그리드) +
 * 정본 24p 주요 데이터 항목
 * "일자/분류명/도서명/공급%/매출수량액/세액/교사용수량액/반품수량액/수금액/잔액".
 *
 * <p>★예전엔 <b>전표 단위</b>로 {@code 일자·구분·전표번호·적요·금액·잔액} 6칸만 냈다.
 * 도서 정보가 적요 한 칸에 뭉개져 공급률·수량·세액을 볼 수 없었다 — 레거시·정본 둘 다
 * 도서 단위로 펼친다. 그 화면은 "얼마 받을 게 남았나"만이 아니라
 * <b>"무슨 책이 몇 권 얼마에 나갔나"</b>를 같이 보는 장부다.
 *
 * <p>★한 행에는 <b>해당 구분의 칸만</b> 채워진다(레거시 UNION 구조 그대로).
 * 매출 행이면 매출수량·매출금액·세액이 차고 교사용·반품·수금 칸은 비어 있다.
 */
@Schema(name = "ArLedgerResponse", description = "외상매출장(도서 단위 명세)")
public record ArLedgerResponse(
        @Schema(description = "거래처 id") Long partnerId,
        @Schema(description = """
                거래처코드. 10/27 피드백 §7-A "기본 표시 항목: 조회기준일, 거래처명,
                **거래처코드**, (주)대성학력개발연구소, 입금계좌".
                ‼️<b>행(컬럼)에서는 뺐다</b> — 거래처 하나를 보는 장부라 매 줄에 같은 코드가
                반복될 뿐이다. 제목 줄에 한 번만 쓴다(레거시 인쇄물: `10003 ─ 강남 하람도서`).""")
        String partnerCode,
        @Schema(description = "거래처명 — 제목에 쓴다(`외상매출장(거래처명)`)") String partnerName,
        @Schema(description = "조회 시작일") LocalDate fromDate,
        @Schema(description = "조회 종료일") LocalDate toDate,

        @Schema(description = "공급자명(인쇄물 하단 왼쪽). 예: 주식회사 대성학력개발연구소")
        String supplierName,
        @Schema(description = """
                입금계좌 안내 문구(인쇄물 하단 오른쪽). 예: `신한은행 140-006-456354`.
                설정 `daesung.supplier.bank-account` 값이며, 계좌가 바뀌면 설정만 고친다.""")
        String bankAccount,
        @Schema(description = "기초이월(시작일 직전까지 잔액)") long opening,
        @Schema(description = "기말잔액(누계 마지막)") long closing,
        @Schema(description = "명세 라인(일자순)") List<Line> lines
) {
    @Schema(name = "ArLedgerLine")
    public record Line(
            @Schema(description = """
                    행 종류 — `DETAIL`(명세) / `OPENING`(이월 금액) /
                    `DAY_SUBTOTAL`(일 계) / `MONTH_SUBTOTAL`(월 계) / `RUNNING_TOTAL`(누 계).

                    ★근거: 특약점 배포용 PDF 실물(`SALES_*.pdf`, 2026-10-02 수신)과
                    레거시 외상매출장조회 화면이 **이 네 종류 집계행을 함께 인쇄**한다.
                    없으면 화면이 직접 더해야 하는데, 그러면 서버 숫자와 화면 숫자가 갈린다.

                    ‼️더프원장(`DuffLedgerResponse`)과 **같은 방식**이다 — 두 장부가 다른 구조면
                    화면이 매번 다른 코드를 써야 한다.""")
            String rowType,
            @Schema(description = "집계행 표시명('이월 금액'·'일 계'·'월 계'·'누 계'). 명세행은 null")
            String label,
            @Schema(description = "거래일자") LocalDate date,
            @Schema(description = "전표번호(매출번호 또는 수금번호)") String refNo,
            @Schema(description = "분류코드") String catCode,
            @Schema(description = """
                    분류명. ‼️<b>수금 행은 여기에 수금 방법</b>을 적는다(`현금`·`어음`…) —
                    레거시 인쇄물이 그렇다(10/27 피드백 §7-A "수금 행: 분류명 칸에 수금 방법").""")
            String catName,
            @Schema(description = "도서코드") String productCode,
            @Schema(description = """
                    도서명. 레거시처럼 회차·학교를 함께 붙인다 —
                    `도서명 [3회] <강남대성학원>`. 같은 책이 회차·학교별로 여러 줄 나오므로
                    이름만으로는 어느 줄인지 가릴 수 없다.""")
            String productName,
            @Schema(description = "공급률(%)") Integer supplyRate,

            @Schema(description = "매출수량(매출 행만)") Long saleQty,
            @Schema(description = "매출금액(공급가)") Long saleAmount,
            @Schema(description = "세액") Long tax,

            @Schema(description = """
                    교사용 수량. ‼️**공급률이 있는 무가만** 교사용으로 본다(레거시 `무상 AND supRate<>0`).
                    공급률 0인 무가는 증정이라 채권과 무관하다.""")
            Long teacherQty,
            @Schema(description = "교사용 금액") Long teacherAmount,

            @Schema(description = "반품수량(음수)") Long returnQty,
            @Schema(description = "반품금액(음수)") Long returnAmount,

            @Schema(description = "수금액(수금 행만)") Long collectAmount,

            @Schema(description = "채권 증감(매출·교사용 +, 반품·수금 −)") long amount,
            @Schema(description = "누계(러닝밸런스)") long balance
    ) {

        /**
         * 명세행. ★<b>한 행에 매출·교사용·반품이 함께 들어갈 수 있다</b>(10/27 피드백 §7-A
         * "같은 도서의 매출·교사용·반품은 한 행의 컬럼으로"). 예전엔 전표 하나가 행 하나라
         * 같은 날 같은 책인데도 교사용만 있는 줄이 따로 생겼다.
         */
        public static Line detail(LocalDate date, String refNo, String catCode,
                                  String catName, String productCode, String productName,
                                  Integer supplyRate, Long saleQty, Long saleAmount, Long tax,
                                  Long teacherQty, Long teacherAmount, Long returnQty,
                                  Long returnAmount, Long collectAmount, long amount, long balance) {
            return new Line("DETAIL", null, date, refNo, catCode, catName,
                    productCode, productName, supplyRate, saleQty, saleAmount, tax,
                    teacherQty, teacherAmount, returnQty, returnAmount, collectAmount,
                    amount, balance);
        }

        /**
         * 집계행(이월·일계·월계·누계). 분류·도서 칸은 비운다 —
         * 채우면 특정 도서의 숫자로 읽힌다(레거시 인쇄물도 비어 있다).
         */
        public static Line aggregate(String rowType, String label, LocalDate date,
                                     Long saleQty, Long saleAmount, Long tax,
                                     Long teacherQty, Long teacherAmount,
                                     Long returnQty, Long returnAmount,
                                     Long collectAmount, long balance) {
            return new Line(rowType, label, date, null, null, null, null, null, null,
                    saleQty, saleAmount, tax, teacherQty, teacherAmount,
                    returnQty, returnAmount, collectAmount, 0L, balance);
        }

    }
}
