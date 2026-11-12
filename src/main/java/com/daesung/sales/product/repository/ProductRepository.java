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
     * <b>이 상품을 참조하는 곳</b>을 한 번에 센다 — 물리삭제 가능 여부 판정(F-36).
     *
     * <p>발주처 요청: "(우클릭) **삭제** 기능. 지금은 사용 중지만 있음".
     * 레거시는 실제로 <b>물리삭제</b>다 — `도서관리.vb:467` 이 그리드에서 행을 지우고
     * {@code DataAdapter.Update} 로 DELETE 를 날린다(우클릭 메뉴도 같은 버튼을 부른다).
     *
     * <p>‼️<b>우리는 그대로 할 수 없다.</b> 레거시는 FK 가 없어서 가능했고, 우리는 매출·재고가
     * 이 상품을 가리킨다. 지우면 과거 매출의 상품이 사라져 수불부·리포트가 통째로 깨진다.
     * → <b>참조가 하나도 없을 때만</b> 물리삭제하고, 있으면 무엇이 몇 건 걸려 있는지 돌려준다.
     *
     * <p>★대상은 {@code products} 를 FK 로 참조하는 <b>전부</b>다(마이그레이션 전수 확인).
     * {@code product_partner_price} 는 V42 에서 삭제돼 빠졌다.
     * ‼️테이블이 늘면 <b>여기도 늘려야 한다</b> — 빠뜨리면 FK 오류로 500 이 난다.
     *
     * @return [참조처 이름, 건수] — 건수가 0인 줄은 빠진다
     */
    @Query(value = """
            SELECT '매출' AS ref, COUNT(*) AS cnt FROM sales WHERE product_id = :id
            UNION ALL SELECT '재고', COUNT(*) FROM inventory WHERE product_id = :id
            UNION ALL SELECT '재고이벤트', COUNT(*) FROM inventory_txn WHERE product_id = :id
            UNION ALL SELECT '위탁미결', COUNT(*) FROM consignment_out WHERE product_id = :id
            UNION ALL SELECT '재고실사', COUNT(*) FROM stocktake_line WHERE product_id = :id
            UNION ALL SELECT '매출목표', COUNT(*) FROM sales_target WHERE product_id = :id
            UNION ALL SELECT '세트구성', COUNT(*) FROM bom_items
                       WHERE parent_product_id = :id OR child_product_id = :id
            UNION ALL SELECT '자재매칭', COUNT(*) FROM material_bom
                       WHERE set_product_id = :id OR round_product_id = :id
            UNION ALL SELECT '물류단가', COUNT(*) FROM product_logis_rate WHERE product_id = :id
            UNION ALL SELECT '특약점 노출설정', COUNT(*) FROM partner_product_visibility WHERE product_id = :id
            UNION ALL SELECT '특약점 재고', COUNT(*) FROM partner_product_stock WHERE product_id = :id
            """, nativeQuery = true)
    java.util.List<Object[]> countReferences(
            @org.springframework.data.repository.query.Param("id") Long id);

    /**
     * 코드 묶음으로 한 번에. 행마다 {@link #findByCode}를 부르던 자리를 대신한다 —
     * 매출일괄등록 미리보기가 2,549행 × 왕복으로 <b>67.8초</b>가 걸려 게이트웨이가 끊었다
     * (2026-11-09 실측. DSRE 쿼리는 0.67초로 무죄였다).
     */
    java.util.List<Product> findByCodeIn(java.util.Collection<String> codes);

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
     * 도서 목록 검색 — 검색어(코드·상품명·<b>분류명·분류코드</b>) + 수불부노출 + 단가노출.
     * 모든 조건이 null이면 전체다. 노출 플래그는 <b>서로 다른 축</b>이라 각각 받는다.
     *
     * <p>★<b>분류명·분류코드도 검색어에 넣는다</b>(전수 재검사 2026-11-05 X-01). 그전엔
     * 코드·상품명만 봐서 'COREXACT'(분류명)로 찾으면 0건이었다 — 화면 안내가 '도서명 검색'
     * 이지만 담당자는 분류명으로도 찾는다(실제로 그렇게 찾다가 못 찾았다).</p>
     *
     * <p>★{@code updatedSince} — <b>그 시각 이후 등록·수정된 것만</b>(프론트 요청 2026-10-20 B-1).
     * 이관 뒤 도서가 14,808권(약 5.4MB)이 되어 화면이 30분마다 전량을 다시 받고 있었다.
     * ‼️{@code coalesce(updatedAt, createdAt)} 로 본다 — 등록만 하고 한 번도 안 고친 도서는
     * {@code updatedAt} 이 비어 있어서, 그것만 보면 <b>새로 등록된 도서가 영영 안 잡힌다</b>.
     *
     * <p>★{@code includeUnused} — 기본 {@code false}로 <b>사용여부 N(=삭제한 도서)을 뺀다</b>.
     * 근거: 발주처 검수 F-36 — "삭제 기능이 없다"는 지적의 실제 원인이 이것이었다.
     * 삭제 API({@code DELETE /masters/products/{id}})가 {@code use_yn=false} 논리삭제인데
     * 이 쿼리에 {@code useYn} 조건이 없어 <b>지운 도서가 목록에 계속 보였다</b>.
     * 거래처의 {@code includeExpired} 와 같은 방식 — 지난 것을 봐야 할 때만 켠다.
     *
     * <p>★{@code useYn} — <b>「사용 중지만」 보기</b>를 위한 3번째 상태다(프론트 요청 2026-11-09:
     * 목록 조건이 "빼고 보기 / 함께 보기 / <b>사용 중지만</b>" 3가지다).
     * ‼️<b>화면에서 걸러낼 수 없다</b> — 페이징이라 페이지마다 사용중지 비율이 달라
     * 총 건수와 페이지 수가 틀어진다. 그래서 조건을 서버에 둔다.
     * <pre>
     *   미지정                → {@code includeUnused} 규칙 그대로(기존 동작)
     *   useYn=true           → 사용중만
     *   useYn=false          → **사용 중지만**
     * </pre>
     * {@code useYn} 을 주면 {@code includeUnused} 는 무시된다 — 더 구체적인 조건이 이긴다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from Product p
             where (:keyword is null or lower(p.code) like lower(concat('%', :keyword, '%'))
                    or lower(p.name) like lower(concat('%', :keyword, '%'))
                    or lower(coalesce(p.catName, '')) like lower(concat('%', :keyword, '%'))
                    or lower(coalesce(p.catCode, '')) like lower(concat('%', :keyword, '%')))
               and (:ledgerVisible is null or p.ledgerVisible = :ledgerVisible)
               and (:priceVisible is null or p.priceVisible = :priceVisible)
               and (:useYn is not null or :includeUnused = true or p.useYn = true)
               and (:useYn is null or p.useYn = :useYn)
               and (:updatedSince is null
                    or coalesce(p.updatedAt, p.createdAt) >= :updatedSince)
            """)
    org.springframework.data.domain.Page<Product> search(
            @org.springframework.data.repository.query.Param("keyword") String keyword,
            @org.springframework.data.repository.query.Param("ledgerVisible") Boolean ledgerVisible,
            @org.springframework.data.repository.query.Param("priceVisible") Boolean priceVisible,
            @org.springframework.data.repository.query.Param("includeUnused") boolean includeUnused,
            @org.springframework.data.repository.query.Param("useYn") Boolean useYn,
            @org.springframework.data.repository.query.Param("updatedSince") java.time.LocalDateTime updatedSince,
            org.springframework.data.domain.Pageable pageable);

    /**
     * <b>특약점 사이트 신청 화면(+재고현황 목록)에 노출할 도서</b>. 근거: <b>11/04 클라이언트
     * 회신 §1</b>(10/31 §1-1 대체, 그것이 다시 10/26 §3·§9-1 대체) — 노출 판단 순서는
     * <b>위에서부터 처음 걸리는 규칙</b>이다.
     *
     * <pre>
     *   (1) p.webVisible = N          -&gt; 비노출 (단락, 아래 전부 무시)
     *   (2) p.restrictedVisible = Y   -&gt; 열기 행 있으면 노출 / 없으면 비노출
     *                                   (★거래처x대분류 매핑을 아예 보지 않는다)
     *   (3) 열기 행 있음               -&gt; 노출
     *   (4) 거래처x대분류 매핑(PartnerSupplyRate, web_visible=Y·use_yn=Y) -&gt; 노출 / 그 외 비노출
     * </pre>
     *
     * <p>★<b>'닫기'는 없다</b> — 예외 행은 이제 <b>열기 하나</b>뿐이고, 닫는 것은 행을 지우는
     * 것이다. 10/31 판은 열기/닫기 두 상태였는데, "지정 거래처에만 노출"을 그 구조로 하려면
     * 나머지 거래처마다 닫기 행을 깔아야 했고 신규 거래처는 기본 노출이라 의도와 반대로
     * 열렸다. 11/04 회신이 그 자리를 (2)(도서 단위 스위치)로 대체했다.
     *
     * <p>‼️<b>이 규칙은 여기 한 곳에만 있다.</b> 예전엔 같은 규칙이
     * {@code PartnerProductVisibilityService} 안에 자바로 한 번 더 구현돼 있었고, 그 사이
     * {@link #isDealerVisible} 은 호출부가 0건이었다 — "신청 화면엔 보이는데 설정 화면엔
     * 다르게 뜨는" 어긋남이 생기는 모양이다. 설정 화면도 이 메서드/아래 단건 판정을 쓴다.
     *
     * <p>대분류는 도서에 직접 없다 — {@code products.sales_division}(세부구분 코드) →
     * {@code sales_divisions.major_category} 로 한 번 더 타고 간다. FK가 아니라 코드 문자열
     * 연결이라 {@code join ... on} 으로 붙인다. 세부구분이 비었거나 마스터에 없는 도서는
     * 대분류를 알 수 없어 매핑을 찾을 수 없고, 열기 행도 없으면 (4)에 따라 비노출이 된다.
     *
     * <p>★<b>캐시를 두지 않는다</b>(10/26 §3 "도서관리에서 바꾸면 사이트에 바로 반영돼야 한다").
     *
     * <p>⚠️<b>도서 사용여부(p.useYn)는 우리가 더한 조건</b>이다 — 회신 원문에는 없다.
     * 쓰지 않기로 한 도서를 특약점이 주문할 수 있으면 그 주문은 받는 쪽에서 막아야 하는데,
     * 그 경로가 없다. 빼야 하면 이 조건만 지우면 된다(2026-11-03 회신에 질문으로 올림).
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from Product p
              join com.daesung.sales.product.entity.SalesDivision d
                on d.code = p.salesDivision
              left join com.daesung.sales.product.entity.PartnerProductVisibility ex
                on ex.partner.id = :partnerId and ex.product = p and ex.visible = true
              left join com.daesung.sales.product.entity.PartnerSupplyRate m
                on m.majorCategory = d.majorCategory and m.partner.id = :partnerId
             where p.webVisible = true
               and p.useYn = true
               and (ex.id is not null
                 or (p.restrictedVisible = false and m.webVisible = true and m.useYn = true))
             order by p.catCode asc, p.code asc
            """)
    java.util.List<Product> findDealerVisible(
            @org.springframework.data.repository.query.Param("partnerId") Long partnerId);

    /**
     * 특약점 재고현황 교재 — <b>모든 특약점 공통</b> 목록(PM 2026-11-12 §1).
     * 교재(대분류) 여부는 호출부({@code DealerStockService})가 이력 도서와 함께 한 번에 거른다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from Product p
             where p.dealerStockListed = true and p.useYn = true
             order by p.catCode asc, p.code asc
            """)
    java.util.List<Product> findDealerStockListed();

    /**
     * {@link #findDealerVisible} 과 <b>같은 WHERE</b>를 쓰되 단건 판정용 — "이 거래처에
     * 이 도서가 보이는가". [도서관리] '거래처별 노출' 설정 화면이 거래처마다 이걸 묻는다.
     *
     * <p>★규칙을 자바로 다시 쓰지 않기 위한 메서드다. 조건을 고칠 일이 있으면
     * <b>위 메서드와 이 메서드를 반드시 같이</b> 고친다(같은 규칙의 목록형·단건형이다).
     */
    @org.springframework.data.jpa.repository.Query("""
            select count(p) > 0 from Product p
              join com.daesung.sales.product.entity.SalesDivision d
                on d.code = p.salesDivision
              left join com.daesung.sales.product.entity.PartnerProductVisibility ex
                on ex.partner.id = :partnerId and ex.product = p and ex.visible = true
              left join com.daesung.sales.product.entity.PartnerSupplyRate m
                on m.majorCategory = d.majorCategory and m.partner.id = :partnerId
             where p = :product
               and p.webVisible = true
               and p.useYn = true
               and (ex.id is not null
                 or (p.restrictedVisible = false and m.webVisible = true and m.useYn = true))
            """)
    boolean isDealerVisible(@org.springframework.data.repository.query.Param("partnerId") Long partnerId,
                            @org.springframework.data.repository.query.Param("product") Product product);
}
