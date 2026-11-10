package com.daesung.sales.sale.dto;

import com.daesung.sales.dsre.gateway.DuffChargeMode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 매출일괄등록(더프) 실행 요청 — <b>레거시 방식</b>(목록을 불러와 사람이 고르고 고쳐서 등록).
 *
 * <p>근거: 발주처 확정(§1) "매출일괄등록은 기존 그대로 유지 — 레거시 그대로 재현, 임의 재설계 금지"
 * + 프론트 요청(2026-10-22 3-2).
 * 레거시 {@code 매출가져오기.vb} 는 ①기간으로 목록을 뽑고 ②담당자가 줄을 체크하고
 * ③<b>분류·도서를 콤보박스에서 골라</b> 체크한 줄 전부에 적용한 뒤 ④값을 고쳐 등록한다.
 *
 * <p>‼️예전엔 기간만 받아 <b>전부 자동 매핑해 등록</b>했다. 그래서
 * ·체크한 줄만 넣을 수 없고 ·상품을 고를 수 없어 '상품 없음'으로 빠지는 줄을 살릴 수 없었다.
 *
 * <p>★모든 칸이 선택이다. 아무것도 안 보내면 예전과 같이 동작한다(기존 호출 호환).
 */
@Schema(name = "DuffImportRequest", description = "매출일괄등록(더프) 실행 요청")
public record DuffImportRequest(

        @Schema(description = "신청일 시작(미지정 시 올해 1/1)") LocalDate fromDate,
        @Schema(description = "신청일 종료(미지정 시 오늘)") LocalDate toDate,
        @Schema(description = "조회모드(ALL/PROCESSED/UNPROCESSED/REGISTERED)") DuffChargeMode mode,
        @Schema(description = "발송완료건만", example = "false") Boolean onlyComplete,

        @Schema(description = """
                등록할 줄의 **소스키 목록**. 미리보기 응답의 `sourceKey` 를 그대로 보낸다.
                ★비우면 기간 전체가 대상이다(예전 동작).
                ‼️미리보기와 **같은 기간·모드**를 함께 보내야 한다 — 범위가 다르면
                그 소스키가 목록에 없어 조용히 빠진다.""")
        List<String> sourceKeys,

        @Schema(description = """
                체크한 줄 **전부에 적용할 상품**(도서) id. 레거시의 아래 콤보박스가 이것이다.
                ★비우면 과목코드로 상품을 찾는다(예전 동작) — 못 찾으면 그 줄은 '상품 없음'으로 빠진다.
                DSRE 과목(시행일×영역)과 우리 도서는 1:1이 아니라, 담당자가 고르는 것이 원칙이다.""")
        Long productId,

        @Schema(description = """
                거래일자. **비우면 신청일 기준**으로 줄마다 다르게 들어간다
                (레거시의 '신청일기준' 체크박스와 같다).""")
        LocalDate salesDate,

        @Schema(description = "등록되는 모든 줄에 붙일 공통 메모") String memo,

        @Schema(description = """
                **신규임의등록** — 체크한 줄의 매출코드·학교 대신 **이 거래처**로 등록한다
                (레거시 `CheckBox_Custom` + 아래 특약점 콤보).
                ★비우면 줄의 원래 거래처(DSRE machul_cd 매핑)를 쓴다.
                ★이걸 주면 거래처 매핑이 안 되던 줄도 등록된다(UNMAPPED_PARTNER 가 사라진다) —
                  마스터에 없는 거래처를 담당자가 지정해 살리는 것이 이 기능의 목적이다.""")
        Long partnerId,

        @Schema(description = """
                **신규임의등록의 학교**. `partnerId` 와 함께 쓴다.
                ★`partnerId` 를 주고 이 칸을 **비우면 학교 없이 그 특약점으로** 등록된다
                (프론트 요청 2026-11-10 §1-5 "비우면 학교 없이 특약점").
                ★`partnerId` 가 없으면 이 칸은 무시된다 — 줄의 원래 학교를 쓴다.""")
        String schoolCode,

        @Schema(description = """
                **수기 줄** — 원천(DSRE)에 없는 줄을 손으로 채워 **같은 묶음으로** 함께 등록한다
                (레거시 '항목 추가'). 소스키가 없으므로 중복 검사 대상이 아니다.
                ★상품은 `productId`(위 콤보)를 쓴다 — 레거시도 고른 분류·도서를 모든 줄에 적용한다.""")
        List<ManualLine> manualLines,

        @Schema(description = "줄별 수정값 — 보낸 줄만 바뀐다") List<Override> overrides
) {
    /**
     * 한 줄의 수정값. <b>보낸 칸만</b> 바뀐다(null = 원래 값 유지).
     * 레거시의 '금액수정가능'이 이것이다.
     */
    @Schema(name = "DuffImportOverride", description = "줄별 수정값")
    public record Override(
            @Schema(description = "대상 줄의 소스키", requiredMode = Schema.RequiredMode.REQUIRED)
            String sourceKey,
            @Schema(description = "청구인원") Integer inwon,
            @Schema(description = "정가") Integer unitPrice,
            @Schema(description = "공급률(%)") Integer supplyRate,
            @Schema(description = "할인액") Integer discount,
            @Schema(description = """
                    총금액을 직접 지정. ‼️주면 정가·공급률·인원으로 **계산하지 않고 이 값을 쓴다** —
                    담당자가 끝수를 맞춘 금액이 계산으로 덮이면 안 된다.""")
            Long totalAmount,

            @Schema(description = """
                    **줄별 메모**(프론트 실측 2026-10-23 §4② — "레거시는 공통 메모와 별개로
                    줄마다 메모 칸이 있었다").

                    공통 메모(`memo`)와 **함께** 실린다. 저장되는 모양은
                    `원천 메모 / 줄 메모 / 공통 메모` 순이다 —
                    원천(DSRE)을 지우면 그 줄이 왜 그렇게 들어왔는지 되짚을 수 없다.""")
            String memo,

            @Schema(description = """
                    **세금** 체크(레거시 줄별 '세금' 칸). `true` 면 **세액 = 총금액 ÷ 10**(버림),
                    합계 = 총금액 + 세액으로 저장한다.
                    ★기본은 `false`(세액 0) — 세액은 자동산출하지 않는다는 발주처 확정 그대로다.
                      담당자가 체크한 줄에만 붙는다.
                    ‼️**면세 상품에 체크하면 그 줄을 거부**한다(조용히 무시하면 담당자가
                      값이 사라진 걸 모른다).""")
            Boolean tax
    ) {
    }

    /**
     * 수기 줄 하나(레거시 '항목 추가'로 만든 빈 줄).
     *
     * <p>원천에 없는 줄이라 <b>소스키가 없다</b> — 중복 검사·write-back 대상이 아니다.
     * 상품은 요청의 {@code productId} 를 쓴다.
     */
    @Schema(name = "DuffImportManualLine", description = "수기 줄")
    public record ManualLine(
            @Schema(description = "거래처 id", requiredMode = Schema.RequiredMode.REQUIRED)
            Long partnerId,
            @Schema(description = "학교코드(선택). 비우면 학교 없이 등록") String schoolCode,
            @Schema(description = "인원(수량)", requiredMode = Schema.RequiredMode.REQUIRED)
            Integer inwon,
            @Schema(description = "정가") Integer unitPrice,
            @Schema(description = "공급률(%)") Integer supplyRate,
            @Schema(description = "할인액") Integer discount,
            @Schema(description = "총금액을 직접 지정. 주면 계산하지 않고 이 값을 쓴다")
            Long totalAmount,
            @Schema(description = "세금 체크 — 세액 = 총금액 ÷ 10") Boolean tax,
            @Schema(description = "메모") String memo
    ) {
    }

    /**
     * 소스키 필터가 걸려 있나.
     *
     * <p>‼️<b>`null`(칸 없음)과 `[]`(빈 배열)을 다르게 읽는다</b>(프론트 실측 2026-11-10 §5).
     * <pre>
     *   sourceKeys 없음(null) → 기간 전체가 대상(예전 호출 호환)
     *   sourceKeys = []       → **고른 줄이 없다**. 신청 줄은 하나도 등록하지 않는다
     *   sourceKeys = [...]    → 그 줄만
     * </pre>
     * 예전엔 둘을 같게 봐서, <b>수기 줄만 넣으려고 소스키를 비우면 그 기간 신청이 전부 매출이 됐다.</b>
     * 수백 건이 한 번에 들어가는 사고라, 화면이 "어느 줄과도 안 맞는 가짜 키"를 넣어 피하고 있었다.
     */
    public boolean hasSelection() {
        return sourceKeys != null;
    }

    public boolean onlyCompleteOrFalse() {
        return Boolean.TRUE.equals(onlyComplete);
    }
}
