package com.daesung.sales.dealer.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.dealer.dto.DealerStockRow;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.entity.MajorCategory;
import com.daesung.sales.product.entity.PartnerProductStock;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.entity.SalesDivision;
import com.daesung.sales.product.repository.PartnerProductStockRepository;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.product.repository.SalesDivisionRepository;
import com.daesung.sales.sale.repository.SaleRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 특약점 재고현황. 근거: 10/31 클라이언트 회신 §3(10/26 §6 확정).
 *
 * <pre>
 *   로직   현행 그대로 — 구매·반품·재고 수기 입력, 출고 = 구매−재고−반품(계산만, 저장 안 함)
 *   대상   교재만(대분류=교재)
 *   범위   그 거래처에 §1 노출 규칙(①②③④)으로 노출되는 교재
 *   자동반영  하지 않는다(현행 유지로 종결)
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

    /** 재고현황 목록 — 그 거래처에 노출되는 교재만, 저장된 값이 있으면 채우고 없으면 0. */
    @Transactional(readOnly = true)
    public List<DealerStockRow> list(String partnerCode) {
        Partner partner = requirePartner(partnerCode);
        List<Product> textbooks = textbooksVisibleTo(partner.getId());

        Map<Long, PartnerProductStock> savedByProductId = stockRepository.findByPartnerId(partner.getId())
                .stream().collect(Collectors.toMap(s -> s.getProduct().getId(), s -> s));

        return textbooks.stream()
                .map(p -> {
                    PartnerProductStock saved = savedByProductId.get(p.getId());
                    return (saved != null) ? DealerStockRow.ofSaved(saved) : DealerStockRow.zero(p);
                })
                .toList();
    }

    /**
     * 구매·반품·재고 입력(덮어쓰기, 현행과 같은 방식 — 월 축 없음). null인 항목은
     * 건드리지 않는다(부분 입력 허용 — 재고만 고치고 구매·반품은 그대로 둘 수 있다).
     *
     * <p>노출되지 않는(또는 교재가 아닌) 도서는 거부한다 — 목록에 없는 도서를 입력받으면
     * 화면에 안 보이는 숫자가 DB에 쌓인다.
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

        PartnerProductStock saved = stockRepository.findByPartnerIdAndProductId(partner.getId(), product.getId())
                .map(e -> {
                    e.apply(purchaseQty, returnQty, stockQty);
                    return e;
                })
                .orElseGet(() -> stockRepository.save(PartnerProductStock.create(partner, product,
                        zeroIfNull(purchaseQty), zeroIfNull(returnQty), zeroIfNull(stockQty))));

        return DealerStockRow.ofSaved(saved);
    }

    /**
     * 그 거래처가 볼 교재 전체 — <b>(노출 교재) ∪ (이력 있는 교재)</b>. 근거: 11/04 회신 §4
     * "현행 그대로 — 범위는 그 거래처에 §1 노출 규칙으로 노출되는 교재"를 재고 입력 이력이
     * 있는 교재까지 넓힌 것. 노출이 꺼진 뒤에도 특약점이 들고 있는 재고 숫자가 화면에서
     * 사라지면 안 된다(이미 입력한 값을 잃어버린 것처럼 보인다).
     *
     * <p>이력 = ① {@code partner_product_stock} 에 행이 있는 교재(기간 무관 — 이미 입력된
     * 숫자다) ∪ ② 최근 N개월(설정값, 기본 12) 매출 출고가 있는 교재. ①은 노출 판정과
     * 무관하게 항상 포함해야 하므로 {@link #list} 가 저장값을 붙일 때 따로 조회하지 않고
     * 여기서 교재 목록에 먼저 합쳐 둔다.
     */
    private List<Product> textbooksVisibleTo(Long partnerId) {
        List<Product> visible = productRepository.findDealerVisible(partnerId);
        Map<Long, Product> byId = visible.stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a, java.util.LinkedHashMap::new));

        // ① 재고 입력 이력이 있는 도서 — 노출 여부와 무관하게 항상 포함.
        for (PartnerProductStock saved : stockRepository.findByPartnerId(partnerId)) {
            Product p = saved.getProduct();
            byId.putIfAbsent(p.getId(), p);
        }

        // ② 최근 N개월 매출 출고가 있는 도서(설정값, 기본 12개월 — 회신 대기라 상수로 안 박음).
        if (properties.stockHistoryFromSales()) {
            java.time.LocalDate from = java.time.LocalDate.now().minusMonths(properties.stockHistoryMonths());
            List<Long> shippedIds = saleRepository.shippedProductIdsSince(partnerId, from);
            for (Long id : shippedIds) {
                if (!byId.containsKey(id)) {
                    productRepository.findById(id).ifPresent(p -> byId.put(id, p));
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
