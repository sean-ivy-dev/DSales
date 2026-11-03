package com.daesung.sales.product.repository;

import com.daesung.sales.product.entity.PartnerProductVisibility;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 거래처×도서 노출 예외(10/31 회신 §1). */
public interface PartnerProductVisibilityRepository extends JpaRepository<PartnerProductVisibility, Long> {

    Optional<PartnerProductVisibility> findByPartnerIdAndProductId(Long partnerId, Long productId);

    /** [거래처관리] '도서별 노출' 화면용 — 그 거래처의 예외 전체(배지 표시에 쓴다). */
    @Query("select e from PartnerProductVisibility e join fetch e.product where e.partner.id = :partnerId")
    List<PartnerProductVisibility> findByPartnerId(@Param("partnerId") Long partnerId);
}
