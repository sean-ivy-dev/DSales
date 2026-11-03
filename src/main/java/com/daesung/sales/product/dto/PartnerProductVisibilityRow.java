package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * [거래처관리] 거래처 상세 → '도서별 노출' 화면 한 줄. 근거: 10/31 회신 §1-4.
 * "분류명 > 도서명 트리. 각 도서의 체크박스는 1-1 규칙으로 계산한 현재 노출 여부를 보여준다
 * … 예외로 저장된 도서는 구분 표시(예: '예외' 배지)한다."
 */
@Schema(name = "PartnerProductVisibilityRow", description = "거래처×도서 노출 — 계산된 현재값 + 예외 여부")
public record PartnerProductVisibilityRow(
        @Schema(description = "도서 id") Long productId,
        @Schema(description = "도서코드") String productCode,
        @Schema(description = "도서명") String productName,
        @Schema(description = "분류코드") String catCode,
        @Schema(description = "분류명") String catName,
        @Schema(description = "1-1 규칙으로 계산한 현재 노출 여부(①②③④ 적용 결과)")
        boolean visible,
        @Schema(description = """
                이 거래처에 예외 행이 있는지 — 화면의 '예외' 배지. true면 visible은 그 예외값,
                false면 거래처×대분류 매핑(③④)에서 그대로 떨어진 기본값이다.""")
        boolean exception,
        @Schema(description = "도서 자체 Web게시 — N이면 체크박스를 비활성화한다(①이 예외보다 우선)")
        boolean productWebVisible
) {
}
