package com.daesung.sales.dealer.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.dealer.dto.DealerShippedOrderRow;
import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.logistics.entity.DeliveryType;
import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.entity.ShipmentTracking;
import com.daesung.sales.logistics.repository.ShipmentRepository;
import com.daesung.sales.order.dto.SalesOrderRow;
import com.daesung.sales.order.service.OrderProgress;
import com.daesung.sales.order.service.SalesOrderQueryService;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.sale.dto.TransactionStatementResponse;
import com.daesung.sales.sale.service.SaleReportService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 특약점 사이트용 <b>송장 조회(§4)</b>·<b>거래명세서(§5)</b>.
 *
 * <p>§5 — "<b>조회·출력 전용</b>이다. 표시 대상: 매출에서 <b>발송완료(D)</b> 처리된 건부터
 * 보여준다. 발송준비중까지는 내부에서 수정될 수 있으므로 표시하지 않는다.
 * 양식: 매출프로그램 거래명세서와 <b>동일</b>하다(기존 양식과 출력 로직을 재사용).
 * 권한: <b>로그인한 거래처 본인 건만</b> 보여준다. 거래처 필터는 서버 세션 기준으로 건다.
 * URL이나 파라미터로 받은 거래처코드는 믿지 않는다(Phase 0 order IDOR과 같은 유형)."
 *
 * <h3>그 '서버 세션 기준'을 우리가 어떻게 지키나</h3>
 * 이 API 를 부르는 것은 브라우저가 아니라 <b>사이트 서버</b>다(§10-3 "사이트 서버가 매출 API를
 * 서버 간 호출"). 특약점 로그인 세션을 들고 있는 쪽이 그 서버이므로, 어느 거래처인지는
 * 그 서버가 말해 준다 — 우리는 <b>그 서버인지</b>를 전용 키로 확인한다
 * ({@link com.daesung.sales.dealer.config.DealerSiteKeyFilter}).
 * §5 가 금지한 것은 <b>브라우저가 준 거래처코드를 그대로 쓰는 것</b>이고, 그건 사이트가
 * 지킬 몫이다. 우리 쪽에서는 <b>주문이 그 거래처 것인지</b>를 매번 확인한다 —
 * 남의 주문번호를 넣어도 "없음"으로 답한다(존재 자체를 숨긴다. PDF 보관함과 같은 규칙).
 *
 * <h3>조회 로직을 새로 쓰지 않는다</h3>
 * 목록은 {@link SalesOrderQueryService}(주문조회)에 <b>발송완료 필터</b>를 걸어 받고,
 * 명세서는 {@link SaleReportService#transactionStatement} 를 그대로 부른다 —
 * §5 가 "기존 양식과 출력 로직을 <b>재사용</b>"하라고 못 박았고, 복붙하면 백오피스
 * 명세서와 사이트 명세서가 달라진다.
 */
@Service
@RequiredArgsConstructor
public class DealerStatementService {

    /** 사이트에 한 번에 내릴 수 있는 주문 수. 그 위는 기간을 좁혀야 한다. */
    private static final int MAX_ROWS = 500;

    private final PartnerRepository partnerRepository;
    private final ShipmentRepository shipmentRepository;
    private final SalesOrderQueryService salesOrderQueryService;
    private final SaleReportService saleReportService;

    /**
     * 그 거래처의 <b>발송완료</b> 주문 목록(송장 포함).
     *
     * @param deliveryType 배송구분 필터. 송장 메뉴는 {@code COURIER} 로 걸러 부른다(§4 표시 조건)
     */
    @Transactional(readOnly = true)
    public List<DealerShippedOrderRow> shippedOrders(String partnerCode, LocalDate from,
                                                     LocalDate to, DeliveryType deliveryType) {
        Partner partner = requirePartner(partnerCode);

        List<SalesOrderRow> orders = salesOrderQueryService.orders(
                from, to, partner.getId(), null, null, OrderState.SHIPPED,
                PageRequest.of(0, MAX_ROWS)).getContent();

        List<DealerShippedOrderRow> out = new ArrayList<>();
        for (SalesOrderRow o : orders) {
            if (deliveryType != null && !deliveryType.name().equals(
                    (o.deliveryType() == null) ? null : o.deliveryType().name())) {
                continue;
            }
            out.add(new DealerShippedOrderRow(o.orderNo(), o.orderDate(), o.schoolName(),
                    (o.deliveryType() == null) ? null : o.deliveryType().name(),
                    o.deliveryTypeName(), o.totalQty(), o.totalAmount(),
                    trackingsOf(o.shipmentIds())));
        }
        return out;
    }

    /**
     * 거래명세서 단건. <b>발송완료</b>이고 <b>그 거래처의</b> 주문일 때만 답한다.
     *
     * <p>‼️명세서는 (거래처 × <b>학교</b>) 단위로 발행된다 — 기존 로직의 규칙 그대로다.
     * 주문 안에 학교가 섞여 있으면 학교를 특정할 수 없어 거부한다(섞인 채로 뽑으면
     * 「학교(원)」 칸에 첫 학교만 찍히고 나머지가 그 학교 몫처럼 보인다).
     */
    @Transactional(readOnly = true)
    public TransactionStatementResponse statement(String partnerCode, String orderNo) {
        Partner partner = requirePartner(partnerCode);
        List<Shipment> shipments = shipmentRepository.findByOrderKeys(List.of(orderNo));

        // ★본인 것이 아니거나 없으면 **같은 답**을 준다 — "남의 주문은 있다"를 알려주지 않는다.
        List<Shipment> mine = new ArrayList<>();
        for (Shipment s : shipments) {
            if (s.getPartner() != null && partner.getId().equals(s.getPartner().getId())) {
                mine.add(s);
            }
        }
        if (mine.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "그 주문을 찾을 수 없습니다.");
        }

        OrderState state = OrderProgress.groupState(mine);
        if (state != OrderState.SHIPPED) {
            // §5 "발송준비중까지는 내부에서 수정될 수 있으므로 표시하지 않는다."
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "발송완료된 주문부터 거래명세서를 볼 수 있습니다. 현재 상태=" + state.label());
        }

        String schoolCode = mine.get(0).getSchoolCode();
        LocalDate date = mine.get(0).getTradeDate();
        for (Shipment s : mine) {
            if (!java.util.Objects.equals(schoolCode, s.getSchoolCode())) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "한 주문에 학교가 섞여 있어 명세서를 특정할 수 없습니다. 담당자에게 문의하세요.");
            }
            if (s.getTradeDate() != null && (date == null || s.getTradeDate().isBefore(date))) {
                date = s.getTradeDate();
            }
        }
        // 기존 양식·로직을 그대로 쓴다(§5 "재사용"). 기간은 그 주문의 거래일자 하루다.
        return saleReportService.transactionStatement(partner.getId(), date, date, null, schoolCode);
    }

    private Partner requirePartner(String partnerCode) {
        return partnerRepository.findByCode(partnerCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                        "거래처를 찾을 수 없습니다: " + partnerCode));
    }

    private List<DealerShippedOrderRow.Tracking> trackingsOf(List<Long> shipmentIds) {
        List<DealerShippedOrderRow.Tracking> out = new ArrayList<>();
        for (Long id : shipmentIds) {
            Shipment s = shipmentRepository.findByIdIncludingDeleted(id).orElse(null);
            if (s == null) {
                continue;
            }
            for (ShipmentTracking t : s.getTrackings()) {
                out.add(new DealerShippedOrderRow.Tracking(
                        t.getBoxNo(), t.getCourierName(), t.getTrackingNo()));
            }
        }
        out.sort(java.util.Comparator.comparingInt(DealerShippedOrderRow.Tracking::boxNo));
        return out;
    }
}
