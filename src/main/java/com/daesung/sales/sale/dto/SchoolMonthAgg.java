package com.daesung.sales.sale.dto;

/** 응시현황(연도별) 원자료 — 매출 쪽. 학교×월 부수·금액. */
public interface SchoolMonthAgg {
    String getSchoolCode();

    Integer getMonth();

    Long getQty();

    Long getAmount();
}
