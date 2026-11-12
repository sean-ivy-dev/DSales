package com.daesung.sales.product.dto;

import com.daesung.sales.product.entity.ContentType;
import com.daesung.sales.product.entity.MajorCategory;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.entity.SalesDivision;

/** 상품 응답 DTO. */
public record ProductResponse(
        Long id,
        String code,
        String name,
        ContentType contentType,
        boolean set,
        Integer price,
        boolean taxFree,
        String grade,

        String gradeName,   // 학년 표기(3 → 3학년). 저장값은 숫자뿐이라 서버가 붙여 준다
        String catCode,
        String catName,
        boolean useYn,
        /** 세부구분 코드(구 '매출구분'). 저장되는 값은 이것 하나뿐이다. */
        String salesDivision,
        /** 세부구분 명칭. 마스터에서 따라온다. */
        String salesDivisionName,
        /** 대분류 — 세부구분 마스터에서 <b>파생</b>. 상품에 따로 저장하지 않는다(매핑은 한 곳에만). */
        MajorCategory majorCategory,
        /** 대분류 명칭(모의고사·교재 …). 프론트가 매핑표를 들고 있지 않게 같이 준다. */
        String majorCategoryName,
        Integer productYear,
        String productType,
        Integer supplyRate,
        boolean ledgerVisible,

        boolean priceVisible,
        boolean webVisible,
        /** 지정 거래처 전용(화이트리스트). true면 열기로 지정한 거래처에만 노출(11/04 회신 §1). */
        boolean restrictedVisible,
        boolean stockManaged,
        /** 특약점 재고현황 교재 — true면 모든 특약점 재고현황에 보인다(PM 2026-11-12). Web게시와 별개 축. */
        boolean dealerStockListed,

        @io.swagger.v3.oas.annotations.media.Schema(description = """
                마지막 변경 시각 — **수정 시각, 없으면 등록 시각**이다.
                `updatedSince` 에 지난번 받은 값 중 가장 큰 것을 그대로 넣으면 그 뒤 바뀐 것만 온다
                (프론트 요청 2026-10-20 B-1).
                ‼️<b>비지 않는다.</b> 예전엔 한 번도 안 고친 도서가 비어 있어서, 전부 그런 상태면
                  화면이 **기준 자체를 못 잡았다**(프론트 실측 2026-10-21).
                  서버가 거르는 기준과 같은 값(`coalesce(updatedAt, createdAt)`)을 그대로 돌려준다.""")
        java.time.LocalDateTime updatedAt,
        @io.swagger.v3.oas.annotations.media.Schema(description = """
                수정 버전 — **수정 요청에 그대로 되돌려 보내면** 다른 사람이 먼저 고친 것을
                덮어쓰지 않는다(어긋나면 409). 안 보내면 예전처럼 그냥 덮어쓴다.""")
        Long version
) {
    /**
     * @param division 이 상품의 세부구분 마스터. null이면 미지정이거나 마스터에 없는 값 →
     *                 대분류는 '미분류'(null)로 나간다. 조용히 '기타'로 바꾸지 않는다 —
     *                 바꿔버리면 매핑이 빠진 상품을 담당자가 영영 못 찾는다.
     */
    public static ProductResponse from(Product p, SalesDivision division) {
        MajorCategory major = (division == null) ? null : division.getMajorCategory();
        return new ProductResponse(
                p.getId(), p.getCode(), p.getName(), p.getContentType(),
                p.isSet(), p.getPrice(), p.isTaxFree(), p.getGrade(), com.daesung.sales.common.code.MasterCodes.gradeName(p.getGrade()),
                p.getCatCode(), p.getCatName(), p.isUseYn(),
                p.getSalesDivision(),
                (division == null) ? null : division.getName(),
                major,
                (major == null) ? null : major.label(),
                p.getProductYear(), p.getProductType(), p.getSupplyRate(),
                p.isLedgerVisible(), p.isPriceVisible(), p.isWebVisible(), p.isRestrictedVisible(),
                p.isStockManaged(), p.isDealerStockListed(),
                // ‼️서버가 `updatedSince` 로 거르는 기준과 **같은 값**을 준다 —
                //   다르면 화면이 만든 기준으로 다시 물었을 때 같은 건이 또 오거나 빠진다.
                (p.getUpdatedAt() != null) ? p.getUpdatedAt() : p.getCreatedAt(),
                p.getVersion());
    }
}
