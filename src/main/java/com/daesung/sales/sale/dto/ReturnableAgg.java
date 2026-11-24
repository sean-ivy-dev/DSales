package com.daesung.sales.sale.dto;

/**
 * 교재식 반품의 "반품 가능내역" 집계(거래처×도서×정가×공급률 단위).
 * 근거: 레거시 교재 반품 — 기존 출고내역(공급률·공급수량)을 먼저 조회 후 그 범위 내에서 차감.
 * 반품가능수량 = 누적 판매출고(SALE) − 기(旣)반품(RETURN). 취소건 제외.
 */
public interface ReturnableAgg {
    Long getProductId();

    String getProductCode();

    String getProductName();

    /** 분류코드 — 발주처 2026-11-23 2번. */
    String getCatCode();

    /** 분류명 — 도서명만으로는 같은 이름이 여럿이라 가릴 수 없다. */
    String getCatName();

    Integer getUnitPrice();

    Integer getSupplyRate();

    long getSaleQty();

    long getReturnQty();

    /**
     * 이 조건(정가·공급률)으로 나간 <b>출고 매출번호들</b>. 쉼표로 이어 붙인 문자열이다.
     *
     * <p>★반품 등록({@code POST /sales/return-inbound})이 {@code sourceOutNo}를 요구하는데
     * 정작 이 조회가 안 내려줘서 담당자가 채울 값이 없었다(프론트 실측 2026-09-18).
     *
     * <p>한 조건에 출고가 여럿일 수 있어 목록이다 — 같은 도서를 같은 공급률로 여러 번 내보내면
     * 전부 같은 줄로 묶이기 때문이다. 화면은 이 중에서 고르게 한다.
     */
    String getSourceSalesNos();
}
