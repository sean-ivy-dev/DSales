package com.daesung.sales.sale.dto;

import java.time.LocalDate;

/** 발송 단위 도서별 상세(작업요청서 지시 목록). */
public interface WorkOrderLineAgg {
    LocalDate getTradeDate();

    Long getPartnerId();

    String getSchoolCode();

    String getTradeClass();

    /** 주문 식별자(매출등록번호). 발송 묶음 키에 들어간다 — 옛 데이터는 빈 문자열. */
    String getEntryNo();

    String getCatCode();

    String getProductCode();

    String getProductName();

    Integer getBookRound();

    Integer getUnitPrice();

    Integer getSupplyRate();

    Long getQty();

    Long getAmount();
}
