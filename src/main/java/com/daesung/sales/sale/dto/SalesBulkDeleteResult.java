package com.daesung.sales.sale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 여러 건 삭제 결과. 기존 일괄 API({@code POST /orders/progress-state})와 <b>같은 모양</b>이다 —
 * 화면이 이미 그 모양을 읽고 있어 새 규칙을 만들지 않는다(프론트 확인 2026-11-17 §2).
 *
 * <p>★<b>하나라도 막히면 아무것도 지우지 않는다.</b> 일부만 지워지면
 * 「일괄등록 취소」가 반쯤 남아 더 나쁘다(발주처 권장 · 프론트 찬성).
 */
@Schema(name = "SalesBulkDeleteResult", description = "매출 여러 건 삭제 결과")
public record SalesBulkDeleteResult(

        @Schema(description = "요청한 건수", example = "3") int requested,

        @Schema(description = """
                실제로 지운 건수. ‼️**막힌 건이 하나라도 있으면 0**이다 —
                그때는 아무것도 지우지 않았다는 뜻이고, `blocked`·`results` 를 보여주면 된다.""",
                example = "0")
        int deleted,

        @Schema(description = "막힌 건수", example = "1") int blocked,

        @Schema(description = """
                **막힌 건만** 담는다(전부 통과하면 빈 배열). 건별 사유가 들어 있어
                화면이 「3건 중 1건이 막혔습니다」 + 목록을 띄울 수 있다.""")
        List<Item> results
) {

    /** 막힌 건 하나. */
    @Schema(name = "SalesBulkDeleteItem", description = "막힌 매출 한 건")
    public record Item(
            @Schema(description = "매출 id", example = "12346") Long saleId,
            @Schema(description = "매출번호", example = "I-20261102-123") String salesNo,
            @Schema(description = "막힌 사유(화면에 그대로 띄울 수 있는 문구)")
            String message
    ) { }
}
