package com.daesung.sales.logistics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.Map;

/**
 * 작업결과 1행 = 발송 건 하나. 레거시 작업결과.vb 컬럼 구성을 따랐다.
 *
 * <p>'출력'·'완료'는 레거시가 날짜 유무로 판단해 {@code ○}를 찍는다 —
 * 별도 상태 컬럼이 없다(정본이 "매출프로그램은 날짜플래그"라고 한 것이 이 화면이다).
 */
@Schema(name = "WorkResultRow", description = "작업결과 1행(발송 건)")
public record WorkResultRow(
        @Schema(description = "발송 건 id") Long id,
        @Schema(description = "분류", example = "IC") String tradeClass,
        @Schema(description = "거래일자") LocalDate tradeDate,
        @Schema(description = "거래순번") int tradeSeq,
        @Schema(description = "거래처코드") String partnerCode,
        @Schema(description = "거래처명") String partnerName,
        @Schema(description = "학교코드") String schoolCode,
        @Schema(description = "학교명") String schoolName,
        @Schema(description = "출력 여부(작업요청서 출력됨)") boolean printed,
        @Schema(description = "확인 여부(출력 다음 단계). 우리가 신설한 축이다") boolean acknowledged,
        @Schema(description = "확인 처리자") String acknowledgedBy,
        @Schema(description = """
                ★**진행상태 6단계** — 발주처 확정(2026-10-23 #17) "한 컬럼 6단계,
                완료·삭제 컬럼은 진행상태로 통합".
                값: `PREPARING`(상품준비중) · `READY_TO_SHIP`(발송준비중) ·
                `SHIPPED`(발송완료) · `CANCELED`(삭제).
                ‼️`RECEIVED`(접수완료)·`INSPECTING`(상품검수)는 **여기서 나오지 않는다** —
                DSRE2 소관(주문 접수 단계)이고, 작업결과는 출고 작업 목록이라
                매출·출고가 선 뒤에야 행이 생긴다.""")
        com.daesung.sales.dsre.gateway.OrderState status,
        @Schema(description = "진행상태 한글 표기 — 화면이 매핑표를 들고 있지 않도록 서버가 준다")
        String statusLabel,
        @Schema(description = """
                **DSRE 진행상태**(`tbl_request_info.STATE`). 위 `status` 와 **다른 축**이다.

                · `status`  = 우리 기록(삭제일·발송일·**작업요청서 출력시각**)으로 판정
                · `dsreStatus` = DSRE2 가 들고 있는 값

                ‼️**둘은 어긋날 수 있고, 그게 정상이다.** DSRE 의 발송준비중(W)은
                거래명세서 발급뿐 아니라 **DSRE2 상자계산 저장**에서도 생긴다 —
                주체가 둘이라 우리 기준을 무엇으로 바꿔도 완전히 맞출 수 없다.
                그래서 하나로 합치지 않고 **나란히 내려 어긋난 건이 보이게** 한다.

                DSRE 연동이 꺼져 있거나 묶인 신청이 없으면(수기 매출) **null**.""")
        com.daesung.sales.dsre.gateway.OrderState dsreStatus,
        @Schema(description = "DSRE 진행상태 한글 표기. 값이 없으면 null")
        String dsreStatusLabel,
        @Schema(description = """
                삭제 여부. `status=CANCELED` 와 같은 값이다 —
                진행상태로 통합됐지만, 줄 색칠처럼 따로 쓰는 곳이 있어 함께 둔다.""")
        boolean deleted,
        @Schema(description = """
                완료 여부. ⚠️**판정에 쓰지 말 것.** 이관분 46,218건이 가짜 1900-01-01 로 채워져
                사실상 전건 참이었고(V97 에서 비웠다), 레거시에서 실제로 쓰인 건 13건뿐이다.
                진행상태는 `status` 를 볼 것.""")
        boolean completed,
        @Schema(description = "발송일") LocalDate sentDate,
        @Schema(description = "출고창고(여러 창고에서 나갔으면 쉼표로 이어 붙인다). 창고 기록 전 매출은 비어 있다")
        String warehouseName,
        @Schema(description = "상품군별 수량(예: 교재 40, IC 20)") Map<String, Long> quantities,
        @Schema(description = "총 수량") long totalQty,
        @Schema(description = "박스 수") int boxCount,
        @Schema(description = "발송메모") String sendMemo,
        @Schema(description = "비고") String memo
) {
}
