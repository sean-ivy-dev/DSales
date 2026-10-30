package com.daesung.sales.sale.service;

import com.daesung.sales.audit.entity.StatusEntityType;
import com.daesung.sales.audit.service.StatusHistoryService;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.money.Amounts;
import com.daesung.sales.common.query.MultiSelect;
import com.daesung.sales.closing.service.PeriodLockService;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.common.sequence.SequenceService;
import com.daesung.sales.inventory.entity.TxnType;
import com.daesung.sales.inventory.service.InventoryService;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.entity.SalesDivision;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.product.repository.SalesDivisionRepository;
import com.daesung.sales.sale.dto.ReturnInboundRequest;
import com.daesung.sales.sale.dto.ReturnableAgg;
import com.daesung.sales.sale.dto.ReturnableResponse;
import com.daesung.sales.sale.dto.SaleResponse;
import com.daesung.sales.sale.dto.SaleUpdateRequest;
import com.daesung.sales.sale.dto.SalesEntryRequest;
import com.daesung.sales.sale.dto.SalesEntryResponse;
import com.daesung.sales.sale.entity.Sale;
import com.daesung.sales.sale.entity.SalesType;
import com.daesung.sales.sale.repository.SaleRepository;
import com.daesung.sales.salestype.entity.SalesCategory;
import com.daesung.sales.salestype.entity.ShipmentType;
import com.daesung.sales.salestype.entity.TradeClass;
import com.daesung.sales.salestype.service.OutTypeLookupService;
import com.daesung.sales.warehouse.entity.Warehouse;
import com.daesung.sales.warehouse.repository.WarehouseRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class SaleService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final SaleRepository saleRepository;
    private final EntryNumberService entryNumberService;
    private final PartnerRepository partnerRepository;
    private final ProductRepository productRepository;
    private final com.daesung.sales.product.service.PartnerSupplyRateService partnerSupplyRateService;
    private final SalesDivisionRepository salesDivisionRepository;
    private final OutTypeLookupService outTypeLookupService;
    private final WarehouseRepository warehouseRepository;
    private final InventoryService inventoryService;
    private final com.daesung.sales.inventory.service.StockWarningCollector stockWarningCollector;
    private final PeriodLockService periodLockService;
    private final StatusHistoryService statusHistoryService;
    private final com.daesung.sales.common.audit.CurrentAuditor currentAuditor;
    private final SequenceService sequenceService;
    private final com.daesung.sales.logistics.service.ShipmentService shipmentService;
    private final com.daesung.sales.school.repository.SchoolRepository schoolRepository;

    /**
     * 수기 매출 등록(일반 매출) + 재고 반영을 한 트랜잭션으로. 품목마다 금액 산출 → 매출번호(I) 채번 →
     * 매출 원장 기록 + 물류창고 재고 반영(정상출고=−차감/음수재고 방지, 반품=+복구). 위탁출고는 이 API 불가.
     * 반품(RETURN) 라인은 반품입고(29p)와 동일한 교재식 범위검증을 거친다(진입점 무관 동일 규칙).
     */
    @Transactional
    public SalesEntryResponse createEntries(SalesEntryRequest req) {
        periodLockService.assertNotLocked(req.salesDate());
        Partner partner = partnerRepository.findById(req.partnerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "거래처가 없습니다. id=" + req.partnerId()));
        // ★창고는 선택이다 — '미출고 매출'(문항사용료·학원매출 등)은 실물이 안 나간다.
        //   재고를 건드리지 않고 매출만 세우며, 거래명세서·물류 작업으로도 안 넘어간다.
        Warehouse warehouse = (req.warehouseId() == null) ? null
                : warehouseRepository.findById(req.warehouseId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                                "물류창고가 없습니다. id=" + req.warehouseId()));

        // 1단계: 라인 해석(상품·회계구분·정가·공급률 자동적용). 저장 전에 전 라인을 확정해야
        //        반품 범위검증을 요청 단위로(순서 무관) 판정할 수 있다.
        List<ResolvedItem> resolved = new ArrayList<>(req.items().size());
        for (SalesEntryRequest.Item item : req.items()) {
            resolved.add(resolve(item, partner));
        }
        // ★특강 여부 캐시(10/27 피드백 §3-4·4-12) — 세부구분 코드마다 한 번만 조회한다.
        //   같은 코드가 요청 안에 여러 번 나올 수 있어 매 아이템 조회는 불필요한 N+1이다.
        Map<String, Boolean> specialLectureCache = new HashMap<>();
        assertWarehouseWhenStockMoves(req, warehouse);
        List<SalesEntryResponse.Warning> warnings = collectReturnWarnings(partner.getId(), resolved);

        // 2단계: 저장 + 재고 반영.
        String datePart = req.salesDate().format(YYYYMMDD);
        // ★등록 묶음번호(현장 피드백 2026-10-14 B-4) — "여러 매출을 등록했을 때 등록 순서로 순번".
        //   매출번호(I-)는 줄마다 채번돼 "한 번에 올린 것"을 가리키지 못한다.
        //   묶음번호 하나를 먼저 뽑고, 줄마다 1,2,3… 을 붙인다.
        String entryNo = entryNumberService.next(req.salesDate());
        int entrySeq = 0;
        // ★거래순번은 **등록 한 번에 한 번만** 뽑는다(10/27 명세 §3-6 "하루에 5번 주문하면 1~5").
        //   예전엔 발송(작업요청서)이 생길 때마다 뽑아, 같은 날 같은 학교면 발송이 한 건에
        //   합쳐지면서 순번도 1개뿐이었다(프론트 B-2② 지적). 여기서 한 번 정해
        //   이 묶음의 모든 줄과 그 줄이 만든 발송이 **같은 번호**를 쓴다.
        int tradeSeq = shipmentService.nextTradeSeq(req.partnerId(), req.salesDate());
        List<SalesEntryResponse.Line> lines = new ArrayList<>();

        for (ResolvedItem r : resolved) {
            SalesEntryRequest.Item item = r.item();
            Product product = r.product();

            Amounts amt = Amounts.of(r.unitPrice(), r.supplyRate(), item.qty(), product.isTaxFree(),
                    item.tax(), r.discountAmount());
            long supplyAmount = amt.supplyAmount();
            long tax = amt.tax();
            long totalAmount = amt.totalAmount();

            String salesNo = "I-" + datePart + "-" + sequenceService.next(SequenceService.SEQ_INVOICE);

            Sale sale = Sale.create(salesNo, req.salesDate(), partner, product,
                    SalesType.NORMAL_SALES, item.shipmentType(), r.salesCategory(),
                    r.unitPrice(), r.supplyRate(), item.qty(),
                    supplyAmount, tax, totalAmount, item.procType(), item.memo());
            // ★공급가액을 직접 입력했으면 계산값을 덮는다(현장 피드백 2026-10-14 B-3).
            //   정가·공급률은 입력한 그대로 둔다 — 금액에 맞춰 역산하면 담당자가 적어 둔 조건이 사라진다.
            if (item.supplyAmount() != null) {
                sale.overrideSupplyAmount(item.supplyAmount());
                supplyAmount = item.supplyAmount();
                totalAmount = supplyAmount + tax;
            }
            sale.applyDiscount(r.discountAmount());   // 적용된 할인액을 남긴다(매핑은 나중에 바뀐다)
            sale.applyWarehouse(warehouse);   // 출고 창고(7p 재고위치 · 27p 출고창고)
            sale.applyUploadDetail(item.schoolCode(), item.schoolName(), item.round());   // 학교·회차(12p)
            sale.applyPackType(item.packType());   // 포장구분(회차별 작업현황 집계축)
            sale.applyEntry(entryNo, ++entrySeq);  // 등록 묶음·순번(B-4)
            sale.applyTradeSeq(tradeSeq);          // 거래순번(주문 단위, §3-6)
            saleRepository.save(sale);

            // 재고 반영(한 트랜잭션): 출고유형 → 부호/이벤트유형. 위탁·취소는 이 API 불가.
            // 재고 미관리 상품(모의고사 등 인원기반)은 차감·이벤트 없음. shipmentType 유효성은 항상 검사.
            int delta = stockDelta(item.shipmentType(), item.qty());
            int stockBalance = 0;
            if (warehouse != null && product.isStockManaged()) {
                TxnType txnType = (delta >= 0) ? TxnType.RETURN : TxnType.OUTBOUND;
                stockBalance = inventoryService.applyShipment(product, warehouse, delta, txnType,
                        item.shipmentType(), req.salesDate(), salesNo, item.memo());
            }

            // 물류 작업 단위(발송 건) 확보 — 레거시도 매출등록 시점에 sendData를 만든다(UC_TabPages.vb:752).
            // 반품은 들어오는 물건이라 내보낼 작업이 없다.
            // ★미출고 매출(창고 없음)도 만들지 않는다 — 내보낼 물건이 없는데 작업지시가 서면
            //   물류 대기목록에 영원히 안 끝나는 건이 쌓인다(정본 13p "거래명세서·물류 연계 제외").
            // ★「매출만(배송X)」이면 작업지시를 만들지 않는다 — 레거시 CheckBox_OnlySales 가
            //   하던 일이 정확히 이것이다(UC_TabPages.vb:749, sendData INSERT 건너뜀).
            //   재고는 평소대로 빠진다 — 창고를 비우는 것과는 다른 축이다.
            // ★특강도 작업지시를 만들지 않는다(10/27 피드백 §3-4·4-12) — 레거시에서 특강은
            //   작업요청서·출고/반품 조회에 안 나오고 통합 매출 조회에만 기록된다.
            //   재고는 매출만(배송X)과 같은 축으로, 평소대로 빠진다.
            boolean isSpecialLecture = specialLectureCache.computeIfAbsent(product.getSalesDivision(),
                    code -> (code != null)
                            && salesDivisionRepository.findByCode(code)
                                    .map(d -> d.getMajorCategory() == com.daesung.sales.product.entity.MajorCategory.SPECIAL_LECTURE)
                                    .orElse(false));
            if (r.salesCategory() != SalesCategory.RETURN && warehouse != null
                    && !Boolean.TRUE.equals(req.onlySales()) && !isSpecialLecture) {
                var shipment = shipmentService.ensureFor(sale);
                // ★비어 있을 때만 채운다 — 발주처 질의 2026-10-26 ①. 자세한 이유는
                //   ShipmentService.applyDeliveryIfBlank/Shipment.fillDeliveryIfBlank 주석 참고.
                if (req.deliveryType() != null || req.receiverName() != null || req.receiverPhone() != null) {
                    shipmentService.applyDeliveryIfBlank(shipment,
                            req.deliveryType(), req.receiverName(), req.receiverPhone());
                }
                // ★명시적 지정·담당자정보 적용을 끝낸 뒤 기본값을 확정한다(10/27 피드백 §8-6).
                //   담당자정보가 있으면 택배, 그 외엔 화물 — 위 if문을 안 탄 경우(셋 다 null)도
                //   포함해 항상 호출해야 "기본은 화물"이 실제로 적용된다.
                shipmentService.decideDeliveryTypeIfBlank(shipment);
            }

            lines.add(new SalesEntryResponse.Line(salesNo, product.getId(), product.getCode(),
                    item.shipmentType(), r.salesCategory(), item.qty(), supplyAmount, tax, totalAmount, stockBalance));
        }
        // ★재고 경고(음수)를 같은 배열에 합친다 — 화면이 한 곳만 보면 되도록.
        stockWarningCollector.drain().forEach(w -> warnings.add(SalesEntryResponse.Warning.ofStock(w)));
        return new SalesEntryResponse(partner.getId(), partner.getName(), lines, warnings);
    }

    /** 매출등록 라인 해석 결과(정가·공급률 자동적용까지 확정된 상태). */
    private record ResolvedItem(SalesEntryRequest.Item item, Product product,
                                SalesCategory salesCategory, int unitPrice, int supplyRate,
                                Integer discountAmount) {
    }

    /**
     * 상품 조회 + 회계구분 룩업 + 정가·공급률 자동적용.
     *
     * <p>공급률 우선순위: <b>입력값 &gt; 거래처별 매핑(거래처×대분류, 34p) &gt; 도서 기본 공급률</b>.
     * 도서 기본값이 마지막 바탕인 이유는, 발주처가 공급률을 거래처구분별 대표값으로만 운영하기
     * 때문이다(자료요청서 1-2). 바탕값이 없으면 등록을 하려고 전 거래처×전 도서 매핑을 깔아야 한다.
     */
    private ResolvedItem resolve(SalesEntryRequest.Item item, Partner partner) {
        Product product = productRepository.findById(item.productId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "상품이 없습니다. id=" + item.productId()));
        SalesCategory salesCategory = SalesCategory.valueOf(
                outTypeLookupService.salesCategoryNameOf(item.shipmentType()));

        Integer unitPrice = (item.unitPrice() != null) ? item.unitPrice() : product.getPrice();
        Integer supplyRate = item.supplyRate();
        if (supplyRate == null) {
            Integer mapped = partnerSupplyRateService.rateFor(product, partner.getId());
            supplyRate = (mapped != null) ? mapped : product.getSupplyRate();
        }
        if (unitPrice == null || supplyRate == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "정가·공급률이 없고 거래처별 단가 매핑도, 도서 기본 공급률도 없습니다. 상품="
                            + product.getCode());
        }
        // 할인액도 같은 우선순위(입력값 > 거래처×대분류 매핑). 있으면 공급률 대신 금액에 쓰인다.
        Integer discount = (item.discountAmount() != null)
                ? item.discountAmount() : partnerSupplyRateService.discountFor(product, partner.getId());
        return new ResolvedItem(item, product, salesCategory, unitPrice, supplyRate, discount);
    }

    /**
     * 매출등록 요청 내 반품(RETURN) 라인의 교재식 <b>초과 확인</b>. 반품 라인이 없으면 조회조차 하지 않는다.
     * 같은 요청의 판매출고(SALE)는 반품가능수량에 선반영 — 최종 장부 기준으로 보므로 라인 순서와 무관.
     *
     * <p>★막지 않고 <b>경고를 모아 돌려준다</b>(발주처 2026-08-31 화면28). {@code consumeReturnable} 참고.
     */
    private List<SalesEntryResponse.Warning> collectReturnWarnings(Long partnerId, List<ResolvedItem> resolved) {
        List<SalesEntryResponse.Warning> warnings = new ArrayList<>();
        boolean hasReturn = resolved.stream().anyMatch(r -> r.salesCategory() == SalesCategory.RETURN);
        if (!hasReturn) {
            return warnings;
        }
        Map<String, Long> returnable = loadReturnable(partnerId);
        for (ResolvedItem r : resolved) {
            if (r.salesCategory() == SalesCategory.SALE) {
                returnable.merge(returnableKey(r.product().getId()), (long) r.item().qty(), Long::sum);
            }
        }
        for (ResolvedItem r : resolved) {
            if (r.salesCategory() == SalesCategory.RETURN) {
                SalesEntryResponse.Warning w =
                        consumeReturnable(returnable, r.product(), r.item().qty());
                if (w != null) {
                    warnings.add(w);
                }
            }
        }
        return warnings;
    }

    /**
     * 반품입고(29p 물류 진입점): 한 트랜잭션으로 매출 반품(RETURN) 라인 생성 + 물류창고 재고 복구.
     * 출고유형은 항상 RETURN(담당자가 선택 안 함). 재고관리 상품만 재고 +복구(모의고사 등은 이벤트 없음).
     * 근거: 요구사항 29p — 매출프로그램 단독 사용 시 반품 등록 원천, 8p 자동이고와 동일한 트랜잭션 원자성.
     */
    @Transactional
    public SalesEntryResponse returnInbound(ReturnInboundRequest req) {
        periodLockService.assertNotLocked(req.returnDate());
        Partner partner = partnerRepository.findById(req.partnerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "거래처가 없습니다. id=" + req.partnerId()));
        Warehouse warehouse = warehouseRepository.findById(req.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "물류창고가 없습니다. id=" + req.warehouseId()));

        String datePart = req.returnDate().format(YYYYMMDD);
        // 반품입고도 한 번에 여러 줄이 들어간다 — 묶음번호·순번을 붙인다(프론트 실측 2026-10-16 B-2).
        String entryNo = entryNumberService.next(req.returnDate());
        int entrySeq = 0;
        // 반품입고도 등록 한 번이 거래 한 건이다 — 같은 규칙으로 거래순번을 붙인다.
        // (반품은 발송을 만들지 않지만, 조회 조건 "거래처+일자+거래순번"이 매출 줄을 본다)
        int tradeSeq = shipmentService.nextTradeSeq(req.partnerId(), req.returnDate());
        List<SalesEntryResponse.Line> lines = new ArrayList<>();
        List<SalesEntryResponse.Warning> warnings = new ArrayList<>();

        // 교재식 반품: 거래처의 도서×정가×공급률별 반품가능수량(누적 출고−기반품) 맵. 한 요청 내 여러 라인은 누적 차감.
        Map<String, Long> returnable = loadReturnable(req.partnerId());

        for (ReturnInboundRequest.Item item : req.items()) {
            Product product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "상품이 없습니다. id=" + item.productId()));

            SalesEntryResponse.Warning w = consumeReturnable(returnable, product, item.qty());
            if (w != null) {
                warnings.add(w);
            }

            // 반품도 출고와 같은 기준으로 계산되어야 한다 — 할인 매출을 정가 기준으로 되돌리면
            // 반품 금액이 원래 판 금액보다 커진다.
            Integer discount = partnerSupplyRateService.discountFor(product, partner.getId());
            Amounts amt = Amounts.of(item.unitPrice(), item.supplyRate(), item.qty(),
                    product.isTaxFree(), item.tax(), discount);
            long supplyAmount = amt.supplyAmount();
            long tax = amt.tax();
            long totalAmount = amt.totalAmount();

            String salesNo = "I-" + datePart + "-" + sequenceService.next(SequenceService.SEQ_INVOICE);

            // 반품 = shipmentType/회계구분 RETURN 고정. 금액·수량은 양수 저장, 리포트가 반품으로 차감.
            Sale sale = Sale.create(salesNo, req.returnDate(), partner, product,
                    SalesType.NORMAL_SALES, ShipmentType.RETURN, SalesCategory.RETURN,
                    item.unitPrice(), item.supplyRate(), item.qty(),
                    supplyAmount, tax, totalAmount, null, item.memo());
            sale.applyDiscount(discount);
            if (item.sourceOutNo() != null && !item.sourceOutNo().isBlank()) {
                sale.linkSourceOut(item.sourceOutNo());
            }
            sale.applyEntry(entryNo, ++entrySeq);
            sale.applyTradeSeq(tradeSeq);
            saleRepository.save(sale);

            // 물류창고 재고 +복구(한 트랜잭션). 재고 미관리 상품(모의고사 등)은 이벤트 없음.
            int stockBalance = 0;
            if (product.isStockManaged()) {
                stockBalance = inventoryService.applyShipment(product, warehouse, item.qty(), TxnType.RETURN,
                        ShipmentType.RETURN, req.returnDate(), salesNo, item.memo());
            }

            lines.add(new SalesEntryResponse.Line(salesNo, product.getId(), product.getCode(),
                    ShipmentType.RETURN, SalesCategory.RETURN, item.qty(), supplyAmount, tax, totalAmount, stockBalance));
        }
        // ★재고 경고(음수)를 같은 배열에 합친다 — 화면이 한 곳만 보면 되도록.
        stockWarningCollector.drain().forEach(w -> warnings.add(SalesEntryResponse.Warning.ofStock(w)));
        return new SalesEntryResponse(partner.getId(), partner.getName(), lines, warnings);
    }

    /**
     * 반품가능내역 맵 키: <b>도서 단위</b>.
     *
     * <p>정가·공급률을 키에 넣지 않는다 — 발주처 확정(2026-08-05 데이터구조 확정사항 2.2):
     * "공급률은 원 출고건 값을 기준으로 <b>표시</b>하되, 담당자가 필요 시 <b>수정 가능</b>해야 함(고정·잠금 아님)".
     * 키에 공급률이 있으면 담당자가 값을 바꾸는 순간 원 출고건을 못 찾아 반품이 거부된다(=사실상 잠금).
     * 범위 판정은 "거래처의 그 도서를 얼마나 내보냈나"만 보고, 정가·공급률은 입력값을 그대로 쓴다.
     */
    private static String returnableKey(Long productId) {
        return String.valueOf(productId);
    }

    /**
     * 거래처의 <b>도서별</b> 반품가능수량(누적 판매출고 − 기반품) 맵.
     * 교재식 반품 규칙의 단일 소스 — 반품입고(29p)·매출등록 RETURN 라인이 모두 이걸 쓴다.
     * 같은 도서가 정가·공급률 달리 여러 번 출고됐어도 도서 단위로 합산한다.
     */
    private Map<String, Long> loadReturnable(Long partnerId) {
        Map<String, Long> returnable = new HashMap<>();
        for (ReturnableAgg a : saleRepository.returnableAgg(partnerId, null)) {
            returnable.merge(returnableKey(a.getProductId()),
                    a.getSaleQty() - a.getReturnQty(), Long::sum);
        }
        return returnable;
    }

    /**
     * 반품 1건 <b>초과 여부 확인</b> + 잔여 차감(한 요청 내 여러 라인은 누적 차감).
     *
     * <p>★<b>막지 않는다.</b> 발주처 화면검토(2026-08-31) 화면28 —
     * "출고내역보다 반품 등록 내역이 더 많이 입력되는 경우 <b>경고 알림(alert)</b>을 넣어주시기 바랍니다."
     * 경고를 요구했지 차단을 요구하지 않았고, 같은 회신에서 재고 음수·초과정산 차단도 함께 걷어냈다(§1).
     * 현장에서는 컷오버 전 출고분이나 다른 경로로 나간 물건이 반품으로 들어온다 —
     * 막으면 실제로 들어온 물건을 장부에 못 적는다.
     *
     * <p>대신 <b>초과분을 숨기지 않는다</b>: 응답에 경고를 담고 서버 로그에도 남긴다.
     * 조용히 통과시키면 담당자는 자기가 초과 입력한 줄 모른다.
     *
     * @return 초과했으면 경고, 아니면 null
     */
    private SalesEntryResponse.Warning consumeReturnable(Map<String, Long> returnable,
                                                         Product product, int qty) {
        String key = returnableKey(product.getId());
        long remain = returnable.getOrDefault(key, 0L);
        returnable.put(key, remain - qty);        // 초과분은 음수로 남는다(다음 라인이 이어서 차감)
        if (qty <= remain) {
            return null;
        }
        long over = qty - Math.max(remain, 0L);
        log.warn("반품 초과(차단 안 함, 발주처 2026-08-31): productId={} 반품가능={} 요청={} 초과={}",
                product.getId(), remain, qty, over);
        return new SalesEntryResponse.Warning("RETURN_EXCEEDS",
                product.getId(), product.getCode(), Math.max(remain, 0L), qty, over,
                "반품 수량이 출고 잔여를 초과했습니다. 도서 " + product.getCode()
                        + " → 반품가능 " + Math.max(remain, 0L) + ", 요청 " + qty + " (초과 " + over + ")");
    }

    /**
     * 교재식 반품 가능내역 조회. 거래처(옵션 도서)의 도서×정가×공급률 단위 출고내역과 반품가능수량.
     *
     * <p>정가·공급률은 <b>원 출고건 값을 화면에 표시</b>해 주기 위한 참고값이다.
     * 담당자가 반품 등록 시 다른 값으로 바꿔도 되고(발주처 확정 2.2), 범위 판정은 도서 단위로만 한다.
     */
    @Transactional(readOnly = true)
    public ReturnableResponse returnable(Long partnerId, Long productId) {
        // 도서 단위로 합산한다 — 범위 판정과 같은 기준이어야 화면의 '반품가능'과 실제 허용치가 어긋나지 않는다.
        // 정가·공급률은 그 도서의 출고 중 수량이 가장 많은 건의 값을 대표로 보여 준다(입력 기본값 용도).
        Map<Long, ReturnableResponse.Row> byProduct = new LinkedHashMap<>();
        Map<Long, Long> repQty = new HashMap<>();
        // ★합치기 전의 출고조건별 내역도 같이 담는다. 화면이 "출고조건(정가·공급률)별로 나뉜
        //   내역이 제공되지 않는다"며 비워 두고 있었다(2026-09-16 번들 실측).
        //   원자료가 이미 조건별이라 쿼리를 새로 파지 않는다.
        Map<Long, List<ReturnableResponse.Condition>> conditions = new LinkedHashMap<>();
        for (ReturnableAgg a : saleRepository.returnableAgg(partnerId, productId)) {
            ReturnableResponse.Row prev = byProduct.get(a.getProductId());
            long saleQty = a.getSaleQty() + (prev == null ? 0 : prev.saleQty());
            long returned = a.getReturnQty() + (prev == null ? 0 : prev.returnedQty());
            // 대표 정가·공급률: 출고수량이 가장 큰 건
            boolean takeRep = prev == null || a.getSaleQty() > repQty.getOrDefault(a.getProductId(), 0L);
            Integer unitPrice = takeRep ? a.getUnitPrice() : prev.unitPrice();
            Integer supplyRate = takeRep ? a.getSupplyRate() : prev.supplyRate();
            if (takeRep) {
                repQty.put(a.getProductId(), a.getSaleQty());
            }
            conditions.computeIfAbsent(a.getProductId(), k -> new ArrayList<>())
                    .add(new ReturnableResponse.Condition(
                            a.getUnitPrice(), a.getSupplyRate(), a.getSaleQty(), a.getReturnQty(),
                            splitSalesNos(a.getSourceSalesNos())));
            byProduct.put(a.getProductId(), new ReturnableResponse.Row(
                    a.getProductId(), a.getProductCode(), a.getProductName(),
                    unitPrice, supplyRate, saleQty, returned, saleQty - returned,
                    conditions.get(a.getProductId())));
        }
        // ★0만 걸러낸다(더 반품할 것도, 잘못된 것도 없는 도서). **음수는 보여준다** —
        //   반품 초과를 허용한 뒤로(발주처 2026-08-31 화면28) 잔여가 음수가 될 수 있는데,
        //   >0 으로 거르면 초과된 도서가 화면에서 통째로 사라져 담당자가 고칠 방법이 없어진다.
        //   보여야 고친다. (위탁 미결 remainingQty에서 같은 이유로 이미 한 번 고쳤다.)
        List<ReturnableResponse.Row> rows = byProduct.values().stream()
                .filter(r -> r.returnableQty() != 0)
                .toList();
        return new ReturnableResponse(partnerId, rows);
    }

    /**
     * 재고가 움직이는 거래에는 창고가 있어야 한다.
     *
     * <p>창고를 선택으로 연 것은 <b>미출고 매출</b>(문항사용료·학원매출 등) 때문이다 —
     * 실물이 안 나가는 매출이라 창고가 없는 게 맞다.
     * 그런데 정상출고·증정·교사용·반품은 재고가 실제로 움직인다. 창고 없이 받으면
     * <b>재고를 조용히 안 건드리고 매출만 서서</b> 장부가 갈라진다.
     *
     * <p>★재고관리 상품인지까지 본다 — 모의고사처럼 재고를 안 세는 상품만 있는 요청이라면
     * 창고가 없어도 어긋날 게 없다.
     */
    private void assertWarehouseWhenStockMoves(SalesEntryRequest req, Warehouse warehouse) {
        if (warehouse != null) {
            return;
        }
        for (SalesEntryRequest.Item item : req.items()) {
            Product p = productRepository.findById(item.productId()).orElse(null);
            if (p != null && p.isStockManaged()) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "재고가 움직이는 매출은 창고를 지정해야 합니다. 출고 없는 매출(문항사용료 등)만 "
                        + "창고를 비울 수 있습니다. 상품=" + p.getCode());
            }
        }
    }

    /** 출고유형별 물류재고 증감 부호. 정상출고/증정/교사용=−차감, 반품=+복구. 위탁·취소는 이 API 불가. */
    private int stockDelta(ShipmentType shipmentType, int qty) {
        return switch (shipmentType) {
            case NORMAL_SHIP, GIFT, TEACHER_USE -> -qty;
            case RETURN -> qty;
            case CONSIGN_SHIP -> throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "위탁출고 매출은 위탁정산(/consignment/settle)으로 처리하세요.");
            case CANCEL -> throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "취소는 매출 취소(/sales/{id}/cancel)로 처리하세요.");
        };
    }

    /**
     * 매출 수정(오입력 정정). 근거: 프론트 회신 2026-10-08 [A]2 — 9/27 회의 항목 7·9·12가
     * 모두 「우클릭 [수정]」을 요구하는데 서버에 경로가 없었다.
     *
     * <p>★<b>레거시에는 이 기능이 없다.</b> {@code UC_TabPages.vb:800} 주변에 UPDATE 문이
     * <b>주석 처리</b>돼 있고, 그 안에 당시 개발자 메모가 남아 있다 —
     * "update의 경우에는 생각할게 많다 … 그냥 삭제하고 넣는게 최선인가?".
     * 참고구현이 없으니 아래 선택을 근거와 함께 남긴다.
     *
     * <p>★<b>제자리 수정이다(매출번호를 유지한다).</b> 삭제 후 재등록하면 번호가 바뀌는데,
     * 매출번호에는 이미 <b>거래명세서 · 작업요청서(발송 건) · DSRE 신청번호 · 반품 원출고번호</b>가
     * 매달려 있다. 번호가 바뀌면 그 연결이 전부 끊기고, 특히 이미 출력된 거래명세서와
     * 대조할 수 없게 된다. (폐기는 그런 연결이 없어 삭제+재등록으로 간다 —
     * {@code InventoryService#reviseDisposal} 참고. 같은 기능이 다른 방식인 이유가 거기 있다.)
     *
     * <p><b>거부하는 경우</b>
     * <ul>
     *   <li>마감된 달 → {@code PERIOD_LOCKED}. 신고가 끝난 달의 숫자는 수정이 아니라 취소로 간다.</li>
     *   <li>이미 취소된 매출 → 되돌린 건을 고치면 역분개와 어긋난다.</li>
     *   <li>위탁정산 매출 → 미결원장(총출고=기정산+미결잔여)과 어긋난다. 취소 후 다시 정산해야 한다.</li>
     *   <li>바꿀 항목이 하나도 없음 → 화면 오류다. 조용히 성공으로 돌려주면 담당자는 반영된 줄 안다.</li>
     * </ul>
     *
     * <p>금액은 항상 {@code Amounts} 로 다시 산출한다 — 수량만 바뀌어도 공급가액·총금액이 따라간다.
     * 세액은 자동산출하지 않는다(재무팀 확정): 보내면 그 값, 안 보내면 기존 값 유지.
     */
    @Transactional
    public SaleResponse update(Long id, SaleUpdateRequest req) {
        Sale sale = saleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "매출이 없습니다. id=" + id));
        if (req.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "바꿀 항목이 없습니다.");
        }
        if (sale.isCanceled()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "취소된 매출은 수정할 수 없습니다: " + sale.getSalesNo());
        }
        if (sale.getSalesType() == SalesType.CONSIGN_SALES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "위탁정산으로 만들어진 매출은 수정할 수 없습니다: " + sale.getSalesNo()
                            + ". 매출 취소(/sales/" + id + "/cancel) 후 다시 정산하세요 — "
                            + "그래야 위탁 미결원장이 함께 맞습니다.");
        }
        periodLockService.assertNotLocked(sale.getSalesDate());

        int oldQty = sale.getQty();
        int newQty = (req.qty() != null) ? req.qty() : oldQty;
        Integer unitPrice = (req.unitPrice() != null) ? req.unitPrice() : sale.getUnitPrice();
        Integer supplyRate = (req.supplyRate() != null) ? req.supplyRate() : sale.getSupplyRate();
        Integer discount = (req.discountAmount() != null) ? req.discountAmount() : sale.getDiscountAmount();
        // 세액은 보낸 값 우선, 없으면 기존 값 유지(자동산출 안 함 — 재무팀 확정).
        Integer tax = (req.tax() != null) ? req.tax()
                : ((sale.getTax() == null) ? null : sale.getTax().intValue());
        // ★★금액 산출 입력(수량·정가·공급률·할인액·세액)을 **하나도 안 바꾼 수정**이면
        //   금액을 다시 계산하지 않고 **저장된 값을 그대로 둔다.**
        //
        //   ‼️안 그러면 메모만 고쳐도 금액이 0원이 된다. 이관 자료에 수량·정가·공급률이 0이고
        //     금액만 있는 줄이 있기 때문이다(대부분 지자체 건. 올해 116줄·약 42.9억,
        //     예: DL-364378 47,636,030원 — 프론트 실측 2026-10-23 §5).
        //     0은 null 검사를 통과하므로 Amounts.of(0,0,0) → 0원으로 덮인다.
        //
        //   "보낸 값으로 맞춘다"는 이 API의 규칙은 유지한다 — 금액 입력을 하나라도 보내면
        //   그때는 종전대로 다시 계산한다. 안 보낸 수정에까지 계산을 들이밀 이유가 없다.
        boolean amountInputsChanged = req.qty() != null || req.unitPrice() != null
                || req.supplyRate() != null || req.discountAmount() != null || req.tax() != null;

        String before = summarizeAmounts(sale);
        if (!amountInputsChanged) {
            sale.applyRevision(newQty, unitPrice, supplyRate, discount,
                    sale.getSupplyAmount(), sale.getTax(), sale.getTotalAmount(),
                    (req.memo() != null) ? req.memo() : sale.getMemo(),
                    (req.bookRound() != null) ? req.bookRound() : sale.getBookRound(),
                    (req.procType() != null) ? req.procType() : sale.getProcType(),
                    (req.packType() != null) ? req.packType() : sale.getPackType());
            return finishUpdate(sale, req, id, oldQty, newQty, before);
        }

        if (unitPrice == null || supplyRate == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "정가·공급률이 비어 있어 금액을 다시 계산할 수 없습니다: " + sale.getSalesNo());
        }

        // 면세 상품에 세액이 들어오면 Amounts 가 거부한다(한 곳에서만 판정한다).
        Amounts amt = Amounts.of(unitPrice, supplyRate, newQty, sale.getProduct().isTaxFree(),
                tax, discount);
        sale.applyRevision(newQty, unitPrice, supplyRate, discount,
                amt.supplyAmount(), amt.tax(), amt.totalAmount(),
                (req.memo() != null) ? req.memo() : sale.getMemo(),
                (req.bookRound() != null) ? req.bookRound() : sale.getBookRound(),
                (req.procType() != null) ? req.procType() : sale.getProcType(),
                (req.packType() != null) ? req.packType() : sale.getPackType());

        return finishUpdate(sale, req, id, oldQty, newQty, before);
    }

    /**
     * 매출 수정의 마무리 — 금액 직접입력 반영 · 이력 기록 · 수량 변동분 재고 보정.
     *
     * <p>금액을 다시 계산한 경로와 그대로 둔 경로가 <b>같은 마무리</b>를 타야 한다.
     * 갈라 두면 한쪽에만 재고 보정이나 이력이 빠진다.
     */
    private SaleResponse finishUpdate(Sale sale, SaleUpdateRequest req, Long id,
                                      int oldQty, int newQty, String before) {
        // ‼️from/to 칸은 VARCHAR(30)이라 요약이 안 들어간다(실제로 1406으로 터졌다).
        //   상태축에는 수량만 넣고, 무엇이 어떻게 바뀌었는지는 사유 칸(500)에 전부 적는다.
        // ★공급가액 직접 입력(B-3). 보내면 계산값을 덮는다.
        if (req.supplyAmount() != null) {
            sale.overrideSupplyAmount(req.supplyAmount());
        }

        statusHistoryService.record(StatusEntityType.SALE, sale.getId(), "revised",
                oldQty + "부", newQty + "부",
                "매출 수정(" + sale.getSalesNo() + ") " + before + " → " + summarizeAmounts(sale));

        // ★수량이 바뀌면 재고도 차액만큼 보정한다. 안 하면 장부(매출)와 재고가 갈린다.
        //   ‼️응답을 먼저 만든다 — 보정의 원자적 UPDATE가 영속성 컨텍스트를 비운다(취소와 같은 이유).
        int stockAdj = stockDelta(sale.getShipmentType(), newQty) - stockDelta(sale.getShipmentType(), oldQty);
        saleRepository.flush();
        SaleResponse response = SaleResponse.from(sale, divisionOf(sale));
        if (stockAdj != 0) {
            inventoryService.adjustShipment(sale.getSalesNo(), stockAdj,
                    "매출 수정: 수량 " + oldQty + " → " + newQty);
        }
        return response;
    }

    /** 이력에 남길 금액 요약. 수정 전·후를 같은 형식으로 적어야 나중에 대조된다. */
    private static String summarizeAmounts(Sale s) {
        return "수량 " + s.getQty() + " / 정가 " + s.getUnitPrice() + " / 공급률 " + s.getSupplyRate()
                + " / 공급가 " + s.getSupplyAmount() + " / 세액 " + s.getTax()
                + " / 총액 " + s.getTotalAmount();
    }

    /**
     * 매출 취소(논리 취소) + 재고 역분개를 한 트랜잭션으로. 이미 취소된 건은 400.
     * 원출고 이벤트(refNo=매출번호)를 반대 부호로 되돌려 재고 복구 + 수불부 버킷 상쇄.
     * 원출고 이벤트가 없으면(위탁정산 매출 등) 재고는 건드리지 않음.
     */
    @Transactional
    public SaleResponse cancel(Long id, String reason) {
        Sale sale = saleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "매출이 없습니다. id=" + id));
        if (sale.isCanceled()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 취소된 매출입니다: " + sale.getSalesNo());
        }
        periodLockService.assertNotLocked(sale.getSalesDate());
        boolean before = sale.isCanceled();   // ★바꾸기 전에 읽는다
        sale.cancel();
        String note = "매출 취소(" + sale.getSalesNo() + ")"
                + ((reason == null || reason.isBlank()) ? "" : " — 사유: " + reason.trim());
        statusHistoryService.record(StatusEntityType.SALE, sale.getId(), "canceled",
                before, true, note);

        // 위탁정산 매출 취소면 미결원장 복원(settled↔remaining 역산). 감사 결함 수정:
        // 이 처리가 없으면 settled_qty가 좌초되어 재정산 불가·재무/물류 desync.
        if (sale.getSalesType() == SalesType.CONSIGN_SALES && sale.getSettlement() != null) {
            sale.getSettlement().getConsignmentOut().unsettle(sale.getSettlement().getSettleQty());
        }

        // 취소 플래그를 먼저 확정(flush)하고 응답을 만든 뒤 역분개.
        // 역분개의 원자적 UPDATE(clearAutomatically)가 세션을 비우므로 순서가 중요.
        saleRepository.flush();
        SaleResponse response = SaleResponse.from(sale, divisionOf(sale));
        inventoryService.reverseShipments(sale.getSalesNo(), LocalDate.now());
        return response;
    }

    /**
     * 매출 삭제(마감 前 오입력 정정). 근거: 발주처 회신 2026-08-14 [4] —
     * "마감 확정 前 → 등록담당자가 우클릭 삭제 가능(레거시 수준)".
     *
     * <p>★<b>취소와 다른 축이다.</b> 합치지 말 것 —
     * <ul>
     *   <li><b>취소</b>: 있었던 거래를 되돌림 → 역분개가 장부에 <b>남는다</b>(그게 사실이다).</li>
     *   <li><b>삭제</b>: 애초에 잘못 친 것 → 장부에 <b>남으면 안 된다</b>(없던 거래다).</li>
     * </ul>
     * 한 플래그로 합치면 마감 후 정당한 반품 취소와 오입력이 섞여, 세무 소명 때 가릴 수 없다.
     *
     * <p>★<b>물리삭제하지 않는다.</b> 레거시는 진짜로 지웠지만(`UC_TabPages.vb:543`),
     * 그건 레거시가 재고 잔고를 저장하지 않아 행을 지우면 재고가 저절로 맞았기 때문이다.
     * 우리는 {@code inventory_txn}이 유일 진실이라 같은 행동이 정반대로 작동한다.
     * 담당자가 보는 결과는 같다(목록에서 사라진다) — 복구 가능성만 더 얻는다.
     *
     * <p><b>거부하는 경우</b>
     * <ul>
     *   <li>마감된 달 → {@code PERIOD_LOCKED}. 발주처 확정대로 그때는 취소로 간다.</li>
     *   <li>위탁정산 매출 → 정산 행위의 결과물이지 오입력이 아니다. 취소로 가야
     *       미결원장이 정상 복원된다({@link #cancel}).</li>
     * </ul>
     *
     * <p>발송 건(작업요청서)은 건드리지 않는다 — 지시 목록은 매출에서 파생되므로
     * 삭제된 매출은 자동으로 빠진다. 품목이 하나도 안 남은 발송 건이 0수량으로 보일 수 있다.
     */
    @Transactional
    public void delete(Long id, String reason) {
        Sale sale = saleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "매출이 없습니다. id=" + id));
        periodLockService.assertNotLocked(sale.getSalesDate());

        if (sale.getSalesType() == SalesType.CONSIGN_SALES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "위탁정산으로 만들어진 매출은 삭제할 수 없습니다. 매출 취소(/sales/" + id
                            + "/cancel)로 처리하세요 — 그래야 위탁 미결원장이 함께 되돌아갑니다.");
        }

        // ‼️지운 내용을 먼저 남긴다. 삭제 후에는 무엇을 지웠는지 이 기록에만 남는다.
        statusHistoryService.record(StatusEntityType.SALE, sale.getId(), "deleted",
                "false", "true", summarize(sale, reason));

        // ‼️매출 삭제 표시를 **먼저** 확정한다. 재고 되돌리기가 원자적 UPDATE라
        //   영속성 컨텍스트를 비우고, 그 뒤에 markDeleted 를 부르면 detached 엔티티에 걸려
        //   조용히 아무 일도 안 일어난다(취소 쪽 주석과 같은 이유).
        sale.markDeleted(currentAuditor.username());
        saleRepository.flush();

        inventoryService.softDeleteByRefNo(sale.getSalesNo(), currentAuditor.username());
    }

    /** 판매채널 조건에 '미분류'가 섞여 있나. 레거시가 별도 분기로 처리하는 값이다(조회.vb:1205). */
    private static boolean hasUnclassifiedChannel(List<String> channels) {
        return channels != null && channels.stream().anyMatch(SaleService::isUnclassified);
    }

    /**
     * '미분류'를 뺀 실제 채널명만. 섞어서 {@code in} 에 넣으면 영원히 안 걸린다 —
     * 미분류는 값이 <b>NULL·공백</b>이라 같다고 비교할 대상이 없다.
     */
    private static List<String> namedChannels(List<String> channels) {
        if (channels == null) {
            return null;
        }
        List<String> named = channels.stream().filter(c -> !isUnclassified(c)).toList();
        return named.isEmpty() ? null : named;
    }

    private static boolean isUnclassified(String c) {
        if (c == null || c.isBlank()) {
            return true;
        }
        String v = c.trim();
        return "미분류".equals(v) || "< 미분류 >".equals(v);
    }

    /**
     * {@code group_concat} 결과를 목록으로 푼다. 값이 없으면 <b>빈 목록</b>이다 —
     * null 을 내리면 전역 {@code NON_NULL} 설정 때문에 키 자체가 사라져,
     * 화면이 "필드가 없다"로 읽는다(이 프로젝트에서 세 번 겪었다).
     */
    private static List<String> splitSalesNos(String concatenated) {
        if (concatenated == null || concatenated.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(concatenated.split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).distinct().sorted().toList();
    }

    /** 삭제 이력에 남길 요약 — 나중에 "무엇을 지웠나"에 답할 수 있어야 한다. */
    private static String summarize(Sale sale, String reason) {
        return "매출 삭제(" + sale.getSalesNo() + ") "
                + sale.getPartner().getName() + " / " + sale.getProduct().getCode()
                + " " + sale.getQty() + "부 / 공급가 " + sale.getSupplyAmount()
                + " / 매출일 " + sale.getSalesDate()
                // 9/27 회의(A-3)로 사유 칸이 화면에서 빠져 빈 값이 정상이 됐다.
                // 그래도 "null"이 찍히게 두지 않는다 — 이력을 읽는 사람이 버그로 읽는다.
                + " — 사유: " + ((reason == null || reason.isBlank()) ? "(사유 미입력)" : reason.trim());
    }

    /**
     * 통합 매출 조회(7p 출고/반품조회 · 12p 통합매출조회 공용). <b>세 축 모두 다중선택</b>이다
     * (발주처 화면검토 2026-08-31 화면3 — "무상/유상/반품/입고 중복선택 체크박스").
     *
     * <p><b>거래분류</b>(표준 4축 중 첫째)로도 거를 수 있다. 다중선택이 되면서 규칙이 둘 생겼다 —
     * <ul>
     *   <li><b>입고·폐기는 골라도 무시</b>한다. 매출 원장에 대응이 없는 값이다(재고 원장의 거래).
     *       '무상 + 입고'를 고른 담당자는 무상은 보고 싶은 것이지, 입고가 섞였다고 무상까지
     *       사라지길 바라지 않는다. 단 <b>입고·폐기만</b> 골랐다면 남는 조건이 없으므로 빈 결과다 —
     *       전체를 주면 '폐기'로 걸렀는데 매출이 잔뜩 나오는 꼴이 된다.</li>
     *   <li>거래분류와 구분(상세)을 <b>같이</b> 주면 <b>교집합</b>이다. 둘은 다른 질문에 답하는
     *       별개 축이라 둘 다 만족해야 한다. 교집합이 비면 빈 결과다.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public PageResponse<SaleResponse> search(LocalDate from, LocalDate to,
                                             List<SalesCategory> salesCategories,
                                             List<TradeClass> tradeClasses,
                                             List<ShipmentType> shipmentTypes,
                                             List<Long> partnerIds,
                                             List<Long> warehouseIds,
                                             List<String> schoolCodes,
                                             List<String> divisionCodes,
                                             List<Long> productIds,
                                             List<String> partnerSchools,
                                             List<String> salesChannels,
                                             String keyword,
                                             Integer tradeSeq,
                                             boolean couponCount,
                                             boolean includeCanceled, Pageable pageable) {
        List<SalesCategory> categories = salesCategories;
        if (!MultiSelect.isAny(tradeClasses)) {
            List<SalesCategory> mapped = new ArrayList<>();
            for (TradeClass tc : tradeClasses) {
                SalesCategory m = tc.toSalesCategory();
                if (m != null && !mapped.contains(m)) {
                    mapped.add(m);       // 입고·폐기(null)는 매출 원장에 없다 — 떨어뜨린다
                }
            }
            if (mapped.isEmpty()) {
                return PageResponse.of(Page.empty(pageable));   // 입고·폐기만 골랐다
            }
            if (!MultiSelect.isAny(categories)) {
                mapped.retainAll(categories);                   // 두 축은 교집합
                if (mapped.isEmpty()) {
                    return PageResponse.of(Page.empty(pageable));
                }
            }
            categories = mapped;
        }
        // 세부구분 마스터는 몇 줄이라 한 번에 읽어 맵으로 쓴다 — 행마다 조회하면 목록 한 장에 수백 번이 된다.
        Map<String, SalesDivision> divisions = new HashMap<>();
        for (SalesDivision d : salesDivisionRepository.findAll()) {
            divisions.put(d.getCode(), d);
        }
        // 키워드는 소문자 + 양쪽 와일드카드. 공백만 들어오면 조건에서 뺀다 —
        // 빈 검색어로 전체가 사라지면 담당자는 데이터가 없는 줄 안다.
        String kw = (keyword == null || keyword.isBlank())
                ? null : "%" + keyword.trim().toLowerCase(java.util.Locale.ROOT) + "%";

        Page<Sale> page = saleRepository.search(from, to,
                MultiSelect.isAny(categories),
                MultiSelect.orPlaceholder(categories, SalesCategory.SALE),
                MultiSelect.isAny(shipmentTypes),
                MultiSelect.orPlaceholder(shipmentTypes, ShipmentType.NORMAL_SHIP),
                MultiSelect.isAny(partnerIds),
                MultiSelect.orPlaceholder(partnerIds, 0L),
                MultiSelect.isAny(warehouseIds),
                MultiSelect.orPlaceholder(warehouseIds, 0L),
                MultiSelect.isAny(schoolCodes),
                MultiSelect.orPlaceholder(schoolCodes, "\u0000"),
                MultiSelect.isAny(divisionCodes),
                MultiSelect.orPlaceholder(divisionCodes, "\u0000"),
                MultiSelect.isAny(productIds),
                MultiSelect.orPlaceholder(productIds, 0L),
                MultiSelect.isAny(partnerSchools),
                MultiSelect.orPlaceholder(normalizePairs(partnerSchools), "\u0000"),
                MultiSelect.isAny(salesChannels),
                hasUnclassifiedChannel(salesChannels),
                MultiSelect.orPlaceholder(namedChannels(salesChannels), "\u0000"),
                kw, tradeSeq, includeCanceled, pageable);
        // ★학교/학원 구분은 매출 원장에 없다 — 학교 마스터에서 붙인다.
        //   페이지 안의 학교코드만 모아 **한 번에** 조회한다(행마다 조회하면 N+1이다).
        //   ‼️마스터에 없는 코드는 그냥 null 로 둔다. 거기서 막으면 과거 매출이 조회에서 사라진다.
        Map<String, com.daesung.sales.school.entity.SchoolType> schoolTypes = schoolTypesOf(page);
        Map<String, String> schoolNames = schoolNamesOf(page);
        Map<String, String> channels = salesChannelsOf(page);
        return PageResponse.of(page.map(s -> SaleResponse.from(
                s, divisions.get(s.getProduct().getSalesDivision()),
                // ‼️학교코드가 없는 매출이 흔하다(학교 없이 거래처로만 나가는 건).
                //   Map.of() 는 **null 키 조회에서 NPE**를 던진다 — 빈 맵이어도 그렇다.
                //   조회 전에 걸러야 한다(실제로 GET /sales 가 통째로 500이었다).
                (s.getSchoolCode() == null) ? null : schoolTypes.get(s.getSchoolCode()),
                // 학교명 보완(항목 13-③) · 쿠폰 카운트(항목 13-⑤)
                (s.getSchoolCode() == null) ? null : schoolNames.get(s.getSchoolCode()),
                // 판매채널도 학교 마스터 값이다(레거시 schDataDT.type1).
                (s.getSchoolCode() == null) ? null : channels.get(s.getSchoolCode()),
                couponCount)));
    }

    /**
     * 거래처×학교 쌍을 쿼리가 비교할 모양으로 다듬는다.
     *
     * <p>화면은 {@code 12}(그 거래처 전체) 또는 {@code 12:S001}(그 학교만)로 보낸다.
     * 앞 형태를 {@code 12:*} 로 바꿔 두면 쿼리에서 한 번에 비교된다.
     *
     * <p>★<b>왜 이 축이 따로 필요한가</b> — 거래처목록 AND 학교목록으로는
     * "A는 전체, B는 그 학교만"을 표현할 수 없다. 학교목록을 걸면 A의 나머지 학교까지 빠진다.
     */
    private static List<String> normalizePairs(List<String> pairs) {
        if (MultiSelect.isAny(pairs)) {
            return pairs;
        }
        List<String> out = new ArrayList<>(pairs.size());
        for (String raw : pairs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String v = raw.trim();
            out.add(v.contains(":") ? v : v + ":*");
        }
        return out;
    }

    /**
     * 페이지에 실린 학교코드의 <b>학교명</b>을 한 번에 가져온다(N+1 방지).
     * 매출에 이름이 이미 있으면 그대로 두고, 빈 것만 이 값으로 채운다 —
     * 등록 당시 이름과 지금 마스터 이름이 다를 수 있어 덮어쓰지 않는다.
     */
    private Map<String, String> schoolNamesOf(Page<Sale> page) {
        java.util.Set<String> codes = new java.util.HashSet<>();
        page.forEach(s -> {
            if (s.getSchoolCode() != null && !s.getSchoolCode().isBlank()
                    && (s.getSchoolName() == null || s.getSchoolName().isBlank())) {
                codes.add(s.getSchoolCode());
            }
        });
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new java.util.HashMap<>();
        schoolRepository.findBySchoolCodeIn(codes)
                .forEach(sc -> names.putIfAbsent(sc.getSchoolCode(), sc.getSchoolName()));
        return names;
    }

    /** 페이지에 실린 학교코드의 <b>판매채널</b>을 한 번에 가져온다(N+1 방지). */
    private Map<String, String> salesChannelsOf(Page<Sale> page) {
        java.util.Set<String> codes = new java.util.HashSet<>();
        page.forEach(s -> {
            if (s.getSchoolCode() != null && !s.getSchoolCode().isBlank()) {
                codes.add(s.getSchoolCode());
            }
        });
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        schoolRepository.findBySchoolCodeIn(codes).forEach(sc -> {
            if (sc.getSalesChannel() != null && !sc.getSalesChannel().isBlank()) {
                out.putIfAbsent(sc.getSchoolCode(), sc.getSalesChannel());
            }
        });
        return out;
    }

    /** 페이지에 실린 학교코드의 학교/학원 구분을 한 번에 가져온다(N+1 방지). */
    private Map<String, com.daesung.sales.school.entity.SchoolType> schoolTypesOf(Page<Sale> page) {
        java.util.Set<String> codes = new java.util.HashSet<>();
        page.forEach(s -> {
            if (s.getSchoolCode() != null && !s.getSchoolCode().isBlank()) {
                codes.add(s.getSchoolCode());
            }
        });
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, com.daesung.sales.school.entity.SchoolType> out = new HashMap<>();
        schoolRepository.findBySchoolCodeIn(codes)
                .forEach(sc -> out.put(sc.getSchoolCode(), sc.getSchoolType()));
        return out;
    }

    /** 단건 응답용 세부구분 조회. 미지정이면 null → 대분류가 '미분류'로 나간다. */
    private SalesDivision divisionOf(Sale sale) {
        String code = sale.getProduct().getSalesDivision();
        return (code == null || code.isBlank())
                ? null : salesDivisionRepository.findByCode(code).orElse(null);
    }

}
