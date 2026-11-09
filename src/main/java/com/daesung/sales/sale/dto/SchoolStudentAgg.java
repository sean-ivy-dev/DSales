package com.daesung.sales.sale.dto;

/** 응시현황 '지역비율' 원자료 — 학교의 거래처·도시·학년별 재학생 수. */
public interface SchoolStudentAgg {
    String getSchoolCode();

    String getCustCode();

    String getCity();

    Integer getCls1StudentCnt();

    Integer getCls2StudentCnt();

    Integer getCls3StudentCnt();
}
