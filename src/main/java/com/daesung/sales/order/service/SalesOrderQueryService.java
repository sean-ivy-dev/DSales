package com.daesung.sales.order.service;

import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.logistics.dto.WorkOrderResponse;
import com.daesung.sales.logistics.entity.DeliveryType;
import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
import com.daesung.sales.logistics.service.WorkService;
import com.daesung.sales.order.dto.SalesOrderRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문조회(통합) — <b>우리 주문</b>을 읽는다.
 *
 * <p>근거: 10/27 명세 §1 — "조회 대상 … 지금은 DSRE 주문(신청번호·지사코드)을 읽어 온다.
 * 이를 <b>새 특약점 주문 + 매출에서 수기 등록한 주문</b>으로 바꾼다.
 * DSRE에서 운영하는 상품을 끌어오는 구조가 아니다." · §2-1 · §2-2.
 *
 * <p>★<b>§2-2 버그의 정체</b>: "등록한 주문이 [주문 조회]에 나오지 않고 [출고/반품 조회]와
 * [작업요청서]에만 넘어간다." 그 목록이 DSRE2 신청을 읽고 있었으니, 우리 쪽에 아무리
 * 등록해도 한 줄도 안 늘었다. 대상을 바꾸면 <b>등록 즉시</b> 보인다(A-3도 같은 변경으로 끝난다).
 *
 * <p>★<b>줄 조립은 작업요청서 것을 그대로 쓴다</b>({@link WorkService#buildWorkOrders}) —
 * 매출 줄과 위탁출고 지시를 모으는 규칙을 복붙하면 두 화면의 줄이 달라진다
 * (물류 작업비가 출고/반품 조회와 어긋나 있던 것과 같은 함정이다).
 */
@Service
@RequiredArgsConstructor
public class SalesOrderQueryService {

    private final ShipmentRepository shipmentRepository;
    private final WorkService workService;

    /**
     * 주문 목록. 페이징 단위는 <b>주문</b>이다 — 한 주문이 발송 여럿으로 갈려도 한 줄이다(§3-6).
     *
     * @param state 진행상태 필터. {@code null}=전체
     */
    @Transactional(readOnly = true)
    public Page<SalesOrderRow> orders(LocalDate from, LocalDate to, Long partnerId,
                                      String partnerCode, String keyword, OrderState state,
                                      Pageable pageable) {
        String kw = (keyword == null || keyword.isBlank())
                ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        Integer stateRank = (state == null) ? null : OrderProgress.rankOf(state);

        Page<String> keys = shipmentRepository.findOrderKeyPage(
                from, to, partnerId, blankToNull(partnerCode), kw, stateRank, pageable);
        if (keys.getContent().isEmpty()) {
            return new PageImpl<>(List.of(), pageable, keys.getTotalElements());
        }

        List<Shipment> shipments = shipmentRepository.findByOrderKeys(keys.getContent());
        // 작업요청서와 같은 조립 — 줄·신청번호가 여기서 붙는다.
        List<WorkOrderResponse> built = workService.buildWorkOrders(shipments, from, to);

        // 발송 id → 그 발송의 엔티티(상태 판정에 쓴다. 조립 결과엔 엔티티가 없다)
        Map<Long, Shipment> byId = new LinkedHashMap<>();
        for (Shipment s : shipments) {
            byId.put(s.getId(), s);
        }

        // 주문 키로 묶는다. 키 순서는 **쿼리가 정한 순서**를 따른다 —
        // 여기서 다시 정렬하면 페이지마다 순서가 달라진다.
        Map<String, List<WorkOrderResponse>> grouped = new LinkedHashMap<>();
        for (String key : keys.getContent()) {
            grouped.put(key, new ArrayList<>());
        }
        for (WorkOrderResponse w : built) {
            Shipment s = byId.get(w.id());
            if (s == null) {
                continue;
            }
            List<WorkOrderResponse> bucket = grouped.get(OrderProgress.orderKey(s));
            if (bucket != null) {
                bucket.add(w);
            }
        }

        List<SalesOrderRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<WorkOrderResponse>> e : grouped.entrySet()) {
            if (!e.getValue().isEmpty()) {
                rows.add(merge(e.getKey(), e.getValue(), byId));
            }
        }
        return new PageImpl<>(rows, pageable, keys.getTotalElements());
    }

    /**
     * 갈린 발송들을 주문 한 줄로 합친다.
     *
     * <p>합치는 규칙 —
     * <ul>
     *   <li>진행상태: <b>가장 덜 진행된</b> 것({@link OrderProgress#groupState})</li>
     *   <li>학교·분류·발송구분: <b>하나로 같을 때만</b> 싣는다. 섞였는데 아무거나 고르면
     *       담당자가 그 값으로 주문을 특정했다고 믿는다 — 비워서 "줄을 보라"고 알린다</li>
     *   <li>삭제: <b>전부</b> 삭제됐을 때만 true</li>
     *   <li>수량·금액: 합</li>
     * </ul>
     */
    private SalesOrderRow merge(String key, List<WorkOrderResponse> parts,
                                Map<Long, Shipment> byId) {
        WorkOrderResponse head = parts.get(0);
        List<Shipment> entities = new ArrayList<>();
        List<Long> shipmentIds = new ArrayList<>();
        List<WorkOrderResponse.Line> lines = new ArrayList<>();
        LinkedHashSet<Integer> reqCds = new LinkedHashSet<>();

        String schoolCode = head.schoolCode();
        String schoolName = head.schoolName();
        String tradeClass = head.tradeClass();
        DeliveryType deliveryType = head.deliveryType();
        boolean allDeleted = true;
        long qty = 0;
        long amount = 0;
        LocalDate date = head.tradeDate();
        int tradeSeq = head.tradeSeq();

        for (WorkOrderResponse w : parts) {
            Shipment s = byId.get(w.id());
            if (s != null) {
                entities.add(s);
            }
            shipmentIds.add(w.id());
            lines.addAll(w.lines());
            reqCds.addAll(w.reqCds());
            allDeleted = allDeleted && w.deleted();
            qty += w.totalQty();
            for (WorkOrderResponse.Line l : w.lines()) {
                amount += l.amount();
            }
            if (!java.util.Objects.equals(schoolCode, w.schoolCode())) {
                schoolCode = null;
                schoolName = null;
            }
            if (!java.util.Objects.equals(tradeClass, w.tradeClass())) {
                tradeClass = null;
            }
            if (!java.util.Objects.equals(deliveryType, w.deliveryType())) {
                deliveryType = null;
            }
            if (w.tradeDate() != null && (date == null || w.tradeDate().isBefore(date))) {
                date = w.tradeDate();
            }
            tradeSeq = Math.min(tradeSeq, w.tradeSeq());
        }

        OrderState state = OrderProgress.groupState(entities);
        return new SalesOrderRow(key, tradeSeq, date,
                head.partnerCode(), head.partnerName(),
                schoolCode, schoolName, tradeClass,
                state.name(), state.label(), allDeleted,
                deliveryType, (deliveryType == null) ? null : deliveryType.label(),
                qty, amount, shipmentIds, new ArrayList<>(reqCds), lines);
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }
}
