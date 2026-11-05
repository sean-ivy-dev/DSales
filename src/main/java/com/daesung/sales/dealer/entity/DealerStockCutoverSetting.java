package com.daesung.sales.dealer.entity;

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
 * 재고현황 매입·반품 자동집계 전환일(11/05 회신 §4) — 싱글톤 행(id=1).
 *
 * <p>★<b>환경변수가 아니라 DB 값이다.</b> 월마감 {@code period_locks}·권한 매트릭스
 * {@code menu_screen}/{@code role_screen_permission} 과 같은 선택 — 발주처가 전환일을
 * 정하는 순간 관리자 API로 바로 바뀌어야 하고, 그걸 바꾸려고 GitHub Actions 변수를
 * 등록하고 재배포하는 건 느리고 이 값의 성격과도 안 맞는다(V117 참고).
 */
@Entity
@Table(name = "dealer_stock_cutover_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DealerStockCutoverSetting extends BaseEntity {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "cutover_date")
    private LocalDate cutoverDate;

    public void setCutoverDate(LocalDate cutoverDate) {
        this.cutoverDate = cutoverDate;
    }

    public boolean autoAggregationEnabled() {
        return cutoverDate != null;
    }
}
