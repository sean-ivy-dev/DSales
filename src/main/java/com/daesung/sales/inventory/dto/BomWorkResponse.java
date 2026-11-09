package com.daesung.sales.inventory.dto;

import com.daesung.sales.inventory.entity.BomDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** BOM 작업 결과. 완제품/구성품별 증감(delta)과 작업 후 잔량(balance). */
public record BomWorkResponse(
        @Schema(description = "전표번호(BW-). 되돌릴 때 이 번호로 가리킨다") String workNo,
        @Schema(description = "작업 창고 id") Long warehouseId,
        @Schema(description = "작업 창고명") String warehouseName,
        @Schema(description = "방향") BomDirection direction,
        @Schema(description = "완제품 결과") Line parent,
        @Schema(description = "작업수량(완제품 기준)") int workQty,
        @Schema(description = "구성품 결과") List<Line> components,
        @Schema(description = "재고 경고(차단 아님). 음수가 되면 담긴다") java.util.List<StockWarning> warnings
) {
    @Schema(name = "BomWorkLine")
    public record Line(
            @Schema(description = """
                    상품 id. ‼️**자재 줄은 null** 이다 — 자재 매칭이 있는 세트는
                    구성이 상품이 아니라 **자재**이므로 `materialId` 를 본다(F-37).""")
            Long productId,
            @Schema(description = "‼️자재 줄의 자재 id. 상품 줄은 null") Long materialId,
            @Schema(description = "상품코드 — **자재 줄은 자재코드**") String productCode,
            @Schema(description = "상품명 — **자재 줄은 자재명**") String productName,
            @Schema(description = """
                    완제품 1개에 몇 개 들어가는가.
                    · 상품 구성 → BOM 비율
                    · **자재 구성 → 세트당 소요수량**(공통 자재는 회차 수가 이미 반영된 값)""")
            Integer ratio,
            @Schema(description = "자재구분(구성 줄만) EXAM_PAPER/ANSWER_SHEET/OMR/LABEL/ETC") String materialType,
            @Schema(description = "증감(+/-)") int delta,
            @Schema(description = "작업 후 잔량") int balance
    ) {
    }
}
