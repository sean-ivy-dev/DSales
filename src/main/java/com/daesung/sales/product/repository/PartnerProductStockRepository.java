package com.daesung.sales.product.repository;

import com.daesung.sales.product.entity.PartnerProductStock;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 특약점 재고현황(10/31 회신 §3). */
public interface PartnerProductStockRepository extends JpaRepository<PartnerProductStock, Long> {

    Optional<PartnerProductStock> findByPartnerIdAndProductId(Long partnerId, Long productId);

    /** 그 거래처의 저장된 재고현황 전체(조회 화면이 노출 도서 목록과 합친다). */
    @Query("select s from PartnerProductStock s where s.partner.id = :partnerId")
    List<PartnerProductStock> findByPartnerId(@Param("partnerId") Long partnerId);
}
