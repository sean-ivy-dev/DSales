package com.daesung.sales.audit.entity;

/**
 * 상태변경 이력의 대상 종류. 감사에서 실제로 추궁받는 축만 둔다.
 *
 * <p>논리삭제(deleted_at/deleted_by)는 여기 넣지 않는다 — 행 자체에 누가·언제가 이미 남아 중복이다.
 */
public enum StatusEntityType {
    /** 매출 — 취소 여부 */
    SALE,
    /** 월마감 — 잠금/해제. 같은 월을 여러 번 여닫으면 기존 구조로는 중간 기록이 사라진다 */
    PERIOD_LOCK,
    /** 위탁 미결 — 미정산/부분정산/완료 */
    CONSIGNMENT_OUT,
    /** 학교 — 사용/미사용(DSRE 동기화 배치가 바꾼다) */
    SCHOOL,
    /** 계정 — 활성/비활성 */
    APP_USER,
    /**
     * 재고 전표(입고·폐기·이고·실사) — 삭제. entityId = 첫 이벤트 id.
     *
     * <p>전표는 자체 테이블이 아니라 {@code inventory_txn} 여러 행의 묶음(refNo)이라
     * 대표 id를 쓴다. 무엇을 지웠는지는 reason 에 품목·수량까지 적어 둔다.
     */
    INVENTORY_VOUCHER,
    /**
     * 발송 건(작업요청서) — 출력·확인 표시.
     *
     * <p>둘 다 <b>되돌릴 수 있게</b> 열었기 때문에 이력이 필요하다(B-14). 특히 출력 되돌리기는
     * "언제 처음 작업지시가 나갔나"를 지우는 행위라, 사유 없이 내리면 나중에 소명할 수 없다.
     */
    SHIPMENT,
    /**
     * DSRE2 주문 — 진행상태. entityId = REQ_CD.
     *
     * <p>원본 상태는 DSRE2 {@code tbl_request_info.STATE}에 있고 <b>DSRE2에는 이력 테이블이 없다</b>
     * (제자리 UPDATE라 이전 값이 사라진다). 우리가 전환시킨 건만이라도 여기 남겨,
     * "누가 언제 명세서를 발급해서 발송준비중이 됐는지"를 소명할 수 있게 한다.
     */
    DSRE_ORDER,
    /**
     * 거래처×도서 노출 예외 — 열기/닫기. entityId = 예외 행 id(삭제 시엔 지우기 전 id).
     *
     * <p>근거: 10/31 클라이언트 회신 §1-4 "변경 이력(누가, 언제, 열기/닫기)을 남긴다".
     */
    PARTNER_PRODUCT_VISIBILITY
}
