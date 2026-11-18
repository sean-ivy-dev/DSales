package com.daesung.sales.closing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** 입력 가능 최소 거래일 변경. null을 보내면 제한을 푼다. */
public record EntryDateFloorUpdateRequest(
        @Schema(description = "최소 거래일. null로 보내면 제한 해제", example = "2026-12-01")
        LocalDate floorDate) {
}
