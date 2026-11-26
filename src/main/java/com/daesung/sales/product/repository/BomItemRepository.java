package com.daesung.sales.product.repository;

import com.daesung.sales.product.entity.BomItem;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BomItemRepository extends JpaRepository<BomItem, Long> {

    /** 구성품(child)을 즉시 로드(JOIN FETCH) — 재고 갱신 중 세션 clear에도 detach 안전. */
    @Query("select b from BomItem b join fetch b.child where b.parent.id = :parentId")
    List<BomItem> findByParentId(@Param("parentId") Long parentId);

    /**
     * 완제품의 기존 BOM 구성을 논리삭제(게이트규칙: 물리 DELETE 금지).
     *
     * <p>벌크 UPDATE라 영속성 컨텍스트를 우회하므로 flush/clear를 함께 건다. 재삽입 전에 삭제가
     * DB에 반영돼야 유니크 (parent, child, del_key) 충돌을 피할 수 있다.
     * {@code @SQLRestriction}은 벌크 연산에 적용되지 않으므로 미삭제 조건을 명시한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update BomItem b set b.deletedAt = :now, b.deletedBy = :actor "
            + "where b.parent.id = :parentId and b.deletedAt is null")
    int softDeleteByParentId(@Param("parentId") Long parentId,
                             @Param("now") LocalDateTime now,
                             @Param("actor") String actor);

    /**
     * 그 상품에 걸린 구성 기록을 <b>물리삭제</b>한다 — 도서 완전 삭제 전용이다.
     *
     * <p>‼️평소에는 <b>논리삭제</b>다(언제 어떤 구성이었는지 남긴다). 그런데 도서를 통째로
     * 지울 때는 그 기록을 남길 수 없다 — <b>FK 가 가리키는 도서가 없어지기 때문</b>이다.
     * 도서가 사라지면 그 구성 기록도 쓸 데가 없다(PM 승인 2026-11-26).
     *
     * <p>★{@code ProductRepository.countReferences} 가 <b>지워진 기록을 빼고 세는 것</b>과 짝이다.
     * 한쪽만 바꾸면 "지울 수 있다고 해 놓고 FK 로 500" 이 난다.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            value = "DELETE FROM bom_items WHERE parent_product_id = :id OR child_product_id = :id",
            nativeQuery = true)
    void hardDeleteByProductId(@org.springframework.data.repository.query.Param("id") Long id);
}