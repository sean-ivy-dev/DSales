package com.daesung.sales.sale.dto;

import java.time.LocalDate;

/** 발송 단위 상품군별 수량 집계(작업결과의 교재·IC 컬럼). */
public interface ShipmentQtyAgg {
    LocalDate getTradeDate();

    Long getPartnerId();

    String getSchoolCode();

    String getTradeClass();

    /** 주문 식별자(매출등록번호). 발송 묶음 키에 들어간다 — 옛 데이터는 빈 문자열. */
    String getEntryNo();

    Long getQty();
}
