package com.daesung.sales.dsre.gateway;

import java.time.LocalDate;

/**
 * 발송완료·발송취소를 DSRE2 에 반영한 신청 1건의 결과.
 *
 * @param beforeState  처리 직전 DSRE 상태(없는 신청이면 null)
 * @param prevDelDate  처리 직전 {@code tbl_logis_info.DEL_DT} — 우리 쪽 저장이 실패해 DSRE 를
 *                     되돌릴 때 원래 발송일로 복원하려고 받아 둔다
 */
public record DsreShipResult(int reqCd, String beforeState, Outcome outcome, LocalDate prevDelDate) {

    public enum Outcome {
        /** 상태를 바꾸고 자재까지 처리했다. */
        CHANGED,
        /** 이미 발송완료라 발송일만 고쳤다(DSRE2 발송처리 화면과 같은 동작). */
        DATE_ONLY,
        /** 대상 상태가 아니어서 아무것도 바꾸지 않았다. */
        SKIPPED,
        /** DSRE2 에 없는 신청. */
        NOT_FOUND
    }

    public boolean changed() {
        return outcome == Outcome.CHANGED;
    }
}
