package com.daesung.sales.batch.service;

import com.daesung.sales.batch.entity.BatchJobRun;
import com.daesung.sales.batch.entity.Notification;
import com.daesung.sales.batch.repository.BatchJobRunRepository;
import com.daesung.sales.batch.repository.NotificationRepository;
import java.time.LocalDate;
import java.util.function.IntSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배치 공통 실행 래퍼 — 이력 기록 + 실패 재시도.
 * 근거: 개발문서 19.0 완료조건 "배치 정상완료 + 실패 재시도 로직 검증".
 *
 * <p>이력은 <b>본 작업과 별도 트랜잭션</b>(REQUIRES_NEW)으로 남긴다.
 * 같은 트랜잭션이면 작업이 실패해 롤백될 때 "실패했다"는 기록까지 함께 사라져,
 * 정작 검수 때 봐야 할 실패 이력이 남지 않는다.
 *
 * <h3>★재시도를 다 쓰고 실패하면 <b>알림</b>을 남긴다(2026-11-16)</h3>
 * 그전에는 {@code log.warn} 과 이력 행만 남았다 — <b>아무도 모른다.</b>
 * 월 1회만 도는 배치({@code DsrePartnerSyncBatch})가 조용히 실패하면
 * <b>다음 달까지 반영이 멈춘 것을 알 수 없다.</b>
 * 그래서 담보만기 팝업이 쓰는 {@code notification} 통로에 같이 적는다 —
 * 화면이 이미 그 표를 읽고 있어 새 채널을 만들 필요가 없다({@code GET /notifications}).
 * 메일·메신저가 붙으면 이 표를 읽어 보내면 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchJobRunner {

    /** 실패 알림 종류. 화면이 이 값으로 가려낸다. */
    public static final String FAIL_NOTI_TYPE = "BATCH_FAILED";

    private final BatchJobRunRepository runRepository;
    private final NotificationRepository notificationRepository;

    /**
     * 작업을 최대 {@code maxAttempts}회까지 시도하고 매 시도를 이력으로 남긴다.
     *
     * @param work 실제 작업. 반환값 = 처리 건수
     * @return 마지막 시도의 이력
     */
    public BatchJobRun run(String jobName, LocalDate runDate, int maxAttempts, IntSupplier work) {
        BatchJobRun last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            BatchJobRun run = save(BatchJobRun.start(jobName, runDate, attempt));
            try {
                int affected = work.getAsInt();
                run.succeed(affected);
                last = save(run);
                log.info("배치 성공 job={} date={} attempt={} affected={}", jobName, runDate, attempt, affected);
                return last;
            } catch (RuntimeException e) {
                run.fail(e.getMessage());
                last = save(run);
                log.warn("배치 실패 job={} date={} attempt={}/{} : {}",
                        jobName, runDate, attempt, maxAttempts, e.getMessage());
            }
        }
        // 여기까지 왔으면 재시도를 다 쓰고 실패했다.
        notifyFailure(jobName, runDate, last);
        return last;
    }

    /**
     * 실패 알림 적재. <b>하루·잡 단위로 한 번만</b> 쌓는다(dedupKey) —
     * 수동으로 여러 번 돌려 보는 동안 같은 알림이 쌓이면 읽지 않게 된다.
     *
     * <p>‼️<b>알림 적재가 실패해도 배치 결과를 덮지 않는다.</b> 알림은 곁가지인데
     * 여기서 예외가 올라가면 호출부가 "배치 자체가 터졌다"로 받는다.
     */
    private void notifyFailure(String jobName, LocalDate runDate, BatchJobRun last) {
        try {
            String dedupKey = FAIL_NOTI_TYPE + ":" + jobName + ":" + runDate;
            if (notificationRepository.findByDedupKey(dedupKey).isPresent()) {
                return;
            }
            String err = (last == null || last.getMessage() == null) ? "(사유 미기록)" : last.getMessage();
            if (err.length() > 300) {
                err = err.substring(0, 300) + "…";
            }
            saveNotification(Notification.of(FAIL_NOTI_TYPE, dedupKey,
                    "배치 실패: " + jobName,
                    runDate + " 실행이 재시도까지 모두 실패했습니다. 사유: " + err,
                    "BATCH_JOB", (last == null) ? null : last.getId(), runDate));
        } catch (RuntimeException e) {
            log.warn("배치 실패 알림 적재 실패 job={} date={} : {}", jobName, runDate, e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveNotification(Notification n) {
        notificationRepository.save(n);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchJobRun save(BatchJobRun run) {
        return runRepository.save(run);
    }
}
