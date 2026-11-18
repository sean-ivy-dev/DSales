package com.daesung.sales.closing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/** 입력 가능 최소 거래일 설정. */
@Schema(description = "입력 가능 최소 거래일 — 이 날짜 이전 거래일로는 등록·날짜변경 불가")
public record EntryDateFloorResponse(
        @Schema(description = "최소 거래일. null이면 제한 없음", example = "2026-12-01")
        LocalDate floorDate,
        @Schema(description = "제한이 걸려 있는지", example = "true")
        boolean enabled,
        @Schema(description = "기준일 대비 막히는 과거 일수(기준일=오늘). 제한 없으면 null",
                example = "17")
        Long blockedDays) {
}
