package com.daesung.sales.warehouse.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.warehouse.dto.WarehouseCreateRequest;
import com.daesung.sales.warehouse.dto.WarehouseResponse;
import com.daesung.sales.warehouse.dto.WarehouseUpdateRequest;
import com.daesung.sales.warehouse.entity.Warehouse;
import com.daesung.sales.warehouse.entity.WarehouseType;
import com.daesung.sales.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final PartnerRepository partnerRepository;
    private final com.daesung.sales.inventory.repository.InventoryRepository inventoryRepository;

    public PageResponse<WarehouseResponse> findAll(String keyword, Pageable pageable) {
        return findAll(keyword, false, pageable);
    }

    /**
     * 창고 목록 — 기본은 <b>사용 중지한 창고 제외</b>.
     *
     * <p>‼️{@code discontinue()} 주석이 "미사용 창고는 목록·선택지에서 빠진다"고 적어 둔 것이
     * 실제로는 안 지켜지고 있었다(쿼리에 조건이 없었다). 중지한 창고가 출고 창고 드롭다운에
     * 계속 떠서 담당자가 고를 수 있었다 — 도서 목록의 같은 결함(발주처 검수 F-36)과 함께 고친다.
     */
    public PageResponse<WarehouseResponse> findAll(String keyword, boolean includeUnused,
                                                   Pageable pageable) {
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        Page<Warehouse> page = warehouseRepository.search(kw, includeUnused, pageable);
        return PageResponse.of(page.map(WarehouseResponse::from));
    }

    public WarehouseResponse findById(Long id) {
        return WarehouseResponse.from(getOrThrow(id));
    }

    @Transactional
    public WarehouseResponse create(WarehouseCreateRequest req) {
        warehouseRepository.findByCode(req.code()).ifPresent(w -> {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 존재하는 창고코드: " + req.code());
        });
        Partner owner = ownerFor(req.type(), req.ownerClientId(), null);
        Warehouse warehouse = Warehouse.create(req.code(), req.name(), req.type(),
                req.physicalStockOrDefault(), owner);
        warehouse.applyExtra(req.useYn(), req.memo());
        return WarehouseResponse.from(warehouseRepository.save(warehouse));
    }

    @Transactional
    public WarehouseResponse update(Long id, WarehouseUpdateRequest req) {
        Warehouse warehouse = getOrThrow(id);
        Partner owner = ownerFor(req.type(), req.ownerClientId(), warehouse.getOwnerClient());
        warehouse.update(req.name(), req.type(), req.physicalStock(), owner);
        warehouse.applyExtra(req.useYn(), req.memo());
        return WarehouseResponse.from(warehouse);
    }

    /**
     * 창고유형에 맞는 소속거래처를 정한다.
     *
     * <p>★<b>위탁창고의 소속 거래처는 선택이다</b>(2026-09-17 정정).
     * 2026-09-11 점검에서 "소속 없는 위탁창고가 만들어진다"를 결함으로 보고 필수로 막았는데,
     * 발주처 회신이 그 전제를 뒤집었다 — "같은 거래처라도 건별로 본사물류창고와 위탁창고 중
     * 선택해서 쓰며, 특정 거래처는 무조건 위탁창고 식의 <b>고정 매핑이 아니다</b>".
     * 운영 DSRE2도 같다(거래처 212곳 중 도서재고 보유 42곳).
     * ‼️"미결을 누구 것으로 정산할지 모른다"는 걱정도 틀렸다 — 정산은 이 매핑이 아니라
     * <b>실제 자동이고 기록</b>에서 창고를 찾는다.
     *
     * <p>★<b>수정 때 안 보낸 소속은 지우지 않는다.</b> {@code Warehouse.update}는 받은 값을
     * 그대로 덮어쓰므로, 이름만 고치려고 {@code ownerClientId}를 빼면 소속이 조용히 날아갔다.
     * 도서 수정에서 같은 문제를 부분 수정으로 고친 적이 있다(7d8361f) — 여기도 같은 규칙이다.
     *
     * <p>물류창고는 소속이 없는 것이 정상이라 무엇이 오든 {@code null}로 만든다.
     *
     * @param current 수정 전 소속(신규 등록이면 {@code null})
     */
    /**
     * 창고 사용 중지(논리삭제) — {@code useYn=false}.
     *
     * <p>★행을 지우지 않는다. 과거 재고 이벤트({@code inventory_txn})가 창고를 가리키고 있어
     * 지우면 수불부가 "어느 창고였는지 모르는" 행을 갖게 된다.
     * 미사용 창고는 목록·선택지에서 빠진다.
     *
     * <p>‼️재고가 남아 있으면 막는다 — 물건이 있는 창고를 목록에서 치우면
     * 그 재고를 아무도 못 찾는다. 먼저 이고로 비워야 한다.
     */
    @Transactional
    public void discontinue(Long id) {
        Warehouse warehouse = getOrThrow(id);
        int balance = inventoryRepository.totalQtyByWarehouse(id);
        if (balance != 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "재고가 남아 있어 중지할 수 없습니다. 현재 " + balance + "부 — 먼저 다른 창고로 옮기세요.");
        }
        warehouse.applyExtra(false, null);
    }

    /**
     * 소속거래처 결정. 물류창고는 없고, <b>위탁창고도 선택</b>이다.
     *
     * <p>‼️예전에는 위탁창고에 소속거래처를 <b>필수</b>로 걸고 없으면 400을 냈다.
     * 발주처 회신(자료요청서 1-1 창고목록)이 그 전제를 뒤집었다 —
     * <blockquote>
     * "위탁창고 사용 여부는 거래처별로 고정된 것이 아니라, 같은 거래처라도 건별 상황에 따라
     *  본사물류창고와 위탁창고 중 선택해서 사용합니다.
     *  <b>특정 거래처는 무조건 위탁창고, 이런 식의 고정 매핑이 아니라는 점</b> 설계에 반영 부탁드립니다."
     * </blockquote>
     * 운영 DSRE2 실측도 같다 — 거래처 212곳 중 도서재고를 들고 있는 곳은 42곳뿐이다
     * ({@code tbl_booklist_cnt} 90행/42거래처).
     *
     * <p>★소속을 <b>지우지는 않았다.</b> 한 거래처 전용으로 만든 위탁창고도 실재하고,
     * 그 경우 누구 창고인지 적어 두는 편이 낫다. 다만 <b>없어도 만들 수 있다.</b>
     *
     * <p>‼️위탁정산은 이 매핑을 쓰지 않는다 — 정산·반품은 <b>실제 자동이고 기록</b>에서
     * 창고를 찾는다({@code ConsignmentService.consignWarehouseOf}). 그래서 비어 있어도 안전하다.
     */
    private Partner ownerFor(WarehouseType type, Long ownerClientId, Partner current) {
        if (type != WarehouseType.CONSIGN) {
            return null;   // 물류창고는 소속 없음
        }
        return (ownerClientId != null) ? resolveOwner(ownerClientId) : current;
    }

    /** 소속거래처 id → Partner(없으면 예외). null이면 소속 없음. */
    private Partner resolveOwner(Long ownerClientId) {
        if (ownerClientId == null) {
            return null;
        }
        return partnerRepository.findById(ownerClientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "소속거래처가 없습니다. id=" + ownerClientId));
    }

    private Warehouse getOrThrow(Long id) {
        return warehouseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "창고가 없습니다. id=" + id));
    }
}
