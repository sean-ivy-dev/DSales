package com.daesung.sales.logistics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 물류비용등록(33p) 좌측 상품 트리 — <b>연도 &gt; 분류 &gt; 상품</b> 3단.
 *
 * <p>정본이 "좌측 트리뷰 상품 선택에 연도 구분 추가"를 요구하는데 서버가 트리 데이터를
 * 준 적이 없었다(F-38). 연도는 <b>분류코드 2~5번째</b>에서 뽑는다 —
 * 레거시 {@code order by substring(catCode,2,4) desc} 와 같은 규칙이다.
 *
 * <p>3단인 이유: 상품이 14,811개다. 연도 21 → 분류 889 → 상품으로 접지 않으면 화면에서 못 쓴다.
 */
@Schema(name = "ProductTreeNode", description = "상품 트리 — 연도 묶음")
public record ProductTreeNode(

        @Schema(description = "연도. 분류코드에서 못 뽑으면 null(맨 뒤)", example = "2026") Integer year,
        @Schema(description = "이 연도의 상품 수") int productCount,
        @Schema(description = "분류 목록(분류코드 순)") List<CatNode> categories
) {

    @Schema(name = "ProductTreeCatNode", description = "분류 묶음")
    public record CatNode(
            @Schema(description = "분류코드", example = "S2026A01") String catCode,
            @Schema(description = "분류명") String catName,
            @Schema(description = "상품 목록(상품코드 순)") List<Leaf> products
    ) {
    }

    @Schema(name = "ProductTreeLeaf", description = "상품")
    public record Leaf(
            @Schema(description = "상품 id") Long productId,
            @Schema(description = "상품코드") String code,
            @Schema(description = "상품명") String name,
            @Schema(description = "물류단가가 등록돼 있는지 — false면 트리에서 골라 새로 등록한다")
            boolean hasRate
    ) {
    }
}
