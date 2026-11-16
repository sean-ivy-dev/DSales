package com.daesung.sales.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.batch.service.DsrePartnerSyncBatch;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * DSRE 거래처 동기화는 <b>한 달에 한 번</b> 돈다 — 그 약속을 못박는다(2026-11-16).
 *
 * <p>발주처에 "한 달에 한 번 자동으로 반영되도록 하겠습니다"라고 회신했다.
 * 주기는 눈에 보이지 않는 값이라, 누가 매일로 바꿔도 테스트가 다 통과한다.
 * ‼️매일 돌면 <b>달 중간에 거래처가 목록에서 사라져</b> 담당자가 영문을 모른다 —
 * 그래서 주기 자체를 검증 대상으로 둔다.
 *
 * <p>★스프링 컨텍스트를 띄우지 않는다 — DSRE 연동이 꺼진 환경에선 이 빈이 아예 없고,
 * 검증하려는 것은 "코드에 적힌 주기"다.
 */
@DisplayName("DSRE 거래처 동기화 주기 — 매월 1일 03시")
class DsrePartnerSyncScheduleTest {

    @Test
    @DisplayName("★매월 1일 새벽 3시에 한 번만 돈다")
    void 매월_1일_03시() {
        Scheduled s = scheduledOf();
        assertThat(s.cron())
                .as("‼️주기를 바꾸려면 발주처 회신(2026-11-16 '한 달에 한 번')부터 바꿔야 한다")
                .isEqualTo("0 0 3 1 * *");

        CronExpression cron = CronExpression.parse(s.cron());

        // 1년을 돌려 보고 실제 발화 시점을 모은다 — cron 문자열만 보면 사람이 틀린다.
        List<LocalDateTime> fires = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(2027, 1, 1, 0, 0);
        for (int i = 0; i < 12; i++) {
            t = cron.next(t);
            fires.add(t);
        }

        assertThat(fires).as("한 해에 열두 번 = 월 1회").hasSize(12);
        assertThat(fires).allSatisfy(f -> {
            assertThat(f.getDayOfMonth()).as("달의 1일: %s", f).isEqualTo(1);
            assertThat(f.getHour()).as("새벽 3시: %s", f).isEqualTo(3);
            assertThat(f.getMinute()).isZero();
        });
        assertThat(fires.stream().map(LocalDateTime::getMonth).distinct().count())
                .as("열두 달이 각각 한 번씩")
                .isEqualTo(12);
    }

    @Test
    @DisplayName("‼️타임존을 지정하지 않는다 — 서버 시간대(KST)를 그대로 쓴다")
    void 타임존_기본() {
        assertThat(scheduledOf().zone())
                .as("타임존을 박으면 서버 설정과 어긋날 때 추적이 어렵다")
                .isEmpty();
    }

    private Scheduled scheduledOf() {
        try {
            Scheduled s = DsrePartnerSyncBatch.class
                    .getMethod("scheduled").getAnnotation(Scheduled.class);
            assertThat(s).as("@Scheduled 가 사라졌다 = 자동 실행이 멈췄다").isNotNull();
            // 참고: 아래는 쓰지 않지만, 시스템 기본 시간대가 바뀌어도 이 테스트가
            // 흔들리지 않는다는 것을 남겨 둔다(cron 계산은 LocalDateTime 으로만 한다).
            ZoneId.systemDefault();
            return s;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("scheduled() 메서드가 사라졌다", e);
        }
    }
}
