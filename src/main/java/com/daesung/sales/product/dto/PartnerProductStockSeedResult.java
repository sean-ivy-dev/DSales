package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 재고현황 이관 베이스라인 일괄 시딩 결과. 한 행이 틀려도 나머지는 그대로 적용된다
 * (엑셀 일괄과 같은 원칙 — 한 건 때문에 전체를 버리지 않는다).
 */
@Schema(name = "PartnerProductStockSeedResult", description = "재고현황 이관 베이스라인 일괄 시딩 결과")
public record PartnerProductStockSeedResult(
        @Schema(description = "요청 건수") int requested,
        @Schema(description = "성공 건수") int succeeded,
        @Schema(description = "실패 건 — 거래처코드·도서코드·사유") List<Failure> failures
) {
    @Schema(name = "PartnerProductStockSeedFailure", description = "시딩 실패 한 건")
    public record Failure(String partnerCode, String productCode, String reason) {
    }
}
