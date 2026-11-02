package com.daesung.sales.sale.dto;

import com.daesung.sales.salestype.entity.SalesCategory;
import com.daesung.sales.salestype.entity.ShipmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 매출 등록 결과. 품목별 생성된 매출번호와 금액. */
public record SalesEntryResponse(

        @Schema(description = "거래처 id", example = "1")
        Long partnerId,

        @Schema(description = "거래처명", example = "리브커넥스(주)")
        String partnerName,

        @Schema(description = """
                등록 묶음번호(E-yyyyMMdd-n) — **이 한 번의 등록**을 가리킨다.
                주문을 통째로 고치거나 지울 때 이 값으로 부른다
                (`PUT`/`DELETE /sales/entries/{entryNo}`, 프론트 B-4③).""")
        String entryNo,

        @Schema(description = """
                거래순번 — 그 거래처의 그 날 **몇 번째 주문**인가(1부터, 10/27 명세 §3-6).
                등록 직후 화면의 순번 칸에 그대로 보여 주면 된다.""")
        Integer tradeSeq,

        @Schema(description = "등록된 매출 라인")
        List<Line> items,

        @Schema(description = """
                경고. **등록은 됐지만 확인이 필요한 것**을 담는다 — 비어 있으면 이상 없음.
                화면은 이 배열이 비어 있지 않으면 alert을 띄워야 한다.
                근거: 발주처 화면검토(2026-08-31) 화면28 — "출고내역보다 반품 등록 내역이
                더 많이 입력되는 경우 **경고 알림(alert)**을 넣어주시기 바랍니다".""")
        List<Warning> warnings
) {
    /**
     * 경고 한 건. <b>실패가 아니다</b> — 저장은 됐고 담당자가 봐야 할 사실이 있을 뿐이다.
     * 조용히 통과시키면 담당자는 자기가 초과 입력한 줄 모른다.
     */
    @Schema(name = "SalesEntryWarning")
    public record Warning(
            @Schema(description = "경고 코드", example = "RETURN_EXCEEDS") String code,
            @Schema(description = "상품 id") Long productId,
            @Schema(description = "상품코드") String productCode,
            @Schema(description = "반품가능수량(확정매출 − 기반품)") long returnableQty,
            @Schema(description = "요청 수량") int requestedQty,
            @Schema(description = "초과 수량") long exceededQty,
            @Schema(description = "화면에 그대로 띄울 수 있는 문구") String message
    ) {
        /**
         * 재고 경고를 같은 모양으로 옮긴다.
         *
         * <p>★경고를 두 배열로 나눠 주면 화면이 둘 다 읽어야 하고, 한쪽을 빠뜨리면 조용히 묻힌다.
         * 출처가 달라도 담당자에게는 "확인할 것" 하나다 — 그래서 한 배열에 합친다.
         * 반품 관련 칸({@code returnableQty}·{@code exceededQty})은 재고 경고엔 없어 0으로 둔다.
         */
        public static Warning ofStock(com.daesung.sales.inventory.dto.StockWarning w) {
            return new Warning(w.code(), w.productId(), w.productCode(),
                    0L, -w.delta(), 0L, w.message());
        }
    }

    @Schema(name = "SalesEntryLine")
    public record Line(

            @Schema(description = "매출번호(I)", example = "I-20260622-1001")
            String salesNo,

            @Schema(description = "상품 id", example = "1")
            Long productId,

            @Schema(description = "상품코드", example = "S2026A02")
            String productCode,

            @Schema(description = "출고유형", example = "NORMAL_SHIP")
            ShipmentType shipmentType,

            @Schema(description = "회계구분", example = "SALE")
            SalesCategory salesCategory,

            @Schema(description = "수량", example = "100")
            int qty,

            @Schema(description = "공급가액", example = "1500000")
            long supplyAmount,

            @Schema(description = "세액", example = "150000")
            long tax,

            @Schema(description = "총금액", example = "1650000")
            long totalAmount,

            @Schema(description = "출고 후 물류창고 재고 잔량", example = "900")
            int stockBalance
    ) {
    }
}
