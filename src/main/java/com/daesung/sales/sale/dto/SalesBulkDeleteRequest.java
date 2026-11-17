package com.daesung.sales.sale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 통합 매출 조회 <b>여러 건 삭제</b>(발주처 요청 2026-11-17 §2③).
 *
 * <p>용도: 매출일괄등록으로 잘못 넣은 건을 한꺼번에 취소.
 */
@Schema(name = "SalesBulkDeleteRequest", description = "매출 여러 건 삭제")
public record SalesBulkDeleteRequest(

        @Schema(description = "지울 매출 id 목록(화면에서 고른 행)",
                example = "[12345, 12346, 12347]", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty List<Long> saleIds,

        @Schema(description = """
                사유(선택). ‼️**비워 보낼 수 있다** — 단건 삭제도 사유 없이 받는다
                (9/27 회의 A-3, 프론트 확인 2026-11-17 §2-1). 비우면 이력에 '(사유 미입력)'으로 남는다.""",
                example = "일괄등록 오등록 취소")
        @Size(max = 500) String reason
) { }
