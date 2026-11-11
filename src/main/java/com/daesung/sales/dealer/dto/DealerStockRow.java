package com.daesung.sales.dealer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 특약점 재고현황 한 줄. 근거: 10/31 클라이언트 회신 §3
 * "구매·반품·재고를 수기로 입력하고, 출고는 구매−재고−반품으로 계산해 표시만 한다."
 *
 * <p>‼️<b>그 "수기 입력"의 주체는 본사다</b>(PM 정정 2026-11-05) — 매입·반품은 본사가
 * DSRE [도서신청 주문내역]에서 넣던 값이고, <b>특약점은 재고만</b> 입력한다.
 *
 * <p>★<b>매입·반품 자동집계(11/05 회신 §4)</b> — cutover 기준일이 설정된 뒤로는
 * {@code purchaseQty}·{@code returnQty}가 "이관 시작값 + 기준일 이후 매출프로그램 집계"다.
 * 기준일이 없으면(지금 운영 상태) 그 두 값은 <b>이관된 값에서 멈춘다</b>(본사가 DSRE에서
 * 고쳐도 우리 쪽엔 안 들어온다 — 전환일까지의 공백, 특약점팀 확인 5번).
 * {@code DealerStockService} 가 어느 쪽인지를 가르고, 이 record 는 합산된 결과만 받는다.
 *
 * <p>★<b>출고가 음수인 경우</b>(발주처 2026-11-11) — 특약점이 입력한 재고가 매입−반품보다 크면
 * 출고가 음수로 나온다. 실제로 이관 대상 92행 중 <b>3행</b>이 그렇다(성림도서 D:VOCA −12,
 * 원영도서 수학1·수학2 각 −70). 발주처 지시는 <b>"저장·계산값은 변경하지 않고, 화면에서만
 * 음수를 그대로 보이지 않게 하고 재고를 확인해 달라는 안내로 표시"</b>다.
 * 그래서 {@code shipQty} 는 음수 그대로 두고 {@link #shipQtyNegative}·{@link #shipQtyNotice} 를
 * 함께 내려준다 — <b>화면을 바꾸는 것은 특약점 사이트 몫</b>이고 우리는 판단 근거만 준다.
 */
@Schema(name = "DealerStockRow", description = "특약점 재고현황 — 교재만, 노출되는 도서만")
public record DealerStockRow(
        @Schema(description = "도서코드") String productCode,
        @Schema(description = "도서명") String productName,
        @Schema(description = "분류명") String catName,
        @Schema(description = "매입. cutover 전: 이관값(본사가 DSRE에 넣던 값) / cutover 후: 이관시작값+매출 자동집계")
        int purchaseQty,
        @Schema(description = "반품. cutover 전: 이관값(본사가 DSRE에 넣던 값) / cutover 후: 이관시작값+매출 자동집계")
        int returnQty,
        @Schema(description = "재고. 항상 특약점이 직접 입력한다(cutover 전후 동일)") int stockQty,
        @Schema(description = "출고 = 매입 − 재고 − 반품(서버가 계산, 저장하지 않음)")
        int shipQty,
        @Schema(description = """
                출고가 **음수**인가(= 입력한 재고가 매입−반품보다 많다).
                ‼️`shipQty` 는 **그대로 둔다**(발주처 2026-11-11 "저장·계산값은 변경하지 않습니다").
                화면은 이 값이 true 면 음수를 그대로 보여주지 말고 `shipQtyNotice` 를 대신 띄운다.""",
                example = "false")
        boolean shipQtyNegative,
        @Schema(description = "출고가 음수일 때 화면에 띄울 안내 문구. 음수가 아니면 null(응답에서 빠진다)",
                example = "입력하신 재고가 매입 수량보다 많습니다. 재고를 확인해 주세요.")
        String shipQtyNotice,
        @Schema(description = "재고 최종수정일(특약점이 재고를 마지막으로 저장한 날). "
                + "이관 행은 DSRE EDIT_WEB 값. 없으면 null. "
                + "★이관·자동집계로는 바뀌지 않는다 — 재고를 저장할 때만 바뀐다",
                example = "2026-11-05")
        java.time.LocalDate stockUpdatedOn
) {

    /** 출고 음수일 때 화면에 띄울 안내. 발주처 2026-11-11 "재고를 확인해 달라는 안내". */
    public static final String NEGATIVE_NOTICE =
            "입력하신 재고가 매입 수량보다 많습니다. 재고를 확인해 주세요.";
    /**
     * @param basePurchase  이관 시작값(또는 cutover 전엔 저장된 수기입력값) 매입
     * @param baseReturn    같은 의미의 반품
     * @param stockQty      재고(항상 수기입력)
     * @param addedPurchase cutover 기준일 이후 매출프로그램 자동집계 매입(비활성 시 0)
     * @param addedReturn   같은 의미의 반품(비활성 시 0)
     * @param stockUpdatedOn 재고 최종수정일(없으면 null)
     */
    public static DealerStockRow of(com.daesung.sales.product.entity.Product p,
                                     int basePurchase, int baseReturn, int stockQty,
                                     int addedPurchase, int addedReturn,
                                     java.time.LocalDate stockUpdatedOn) {
        int purchase = basePurchase + addedPurchase;
        int ret = baseReturn + addedReturn;
        int ship = purchase - stockQty - ret;
        boolean negative = ship < 0;
        return new DealerStockRow(p.getCode(), p.getName(), p.getCatName(),
                purchase, ret, stockQty, ship,
                negative, negative ? NEGATIVE_NOTICE : null,
                stockUpdatedOn);
    }
}
