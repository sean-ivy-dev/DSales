package com.daesung.sales.inventory.dto;

import com.daesung.sales.inventory.entity.InboundType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 입고 결과. 품목별 입고수량 + 갱신된 현재 잔량. */
public record InboundResponse(

        @Schema(description = "입고 전표번호(IN-yyyyMMdd-n). 취소할 때 이 번호로 지정한다",
                example = "IN-20260917-1")
        String inboundNo,

        @Schema(description = "도착 창고 id", example = "1")
        Long warehouseId,

        @Schema(description = "도착 창고명", example = "본사 메인 물류창고")
        String warehouseName,

        @Schema(description = "입고구분 NORMAL/PURCHASE", example = "NORMAL")
        InboundType inboundType,

        @Schema(description = "입고 결과 품목")
        List<Line> items,

        @Schema(description = """
                경고 — **막지 않고 알리기만 한다**. 비어 있으면 이상 없음.
                `PERIOD_LOCKED` 는 마감된 달에 등록한 경우다(발주처 2026-10-23: 차단 안 함, 안내만).
                ‼️전표 단위 경고라 상품·창고 칸이 비어 있다 — 코드로 갈라 읽을 것.""")
        java.util.List<StockWarning> warnings
) {
    @Schema(name = "InboundLine")
    public record Line(

            @Schema(description = "상품 id", example = "1")
            Long productId,

            @Schema(description = "상품코드", example = "KOR-A02-1")
            String productCode,

            @Schema(description = "이번 입고 수량", example = "1000")
            int inboundQty,

            @Schema(description = "입고 후 현재 잔량", example = "1000")
            int currentQty,

            @Schema(description = "매입률 %(F-13). 매입입고·매입반품에만 값이 있다", example = "55")
            Integer purchaseRate,

            @Schema(description = """
                    확정된 입고단가(원). 직접 넣었으면 그 값, 비우고 매입률만 줬으면
                    **정가 × 매입률/100** 으로 서버가 만든 값이다 — 화면이 되돌려 보여줄 수 있게 돌려준다.""",
                    example = "6600")
            Long unitCost
    ) {
    }
}
