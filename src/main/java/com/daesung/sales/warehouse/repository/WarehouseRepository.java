package com.daesung.sales.warehouse.repository;

import com.daesung.sales.warehouse.entity.Warehouse;
import com.daesung.sales.warehouse.entity.WarehouseType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {
    Optional<Warehouse> findByCode(String code);
    List<Warehouse> findByType(WarehouseType type);

    Page<Warehouse> findByCodeContainingIgnoreCaseOrNameContainingIgnoreCase(
            String code, String name, Pageable pageable);

    /**
     * 그 거래처의 <b>위탁창고</b> — 주문 등록에서 위탁출고를 받을 때 도착 창고를 찾는다
     * (10/27 명세 §4-1 "주문 등록의 출고유형에 '위탁출고'를 포함", 프론트 C-1).
     *
     * <p>매출등록 요청에는 창고가 하나뿐인데(출고 창고) 위탁출고는 <b>물류창고 → 위탁창고</b>
     * 두 곳이 필요하다. 위탁창고는 창고 마스터의 <b>소속거래처</b>로 정해진다(정본 32p 확장).
     *
     * <p>여럿이면 담당자가 고를 수 없으므로 서비스가 사유와 함께 거부한다 — 조용히 하나를
     * 고르면 물건이 엉뚱한 창고로 간다.
     */
    @Query("select w from Warehouse w where w.ownerClient.id = :partnerId "
            + "and w.type = com.daesung.sales.warehouse.entity.WarehouseType.CONSIGN "
            + "order by w.code")
    List<Warehouse> findConsignWarehouses(@Param("partnerId") Long partnerId);
}
