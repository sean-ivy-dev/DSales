package com.daesung.sales.dsre.gateway;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 물류 작업비 계산(27p) 좌측 '시행 선택' 트리 — <b>연도 &gt; 상품 &gt; 시행</b>.
 *
 * <p>정본 F-32: "좌측 트리뷰 상품 선택에 <b>연도 구분</b> 추가(29장 레거시: 2026년/2025년… 트리)".
 * 필터({@code dtlCds})는 이미 있었고 <b>트리 데이터를 주는 경로가 없었다</b>.
 *
 * <p>‼️기존 {@code GET /orders/exams} 로는 쓸 수 없다 — 그쪽은 <b>주문 등록용</b>이라
 * {@code SALE_DT >= 오늘} 로 <b>판매가 끝난 시행을 버린다</b>. 물류 작업비는 과거 연도를
 * 들여다보는 화면이라 끝난 시행이 전부 필요하다.
 *
 * <p>연도 축은 DSRE2 {@code tbl_product_info.YEAR}(상품 년도)다. 복제본 실측 2019~2026,
 * 상품 212 · 시행 2,576건(0.21초).
 */
@Schema(name = "ExamTreeNode", description = "시행 트리 — 연도 묶음")
public record ExamTreeNode(

        @Schema(description = "상품 연도(DSRE tbl_product_info.YEAR)", example = "2026") Integer year,
        @Schema(description = "이 연도의 시행 수") int examCount,
        @Schema(description = "상품 목록(상품코드 순)") List<ProductNode> products
) {

    @Schema(name = "ExamTreeProductNode", description = "상품 묶음")
    public record ProductNode(
            @Schema(description = "상품코드(PROD_CD)", example = "A0150") String prodCode,
            @Schema(description = "상품명", example = "더 프리미엄") String prodName,
            @Schema(description = "시행 목록(학년 → 시행코드 순)") List<Leaf> exams
    ) {
    }

    @Schema(name = "ExamTreeLeaf", description = "시행")
    public record Leaf(
            @Schema(description = "시행코드(DTL_CD) — 물류단가·물류비 명세가 키로 쓰는 그 값")
            int dtlCd,
            @Schema(description = "시행명", example = "5월 20일") String dtlName,
            @Schema(description = "학년") String grade,
            @Schema(description = "성적처리 구분(Y/N)") String procYn,
            @Schema(description = "판매종료일(yyyyMMdd). **지난 시행도 내려온다**") String saleEndDate
    ) {
    }
}
