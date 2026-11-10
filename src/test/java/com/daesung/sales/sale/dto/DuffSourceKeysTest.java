package com.daesung.sales.sale.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 매출일괄등록 — <b>`sourceKeys` 가 없는 것(null)과 빈 배열([])은 다르다</b>
 * (프론트 실측 2026-11-10 §5).
 *
 * <p>‼️예전엔 둘을 같게 봐서, <b>수기 줄만 넣으려고 소스키를 비우면 그 기간 신청이 전부 매출이 됐다.</b>
 * 수백 건이 한 번에 들어가는 사고라, 화면이 "어느 줄과도 안 맞는 가짜 키"를 하나 실어 피하고 있었다.
 *
 * <p>‼️<b>null 을 '전체'로 두는 것은 유지한다</b> — 기간만 주고 호출하는 예전 경로가 있다.
 * 그 둘을 가르는 것이 이 테스트다.
 *
 * <p>⚠️등록 경로 전체는 DSRE 연동이 켜져야 돌아가(@ConditionalOnProperty) 통합테스트로 덮을 수 없다.
 * 판정부만 여기서 고정한다.
 */
@DisplayName("더프 일괄등록 — sourceKeys null ≠ [] (§5)")
class DuffSourceKeysTest {

    private static DuffImportRequest req(List<String> keys) {
        return new DuffImportRequest(null, null, null, null, keys, null, null, null,
                null, null, null, null);
    }

    @Test
    @DisplayName("칸이 없으면(null) 기간 전체 — 예전 호출 호환")
    void null은_전체() {
        assertThat(req(null).hasSelection()).isFalse();
    }

    @Test
    @DisplayName("‼️빈 배열([])은 **고른 줄이 없다** — 기간 전체가 아니다")
    void 빈배열은_없음() {
        assertThat(req(List.of()).hasSelection())
                .as("전엔 false 라 기간 전체가 등록됐다 — 수백 건이 한 번에 들어가는 사고다")
                .isTrue();
    }

    @Test
    @DisplayName("값이 있으면 그 줄만")
    void 값있으면_그줄만() {
        assertThat(req(List.of("1:A:01:M")).hasSelection()).isTrue();
    }
}
