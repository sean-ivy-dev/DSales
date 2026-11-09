package com.daesung.sales.receivable.dto;

import com.daesung.sales.product.entity.MajorCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** 미수금(외상매출) 현황. 거래처별 잔액 + 담보. 근거: 레거시 외상매출현황조회. */
public record ArStatusResponse(
        @Schema(description = "집계 시작일") LocalDate fromDate,
        @Schema(description = "집계 종료일") LocalDate toDate,
        @Schema(description = "거래처별 채권 현황") List<Row> rows,
        @Schema(description = "합계행") Row total,

        @Schema(description = """
                매입처 소계 — **대상이 없으면 null**. 레거시 대성교재 화면의 GROUPING 소계와
                같은 자리다(매입처 소계 / 매출처 소계 / 합계 3단). 판정은 `partnerKind` 참고.
                ‼️빈 소계행을 0으로 채워 내려보내지 않는다 — 화면에 없는 묶음이 생긴다.""")
        Row purchaseSubtotal,

        @Schema(description = "매출처 소계 — 대상이 없으면 null.") Row salesSubtotal,

        @Schema(description = """
                경고 — 비어 있으면 이상 없음.

                ★**이월 스냅샷이 없는 연도**를 조회하면 여기로 알린다. 예전엔 조용히 0으로 나가서,
                이월이 안 잡힌 채 "잔액이 맞다"고 읽혔다(실측: 부산지사 2025 미수 3,646만 원 누락).
                숫자가 틀린 게 아니라 **없는 것을 없다고 말하지 않은 것**이 문제였다.""")
        List<String> warnings
) {
    @Schema(name = "ArStatusRow")
    public record Row(
            @Schema(description = """
                    행 구분 — 명세는 `DETAIL`, 접두어 구간 소계는 `GROUP_SUBTOTAL`.

                    ★<b>엑셀이 집계행에 색을 칠하는 근거</b>이고(공통 판정식 `AGG_BY_ROW_TYPE`),
                    화면도 이 값으로 소계 줄을 굵게 그릴 수 있다.
                    ‼️예전엔 이 칸이 없어 <b>엑셀 소계행에 색이 하나도 안 칠해졌다</b> —
                    판정식이 `rowType` 을 보는데 이 DTO 에만 그 칸이 없었다(검증 2026-11-09).
                    화면이 거래처명 "소 계" 로 문자열 비교해 알아내야 했던 것도 함께 없앤다.""")
            String rowType,
            @Schema(description = "거래처 id(합계행 null)") Long partnerId,
            @Schema(description = "거래처코드") String partnerCode,
            @Schema(description = "거래처명") String partnerName,
            @Schema(description = "사장명 — 재무팀 실파일 2번째 칸") String bossName,
            @Schema(description = """
                    거래처 구분 — `PURCHASE`(매입처) / `SALES`(매출처). 소계행·합계행은 null.

                    ★레거시 대성교재 화면이 매입처·매출처로 나눠 소계를 낸다. 다만 거기 쓰인
                    **`거래처코드 첫 글자 'A'` 규칙은 쓰지 않는다** — DSTxtBook DB 안에서만
                    성립하던 규칙이라 두 법인을 한 DB에 합친 뒤에는 안 맞는다
                    (A 거래처 425곳 중 267곳이 매출처. 2026-10-22 실측).
                    **매입(입고)이 있으면 매입처**로 본다 — 매출과 겹치는 곳은 3곳뿐이다.""")
            String partnerKind,
            @Schema(description = """
                    기초 — **시작일 전날까지의 잔액**이다(그해 이월 + 1/1~시작 전날 거래).
                    ‼️예전엔 '그해 이월'만 넣어서 **같은 날짜의 잔액이 시작일에 따라 달라졌다**
                    (프론트 실측 2026-10-15). 기간을 어떻게 잡든 종료일 기준 잔액은 같아야 한다.""")
            long opening,
            @Schema(description = "기간 매출수량") long saleQty,
            @Schema(description = "기간 매출액") long saleAmount,
            @Schema(description = "기간 반품수량") long returnQty,
            @Schema(description = "기간 반품액") long returnAmount,
            @Schema(description = "기간 세액(순)") long tax,
            @Schema(description = "기간 채권발생(매출+세액−반품)") long receivableGen,
            @Schema(description = "기간 매입수량(입고). 레거시 대성교재 화면의 `입고수량` 칸.")
            long purchaseQty,
            @Schema(description = """
                    기간 **매입액**(입고). 레거시 대성교재 화면의 `입고금액` 칸이다 —
                    한 거래처 원장에 받을 돈(매출)과 줄 돈(매입)을 함께 본다.
                    매입처는 이 칸으로 늘고 수금으로 준다.

                    ‼️레거시는 **법인마다 잔액 공식이 다르다.** 대성학력개발연구소 화면
                    (`Refresh_DataGridView`, 외상매출현황조회.vb:177)의 잔액에는 입고가 **없고**,
                    대성교재 화면(`Refresh_DataGridView_대성교재`, :676)에만 있다.
                    우리는 두 법인을 한 DB에 담았는데 **수금에는 법인을 가릴 근거가 없어**
                    (매출은 상품코드 접두어로 가릴 수 있으나 수금은 불가),
                    숫자가 맞아떨어지는 **대성교재 공식으로 통합**했다 —
                    매입처 6곳의 매입과 수금이 원 단위까지 일치한다.""")
            long purchaseAmount,
            @Schema(description = "기간 수금") long collected,
            @Schema(description = "잔액(이월+채권발생+매입−수금)") long balance,
            @Schema(description = """
                    교사용(증정 포함) 공급가액. ‼️무가라 **채권에는 들어가지 않는다** —
                    잔액·채권발생과 더하지 말 것. "이 거래처에 무가로 얼마가 나갔나"를 보는 칸이다.""")
            long teacherAmount,

            @Schema(description = """
                    교사용(증정 포함) 수량. ‼️금액과 마찬가지로 **채권에는 들어가지 않는다** —
                    잔액·채권발생과 더하지 말 것.""")
            long teacherQty,
            @Schema(description = """
                    상품군(대분류)별 분해. key = 대분류 명칭(모의고사·교재·기타고사·특강·기타),
                    세부구분 미지정 상품은 `미분류`.

                    ★**칸을 고정하지 않았다.** 재무팀 실파일은 교재·모의고사·기타·특강 4칸인데
                    우리 대분류는 5종이다. 기타고사를 기타에 합쳐 4칸으로 맞출 수도 있지만
                    그건 발주처가 정할 문제다 — 여기서 조용히 합치면 기타고사 매출이
                    어느 칸에 섞였는지 화면에서 알 수 없다. 묶는 건 화면에서 한다.

                    ‼️합은 매출·반품 전체와 **일치한다**(미분류를 빼지 않았다).""")
            List<CategoryBreakdown> byCategory,

            @Schema(description = "담보금액(여신한도)") Long assureAmount,
            @Schema(description = "담보비율(%)=잔액/담보×100, 담보 없으면 null") Double assureRatio,
            @Schema(description = "담보 만기") LocalDate assureExpiry,
            @Schema(description = "담보 내용(정본 25p 데이터 항목 '담보(금액,만기,내용)')") String assureNote,
            @Schema(description = "담보 경고등급(OVER≥100/WARN≥70/WATCH>50/NORMAL/null)") String assureLevel,
            @Schema(description = """
                    담보만기 경고등급(EXPIRED=오늘 기준 이미 만료/IMMINENT=30일 이내 임박/null=여유 있음·만기 없음).

                    ‼️담보비율(assureLevel)과 **별도 축**이다 — 10/27 피드백 §7-C가 "담보비율과 담보만기
                    칸에 각각 색을 칠해 구분한다"고 두 칸을 따로 지목했는데, assureLevel은 비율만 보고
                    만기일(assureExpiry)엔 등급이 없어 프론트가 색을 칠할 근거가 없었다.
                    기준일은 조회기간(toDate)이 아니라 **오늘**이다 — 과거 기간을 조회해도 "지금 임박한지"를 봐야 한다.
                    기존 담보만기 알림(`GET /masters/clients/collateral-expiry`, 기본 30일)과 같은 기준.""")
            String assureExpiryLevel
    ) {
    }

    /** 상품군(대분류) 한 칸. 재무팀 실파일의 「수량(교재)·매출(교재)」 계열이 이것이다. */
    @Schema(name = "ArCategoryBreakdown", description = "외상매출현황 상품군별 수량·금액")
    public record CategoryBreakdown(
            @Schema(description = "대분류 코드(미지정은 null)") MajorCategory majorCategory,
            @Schema(description = "대분류 명칭(모의고사·교재·기타고사·특강·기타·미분류)") String majorName,
            @Schema(description = "매출수량") long saleQty,
            @Schema(description = "매출액(공급가)") long saleAmount,
            @Schema(description = "반품수량") long returnQty,
            @Schema(description = "반품액") long returnAmount
    ) {
    }
}
