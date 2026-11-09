package com.daesung.sales.sale.dto;

import com.daesung.sales.product.entity.MajorCategory;
import java.time.LocalDate;

/**
 * 발송 단위 <b>대분류</b> 집계 — 작업결과(26p)의 '분류' 컬럼·필터용(F-31).
 *
 * <p>★<b>왜 발송의 {@code tradeClass} 문자열을 쓰지 않는가.</b> 그 칸에 축이 섞여 있다
 * (2026년 운영 실측 2,229건):
 * <pre>
 *   기타고사  1,120  ← 대분류 라벨
 *   대성교재    833  ← ‼️**법인명**. 대분류도 세부구분도 아니다
 *   교재        268  ← 대분류 라벨
 *   D모의고사     7  ← 세부구분명
 *   기타          1  ← 대분류 라벨
 * </pre>
 * 문자열 매핑만으로는 <b>833건(37%)이 미지정</b>이 된다. 그래서 발송에 묶인 매출의
 * <b>상품 → 세부구분 → 대분류</b>로 파생한다.
 *
 * <p>묶음 키는 {@link ShipmentQtyAgg}와 <b>같다</b>(일자·거래처·학교·분류·등록번호) —
 * 발송↔매출 매칭 규칙을 다시 짜지 않는다.
 */
public interface ShipmentMajorAgg {

    LocalDate getTradeDate();

    Long getPartnerId();

    String getSchoolCode();

    String getTradeClass();

    String getEntryNo();

    /** 그 발송에 섞인 대분류 하나. 한 발송에 여럿이면 행이 여러 개 나온다. */
    MajorCategory getMajorCategory();

    /** 그 대분류의 수량 — 여럿일 때 **많은 쪽을 앞세우는 데** 쓴다. */
    Long getQty();
}
