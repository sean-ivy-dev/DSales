package com.daesung.sales.dsre.gateway;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 물류 회수 작업비 <b>한 행</b>. 근거: 프론트 요청(2026-09-18) B-4 —
 * "회수는 기간 총계 하나뿐이라 출고처럼 행 명세로 볼 수 없습니다".
 *
 * <p>★<b>총계와 같은 규칙으로 금액을 낸다.</b> 자재구분으로 단가를 고르고
 * (OMR→OMR / 단행본·책자→ETC / 라벨→LABEL / 그 외→PAPER), 회수단가는
 * {@code tbl_logis_cost} 의 특수행({@code DTL_CD=0}) 최신값을 쓴다 —
 * 총계 쿼리와 한 글자도 다르지 않아야 <b>행 합 = 총계</b>가 성립한다.
 *
 * <p>★★<b>상품·시행·거래처를 함께 낸다</b>(발주처 2026-11-23 4번 8쪽 — "회수전체 화면도
 * 출고전체처럼 좌측 시행 트리 + 상품·시행·거래처별 소계"). 예전엔 회수일·자재·구분·수량·
 * 단가·금액 여섯 칸뿐이라 트리도 소계도 붙일 수 없었다.
 *
 * <p>근거는 DSRE 표에 그대로 있다(복제본 실측 2026-11-24, <b>매칭률 100%</b>):
 * <ul>
 *   <li><b>반품</b>({@code tbl_wol_dtl_b})은 {@code CUST_CD}·{@code DTL_CD} 를 <b>직접</b> 갖는다
 *       — 24,410행 전부 시행·거래처가 맞는다.</li>
 *   <li><b>사고</b>({@code tbl_wol_dtl})는 {@code REQ_CD}(신청번호)만 있어
 *       {@code tbl_request_info} 로 거래처·시행을 가져온다 — 4,308행 전부 맞는다.</li>
 * </ul>
 */
@Schema(name = "ReturnCostDetailRow", description = "회수 작업비 명세 1행")
public record ReturnCostDetailRow(
        @Schema(description = "회수일자") LocalDate returnDate,
        @Schema(description = "자재코드") int materialCode,
        @Schema(description = "자재명") String materialName,
        @Schema(description = "자재구분(OMR·단행본·책자·라벨·그 외=시험지)") String materialType,
        @Schema(description = "회수 구분 ACCIDENT(사고)/NORMAL(반품)") String mode,
        @Schema(description = "상품코드(분류)") String productCode,
        @Schema(description = "상품명(분류명)") String productName,
        @Schema(description = "학년") String grade,
        @Schema(description = "시행코드") int examCode,
        @Schema(description = "시행명") String examName,
        @Schema(description = "거래처코드") String partnerCode,
        @Schema(description = """
                거래처명 — **CUST_FNM(지사 풀네임)** 이다. 출고 명세와 같은 칸을 쓴다
                (레거시 `물류비계산2.vb` 가 `max(CUST_FNM)` 으로 쓴다 — 상호(CUST_NM)를
                쓰면 화면에 짧은 이름이 나간다).""")
        String partnerName,
        @Schema(description = "지역(특약지역명)") String region,
        @Schema(description = "수량") int qty,
        @Schema(description = "적용 단가") int unitCost,
        @Schema(description = "금액(수량×단가)") long amount
) {
}
