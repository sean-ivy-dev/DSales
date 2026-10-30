package com.daesung.sales.product.entity;

/**
 * 자재구분. 자재 마스터(V57)와 BOM 상세(33p)가 함께 쓴다.
 *
 * <p>★<b>물류 작업비 단가를 고르는 유일한 키</b>다. 발주처 구조보완요청안(2026-08-31) 원문 —
 * "물류 작업비는 세트구성(BOM)의 자재별 소요수량에 그 시행의 해당 자재구분 단가
 * (시험지→시험지, OMR→OMR, 단행본·책자→단행본, 라벨→라벨, <b>해설지→시험지</b>)를 곱해
 * 계산하는 기존 로직을 그대로 사용하므로, 자재 마스터에 별도 비용 연계 필드는 필요하지
 * 않습니다. <b>등록 시 자재구분을 정확히 선택하는 것이 중요합니다.</b>"
 *
 * <p>‼️<b>해설지는 시험지 단가를 쓴다.</b> 자재 목록에서는 시험지와 별개로 유지하지만
 * (별도 소요수량 관리·재고관리 목적) 단가는 시험지를 따른다 — 해설지 단가는 신설하지 않는다.
 * {@link #rateKey()}가 그 매핑을 한 곳에 담는다.
 */
public enum MaterialType {
    /** 시험지 */
    EXAM_PAPER("시험지"),
    /** 해설지 */
    ANSWER_SHEET("해설지"),
    /** OMR 카드 */
    OMR("OMR"),
    /** 라벨 */
    LABEL("라벨"),
    /** 단행본·책자 */
    BOOK("단행본"),
    /** 기타 자재 */
    ETC("기타");

    private final String label;

    /**
     * 코드명·한글 <b>둘 다</b> 받는다(프론트 회신 2026-09-17 B-9 — 자재 등록 400).
     * 화면은 목록에서 고른 한글을 그대로 되보낸다. 모르는 값은 그대로 400이다.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static MaterialType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        for (MaterialType t : values()) {
            if (t.name().equals(v) || t.label.equals(v)) {
                return t;
            }
        }
        // ★'책자'도 단행본으로 받는다(DSRE 운영덤프 실측: 책자 305건 ↔ 단행본 2건).
        // DSRE는 둘을 나눠 두었지만 단행본이 2건뿐이라 우리는 BOOK 한 항목으로 관리한다.
        // 화면·엑셀 표기는 '단행본'이고, 그 값으로 들어오면 400을 내지 않고 받아 준다
        // — 안 받으면 실데이터에서 압도적으로 많은 쪽(책자)이 거부된다.
        if ("책자".equals(v)) {
            return BOOK;
        }
        throw new IllegalArgumentException("알 수 없는 자재구분입니다: " + raw
                + " (시험지/해설지/OMR/라벨/단행본/기타)");
    }

    MaterialType(String label) {
        this.label = label;
    }

    /** 화면·엑셀 표기. 담당자는 EXAM_PAPER가 아니라 '시험지'라고 적는다. */
    public String label() {
        return label;
    }

    /**
     * 물류비용등록(36p)에서 어느 단가 컬럼을 볼지. 발주처 확정 매핑이다.
     *
     * <p>해설지가 시험지로 접히는 것이 핵심 — 이 매핑을 여러 곳에 흩어 두면
     * 한 곳만 고쳤을 때 작업비가 조용히 갈린다.
     */
    public MaterialType rateKey() {
        return (this == ANSWER_SHEET) ? EXAM_PAPER : this;
    }
}
