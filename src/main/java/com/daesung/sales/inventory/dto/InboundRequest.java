package com.daesung.sales.inventory.dto;

import com.daesung.sales.inventory.entity.InboundType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;

/** 일반 입고 요청. 인쇄소 등(거래처) → 도착 창고로 입고. */
public record InboundRequest(

        @Schema(description = "처리일자", example = "2026-06-08", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalDate processedDate,

        @Schema(description = """
                입고 거래처(인쇄소·매입처 등) id. **선택이다** — 비워도 등록된다.
                근거: 현장 피드백 2026-10-14 「입고 거래처는 필수 아님 — 선택 없어도 등록되도록」.
                비우면 조회·수불부의 매입처 칸이 빈칸으로 나간다.""",
                example = "1")
        Long supplierClientId,

        @Schema(description = "도착 창고 id", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long destinationWarehouseId,

        @Schema(description = """
                입고구분. 미지정 시 NORMAL.
                · `NORMAL` 정상입고(인쇄소 등 자체 제작·조달)
                · `PURCHASE` 매입입고 — **16p 순매출조회 매입액에 반영**
                · `RETURN` 반품입고 — 거래처에서 되돌아온 물량. **매입액에는 안 들어간다**
                  (반품으로 들어온 건 사 온 게 아니다).
                  ‼️매출 반품 라인까지 함께 만들려면 `POST /sales/return-inbound`를 쓴다 —
                  여기서는 재고만 늘고 매출은 건드리지 않는다.
                · `PURCHASE_RETURN` 매입반품 — 사 온 물건을 공급처로 되돌려 보냄(발주처 2026-10-02 #6).
                  ★**수량은 양수로 입력**하고 서버가 음수로 저장한다 — 재고가 실제로 준다.
                  16p 순매출조회의 **매입반품 칸**으로 집계되며, **매입액·이익에는 들어가지 않는다**
                  (매입액은 "판 물건을 얼마에 사 왔나"고 매입반품은 재고 흐름이다).""",
                example = "NORMAL")
        InboundType inboundType,

        @Schema(description = "물류작업비 대상 여부(8p). 이 입고분을 물류 작업비 청구 대상으로 볼지",
                example = "false")
        Boolean logisCostTarget,

        @Schema(description = "입고 품목 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Valid List<InboundItem> items
) {
    /** 입고 품목. */
    public record InboundItem(

            @Schema(description = "상품 id", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Long productId,

            @Schema(description = """
                    입고단가(원가, 원).
                    ★**미입력이고 `purchaseRate` 를 주면 `정가 × 매입률/100` 으로 채운다**
                    (레거시 식 그대로). 직접 주면 그 값이 이긴다.""", example = "3500")
            Long unitCost,

            @Schema(description = """
                    ★**매입률 %**(F-13, 매입입고 전용). 매입단가 = **정가 × 매입률/100**.

                    발주처 요청: "입고구분 「매입입고」 선택 시 **매입률 입력칸 추가**".
                    레거시 입고행 33,221건에 실재하는 값이다(85%·75%·50%·60%·80% …).

                    ‼️**입고 건별**이다 — 도서 마스터에 기본값을 두지 않는다. 같은 도서가 같은 해에도
                    매입률 3종을 갖는 경우가 있어, 기본값을 두면 담당자가 넣은 값과 어긋난다.
                    ‼️**매입입고(`PURCHASE`)가 아닌데 주면 400** 이다 — 조용히 무시하면
                    담당자는 값이 사라진 걸 모른다(세액을 면세 상품에 넣을 때와 같은 규칙).""",
                    example = "55")
            @jakarta.validation.constraints.Positive Integer purchaseRate,

            @Schema(description = "입고수량(양수)", example = "1000", requiredMode = Schema.RequiredMode.REQUIRED)
            @Positive int qty,

            @Schema(description = "비고", example = "초도 인쇄 물량")
            String memo
    ) {
    }
}
