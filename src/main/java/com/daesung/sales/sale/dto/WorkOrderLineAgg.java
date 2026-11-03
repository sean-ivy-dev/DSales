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

    /** 분류명 — 사이트 화면 표시가 「세부구분 / 분류명 / 도서명」(특약점팀 요청 2026-11-03 §4). */
    String getCatName();

    /** 세부구분(도서 마스터 salesDivision). */
    String getSalesDivision();

    String getProductCode();

    String getProductName();

    Integer getBookRound();

    Integer getUnitPrice();

    Integer getSupplyRate();

    Long getQty();

    Long getAmount();
}
