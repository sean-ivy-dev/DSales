package com.daesung.sales.sale.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 매출 엑셀 업로드 결과. 근거: 레거시 매출가져오기.vb(salesData 일괄 INSERT, 재고 미반영).
 * 행별 처리결과(등록/오류/경고) + 요약.
 *
 * <p>★<b>경고는 등록을 막지 않는다</b>(10/27 피드백 §4-15 "수량·공급률·금액이 계산과 맞지
 * 않아도 예외가 있을 수 있다. 알럿만 주고 등록은 허용한다"). 반품 초과 경고와 같은 원칙이다 —
 * 담당자가 아는 예외를 시스템이 막아서는 안 되지만, 모르고 지나가게 둬서도 안 된다.
 *
 * @param warned 경고와 함께 등록된 행 수(= result 가 {@code IMPORTED_WITH_WARNING} 인 줄).
 *               {@code imported} 에 <b>포함</b>된다 — 등록은 됐기 때문이다.
 */
public record SalesUploadResponse(int imported, int failed, int warned, List<Line> lines) {

    @Schema(name = "SalesUploadLine")

    public record Line(
            int row,             // 엑셀 행번호(1-base)
            @Schema(description = """
                    `IMPORTED`(등록) / `IMPORTED_WITH_WARNING`(경고와 함께 등록) / `ERROR`(거부).

                    ‼️`IMPORTED_WITH_WARNING` 도 **등록된 줄**이다. 화면은 이 줄을 노란색 등으로
                    구분해 보여 주되, 실패로 세지 않는다.""")
            String result,       // IMPORTED / IMPORTED_WITH_WARNING / ERROR
            String salesNo,      // 등록 성공 시 매출번호
            String custCode,
            String productCode,
            int qty,
            long supplyAmount,
            @Schema(description = "오류 사유(ERROR) 또는 경고 문구(IMPORTED_WITH_WARNING)")
            String message
    ) {
    }
}
