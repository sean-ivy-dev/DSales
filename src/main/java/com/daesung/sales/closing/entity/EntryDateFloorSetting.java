package com.daesung.sales.closing.entity;

import com.daesung.sales.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 입력 가능 최소 거래일 — 싱글톤 행(id=1). 근거: 「매출 마무리」 §2-3 ㄱ.
 *
 * <p>이 날짜 <b>이전</b>의 거래일로는 매출·수금을 <b>새로 등록하거나 날짜를 그쪽으로
 * 바꿀 수 없다</b>. 12/10~11 이관이 그 기간을 담당하므로, 그 사이에 손으로 넣으면
 * 같은 거래가 두 번 남는다(V132 참고).
 *
 * <p>‼️<b>이미 있는 자료의 취소·삭제·수정은 막지 않는다.</b> 막는 것은 "그 날짜로
 * 새로 집어넣는 것"뿐이다 — 이관분(1월~11월)을 고치지 못하게 하면 잘못 들어간 자료를
 * 바로잡을 길이 없어진다.
 *
 * <p>비어 있으면(null) 제한이 없다 — 기본값이 그것이라, 올려도 아무 동작이 바뀌지 않는다.
 */
@Entity
@Table(name = "entry_date_floor_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EntryDateFloorSetting extends BaseEntity {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "floor_date")
    private LocalDate floorDate;

    public void setFloorDate(LocalDate floorDate) {
        this.floorDate = floorDate;
    }

    /** 그 거래일이 막히는가. 설정이 비어 있으면 늘 false(제한 없음). */
    public boolean blocks(LocalDate date) {
        return floorDate != null && date != null && date.isBefore(floorDate);
    }
}
