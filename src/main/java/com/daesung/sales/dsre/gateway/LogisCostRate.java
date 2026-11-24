package com.daesung.sales.dsre.gateway;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 물류단가 1행(DSRE2 tbl_logis_cost). 근거: 레거시 물류비용등록.vb.
 * dtl_cd(시행코드)를 키로 관리. dtl_cd=0은 회수 단가 특수행(PAPER/OMR/ETC만 사용).
 *
 * <p>상품명·시행명은 DSRE2에서 조인해 붙인다. 코드만 보여주면 담당자가 어느 줄을 고쳐야 할지
 * 알 수 없다 — 실제로 값이 같은 행이 여러 개라 화면에서 구분이 안 됐다.
 * 근거: 정본 구분값정리 [10.물류비용등록] "목록 컬럼 | 상품명 / 시행명 | 상품·시행회차 식별 정보".
 */
public record LogisCostRate(
        @Schema(description = "시행코드(DTL_CD, 0=회수단가)") int dtlCd,
        @Schema(description = "상품코드(DSRE PROD_CD)", example = "A0150") String productCode,
        @Schema(description = """
                **상품 연도**(DSRE `tbl_product_info.YEAR`).

                ‼️전엔 이 칸이 없어 화면이 **상품년도로 걸 수 없었다** — 「상품년도 2026」으로
                보고 있는데 2019~2021년 상품이 그대로 보였다(발주처 2026-11-23 3번).
                단가 2,651행이 2019~2026 전 연도이고 **2026년은 229행**뿐이다.""")
        Integer productYear,
        @Schema(description = "상품명", example = "더 프리미엄") String productName,
        @Schema(description = "시행명(회차)", example = "7월 16일(영어)") String detailName,
        @Schema(description = "시험지 단가(OMR외)") int paper,
        @Schema(description = "OMR 단가") int omr,
        @Schema(description = "단행본(ETC) 단가") int etc,
        @Schema(description = "라벨 단가") int label,
        @Schema(description = "인별 기본작업비") int basic,
        @Schema(description = "인별 배송비(출고비)") int trade,
        @Schema(description = "포장구분(1:반별봉투 2:개별봉투 3:예외SET)") int packtype,
        @Schema(description = "여분포함 여부(Y/N)") String bSpare,
        @Schema(description = "변경일(단가 변경 이력)") java.time.LocalDateTime changedAt
) {
}
