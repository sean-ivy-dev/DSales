package com.daesung.sales.order.dto;

import com.daesung.sales.dsre.gateway.OrderState;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 주문조회 <b>선택 건 상태변경</b>(10/27 명세 §2-8 · 프론트 A-4).
 *
 * <p>§2-8 — "선택지를 <b>접수완료 / 상품검수 / 상품준비중</b>만 남긴다.
 * 발송준비중·발송완료는 물류 영역이므로 <b>[작업요청서]에서만</b> 처리한다."
 */
@Schema(name = "OrderProgressChangeRequest", description = "주문 접수 단계 일괄 전환")
public record OrderProgressChangeRequest(

        @Schema(description = "주문번호 목록(`GET /orders` 의 `orderNo`)",
                example = "[\"E-20261102-7\"]", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<String> orderNos,

        @Schema(description = """
                목표 상태. **RECEIVED(접수완료) · INSPECTING(상품검수) · PREPARING(상품준비중)** 셋만.
                ‼️READY_TO_SHIP·SHIPPED·CANCELED 는 거부한다 — 발송 축은 작업요청서 소관이다(§2-8).""",
                example = "INSPECTING", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull OrderState toState,

        @Schema(description = "변경 사유(선택). 이력에 남는다 — 감사에서 묻는 것은 \"왜\"다",
                example = "검수 요청")
        String reason
) { }
