package com.daesung.sales.product.service;

import com.daesung.sales.audit.entity.StatusEntityType;
import com.daesung.sales.audit.service.StatusHistoryService;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.dto.ProductPartnerVisibilityRow;
import com.daesung.sales.product.dto.ProductVisibilityBulkRequest;
import com.daesung.sales.product.dto.ProductVisibilityBulkResult;
import com.daesung.sales.product.dto.ProductVisibilityCategoryBulkRequest;
import com.daesung.sales.product.dto.ProductVisibilityCategoryBulkResult;
import com.daesung.sales.product.entity.PartnerProductVisibility;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.PartnerProductVisibilityRepository;
import com.daesung.sales.product.repository.ProductRepository;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [도서관리] '거래처별 노출' 탭 — <b>도서 한 권을 어느 거래처에 열지</b> 지정한다.
 * 근거: 11/04 클라이언트 회신 §2(설정 위치를 10/31 판의 [거래처관리] 거래처 축에서
 * [도서관리] 도서 축으로 옮김).
 *
 * <p>★<b>축이 뒤집혔다.</b> 예전엔 거래처 하나를 열고 도서 수천 권을 페이징했다 —
 * "이 도서를 지정한 몇 곳에만 열기"가 실제 운영인데, 그걸 하려면 도서를 거래처마다
 * 다시 찾아야 했다. 지금은 도서를 고르고 거래처를 체크한다(정본 도서관리 매핑 탭의
 * '선택 거래처 일괄 적용'과 같은 조작).
 *
 * <p>★<b>판정 규칙을 여기서 다시 짜지 않는다.</b> 이전 판은 이 클래스 주석에 같은 말을
 * 적어 두고도 {@code defaultVisible()} 로 뒤 두 단계를 자바로 한 번 더 구현했고, 그동안
 * {@link ProductRepository#isDealerVisible} 은 호출부가 0건이었다. 지금은 그 단건 판정을
 * 실제로 쓴다 — 규칙이 바뀌면 쿼리 두 개(목록형·단건형)만 고치면 된다.
 *
 * <p>★<b>'닫기' 상태는 없다</b>(11/04 §1). 지정은 '열기' 하나뿐이고, 닫는 것은 지정을
 * 지우는 것이다. "그 외 전부 비노출"은 도서의 <b>지정 거래처 전용</b> 스위치가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class PartnerProductVisibilityService {

    private final PartnerProductVisibilityRepository visibilityRepository;
    private final PartnerRepository partnerRepository;
    private final ProductRepository productRepository;
    private final StatusHistoryService statusHistoryService;

    /**
     * 그 도서에 대한 거래처 목록 — 체크 상태(열기 지정)와 계산된 현재 노출값을 함께 준다.
     *
     * <p>⚠️노출값은 거래처마다 {@link ProductRepository#isDealerVisible} 를 한 번씩 부른다
     * (페이지 상한 200건이라 최대 200회). 한 방에 거르는 쿼리를 따로 만들면 같은 규칙이
     * <b>세 번째</b>로 복사된다 — 그게 레거시가 "화면마다 재고가 다른" 이유였다.
     * 느려지면 캐시가 아니라 목록형 쿼리를 거래처 축으로 뒤집는 쪽을 먼저 보라.
     */
    @Transactional(readOnly = true)
    public Page<ProductPartnerVisibilityRow> list(Long productId, String keyword, String clientCategory,
                                                  boolean includeExpired, Pageable pageable) {
        Product product = requireProduct(productId);
        Set<Long> openPartnerIds = visibilityRepository.findByProductId(productId).stream()
                .map(e -> e.getPartner().getId())
                .collect(Collectors.toSet());

        Page<Partner> partners =
                partnerRepository.searchByCategory(keyword, clientCategory, includeExpired, pageable);

        return partners.map(p -> new ProductPartnerVisibilityRow(
                p.getId(), p.getCode(), p.getName(), p.getClientCategory(),
                productRepository.isDealerVisible(p.getId(), product),
                openPartnerIds.contains(p.getId()),
                product.isWebVisible(), product.isRestrictedVisible()));
    }

    /**
     * 선택 거래처 일괄 적용(열기 지정/해제).
     *
     * <p>이미 그 상태인 거래처는 건드리지 않고 {@code unchanged} 로 센다 — 같은 값을 다시
     * 써서 변경 이력을 부풀리면 "언제 누가 실제로 바꿨나"를 못 읽는다.
     *
     * <p>‼️<b>이력은 일괄 경로에서도 남긴다.</b> 참고한 거래처별 단가 일괄
     * ({@code PartnerSupplyRateService.bulkApply})은 이력을 안 남기는데(단건 upsert 경로만
     * 남긴다), 그 누락까지 베끼지 않는다 — 11/04 §2가 변경 이력을 명시 요구했다.
     */
    @Transactional
    public ProductVisibilityBulkResult bulkApply(Long productId, ProductVisibilityBulkRequest req) {
        Product product = requireProduct(productId);
        var partnerIds = req.partnerIds().stream().distinct().toList();
        int opened = 0;
        int closed = 0;
        int unchanged = 0;

        for (Long partnerId : partnerIds) {
            Partner partner = requirePartner(partnerId);
            Optional<PartnerProductVisibility> existing =
                    visibilityRepository.findByPartnerIdAndProductId(partnerId, productId);

            if (req.open()) {
                if (existing.isPresent()) {
                    unchanged++;
                    continue;
                }
                PartnerProductVisibility saved = visibilityRepository.save(
                        PartnerProductVisibility.create(partner, product, true));
                recordHistory(saved.getId(), false, true,
                        "열기 지정 — 도서 " + product.getCode() + " / 거래처 " + partner.getCode(), req.reason());
                opened++;
            } else {
                if (existing.isEmpty()) {
                    unchanged++;
                    continue;
                }
                Long id = existing.get().getId();
                visibilityRepository.delete(existing.get());
                recordHistory(id, true, false,
                        "열기 해제 — 도서 " + product.getCode() + " / 거래처 " + partner.getCode(), req.reason());
                closed++;
            }
        }
        return new ProductVisibilityBulkResult(product.getCode(), partnerIds.size(), opened, closed, unchanged);
    }

    /**
     * 분류 단위 일괄 적용(11/04 §2 "분류명 체크 → 하위 도서 일괄 선택" +
     * "거래처 체크박스 다중 선택 → 열기/닫기 일괄 적용"). {@code catCode}×{@code partnerIds}
     * 전체 조합에 같은 동작을 적용한다.
     *
     * <p>★<b>로직을 또 짜지 않는다.</b> 도서 한 권 단위로는 이미 {@link #bulkApply}가
     * 열기/해제·이력 기록·unchanged 판정을 전부 한다 — 여기서는 대상 도서를 정하고
     * 그 메서드를 도서마다 호출해 합산할 뿐이다. 두 번째로 같은 규칙을 구현하면
     * 그게 V42가 경고한 실패 방식이다.
     */
    @Transactional
    public ProductVisibilityCategoryBulkResult bulkApplyByCategory(ProductVisibilityCategoryBulkRequest req) {
        if (req.hasNoTarget()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "대상이 없습니다. 도서 id 목록이나 분류코드를 지정하세요.");
        }
        java.util.List<Long> productIds = (req.catCode() != null && !req.catCode().isBlank())
                ? productRepository.findIdsByCatCode(req.catCode().trim())
                : req.productIds();
        if (productIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "그 분류에 속한 도서가 없습니다: " + req.catCode());
        }
        var partnerIds = req.partnerIds().stream().distinct().toList();
        ProductVisibilityBulkRequest perProduct =
                new ProductVisibilityBulkRequest(partnerIds, req.open(), req.reason());

        int opened = 0;
        int closed = 0;
        int unchanged = 0;
        for (Long productId : productIds) {
            ProductVisibilityBulkResult r = bulkApply(productId, perProduct);
            opened += r.opened();
            closed += r.closed();
            unchanged += r.unchanged();
        }
        return new ProductVisibilityCategoryBulkResult(
                productIds.size(), partnerIds.size(), opened, closed, unchanged);
    }

    private void recordHistory(Long entityId, boolean from, boolean to, String what, String reason) {
        statusHistoryService.record(StatusEntityType.PARTNER_PRODUCT_VISIBILITY, entityId,
                "visible", from, to, what + reasonSuffix(reason));
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
