package com.daesung.sales.sale.dto;

/**
 * 응시현황 '지역비율' 원자료 — <b>학교별 학년 부수</b>.
 *
 * <p>‼️여기서 말하는 '매출'은 <b>금액이 아니라 부수</b>다. 레거시
 * {@code 응시현황.vb:531} 가 {@code SUM(bookCnt)} 를 쓰고, 금액({@code totalAmt}) 줄은
 * <b>주석으로 막혀</b> 있다. 화면 제목이 '매출'이라 금액으로 읽히지만 부수다.
 *
 * <p>★학년은 <b>도서의 학년</b>({@code products.grade})이다 — 학교 학년이 아니다
 * (레거시도 {@code bookData.grade} 로 1·2·3학년을 나눈다).
 */
public interface AttendanceRatioAgg {
    String getSchoolCode();

    /** 도서 학년 — '1'·'2'·'3'. 그 밖(공란·'공통')은 학년 분해에서 빠진다. */
    String getGrade();

    Long getQty();
}
