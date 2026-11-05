package com.daesung.sales.dealer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(name = "DealerStockCutoverSettingResponse", description = "재고현황 매입·반품 자동집계 전환일 설정")
public record DealerStockCutoverSettingResponse(
        @Schema(description = "전환일. null이면 아직 전환 전 — 매입·반품은 이관값에서 멈춘다(본사가 DSRE에 넣는 값이라 우리 쪽에선 바뀌지 않는다)")
        LocalDate cutoverDate,
        @Schema(description = "자동집계 활성 여부 — cutoverDate가 있는가")
        boolean autoAggregationEnabled
) {
}
