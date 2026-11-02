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

    /** 진행도. 작을수록 덜 진행된 것. 삭제는 99 — 살아 있는 발송이 하나라도 있으면 그쪽이 이긴다. */
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

    /** 주문(발송 묶음)의 상태 = 가장 덜 진행된 발송의 상태. */
    public static OrderState groupState(Collection<Shipment> shipments) {
        int min = Integer.MAX_VALUE;
        for (Shipment s : shipments) {
            min = Math.min(min, rankOf(s.progressState()));
        }
        return (min == Integer.MAX_VALUE) ? OrderState.PREPARING : stateOf(min);
    }

    /**
     * 주문 식별 키. 등록번호가 있으면 그것, 없으면 발송 건 자신({@code #id})이다.
     *
     * <p>‼️등록번호는 V103 에서 생겼다 — 그 전 등록분·이관분은 비어 있다. 그때 비어 있는
     * 것들을 (거래처·거래일자·거래순번)으로 묶으면 <b>서로 무관한 옛 주문이 한 줄로 합쳐진다</b>
     * (옛 행은 거래순번이 기본값 1에서 멈춰 있다). 그래서 발송 건 단위로 둔다.
     *
     * <p>DB 쪽 식({@code COALESCE(s.entry_no, CONCAT('#', s.id))})과 같아야 한다 —
     * 어긋나면 페이징한 키로 발송 건을 못 찾아 목록이 빈다. 테스트가 지킨다.
     */
    public static String orderKey(Shipment s) {
        String entryNo = s.getEntryNo();
        return (entryNo == null || entryNo.isBlank()) ? "#" + s.getId() : entryNo;
    }
}
