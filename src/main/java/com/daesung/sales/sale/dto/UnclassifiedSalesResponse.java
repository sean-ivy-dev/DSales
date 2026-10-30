package com.daesung.sales.sale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 미분류 매출 — 상품별 목록. 근거: 10/27 검수 피드백 §7-C 하단 5번
 * "데이터 이관 때 <b>매핑되지 않는 누락 건은 반드시 목록으로 뽑아 확인</b>한다".
 *
 * <p>대분류는 {@code 매출 → 상품.세부구분 → 세부구분 마스터.대분류} 로 이어진다.
 * 이 사슬이 끊긴 매출은 외상매출현황·매출액정리의 상품군 칸 어디에도 안 들어가
 * '미분류'로 남는다. 이 목록이 <b>그 끊긴 자리를 상품 단위로</b> 보여 준다.
 *
 * @param totalAmount 기간 전체 매출액(미분류 포함). 비율을 내려면 분모가 필요하다
 * @param amount      그중 미분류 매출액
 * @param ratio       미분류 비율(%) — 한눈에 "얼마나 심한가"를 본다
 */
@Schema(name = "UnclassifiedSalesResponse", description = "미분류 매출 상품 목록(이관 검증용)")
public record UnclassifiedSalesResponse(
        @Schema(description = "집계 시작일") LocalDate fromDate,
        @Schema(description = "집계 종료일") LocalDate toDate,
        @Schema(description = "기간 전체 매출액") long totalAmount,
        @Schema(description = "미분류 매출액") long amount,
        @Schema(description = "미분류 비율(%)") double ratio,
        @Schema(description = "미분류 상품 — 매출액 큰 순") List<Row> rows
) {
    @Schema(name = "UnclassifiedSalesRow")
    public record Row(
            @Schema(description = "상품 id") Long productId,
            @Schema(description = "도서코드") String productCode,
            @Schema(description = "도서명") String productName,
            @Schema(description = "분류코드") String catCode,
            @Schema(description = "분류명") String catName,
            @Schema(description = "상품에 들어 있는 세부구분 값(비어 있으면 빈 문자열)")
            String salesDivision,
            @Schema(description = """
                    끊긴 이유 —
                    `EMPTY`(상품에 세부구분이 비어 있다. 이관 때 안 채워진 경우) /
                    `UNKNOWN`(세부구분 값은 있는데 세부구분 마스터에 없다. 오타·폐지된 값).

                    ★고치는 방법이 다르다. `EMPTY`는 **도서관리에서 세부구분을 채우면** 되고,
                    `UNKNOWN`은 그 값을 **세부구분 마스터에 추가하거나** 상품 쪽을 고쳐야 한다.""")
            String reason,
            @Schema(description = "기간 매출수량") long saleQty,
            @Schema(description = "기간 매출액(공급가)") long saleAmount,
            @Schema(description = "마지막 매출일 — 지금도 쓰는 상품인지 가늠한다") LocalDate lastSalesDate
    ) {
    }
}
