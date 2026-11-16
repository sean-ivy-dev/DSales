package com.daesung.sales.batch.service;

import com.daesung.sales.batch.entity.BatchJobRun;
import com.daesung.sales.partner.dto.DsreCodeSyncResult;
import com.daesung.sales.partner.service.DsrePartnerCodeSyncService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * DSRE 거래처 동기화 <b>월 1회</b> 배치 — 만료 거래처를 우리 마스터에 반영한다.
 *
 * <p>근거: 발주처 회신 2026-11-16 — "DSRE 만료 자동 반영: 한 달에 한 번 자동으로
 * 반영되도록 하겠습니다". 그전에는 사람이 {@code POST /masters/clients/sync-dsre-code} 를
 * 눌러야 했고, 실제로 11/11·11/12 두 번만 돌았다.
 *
 * <h3>★매월 1일 새벽 3시로 잡은 이유</h3>
 * <ul>
 *   <li><b>월초</b> — 전월 마감을 끝내고 새 달을 시작할 때 거래처 상태가 정리돼 있는 것이 자연스럽다.
 *       달 중간에 돌면 쓰던 거래처가 갑자기 목록에서 사라져 담당자가 영문을 모른다.</li>
 *   <li><b>새벽 3시</b> — 담당자가 쓰지 않는 시간이고,
 *       {@link CollateralExpiryBatch}(매일 3시)와 같은 시간대다.</li>
 * </ul>
 *
 * <p>‼️<b>조회·마감 화면의 숫자는 바뀌지 않는다</b> — 만료 조건이 걸린 곳은 거래처 목록
 * 조회 두 곳뿐이다(외상매출현황·외상매출장·통합매출조회·수금·계산서신고엔 없다).
 * 바뀌는 것은 "거래처를 고르는 목록"뿐이다.
 *
 * <p>★<b>DSRE 연동이 꺼져 있으면 이 빈 자체가 안 뜬다</b>({@code daesung.dsre.enabled}).
 * 동기화 서비스가 같은 조건이라, 그것만 있고 배치가 없으면 기동 때 의존성을 못 찾는다.
 */
@Service
@ConditionalOnProperty(name = "daesung.dsre.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DsrePartnerSyncBatch {

    public static final String JOB_NAME = "DSRE_PARTNER_SYNC";

    /** 한 달에 한 번이라 실패하면 다음 달까지 비어 버린다 — 재시도를 넉넉히 둔다. */
    private static final int MAX_ATTEMPTS = 3;

    private final DsrePartnerCodeSyncService syncService;
    private final BatchJobRunner runner;

    /** 매월 1일 새벽 3시. */
    @Scheduled(cron = "0 0 3 1 * *")
    public void scheduled() {
        run(LocalDate.now());
    }

    /**
     * 실제 동기화. 스케줄과 이력 적재를 묶는다.
     *
     * <p>★여러 번 돌려도 안전하다 — 대표코드는 가장 작은 값으로 결정적이고,
     * 만료일은 "처음 Y 를 본 날"을 한 번만 적는다(이미 만료된 행은 덮지 않는다).
     *
     * <p>‼️<b>수동 실행용 엔드포인트는 새로 만들지 않았다</b> —
     * {@code POST /masters/clients/sync-dsre-code} 가 이미 그것이고,
     * 같은 서비스를 부른다(DSRE 꺼짐 처리까지 그쪽에 있다). 같은 일을 하는 길을
     * 둘로 늘리면 "어느 쪽이 진짜냐"가 생긴다.
     * 자동 실행 결과는 {@code GET /batch/runs?jobName=DSRE_PARTNER_SYNC} 로 본다.
     *
     * @return 실행 기록. 처리 건수는 "새로 만료 + 만료 해제" 합이다.
     */
    public BatchJobRun run(LocalDate baseDate) {
        return runner.run(JOB_NAME, baseDate, MAX_ATTEMPTS, () -> {
            DsreCodeSyncResult r = syncService.sync();
            return r.dsreExpired() + r.dsreRestored();
        });
    }
}
