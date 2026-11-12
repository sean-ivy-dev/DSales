package com.daesung.sales.master;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.entity.PartnerType;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 거래처 만료의 <b>출처별 규칙</b>(발주처 지적 2026-11-12).
 *
 * <p>만료가 들어오는 길이 둘이다 —
 * ① 이 프로그램에서 [거래 종료](레거시 이관 458곳 포함) ② DSRE 동기화({@code END_GUBUN='Y'}).
 *
 * <p>★<b>이 테스트가 지키는 것</b>: DSRE 가 `N` 으로 돌아와도 <b>담당자가 직접 끝낸 거래처는
 * 되살아나지 않는다.</b> 출처를 구분하지 않으면 DSRE 쪽 사정으로 우리가 끝낸 거래처가
 * 목록에 다시 나타난다.
 */
@DisplayName("거래처 만료 — 출처(MANUAL/DSRE)별 규칙")
class PartnerExpiryRuleTest {

    private static final LocalDate 종료일 = LocalDate.of(2024, 4, 3);
    private static final LocalDate 동기화일 = LocalDate.of(2026, 11, 12);

    private static Partner 거래처() {
        return Partner.create("A10A122", "동작일신도서", PartnerType.NORMAL);
    }

    @Test
    @DisplayName("DSRE 만료를 걸면 만료일은 '처음 Y 를 본 날' — DSRE 에 만료일 칸이 없다")
    void dsre가_만료를_건다() {
        Partner p = 거래처();
        assertThat(p.isExpired()).isFalse();

        assertThat(p.markDsreExpired(동기화일)).isTrue();
        assertThat(p.isExpired()).isTrue();
        assertThat(p.getEndDate()).isEqualTo(동기화일);
    }

    @Test
    @DisplayName("‼️이미 만료된 거래처는 날짜를 덮지 않는다 — 돌릴 때마다 날짜가 밀리면 안 된다")
    void 재실행해도_날짜가_안밀린다() {
        Partner p = 거래처();
        p.discontinue(종료일);

        assertThat(p.markDsreExpired(동기화일))
                .as("이미 만료라 새로 거는 게 아니다").isFalse();
        assertThat(p.getEndDate())
                .as("담당자가 끝낸 날짜가 살아 있어야 한다").isEqualTo(종료일);
    }

    @Test
    @DisplayName("★★DSRE 가 N 으로 돌아와도 **담당자가 끝낸 거래처는 안 풀린다**")
    void 수기종료는_동기화가_못푼다() {
        Partner p = 거래처();
        p.discontinue(종료일);

        assertThat(p.clearDsreExpiry())
                .as("‼️여기서 true 면 우리가 끝낸 거래처가 DSRE 사정으로 되살아난다").isFalse();
        assertThat(p.isExpired()).isTrue();
        assertThat(p.getEndDate()).isEqualTo(종료일);
    }

    @Test
    @DisplayName("DSRE 가 건 만료는 N 으로 돌아오면 풀린다")
    void dsre가_건것은_풀린다() {
        Partner p = 거래처();
        p.markDsreExpired(동기화일);

        assertThat(p.clearDsreExpiry()).isTrue();
        assertThat(p.isExpired()).isFalse();
        assertThat(p.getEndDate()).isNull();
    }

    @Test
    @DisplayName("만료가 아닌 거래처에 해제를 불러도 아무 일도 없다")
    void 만료아니면_해제는_무해() {
        Partner p = 거래처();
        assertThat(p.clearDsreExpiry()).isFalse();
        assertThat(p.isExpired()).isFalse();
    }

    @Test
    @DisplayName("사용 재개는 출처와 무관하게 푼다 — 담당자가 화면에서 직접 하는 조작이다")
    void 사용재개는_출처무관() {
        Partner p = 거래처();
        p.markDsreExpired(동기화일);
        p.restore();
        assertThat(p.isExpired()).isFalse();

        Partner q = 거래처();
        q.discontinue(종료일);
        q.restore();
        assertThat(q.isExpired()).isFalse();
        // 재개 뒤 DSRE 가 다시 걸 수 있어야 한다(출처가 지워졌으므로)
        assertThat(q.markDsreExpired(동기화일)).isTrue();
    }
}
