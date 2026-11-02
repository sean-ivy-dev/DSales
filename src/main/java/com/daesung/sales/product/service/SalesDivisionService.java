package com.daesung.sales.product.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.product.dto.MajorCategoryResponse;
import com.daesung.sales.product.dto.SalesDivisionRequest;
import com.daesung.sales.product.dto.SalesDivisionResponse;
import com.daesung.sales.product.entity.MajorCategory;
import com.daesung.sales.product.entity.SalesDivision;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.product.repository.SalesDivisionRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세부구분(구 '매출구분') 마스터 관리. 근거: 발주처 회신 2026-08-20
 * "명칭 자체가 연도별로 바뀔 수 있어 사용자가 직접 추가/삭제할 수 있는 관리형 값".
 *
 * <p>대분류는 5종 고정이라 관리 대상이 아니다({@link MajorCategory}). 여기서 관리하는 건
 * 그 아래 세부구분과, <b>세부구분이 어느 대분류에 속하는지의 매핑</b>이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesDivisionService {

    private final SalesDivisionRepository salesDivisionRepository;
    private final ProductRepository productRepository;

    /**
     * 세부구분 목록.
     *
     * @param majorCategory 대분류 필터(null=전체)
     * @param includeUnused true면 미사용(use_yn=false)도 포함. 기본은 사용중인 것만 —
     *                      상품 등록 화면에서 이미 끈 구분이 다시 선택지로 보이면 안 된다.
     */
    public List<SalesDivisionResponse> findAll(MajorCategory majorCategory, boolean includeUnused) {
        List<SalesDivision> all = (majorCategory == null)
                ? salesDivisionRepository.findAllByOrderByMajorCategoryAscSortOrderAscCodeAsc()
                : salesDivisionRepository.findByMajorCategoryOrderBySortOrderAscCodeAsc(majorCategory);
        return all.stream()
                .filter(d -> includeUnused || d.isUseYn())
                .map(SalesDivisionResponse::from)
                .toList();
    }

    /**
     * 대분류별로 묶은 트리. 32p 상품 등록이 "대분류 먼저 선택 → 하위 세부구분 입력"이라
     * 한 번 호출로 두 단계를 다 채우게 한다.
     *
     * <p>IC는 빠진다 — 회신 "현재 미사용이나 데이터는 보존해야 하니 화면상 숨김 처리".
     * 숨기는 것이지 지우는 게 아니라서, IC로 저장된 기존 데이터는 조회·집계에서 그대로 살아 있다.
     */
    public List<MajorCategoryResponse> majorCategories(boolean includeUnused) {
        List<MajorCategoryResponse> out = new ArrayList<>();
        for (MajorCategory c : MajorCategory.values()) {
            if (!c.visible()) {
                continue;
            }
            out.add(MajorCategoryResponse.of(c, findAll(c, includeUnused)));
        }
        return out;
    }

    @Transactional
    public SalesDivisionResponse create(SalesDivisionRequest req) {
        if (salesDivisionRepository.existsByCode(req.code())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이미 존재하는 세부구분 코드: " + req.code());
        }
        SalesDivision d = SalesDivision.create(req.code(), req.name(), req.majorCategory(),
                req.useYn(), req.sortOrder());
        return SalesDivisionResponse.from(salesDivisionRepository.save(d));
    }

    /** 수정. 코드는 바꾸지 않는다 — 상품이 그 값으로 연결돼 있어 바꾸면 연결이 끊긴다. */
    @Transactional
    public SalesDivisionResponse update(Long id, SalesDivisionRequest req) {
        SalesDivision d = getOrThrow(id);
        d.update(req.name(), req.majorCategory(), req.useYn(), req.sortOrder());
        return SalesDivisionResponse.from(d);
    }

    /**
     * 삭제. <b>쓰는 상품이 있으면 지우지 않고 비활성으로 돌린다.</b>
     *
     * <p>지워버리면 그 상품들의 세부구분이 어느 대분류였는지 알 수 없게 되고,
     * 매출 집계에서 통째로 빠진다. 담당자에겐 목록에서 사라진 것으로 똑같이 보이되
     * 과거 데이터는 살아 있는 쪽이 맞다.
     *
     * @return true=실제 삭제, false=사용 중이라 비활성 처리
     */
    @Transactional
    public boolean delete(Long id) {
        SalesDivision d = getOrThrow(id);
        if (productRepository.countBySalesDivision(d.getCode()) > 0) {
            d.deactivate();
            return false;
        }
        salesDivisionRepository.delete(d);
        return true;
    }

    /**
     * 상품 저장 시 세부구분 코드 검증. 마스터에 없는 값이면 거부한다.
     *
     * <p>예전엔 자유 문자열이라 오타가 그대로 새 구분이 되어, 같은 뜻의 값이 여러 표기로 흩어졌다.
     *
     * <p>‼️<b>여기서는 null·빈값을 통과시킨다 — 필수 여부는 호출부가 정한다.</b>
     * <ul>
     *   <li><b>등록</b>: {@code ProductCreateRequest.salesDivision} 에 {@code @NotBlank} —
     *       필수다(10/27 §7-C 3번 "신규 상품에서 미분류가 생기지 않게", 프론트 G-3).</li>
     *   <li><b>수정</b>: null 은 "안 보냈다 = 유지"라 통과시키고, {@code ""}(비우겠다)만
     *       {@code ProductService.update} 에서 막는다. null 까지 막으면 정가만 고치려 보낸
     *       요청이 거부된다.</li>
     * </ul>
     * 예전 주석은 "세부구분 미지정 상품이 실재한다"를 근거로 들었는데,
     * <b>운영 실측(2026-10-30) 결과 상품 14,808건 전부 채워져 있어</b> 그 전제가 이미 깨졌다.
     *
     * <p>★<b>존재만 보고 사용여부는 보지 않는다.</b> 비활성 구분까지 막으면 그 구분을 쓰던
     * 기존 상품을 이름 하나 고치는 것조차 못 하게 된다(수정도 이 검증을 지난다).
     * 새로 고르지 못하게 하는 건 목록에서 빼는 것으로 충분하다 — IC가 그 경우다.
     */
    public void validateCode(String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        if (!salesDivisionRepository.existsByCode(code)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "등록되지 않은 세부구분입니다: " + code + " (기초관리 > 세부구분에서 먼저 등록하세요)");
        }
    }

    private SalesDivision getOrThrow(Long id) {
        return salesDivisionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "세부구분이 없습니다. id=" + id));
    }

    /**
     * 대분류가 <b>특강</b>인 세부구분 코드들. 특강을 빼야 하는 쪽이 전부 이걸 쓴다 —
     * 출고/반품 조회(§3-4·4-12)와 <b>물류 작업비</b>(§10 "출고/반품 조회의 출고 수량").
     *
     * <p>★여기로 올린 이유: 예전엔 {@code SaleService} 안의 private 메서드였고, 물류비는
     * 특강을 <b>아예 안 걸렀다</b>. 두 화면이 같은 수량을 보여야 하는데 조건이 한쪽에만
     * 있었던 것이다. 복붙하면 또 어긋나므로 한 곳에 둔다.
     *
     * <p>매출 행에는 세부구분(코드)만 있고 대분류는 마스터에 있어, 쿼리가 대분류를 바로 못 본다.
     * 비어 있으면 {@code in} 이 아무것도 안 걸러야 하므로 절대 안 맞는 값 하나를 넣는다
     * (빈 컬렉션은 JPQL {@code in} 에서 문법 오류다).
     */
    @Transactional(readOnly = true)
    public List<String> lectureDivisionCodes() {
        List<String> codes = new ArrayList<>();
        for (SalesDivision d : salesDivisionRepository.findAll()) {
            if (d.getMajorCategory() == MajorCategory.SPECIAL_LECTURE) {
                codes.add(d.getCode());
            }
        }
        return codes.isEmpty() ? List.of("\u0000") : codes;
    }
}
