package com.daesung.sales.logistics.service;

import com.daesung.sales.common.audit.CurrentAuditor;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.query.Keywords;
import com.daesung.sales.logistics.dto.ProductLogisRateRequest;
import com.daesung.sales.logistics.dto.ProductLogisRateResponse;
import com.daesung.sales.logistics.dto.ProductLogisRateUpdateRequest;
import com.daesung.sales.logistics.entity.ProductLogisRate;
import com.daesung.sales.logistics.entity.WorkType;
import com.daesung.sales.logistics.repository.ProductLogisRateRepository;
import com.daesung.sales.logistics.repository.WorkTypeRepository;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.ProductRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매출프로그램 상품의 물류 단가(36p "신규등록"). 근거: 발주처 회신 §1-10 원문 —
 * "매출프로그램에 등록된 상품을 불러와 <b>작업구분을 선택하면 단가가 자동 적용되는 별도 '신규등록'</b> 신설".
 *
 * <p>★DSRE 시행 단가({@code tbl_logis_cost})와 <b>별개 축</b>이다. 그쪽은 {@code DTL_CD}
 * (시행일코드)로 키를 잡는데 우리 상품엔 시행코드가 없고, DSRE2는 분리 유지라 우리가 발급할 수도 없다.
 * 화면이 "물류비용 신규 등록은 시행코드가 있어야 합니다"로 막아 둔 이유가 그것이다.
 *
 * <p>★<b>차단하지 않고 경고한다.</b> DSRE 병행(N) 상품에 단가를 등록해도 막지 않는다 —
 * 단독/병행 구분값의 필드 구조가 아직 발주처 확인 대기라(한 필드 vs 두 필드), 지금 하드블록을 걸면
 * 방향이 뒤집힐 때 등록해 둔 데이터가 통째로 막힌다. 대신 경고로 알린다.
 */
@Service
@RequiredArgsConstructor
public class ProductLogisRateService {

    private final ProductLogisRateRepository rateRepository;
    private final ProductRepository productRepository;
    private final WorkTypeRepository workTypeRepository;
    private final CurrentAuditor currentAuditor;

    /** 목록. 작업구분·키워드(상품코드·상품명) 필터. */
    @Transactional(readOnly = true)
    public List<ProductLogisRateResponse> search(Integer packType, String keyword,
                                                 Integer productYear) {
        String kw = Keywords.norm(keyword);
        List<ProductLogisRate> rows = rateRepository.search(packType, (kw == null) ? null : "%" + kw + "%");
        Map<Integer, String> names = workTypeNames();
        List<ProductLogisRateResponse> out = new ArrayList<>(rows.size());
        for (ProductLogisRate r : rows) {
            // ★연도는 분류코드에서 파생되는 값이라 SQL로 걸러내지 않고 여기서 본다.
            //   컬럼이 아니므로 인덱스를 탈 수도 없고, 이 목록은 단가 등록분뿐이라 양이 작다.
            if (productYear != null && !productYear.equals(r.getProduct().catCodeYear())) {
                continue;
            }
            out.add(ProductLogisRateResponse.from(r, names.get(r.getPackType())));
        }
        return out;
    }

    /**
     * 좌측 상품 트리 — <b>연도 &gt; 분류 &gt; 상품</b> 3단(F-38).
     *
     * <p>정본 33p가 "좌측 트리뷰 상품 선택에 연도 구분 추가"를 요구하는데 서버가 트리 데이터를
     * 준 적이 없었다. 연도 축은 {@link Product#catCodeYear()} — 레거시와 같은 규칙이다.
     *
     * <p>★3단으로 나누는 이유: 상품이 14,811개다. 연도 21 → 분류 889 → 상품으로 접어야
     * 화면에서 쓸 수 있다(연도당 분류 70~90개).
     *
     * <p>{@code withRateOnly=true} 면 <b>단가가 등록된 상품만</b>. 기본은 전체다 —
     * 트리에서 상품을 골라 단가를 <b>새로 등록</b>하는 화면이라 미등록 상품이 보여야 한다.
     */
    @Transactional(readOnly = true)
    public List<com.daesung.sales.logistics.dto.ProductTreeNode> tree(boolean withRateOnly,
                                                                      String keyword) {
        String kw = Keywords.norm(keyword);
        java.util.Set<Long> rated = withRateOnly
                ? rateRepository.findAll().stream()
                        .map(r -> r.getProduct().getId()).collect(java.util.stream.Collectors.toSet())
                : java.util.Set.of();
        List<Product> products = productRepository.findAll().stream()
                .filter(Product::isUseYn)
                .filter(p -> !withRateOnly || rated.contains(p.getId()))
                .filter(p -> kw == null
                        || contains(p.getCode(), kw) || contains(p.getName(), kw)
                        || contains(p.getCatCode(), kw) || contains(p.getCatName(), kw))
                .toList();

        // 연도 내림차순(최근 먼저) → 분류코드 → 상품코드. 레거시 정렬과 같다.
        java.util.Map<Integer, java.util.Map<String, List<Product>>> grouped =
                new java.util.TreeMap<>(java.util.Comparator.nullsLast(
                        java.util.Comparator.reverseOrder()));
        for (Product p : products) {
            grouped.computeIfAbsent(p.catCodeYear(), y -> new java.util.TreeMap<>())
                    .computeIfAbsent(nz(p.getCatCode()), c -> new ArrayList<>())
                    .add(p);
        }

        List<com.daesung.sales.logistics.dto.ProductTreeNode> out = new ArrayList<>();
        grouped.forEach((year, byCat) -> {
            List<com.daesung.sales.logistics.dto.ProductTreeNode.CatNode> cats = new ArrayList<>();
            byCat.forEach((catCode, list) -> {
                list.sort(java.util.Comparator.comparing(Product::getCode,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
                cats.add(new com.daesung.sales.logistics.dto.ProductTreeNode.CatNode(
                        catCode, list.get(0).getCatName(),
                        list.stream().map(p -> new com.daesung.sales.logistics.dto.ProductTreeNode.Leaf(
                                p.getId(), p.getCode(), p.getName(),
                                withRateOnly || rated.contains(p.getId()))).toList()));
            });
            int cnt = cats.stream().mapToInt(c -> c.products().size()).sum();
            out.add(new com.daesung.sales.logistics.dto.ProductTreeNode(year, cnt, cats));
        });
        return out;
    }

    private static boolean contains(String v, String kw) {
        return v != null && v.toLowerCase(java.util.Locale.ROOT).contains(kw.toLowerCase(java.util.Locale.ROOT));
    }

    private static String nz(String v) {
        return (v == null) ? "" : v;
    }

    /**
     * 신규등록 — 상품 + 작업구분만 받고 <b>단가는 작업구분 기준단가를 그대로 복사</b>한다.
     *
     * <p>복사인 이유: 참조로 두면 기준단가를 고치는 순간 과거 출고의 작업비까지 따라 바뀐다
     * (발주처 §1-1 "저장된 출고 작업비는 단가 변경에 소급되지 않아야 함").
     */
    @Transactional
    public ProductLogisRateResponse.Result create(ProductLogisRateRequest req) {
        Product product = productRepository.findById(req.productId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "상품이 없습니다. id=" + req.productId()));
        if (rateRepository.existsByProductId(product.getId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이미 물류단가가 등록된 상품입니다: " + product.getCode()
                            + ". 수정으로 변경하세요(상품 하나에 단가는 하나다).");
        }
        WorkType w = workType(req.packType());

        ProductLogisRate saved = rateRepository.save(ProductLogisRate.of(product, w, req.bSpare()));
        return result(saved, w.getName(), warningsFor(product));
    }

    /**
     * 개별 수정. 지정한 항목만 바뀌고, <b>이 행은 예외로 표시</b>되어 이후 일괄반영이 건너뛴다.
     *
     * <p>작업구분만 바꾸는 요청이면 새 기준단가로 다시 채우고 예외 표시를 <b>푼다</b> —
     * 작업구분을 바꿨다는 건 그 기준단가를 쓰겠다는 뜻이다.
     */
    @Transactional
    public ProductLogisRateResponse.Result update(Long id, ProductLogisRateUpdateRequest req) {
        ProductLogisRate rate = getOrThrow(id);

        if (req.packType() != null && req.packType() != rate.getPackType() && req.hasNoRateField()) {
            WorkType w = workType(req.packType());
            rate.changeWorkType(w);
            return result(rate, w.getName(), warningsFor(rate.getProduct()));
        }
        if (req.packType() != null) {
            // 단가를 같이 줬다면 작업구분만 바꾸고 단가는 입력값이 이긴다(예외 표시는 update가 붙인다).
            rate.changeWorkType(workType(req.packType()));
        }
        rate.update(req.paper(), req.omr(), req.etc(), req.label(),
                req.basic(), req.trade(), req.bSpare());
        return result(rate, workTypeNames().get(rate.getPackType()), warningsFor(rate.getProduct()));
    }

    /** 삭제(논리). 행은 남고 누가·언제 지웠는지 추적된다. */
    @Transactional
    public void delete(Long id) {
        getOrThrow(id).markDeleted(currentAuditor.username());
    }

    /**
     * 작업구분 기준단가를 <b>우리 단가 행에</b> 일괄 반영한다(36p ①).
     * DSRE 쪽 반영({@code WorkTypeService.apply})과 같은 규칙 — 개별 수정한 행은 건너뛴다.
     *
     * @param dryRun true면 아무것도 바꾸지 않고 대상만 센다
     * @return [반영(될) 건수, 건너뛴 건수]
     */
    @Transactional
    public int[] applyWorkType(int packType, boolean dryRun) {
        WorkType w = workType(packType);
        int applied = 0;
        int skipped = 0;
        for (ProductLogisRate r : rateRepository.findByPackType(packType)) {
            if (r.isOverridden()) {
                skipped++;
                continue;
            }
            applied++;
            if (!dryRun) {
                r.applyFrom(w);
            }
        }
        return new int[]{applied, skipped};
    }

    /**
     * 경고 — 막지 않고 알린다.
     *
     * <p>DSRE 병행(N) 상품은 단가 기준이 DSRE라, 여기 등록한 값이 실제 계산에 안 쓰일 수 있다.
     * 등록 자체를 막지 않는 이유는 클래스 주석 참고(구분값 필드 구조가 확인 대기다).
     */
    private static List<String> warningsFor(Product product) {
        List<String> out = new ArrayList<>();
        if (!product.isPriceVisible()) {
            out.add("이 상품은 'DSRE 병행'(단가노출 N)으로 설정돼 있습니다 — "
                    + "물류비 계산이 DSRE 기준을 따르면 여기 등록한 단가는 쓰이지 않습니다. "
                    + "단독 관리로 쓰려면 도서관리에서 단가노출을 Y로 바꾸세요.");
        }
        return out;
    }

    private WorkType workType(Integer packType) {
        return workTypeRepository.findByPackType(packType)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                        "등록되지 않은 작업구분입니다: " + packType + ". 작업구분 관리에 먼저 등록하세요."));
    }

    private ProductLogisRate getOrThrow(Long id) {
        return rateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "물류단가가 없습니다. id=" + id));
    }

    private Map<Integer, String> workTypeNames() {
        Map<Integer, String> m = new HashMap<>();
        workTypeRepository.findAll().forEach(w -> m.put(w.getPackType(), w.getName()));
        return m;
    }

    private static ProductLogisRateResponse.Result result(ProductLogisRate r, String name,
                                                          List<String> warnings) {
        return new ProductLogisRateResponse.Result(ProductLogisRateResponse.from(r, name), warnings);
    }
}
