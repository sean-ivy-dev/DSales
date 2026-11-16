package com.daesung.sales.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.batch.entity.BatchJobRun;
import com.daesung.sales.batch.repository.NotificationRepository;
import com.daesung.sales.batch.service.BatchJobRunner;
import com.daesung.sales.support.IntegrationTestSupport;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 배치가 재시도를 다 쓰고 실패하면 <b>알림이 남는다</b>(2026-11-16).
 *
 * <p>‼️그전에는 {@code log.warn} 과 이력 행만 남았다 — <b>아무도 모른다.</b>
 * DSRE 거래처 동기화는 <b>한 달에 한 번</b>만 도는데, 조용히 실패하면
 * 다음 달까지 만료 반영이 멈춘 것을 알 수 없다(PM 전달 2026-11-16 §3 "실패 시 알림").
 *
 * <p>★알림은 담보만기 팝업이 쓰는 {@code notification} 표에 적는다 —
 * 화면이 이미 그것을 읽고 있어 새 채널을 만들 필요가 없다.
 */
@DisplayName("배치 실패 알림 — 조용히 실패하지 않는다")
class BatchFailureNotificationTest extends IntegrationTestSupport {

    @Autowired
    private BatchJobRunner runner;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    @DisplayName("★★재시도를 다 쓰고 실패하면 알림이 남는다 — 사유까지")
    void 실패하면_알림() {
        String job = "TEST_FAIL_" + System.nanoTime();
        LocalDate day = LocalDate.of(2044, 3, 3);
        AtomicInteger calls = new AtomicInteger();

        BatchJobRun last = runner.run(job, day, 3, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("DSRE 접속 실패(테스트)");
        });

        assertThat(calls.get()).as("재시도를 다 쓴다").isEqualTo(3);
        assertThat(last).isNotNull();

        var noti = notificationRepository.findByDedupKey(
                BatchJobRunner.FAIL_NOTI_TYPE + ":" + job + ":" + day);
        assertThat(noti).as("실패 알림이 남아야 한다").isPresent();
        assertThat(noti.get().getTitle()).contains(job);
        assertThat(noti.get().getMessage())
                .as("사유가 없으면 담당자가 무엇을 손봐야 할지 모른다")
                .contains("DSRE 접속 실패(테스트)");
        assertThat(noti.get().isReadYn()).isFalse();
    }

    @Test
    @DisplayName("‼️여러 번 돌려도 알림은 하루·잡 단위로 하나 — 쌓이면 읽지 않게 된다")
    void 중복되지_않는다() {
        String job = "TEST_DUP_" + System.nanoTime();
        LocalDate day = LocalDate.of(2044, 4, 4);
        for (int i = 0; i < 3; i++) {
            runner.run(job, day, 1, () -> {
                throw new IllegalStateException("반복 실패");
            });
        }
        long n = notificationRepository.findAll().stream()
                .filter(x -> x.getDedupKey().contains(job))
                .count();
        assertThat(n).isEqualTo(1);
    }

    @Test
    @DisplayName("성공하면 알림을 만들지 않는다 — 잘 돌았다는 알림은 소음이다")
    void 성공하면_알림없음() {
        String job = "TEST_OK_" + System.nanoTime();
        LocalDate day = LocalDate.of(2044, 5, 5);
        BatchJobRun run = runner.run(job, day, 3, () -> 7);

        assertThat(run.getAffected()).isEqualTo(7);
        assertThat(notificationRepository.findAll().stream()
                .noneMatch(x -> x.getDedupKey().contains(job))).isTrue();
    }
}
