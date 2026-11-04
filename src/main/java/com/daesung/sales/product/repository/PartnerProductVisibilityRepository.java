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

    /** 그 거래처에 '열기'로 지정된 도서 전체(특약점 쪽 조회·점검용). */
    @Query("select e from PartnerProductVisibility e join fetch e.product where e.partner.id = :partnerId")
    List<PartnerProductVisibility> findByPartnerId(@Param("partnerId") Long partnerId);

    /**
     * [도서관리] '거래처별 노출' 탭용 — <b>그 도서에 열기로 지정된 거래처 전체</b>.
     * 근거: 11/04 회신 §2(설정 축이 거래처 → 도서로 뒤집혔다).
     * V116 이 {@code product_id} 인덱스를 더했다(V113 은 partner_id 하나뿐이었다).
     */
    @Query("select e from PartnerProductVisibility e join fetch e.partner where e.product.id = :productId")
    List<PartnerProductVisibility> findByProductId(@Param("productId") Long productId);
}
