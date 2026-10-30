package com.daesung.sales.logistics.dto;

import com.daesung.sales.logistics.entity.DeliveryType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 작업요청서 1건 = 발송 건 + 그 안에 담을 도서 목록.
 *
 * <p>작업결과가 "다 됐나"를 보는 요약이라면, 작업요청서는 물류에게 <b>"무엇을 몇 개 넣어라"</b>를
 * 지시하는 목록이다(레거시 작업요청서.vb가 도서코드·수량·정가·공급률까지 뽑는다).
 */
@Schema(name = "WorkOrderResponse", description = "작업요청서(발송 건 + 도서 지시 목록)")
public record WorkOrderResponse(
        @Schema(description = "발송 건 id") Long id,
        @Schema(description = "분류") String tradeClass,
        @Schema(description = "거래일자") LocalDate tradeDate,
        @Schema(description = """
                거래순번 — 그 거래처의 그 날 **몇 번째 주문**인가(10/27 명세 §3-6·§8-2, 1부터).
                한 주문이 학교·분류 때문에 발송 여러 건으로 갈려도 **번호는 하나**다.""")
        int tradeSeq,
        @Schema(description = "거래처코드") String partnerCode,
        @Schema(description = "거래처명") String partnerName,
        @Schema(description = "학교코드") String schoolCode,
        @Schema(description = "학교명") String schoolName,
        @Schema(description = """
                삭제 여부(9/27 회의 항목 20 ②). **목록에서 지우지 않고 표시만 한다** —
                레거시가 취소선+회색으로 남긴다(작업요청서.vb:768). 숨기면 '취소된 건'과
                '원래 없던 건'이 구분되지 않아 담당자가 같은 발송을 다시 만든다.
                ‼️출력·확인·발송 처리에서는 빠진다.""")
        boolean deleted,

        @Schema(description = """
                이 발송 건에 묶인 **신청번호(DSRE reqCd)** 목록(2026-10-01 추가).
                발송완료·발송취소(`POST /orders/state`)가 신청번호로 동작해서, 이게 없으면
                작업요청서 화면에서 상태를 바꿀 수 없었다.
                ★같은 날 같은 학교로 두 주문이 들어오면 물류는 한 번에 싸서 보내므로 **여럿일 수 있다**.
                주문을 거치지 않고 수기로 등록한 매출만 있는 발송 건은 **빈 목록**이다.""")
        List<Integer> reqCds,

        @Schema(description = "출력 여부") boolean printed,
        @Schema(description = "확인 여부(출력 다음 단계). ⚠️레거시 '완료'와는 다른 축이다") boolean acknowledged,
        @Schema(description = "확인 처리자") String acknowledgedBy,
        @Schema(description = "박스 수") int boxCount,
        @Schema(description = "발송일") LocalDate sentDate,
        @Schema(description = "발송메모") String sendMemo,
        @Schema(description = "발송구분 코드(COURIER/FREIGHT). 미지정이면 null") DeliveryType deliveryType,
        @Schema(description = "발송구분 명칭(택배/화물)") String deliveryTypeName,
        @Schema(description = "수령인 — 정본 26p \"'택배' 선택 시 담당자 정보가 노출\"") String receiverName,
        @Schema(description = "수령인 연락처") String receiverPhone,
        @Schema(description = "택배사") String courierName,
        @Schema(description = "송장번호") String trackingNo,
        @Schema(description = "총 수량") long totalQty,
        @Schema(description = "도서별 지시 목록") List<Line> lines
) {
    @Schema(name = "WorkOrderLine")
    public record Line(
            @Schema(description = "분류코드") String catCode,
            @Schema(description = "도서코드") String productCode,
            @Schema(description = "도서명") String productName,
            @Schema(description = "회차") Integer bookRound,
            @Schema(description = "정가") Integer unitPrice,
            @Schema(description = "공급률(%)") Integer supplyRate,
            @Schema(description = "수량") long qty,
            @Schema(description = "금액(공급가)") long amount
    ) {
    }
}
