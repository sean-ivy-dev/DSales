package com.daesung.sales.product.repository;

import com.daesung.sales.product.entity.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByCode(String code);

    /**
     * 분류코드 + 도서코드로 찾는다(매출 엑셀 업로드용).
     *
     * <p>★발주처 표준양식이 두 칸을 따로 받는다 —
     * "⚠분류코드마다 01부터 재사용되므로 <b>분류코드 없이는 특정 불가</b>"(매출 업로드양식_샘플).
     * 레거시는 도서코드가 분류 안에서만 고유했다.
     *
     * <p>‼️그런데 우리 마스터는 도서코드를 <b>전역 고유</b>로 쓴다(BK-K2026-1).
     * 두 체계가 섞여 있어 호출부가 <b>이것을 먼저 보고, 못 찾으면 도서코드 단독</b>으로 한 번 더 찾는다.
     * 어느 한쪽만 지원하면 다른 체계로 만든 양식이 통째로 실패한다.
     */
    Optional<Product> findByCatCodeAndCode(String catCode, String code);

    /**
     * 쓰이고 있는 분류코드·분류명 목록(도서 등록 화면 선택용).
     *
     * <p>★분류 마스터가 아니라 <b>실제 사용 중인 값의 집계</b>다. 정본에 분류 등록 화면이 없어
     * 분류코드는 도서에 직접 입력된다 — 그 값들을 모아 보여줘 타이핑을 줄이는 것이 목적이다.
     *
     * <p>★<b>코드와 이름을 함께 묶는다.</b> 코드만 뽑으면 같은 코드에 이름이 갈려 있어도
     * 한 줄로 보여 문제가 감춰진다. 갈려 있으면 두 줄로 나오는 편이 낫다.
     *
     * <p>사용여부(useYn)가 꺼진 도서도 포함한다 — 과거 매출이 그 분류를 가리키고 있어
     * 리포트에는 계속 나온다. 목록에서 빼면 담당자가 "없는 분류"로 알고 새로 만든다.
     */
    @Query("""
            select p.catCode as catCode, p.catName as catName, count(p) as productCount
            from Product p
            where p.catCode is not null and p.catCode <> ''
            group by p.catCode, p.catName
            order by p.catCode asc, p.catName asc
            """)
    List<CategoryAgg> findUsedCategories();

    /** {@link #findUsedCategories} 투영. */
    interface CategoryAgg {
        String getCatCode();

        String getCatName();

        long getProductCount();
    }

    Page<Product> findByCodeContainingIgnoreCaseOrNameContainingIgnoreCase(
            String code, String name, Pageable pageable);

    /** 이 세부구분을 쓰는 상품 수. 세부구분을 지워도 되는지 판단한다(쓰는 곳이 있으면 비활성만). */
    long countBySalesDivision(String salesDivision);

    /** 분류코드에 속한 도서 id 전체. 분류 단위 플래그 일괄 적용(수불부/단가 노출)에 쓴다. */
    @org.springframework.data.jpa.repository.Query("select p.id from Product p where p.catCode = :catCode")
    java.util.List<Long> findIdsByCatCode(@org.springframework.data.repository.query.Param("catCode") String catCode);

    /**
     * 도서 목록 검색 — 검색어(코드·상품명) + 수불부노출 + 단가노출.
     * 모든 조건이 null이면 전체다. 노출 플래그는 <b>서로 다른 축</b>이라 각각 받는다.
     *
     * <p>★{@code updatedSince} — <b>그 시각 이후 등록·수정된 것만</b>(프론트 요청 2026-10-20 B-1).
     * 이관 뒤 도서가 14,808권(약 5.4MB)이 되어 화면이 30분마다 전량을 다시 받고 있었다.
     * ‼️{@code coalesce(updatedAt, createdAt)} 로 본다 — 등록만 하고 한 번도 안 고친 도서는
     * {@code updatedAt} 이 비어 있어서, 그것만 보면 <b>새로 등록된 도서가 영영 안 잡힌다</b>.
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from Product p
             where (:keyword is null or lower(p.code) like lower(concat('%', :keyword, '%'))
                    or lower(p.name) like lower(concat('%', :keyword, '%')))
               and (:ledgerVisible is null or p.ledgerVisible = :ledgerVisible)
               and (:priceVisible is null or p.priceVisible = :priceVisible)
               and (:updatedSince is null
                    or coalesce(p.updatedAt, p.createdAt) >= :updatedSince)
            """)
    org.springframework.data.domain.Page<Product> search(
            @org.springframework.data.repository.query.Param("keyword") String keyword,
            @org.springframework.data.repository.query.Param("ledgerVisible") Boolean ledgerVisible,
            @org.springframework.data.repository.query.Param("priceVisible") Boolean priceVisible,
            @org.springframework.data.repository.query.Param("updatedSince") java.time.LocalDateTime updatedSince,
            org.springframework.data.domain.Pageable pageable);

    /**
     * <b>특약점 사이트 신청 화면(+재고현황 목록)에 노출할 도서</b>. 근거: 10/31 클라이언트
     * 회신(`특약점사이트-개발전달-261031.md` §1-1, 10/26 §3·§9-1 대체) — 노출 판단 순서는
     * <b>위에서부터 처음 걸리는 규칙</b>이다.
     *
     * <pre>
     *   ① p.webVisible = N                                  → 비노출 (예외보다 우선)
     *   ② 거래처×도서 예외(PartnerProductVisibility) 행이 있음  → 그 행의 visible
     *      (열기=매핑이 없거나 닫혀 있어도 노출 / 닫기=매핑이 열려 있어도 비노출)
     *   ③ 거래처×대분류 매핑(PartnerSupplyRate, use_yn=Y·미삭제)이 있음 → 그 행의 web_visible
     *   ④ 매핑 행 없음                                        → 비노출
     * </pre>
     *
     * <p>‼️<b>처음엔 ②(거래처×도서 예외)가 없었다</b> — 10/26 명세 §3만 보고 ①③④로 구현했는데
     * 10/31 회신이 §3을 통째로 대체하면서 ②를 추가했다. 10/31 문서가 늦게 발견돼
     * 이 메서드가 한동안 확정 규칙과 다르게 동작했다(2026-11-03).
     *
     * <p>대분류는 도서에 직접 없다 — {@code products.sales_division}(세부구분 코드) →
     * {@code sales_divisions.major_category} 로 한 번 더 타고 간다. FK가 아니라 코드 문자열
     * 연결이라 {@code join ... on} 으로 붙인다. 세부구분이 비었거나 마스터에 없는 도서는
     * 대분류를 알 수 없어 매핑을 찾을 수 없고, 예외도 없으면 ④에 따라 비노출이 된다.
     *
     * <p>★<b>캐시를 두지 않는다</b>(§3 "도서관리에서 바꾸면 사이트에 바로 반영돼야 한다").
     *
     * <p>⚠️<b>도서 사용여부(p.useYn)는 우리가 더한 조건</b>이다 — 10/31 §1-1 원문에는 없다.
     * 쓰지 않기로 한 도서를 특약점이 주문할 수 있으면 그 주문은 받는 쪽에서 막아야 하는데,
     * 그 경로가 없다. 빼야 하면 이 조건만 지우면 된다(2026-11-03 회신에 질문으로 올림).
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from Product p
              join com.daesung.sales.product.entity.SalesDivision d
                on d.code = p.salesDivision
              left join com.daesung.sales.product.entity.PartnerProductVisibility ex
                on ex.partner.id = :partnerId and ex.product = p
              left join com.daesung.sales.product.entity.PartnerSupplyRate m
                on m.majorCategory = d.majorCategory and m.partner.id = :partnerId
             where p.webVisible = true
               and p.useYn = true
               and ((ex.id is not null and ex.visible = true)
                 or (ex.id is null and m.webVisible = true and m.useYn = true))
             order by p.catCode asc, p.code asc
            """)
    java.util.List<Product> findDealerVisible(
            @org.springframework.data.repository.query.Param("partnerId") Long partnerId);

    /**
     * {@link #findDealerVisible} 과 <b>같은 WHERE</b>를 쓰되 단건 판정용 — "이 거래처에
     * 이 도서가 보이는가"를 [거래처관리] '도서별 노출' 화면이 전 도서마다 묻는다.
     * 전체를 긁어 자바에서 걸러도 되지만, 그 화면이 수천 권을 한 번에 보여줄 수 있어
     * DB에서 바로 거른다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select count(p) > 0 from Product p
              join com.daesung.sales.product.entity.SalesDivision d
                on d.code = p.salesDivision
              left join com.daesung.sales.product.entity.PartnerProductVisibility ex
                on ex.partner.id = :partnerId and ex.product = p
              left join com.daesung.sales.product.entity.PartnerSupplyRate m
                on m.majorCategory = d.majorCategory and m.partner.id = :partnerId
             where p = :product
               and p.webVisible = true
               and p.useYn = true
               and ((ex.id is not null and ex.visible = true)
                 or (ex.id is null and m.webVisible = true and m.useYn = true))
            """)
    boolean isDealerVisible(@org.springframework.data.repository.query.Param("partnerId") Long partnerId,
                            @org.springframework.data.repository.query.Param("product") Product product);
}
