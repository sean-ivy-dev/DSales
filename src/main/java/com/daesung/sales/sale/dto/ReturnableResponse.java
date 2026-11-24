package com.daesung.sales.sale.dto;


import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 교재식 반품 가능내역 조회 응답. 거래처의 <b>도서 단위</b> 누적 매출·반품 내역.
 *
 * <p>★<b>행은 도서 하나에 하나다.</b> 출고조건(정가·공급률)별로 갈라 보고 싶으면
 * 각 행의 {@code conditions}를 본다 — 화면이 "출고조건별로 나뉜 내역이 없다"고
 * 비워 두고 있어 2026-09-16에 실어 줬다. <b>판정 단위는 그대로 도서다.</b> 예전에는 도서×정가×공급률 단위로 쪼개져 있었고
 * 이 주석도 그렇게 적혀 있었는데(2026-08-18 지적), 판정을 도서 단위로 바꾸면서
 * 서비스가 {@code byProduct}로 합산해 내보낸다 — <b>설명만 옛 구조로 남아 있었다.</b>
 * 같은 도서를 공급률 달리 여러 번 내보냈어도 한 줄로 합쳐진다.
 *
 * <p>반품가능수량의 바탕은 <b>확정매출(SALE) 수량</b>이다 — 출고수량이 아니다.
 * 위탁출고는 정산 전까지 매출이 아니므로 미결분은 여기 잡히지 않는다(정산돼야 반품 대상이 된다).
 *
 * <p>정가·공급률은 <b>원 출고건 값을 표시해 주기 위한 참고값</b>이며 반품 등록 시 수정할 수 있다
 * (발주처 확정 2026-08-05: "공급률은 원 출고건 값을 기준으로 표시하되 담당자가 필요 시 수정 가능").
 * 반품 범위 판정은 <b>도서 단위 합계</b>로 한다.
 */
public record ReturnableResponse(Long partnerId, List<Row> rows) {

    @Schema(name = "ReturnableRow")

    public record Row(
            Long productId,
            @Schema(description = "분류코드") String catCode,
            @Schema(description = """
                    분류명 — 반품 등록 화면이 **도서명 옆에 함께** 보여 준다
                    (발주처 2026-11-23 2번: "도서명만 보여 도서 구분이 안 됨").""")
            String catName,
            String productCode,
            String productName,
            Integer unitPrice,
            Integer supplyRate,
            @Schema(description = "누적 확정매출 수량(SALE). 위탁 미결분은 아직 매출이 아니라 포함되지 않는다")
            long saleQty,
            @Schema(description = "기(旣)반품 수량(RETURN)")
            long returnedQty,
            @Schema(description = "반품가능수량 = 확정매출 − 기반품")
            long returnableQty,

            @Schema(description = """
                    이 도서의 **출고조건(정가·공급률)별 내역**. 같은 도서를 공급률 달리 여러 번
                    내보냈으면 여기서 갈라진다.

                    <p>★<b>참고 내역이지 판정 단위가 아니다.</b> 반품 가능 여부는 위의
                    도서 단위 `returnableQty`로 판정한다(발주처 확정 2026-08-05 —
                    "반품 범위 판정은 도서 단위 합계"). 그래서 조건별 '반품가능수량'은 일부러 두지 않는다 —
                    두면 화면이 조건마다 입력을 막게 되고, 그건 확정과 다른 동작이다.""")
            List<Condition> conditions
    ) {
    }

    @Schema(name = "ReturnableCondition", description = "출고조건(정가·공급률)별 누적 내역")
    public record Condition(
            @Schema(description = "정가(원)") Integer unitPrice,
            @Schema(description = "공급률(%)") Integer supplyRate,
            @Schema(description = "이 조건으로 확정매출된 수량") long saleQty,
            @Schema(description = "이 조건으로 반품된 수량") long returnedQty,

            @Schema(description = """
                    이 조건으로 나간 **원출고 매출번호 목록**.

                    ★반품 등록(`POST /sales/return-inbound`)이 `sourceOutNo`를 받는데
                    정작 이 조회가 안 내려줘서 담당자가 채울 값이 없었다(2026-09-18 지적).
                    한 조건에 출고가 여럿일 수 있어 목록이다 — 같은 도서를 같은 공급률로
                    여러 번 내보내면 전부 같은 줄로 묶이기 때문이다. 화면은 여기서 고르게 한다.""")
            List<String> sourceSalesNos
    ) {
    }
}
