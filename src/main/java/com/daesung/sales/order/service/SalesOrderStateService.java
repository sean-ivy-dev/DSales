package com.daesung.sales.order.service;

import com.daesung.sales.audit.entity.StatusEntityType;
import com.daesung.sales.audit.service.StatusHistoryService;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문조회 <b>선택 건 상태변경</b> — 접수 단계(접수완료·상품검수·상품준비중) 전이.
 *
 * <p>근거: 10/27 명세 §2-8 — "선택지를 <b>접수완료 / 상품검수 / 상품준비중</b>만 남긴다.
 * 발송준비중·발송완료는 물류 영역이므로 <b>[작업요청서]에서만</b> 처리한다."
 * 발주처 1차회신(2026-10-02) #4 — "진행상태 6단계, <b>상품검수·취소 포함</b>
 * (사용빈도는 거의 없으나 일단 포함)".
 *
 * <h3>전이 축이 둘인 이유</h3>
 * <pre>
 *   접수 축(여기)        접수완료 ↔ 상품검수 ↔ 상품준비중     주문조회 화면
 *   물류 축(작업요청서)   상품준비중 → 발송준비중 → 발송완료    POST /orders/state
 * </pre>
 * 섞으면 접수완료가 곧바로 발송완료가 되는 주문이 생기고, 검수·준비 단계가 있으나 마나가 된다.
 *
 * <h3>세 상태 사이는 자유롭게 오간다</h3>
 * §2-8 이 "선택지를 셋만 남긴다"고만 했고, 발주처가 상품검수를 "사용빈도는 거의 없으나 일단
 * 포함"이라고 했다 — 즉 <b>접수완료 → 상품준비중 바로 가기가 평상시 흐름</b>이다.
 * 한 칸씩만 허용하면 그 평상시 흐름이 막힌다.
 *
 * <h3>‼️물류가 이미 진행한 주문은 거부한다</h3>
 * 출력·발송된 발송 건에 접수 단계를 적어도 <b>화면에는 아무 변화가 없다</b> —
 * {@link Shipment#progressState()} 가 출력·발송을 먼저 보기 때문이다(그게 맞다. 접수 단계가
 * 물류 진행을 되돌리면 안 된다). 조용한 무동작은 담당자가 바뀐 줄 알게 만들므로
 * <b>사유를 말하고 거부</b>한다.
 */
@Service
@RequiredArgsConstructor
public class SalesOrderStateService {

    /** 이 API 가 다루는 축. §2-8 의 세 선택지 그대로다. */
    private static final Set<OrderState> INTAKE_STATES =
            Set.of(OrderState.RECEIVED, OrderState.INSPECTING, OrderState.PREPARING);

    private final ShipmentRepository shipmentRepository;
    private final StatusHistoryService statusHistoryService;

    /** 건별 결과. {@code changed=false} 면 {@code message} 에 사유가 있다. */
    public record ItemResult(String orderNo, boolean changed,
                             String fromCode, String fromName,
                             String toCode, String toName, String message) {
    }

    /** 일괄 전환 결과. {@code skipped} 가 0이 아니면 건별 사유를 확인해야 한다. */
    public record BulkResult(int requested, int changed, int skipped, List<ItemResult> results) {
    }

    /**
     * 선택한 주문들의 접수 단계를 한 번에 바꾼다.
     *
     * <p>★<b>한 건이 안 된다고 전체를 실패시키지 않는다.</b> 100건을 체크한 담당자가
     * 이미 발송된 1건 때문에 전부 다시 고르는 것은 실무에서 못 쓴다(기존 물류 축 전환과 같은 규칙).
     */
    @Transactional
    public BulkResult change(List<String> orderNos, OrderState to, String reason) {
        if (!INTAKE_STATES.contains(to)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "주문조회에서 바꿀 수 있는 상태는 접수완료·상품검수·상품준비중뿐입니다. "
                            + "요청: " + to.label() + "(" + to.code() + ") — "
                            + "발송준비중·발송완료는 작업요청서에서 처리합니다.");
        }

        // 주문 키 → 그 주문의 발송 건들. 한 주문이 발송 여럿으로 갈릴 수 있어 함께 바꾼다(§3-6).
        Map<String, List<Shipment>> byOrder = new LinkedHashMap<>();
        for (String no : orderNos) {
            byOrder.putIfAbsent(no, new ArrayList<>());
        }
        for (Shipment s : shipmentRepository.findByOrderKeys(byOrder.keySet())) {
            List<Shipment> bucket = byOrder.get(OrderProgress.orderKey(s));
            if (bucket != null) {
                bucket.add(s);
            }
        }

        List<ItemResult> results = new ArrayList<>();
        int changed = 0;
        for (Map.Entry<String, List<Shipment>> e : byOrder.entrySet()) {
            ItemResult r = changeOne(e.getKey(), e.getValue(), to, reason);
            results.add(r);
            if (r.changed()) {
                changed++;
            }
        }
        return new BulkResult(byOrder.size(), changed, byOrder.size() - changed, results);
    }

    private ItemResult changeOne(String orderNo, List<Shipment> shipments,
                                 OrderState to, String reason) {
        if (shipments.isEmpty()) {
            return new ItemResult(orderNo, false, null, null, to.name(), to.label(),
                    "그 주문번호의 주문이 없습니다.");
        }
        OrderState from = OrderProgress.groupState(shipments);
        if (from == to) {
            return new ItemResult(orderNo, false, from.name(), from.label(),
                    to.name(), to.label(), "이미 " + to.label() + "입니다.");
        }
        if (!INTAKE_STATES.contains(from)) {
            // 물류가 이미 진행했거나 삭제된 주문 — 적어도 화면이 안 바뀌므로 거부한다.
            return new ItemResult(orderNo, false, from.name(), from.label(),
                    to.name(), to.label(),
                    from.label() + " 주문은 접수 단계로 바꿀 수 없습니다"
                            + (from == OrderState.CANCELED ? "." : " — 작업요청서에서 처리합니다."));
        }

        for (Shipment s : shipments) {
            OrderState before = s.progressState();
            s.applyIntakeState(to);
            // ‼️발송 건마다 남긴다 — 주문 단위로 하나만 남기면 갈린 발송 중 어느 것이
            //   바뀌었는지 못 따라간다. 이력이 없으면 되돌린 사실 자체가 어디에도 없다.
            statusHistoryService.record(StatusEntityType.SHIPMENT, s.getId(), "progressState",
                    before.name(), to.name(), reason);
        }
        return new ItemResult(orderNo, true, from.name(), from.label(),
                to.name(), to.label(), null);
    }
}
