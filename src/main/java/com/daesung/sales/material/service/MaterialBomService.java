package com.daesung.sales.material.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.material.dto.MaterialBomRequest;
import com.daesung.sales.material.dto.MaterialBomResponse;
import com.daesung.sales.material.entity.MaterialBom;
import com.daesung.sales.material.repository.MaterialBomRepository;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세트·회차 ↔ 자재 매칭(33p). 근거: 발주처 구조보완요청안(2026-08-31).
 *
 * <p>★같은 자재를 <b>여러 세트에 중복 매칭</b>할 수 있다 — 그게 이 구조를 만든 이유다
 * ("국어 OMR 1개 자재를 여러 시행의 여러 회차에 매칭"). 다만 <b>같은 세트 안에서</b>
 * 같은 (회차, 자재) 조합은 한 번뿐이다. 두 번 넣으면 소요수량이 두 배가 된다.
 */
@Service
@RequiredArgsConstructor
public class MaterialBomService {

    private final MaterialBomRepository materialBomRepository;
    private final MaterialService materialService;
    private final ProductRepository productRepository;
    private final com.daesung.sales.inventory.repository.InventoryRepository inventoryRepository;

    @Transactional(readOnly = true)
    public MaterialBomResponse list(Long setProductId) {
        Product set = productOrThrow(setProductId);
        return MaterialBomResponse.of(set.getId(), set.getCode(), set.getName(),
                materialBomRepository.findBySet(setProductId));
    }

    /**
     * 전 세트의 자재 매칭을 한 번에. {@code commonOnly=true} 면 공통(회차 미지정)만.
     *
     * <p>세트 하나씩 보는 {@link #list}만으로는 "자재가 어디에 몇 개 걸려 있나"를 볼 수 없어
     * 신설했다(상품 14,811개를 순회할 수는 없다). F-37 회신(공통 매칭을 새 구조로 어떻게
     * 옮길지)의 대상 목록을 뽑는 데 쓰고, 33p 화면에서도 쓸 수 있다.
     */
    @Transactional(readOnly = true)
    public java.util.List<com.daesung.sales.material.dto.MaterialBomFlatRow> listAll(
            boolean commonOnly) {
        return materialBomRepository.findAllMatches(commonOnly).stream()
                .map(com.daesung.sales.material.dto.MaterialBomFlatRow::of)
                .toList();
    }

    /**
     * 매칭 추가. 같은 (세트, 회차, 자재)가 이미 있으면 <b>수량을 갱신</b>한다(upsert).
     *
     * <p>‼️공통 매칭(회차 null)은 DB UNIQUE가 막지 못한다 — MySQL은 NULL을 서로 다른
     * 값으로 보기 때문이다. 그래서 여기서 먼저 조회해 걸러낸다.
     */
    @Transactional
    public MaterialBomResponse add(Long setProductId, MaterialBomRequest req) {
        Product set = productOrThrow(setProductId);
        Product round = (req.roundProductId() == null) ? null : productOrThrow(req.roundProductId());
        if (round != null && round.getId().equals(set.getId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "회차가 세트 자신입니다. 세트 전체에 붙는 자재라면 회차를 비워 '공통'으로 등록하세요.");
        }

        materialBomRepository.findMatch(setProductId, req.roundProductId(), req.materialId())
                .ifPresentOrElse(
                        existing -> existing.updateQty(req.qtyPerSet(), req.perRound()),
                        () -> materialBomRepository.save(MaterialBom.of(set, round,
                                materialService.getOrThrow(req.materialId()), req.qtyPerSet(), req.perRound())));

        return list(setProductId);
    }

    /** 매칭 해제. */
    @Transactional
    public MaterialBomResponse remove(Long setProductId, Long bomId) {
        MaterialBom b = materialBomRepository.findById(bomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "자재 매칭이 없습니다. id=" + bomId));
        if (!b.getSetProduct().getId().equals(setProductId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "다른 세트의 매칭입니다. setProductId=" + setProductId);
        }
        materialBomRepository.delete(b);
        return list(setProductId);
    }

    /** 자재 삭제 전 매칭 건수(삭제 차단 판단). */
    @Transactional(readOnly = true)
    public long matchedCount(Long materialId) {
        return materialBomRepository.countByMaterialId(materialId);
    }

    /** 세트의 매칭 전체(수불부 자재 상세 집계에서 재사용). */
    @Transactional(readOnly = true)
    public List<MaterialBom> bomsOf(Long setProductId) {
        return materialBomRepository.findBySet(setProductId);
    }

    private Product productOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "상품이 없습니다. id=" + id));
    }

    /**
     * 세트 가용재고 — <b>구성 회차별 시험지 재고의 최소값</b>.
     *
     * <p>근거: 발주처 확정(2026-10-23) "세트 재고는 구성 회차별 시험지 재고의 최소값.
     * 예) 1~4회 100/120/105/111 → 세트 100. 회차 단독판매도 동일 기준."
     *
     * <p>★<b>세트는 따로 쌓아 두는 물건이 아니다.</b> 회차 시험지를 모아 만드는 것이라
     * "몇 세트 나갈 수 있나"는 <b>가장 모자란 회차</b>가 정한다.
     *
     * <p>‼️<b>시험지만 본다.</b> OMR·라벨·단행본은 세트 수를 정하는 축이 아니다 —
     * 발주처 문구가 "구성 회차별 <b>시험지</b> 재고"다. 다른 자재까지 넣으면
     * 라벨이 모자라서 세트가 0으로 보이는 일이 생긴다.
     *
     * <p>‼️재고가 <b>음수</b>면 세트도 음수로 낸다. 0으로 올리면 "만들 수 있다"로 읽혀
     * 모자란 사실이 묻힌다(재고 음수를 막지 않기로 한 것과 같은 결).
     */
    @Transactional(readOnly = true)
    public com.daesung.sales.material.dto.SetStockResponse setStock(Long setProductId) {
        Product set = productRepository.findById(setProductId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "상품이 없습니다. id=" + setProductId));

        List<MaterialBom> boms = materialBomRepository.findBySet(setProductId).stream()
                .filter(b -> b.getMaterial().getMaterialType()
                        == com.daesung.sales.product.entity.MaterialType.EXAM_PAPER)
                .toList();
        if (boms.isEmpty()) {
            // 구성이 없으면 null — 0과 다르다. 0은 "못 만든다", null 은 "구성이 안 잡혀 있다"다.
            return new com.daesung.sales.material.dto.SetStockResponse(
                    set.getId(), set.getCode(), set.getName(), null, List.of());
        }

        java.util.Map<Long, Integer> stock = new java.util.HashMap<>();
        for (Object[] r : inventoryRepository.sumByMaterialIds(
                boms.stream().map(b -> b.getMaterial().getId()).distinct().toList())) {
            stock.put((Long) r[0], ((Number) r[1]).intValue());
        }

        List<com.daesung.sales.material.dto.SetStockResponse.Round> rounds = new java.util.ArrayList<>();
        Integer min = null;
        for (MaterialBom b : boms) {
            int qty = stock.getOrDefault(b.getMaterial().getId(), 0);
            // 소요수량으로 나눈다 — 예시(소요 1)에서는 그대로 재고가 되고, 2 이상이면 그만큼 준다.
            int possible = Math.floorDiv(qty, b.getQtyPerSet());
            min = (min == null) ? possible : Math.min(min, possible);
            Product round = b.getRoundProduct();
            rounds.add(new com.daesung.sales.material.dto.SetStockResponse.Round(
                    (round == null) ? null : round.getId(),
                    (round == null) ? "(공통)" : round.getCode(),
                    (round == null) ? "공통 자재" : round.getName(),
                    b.getMaterial().getId(), b.getMaterial().getCode(), b.getMaterial().getName(),
                    qty, b.getQtyPerSet(), possible, false));
        }

        // 병목 표시 — 어느 회차가 세트 수를 정했는지 화면이 바로 짚을 수 있게 한다.
        final int bottleneck = min;
        List<com.daesung.sales.material.dto.SetStockResponse.Round> marked = rounds.stream()
                .map(r -> r.possibleSets() == bottleneck
                        ? new com.daesung.sales.material.dto.SetStockResponse.Round(
                                r.roundProductId(), r.roundProductCode(), r.roundProductName(),
                                r.materialId(), r.materialCode(), r.materialName(),
                                r.stockQty(), r.qtyPerSet(), r.possibleSets(), true)
                        : r)
                .toList();

        return new com.daesung.sales.material.dto.SetStockResponse(
                set.getId(), set.getCode(), set.getName(), min, marked);
    }
}
