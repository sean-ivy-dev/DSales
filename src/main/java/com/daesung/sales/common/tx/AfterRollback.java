package com.daesung.sales.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 우리 트랜잭션이 <b>롤백되면</b> 실행할 보상 작업을 건다.
 *
 * <p>DSRE2 는 별도 DB 라 우리 트랜잭션에 묶이지 않는다. DSRE 쪽을 먼저 커밋한 뒤 우리 저장이
 * 실패하면 두 장부가 어긋난다 — 그때 DSRE 를 원래대로 되돌리는 데 쓴다.
 *
 * <p>보상이 실패해도 예외를 던지지 않는다(이미 롤백 중이라 받을 곳이 없다). 대신 오류 로그를 남겨
 * 사람이 손으로 맞출 수 있게 한다.
 */
@Slf4j
public final class AfterRollback {

    private AfterRollback() {
    }

    /** 무엇을 되돌리는가. 로그 문구를 상수로 묶어 둔다(자유 문자열을 로그에 넣지 않는다 — CRLF 위조 방지). */
    public enum Kind {
        /** 발송완료를 되돌림(우리 저장 실패 → DSRE 발송취소). */
        UNDO_SHIP,
        /** 발송취소를 되돌림(우리 저장 실패 → DSRE 다시 발송완료). */
        UNDO_UNSHIP
    }

    public static void register(Kind kind, int reqCd, Runnable compensation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;   // 트랜잭션 밖이면 롤백도 없다
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                boolean undoShip = kind == Kind.UNDO_SHIP;
                try {
                    compensation.run();
                    if (undoShip) {
                        log.warn("우리 저장이 롤백되어 DSRE 발송완료를 되돌렸습니다. reqCd={}", reqCd);
                    } else {
                        log.warn("우리 저장이 롤백되어 DSRE 발송취소를 되돌렸습니다. reqCd={}", reqCd);
                    }
                } catch (RuntimeException e) {
                    // 예외 객체는 넘기지 않는다 — 메시지에 외부 값이 섞일 수 있어 로그 위조 룰에 걸린다. 종류만 남긴다.
                    if (undoShip) {
                        log.error("‼️DSRE 발송완료 되돌리기 실패 — 수동 확인 필요. reqCd={} cause={}",
                                reqCd, e.getClass().getName());
                    } else {
                        log.error("‼️DSRE 발송취소 되돌리기 실패 — 수동 확인 필요. reqCd={} cause={}",
                                reqCd, e.getClass().getName());
                    }
                }
            }
        });
    }
}
