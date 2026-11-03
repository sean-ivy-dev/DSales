package com.daesung.sales.dealer.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.dealer.dto.DealerOrderRequest;
import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
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
 */
@Service
@RequiredArgsConstructor
public class DealerOrderService {

    private final SaleService saleService;
    private final PartnerRepository partnerRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final ShipmentRepository shipmentRepository;
    private final com.daesung.sales.dealer.config.DealerSiteProperties properties;

    @Transactional
    public SalesEntryResponse accept(DealerOrderRequest req) {
        Partner partner = partnerRepository.findByCode(req.partnerCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                        "거래처를 찾을 수 없습니다: " + req.partnerCode()));
        LocalDate date = (req.orderDate() == null) ? LocalDate.now() : req.orderDate();
        Warehouse warehouse = defaultWarehouse();

        List<SalesEntryRequest.Item> items = new ArrayList<>();
        for (DealerOrderRequest.Item i : req.items()) {
            Product product = productRepository.findByCode(i.productCode())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                            "도서를 찾을 수 없습니다: " + i.productCode()));
            // ★가격 칸은 전부 null 로 넘긴다 — 서버가 거래처별 단가(V19) → 마스터 순으로 정한다
            //   (10/26 §1 "매출 금액은 매출프로그램 기준으로 계산한다").
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

        // onlySales·발송구분은 비워 둔다 — §2-10 "특약점 사이트에서는 고르지 않는다"(택배/화물
        // 선택 얘기다). 발송구분은 등록 쪽이 "아무것도 없으면 화물"로 정한다
        // (decideDeliveryTypeIfBlank — 다만 아래 담당자정보가 채워지면 택배로 자동 판정된다,
        // §8-6 "담당자명·연락처가 있을 때만 택배").
        //
        // ‼️§2-10 과 §4 "담당자 정보"는 **다른 요구사항**이다 — 전자는 발송구분(화물/택배)
        //   선택지 얘기고, 후자는 담당자명·연락처·이메일 세 칸 얘기다. 예전엔 이 둘을
        //   섞어서 "담당자정보도 비워 둔다"고 잘못 적었다(2026-11-03 전수 재대조로 발견).
        //
        // ★접수경로는 **서버가 ONLINE 으로 박는다**(프론트 I-2) — 요청으로 바꿀 수 없다.
        //   사이트에서 온 주문을 수기로 위장할 수 있으면 §2-1 의 "함께 보여주고"가 무의미해진다.
        SalesEntryRequest entry = new SalesEntryRequest(
                date, partner.getId(), warehouse.getId(),
                null, null,
                receiverOrDefault(req.receiverName(), partner.getBossName()),
                receiverOrDefault(req.receiverPhone(), partner.getTel1()),
                receiverOrDefault(req.receiverEmail(), partner.getEmail1()),
                com.daesung.sales.sale.entity.OrderChannel.ONLINE, items);
        SalesEntryResponse res = saleService.createEntries(entry);

        markReceived(res.entryNo());
        return res;
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
