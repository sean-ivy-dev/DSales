package com.daesung.sales.consignment.repository;

import com.daesung.sales.consignment.entity.ConsignmentOut;
import com.daesung.sales.consignment.entity.ConsignmentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

public interface ConsignmentOutRepository extends JpaRepository<ConsignmentOut, Long> {
    Optional<ConsignmentOut> findBySourceOutNo(String sourceOutNo);

    /**
     * 미결 1건을 비관적 쓰기락(SELECT ... FOR UPDATE)으로 로드. 정산/반품의 read-modify-write를
     * 동일 행에 대해 직렬화 → 동시 정산 시 lost update·초과정산 방지(재고의 원자적 UPDATE와 대칭).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConsignmentOut c where c.id = :id")
    Optional<ConsignmentOut> findByIdForUpdate(Long id);

    List<ConsignmentOut> findByPartnerIdAndStatus(Long partnerId, ConsignmentStatus status);

    /** 특정 거래처의 미결(잔여>0) 위탁출고. product를 함께 로드해 N+1 방지. */
    @Query("select c from ConsignmentOut c join fetch c.product"
            + " where c.partner.id = :partnerId and c.remainingQty <> 0 order by c.id")
    List<ConsignmentOut> findPending(Long partnerId);

    /**
     * <b>전 거래처</b>의 미결 위탁출고(2026-10-08). 거래처를 안 고르고 전체를 훑는 조회다.
     *
     * <p>화면이 기간 데이터를 전부 받아 걸러 쓰고 있어서 열었다 —
     * 미결은 거래처가 늘수록 쌓이기만 하는 자료라 전부 받아 거르는 방식은 오래 못 간다.
     * 거래처를 함께 로드해 목록에 이름을 붙일 때 N+1 이 나지 않게 한다.
     */
    @Query("select c from ConsignmentOut c join fetch c.product join fetch c.partner"
            + " where c.remainingQty <> 0 order by c.partner.id, c.id")
    List<ConsignmentOut> findAllPending();
    // ★조건이 `> 0`이 아니라 `<> 0`인 이유: 초과정산 차단을 제거한 뒤(발주처 2026-08-31)
    //   잔여가 **음수**가 될 수 있다. `> 0`으로 두면 초과된 미결이 화면에서 사라져
    //   "경고만 주고 담당자가 수기로 정리한다"는 요구를 만족할 수 없다 — 보여야 고친다.

    /**
     * 전 거래처 미결 백로그(건수·잔여수량 합). 대시보드 '위탁 정산 대기' 카드용.
     *
     * <p>거래처별 조회({@link #findPending})를 전 거래처로 돌려 세면 되지만,
     * 그러면 거래처 수만큼 쿼리가 나가고 화면은 <b>숫자 두 개</b>만 쓴다.
     * <p>‼️반환 타입을 {@code Object[]}로 쓰면 안 된다 — Hibernate가 <b>행 배열을 한 겹 더 감싸</b>
     * {@code Object[]{Object[]{건수, 합계}}}로 준다. 그대로 읽으면 ClassCastException이 나고
     * 화면에는 카드가 통째로 사라진다(조용히 틀리는 쪽이라 실제로 한 번 겪었다).
     * 스칼라 두 개는 따로 세는 편이 안전하고, 쿼리도 그대로 인덱스를 탄다.
     */
    @Query("select count(c) from ConsignmentOut c where c.remainingQty <> 0")
    long countPending();

    /** 미결 잔여수량 합. {@link #countPending}과 짝. */
    @Query("select coalesce(sum(c.remainingQty), 0) from ConsignmentOut c where c.remainingQty > 0")
    long sumPendingQty();

    /**
     * 발송 단위(일자·거래처·학교·분류)의 <b>위탁출고</b> 도서별 지시 목록.
     *
     * <p>근거: 검수피드백 2026-09-30 p.12 — "위탁출고 등록 시 물류 [작업요청서] 내역에
     * 반영되지 않는 문제가 있습니다. <b>위탁출고도 실제 출고가 발생하므로</b> 작업요청서에
     * 포함되어야 합니다".
     *
     * <p>★<b>매출 쪽 {@code workOrderLines} 로는 못 잡는다.</b> 위탁출고 시점엔 매출이 없다 —
     * 미결로 나가고 정산할 때 비로소 매출이 선다. 그래서 미결원장에서 따로 뽑아 합친다.
     *
     * <p>★<b>원출고 수량({@code originalQty})을 쓴다.</b> 물류 지시는 "몇 개를 싸서 보내라"이고
     * 그건 나갈 때 수량이다 — 나중에 정산·반품으로 잔여가 줄어도 이미 나간 상자는 그대로다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select c.tradeDate as tradeDate, c.partner.id as partnerId,
                   coalesce(c.schoolCode, '') as schoolCode,
                   coalesce(p.salesDivision, '') as tradeClass,
                   p.catCode as catCode, p.code as productCode, p.name as productName,
                   c.unitPrice as unitPrice, c.supplyRate as supplyRate,
                   sum(c.originalQty) as qty
              from ConsignmentOut c join c.product p
             where c.tradeDate between :from and :to
             group by c.tradeDate, c.partner.id, coalesce(c.schoolCode, ''),
                      coalesce(p.salesDivision, ''), p.catCode, p.code, p.name,
                      c.unitPrice, c.supplyRate
            """)
    java.util.List<ConsignWorkOrderLine> workOrderLines(
            @org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDate to);

    /** 작업요청서에 실을 위탁출고 한 줄. */
    interface ConsignWorkOrderLine {
        java.time.LocalDate getTradeDate();

        Long getPartnerId();

        String getSchoolCode();

        String getTradeClass();

        String getCatCode();

        String getProductCode();

        String getProductName();

        Integer getUnitPrice();

        Integer getSupplyRate();

        Long getQty();
    }

    /**
     * 출고/반품 조회에 보일 <b>위탁출고</b>(10/27 명세 §4-3·§4-4, 프론트 C-3·C-4).
     *
     * <p>§4-3 "위탁출고도 실제 출고다. <b>정산과 관계없이 등록(출고) 시점에</b> 잡혀야 한다" ·
     * §4-4 "미결잔고 50 중 40을 정산 확정하면 <b>출고/반품에는 50부</b>(실제 출고)".
     *
     * <p>★<b>정산 여부로 거르지 않는다.</b> 다 팔렸어도 "그날 50부가 나갔다"는 사실은 남는다.
     * 수량은 {@code originalQty}(원출고)를 쓴다 — 잔여를 쓰면 정산할수록 출고가 줄어든다.
     *
     * <p>적용하는 조건은 기간·거래처·학교·도서·세부구분·키워드다. 매출에만 있는 축
     * (회계구분·출고유형·창고·판매채널·거래순번)은 위탁 미결에 해당 값이 없어 걸지 않는다 —
     * 억지로 맞추면 "위탁출고인데 출고유형으로 걸러 사라지는" 일이 생긴다.
     */
    @Query("select co from ConsignmentOut co join fetch co.product p join fetch co.partner "
            + "where (:from is null or co.tradeDate >= :from) "
            + "and (:to is null or co.tradeDate <= :to) "
            + "and (:anyPartner = true or co.partner.id in :partnerIds) "
            + "and (:anySchool = true or co.schoolCode in :schoolCodes) "
            + "and (:anyProduct = true or p.id in :productIds) "
            + "and (:anyDivision = true or coalesce(p.salesDivision, '') in :divisions) "
            + "and (:keyword is null or lower(co.partner.name) like :keyword "
            + "     or lower(p.name) like :keyword or lower(p.code) like :keyword "
            + "     or lower(co.sourceOutNo) like :keyword "
            + "     or lower(coalesce(co.schoolName, '')) like :keyword) "
            + "order by co.tradeDate desc, co.id desc")
    List<ConsignmentOut> findForShippedView(@Param("from") java.time.LocalDate from,
                                            @Param("to") java.time.LocalDate to,
                                            @Param("anyPartner") boolean anyPartner,
                                            @Param("partnerIds") java.util.Collection<Long> partnerIds,
                                            @Param("anySchool") boolean anySchool,
                                            @Param("schoolCodes") java.util.Collection<String> schoolCodes,
                                            @Param("anyProduct") boolean anyProduct,
                                            @Param("productIds") java.util.Collection<Long> productIds,
                                            @Param("anyDivision") boolean anyDivision,
                                            @Param("divisions") java.util.Collection<String> divisions,
                                            @Param("keyword") String keyword);
}
