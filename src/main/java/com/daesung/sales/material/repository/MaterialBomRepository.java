package com.daesung.sales.material.repository;

import com.daesung.sales.material.entity.MaterialBom;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaterialBomRepository extends JpaRepository<MaterialBom, Long> {

    /** 세트의 전체 매칭(공통 + 회차별). 자재·회차 즉시 로드 — 목록이 매 행 이름을 쓴다. */
    @Query("""
            select b from MaterialBom b
              join fetch b.material
              left join fetch b.roundProduct
             where b.setProduct.id = :setProductId
             order by b.roundProduct.code nulls first, b.material.code
            """)
    List<MaterialBom> findBySet(@Param("setProductId") Long setProductId);

    /**
     * <b>전 세트의 매칭을 한 번에</b>. 세트 하나씩 보는 {@link #findBySet}만 있어
     * "지금 자재가 어디에 몇 개 걸려 있나"를 볼 길이 없었다 — 상품이 14,811개라
     * 순회로는 답이 안 나온다(2026-11-09 F-37 회신 준비 중 확인).
     *
     * <p>{@code commonOnly = true} 면 <b>공통(회차 미지정)</b> 매칭만. 발주처가
     * "지금 「공통(회차 0)」으로 연결된 건을 새 구조로 어떻게 옮길지" 회신을 요구한
     * 그 대상이다(F-37).
     */
    @Query("""
            select b from MaterialBom b
              join fetch b.setProduct
              join fetch b.material
              left join fetch b.roundProduct
             where (:commonOnly = false or b.roundProduct is null)
             order by b.setProduct.code, b.roundProduct.code nulls first, b.material.code
            """)
    List<MaterialBom> findAllMatches(@Param("commonOnly") boolean commonOnly);

    /**
     * <b>이 상품이 어느 세트의 회차로 등록돼 있나</b> — 역방향 조회(F-37 ⑤).
     *
     * <p>회차 단독 출고가 "그 회차 자재"를 깎아야 하는데, 출고 쪽은 상품 하나만 들고 있다.
     * 그 상품이 회차인지 알아야 하고, 회차면 <b>그 세트의 매칭 전체</b>(회차 전용 + 공통)가
     * 필요하다 — 공통 자재는 회차당 소요가 세트당÷회차수이기 때문이다.
     *
     * <p>한 상품이 <b>여러 세트의 회차</b>일 수 있다(같은 회차 도서를 여러 세트가 구성에 넣는 경우).
     * 그래서 목록으로 받는다.
     */
    @Query("""
            select b from MaterialBom b
              join fetch b.setProduct
             where b.roundProduct.id = :roundProductId
            """)
    List<MaterialBom> findByRoundProductId(@Param("roundProductId") Long roundProductId);

    /** 같은 조합이 이미 있는지. 공통(회차 null)은 DB UNIQUE가 못 막아 여기서 본다. */
    @Query("""
            select b from MaterialBom b
             where b.setProduct.id = :setProductId
               and b.material.id = :materialId
               and ((:roundProductId is null and b.roundProduct is null)
                    or b.roundProduct.id = :roundProductId)
            """)
    Optional<MaterialBom> findMatch(@Param("setProductId") Long setProductId,
                                    @Param("roundProductId") Long roundProductId,
                                    @Param("materialId") Long materialId);

    /** 자재가 어딘가에 매칭돼 있는지(삭제 차단용). */
    long countByMaterialId(Long materialId);
}
