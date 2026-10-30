package com.daesung.sales.receivable.repository;

import com.daesung.sales.receivable.entity.Collection;
import com.daesung.sales.receivable.entity.CollectionType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CollectionRepository extends JpaRepository<Collection, Long> {

    /** 수금 목록(기간·거래처 + 수금구분·입금구분 두 축 필터). 근거: 정본 23p 데이터 항목. */
    @Query("select c from Collection c "
            // ★조회기준 3종(정본 23p) — 기간을 어느 날짜로 자를지 담당자가 고른다.
            //   COLL=수금일자 / WRITE=기장일자(회계 기표일). 돈이 들어온 날과 장부에 올린 날이
            //   다를 수 있어 재무팀이 둘을 나눠 본다. 기본은 수금일자.
            //   ‼️기장일자는 null일 수 있다 — 그 기준으로 조회하면 미기표 건은 빠지는 게 맞다.
            + "where (:from is null or (case when :basis = 'WRITE' then c.writeDate else c.collDate end) >= :from) "
            + "and (:to is null or (case when :basis = 'WRITE' then c.writeDate else c.collDate end) <= :to) "
            + "and (:partnerId is null or c.partner.id = :partnerId) "
            + "and (:collKind is null or c.collKind = :collKind) "
            + "and (:collType is null or c.collType = :collType) "
            // ★여긴 DB 페이징이라 키워드를 쿼리에 넣는다. 메모리에서 거르면 한 페이지(20건)만
            //   걸러져 "2페이지엔 있는데 1페이지에선 안 보인다"가 된다.
            + "and (:keyword is null or lower(c.partner.name) like :keyword "
            + "     or lower(c.collectionNo) like :keyword "
            + "     or lower(coalesce(c.promissoryNo,'')) like :keyword "
            + "     or lower(coalesce(c.bankName,'')) like :keyword)")
    Page<Collection> search(@Param("from") LocalDate from,
                            @Param("to") LocalDate to,
                            @Param("partnerId") Long partnerId,
                            @Param("collKind") String collKind,
                            @Param("collType") CollectionType collType,
                            @Param("basis") String basis,
                            @Param("keyword") String keyword,
                            Pageable pageable);

    /**
     * 거래처별 수금 합계(기간). 반환 Object[]: [partnerId, collSum].
     *
     * <p>‼️<b>네이티브라 엔티티의 {@code @SQLRestriction}이 걸리지 않는다</b> —
     * 삭제 제외 조건을 손으로 넣어야 한다. 빠뜨리면 지운 수금이 계속 채권을 깎아
     * 미수금현황·이월 스냅샷의 잔액이 조용히 틀어진다.
     */
    @Query(value = """
            SELECT c.partner_id, COALESCE(SUM(c.coll_amt),0)
            FROM collection c
            WHERE c.deleted_at IS NULL
              AND c.coll_date BETWEEN :fromDate AND :toDate
              AND (CAST(:partnerId AS SIGNED) IS NULL OR c.partner_id = :partnerId)
            GROUP BY c.partner_id
            """, nativeQuery = true)
    List<Object[]> sumByPartner(@Param("fromDate") LocalDate fromDate,
                                @Param("toDate") LocalDate toDate,
                                @Param("partnerId") Long partnerId);

    /**
     * 수금관리(23p) 조회 — 기준별로 <b>날짜 필터 대상과 정렬이 함께 바뀐다</b>.
     * 근거: 레거시 수금관리.vb:80~89.
     * <pre>
     *   수금일자 기준: where collDate  … order by collDate,  custCode, id
     *   기장일자 기준: where writeDate … order by writeDate, custCode, id
     *   거래처   기준: where collDate  … order by custCode,  collDate, id
     * </pre>
     * 소계는 정렬 순서에 기대므로 여기서 순서를 어기면 소계가 엉뚱한 자리에 붙는다.
     *
     * <p>기장일자는 비어 있을 수 있다(수금일자로 자동 채우지 않는다) →
     * 기장일자 기준 조회에서 그런 건은 빠진다. 아직 기표하지 않은 건이라 그게 맞다.
     */
    @Query("""
            select c from Collection c join fetch c.partner p
             where (:byWrite = false or (c.writeDate is not null
                                         and c.writeDate between :fromDate and :toDate))
               and (:byWrite = true  or c.collDate between :fromDate and :toDate)
               and (:partnerId is null or p.id = :partnerId)
               and (:collKind is null or c.collKind = :collKind)
               and (:collType is null or c.collType = :collType)
             order by
               case when :byPartner = true then p.code end asc,
               case when :byWrite = true then c.writeDate else c.collDate end asc,
               case when :byPartner = true then c.collDate end asc,
               p.code asc, c.id asc
            """)
    List<Collection> ledger(@Param("fromDate") LocalDate fromDate,
                            @Param("toDate") LocalDate toDate,
                            @Param("partnerId") Long partnerId,
                            @Param("collKind") String collKind,
                            @Param("collType") CollectionType collType,
                            @Param("byWrite") boolean byWrite,
                            @Param("byPartner") boolean byPartner);

    /** 외상매출장 명세: 특정 거래처의 기간 내 수금 라인(일자순). */
    @Query("select c from Collection c where c.partner.id = :partnerId "
            + "and c.collDate between :from and :to order by c.collDate, c.id")
    List<Collection> findLedgerLines(@Param("partnerId") Long partnerId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    /**
     * 외상매출장 <b>전체 거래처</b> 명세: 여러 거래처의 기간 내 수금 라인을 한 번에.
     * 근거는 {@code SaleRepository.findLedgerLinesForPartners} 와 같다(10/30 D-3 성능).
     */
    @Query("select c from Collection c where c.partner.id in :partnerIds "
            + "and c.collDate between :from and :to "
            + "order by c.partner.id, c.collDate, c.id")
    List<Collection> findLedgerLinesForPartners(@Param("partnerIds") List<Long> partnerIds,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);
}
