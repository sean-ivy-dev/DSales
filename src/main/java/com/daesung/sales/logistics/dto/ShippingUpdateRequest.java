package com.daesung.sales.logistics.dto;

import com.daesung.sales.logistics.entity.CourierCompany;
import com.daesung.sales.logistics.entity.DeliveryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;

/**
 * 물류 발송정보 입력. 근거: 작업요청서.vb:915
 * {@code UPDATE sendData SET BoxCount=?, sendDate=?, sendMemo=? WHERE idx=?}.
 *
 * <p>지정하지 않은 항목은 건드리지 않는다 — 박스 수만 고치려다 발송일이 지워지면 안 된다.
 */
@Schema(name = "ShippingUpdateRequest", description = "발송정보 입력(박스 수·발송일·발송메모·발송구분·수령인·송장)")
public record ShippingUpdateRequest(
        @Schema(description = "박스 수", example = "3") @PositiveOrZero Integer boxCount,
        @Schema(description = "발송일", example = "2026-06-30") LocalDate sentDate,
        @Schema(description = "발송메모", example = "착불") String sendMemo,

        @Schema(description = """
                발송구분 COURIER(택배)/FREIGHT(화물). 26p 확정 항목.
                **기본값은 없다** — 정본이 "자동 기본값이 있는지"를 미해결로 남겨,
                고르지 않은 건을 임의로 한쪽에 넣지 않는다.""", example = "COURIER")
        DeliveryType deliveryType,

        @Schema(description = "수령인(택배일 때 담당자 정보)", example = "홍길동") String receiverName,
        @Schema(description = "수령인 연락처", example = "010-0000-0000") String receiverPhone,
        @Schema(description = "수령인 이메일", example = "teacher@example.com") String receiverEmail,
        @Schema(description = """
                택배사. CJ(CJ대한통운) / DAESIN(대신택배). 미지정이면 기존 값을 유지한다.
                기본값은 **화면이** CJ대한통운으로 미리 선택해 보낸다(10/31 §2).""",
                example = "CJ")
        CourierCompany courierName,
        @Schema(description = "송장번호", example = "123456789012") String trackingNo
) {
}
