package com.daesung.sales.dashboard.dto;

import com.daesung.sales.dashboard.entity.SalesTarget;
import com.daesung.sales.dashboard.entity.TargetEntryType;
import com.daesung.sales.dashboard.entity.TargetScope;
import io.swagger.v3.oas.annotations.media.Schema;

/** 등록된 매출목표(또는 과거연도 확정 실적) 1건. */
@Schema(name = "TargetResponse", description = "매출목표·실적 항목")
public record TargetResponse(
        @Schema(description = "id") Long id,
        @Schema(description = "연도") int year,
        @Schema(description = "월(null이면 연간)") Integer month,
        @Schema(description = "대상 축") TargetScope scope,
        @Schema(description = "사업부문명(scope=DIVISION)") String scopeKey,
        @Schema(description = "상품 id(scope=PRODUCT)") Long productId,
        @Schema(description = """
                상품코드·상품명(scope=PRODUCT). ‼️예전엔 **id 만** 내려가서, 목표 관리 화면이
                "어느 상품의 목표인지" 보여주려면 상품마다 따로 조회해야 했다(F-25).""")
        String productCode,
        String productName,
        @Schema(description = "TARGET(목표)/ACTUAL(확정 실적)") TargetEntryType entryType,
        @Schema(description = "금액") long amount
) {
    public static TargetResponse from(SalesTarget t) {
        return from(t, null, null);
    }

    /**
     * 상품코드·명을 함께 담는다. <b>상품은 호출부가 묶어 읽어 넘긴다</b> —
     * 여기서 조회하면 목표 한 줄마다 상품 쿼리가 돌아 목록 한 장에 수십 번이 된다.
     */
    public static TargetResponse from(SalesTarget t, String productCode, String productName) {
        return new TargetResponse(t.getId(), t.getFiscalYear(), t.getMonth(), t.getScope(),
                t.getScopeKey(), t.getProductId(), productCode, productName,
                t.getEntryType(), t.getTargetAmount());
    }
}
