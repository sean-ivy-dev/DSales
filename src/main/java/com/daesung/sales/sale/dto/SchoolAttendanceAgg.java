package com.daesung.sales.sale.dto;

/**
 * 응시현황(연도별) 원자료 — 학교 마스터 쪽. 거래처·지역·상세지역·학년별 재학생 수.
 *
 * <p>★<b>매출이 없는 학교도 들어온다.</b> 레거시({@code 응시현황.vb:333})가
 * {@code schAllData FULL OUTER JOIN schData} 로 학교 전체를 만들고 매출을
 * {@code LEFT OUTER JOIN} 으로 붙이기 때문이다 — "안 판 학교"를 보는 것이 이 화면의 목적이다.
 */
public interface SchoolAttendanceAgg {
    String getSchoolCode();

    String getSchoolName();

    String getCustCode();

    String getCustName();

    String getCity();

    /** 상세지역(레거시 {@code schData.custLoc}). */
    String getPartnerLoc();

    Boolean getIsSchool();

    Integer getCls1StudentCnt();

    Integer getCls2StudentCnt();

    Integer getCls3StudentCnt();
}
