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
     * 창고 목록 검색 — 검색어(코드·창고명) + 사용여부.
     *
     * <p>‼️{@code includeUnused} 기본 {@code false}로 <b>사용 중지한 창고를 뺀다</b>.
     * {@code discontinue()} 주석이 "미사용 창고는 목록·선택지에서 빠진다"고 적어 뒀는데
     * <b>실제로는 안 빠지고 있었다</b>(쿼리에 조건이 없었다) — 중지한 창고가 출고 창고
     * 드롭다운에 계속 떴다. 도서 목록의 같은 결함(발주처 검수 F-36)과 한 뿌리다.
     */
    @Query("""
            select w from Warehouse w
             where (:keyword is null or lower(w.code) like lower(concat('%', :keyword, '%'))
                    or lower(w.name) like lower(concat('%', :keyword, '%')))
               and (:includeUnused = true or w.useYn = true)
            """)
    Page<Warehouse> search(@Param("keyword") String keyword,
                           @Param("includeUnused") boolean includeUnused,
                           Pageable pageable);

    /**
     * 업로드 양식의 <b>「출고 창고」 칸</b>을 창고로 푼다 — <b>코드 또는 창고명</b> 정확일치
     * (발주처 2026-11-23 6번, 양식에 「본사 물류창고」처럼 <b>이름</b>이 적혀 온다).
     *
     * <p>★사용 중지한 창고는 뺀다 — 드롭다운에서 빠진 창고를 업로드로 되살리면
     * {@code search(includeUnused=false)} 와 어긋난다.
     *
     * <p>여럿이면 서비스가 사유와 함께 그 줄을 거부한다(조용히 하나를 고르면 엉뚱한 창고에서 나간다).
     */
    @Query("select w from Warehouse w where w.useYn = true "
            + "and (w.code = :token or w.name = :token) order by w.code")
    List<Warehouse> findByCodeOrName(@Param("token") String token);

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
