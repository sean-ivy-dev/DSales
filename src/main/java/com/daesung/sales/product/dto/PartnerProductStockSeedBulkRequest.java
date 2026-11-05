package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 재고현황 이관 베이스라인 일괄 시딩 요청. */
@Schema(name = "PartnerProductStockSeedBulkRequest", description = "재고현황 이관 베이스라인 일괄 시딩")
public record PartnerProductStockSeedBulkRequest(
        @Schema(description = "거래처×도서 시딩 건 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Size(max = 500, message = "한 번에 500건까지 처리할 수 있습니다.")
        @Valid List<PartnerProductStockSeedRequest> items
) {
}
