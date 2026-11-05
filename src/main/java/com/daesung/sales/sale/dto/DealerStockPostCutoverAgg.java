package com.daesung.sales.sale.dto;

/**
 * [특약점 사이트] 재고현황 매입·반품 자동집계(11/05 회신 §4) — 그 거래처·도서의
 * <b>기준일 이후</b> 매출프로그램 출고/반품 합계. 이관 시작값({@code PartnerProductStock})에
 * 더해 "매입 = 시작값 + 이 값", "반품 = 시작값 + 이 값"을 만든다.
 *
 * <p>★<b>정상출고(NORMAL_SHIP)만 매입으로 본다</b> — 위탁출고(CONSIGN_SHIP)는 매출이 정산
 * 시점에 잡히는 별개 흐름이라 섞으면 특약점 재고현황이 실제 출고보다 먼저/다르게 움직인다.
 * 증정·교사용(GIFT·TEACHER_USE)도 "매입"이 아니다(무상이라 대금 흐름이 다르다).
 *
 * <p>★<b>반품(RETURN)만 보고 취소(CANCEL)는 넣지 않는다</b> — 둘 다 회계구분상 RETURN으로
 * 묶이지만(발주처 2026-09-01 확정), 재고현황의 "반품"이 취소까지 포함하는지는 아직 확인된
 * 적이 없다. 넣어야 한다고 확인되면 이 쿼리의 shipmentType 조건만 넓히면 된다.
 *
 * <p>★<b>「매출만(배송X)」({@code only_sales}, V105)도 뺀다</b> — 출고유형이 정상출고라도
 * 실제로 물건이 나가지 않은 건이다. 프론트가 2026-11-05에 잡아 준 결함이다
 * (처음 쿼리는 shipmentType·canceled·salesDate만 봐서, 교재를 매출만으로 등록하면
 * 나가지도 않은 수량이 특약점 매입에 더해졌다). 11/05 요청서 "위탁·특강·매출만 같은
 * 실출고 아닌 건이 섞이지 않게"의 '매출만'이 바로 이것.
 */
public interface DealerStockPostCutoverAgg {
    Long getProductId();

    long getPurchasedQty();

    long getReturnedQty();
}
