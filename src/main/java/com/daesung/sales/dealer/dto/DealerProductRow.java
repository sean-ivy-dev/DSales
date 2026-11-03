package com.daesung.sales.dealer.dto;

import com.daesung.sales.product.entity.Product;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 특약점 사이트 신청 화면에 노출할 도서 한 줄. 근거: 10/26 §3 + 특약점팀 전달(2026-11-03)
 * "칸: productCode · salesDivision · catName · name · isSet.
 * <b>공급률·정가·Web게시 값은 빼 주세요</b>".
 *
 * <p>★금액 관련 칸을 일부러 안 담는다 — 10/26 §1 "매출 금액은 매출프로그램 기준으로
 * 계산한다". 사이트가 단가를 들고 있으면 두 곳에서 금액이 계산되기 시작한다.
 * Web게시 값 자체도 안 담는다(§3 "Web게시여부 값은 사이트에 보여주지 않는다") —
 * 노출 판정은 이 목록에 <b>있는지 없는지</b>로 이미 전달된다.
 */
@Schema(name = "DealerProductRow", description = "특약점 신청 화면 노출 도서")
public record DealerProductRow(
        @Schema(description = "도서코드", example = "A0176001") String productCode,
        @Schema(description = "도서명(SET 또는 회차 단위)", example = "THE FULL 사회탐구 2회")
        String name,
        @Schema(description = "세부구분 코드", example = "교재") String salesDivision,
        @Schema(description = "분류명", example = "THE FULL 사회탐구") String catName,
        @Schema(description = "세트 여부 — true면 SET 상품") boolean isSet
) {
    public static DealerProductRow from(Product p) {
        return new DealerProductRow(p.getCode(), p.getName(), p.getSalesDivision(),
                p.getCatName(), p.isSet());
    }
}
