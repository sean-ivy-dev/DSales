package com.daesung.sales.sale.entity;

/**
 * 접수경로 — 주문이 <b>어디로 들어왔나</b>(프론트 I-2).
 *
 * <p>근거: 10/27 명세 §2-1 "<b>수기 주문과 온라인(특약점) 주문을 함께 보여주고</b>,
 * 특약점 사이트와 서로 연동한다." 함께 보여주려면 둘을 가를 칸이 있어야 한다.
 *
 * <p>‼️<b>비어 있으면 수기({@link #MANUAL})로 본다.</b> 이관분·기존 등록분이 전부 비어 있는데,
 * 특약점 사이트 연동이 2026-11 에 열렸으므로 그 전 주문은 전부 수기가 맞다.
 */
public enum OrderChannel {

    /** 특약점 사이트에서 들어온 주문({@code POST /dealer-site/orders}). */
    ONLINE("온라인"),

    /** 매출등록에서 담당자가 넣은 주문({@code POST /sales/entries}). */
    MANUAL("수기");

    private final String label;

    OrderChannel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 비어 있으면 수기다 — 위 주석의 이유. */
    public static OrderChannel orDefault(OrderChannel v) {
        return (v == null) ? MANUAL : v;
    }
}
