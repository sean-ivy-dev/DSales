package com.daesung.sales.dsre.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 찍은 시각을 <b>한국 시각으로</b> 바꾸는 보정 — 두 사본 DB 의 타임존이 다르다.
 *
 * <p>‼️실측(2026-11-25): 운영 사본 DB 는 <b>KST</b>, 시험 사본 DB 는 <b>UTC</b> 로 돈다.
 * 같은 날 아침에 찍은 두 사본이 {@code refreshedAt} 09:58 ↔ 01:53 으로
 * <b>9시간 차이</b>처럼 보였고, 화면이 그대로 찍어 한쪽이 9시간 이르게 나왔다.
 *
 * <p>★보정은 서버 한 곳에서 한다. 이 테스트가 그 계산을 못박는다 —
 * 컨테이너가 필요 없는 순수 계산이라 통합테스트로 두지 않는다.
 */
@DisplayName("DSRE 사본 찍은 시각 → 한국 시각 보정(2026-11-25)")
class DsreSnapshotKstTest {

    /** 사본 DB 가 UTC 로 돌면 9시간을 더해 보여야 한다. */
    @Test
    @DisplayName("★UTC 로 도는 사본 — 01:53 을 **10:53** 으로 보여준다")
    void utc_사본은_9시간_더한다() {
        LocalDateTime dbNow = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime refreshedAt = LocalDateTime.of(2026, 11, 24, 1, 53, 59);

        assertThat(JdbcDsreGateway.toKst(refreshedAt, dbNow))
                .as("‼️그대로 찍으면 화면에 9시간 이르게 나온다")
                .isEqualTo(LocalDateTime.of(2026, 11, 24, 10, 53, 59));
    }

    /** 사본 DB 가 이미 KST 면 **건드리지 않는다**. */
    @Test
    @DisplayName("★KST 로 도는 사본 — 09:58 은 **그대로**")
    void kst_사본은_그대로() {
        LocalDateTime dbNow = LocalDateTime.now(ZoneOffset.UTC).plusHours(9);
        LocalDateTime refreshedAt = LocalDateTime.of(2026, 11, 24, 9, 58, 44);

        assertThat(JdbcDsreGateway.toKst(refreshedAt, dbNow))
                .as("‼️여기서 9시간을 또 더하면 미래 시각이 된다")
                .isEqualTo(refreshedAt);
    }

    /**
     * 망 왕복·시계 오차는 <b>걷어낸다</b> — 타임존 치우침은 전부 15분의 배수다.
     *
     * <p>★이걸 안 하면 몇 초 지연이 그대로 남아 「10:53:57」처럼 들쭉날쭉 보인다.
     */
    @Test
    @DisplayName("‼️몇 초 지연은 **걷어낸다** — 15분 단위로 맞춘다")
    void 초단위_오차는_무시한다() {
        LocalDateTime dbNow = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(40);
        LocalDateTime refreshedAt = LocalDateTime.of(2026, 11, 24, 1, 53, 59);

        assertThat(JdbcDsreGateway.toKst(refreshedAt, dbNow))
                .isEqualTo(LocalDateTime.of(2026, 11, 24, 10, 53, 59));
    }

    /** 모르면 <b>null</b> — 「모른다」를 그럴듯한 시각으로 꾸미지 않는다. */
    @Test
    @DisplayName("찍은 시각이나 사본 시계를 모르면 null")
    void 모르면_null() {
        assertThat(JdbcDsreGateway.toKst(null, LocalDateTime.now(ZoneOffset.UTC))).isNull();
        assertThat(JdbcDsreGateway.toKst(LocalDateTime.now(), null)).isNull();
    }
}
