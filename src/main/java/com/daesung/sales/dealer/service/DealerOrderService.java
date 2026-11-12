package com.daesung.sales.dealer.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.dealer.dto.DealerOrderRequest;
import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
import com.daesung.sales.order.dto.SalesOrderRow;
import com.daesung.sales.order.service.OrderProgress;
import com.daesung.sales.order.service.SalesOrderQueryService;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.sale.dto.SalesEntryRequest;
import com.daesung.sales.sale.dto.SalesEntryResponse;
import com.daesung.sales.sale.service.SaleService;
import com.daesung.sales.salestype.entity.ShipmentType;
import com.daesung.sales.warehouse.entity.Warehouse;
import com.daesung.sales.warehouse.entity.WarehouseType;
import com.daesung.sales.warehouse.repository.WarehouseRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 특약점 사이트 [새 주문] 탭 주문 접수 — <b>매출 주문으로 저장</b>한다.
 *
 * <p>근거: 10/27 명세 §1 — "§10-1 '신청 저장 구조'의 답은 <b>'새 탭 주문은 매출 주문으로
 * 저장'</b>이다. DSRE2 {@code tbl_request_info} 에 매출 도서를 담는 방식은 쓰지 않는다."
 *
 * <p>★<b>등록 로직을 새로 쓰지 않는다.</b> {@link SaleService#createEntries}를 그대로 부른다 —
 * 단가 자동적용(거래처별 단가 → 마스터)·재고 차감·작업요청서 생성·거래순번·전표번호가 전부
 * 거기 있다. 복붙하면 수기 주문과 특약점 주문이 다르게 동작하기 시작한다
 * (C-1 이 위탁출고를 주문 등록으로 모은 것과 같은 이유다).
 *
 * <p>접수 뒤 한 가지만 더 한다 — 진행상태를 <b>접수완료</b>로 세운다. §2-3(10/26)이
 * "A(접수완료)에서만 수정·삭제할 수 있다"를 요구하므로 그 상태가 실제로 있어야 한다.
 * 수기 등록(매출등록 화면)은 본사가 직접 넣은 것이라 접수 단계를 지나 <b>상품준비중</b>에서
 * 시작한다 — 그래서 수기 쪽에는 이 처리를 하지 않는다.
 *
 * <h3>조회·수정·삭제도 이 클래스가 한다(10/26 §2)</h3>
 * ★<b>처음엔 접수(만들기)만 만들고 이걸 빠뜨렸다</b> — "특약점 사이트는 레포가 없어 범위
 * 밖"이라고 치웠는데, 10/27 §1 이 "진행상태 제한·삭제 표시(§2)는 <b>새 탭 주문에 적용한다</b>"
 * 고 명시한다. 새 탭 주문은 <b>우리 시스템에 저장</b>되므로, 사이트가 못 만들어도 사이트가
 * 부를 <b>API는 우리 쪽에 있어야</b> 한다(2026-11-03 전수 재대조로 발견).
 *
 * <p>§2-3 — "<b>A(접수완료)에서만</b> 수정·삭제할 수 있다. G~C는 조회만 된다."
 * §2-4 — 그 외 상태에서 시도하면 <b>"주문 건의 수정 및 취소를 원하시는 경우 담당자에게
 * 확인 바랍니다."</b>
 * §2-6 — "수정·삭제 SQL에 STATE = 'A' 조건을 넣는다. <b>영향 행이 0이면 거부</b>한다."
 *
 * <p>★<b>남의 거래처 주문은 건드릴 수 없다.</b> 사이트 인증은 공유 키 하나뿐이고 어느
 * 거래처인지는 요청 바디가 말한다 — 그 말을 그대로 믿으면 다른 특약점의 주문을 고치거나
 * 지울 수 있다. 그래서 요청의 {@code partnerCode} 가 그 주문의 실제 거래처와
 * <b>같은지</b>를 매번 확인한다(없는 주문과 같은 메시지로 답한다 — PDF·거래명세서와 같은 규칙).
 */
@Service
@RequiredArgsConstructor
public class DealerOrderService {

    /** §2-4 알럿 문구. 접수완료가 아닐 때 수정·삭제를 시도하면 이 메시지로 거부한다. */
    private static final String EDIT_LOCKED_MESSAGE =
            "주문 건의 수정 및 취소를 원하시는 경우 담당자에게 확인 바랍니다.";

    private final SaleService saleService;
    private final PartnerRepository partnerRepository;
    private final ProductRepository productRepository;
    private final DealerProductService dealerProductService;
    private final WarehouseRepository warehouseRepository;
    private final ShipmentRepository shipmentRepository;
    private final SalesOrderQueryService salesOrderQueryService;
    private final com.daesung.sales.dealer.config.DealerSiteProperties properties;

    @Transactional
    public SalesEntryResponse accept(DealerOrderRequest req) {
        Partner partner = requirePartner(req.partnerCode());
        LocalDate date = (req.orderDate() == null) ? LocalDate.now() : req.orderDate();
        SalesEntryRequest entry = buildEntry(partner, date, defaultWarehouse().getId(), req);

        SalesEntryResponse res = saleService.createEntries(entry);
        markReceived(res.entryNo());
        return res;
    }

    /**
     * 본인 주문 목록(§2-1 "수기 주문과 온라인 주문을 함께 보여준다" 의 특약점 쪽 반사 — 자기
     * 것만 본다). 상태 라벨은 {@link SalesOrderRow#progressStateName} 그대로 쓰면 된다
     * (정본 6단계 명칭과 이미 같다).
     */
    @Transactional(readOnly = true)
    public Page<SalesOrderRow> list(String partnerCode, LocalDate from, LocalDate to,
                                    com.daesung.sales.dsre.gateway.OrderState state,
                                    int page, int size) {
        requirePartner(partnerCode);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        return salesOrderQueryService.orders(from, to, null, partnerCode, null, state, null, pageable);
    }

    /**
     * 본인 주문 수정(§2-3 "A 에서만"). 줄을 통째로 바꾼다 — 규칙은
     * {@link SaleService#replaceEntry} 와 같다(등록번호·거래순번 유지, 지우고 다시 넣기).
     */
    @Transactional
    public SalesEntryResponse edit(String orderNo, DealerOrderRequest req) {
        Partner partner = requirePartner(req.partnerCode());
        requireOwnedAndReceived(orderNo, partner);

        SalesEntryRequest entry = buildEntry(partner, orderDateOf(orderNo, req), defaultWarehouse().getId(), req);
        return saleService.replaceEntry(orderNo, entry);
    }

    /**
     * 신청 화면 노출 도서(10/26 §3). 판정은 {@link DealerProductService} 한 곳에만 있고,
     * 접수·수정의 거부 검증도 같은 것을 쓴다.
     */
    @Transactional(readOnly = true)
    public List<com.daesung.sales.dealer.dto.DealerProductRow> visibleProducts(String partnerCode) {
        return dealerProductService.visible(requirePartner(partnerCode).getId());
    }

    /**
     * 담당자 정보 기본값(10/26 §4). ★<b>{@code buildEntry} 의 기본값과 같은 출처</b>를 쓴다 —
     * 화면에 미리 채워 보여준 값과 비워 보냈을 때 저장되는 값이 갈리면 안 된다.
     */
    @Transactional(readOnly = true)
    public com.daesung.sales.dealer.dto.DealerContactResponse contact(String partnerCode) {
        return com.daesung.sales.dealer.dto.DealerContactResponse.from(requirePartner(partnerCode));
    }

    /** 본인 주문 삭제(§2-3·§2-9와 같은 규칙, 다만 거부 문구는 §2-4 전용). */
    @Transactional
    public int delete(String orderNo, String partnerCode) {
        Partner partner = requirePartner(partnerCode);
        requireOwnedAndReceived(orderNo, partner);
        return saleService.deleteEntry(orderNo, "특약점 사이트 삭제");
    }

    /**
     * 그 주문이 <b>이 거래처 것</b>이고 <b>접수완료</b> 상태인지 확인한다. 어느 쪽이든
     * 아니면 <b>같은 메시지</b>로 거부한다 — "남의 주문이라 거부"와 "상태가 달라 거부"를
     * 구분해서 알려주면, 전자가 "그 주문번호가 존재한다"는 사실을 알려주게 된다.
     */
    private void requireOwnedAndReceived(String orderNo, Partner partner) {
        if (orderNo == null || orderNo.startsWith("#")) {
            throw new BusinessException(ErrorCode.NOT_FOUND, EDIT_LOCKED_MESSAGE);
        }
        List<Shipment> shipments = shipmentRepository.findByOrderKeys(List.of(orderNo));
        boolean owned = !shipments.isEmpty() && shipments.stream()
                .allMatch(s -> s.getPartner() != null
                        && s.getPartner().getId().equals(partner.getId()));
        if (!owned) {
            throw new BusinessException(ErrorCode.NOT_FOUND, EDIT_LOCKED_MESSAGE);
        }
        if (OrderProgress.groupState(shipments) != OrderState.RECEIVED) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, EDIT_LOCKED_MESSAGE);
        }
    }

    /** 수정 때 거래일자를 바꾸면 거래순번 범위가 달라진다 — 요청에 없으면 원래 날짜를 쓴다. */
    private LocalDate orderDateOf(String orderNo, DealerOrderRequest req) {
        if (req.orderDate() != null) {
            return req.orderDate();
        }
        return shipmentRepository.findByOrderKeys(List.of(orderNo)).stream()
                .findFirst().map(Shipment::getTradeDate).orElseGet(LocalDate::now);
    }

    /**
     * {@link DealerOrderRequest} → {@link SalesEntryRequest}. 접수·수정이 <b>같은 변환</b>을
     * 쓴다 — 복붙하면 둘이 다르게 동작하기 시작한다.
     *
     * <p>★가격·창고·발송구분 칸은 전부 비워 둔다 — 10/26 §1 "매출 금액은 매출프로그램 기준으로
     * 계산한다", §2-10 "발송구분은 특약점 사이트에서 고르지 않는다". 담당자 정보(§4)만
     * "보낸 값 우선, 없으면 거래처 마스터"로 채운다.
     */
    private SalesEntryRequest buildEntry(Partner partner, LocalDate date, Long warehouseId,
                                         DealerOrderRequest req) {
        List<SalesEntryRequest.Item> items = new ArrayList<>();
        for (DealerOrderRequest.Item i : req.items()) {
            Product product = productRepository.findByCode(i.productCode())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                            "도서를 찾을 수 없습니다: " + i.productCode()));
            items.add(new SalesEntryRequest.Item(
                    product.getId(), ShipmentType.NORMAL_SHIP,
                    null, null, null,          // 정가·공급률·할인액 — 서버가 정한다
                    i.qty(),
                    null, null,                // 세액·금액 — 세액은 기본 0(자동산출 안 함), 금액은 계산
                    null,                      // 성적처리 구분 — 특약점 주문에는 없다
                    req.schoolCode(), req.schoolName(),
                    null, null,                // 회차·포장구분 — 사이트가 고르지 않는다
                    req.memo()));
        }

        // ★노출되지 않는 도서는 거부한다(특약점팀 요청 2026-11-03 §1 "접수·수정도 같은 계산으로").
        //   목록(GET /dealer-site/products)과 **같은 판정**을 쓴다 — 따로 두면
        //   "목록엔 없는데 주문은 되는" 구멍이나 그 반대가 생긴다.
        //   ‼️**도서 존재 확인(위 루프) 뒤에** 본다 — 순서를 바꾸면 아예 없는 도서코드에도
        //     "노출되지 않는 도서"라고 답해, 담당자가 마스터를 뒤지게 된다.
        dealerProductService.assertAllVisible(partner.getId(),
                req.items().stream().map(DealerOrderRequest.Item::productCode).toList());

        // onlySales·발송구분은 비워 둔다 — §2-10 "특약점 사이트에서는 고르지 않는다"(택배/화물
        // 선택 얘기다). 발송구분은 등록 쪽이 정하는데, **이 경로(channel=ONLINE)는 담당자정보가
        // 있어도 화물**이다(발주처 추가요청 J-1 2026-11-03). 아래 §4대로 담당자정보를 거래처
        // 마스터에서 자동으로 채우기 때문에, §8-6의 "담당자명·연락처가 있으면 택배"를 이 경로에도
        // 적용하면 운영 거래처 180곳 중 178곳 주문이 전부 택배가 된다 — §8-6이 버그로 지적한
        // 바로 그 증상이다. 택배로 보낼 건은 물류가 작업요청서 「발송 구분」에서 바꾼다.
        //
        // ‼️§2-10 과 §4 "담당자 정보"는 **다른 요구사항**이다 — 전자는 발송구분(화물/택배)
        //   선택지 얘기고, 후자는 담당자명·연락처·이메일 세 칸 얘기다. 예전엔 이 둘을
        //   섞어서 "담당자정보도 비워 둔다"고 잘못 적었다(2026-11-03 전수 재대조로 발견).
        //
        // ★접수경로는 **서버가 ONLINE 으로 박는다**(프론트 I-2) — 요청으로 바꿀 수 없다.
        //   사이트에서 온 주문을 수기로 위장할 수 있으면 §2-1 의 "함께 보여주고"가 무의미해진다.
        return new SalesEntryRequest(
                date, partner.getId(), warehouseId,
                null,                 // 위탁창고 — 특약점 신청은 정상출고다
                null, null,
                receiverOrDefault(req.receiverName(), partner.getBossName()),
                receiverOrDefault(req.receiverPhone(), partner.getTel1()),
                receiverOrDefault(req.receiverEmail(), partner.getEmail1()),
                // 배송지는 마스터 기본값이 없다 — 사이트가 학교 주소를 미리 채워 보낸다(§2).
                req.zipCode(), req.address(),
                com.daesung.sales.sale.entity.OrderChannel.ONLINE, items);
    }

    /**
     * 사이트가 보낸 거래처코드(DSRE 매출코드 {@code tbl_cust_info.MACHUL_CD})로 우리 거래처를 찾는다.
     *
     * <p>★못 찾으면 {@link ErrorCode#PARTNER_NOT_LINKED} 다 — {@code INVALID_INPUT} 이 아니다.
     * 사이트가 이 상황에만 "매출 거래처 연결이 되어 있지 않습니다" 안내를 띄우는데,
     * 예전엔 문구로 구분하고 있어서 우리가 메시지만 다듬어도 그 안내가 깨졌다
     * (특약점팀 전달 2026-11-03 §5 요청).
     */
    private Partner requirePartner(String partnerCode) {
        return partnerRepository.findByCode(partnerCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARTNER_NOT_LINKED,
                        "매출 거래처 연결이 되어 있지 않습니다: " + partnerCode));
    }

    /**
     * 접수 묶음의 작업요청서를 <b>접수완료</b>로 세운다.
     *
     * <p>‼️작업요청서가 없을 수도 있다(매출만·특강 등은 만들지 않는다). 그때는 세울 곳이
     * 없고, 그래도 접수 자체는 성공으로 둔다 — 주문은 이미 섰는데 상태를 못 적었다고
     * 전체를 되돌리면 특약점은 주문이 안 된 줄 알고 다시 넣는다.
     */
    private void markReceived(String entryNo) {
        if (entryNo == null || entryNo.isBlank()) {
            return;
        }
        for (Shipment shipment : shipmentRepository.findByEntryNo(entryNo)) {
            shipment.applyIntakeState(OrderState.RECEIVED);
        }
    }

    /**
     * 담당자 정보 — "보낸 값이 있으면 그대로, 없으면 거래처 마스터 기본값"(10/26 §4).
     *
     * <p>★<b>레거시 매핑을 실측으로 확인했다</b>(DSRE2 {@code tbl_cust_info} DDL — 담당자명에
     * 해당하는 칸이 따로 없고 <b>대표자명({@code OWNER_NM})</b>·연락처({@code TEL_NO1})·
     * 이메일({@code EMAIL1})을 쓴다). 우리 {@code Partner} 의 {@code bossName}·{@code tel1}·
     * {@code email1} 이 그 대응이다.
     *
     * <p>★<b>빈 문자열("")과 null 을 다르게 본다</b> — null 이면 "안 보냈다"(기본값으로
     * 채운다), 빈 문자열이면 "일부러 지웠다"(그대로 빈 값으로 저장한다). 사이트가 프리필된
     * 값을 지우고 제출하면 그 의도를 존중해야 한다.
     */
    private static String receiverOrDefault(String sent, String masterDefault) {
        return (sent != null) ? sent : masterDefault;
    }

    /**
     * 특약점 주문이 나갈 <b>물류창고</b>. 특약점은 창고를 고르지 않으므로 서버가 정한다.
     *
     * <p>순서가 중요하다 —
     * <ol>
     *   <li>설정({@code daesung.dealer-site.warehouse-code})이 있으면 그 창고. 운영은 이쪽이다.</li>
     *   <li>없으면 실물재고 물류창고(MAIN·사용중)가 <b>하나일 때만</b> 그것.</li>
     *   <li>둘 이상인데 설정이 없으면 <b>거부하고 사유를 말한다</b>.</li>
     * </ol>
     *
     * <p>‼️2번을 "여러 개 중 아무거나"로 하지 않는 이유: 재고가 엉뚱한 창고에서 빠지고
     * 수불부가 조용히 틀어진다. 틀린 답을 주는 것보다 못 받는 게 낫다.
     * ★처음엔 2번만 뒀는데, 물류창고가 둘인 환경에서 <b>모든 특약점 주문이 실패</b>했다 —
     * 설정으로 못 박을 길이 반드시 있어야 한다.
     */
    private Warehouse defaultWarehouse() {
        if (properties.warehouseCode() != null) {
            return warehouseRepository.findByCode(properties.warehouseCode())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                            "특약점 주문용 창고 설정이 가리키는 창고가 없습니다: "
                                    + properties.warehouseCode()
                                    + " (daesung.dealer-site.warehouse-code)"));
        }
        List<Warehouse> candidates = new ArrayList<>();
        for (Warehouse w : warehouseRepository.findAll()) {
            if (w.getType() == WarehouseType.MAIN && w.isPhysicalStock() && w.isUseYn()) {
                candidates.add(w);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT, candidates.isEmpty()
                ? "특약점 주문을 받을 물류창고가 없습니다. 창고관리에서 실물재고 물류창고를 등록하세요."
                : "실물재고 물류창고가 " + candidates.size() + "개여서 어느 창고에서 낼지 정할 수 없습니다. "
                        + "daesung.dealer-site.warehouse-code 에 창고코드를 지정하세요.");
    }
}
