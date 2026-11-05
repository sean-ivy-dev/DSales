package com.daesung.sales.dealer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** null로 보내면 전환을 끈다(이관값에서 멈추는 상태로 되돌림) — 명시적 비움 허용. */
@Schema(name = "DealerStockCutoverSettingUpdateRequest", description = "재고현황 매입·반품 자동집계 전환일 설정 변경")
public record DealerStockCutoverSettingUpdateRequest(
        @Schema(description = "전환일. null이면 전환을 끈다", example = "2026-12-01")
        LocalDate cutoverDate
) {
}
