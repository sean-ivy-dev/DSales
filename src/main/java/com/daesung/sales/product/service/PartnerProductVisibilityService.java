package com.daesung.sales.product.service;

import com.daesung.sales.audit.entity.StatusEntityType;
import com.daesung.sales.audit.service.StatusHistoryService;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.dto.PartnerProductVisibilityRow;
import com.daesung.sales.product.entity.PartnerProductVisibility;
import com.daesung.sales.product.entity.PartnerSupplyRate;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.entity.SalesDivision;
import com.daesung.sales.product.repository.PartnerProductVisibilityRepository;
import com.daesung.sales.product.repository.PartnerSupplyRateRepository;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.product.repository.SalesDivisionRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [거래처관리] 거래처 상세 → '도서별 노출' 화면. 근거: 10/31 클라이언트 회신 §1-3·1-4.
 *
 * <p>★<b>판정 쿼리({@link ProductRepository#findDealerVisible})와 같은 규칙을 다시 짜지
 * 않는다.</b> 이 서비스가 하는 일은 "체크박스를 켰을 때 예외 행을 만들지 지울지"뿐이고,
 * 실제 노출 여부 계산(①②③④)은 전부 그 쿼리가 혼자 담당한다. 여기서 규칙을 또 구현하면
 * 두 곳이 갈릴 수 있다.
 */
@Service
@RequiredArgsConstructor
public class PartnerProductVisibilityService {

    private final PartnerProductVisibilityRepository visibilityRepository;
    private final PartnerSupplyRateRepository supplyRateRepository;
    private final SalesDivisionRepository salesDivisionRepository;
    private final PartnerRepository partnerRepository;
    private final ProductRepository productRepository;
    private final StatusHistoryService statusHistoryService;

    /** '도서별 노출' 화면 목록. 분류코드→도서코드 순(트리는 화면에서 분류명으로 묶는다). */
    @Transactional(readOnly = true)
    public Page<PartnerProductVisibilityRow> list(Long partnerId, String keyword, Pageable pageable) {
        requirePartner(partnerId);
        List<PartnerProductVisibility> exceptions = visibilityRepository.findByPartnerId(partnerId);
        java.util.Map<Long, Boolean> exceptionByProductId = exceptions.stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getProduct().getId(),
                        PartnerProductVisibility::isVisible));

        Page<Product> products = productRepository.search(keyword, null, null, null, pageable);
        return products.map(p -> {
            boolean hasException = exceptionByProductId.containsKey(p.getId());
            boolean visible;
            if (!p.isWebVisible()) {
                visible = false;   // ① 이 예외보다 우선
            } else if (hasException) {
                visible = exceptionByProductId.get(p.getId());   // ②
            } else {
                visible = defaultVisible(partnerId, p);   // ③④
            }
            return new PartnerProductVisibilityRow(p.getId(), p.getCode(), p.getName(),
                    p.getCatCode(), p.getCatName(), visible, hasException, p.isWebVisible());
        });
    }

    /**
     * 체크박스를 바꾼다. <b>계산한 기본값(③④)과 같아지면 예외 행을 지운다</b>(10/31 §1-4) —
     * 예외가 "달라서 저장해 둔 값"이라는 의미를 지키려면, 같아진 예외를 계속 들고 있으면 안 된다.
     */
    @Transactional
    public void set(Long partnerId, Long productId, boolean visible, String reason) {
        Partner partner = requirePartner(partnerId);
        Product product = requireProduct(productId);
        boolean def = defaultVisible(partnerId, product);

        Optional<PartnerProductVisibility> existing =
                visibilityRepository.findByPartnerIdAndProductId(partnerId, productId);

        if (visible == def) {
            existing.ifPresent(e -> {
                Long id = e.getId();
                visibilityRepository.delete(e);
                statusHistoryService.record(StatusEntityType.PARTNER_PRODUCT_VISIBILITY, id,
                        "visible", e.isVisible(), def,
                        "기본값과 같아져 예외 해제" + reasonSuffix(reason));
            });
            return;
        }

        if (existing.isPresent()) {
            PartnerProductVisibility e = existing.get();
            boolean from = e.isVisible();
            e.apply(visible);
            statusHistoryService.record(StatusEntityType.PARTNER_PRODUCT_VISIBILITY, e.getId(),
                    "visible", from, visible, (visible ? "열기로 변경" : "닫기로 변경") + reasonSuffix(reason));
        } else {
            PartnerProductVisibility saved =
                    visibilityRepository.save(PartnerProductVisibility.create(partner, product, visible));
            statusHistoryService.record(StatusEntityType.PARTNER_PRODUCT_VISIBILITY, saved.getId(),
                    "visible", def, visible, (visible ? "예외로 열기" : "예외로 닫기") + reasonSuffix(reason));
        }
    }

    /** '기본값으로 되돌리기' 버튼(§1-4) — 예외가 있으면 무조건 지운다. */
    @Transactional
    public void reset(Long partnerId, Long productId, String reason) {
        requirePartner(partnerId);
        visibilityRepository.findByPartnerIdAndProductId(partnerId, productId).ifPresent(e -> {
            Long id = e.getId();
            boolean from = e.isVisible();
            visibilityRepository.delete(e);
            boolean def = defaultVisible(partnerId, requireProduct(productId));
            statusHistoryService.record(StatusEntityType.PARTNER_PRODUCT_VISIBILITY, id,
                    "visible", from, def, "되돌리기" + reasonSuffix(reason));
        });
    }

    /** ③④만 — 예외·도서 Web게시(①)를 보지 않은 "매핑만으로의" 기본값. set()의 diff 기준. */
    private boolean defaultVisible(Long partnerId, Product product) {
        if (product.getSalesDivision() == null) {
            return false;   // 대분류를 모르면 매핑을 찾을 수 없다 — ④
        }
        Optional<SalesDivision> division = salesDivisionRepository.findByCode(product.getSalesDivision());
        if (division.isEmpty()) {
            return false;
        }
        return supplyRateRepository.findActive(partnerId, division.get().getMajorCategory())
                .map(PartnerSupplyRate::isWebVisible)
                .orElse(false);   // ④ 매핑 없음
    }

    private static String reasonSuffix(String reason) {
        return (reason == null || reason.isBlank()) ? "" : " — 사유: " + reason.trim();
    }

    private Partner requirePartner(Long partnerId) {
        return partnerRepository.findById(partnerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "거래처가 없습니다. id=" + partnerId));
    }

    private Product requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "도서가 없습니다. id=" + productId));
    }
}
