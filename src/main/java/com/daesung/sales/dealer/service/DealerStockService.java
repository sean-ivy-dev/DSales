package com.daesung.sales.dealer.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.dealer.dto.DealerStockCutoverSettingResponse;
import com.daesung.sales.dealer.dto.DealerStockRow;
import com.daesung.sales.dealer.entity.DealerStockCutoverSetting;
import com.daesung.sales.dealer.repository.DealerStockCutoverSettingRepository;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.entity.MajorCategory;
import com.daesung.sales.product.entity.PartnerProductStock;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.entity.SalesDivision;
import com.daesung.sales.product.repository.PartnerProductStockRepository;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.product.dto.PartnerProductStockSeedRequest;
import com.daesung.sales.product.dto.PartnerProductStockSeedResult;
import com.daesung.sales.product.repository.SalesDivisionRepository;
import com.daesung.sales.sale.dto.DealerStockPostCutoverAgg;
import com.daesung.sales.sale.repository.SaleRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 특약점 재고현황. 근거: 10/31 클라이언트 회신 §3(10/26 §6 확정) + <b>11/05 회신 §4
 * 매입·반품 자동집계로 전면 재설계</b>.
 *
 * <p>‼️<b>누가 무엇을 입력하는지</b>(PM 정정 2026-11-05, 레거시 {@code FM_DSRE_BookList}
 * 확인): 매입·반품을 손으로 넣는 쪽은 <b>본사</b>다(DSRE [도서신청 주문내역]).
 * <b>특약점은 재고만</b> 입력한다 — 사이트는 {@code STOCK}({@code EDIT_WEB})만 쓴다.
 * 아래에서 "수기 입력"은 그래서 <b>본사가 DSRE에 넣던 값</b>을 가리킨다.
 *
 * <pre>
 *   cutover 전(기본, {@code dealer_stock_cutover_setting.cutover_date} 비어 있음)
 *     로직   매입·반품은 본사가 DSRE에 넣은 값(우리 쪽은 이관값), 재고는 특약점 입력,
 *           출고 = 매입−재고−반품(계산만, 저장 안 함)
 *   cutover 후(기준일 설정됨)
 *     로직   매입 = 이관 시작값 + 기준일 이후 매출프로그램 정상출고(NORMAL_SHIP) 합계
 *           반품 = 이관 시작값 + 기준일 이후 매출프로그램 반품(RETURN) 합계
 *           재고만 특약점이 입력, 매입·반품 입력은 무시(이관 시작값을 덮어쓰면 안 된다)
 *   대상   교재만(대분류=교재)
 *   범위   그 거래처에 §1 노출 규칙(①②③④)으로 노출되는 교재 ∪ 이력 있는 교재(§2)
 * </pre>
 *
 * <p>★<b>노출 판정을 또 구현하지 않는다.</b> {@link DealerProductService}(요청1)가 쓰는
 * {@link ProductRepository#findDealerVisible} 를 그대로 재사용해 <b>교재만</b> 추려낸다 —
 * 노출 규칙이 두 곳에 갈리면 "신청 화면엔 보이는데 재고현황엔 안 보이는" 어긋남이 생긴다.
 */
@Service
@RequiredArgsConstructor
public class DealerStockService {

    private final PartnerRepository partnerRepository;
    private final ProductRepository productRepository;
    private final SalesDivisionRepository salesDivisionRepository;
    private final PartnerProductStockRepository stockRepository;
    private final SaleRepository saleRepository;
    private final com.daesung.sales.dealer.config.DealerSiteProperties properties;
    private final DealerStockCutoverSettingRepository cutoverSettingRepository;

    /** 재고현황 목록 — 그 거래처에 노출되는 교재만, 저장된 값이 있으면 채우고 없으면 0. */
    @Transactional(readOnly = true)
    public List<DealerStockRow> list(String partnerCode) {
        Partner partner = requirePartner(partnerCode);
        List<Product> textbooks = textbooksVisibleTo(partner.getId());

        Map<Long, PartnerProductStock> savedByProductId = stockRepository.findByPartnerId(partner.getId())
                .stream().collect(Collectors.toMap(s -> s.getProduct().getId(), s -> s));
        Map<Long, DealerStockPostCutoverAgg> aggByProductId = postCutoverAggByProductId(partner.getId());

        return textbooks.stream()
                .map(p -> toRow(p, savedByProductId.get(p.getId()), aggByProductId))
                .toList();
    }

    /**
     * 구매·반품·재고 입력(덮어쓰기, 현행과 같은 방식 — 월 축 없음). null인 항목은
     * 건드리지 않는다(부분 입력 허용 — 재고만 고치고 구매·반품은 그대로 둘 수 있다).
     *
     * <p>노출되지 않는(또는 교재가 아닌) 도서는 거부한다 — 목록에 없는 도서를 입력받으면
     * 화면에 안 보이는 숫자가 DB에 쌓인다.
     *
     * <p>★<b>cutover 후엔 매입·반품 입력을 무시한다</b>(11/05 §4) — 그 둘은 더는 수기
     * 입력값이 아니라 "이관 시작값"이다. 특약점이 보낸 값을 그대로 받아 쓰면 그 시작값이
     * 사라지고, 자동집계가 틀린 바탕 위에 더해진다. 재고는 cutover 전후 상관없이 항상
     * 수기 입력이다.
     */
    @Transactional
    public DealerStockRow save(String partnerCode, String productCode,
                               Integer purchaseQty, Integer returnQty, Integer stockQty) {
        Partner partner = requirePartner(partnerCode);
        Product product = productRepository.findByCode(productCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "도서를 찾을 수 없습니다: " + productCode));

        if (textbooksVisibleTo(partner.getId()).stream().noneMatch(p -> p.getId().equals(product.getId()))) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_VISIBLE,
                    "재고현황 대상이 아닙니다(교재가 아니거나 노출되지 않는 도서): " + productCode);
        }

        boolean autoAgg = cutoverSetting().autoAggregationEnabled();
        Integer basePurchase = autoAgg ? null : purchaseQty;
        Integer baseReturn = autoAgg ? null : returnQty;

        PartnerProductStock saved = stockRepository.findByPartnerIdAndProductId(partner.getId(), product.getId())
                .map(e -> {
                    e.apply(basePurchase, baseReturn, stockQty);
                    return e;
                })
                .orElseGet(() -> stockRepository.save(PartnerProductStock.create(partner, product,
                        zeroIfNull(basePurchase), zeroIfNull(baseReturn), zeroIfNull(stockQty))));

        return toRow(product, saved, postCutoverAggByProductId(partner.getId()));
    }

    /**
     * [관리자] 재고현황 이관 베이스라인 일괄 시딩(11/05 회신 §4). 한 건이 실패해도 나머지는
     * 계속 처리한다(엑셀 업로드와 같은 원칙).
     *
     * <p>★<b>노출 판정을 보지 않는다</b> — {@link #save} 와 결정적으로 다른 점이다. 이관되는
     * 값은 "그 거래처가 과거에 실제로 거래한 매입·반품·재고"이고, 지금 노출 설정(①~④)이
     * 그 과거 사실과 무관하게 나중에 바뀔 수 있다. 노출 체크를 걸면 아직 열기 지정이
     * 안 된 거래처×도서의 이관 자체가 막혀 버린다(실제로 92건 중 85건이 이렇게 막혔다).
     */
    @Transactional
    public PartnerProductStockSeedResult seedBaselineBulk(List<PartnerProductStockSeedRequest> items) {
        int succeeded = 0;
        List<PartnerProductStockSeedResult.Failure> failures = new ArrayList<>();
        for (PartnerProductStockSeedRequest item : items) {
            try {
                seedBaseline(item.partnerCode(), item.productCode(),
                        item.purchaseQty(), item.returnQty(), item.stockQty(),
                        item.stockUpdatedOn());
                succeeded++;
            } catch (BusinessException e) {
                failures.add(new PartnerProductStockSeedResult.Failure(
                        item.partnerCode(), item.productCode(), e.getMessage()));
            }
        }
        return new PartnerProductStockSeedResult(items.size(), succeeded, failures);
    }

    /**
     * {@link #seedBaselineBulk} 의 한 건 — 노출 판정 없이 바로 베이스라인을 쓴다.
     *
     * <p>{@code stockUpdatedOn}(DSRE {@code EDIT_WEB})을 주면 재고 최종수정일을 그 날짜로
     * 맞춘다 — 이관 행이 "오늘 고쳐진 것"으로 보이면 지금 화면과 값이 달라진다.
     */
    @Transactional
    public DealerStockRow seedBaseline(String partnerCode, String productCode,
                                       Integer purchaseQty, Integer returnQty, Integer stockQty,
                                       java.time.LocalDate stockUpdatedOn) {
        Partner partner = requirePartner(partnerCode);
        Product product = productRepository.findByCode(productCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "도서를 찾을 수 없습니다: " + productCode));

        PartnerProductStock saved = stockRepository
                .findByPartnerIdAndProductId(partner.getId(), product.getId())
                .orElseGet(() -> stockRepository.save(
                        PartnerProductStock.create(partner, product, 0, 0, 0)));
        // ★이관은 apply()가 아니라 applyBaselineSeed() — 재고를 넘겨도 최종수정일을
        //   오늘로 찍지 않는다(찍으면 특약점 화면이 "안 고쳤는데 오늘 고침"으로 보인다).
        saved.applyBaselineSeed(purchaseQty, returnQty, stockQty, stockUpdatedOn);

        return toRow(product, saved, postCutoverAggByProductId(partner.getId()));
    }

    /** 저장값(또는 없으면 0) + cutover 자동집계(있으면)를 합쳐 한 줄로. */
    private DealerStockRow toRow(Product p, PartnerProductStock saved,
                                  Map<Long, DealerStockPostCutoverAgg> aggByProductId) {
        int basePurchase = (saved != null) ? saved.getPurchaseQty() : 0;
        int baseReturn = (saved != null) ? saved.getReturnQty() : 0;
        int stockQty = (saved != null) ? saved.getStockQty() : 0;
        DealerStockPostCutoverAgg agg = aggByProductId.get(p.getId());
        int addedPurchase = (agg != null) ? (int) agg.getPurchasedQty() : 0;
        int addedReturn = (agg != null) ? (int) agg.getReturnedQty() : 0;
        java.time.LocalDate stockUpdatedOn = (saved != null) ? saved.getStockUpdatedOn() : null;
        return DealerStockRow.of(p, basePurchase, baseReturn, stockQty,
                addedPurchase, addedReturn, stockUpdatedOn);
    }

    /** cutover 가 설정되지 않았으면 비워 둔다(자동집계 없음 — 매입·반품은 이관값 그대로). */
    private Map<Long, DealerStockPostCutoverAgg> postCutoverAggByProductId(Long partnerId) {
        DealerStockCutoverSetting setting = cutoverSetting();
        if (!setting.autoAggregationEnabled()) {
            return Map.of();
        }
        return saleRepository.dealerStockPostCutoverAgg(partnerId, setting.getCutoverDate()).stream()
                .collect(Collectors.toMap(DealerStockPostCutoverAgg::getProductId, a -> a));
    }

    /**
     * [관리자] 매입·반품 자동집계 전환 설정 조회
     * ({@code PartnerProductStockController.cutoverDate} 가 호출).
     */
    @Transactional(readOnly = true)
    public DealerStockCutoverSettingResponse getCutoverSetting() {
        DealerStockCutoverSetting setting = cutoverSetting();
        return new DealerStockCutoverSettingResponse(
                setting.getCutoverDate(), setting.autoAggregationEnabled());
    }

    /**
     * [관리자] 매입·반품 자동집계 전환일 변경 — 즉시 적용된다(배포 불필요, 11/05 §4).
     * {@code cutoverDate=null} 이면 전환을 끄고 매입·반품이 이관값에서 멈추는 상태로 되돌린다.
     */
    @Transactional
    public DealerStockCutoverSettingResponse updateCutoverSetting(java.time.LocalDate cutoverDate) {
        DealerStockCutoverSetting setting = cutoverSetting();
        setting.setCutoverDate(cutoverDate);
        return new DealerStockCutoverSettingResponse(
                setting.getCutoverDate(), setting.autoAggregationEnabled());
    }

    /** 매입·반품 자동집계 전환 설정(싱글톤 행, V117) — 관리자 API로 즉시 바뀐다. */
    private DealerStockCutoverSetting cutoverSetting() {
        return cutoverSettingRepository.findById(DealerStockCutoverSetting.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "dealer_stock_cutover_setting 싱글톤 행이 없습니다(V117 마이그레이션 확인)"));
    }

    /**
     * 그 거래처가 볼 교재 전체 — <b>(노출 교재) ∪ (이력 있는 교재)</b>. 근거: 11/05 회신 §2
     * (11/04 §4의 "최근 12개월"을 <b>기간 무제한</b>으로 대체 + 사용여부(useYn) N인 도서는
     * 제외). 노출이 꺼진 뒤에도 특약점이 들고 있는 재고 숫자가 화면에서 사라지면 안 된다
     * (이미 입력한 값을 잃어버린 것처럼 보인다).
     *
     * <p>이력 = ① {@code partner_product_stock} 에 행이 있는 교재(기간 무관) ∪ ② 매출
     * 출고가 있는 교재(기간 무관). ①은 노출 판정과 무관하게 항상 포함해야 하므로
     * {@link #list} 가 저장값을 붙일 때 따로 조회하지 않고 여기서 교재 목록에 먼저
     * 합쳐 둔다.
     *
     * <p>★<b>useYn 은 ①·②에도 적용한다</b> — {@link ProductRepository#findDealerVisible} 는
     * 이미 {@code useYn=true} 를 보지만, 이력으로 끌어오는 도서는 그 쿼리를 거치지 않아
     * 같은 필터를 여기서 다시 걸어야 한다(안 그러면 그만 쓰는 도서가 이력 때문에 다시
     * 노출된다).
     */
    private List<Product> textbooksVisibleTo(Long partnerId) {
        List<Product> visible = productRepository.findDealerVisible(partnerId);
        Map<Long, Product> byId = visible.stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a, java.util.LinkedHashMap::new));

        // ① 재고 입력 이력이 있는 도서 — 노출 여부와 무관하게 항상 포함(사용여부는 본다).
        for (PartnerProductStock saved : stockRepository.findByPartnerId(partnerId)) {
            Product p = saved.getProduct();
            if (p.isUseYn()) {
                byId.putIfAbsent(p.getId(), p);
            }
        }

        // ② 매출 출고가 있는 도서(기간 무제한, 11/05 §2). 사용여부는 본다.
        if (properties.stockHistoryFromSales()) {
            List<Long> shippedIds = saleRepository.shippedProductIds(partnerId);
            for (Long id : shippedIds) {
                if (!byId.containsKey(id)) {
                    productRepository.findById(id)
                            .filter(Product::isUseYn)
                            .ifPresent(p -> byId.put(id, p));
                }
            }
        }

        Map<String, MajorCategory> majorByDivisionCode = byId.values().stream()
                .map(Product::getSalesDivision)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(salesDivisionRepository::findByCode)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(SalesDivision::getCode, SalesDivision::getMajorCategory));

        return byId.values().stream()
                .filter(p -> majorByDivisionCode.get(p.getSalesDivision()) == MajorCategory.TEXTBOOK)
                .toList();
    }

    private static int zeroIfNull(Integer v) {
        return (v == null) ? 0 : v;
    }

    private Partner requirePartner(String partnerCode) {
        return partnerRepository.findByCode(partnerCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARTNER_NOT_LINKED,
                        "매출 거래처 연결이 되어 있지 않습니다: " + partnerCode));
    }
}
