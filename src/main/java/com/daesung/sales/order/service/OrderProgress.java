package com.daesung.sales.order.service;

import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.logistics.entity.Shipment;
import java.util.Collection;

/**
 * 주문의 <b>진행도</b> — 한 주문이 발송 여러 건으로 갈렸을 때 그 주문의 상태를 정한다.
 *
 * <p>근거: 10/27 명세 §3-6 — "거래순번 … <b>한 주문이 학교·분류 때문에 발송 여러 건으로
 * 갈려도 번호는 하나</b>다." 주문조회(§2-1)는 주문 단위 목록이므로, 갈린 발송들의 상태를
 * 하나로 합쳐야 한다.
 *
 * <p>★규칙은 <b>가장 덜 진행된 것</b>이다. 세 상자 중 둘만 나갔으면 그 주문은 아직
 * 발송완료가 아니다 — 가장 진행된 것을 쓰면 안 나간 상자가 조용히 묻힌다.
 *
 * <p>삭제는 랭크 99라 <b>같은 min 규칙에 자연히 들어온다</b> — 일부만 삭제된 주문은 살아 있는
 * 쪽 상태가 이기고, 전부 삭제됐을 때만 삭제로 보인다. 별도 분기가 필요 없다.
 *
 * <p>‼️<b>단건 상태는 {@link Shipment#progressState()} 하나가 정한다.</b> 여기서 날짜를
 * 다시 읽지 않는다 — 판정이 두 군데로 갈라지면 화면마다 상태가 달라진다.
 * DB 쪽 랭크 식({@code ShipmentRepository.ORDER_STATE_RANK})과의 일치는 테스트가 지킨다.
 */
public final class OrderProgress {

    private OrderProgress() {
    }

    /**
     * 진행도. 작을수록 덜 진행된 것. 삭제는 99 — 살아 있는 발송이 하나라도 있으면 그쪽이 이긴다.
     *
     * <p>‼️{@code null} 은 <b>접수완료로 보지 않는다</b>. 접수완료는 "수정·삭제해도 된다"는
     * 뜻이라({@code SalesOrderStateService.delete}·{@code SaleService.assertEditable}),
     * 상태를 모르는 것을 거기에 넣으면 <b>모른다는 사실이 허가로 바뀐다</b>.
     * 상품준비중으로 둔다 — {@link Shipment#progressState()} 는 null 을 내지 않으므로
     * 이 분기는 실제로 닿지 않는 방어다.
     */
    public static int rankOf(OrderState state) {
        if (state == null) {
            return rankOf(OrderState.PREPARING);
        }
        return switch (state) {
            case RECEIVED -> 1;
            case INSPECTING -> 2;
            case PREPARING -> 3;
            case READY_TO_SHIP -> 4;
            case SHIPPED -> 5;
            case CANCELED -> 99;
        };
    }

    /** 랭크 → 상태. {@link #rankOf}의 역이다. */
    public static OrderState stateOf(int rank) {
        for (OrderState s : OrderState.values()) {
            if (rankOf(s) == rank) {
                return s;
            }
        }
        return OrderState.PREPARING;
    }

    /**
     * 주문(발송 묶음)의 상태 = 가장 덜 진행된 발송의 상태.
     *
     * <p>‼️<b>빈 묶음은 접수완료가 아니다</b> — 위 {@link #rankOf} 와 같은 이유로
     * 상품준비중을 돌려준다. 호출부는 비었는지 먼저 확인하고 사유를 말해야 한다.
     */
    public static OrderState groupState(Collection<Shipment> shipments) {
        int min = Integer.MAX_VALUE;
        for (Shipment s : shipments) {
            min = Math.min(min, rankOf(s.progressState()));
        }
        return (min == Integer.MAX_VALUE) ? OrderState.PREPARING : stateOf(min);
    }

    /**
     * 주문 식별 키. 등록번호가 있으면 그것, 없으면 <b>(거래일·거래처·학교·분류)</b> 네 칸이다.
     *
     * <h3>‼️정정(2026-11-17) — 발송 건 단위({@code #id})에서 네 칸 키로 바꿨다</h3>
     * 이관이 한 묶음을 발송 여럿으로 쪼개 넣어, 발송 단위로 두면 <b>같은 줄이 발송마다
     * 중복으로 더해졌다</b>(10/31 자사몰 16건 → 금액 3.7배). 자세한 내용은
     * {@code ShipmentRepository.ORDER_KEY} 주석.
     *
     * <p>★예전 주석의 우려("(거래처·거래일자·<b>거래순번</b>)으로 묶으면 무관한 옛 주문이
     * 합쳐진다")는 <b>거래순번</b>을 쓸 때의 이야기다. 여기는 거래순번을 쓰지 않고
     * <b>학교·분류</b>를 쓴다 — 네 칸이 모두 같은 건들은 원래 한 묶음이다.
     *
     * <p>DB 쪽 식({@code ShipmentRepository.ORDER_KEY})과 같아야 한다 —
     * 어긋나면 페이징한 키로 발송 건을 못 찾아 목록이 빈다. 테스트가 지킨다.
     */
    public static String orderKey(Shipment s) {
        String entryNo = s.getEntryNo();
        if (entryNo != null && !entryNo.isBlank()) {
            return entryNo;
        }
        // ★등록번호가 없으면 **네 칸 키**(거래일·거래처·학교·분류).
        //   ‼️`ShipmentRepository.ORDER_KEY`(SQL)와 **문자 하나까지 같아야** 한다 —
        //     어긋나면 페이징한 키로 발송을 못 찾아 목록이 빈다.
        return s.getTradeDate() + "|" + s.getPartner().getId() + "|"
                + (s.getSchoolCode() == null ? "" : s.getSchoolCode()) + "|"
                + (s.getTradeClass() == null ? "" : s.getTradeClass());
    }
}
