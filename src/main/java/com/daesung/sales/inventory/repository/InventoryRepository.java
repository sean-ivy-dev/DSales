package com.daesung.sales.inventory.repository;

import com.daesung.sales.inventory.entity.Inventory;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByProductIdAndWarehouseId(Long productId, Long warehouseId);

    Optional<Inventory> findByMaterialIdAndWarehouseId(Long materialId, Long warehouseId);

    /**
     * 원자적 잔량 증감(입고/도착 +). {@code qty = qty + delta}를 DB 한 문장으로 처리(lost update 없음).
     * 반환값 = 영향 행수(0이면 행 없음 → 신규 생성 필요).
     */
    @Modifying(clearAutomatically = true)
    @Query("update Inventory i set i.qty = i.qty + :delta "
            + "where i.product.id = :productId and i.warehouse.id = :warehouseId")
    int addQty(@Param("productId") Long productId,
               @Param("warehouseId") Long warehouseId,
               @Param("delta") int delta);

    /**
     * 음수재고 방지 원자적 차감/증감. {@code qty + delta >= 0}일 때만 갱신.
     * 반환값 = 영향 행수(0이면 행 없음 또는 재고 부족 → 호출부에서 예외).
     */
    @Modifying(clearAutomatically = true)
    @Query("update Inventory i set i.qty = i.qty + :delta "
            + "where i.product.id = :productId and i.warehouse.id = :warehouseId and i.qty + :delta >= 0")
    int addQtyIfEnough(@Param("productId") Long productId,
                       @Param("warehouseId") Long warehouseId,
                       @Param("delta") int delta);

    /**
     * 자재 잔량 원자적 증감(V78). 도서 쪽 {@link #addQty}와 같은 이유로 read-modify-write 를 쓰지 않는다 —
     * 같은 자재를 동시에 입고하면 한쪽이 유실된다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Inventory i set i.qty = i.qty + :delta "
            + "where i.material.id = :materialId and i.warehouse.id = :warehouseId")
    int addMaterialQty(@Param("materialId") Long materialId,
                       @Param("warehouseId") Long warehouseId,
                       @Param("delta") int delta);

    /**
     * 자재 재고 현황. 잔량이 0인 자재도 낸다(한 번이라도 다룬 자재는 표에 남아야
     * "없는 것"과 "0인 것"이 구분된다).
     *
     * <p>반환 Object[]: [materialId, code, name, materialType, warehouseId, warehouseName, qty].
     */
    /**
     * 자재별 **전 창고 합계** 재고. 세트 가용재고 계산용.
     *
     * <p>★창고를 가리지 않는다 — 세트는 "회사 전체에 몇 개 만들 수 있나"를 보는 것이라
     * 창고별로 쪼개면 각 창고 최소값의 합이 되어 실제보다 작게 나온다.
     * 반환 Object[]: [materialId, qtySum].
     */
    /**
     * 상품 재고 합계(전 창고). <b>조립돼 있는 세트 수</b>를 보는 데 쓴다 —
     * F-37 발주처 확정대로 "SET 재고는 세트 조립·해체로 증감"하는 그 숫자다.
     *
     * <p>자재 합계와 같은 이유로 창고를 가리지 않는다.
     */
    @Query("select coalesce(sum(i.qty), 0) from Inventory i where i.product.id = :productId")
    int sumQtyByProductId(@Param("productId") Long productId);

    @Query("select m.id, coalesce(sum(i.qty), 0) from Inventory i join i.material m "
            + "where m.id in :materialIds group by m.id")
    java.util.List<Object[]> sumByMaterialIds(
            @org.springframework.data.repository.query.Param("materialIds")
            java.util.Collection<Long> materialIds);

    @Query("select m.id, m.code, m.name, m.materialType, w.id, w.name, i.qty "
            + "from Inventory i join i.material m join i.warehouse w "
            + "where (:materialId is null or m.id = :materialId) "
            + "  and (:warehouseId is null or w.id = :warehouseId) "
            + "  and (:type is null or m.materialType = :type) "
            + "order by m.materialType, m.code, w.id")
    java.util.List<Object[]> materialStock(
            @Param("materialId") Long materialId,
            @Param("warehouseId") Long warehouseId,
            @Param("type") com.daesung.sales.product.entity.MaterialType type);

    /** 창고의 재고 총합. 창고를 중지해도 되는지 판단하는 데 쓴다(0이어야 안전). */
    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(i.qty), 0) from Inventory i where i.warehouse.id = :warehouseId")
    int totalQtyByWarehouse(@org.springframework.data.repository.query.Param("warehouseId") Long warehouseId);

    /**
     * 창고별 재고 수량 합계(19p 상세 대시보드 '창고별 재고 수량').
     *
     * <p>‼️<b>사용 중인 창고만</b> 낸다 — 쓰지 않기로 한 창고가 도넛에 남으면
     * 담당자는 그 창고에 아직 물건이 있다고 읽는다.
     *
     * <p>‼️<b>도서만</b> 센다(V78). 자재까지 합치면 권수와 장수가 한 숫자에 섞여
     * 도넛의 크기가 아무 뜻도 갖지 못한다. 자재 재고는 자재 현황에서 따로 본다.
     *
     * <p>반환 Object[]: [warehouseId, name, type, qty].
     */
    @Query("select w.id, w.name, w.type, coalesce(sum(i.qty), 0) "
            + "from Inventory i join i.warehouse w "
            + "where w.useYn = true and i.product is not null "
            + "group by w.id, w.name, w.type order by w.id")
    java.util.List<Object[]> stockQtyByWarehouse();
}
