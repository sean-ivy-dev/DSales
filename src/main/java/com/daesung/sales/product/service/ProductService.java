package com.daesung.sales.product.service;

import com.daesung.sales.common.audit.CurrentAuditor;
import com.daesung.sales.audit.entity.MasterEntityType;
import com.daesung.sales.audit.service.MasterChangeLogService;
import com.daesung.sales.common.code.MasterCodes;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.product.dto.BomRegisterRequest;
import com.daesung.sales.product.dto.BomResponse;
import com.daesung.sales.product.dto.ProductFlagBulkRequest;
import com.daesung.sales.product.dto.ProductFlagBulkResult;
import com.daesung.sales.product.dto.ProductCategoryRow;
import com.daesung.sales.product.dto.ProductCreateRequest;
import com.daesung.sales.product.dto.ProductResponse;
import com.daesung.sales.product.dto.ProductUpdateRequest;
import com.daesung.sales.product.entity.BomItem;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.entity.SalesDivision;
import com.daesung.sales.product.repository.BomItemRepository;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.product.repository.SalesDivisionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final MasterChangeLogService masterChangeLogService;
    private final BomItemRepository bomItemRepository;
    private final CurrentAuditor currentAuditor;
    private final SalesDivisionRepository salesDivisionRepository;
    private final SalesDivisionService salesDivisionService;

    /**
     * 도서 목록. 검색어 + <b>노출 플래그 2종</b>으로 좁힌다.
     *
     * <p>★수불부노출과 단가노출은 <b>다른 축</b>이다(발주처 2026-08-21 E-7로 분리).
     * 수불부엔 안 나와도 단가는 매기는 상품이 있어 한 값으로 묶을 수 없다.
     */
    public PageResponse<ProductResponse> findAll(String keyword, Boolean ledgerVisible,
                                                 Boolean priceVisible, Pageable pageable) {
        return findAll(keyword, ledgerVisible, priceVisible, null, false, pageable);
    }

    /** 위와 같되 {@code updatedSince} 이후 등록·수정된 것만(B-1). */
    public PageResponse<ProductResponse> findAll(String keyword, Boolean ledgerVisible,
                                                 Boolean priceVisible,
                                                 java.time.LocalDateTime updatedSince,
                                                 Pageable pageable) {
        return findAll(keyword, ledgerVisible, priceVisible, updatedSince, false, pageable);
    }

    /**
     * 위와 같되 사용여부 N(=삭제한 도서)을 포함할지 고를 수 있다.
     *
     * <p>근거: 발주처 검수 F-36. 기본은 <b>제외</b>다 — 삭제 API가 {@code use_yn=false}
     * 논리삭제인데 목록이 그걸 안 걸러서 "지웠는데 그대로 있다"로 보였다.
     */
    public PageResponse<ProductResponse> findAll(String keyword, Boolean ledgerVisible,
                                                 Boolean priceVisible,
                                                 java.time.LocalDateTime updatedSince,
                                                 boolean includeUnused,
                                                 Pageable pageable) {
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Page<Product> page = productRepository.search(
                kw, ledgerVisible, priceVisible, includeUnused, updatedSince, pageable);
        // 세부구분 마스터는 몇 줄짜리라 한 번에 읽어 맵으로 쓴다 — 상품마다 조회하면 목록 한 장에 수백 번이 된다.
        Map<String, SalesDivision> divisions = divisionsByCode();
        return PageResponse.of(page.map(p -> ProductResponse.from(p, divisions.get(p.getSalesDivision()))));
    }

    /**
     * 쓰이고 있는 분류코드 목록(도서 등록 화면 선택용).
     * 마스터 테이블이 아니라 실제 사용 중인 값의 집계다 — {@link ProductCategoryRow} 참고.
     */
    @Transactional(readOnly = true)
    public List<ProductCategoryRow> usedCategories() {
        return productRepository.findUsedCategories().stream()
                .map(a -> new ProductCategoryRow(a.getCatCode(), a.getCatName(), a.getProductCount()))
                .toList();
    }

    public ProductResponse findById(Long id) {
        Product product = getOrThrow(id);
        return ProductResponse.from(product, findDivision(product.getSalesDivision()));
    }

    /** 세부구분 코드 → 마스터 전량. 고정 목록 수준의 크기라 통째로 읽는다. */
    private Map<String, SalesDivision> divisionsByCode() {
        Map<String, SalesDivision> map = new java.util.HashMap<>();
        for (SalesDivision d : salesDivisionRepository.findAll()) {
            map.put(d.getCode(), d);
        }
        return map;
    }

    /** 세부구분 단건. 미지정(null/공백)이면 null — 대분류가 '미분류'로 나간다. */
    private SalesDivision findDivision(String code) {
        return (code == null || code.isBlank())
                ? null : salesDivisionRepository.findByCode(code).orElse(null);
    }

    @Transactional
    public ProductResponse create(ProductCreateRequest req) {
        // ★값이 정해진 항목은 저장 직전에 좁힌다. 화면 셀렉트만 믿으면 API 직접 호출로 뚫린다.
        String grade = MasterCodes.grade(req.grade());
        // ★도서코드는 **분류 안에서만** 유일하다. 레거시 bookData 의 기본키가
        //   (catCode, bookCode) 복합키였고, 01·02·03 같은 짧은 코드를 분류마다 다시 쓴다.
        //   전역 유니크로 막고 있어 같은 코드를 다른 분류에 못 넣었다(2026-09-17 지적 B-11).
        //   우리 매출 업로드도 이미 (분류+도서) 조합으로 상품을 찾고 있어 저장 쪽만 어긋나 있었다.
        String catCode = (req.catCode() == null) ? "" : req.catCode();
        productRepository.findByCatCodeAndCode(catCode, req.code()).ifPresent(p -> {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이미 존재하는 상품코드: " + req.code() + " (분류 " + catCode + ")");
        });
        // 세부구분은 이제 마스터 값이다. 예전처럼 자유 문자열로 두면 오타가 그대로 새 구분이 되어
        // 같은 뜻의 값이 여러 표기로 흩어지고, 대분류 매핑이 없어 집계에서 빠진다.
        salesDivisionService.validateCode(req.salesDivision());
        Product product = Product.create(
                req.code(), req.name(), req.contentType(), req.set(),
                req.price(), req.taxFree(), grade,
                req.catCode(), req.catName(), req.useYnOrDefault(),
                req.salesDivision(), req.ledgerVisibleOrDefault(), req.webVisibleOrDefault(),
                req.stockManagedOrDefault());
        product.applyExtra(req.productYear(), req.productType(), req.supplyRate());
        // 단가노출은 미지정 시 수불부노출을 따른다 — V60 분리 이전 데이터와 같은 모양이 되게.
        product.applyPriceVisible((req.priceVisible() != null)
                ? req.priceVisible() : req.ledgerVisibleOrDefault());
        // 지정 거래처 전용은 미지정이면 false — 기본은 '매핑대로'다(11/04 회신 §1).
        product.applyRestrictedVisible((req.restrictedVisible() != null) && req.restrictedVisible());
        Product saved = productRepository.save(product);
        return ProductResponse.from(saved, findDivision(saved.getSalesDivision()));
    }

    /** 도서 수정. 바뀐 필드는 변경이력에 남는다(발주처 확정 3-1 라). 스냅샷은 수정 전에 뜬다. */
    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest req) {
        Product product = getOrThrow(id);
        // 도서 수정은 부분 수정이라 서로 다른 칸을 고치면 덜 위험하지만,
        // 같은 칸을 동시에 고치면 여전히 나중 저장이 이긴다. 버전을 보내면 그걸 막는다.
        com.daesung.sales.common.idempotency.VersionGuard.check(
                req.version(), product.getVersion(), "도서");
        String grade = MasterCodes.grade(req.grade());
        // ★수정은 **부분 수정**이다(안 보낸 필드는 유지). 그래서 필수 애너테이션을 뗐는데,
        //   이름만은 빈 문자열을 막는다 — 이름 없는 상품은 목록·리포트에서 찾을 수 없다.
        //   null(안 보냄)과 ""(비우겠다)는 다른 의사표시라 여기서 갈라 준다.
        if (req.name() != null && req.name().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "상품명은 비울 수 없습니다.");
        }
        // ★수정에서 세부구분을 **비우는 것**만 막는다(프론트 G-3 "등록·수정 때 필수").
        //   ‼️null 은 막지 않는다 — 이 화면은 부분 수정이라 null = "안 보냈다 = 유지"다.
        //   필수로 쳐서 null 까지 막으면 정가만 고치려 보낸 요청이 거부된다.
        //   ""(비우겠다)와 null(안 보냄)은 다른 의사표시라 여기서 갈라 준다(상품명과 같은 규칙).
        if (req.salesDivision() != null && req.salesDivision().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "세부구분은 비울 수 없습니다 — 비우면 그 상품 매출이 미분류로 떨어집니다.");
        }
        salesDivisionService.validateCode(req.salesDivision());
        Map<String, String> before = product.auditSnapshot();
        product.update(req.name(), req.contentType(), req.set(),
                req.price(), req.taxFree(), grade,
                req.catCode(), req.catName(), req.useYn(),
                req.salesDivision(), req.ledgerVisible(), req.webVisible(), req.stockManaged());
        product.applyExtra(req.productYear(), req.productType(), req.supplyRate());
        // 수정에서는 미지정이면 기존 값을 그대로 둔다(다른 필드와 같은 규칙).
        product.applyPriceVisible(req.priceVisible());
        product.applyRestrictedVisible(req.restrictedVisible());
        masterChangeLogService.recordDiff(MasterEntityType.PRODUCT, product.getId(), product.getCode(),
                before, product.auditSnapshot());
        return ProductResponse.from(product, findDivision(product.getSalesDivision()));
    }

    @Transactional
    public void deactivate(Long id) {
        getOrThrow(id).deactivate();
    }

    /**
     * BOM 구성 등록(기존 구성 대체). 완제품은 세트(is_set)로 표시.
     *
     * <p>기존 구성은 물리삭제가 아니라 논리삭제로 대체된다(게이트규칙) — 언제 어떤 구성이었는지가
     * 남아야 과거 조립/해체 수량을 소명할 수 있다.
     */
    @Transactional
    public BomResponse registerBom(Long parentId, BomRegisterRequest req) {
        getOrThrow(parentId);   // 존재 검증(삭제 전)
        bomItemRepository.softDeleteByParentId(parentId, LocalDateTime.now(), currentAuditor.username());
        Product parent = getOrThrow(parentId);   // 벌크 UPDATE가 컨텍스트를 clear하므로 재로딩
        List<BomItem> saved = new ArrayList<>();
        for (BomRegisterRequest.Component c : req.components()) {
            if (c.childProductId().equals(parentId)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "구성품이 완제품과 같을 수 없습니다.");
            }
            Product child = productRepository.findById(c.childProductId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "구성품 상품이 없습니다. id=" + c.childProductId()));
            saved.add(bomItemRepository.save(BomItem.create(parent, child, c.ratio())
                    .applyDetail(c.round(), c.examDate(), c.separatePack(), c.materialType())));
        }
        parent.markAsSet();
        return BomResponse.from(parent, saved);
    }

    public BomResponse getBom(Long parentId) {
        Product parent = getOrThrow(parentId);
        return BomResponse.from(parent, bomItemRepository.findByParentId(parentId));
    }

    // ── 거래처별 단가는 축이 바뀌어 이 클래스에서 빠졌다 ─────────────────────
    //   34p는 도서 × 거래처가 아니라 **거래처 × 대분류**다(정본 34p, V42).
    //   담당 클래스: PartnerSupplyRateService.

    /**
     * 도서 Y/N 항목 일괄 변경. 근거: 발주처 요청(1-3) "각 열을 일괄로 처리(전체선택 등)".
     *
     * <p>지정하지 않은 플래그는 건드리지 않는다. 그리고 <b>변경이력을 건별로 남긴다</b> —
     * 한 번에 수백 건을 바꾸는 동작이라, 잘못 눌렀을 때 무엇이 바뀌었는지 되짚을 수 없으면
     * 원상복구가 불가능하다(발주처가 변경이력을 요청한 이유가 바로 이런 경우다).
     */
    @Transactional
    public ProductFlagBulkResult bulkUpdateFlags(ProductFlagBulkRequest req) {
        if (req.hasNoFlag()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "변경할 항목을 하나 이상 지정해야 합니다(Web게시·면세·수불부노출·사용여부·재고관리).");
        }
        int changed = 0;
        int unchanged = 0;
        List<Long> notFound = new ArrayList<>();

        // ★분류코드를 주면 그 분류 전체가 대상이다(발주처 2026-08-31: 수불부/단가 노출은
        //   분류명(콘텐츠) 단위로 설정). 저장은 도서 단위 그대로라 단일 소스가 유지된다 —
        //   분류별 설정을 따로 저장하면 도서 값과 어긋났을 때 어느 쪽이 이기는지가 또 규칙이 된다.
        List<Long> targets = (req.catCode() != null && !req.catCode().isBlank())
                ? productRepository.findIdsByCatCode(req.catCode().trim())
                : (req.productIds() == null ? List.of() : req.productIds());
        if (targets.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "대상이 없습니다. 도서 id 목록이나 분류코드를 지정하세요.");
        }

        for (Long id : targets.stream().distinct().toList()) {
            var found = productRepository.findById(id);
            if (found.isEmpty()) {
                notFound.add(id);
                continue;
            }
            Product p = found.get();
            Map<String, String> before = p.auditSnapshot();
            p.updateFlags(req.webVisible(), req.taxFree(), req.ledgerVisible(),
                    req.useYn(), req.stockManaged(), req.priceVisible(), req.restrictedVisible());
            Map<String, String> after = p.auditSnapshot();
            if (before.equals(after)) {
                unchanged++;
                continue;
            }
            masterChangeLogService.recordDiff(MasterEntityType.PRODUCT, p.getId(), p.getCode(),
                    before, after);
            changed++;
        }
        return new ProductFlagBulkResult(targets.size(), changed, unchanged, notFound);
    }

    /** 거래처별 단가 스냅샷(변경이력용). */
    private Product getOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "상품이 없습니다. id=" + id));
    }
}
