package com.daesung.sales.sale.repository;

import com.daesung.sales.sale.dto.AttendanceAgg;
import com.daesung.sales.sale.dto.AttendancePeriodAgg;
import com.daesung.sales.sale.dto.BookSalesAgg;
import com.daesung.sales.sale.dto.CategorySalesAgg;
import com.daesung.sales.sale.dto.PartnerProductSalesAgg;
import com.daesung.sales.sale.dto.ReturnableAgg;
import com.daesung.sales.sale.dto.ShipmentQtyAgg;
import com.daesung.sales.sale.dto.ShipmentWarehouseAgg;
import com.daesung.sales.sale.dto.WorkOrderLineAgg;
import com.daesung.sales.sale.dto.SalesStatementAgg;
import com.daesung.sales.sale.entity.Sale;
import com.daesung.sales.salestype.entity.SalesCategory;
import com.daesung.sales.salestype.entity.ShipmentType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    /** 매출번호로 한 건. 주문 등록이 방금 세운 매출에 주문번호를 붙일 때 쓴다(9/27 항목 2). */
    java.util.Optional<Sale> findBySalesNo(String salesNo);

    /**
     * 거래명세서 발행 대상 <b>학교 목록</b>. 실물 양식의 「학교(원)」 칸은 <b>값이 하나</b>라
     * 명세서는 (거래처 × 학교) 단위로 발행된다 — 기간에 학교가 여럿이면 여러 장이다.
     *
     * <p>이 목록이 없으면 화면은 학교가 섞인 한 장을 만들고 <b>첫 학교 이름만</b> 찍는다.
     * 그러면 나머지 학교 물량이 남의 명세서에 실린다.
     */
    @Query("""
            select distinct s.schoolCode as schoolCode, s.schoolName as schoolName
              from Sale s
             where s.partner.id = :partnerId and s.canceled = false
               and s.salesDate between :from and :to
               and s.salesCategory in :categories
             order by s.schoolName
            """)
    List<StatementSchool> statementSchools(@Param("partnerId") Long partnerId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to,
                                           @Param("categories") Collection<SalesCategory> categories);

    /** 거래명세서 발행 대상 학교 한 줄. */
    interface StatementSchool {
        String getSchoolCode();

        String getSchoolName();
    }


    /**
     * 주문번호별 매출번호. 주문 목록이 "이 주문이 무슨 매출이 됐나"를 붙일 때 쓴다.
     *
     * <p>한 주문이 여러 매출로 갈릴 수 있다(품목·학교별로 나뉘는 경우) — 그래서 목록이다.
     * 취소·삭제분은 뺀다. 되돌린 매출까지 붙이면 화면이 "이미 처리됨"으로 읽는다.
     */
    @Query("""
            select s.reqCd as reqCd, s.salesNo as salesNo
              from Sale s
             where s.reqCd in :reqCds and s.canceled = false
             order by s.salesNo
            """)
    List<ReqSalesNo> salesNosByReqCds(@Param("reqCds") Collection<Integer> reqCds);

    /** 주문번호 ↔ 매출번호 한 줄. */
    interface ReqSalesNo {
        Integer getReqCd();

        String getSalesNo();
    }


    /**
     * 매출액명세서 도서 단위 집계(취소 제외, 기간·회계구분 필터). 근거: 레거시 매출액명세서.vb.
     * 금액=Σ공급가, 세액=Σ세액. 합계(금액+세액)와 대분류·소계·총계 rollup은 서비스에서 조립.
     *
     * <p>★대분류는 세부구분 마스터를 <b>left join</b>해 가져온다(발주처 회신 2026-08-20).
     * left인 이유: 세부구분이 없거나 마스터에 없는 상품도 매출은 실재하므로 빠지면 안 된다
     * (그런 행은 대분류 null → 서비스에서 '미분류'로 모인다).
     *
     * <p>필터는 정본 15p 그대로 <b>구분(5종) + 매출유형(일반/위탁)</b>이다.
     * 구분은 회계구분·출고유형이 섞인 축이라(교사용·증정용은 둘 다 무상) 두 파라미터로 받는다.
     *
     * <p>행 순서는 rollup 조립용으로 catCode·code 오름차순이고, <b>대분류 순서는 서비스에서 정한다</b> —
     * 대분류를 SQL로 정렬하면 enum 이름 알파벳순(ETC, ETC_EXAM, MOCK_EXAM…)이 되어
     * 화면 순서(모의고사·교재·기타고사·특강·기타)와 어긋난다.
     */
    @Query("""
            select d.majorCategory as majorCategory,
                   p.catCode as catCode, p.catName as catName,
                   p.code as bookCode, p.name as bookName,
                   sum(s.qty) as qty,
                   sum(coalesce(s.supplyAmount, 0)) as amount,
                   sum(coalesce(s.tax, 0)) as tax
            from Sale s join s.product p
              left join SalesDivision d on d.code = p.salesDivision
            where s.canceled = false
              and s.salesDate between :from and :to
              and (:partnerId is null or s.partner.id = :partnerId)
              and (:category is null or s.salesCategory = :category)
              and (:shipmentType is null or s.shipmentType = :shipmentType)
              and (:salesType is null or s.salesType = :salesType)
              and (:anyTradeClass = true or s.salesCategory in :tradeCategories)
            group by d.majorCategory, p.catCode, p.catName, p.code, p.name
            order by p.catCode asc, p.code asc
            """)
    List<SalesStatementAgg> statementAgg(@Param("from") LocalDate from,
                                         @Param("to") LocalDate to,
                                         @Param("partnerId") Long partnerId,
                                         @Param("category") SalesCategory category,
                                         @Param("shipmentType") ShipmentType shipmentType,
                                         @Param("salesType") com.daesung.sales.sale.entity.SalesType salesType,
                                         @Param("anyTradeClass") boolean anyTradeClass,
                                         @Param("tradeCategories") Collection<SalesCategory> tradeCategories);

    /**
     * 거래명세서 라인(거래처×기간). 취소 제외, 지정 회계구분(들)만. 상품 fetch join으로 N+1 방지.
     * 정렬: 분류코드→도서코드→매출일자. 근거: 레거시 거래명세서 데이터셋.
     */
    @Query("select s from Sale s join fetch s.product p "
            + "where s.partner.id = :partnerId and s.canceled = false "
            + "and s.salesDate between :from and :to "
            + "and s.salesCategory in :categories "
            + "order by p.catCode asc, p.code asc, s.salesDate asc")
    List<Sale> statementLines(@Param("partnerId") Long partnerId,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to,
                              @Param("categories") java.util.Collection<SalesCategory> categories);

    /**
     * 과목별매출현황 집계(거래처×분류×도서). 취소 제외, 거래처·분류 옵션 필터.
     * 매출/반품/교사용 수량을 salesCategory로 버킷. 근거: 레거시 과목별매출현황.vb.
     */
    @Query("""
            select pt.id as partnerId, pt.code as partnerCode, pt.name as partnerName,
                   p.catCode as catCode, p.catName as catName, p.code as bookCode, p.name as bookName,
                   sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.SALE then s.qty else 0 end) as saleQty,
                   sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.RETURN then s.qty else 0 end) as returnQty,
                   sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.FREE then s.qty else 0 end) as teacherQty,
                   sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.SALE then coalesce(s.supplyAmount, 0) else 0 end) as saleAmount,
                   sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.RETURN then coalesce(s.supplyAmount, 0) else 0 end) as returnAmount,
                   sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.FREE then coalesce(s.supplyAmount, 0) else 0 end) as teacherAmount
            from Sale s join s.product p join s.partner pt
            where s.canceled = false
              and s.salesDate between :from and :to
              and (:partnerId is null or pt.id = :partnerId)
              and (:catCode is null or p.catCode = :catCode)
            group by pt.id, pt.code, pt.name, p.catCode, p.catName, p.code, p.name
            order by pt.code asc, p.catCode asc, p.code asc
            """)
    List<CategorySalesAgg> categorySales(@Param("from") LocalDate from,
                                         @Param("to") LocalDate to,
                                         @Param("partnerId") Long partnerId,
                                         @Param("catCode") String catCode);

    /**
     * 도서입출고현황의 매출측(상품별 출고/반품 수량·금액). 취소 제외.
     * 근거: 레거시 도서입출고현황.vb 매출/반품 버킷. 매입측·재고는 InventoryTxnRepository에서 병합.
     */
    @Query("select p.id as productId, "
            + "sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.SALE then s.qty else 0 end) as outQty, "
            + "sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.SALE then coalesce(s.supplyAmount,0) else 0 end) as outAmt, "
            + "sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.RETURN then s.qty else 0 end) as retQty, "
            + "sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.RETURN then coalesce(s.supplyAmount,0) else 0 end) as retAmt "
            + "from Sale s join s.product p "
            + "where s.canceled = false and s.salesDate between :from and :to "
            + "group by p.id")
    List<BookSalesAgg> bookSalesAgg(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 교재식 반품 가능내역: 거래처×도서×정가×공급률 단위로 누적 판매출고(SALE)·기반품(RETURN) 집계.
     * 반품가능수량 = SALE − RETURN(서비스에서 계산·필터). 취소 제외. productId 옵션 필터.
     * 공급률·정가별로 그룹 → 반품 시 원 출고건 공급률 고정 적용의 근거가 됨.
     */
    @Query("select p.id as productId, p.code as productCode, p.name as productName, "
            + "s.unitPrice as unitPrice, s.supplyRate as supplyRate, "
            + "sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.SALE then s.qty else 0 end) as saleQty, "
            + "sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.RETURN then s.qty else 0 end) as returnQty, "
            // ★원출고 매출번호 — 반품 등록이 sourceOutNo 로 요구하는 값이다.
            //   판매출고(SALE)만 모은다. 반품 줄의 번호를 섞으면 "무엇을 되돌리는지"가 뒤집힌다.
            + "function('group_concat', case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.SALE then s.salesNo else null end) as sourceSalesNos "
            + "from Sale s join s.product p "
            + "where s.partner.id = :partnerId and s.canceled = false "
            + "and (:productId is null or p.id = :productId) "
            + "group by p.id, p.code, p.name, s.unitPrice, s.supplyRate "
            + "order by p.code, s.supplyRate")
    List<ReturnableAgg> returnableAgg(@Param("partnerId") Long partnerId, @Param("productId") Long productId);

    /**
     * 거래처×상품 매출집계(SALE만, 취소 제외). 거래처별 매출대비표(전년 동기간 비교)용.
     * 근거: 레거시 거래처별_매출대비표.vb(매출+유상무상=우리 SALE로 정규화). 거래처·분류 옵션 필터.
     */
    @Query("select pt.id as partnerId, pt.code as partnerCode, pt.name as partnerName, "
            + "p.catCode as catCode, p.catName as catName, "
            + "p.id as productId, p.code as bookCode, p.name as bookName, "
            + "sum(s.qty) as qty, sum(coalesce(s.supplyAmount,0)) as amount "
            + "from Sale s join s.partner pt join s.product p "
            + "where s.canceled = false "
            + "and s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.SALE "
            + "and s.salesDate between :from and :to "
            + "and (:partnerId is null or pt.id = :partnerId) "
            + "and (:catCode is null or p.catCode = :catCode) "
            + "group by pt.id, pt.code, pt.name, p.catCode, p.catName, p.id, p.code, p.name")
    List<PartnerProductSalesAgg> salesByPartnerProduct(@Param("from") LocalDate from,
                                                       @Param("to") LocalDate to,
                                                       @Param("partnerId") Long partnerId,
                                                       @Param("catCode") String catCode);

    /**
     * 기간 순매출을 <b>가장 잘게</b> 집계(19p 기본 통계 대시보드).
     * 상품×거래처×대분류×거래처구분 단위로 내고, 축별 합계(상품별·거래처별·대분류별·구분별)는 서비스에서 접는다.
     *
     * <p>★순매출 식은 20p 목표대비({@link #monthlyNetSales})와 <b>같다</b> —
     * {@code SALE 공급가 − RETURN 공급가}, 취소 제외. 정본 19p가
     * "20페이지 매출상세대시보드와 원천데이터 공유"를 조건으로 달았으므로 식이 갈리면 안 된다.
     *
     * <p>축마다 쿼리를 따로 쓰지 않는 이유가 그것이다 — 네 벌로 갈라지면 언젠가 한 벌만 고쳐진다.
     * 접는 일은 Java가 한다(행 수는 매출 조합 수라 대시보드에 충분히 작다).
     *
     * <p>반환 Object[]: [productId, productName, partnerId, partnerName, majorCategory, clientCategory,
     * netAmount, <b>shipmentType</b>, <b>region</b>, <b>salesDivision</b>].
     *
     * <p>★출고유형·지역 축은 2026-09-16에 <b>이 쿼리에 붙였다</b> — 상세 대시보드의
     * '출고유형별/지역별 매출' 카드가 쓴다. 축마다 쿼리를 새로 파지 않는 이 파일의 원칙 그대로다.
     * 행 수는 조합만큼 늘지만 대시보드 한 화면 분량이라 문제되지 않는다.
     */
    @Query(value = """
            SELECT p.id, MAX(p.name), pt.id, MAX(pt.name),
                   COALESCE(d.major_category, ''), COALESCE(pt.client_category, ''),
                   COALESCE(SUM(CASE WHEN s.sales_category='SALE'   THEN s.supply_amount
                                     WHEN s.sales_category='RETURN' THEN -s.supply_amount
                                     ELSE 0 END),0) AS net_amt,
                   COALESCE(s.shipment_type, ''), COALESCE(pt.region, ''),
                   COALESCE(p.sales_division, '')
            FROM sales s
              JOIN products p  ON p.id = s.product_id
              JOIN partners pt ON pt.id = s.partner_id
              LEFT JOIN sales_divisions d ON d.code = p.sales_division
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_date BETWEEN :fromDate AND :toDate
            GROUP BY p.id, pt.id, d.major_category, pt.client_category, s.shipment_type, pt.region,
                     p.sales_division
            """, nativeQuery = true)
    List<Object[]> netSalesBreakdown(@Param("fromDate") LocalDate fromDate,
                                     @Param("toDate") LocalDate toDate);

    /**
     * 외상매출장 '더프모만'(24p) 명세. 근거: 레거시 외상매출장조회.vb:603
     * {@code Refresh_DataGridView_더프모만()}.
     *
     * <p>정본 24p: "'더프모만' 체크 시 그리드 컬럼 구조 자체가 학교(원)별/학년별/시행월별
     * 세분화 구조로 <b>완전 전환</b>됨(조건부 스키마)".
     *
     * <p>★레거시는 여기서도 <b>문자열을 파싱</b>했고, 안 맞으면 {@code '##ERROR'}를 찍었다:
     * <pre>
     *   시행월 = LEFT(bookName, CHARINDEX('월', bookName))
     *   학년   = CASE WHEN bookName LIKE '%고3%' THEN '고3' … ELSE '##ERROR'
     *   처리   = CASE WHEN catName LIKE '%비처리%' … ELSE '##ERROR'
     * </pre>
     * 우리는 셋 다 필드로 갖고 있어 파싱하지 않는다 —
     * 학년={@code products.grade}, 처리={@code sales.proc_type},
     * 시행월={@code bom_items.exam_date}(V27, 그 상품·회차의 시행예정일).
     *
     * <p>★<b>더프 판별은 레거시 그대로</b> 분류코드 패턴이다 —
     * {@code catCode LIKE 'M%A%' OR 'M%B%' OR 'M%C%'}(외상매출장조회.vb:668).
     * 더프는 모의고사 <b>전체가 아니라 그중 A·B·C 계열</b>이라, 대분류(모의고사)로 거르면 범위가 넓어진다.
     *
     * <p>패턴에 기대도 되는 이유: 우리 분류코드는 {@code @CatCode}로
     * {@code [영문1][연도4][영문·숫자1~3]} 형식을 <b>강제</b>하고 있고, 그 규칙은
     * 레거시 실데이터 4,600건이 100% 따르는 것을 확인한 것이다. 2~5자리가 숫자라
     * {@code 'M%A%'}의 A는 접미부에서만 걸린다.
     *
     * <p><b>시행월(yyyy-MM)은 2단 경로다 — BOM 시행예정일이 1순위, 도서명이 2순위.</b>
     * BOM에만 기대면 <b>전 행이 빈 칸</b>이 된다. 이관 데이터에 세트구성 시행예정일을
     * 넣은 상품이 없기 때문이다(화면이 "빈 칸 시행월"로 두 달째 띄우고 있던 결함).
     *
     * <p>2순위 근거 — 운영 추출본 전수 확인(2026-10-23):
     * <pre>
     *   2026년 더프 매출 5,112건 중 도서명에서 시행월이 나오는 건  4,983 (97%)
     *   남는 129건 = 「고3 평가고사」 127(시행월 개념이 없는 상품) + 도서명 오기 2
     * </pre>
     * 즉 <b>틀린 값이 찍히는 건은 0건</b>이고, 못 채우는 3%는 BOM에 넣으면 1순위로 채워진다.
     *
     * <p>★레거시({@code LEFT(bookName, CHARINDEX('월', bookName))})보다 <b>좁게</b> 잡는다.
     * 그쪽은 첫 '월'까지 무조건 잘라서 {@code 월례고사} 같은 이름이 '월'로 찍혔다.
     * 우리는 <b>맨 앞 1~2자리 숫자 + '월'</b>이고 그 수가 1~12일 때만 쓴다.
     *
     * <p>★연도는 <b>분류코드</b>에서 온다(판매일이 아니다). 2026-01에 팔린
     * "11월 고2 수능"은 분류가 {@code M2025A…}인 <b>2025년 11월</b> 시행분이다 —
     * 판매일로 연도를 잡으면 한 해가 밀린다. 분류코드 {@code [영문1][연도4][…]} 형식은
     * {@code @CatCode}가 강제하고 있어 2~5자리를 연도로 쓸 수 있다.
     *
     * <p>★{@code partnerId}가 <b>null이면 전 거래처</b>다. 레거시도 거래처 콤보 0번이
     * "전체 거래처"이고, <b>그걸 골랐을 때만</b> 「전 거래처 메일 일괄발송」·「PDF 일괄저장」이
     * 켜진다(외상매출장조회.vb:97~106·121). 즉 전체 조회는 부수 기능이 아니라
     * <b>일괄 발송의 전제</b>다. 거래처별로 끊어 쓰도록 {@code pt.code} 로 먼저 정렬한다.
     *
     * <p>소계(학교 계·월 계)는 서비스에서 조립한다. 반환 Object[]:
     * [salesDate, schoolName, <b>examMonth(문자열 yyyy-MM)</b>, grade, procType,
     *  unitPrice, supplyRate, qty, amount,
     *  <b>partnerId, partnerCode, partnerName, partnerDsreCode, schoolCode</b>].
     *
     * <p>★<b>정렬은 학교명이 아니라 학교코드순</b>이다(10/27 피드백 §7-B "레거시는 학교·학원명
     * 가나다순이 아니다. 기존 소스에서 정렬 기준을 확인해 같게 적용한다").
     * 레거시 원문({@code 외상매출장조회.vb:672}) —
     * {@code ORDER BY 거래년월, isnull(schCode,99999), grouping(schCode), …, 시행월, 학년, 처리}.
     * 학교코드가 빈 건을 뒤로 보내는 것도 레거시의 {@code isnull(schCode,99999)}와 같은 뜻이다.
     * 실물 PDF({@code RESULT_*.pdf})의 줄 순서가 날짜순도 가나다순도 아닌 이유가 이것이다.
     */
    @Query(value = """
            SELECT s.sales_date, COALESCE(s.school_name, ''),
                   COALESCE(
                     (SELECT DATE_FORMAT(MIN(b.exam_date), '%Y-%m') FROM bom_items b
                       WHERE b.parent_product_id = s.product_id
                         AND b.round = s.book_round AND b.deleted_at IS NULL),
                     CASE WHEN p.name REGEXP '^[0-9]{1,2}월'
                           AND CAST(REGEXP_SUBSTR(p.name, '^[0-9]{1,2}') AS UNSIGNED) BETWEEN 1 AND 12
                           AND p.cat_code REGEXP '^[A-Za-z][0-9]{4}'
                          THEN CONCAT(SUBSTRING(p.cat_code, 2, 4), '-',
                                      LPAD(REGEXP_SUBSTR(p.name, '^[0-9]{1,2}'), 2, '0'))
                     END
                   ) AS exam_month,
                   COALESCE(p.grade, ''), COALESCE(s.proc_type, ''),
                   s.unit_price, s.supply_rate, s.qty, COALESCE(s.supply_amount, 0),
                   s.partner_id, pt.code, pt.name, pt.dsre_code, COALESCE(s.school_code, '')
            FROM sales s
              JOIN products p ON p.id = s.product_id
              JOIN partners pt ON pt.id = s.partner_id
              LEFT JOIN sales_divisions d ON d.code = p.sales_division
            WHERE s.canceled = false
              AND p.cat_code LIKE 'M%'
              AND (p.cat_code LIKE 'M%A%' OR p.cat_code LIKE 'M%B%' OR p.cat_code LIKE 'M%C%')
              AND (:partnerId IS NULL OR s.partner_id = :partnerId)
              AND s.sales_date BETWEEN :fromDate AND :toDate
            ORDER BY pt.code, s.partner_id,
                     EXTRACT(YEAR FROM s.sales_date), EXTRACT(MONTH FROM s.sales_date),
                     CASE WHEN COALESCE(s.school_code,'') = '' THEN 1 ELSE 0 END, s.school_code,
                     exam_month, p.grade, s.proc_type, s.id
            """, nativeQuery = true)
    List<Object[]> duffLedgerLines(@Param("partnerId") Long partnerId,
                                   @Param("fromDate") LocalDate fromDate,
                                   @Param("toDate") LocalDate toDate);

    /**
     * <b>미분류 매출 — 상품별 목록</b>(10/27 피드백 §7-C 하단 5번
     * "데이터 이관 때 매핑되지 않는 누락 건은 반드시 목록으로 뽑아 확인한다").
     *
     * <p>대분류는 {@code sales → products.sales_division → sales_divisions.major_category} 로
     * 이어진다. 이 사슬이 끊기면 그 매출은 상품군 칸 어디에도 안 들어가고 '미분류'가 된다.
     * 끊기는 경우는 둘이다 —
     * <ul>
     *   <li>{@code EMPTY}: 상품에 세부구분이 비어 있다(이관 때 안 채워진 경우)</li>
     *   <li>{@code UNKNOWN}: 세부구분 값이 있는데 세부구분 마스터에 없다(오타·폐지된 값)</li>
     * </ul>
     *
     * <p>★<b>매출이 큰 순서로 돌려준다.</b> 담당자가 하나씩 채워야 하는 목록이라
     * 어느 상품부터 손대야 장부가 빨리 맞는지가 먼저 보여야 한다.
     *
     * <p>반환 Object[]: [productId, code, name, catCode, catName, salesDivision, reason,
     * saleQty, saleAmount, lastSalesDate].
     */
    @Query(value = """
            SELECT p.id, p.code, p.name, p.cat_code, p.cat_name,
                   COALESCE(p.sales_division, ''),
                   CASE WHEN COALESCE(p.sales_division,'') = '' THEN 'EMPTY' ELSE 'UNKNOWN' END,
                   COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.qty ELSE 0 END),0),
                   COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.supply_amount ELSE 0 END),0),
                   MAX(s.sales_date)
            FROM sales s
              JOIN products p ON p.id = s.product_id
              LEFT JOIN sales_divisions d ON d.code = p.sales_division
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_date BETWEEN :fromDate AND :toDate
              AND d.major_category IS NULL
            GROUP BY p.id, p.code, p.name, p.cat_code, p.cat_name, p.sales_division
            ORDER BY 9 DESC, p.code
            """, nativeQuery = true)
    List<Object[]> unclassifiedProducts(@Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate);

    /** 기간 전체 매출액(공급가, 취소·삭제 제외) — 미분류 비율의 분모. */
    @Query(value = """
            SELECT COALESCE(SUM(s.supply_amount), 0)
            FROM sales s
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_category = 'SALE'
              AND s.sales_date BETWEEN :fromDate AND :toDate
            """, nativeQuery = true)
    long sumSaleAmount(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    /** 매출일괄등록 멱등: 이미 등록된 소스키인지. */
    boolean existsByBulkImportKey(String bulkImportKey);

    /**
     * 그 <b>신청번호로 이미 매출이 섰는지</b>(취소분 제외).
     *
     * <p>근거: 프론트 확인 질문(2026-10-01) — 주문 등록 화면에서 매출까지 함께 세운 신청이
     * 매출일괄등록·더프 가져오기에 <b>다시 잡히면 이중 매출</b>이 된다.
     *
     * <p>★소스키({@code bulkImportKey})로는 못 막는다. 그 키는 가져오기 경로가 만드는 값이라,
     * 주문 화면에서 만든 매출에는 없다. 두 경로가 공유하는 유일한 값이 신청번호다.
     */
    boolean existsByReqCdAndCanceledFalse(Integer reqCd);

    /**
     * 월별 실적(순매출액 = 매출−반품). 취소 제외. 상품 필터(null=전체). 대시보드 목표대비용.
     * 반환 Object[]: [month(1~12), netAmount].
     */
    @Query(value = """
            SELECT EXTRACT(MONTH FROM s.sales_date) AS mon,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.supply_amount
                                WHEN s.sales_category='RETURN' THEN -s.supply_amount ELSE 0 END),0) AS net_amt
            FROM sales s
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND EXTRACT(YEAR FROM s.sales_date) = :year
              AND (CAST(:productId AS SIGNED) IS NULL OR s.product_id = :productId)
            GROUP BY EXTRACT(MONTH FROM s.sales_date)
            """, nativeQuery = true)
    List<Object[]> monthlyNetSales(@Param("year") int year, @Param("productId") Long productId);

    /**
     * 통합 매출 조회(기간·회계구분·출고유형·거래처·취소포함 여부 필터).
     * 근거: 발주처 화면검토(2026-08-31) 화면3 — "무상/유상/반품/입고 <b>중복선택</b> 체크박스".
     *
     * <p>세 축 모두 <b>다중선택</b>이다. 축마다 {@code anyXxx} 플래그가 짝으로 붙는데,
     * 이는 "이 축은 안 걸렀다"는 뜻이고 그때 {@code in} 절 자체를 건너뛴다.
     * 빈 컬렉션을 {@code in ()} 으로 넘기면 DB마다 다르게 깨지므로 플래그로 분기한다
     * ({@code MultiSelect.orPlaceholder} 참고).
     */
    // ‼️창고는 **left join** 이어야 한다. `s.warehouse.id` 라고만 쓰면 JPQL이 inner join을 만들어
    //   창고가 없는 과거 매출(업로드분 등)이 조건과 무관하게 통째로 사라진다.
    /**
     * 통합 매출 조회의 <b>WHERE 절</b>. {@link #search} 와 {@link #groupTotalsByPartner}·
     * {@link #groupTotalsByDate} 가 <b>같은 문자열을 공유</b>한다.
     *
     * <p>★이렇게 뺀 이유 — 프론트 B-3 이 "조건은 {@code /sales} 와 같음"을 요구한다.
     * 복사해 두면 한쪽만 고쳐져 <b>목록과 소계가 다른 집합을 세게</b> 된다. 이 프로젝트는
     * 이미 그런 어긋남을 겪었다(물류 작업비가 출고/반품 조회와 다른 조건을 쓰던 일).
     * 상수 하나를 이어 붙이면 갈릴 수가 없다.
     */
    String SALES_SEARCH_WHERE =
            "where (:from is null or s.salesDate >= :from) "
            + "and (:to is null or s.salesDate <= :to) "
            + "and (:anyCategory = true or s.salesCategory in :salesCategories) "
            + "and (:anyShipmentType = true or s.shipmentType in :shipmentTypes) "
            + "and (:anyPartner = true or s.partner.id in :partnerIds) "
            + "and (:anyWarehouse = true or w.id in :warehouseIds) "
            // ★학교 축(9/27 항목 13-② "학교별 중복조회"). 한 거래처가 여러 학교에 나가므로
            //   거래처만으로는 좁혀지지 않는다.
            + "and (:anySchool = true or s.schoolCode in :schoolCodes) "
            // ★세부구분·도서 축(2026-10-08). 없던 동안 화면이 기간 전체를 받아 걸러 쓰고 있었다 —
            //   데이터가 쌓이면 느려지는 정도가 아니라 못 쓴다.
            + "and (:anyDivision = true or coalesce(s.product.salesDivision, '') in :divisions) "
            + "and (:anyProduct = true or s.product.id in :productIds) "
            // ★거래처×학교 **조합**. 'A는 전체 + B는 특정 학교만' 처럼 거래처마다 학교가 다른 조회를
            //   거래처목록 AND 학교목록으로는 표현할 수 없다(A의 그 학교까지 걸러진다).
            //   쌍을 'partnerId:schoolCode' 문자열로 받아 매칭한다. 전체는 'partnerId:*'.
            + "and (:anyPair = true "
            + "     or concat(s.partner.id, ':', coalesce(s.schoolCode, '')) in :pairs "
            + "     or concat(s.partner.id, ':*') in :pairs) "
            // ★판매채널(레거시 `조회.Designer.vb:365` 콤보). 값은 **학교 마스터**에 있다
            //   (schDataDT.type1 — 우리 School.salesChannel). 매출 행에는 없으므로 학교코드로 되짚는다.
            //   ‼️'미분류'는 레거시가 별도 분기로 처리한다(조회.vb:1205 `type1 is null or ''`) —
            //     목록에 넣어 비교하면 값이 NULL 이라 영원히 안 걸린다.
            // ‼️**exists 상관 서브쿼리를 쓰면 안 된다.** 실데이터 33만 행에서 이 조건 하나로
            //   0.08초 → **45초**가 됐다(2026-10-16 이관 후 실측). MySQL 이 `0=1 and not exists(...)`
            //   를 상수로 접지 못하고 **행마다 서브쿼리를 돌린다** — 조건이 꺼져 있어도 그렇다.
            //   IN/NOT IN 은 세미조인으로 풀려 0.19초다(265배).
            //   ★시드 수십 건에서는 드러나지 않는다. 실데이터가 들어와야 보인다.
            //
            // ‼️미분류에 `school_code is null` 을 **반드시 함께** 둔다.
            //   `not in` 은 좌변이 NULL 이면 결과가 NULL(거짓)이라, 학교 없는 매출
            //   (전체의 73%, 248,949건)이 통째로 빠진다 — 실제로 0건이 나왔다.
            + "and (:anyChannel = true "
            + "     or (:unclassified = true and (s.schoolCode is null or s.schoolCode = '' "
            + "          or s.schoolCode not in (select uc.schoolCode from School uc "
            + "               where uc.salesChannel is not null and uc.salesChannel <> ''))) "
            + "     or s.schoolCode in (select sc.schoolCode from School sc "
            + "                           where sc.salesChannel in :channels)) "
            // 키워드는 담당자가 화면에서 치는 값이다 — 거래처명·도서명·도서코드·매출번호·학교명을 함께 훑는다.
            // 어느 칸에 쳤는지 기억하고 고르게 하면 실무에서 안 쓴다.
            + "and (:keyword is null or lower(s.partner.name) like :keyword "
            + "     or lower(s.product.name) like :keyword or lower(s.product.code) like :keyword "
            + "     or lower(s.salesNo) like :keyword or lower(coalesce(s.schoolName, '')) like :keyword) "
            // 거래순번(10/30 프론트 B-2③ — "거래처 + 거래일자 + 거래순번으로 조회").
            // 거래처·기간은 위 조건이 이미 받으므로 여기서는 순번만 더한다.
            + "and (:tradeSeq is null or s.tradeSeq = :tradeSeq) "
            // ★출고/반품 조회(10/27 §3-3·4-10·4-12, 프론트 B-1) — **실제로 물건이 움직인 건만**.
            //   §3 머리말이 두 화면을 이렇게 가른다:
            //     출고/반품 조회 = 실제로 재고가 움직인 출고·반품(물류 작업비 계산 기준)
            //     통합 매출 조회 = 매출로 인식되는 전체(미출고 콘텐츠·특강 포함)
            //   빠져야 하는 셋을 **등록 쪽과 같은 규칙**으로 건다 —
            //     ① 매출만(배송X)  ② 창고 없는 매출  ③ 특강
            //   ‼️"작업요청서가 있나"로 가르지 않는다. 이 규칙이 생기기 전(10/29) 등록분은
            //     매출만 건에도 작업요청서가 남아 있어, 그걸 기준 삼으면 과거분이 그대로 샌다.
            + "and (:shippedOnly = false or ("
            + "      coalesce(s.onlySales, false) = false "
            + "      and w.id is not null "
            + "      and coalesce(s.product.salesDivision, '') not in :lectureDivisions)) "
            + "and (:includeCanceled = true or s.canceled = false)";

    @Query("select s from Sale s left join s.warehouse w " + SALES_SEARCH_WHERE)
    Page<Sale> search(@Param("from") LocalDate from,
                      @Param("to") LocalDate to,
                      @Param("anyCategory") boolean anyCategory,
                      @Param("salesCategories") Collection<SalesCategory> salesCategories,
                      @Param("anyShipmentType") boolean anyShipmentType,
                      @Param("shipmentTypes") Collection<ShipmentType> shipmentTypes,
                      @Param("anyPartner") boolean anyPartner,
                      @Param("partnerIds") Collection<Long> partnerIds,
                      @Param("anyWarehouse") boolean anyWarehouse,
                      @Param("warehouseIds") Collection<Long> warehouseIds,
                      @Param("anySchool") boolean anySchool,
                      @Param("schoolCodes") Collection<String> schoolCodes,
                      @Param("anyDivision") boolean anyDivision,
                      @Param("divisions") Collection<String> divisions,
                      @Param("anyProduct") boolean anyProduct,
                      @Param("productIds") Collection<Long> productIds,
                      @Param("anyPair") boolean anyPair,
                      @Param("pairs") Collection<String> pairs,
                      @Param("anyChannel") boolean anyChannel,
                      @Param("unclassified") boolean unclassified,
                      @Param("channels") Collection<String> channels,
                      @Param("keyword") String keyword,
                      @Param("tradeSeq") Integer tradeSeq,
                      @Param("shippedOnly") boolean shippedOnly,
                      @Param("lectureDivisions") Collection<String> lectureDivisions,
                      @Param("includeCanceled") boolean includeCanceled,
                      Pageable pageable);

    /**
     * <b>묶음 합계 — 거래처별</b>(프론트 B-3). 소계 행을 붙이려고 쓴다.
     *
     * <p>★조건은 {@link #search} 와 <b>같은 문자열</b>을 쓴다({@link #SALES_SEARCH_WHERE}) —
     * 목록과 소계가 다른 집합을 세면 화면에서 합이 안 맞는다.
     *
     * <p>지금은 화면이 전량을 받아 더하는데 1~10월 27,187건에 <b>약 4분</b>이 걸린다
     * (프론트 실측). DB 가 세면 한 번이면 끝난다.
     *
     * <p>반환 {@code Object[]}: [키, 이름, 수량, 공급가액, 세액, 합계금액]
     */
    @Query("select s.partner.id, s.partner.name, "
            + "coalesce(sum(s.qty),0), coalesce(sum(s.supplyAmount),0), "
            + "coalesce(sum(s.tax),0), coalesce(sum(s.totalAmount),0) "
            + "from Sale s left join s.warehouse w " + SALES_SEARCH_WHERE
            + "group by s.partner.id, s.partner.name order by s.partner.name")
    List<Object[]> groupTotalsByPartner(@Param("from") LocalDate from,
                      @Param("to") LocalDate to,
                      @Param("anyCategory") boolean anyCategory,
                      @Param("salesCategories") Collection<SalesCategory> salesCategories,
                      @Param("anyShipmentType") boolean anyShipmentType,
                      @Param("shipmentTypes") Collection<ShipmentType> shipmentTypes,
                      @Param("anyPartner") boolean anyPartner,
                      @Param("partnerIds") Collection<Long> partnerIds,
                      @Param("anyWarehouse") boolean anyWarehouse,
                      @Param("warehouseIds") Collection<Long> warehouseIds,
                      @Param("anySchool") boolean anySchool,
                      @Param("schoolCodes") Collection<String> schoolCodes,
                      @Param("anyDivision") boolean anyDivision,
                      @Param("divisions") Collection<String> divisions,
                      @Param("anyProduct") boolean anyProduct,
                      @Param("productIds") Collection<Long> productIds,
                      @Param("anyPair") boolean anyPair,
                      @Param("pairs") Collection<String> pairs,
                      @Param("anyChannel") boolean anyChannel,
                      @Param("unclassified") boolean unclassified,
                      @Param("channels") Collection<String> channels,
                      @Param("keyword") String keyword,
                      @Param("tradeSeq") Integer tradeSeq,
                      @Param("shippedOnly") boolean shippedOnly,
                      @Param("lectureDivisions") Collection<String> lectureDivisions,
                      @Param("includeCanceled") boolean includeCanceled);

    /** <b>묶음 합계 — 거래일자별</b>. 근거·구조는 {@link #groupTotalsByPartner} 와 같다. */
    @Query("select s.salesDate, s.salesDate, "
            + "coalesce(sum(s.qty),0), coalesce(sum(s.supplyAmount),0), "
            + "coalesce(sum(s.tax),0), coalesce(sum(s.totalAmount),0) "
            + "from Sale s left join s.warehouse w " + SALES_SEARCH_WHERE
            + "group by s.salesDate order by s.salesDate")
    List<Object[]> groupTotalsByDate(@Param("from") LocalDate from,
                      @Param("to") LocalDate to,
                      @Param("anyCategory") boolean anyCategory,
                      @Param("salesCategories") Collection<SalesCategory> salesCategories,
                      @Param("anyShipmentType") boolean anyShipmentType,
                      @Param("shipmentTypes") Collection<ShipmentType> shipmentTypes,
                      @Param("anyPartner") boolean anyPartner,
                      @Param("partnerIds") Collection<Long> partnerIds,
                      @Param("anyWarehouse") boolean anyWarehouse,
                      @Param("warehouseIds") Collection<Long> warehouseIds,
                      @Param("anySchool") boolean anySchool,
                      @Param("schoolCodes") Collection<String> schoolCodes,
                      @Param("anyDivision") boolean anyDivision,
                      @Param("divisions") Collection<String> divisions,
                      @Param("anyProduct") boolean anyProduct,
                      @Param("productIds") Collection<Long> productIds,
                      @Param("anyPair") boolean anyPair,
                      @Param("pairs") Collection<String> pairs,
                      @Param("anyChannel") boolean anyChannel,
                      @Param("unclassified") boolean unclassified,
                      @Param("channels") Collection<String> channels,
                      @Param("keyword") String keyword,
                      @Param("tradeSeq") Integer tradeSeq,
                      @Param("shippedOnly") boolean shippedOnly,
                      @Param("lectureDivisions") Collection<String> lectureDivisions,
                      @Param("includeCanceled") boolean includeCanceled);

    /**
     * 순매출 집계(상품별). 취소 제외. 회계구분·출고유형으로 매출/증정/교사용/반품 버킷 분해.
     * 순매출 = 매출 − 반품(수량·금액)은 서비스에서 계산. 근거: 레거시 순매출조회.
     * 반환 Object[]: [productId, code, name, saleQty, saleAmt, freeQty, freeAmt,
     *   teacherQty, teacherAmt, returnQty, returnAmt, tax, total]
     */
    @Query(value = """
            SELECT s.product_id, p.code, p.name,
              p.cat_code, p.cat_name,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.qty ELSE 0 END),0) AS sale_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.supply_amount ELSE 0 END),0) AS sale_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='FREE' AND s.shipment_type='GIFT' THEN s.qty ELSE 0 END),0) AS free_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='FREE' AND s.shipment_type='GIFT' THEN s.supply_amount ELSE 0 END),0) AS free_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='FREE' AND s.shipment_type='TEACHER_USE' THEN s.qty ELSE 0 END),0) AS teacher_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='FREE' AND s.shipment_type='TEACHER_USE' THEN s.supply_amount ELSE 0 END),0) AS teacher_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.qty ELSE 0 END),0) AS return_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.supply_amount ELSE 0 END),0) AS return_amt,
              COALESCE(SUM(s.tax),0) AS tax,
              COALESCE(SUM(s.total_amount),0) AS total
            FROM sales s JOIN products p ON p.id = s.product_id
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_date BETWEEN :fromDate AND :toDate
              AND (CAST(:partnerId AS SIGNED) IS NULL OR s.partner_id = :partnerId)
              -- 구분(매출/반품/교사용/증정용) · 매출유형(일반/위탁) 필터. null이면 전체.
              AND (CAST(:salesCategory AS CHAR) IS NULL OR s.sales_category = :salesCategory)
              -- ★출고유형 조건을 둘로 나눠 **각각** 건다. 하나로 합치면 '교사용 + 위탁매출' 같은
              --   모순 조합에서 한쪽이 조용히 무시돼, 담당자는 걸러진 줄 알고 교사용을 본다.
              --   따로 걸면 서로 충돌해 빈 결과가 되고, 그게 사실에 맞다.
              AND (CAST(:kindShipment AS CHAR) IS NULL OR s.shipment_type = :kindShipment)
              AND (CAST(:typeShipment AS CHAR) IS NULL OR s.shipment_type = :typeShipment)
              -- ★거래분류 **다중선택**(2026-10-08). 기존 summaryKind 는 한 번에 하나뿐이라
              --   "무상+반품만" 같은 조합을 화면이 전체를 받아 걸러 쓰고 있었다.
              AND (:anyCategory = TRUE OR s.sales_category IN (:categories))
            GROUP BY s.product_id, p.code, p.name, p.cat_code, p.cat_name
            ORDER BY p.cat_code, p.code
            """, nativeQuery = true)
    List<Object[]> salesSummary(@Param("anyCategory") boolean anyCategory,
                                @Param("categories") Collection<String> categories,
                                @Param("fromDate") LocalDate fromDate,
                                @Param("toDate") LocalDate toDate,
                                @Param("partnerId") Long partnerId,
                                @Param("salesCategory") String salesCategory,
                                @Param("kindShipment") String kindShipment,
                                @Param("typeShipment") String typeShipment);

    /**
     * 거래처별 채권 발생액(기간). 취소 제외. 반품은 채권 감소(−total).
     * receivableGen = Σ(RETURN이면 −total_amount, else +total_amount) = 매출+세액+유가교사용 − 반품.
     * <p>★교사용(증정 포함) 공급가액도 함께 낸다 — 무가라 <b>채권에는 안 들어가지만</b>
     * 24p 외상매출현황이 "이 거래처에 무가로 얼마가 나갔나"를 같이 보여 준다.
     * 채권 계산(receivableGen·balance)에는 절대 더하지 말 것.
     *
     * 반환 Object[]: [partnerId, receivableGen, saleAmt, returnAmt, tax, teacherAmt, teacherQty,
     *                 saleQty, returnQty].
     */
    @Query(value = """
            SELECT s.partner_id,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN -s.total_amount ELSE s.total_amount END),0) AS receivable_gen,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.supply_amount ELSE 0 END),0) AS sale_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.supply_amount ELSE 0 END),0) AS return_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN -s.tax ELSE s.tax END),0) AS tax_net,
              COALESCE(SUM(CASE WHEN s.sales_category='FREE' THEN s.supply_amount ELSE 0 END),0) AS teacher_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='FREE' THEN s.qty ELSE 0 END),0) AS teacher_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.qty ELSE 0 END),0) AS sale_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.qty ELSE 0 END),0) AS return_qty
            FROM sales s
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_date BETWEEN :fromDate AND :toDate
              AND (CAST(:partnerId AS SIGNED) IS NULL OR s.partner_id = :partnerId)
            GROUP BY s.partner_id
            """, nativeQuery = true)
    List<Object[]> receivableByPartner(@Param("fromDate") LocalDate fromDate,
                                       @Param("toDate") LocalDate toDate,
                                       @Param("partnerId") Long partnerId);

    /**
     * 외상매출현황의 <b>상품군(대분류)별 분해</b>. 근거: 재무팀 실파일
     * {@code 외상매출현황조회_20260630.xlsx} — 거래처 한 행이 24칸이고 그중 12칸이
     * 상품군별 수량·금액이다(수량/매출 × 교재·모의고사·기타·특강, 반품수량·반품금액 × 교재·기타).
     *
     * <p>★<b>칸을 고정하지 않고 대분류별로 내려준다.</b> 실파일은 4칸인데 우리 대분류는
     * 5종이다(모의고사·교재·기타고사·특강·기타). 기타고사를 기타에 합쳐 4칸으로 맞출 수도 있지만,
     * 그건 <b>발주처가 정할 문제</b>다 — 여기서 조용히 합치면 기타고사 매출이 어느 칸에
     * 섞여 들어갔는지 화면에서 알 수 없다. 대분류를 그대로 주고 묶는 건 화면에서 한다.
     *
     * <p>대분류가 없는 상품(세부구분 미지정)은 {@code ''}로 나온다 — 빼지 않는다.
     * 빼면 상품군 합이 전체 매출과 안 맞고, 그러면 담당자는 어느 쪽이 틀렸는지 모른다.
     *
     * <p>반환 Object[]: [partnerId, majorCategory, saleQty, saleAmt, returnQty, returnAmt].
     */
    @Query(value = """
            SELECT s.partner_id, COALESCE(d.major_category,'') AS major_category,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.qty ELSE 0 END),0) AS sale_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.supply_amount ELSE 0 END),0) AS sale_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.qty ELSE 0 END),0) AS return_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.supply_amount ELSE 0 END),0) AS return_amt
            FROM sales s
              JOIN products p ON p.id = s.product_id
              LEFT JOIN sales_divisions d ON d.code = p.sales_division
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_date BETWEEN :fromDate AND :toDate
              AND (CAST(:partnerId AS SIGNED) IS NULL OR s.partner_id = :partnerId)
            GROUP BY s.partner_id, COALESCE(d.major_category,'')
            """, nativeQuery = true)
    List<Object[]> receivableByPartnerAndCategory(@Param("fromDate") LocalDate fromDate,
                                                  @Param("toDate") LocalDate toDate,
                                                  @Param("partnerId") Long partnerId);

    /**
     * 콘텐츠구분 순매출: 상품별 매출/무상/반품 집계 + 콘텐츠구분 + 상품분류. 취소 제외.
     * 외부콘텐츠 이익은 서비스에서 매입원가(입고 unit_cost 평균)와 결합.
     * contentType: null=전체, 'SELF'/'EXTERNAL'.
     *
     * <p>★교사용은 <b>증정용을 포함</b>한다 — 정본 16p "교사용(증정용포함)".
     * 둘 다 회계구분 FREE라 한 칸으로 합친다(레거시처럼 무가/유가로 가르지 않는다).
     *
     * <p>★세액은 매출−반품으로 순액을 낸다. 매출 세액만 보면 반품된 건의 세액이 남아
     * 22p·38p 신고액과 어긋난다.
     *
     * <p>반환 Object[]: [productId, code, name, contentType, catCode, catName,
     * saleQty, saleAmt, saleTax, freeQty, freeAmt, returnQty, returnAmt, returnTax].
     */
    @Query(value = """
            SELECT s.product_id, p.code, p.name, p.content_type, p.cat_code, p.cat_name,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.qty ELSE 0 END),0) sale_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.supply_amount ELSE 0 END),0) sale_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.tax ELSE 0 END),0) sale_tax,
              COALESCE(SUM(CASE WHEN s.sales_category='FREE' THEN s.qty ELSE 0 END),0) free_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='FREE' THEN s.supply_amount ELSE 0 END),0) free_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.qty ELSE 0 END),0) return_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.supply_amount ELSE 0 END),0) return_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.tax ELSE 0 END),0) return_tax
            FROM sales s JOIN products p ON p.id = s.product_id
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_date BETWEEN :fromDate AND :toDate
              AND (CAST(:contentType AS CHAR) IS NULL OR p.content_type = :contentType)
              -- ★조회 조건(2026-10-08) — 거래처·세부구분·분류·도서.
              --   없던 동안 화면이 기간 전체를 받아 걸러 쓰고 있었다.
              AND (:anyPartner = TRUE OR s.partner_id IN (:partnerIds))
              AND (:anyDivision = TRUE OR COALESCE(p.sales_division, '') IN (:divisions))
              AND (:anyCat = TRUE OR p.cat_code IN (:catCodes))
              AND (:anyProduct = TRUE OR s.product_id IN (:productIds))
            GROUP BY s.product_id, p.code, p.name, p.content_type, p.cat_code, p.cat_name
            ORDER BY p.cat_code, p.code
            """, nativeQuery = true)
    List<Object[]> netSalesByProduct(@Param("anyPartner") boolean anyPartner,
                                     @Param("partnerIds") Collection<Long> partnerIds,
                                     @Param("anyDivision") boolean anyDivision,
                                     @Param("divisions") Collection<String> divisions,
                                     @Param("anyCat") boolean anyCat,
                                     @Param("catCodes") Collection<String> catCodes,
                                     @Param("anyProduct") boolean anyProduct,
                                     @Param("productIds") Collection<Long> productIds,
                                     @Param("fromDate") LocalDate fromDate,
                                     @Param("toDate") LocalDate toDate,
                                     @Param("contentType") String contentType);

    /**
     * 순매출 — <b>거래처별</b> 집계(현장 피드백 2026-10-14 B-2).
     *
     * <p>근거: 레거시 순매출조회 화면 오른쪽 위 「조회구분 ○거래처별 ○분류별」(검수피드백 15쪽).
     * 도서별 행만 주면 화면이 거래처로 다시 묶을 수 없다 — 한 도서가 여러 거래처에 걸려 있다.
     *
     * <p>★<b>금액 칸은 도서별과 똑같다.</b> 같은 화면의 두 보기인데 칸이 다르면
     * 담당자가 두 숫자를 비교하지 못한다. 묶는 축만 바뀐다.
     *
     * <p>‼️콘텐츠구분(content_type)은 <b>상품 속성</b>이라 거래처로 묶으면 섞인다.
     * 한 거래처가 자체교재와 매입교재를 함께 사므로 그 칸은 비운다 —
     * 섞인 값을 하나 골라 넣으면 매입액·이익이 틀린 기준으로 계산된다.
     *
     * <p>반환 Object[]: [partnerId, code, name, saleQty, saleAmt, saleTax,
     * freeQty, freeAmt, returnQty, returnAmt, returnTax].
     */
    @Query(value = """
            SELECT s.partner_id, pt.code, pt.name,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.qty ELSE 0 END),0) sale_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.supply_amount ELSE 0 END),0) sale_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE' THEN s.tax ELSE 0 END),0) sale_tax,
              COALESCE(SUM(CASE WHEN s.sales_category='FREE' THEN s.qty ELSE 0 END),0) free_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='FREE' THEN s.supply_amount ELSE 0 END),0) free_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.qty ELSE 0 END),0) return_qty,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.supply_amount ELSE 0 END),0) return_amt,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.tax ELSE 0 END),0) return_tax
            FROM sales s
              JOIN products p  ON p.id  = s.product_id
              JOIN partners pt ON pt.id = s.partner_id
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_date BETWEEN :fromDate AND :toDate
              AND (CAST(:contentType AS CHAR) IS NULL OR p.content_type = :contentType)
              AND (:anyPartner = TRUE OR s.partner_id IN (:partnerIds))
              AND (:anyDivision = TRUE OR COALESCE(p.sales_division, '') IN (:divisions))
              AND (:anyCat = TRUE OR p.cat_code IN (:catCodes))
              AND (:anyProduct = TRUE OR s.product_id IN (:productIds))
            GROUP BY s.partner_id, pt.code, pt.name
            ORDER BY pt.code
            """, nativeQuery = true)
    List<Object[]> netSalesByPartner(@Param("anyPartner") boolean anyPartner,
                                     @Param("partnerIds") Collection<Long> partnerIds,
                                     @Param("anyDivision") boolean anyDivision,
                                     @Param("divisions") Collection<String> divisions,
                                     @Param("anyCat") boolean anyCat,
                                     @Param("catCodes") Collection<String> catCodes,
                                     @Param("anyProduct") boolean anyProduct,
                                     @Param("productIds") Collection<Long> productIds,
                                     @Param("fromDate") LocalDate fromDate,
                                     @Param("toDate") LocalDate toDate,
                                     @Param("contentType") String contentType);

    /**
     * 그 미결의 <b>마지막 위탁정산 매출</b>. 확정매출분 반품(Case1)에서 정가·공급률 출처로 쓴다.
     *
     * <p>담당자가 반품하며 단가를 다시 적지 않아도 되게 하려는 것이다 —
     * "팔린 것을 무르는" 건이라 팔 때의 조건을 그대로 따르는 게 맞다.
     * 여러 번 나눠 정산했으면 <b>가장 최근 건</b>을 쓴다(조건이 바뀌었다면 최신이 현재 계약이다).
     */
    @Query("select s from Sale s where s.settlement.consignmentOut.id = :consignmentOutId "
            + "and s.canceled = false order by s.id desc limit 1")
    java.util.Optional<Sale> findLatestConsignSale(@Param("consignmentOutId") Long consignmentOutId);

    /**
     * 한 번의 등록(주문)에 속한 매출 줄 전부 — 주문 통째 수정·삭제가 쓴다(프론트 B-4).
     *
     * <p>취소분도 포함해 돌려준다. 이미 취소된 줄까지 보여야 담당자가 "무엇을 되돌리는지"
     * 알 수 있고, 삭제는 취소 여부와 무관하게 그 묶음 전체를 지운다.
     */
    @Query("select s from Sale s join fetch s.product "
            + "where s.entryNo = :entryNo order by s.entrySeq, s.id")
    List<Sale> findByEntryNo(@Param("entryNo") String entryNo);

    /**
     * 그 발송 건에 <b>아직 살아 있는 매출 줄</b>이 몇인가 — 빈 작업요청서를 걷어내려고 센다.
     *
     * <p>근거: 프론트 B-5 — 매출 줄을 모두 지워도 묶여 있던 작업요청서가 <b>도서 0줄로 남고</b>
     * 알림 배지에 그대로 잡힌다(빈 건 2개를 지우니 21→19). 물류는 쌀 것이 없는 지시를 본다.
     *
     * <p>키는 작업요청서 집계와 <b>같은 다섯 칸</b>이다(일자·거래처·학교·분류·주문).
     * 분류는 매출 행의 거래분류가 없으면 상품 세부구분으로 떨어진다 — 집계 쿼리와 같은 규칙이라
     * 여기만 다르면 "줄은 있는데 0으로 세는" 반대 사고가 난다.
     *
     * <p>삭제분은 {@code Sale} 의 {@code @SQLRestriction} 이 알아서 빼고, 취소분은 여기서 뺀다.
     */
    @Query("""
            select count(s) from Sale s join s.product p
             where s.canceled = false
               and s.salesDate = :tradeDate and s.partner.id = :partnerId
               and coalesce(s.schoolCode, '') = :schoolCode
               and coalesce(s.tradeClass, p.salesDivision, '') = :tradeClass
               and coalesce(s.entryNo, '') = :entryNo
            """)
    long countLiveForShipment(@Param("tradeDate") LocalDate tradeDate,
                              @Param("partnerId") Long partnerId,
                              @Param("schoolCode") String schoolCode,
                              @Param("tradeClass") String tradeClass,
                              @Param("entryNo") String entryNo);

    /**
     * 외상매출장 명세: 특정 거래처의 기간 내 매출 라인(취소 제외, 일자순).
     *
     * <p>‼️{@code join fetch s.product} — 도서명이 행마다 필요한데
     * {@code Sale.product} 가 LAZY 라, 안 걸면 <b>매출 행 수만큼 쿼리가 더 나간다</b>.
     */
    @Query("select s from Sale s join fetch s.product where s.partner.id = :partnerId "
            + "and s.canceled = false and s.salesDate between :from and :to "
            + "order by s.salesDate, s.id")
    List<Sale> findLedgerLines(@Param("partnerId") Long partnerId,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to);

    /**
     * 외상매출장 <b>전체 거래처</b> 명세: 여러 거래처의 기간 내 매출 라인을 한 번에.
     *
     * <p>★성능(10/30 프론트 D-3 — 전체 조회가 8~9초). 예전에는 거래처마다
     * {@link #findLedgerLines} 를 한 번씩 돌려 <b>거래처 수 × (쿼리 + 도서 N+1)</b> 이 났다.
     * 100곳 기준 8.5초, 200곳 19.9초(운영 실측). 한 번에 읽어 메모리에서 거래처별로 가른다.
     */
    @Query("select s from Sale s join fetch s.product where s.partner.id in :partnerIds "
            + "and s.canceled = false and s.salesDate between :from and :to "
            + "order by s.partner.id, s.salesDate, s.id")
    List<Sale> findLedgerLinesForPartners(@Param("partnerIds") List<Long> partnerIds,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    /**
     * 수익신고: 거래처×월 순매출/세액 집계. 취소 제외. 반품은 −(순매출·세액 감소).
     * taxFilter: 'ALL'=전체, 'FREE'=면세(tax=0), 'TAXABLE'=과세(tax≠0).
     * 반환 Object[]: [partnerId, partnerCode, partnerName, bizNo, bossName, yyyymm, count, netSupply, netTax].
     *
     * <p>거래처코드·사업자번호·대표자는 신고 표의 고정 컬럼이라 여기서 함께 읽는다
     * (행마다 거래처를 다시 조회하면 N+1이 된다).
     */
    @Query(value = """
            SELECT s.partner_id, pt.code, pt.name, pt.biz_no, pt.boss_name,
              DATE_FORMAT(s.sales_date,'%Y%m') AS ym,
              COUNT(*) AS cnt,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN -s.supply_amount ELSE s.supply_amount END),0) AS net_supply,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN -s.tax ELSE s.tax END),0) AS net_tax
            FROM sales s JOIN partners pt ON pt.id = s.partner_id
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_date BETWEEN :fromDate AND :toDate
              AND ( :taxFilter = 'ALL'
                    OR (:taxFilter = 'FREE' AND s.tax = 0)
                    OR (:taxFilter = 'TAXABLE' AND s.tax <> 0) )
            GROUP BY s.partner_id, pt.code, pt.name, pt.biz_no, pt.boss_name,
                     DATE_FORMAT(s.sales_date,'%Y%m')
            ORDER BY pt.name, ym
            """, nativeQuery = true)
    List<Object[]> revenueReport(@Param("fromDate") LocalDate fromDate,
                                 @Param("toDate") LocalDate toDate,
                                 @Param("taxFilter") String taxFilter);

    /**
     * 계산서신고: 거래처×상품×과세구분 집계(품목 라인). 취소 제외, 반품 차감.
     * tax_bucket: 'FREE'(면세, tax=0) / 'TAXABLE'(과세). 순매출 0인 품목은 제외.
     * 반환 Object[]: [partnerId, partnerName, productId, productName, taxBucket, supply, tax,
     * qty, priceKinds, unitPrice, catCode, catName, salesDivision].
     *
     * <p>★<b>과세구분 필터는 WHERE에서 건다</b>('ALL'이면 미적용).
     * 다 읽어 온 뒤 자바에서 거르면 계산서 장수·합계는 걸러진 것으로 나오는데
     * 집계(HAVING 순매출 0 제외)는 거르기 전 기준이라 <b>둘이 어긋날 수 있다</b>.
     * 세무 신고에 쓰는 숫자라 한 곳에서 자른다.
     */
    @Query(value = """
            SELECT s.partner_id, pt.name, s.product_id, p.name,
              CASE WHEN s.tax = 0 THEN 'FREE' ELSE 'TAXABLE' END AS tax_bucket,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN -s.supply_amount ELSE s.supply_amount END),0) AS supply,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN -s.tax ELSE s.tax END),0) AS tax,
              -- 홈택스 양식의 수량·단가 칸(9/27 항목 16). 반품은 음수로 상계한다 —
              -- 공급가액과 같은 부호 규칙이어야 단가 역산이 어긋나지 않는다.
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN -s.qty ELSE s.qty END),0) AS qty,
              -- 단가가 라인마다 다르면 하나로 정할 수 없다. 서로 다른 값의 가짓수를 함께 센다.
              COUNT(DISTINCT s.unit_price) AS price_kinds,
              MIN(s.unit_price) AS unit_price,
              -- ★품목의 분류 정보(2026-10-08 프론트 요청 [B]15). 신고 담당자가 품목을
              --   상품군으로 묶을 때 쓴다. ‼️'상품군 4종'의 정의는 아직 못 받았으므로
              --   우리가 묶지 않고 **묶을 수 있는 축을 함께 내려준다**(임의로 묶으면 신고가 틀어진다).
              p.cat_code, p.cat_name, p.sales_division
            FROM sales s
              JOIN partners pt ON pt.id = s.partner_id
              JOIN products p ON p.id = s.product_id
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_date BETWEEN :fromDate AND :toDate
              AND (CAST(:partnerId AS SIGNED) IS NULL OR s.partner_id = :partnerId)
              AND (:taxType = 'ALL'
                   OR CASE WHEN s.tax = 0 THEN 'FREE' ELSE 'TAXABLE' END = :taxType)
            GROUP BY s.partner_id, pt.name, s.product_id, p.name, p.cat_code, p.cat_name,
                     p.sales_division, CASE WHEN s.tax = 0 THEN 'FREE' ELSE 'TAXABLE' END
            HAVING COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN -s.supply_amount ELSE s.supply_amount END),0) <> 0
            ORDER BY pt.name, s.partner_id, tax_bucket, p.name
            """, nativeQuery = true)
    List<Object[]> taxInvoiceLines(@Param("fromDate") LocalDate fromDate,
                                   @Param("toDate") LocalDate toDate,
                                   @Param("partnerId") Long partnerId,
                                   @Param("taxType") String taxType);

    /**
     * <b>단독 상품</b>(수불부노출 Y)의 기간 출고수량 — 물류 작업비의 '인원' 자리에 들어간다.
     *
     * <p>근거: 발주처 확정(2026-10-23 #10) "Y(단독)→'인원' 자리에 매출프로그램 출고수량".
     *
     * <p>★<b>출고된 것만 센다</b> — 매출(SALE)과 무상(FREE)이다. 물류 작업은 물건이 나갈 때
     * 생기므로 둘 다 작업이 있었다. 반품(RETURN)은 빼는 게 아니라 <b>회수 물류비</b>라는
     * 다른 축에서 따로 센다(레거시도 출고/회수를 라디오로 갈라 놓았다).
     *
     * <p>‼️<b>병행(N) 상품은 제외한다.</b> 그쪽은 DSRE 신청 기준으로 이미 집계돼 온다 —
     * 여기서 또 세면 금액이 두 배가 된다(발주처가 콕 집어 경고한 지점).
     *
     * <p>반환 Object[]: [productId, productCode, productName, grade, partnerCode, partnerName, qty].
     */
    @Query("""
            select p.id, p.code, p.name, coalesce(p.grade, ''),
                   pt.code, pt.name, sum(s.qty)
              from Sale s join s.product p join s.partner pt
             where s.canceled = false
               and s.deletedAt is null
               and p.ledgerVisible = true
               and s.salesCategory <> com.daesung.sales.salestype.entity.SalesCategory.RETURN
               and s.salesDate between :from and :to
             group by p.id, p.code, p.name, coalesce(p.grade, ''), pt.code, pt.name
             having sum(s.qty) > 0
             order by p.code, pt.code
            """)
    List<Object[]> standaloneShippedQty(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 계산서·세금계산서 월별신고(38p): 월×발행유형으로 매출/반품/세액 집계. 취소 제외.
     * 발행유형 3종 — 앞 조건이 이긴다(배타):
     * <pre>
     *   UNISSUED    거래처 사업자번호가 비어 있음 → 계산서를 끊을 상대가 없다 = 미발행분
     *   INVOICE     tax = 0  → 계산서(면세)
     *   TAX_INVOICE tax ≠ 0  → 세금계산서(과세)
     * </pre>
     * ★UNISSUED를 <b>맨 앞에</b> 둔다. 사업자번호가 없으면 면세든 과세든 발행 자체를 안 하므로,
     * tax로 먼저 가르면 그 매출이 계산서/세금계산서 칸에 섞여 들어가 미발행분이 영영 0이 된다.
     * 근거: 재무팀 양식 '2026 월별 신고내역' <b>E4 셀 메모</b> —
     * "학원매출+외부판매 / 온라인몰 / (사업자번호가없는 거래처의 합)".
     *
     * <p>하이픈·공백만 든 값도 '없음'으로 본다({@code REGEXP_REPLACE}로 숫자만 남겨 비교) —
     * 실데이터에 {@code '-'} 같은 자리표시자가 섞여 있으면 단순 NULL 검사로는 안 걸린다.
     * 무상(FREE)은 매출/반품에서 제외(SALE·RETURN만 집계).
     * 반환 Object[]: [month(1~12), issueType, saleSupply, returnSupply, netTax].
     */
    @Query(value = """
            SELECT EXTRACT(MONTH FROM s.sales_date) AS mon,
              CASE WHEN REGEXP_REPLACE(COALESCE(pt.biz_no,''),'[^0-9]','') = '' THEN 'UNISSUED'
                   WHEN s.tax = 0 THEN 'INVOICE' ELSE 'TAX_INVOICE' END AS issue_type,
              COALESCE(SUM(CASE WHEN s.sales_category='SALE'   THEN s.supply_amount ELSE 0 END),0) AS sale_supply,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN s.supply_amount ELSE 0 END),0) AS return_supply,
              COALESCE(SUM(CASE WHEN s.sales_category='RETURN' THEN -s.tax ELSE s.tax END),0)      AS net_tax
            FROM sales s
              LEFT JOIN partners pt ON pt.id = s.partner_id
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND EXTRACT(YEAR FROM s.sales_date) = :year
            GROUP BY EXTRACT(MONTH FROM s.sales_date),
                     CASE WHEN REGEXP_REPLACE(COALESCE(pt.biz_no,''),'[^0-9]','') = '' THEN 'UNISSUED'
                          WHEN s.tax = 0 THEN 'INVOICE' ELSE 'TAX_INVOICE' END
            ORDER BY mon
            """, nativeQuery = true)
    List<Object[]> taxFilingByMonth(@Param("year") int year);

    /**
     * 계산서 10일 분기용: 특정 연·월의 반품(RETURN) 라인(취소 제외). 처리일 오름차순.
     * 반환 Object[]: [salesNo, partnerName, productName, salesDate, supplyAmount, tax].
     */
    @Query(value = """
            SELECT s.sales_no, pt.name, p.name, s.sales_date,
              COALESCE(s.supply_amount,0), COALESCE(s.tax,0)
            FROM sales s
              JOIN partners pt ON pt.id = s.partner_id
              JOIN products p  ON p.id = s.product_id
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_category = 'RETURN'
              AND EXTRACT(YEAR FROM s.sales_date) = :year
              AND EXTRACT(MONTH FROM s.sales_date) = :month
            ORDER BY s.sales_date, s.sales_no
            """, nativeQuery = true)
    List<Object[]> returnsInMonth(@Param("year") int year, @Param("month") int month);

    /**
     * 월별매출액명세서(37p): 대분류×분류×상품별 성적처리/비처리 인원·금액 + 과세·부가세. 매출(SALE)만, 취소 제외.
     * 성적처리 = proc_type='GRADED', 그 외(비처리/미지정)는 UNGRADED 버킷. 인원=qty, 금액=supply_amount.
     * ★대분류 = 세부구분 마스터({@code sales_divisions.major_category})에서 파생. 발주처 회신 2026-08-20.
     * 예전엔 {@code LEFT(cat_code,1)}이라 화면에 'H'·'M' 같은 글자가 나왔고, 분류코드 체계가 없는
     * 신규 데이터에서는 대분류가 무의미했다. LEFT JOIN인 이유는 세부구분이 없는 상품의 매출도
     * 빠지면 안 되기 때문(대분류 null → 서비스에서 '미분류'로 모인다).
     * 대분류 순서는 서비스에서 정한다 — SQL로 정렬하면 enum 이름 알파벳순이라 화면 순서와 어긋난다.
     *
     * <p>반환 Object[]:
     *   [major, catCode, catName, productId, code, name, gradedQty, gradedAmt, ungradedQty, ungradedAmt, taxableAmt, vat].
     */
    @Query(value = """
            SELECT d.major_category AS major, p.cat_code, p.cat_name, s.product_id, p.code, p.name,
              COALESCE(SUM(CASE WHEN s.proc_type='GRADED' THEN s.qty ELSE 0 END),0)            AS graded_qty,
              COALESCE(SUM(CASE WHEN s.proc_type='GRADED' THEN s.supply_amount ELSE 0 END),0)  AS graded_amt,
              COALESCE(SUM(CASE WHEN s.proc_type='GRADED' THEN 0 ELSE s.qty END),0)            AS ungraded_qty,
              COALESCE(SUM(CASE WHEN s.proc_type='GRADED' THEN 0 ELSE s.supply_amount END),0)  AS ungraded_amt,
              COALESCE(SUM(CASE WHEN s.tax <> 0 THEN s.supply_amount ELSE 0 END),0)            AS taxable_amt,
              COALESCE(SUM(s.tax),0)                                                           AS vat
            FROM sales s JOIN products p ON p.id = s.product_id
              LEFT JOIN sales_divisions d ON d.code = p.sales_division
            WHERE s.deleted_at IS NULL AND s.canceled = false
              AND s.sales_category = 'SALE'
              AND EXTRACT(YEAR FROM s.sales_date) = :year
              AND EXTRACT(MONTH FROM s.sales_date) = :month
            GROUP BY d.major_category, p.cat_code, p.cat_name, s.product_id, p.code, p.name
            ORDER BY d.major_category, p.cat_code, p.code
            """, nativeQuery = true)
    List<Object[]> monthlyStatementAgg(@Param("year") int year, @Param("month") int month);

    /**
     * 회차별 작업현황(구 IC회차별작업현황). 분류×도서×회차 단위로 포장구분별 수량을 펼친다.
     * 근거: 레거시 IC회차별작업현황.vb — {@code group by catCode, bookCode, bookReqSeq, packType} 후
     * 포장구분을 열로 피벗한 구조를 한 번에 집계한다.
     * 레거시의 {@code bookReqSeq<>0} 조건(회차 없는 건 제외)을 그대로 옮겼다.
     */
    @Query(value = """
            SELECT p.cat_code, MAX(p.cat_name), p.code, MAX(p.name), s.book_round,
                   COALESCE(SUM(CASE WHEN s.pack_type = 'INDIVIDUAL_1' THEN s.qty END), 0) AS indiv1,
                   COALESCE(SUM(CASE WHEN s.pack_type = 'INDIVIDUAL_2' THEN s.qty END), 0) AS indiv2,
                   COALESCE(SUM(CASE WHEN s.pack_type = 'CLASS_BUNDLE' THEN s.qty END), 0) AS cls,
                   COALESCE(SUM(s.qty), 0) AS total
              FROM sales s JOIN products p ON p.id = s.product_id
             WHERE s.deleted_at IS NULL AND s.canceled = false
               AND s.sales_date BETWEEN :fromDate AND :toDate
               AND s.book_round IS NOT NULL AND s.book_round <> 0
               AND (:catCode IS NULL OR p.cat_code = :catCode)
             GROUP BY p.cat_code, p.code, s.book_round
             ORDER BY p.cat_code, p.code, s.book_round
            """, nativeQuery = true)
    List<Object[]> roundWorkStatus(@Param("fromDate") java.time.LocalDate fromDate,
                                   @Param("toDate") java.time.LocalDate toDate,
                                   @Param("catCode") String catCode);

    /**
     * 발송 단위(일자·거래처·학교)의 상품군별 수량. 작업결과 화면의 '교재'·'IC' 컬럼.
     * 근거: 작업결과.vb:115 — 레거시도 sendData에 salesData를 조인해 tradeClass별로 합산한다.
     * 취소·반품은 제외한다(내보내는 작업이 아니다).
     *
     * <p>★거래분류는 <b>매출 행의 값</b>({@code s.tradeClass})이다 — 상품의 세부구분이 아니다.
     * ‼️예전엔 {@code p.salesDivision} 으로 맞췄는데, 발송 한 건에 여러 세부구분 상품이
     * 섞여 들어가 거의 안 붙었다(2026년 2,219건 중 341건만 매칭.
     * 발송 '기타고사'↔상품 'D모의고사' 65,081건, 발송 '대성교재'↔상품 '기타고사' 41,457건.
     * '대성교재'라는 세부구분은 상품에 아예 없다). 바꾼 뒤 2,215/2,221 이 붙는다.
     * {@code coalesce} 로 상품 세부구분을 뒤에 두는 이유는 <b>이 칸이 생기기 전에 등록된
     * 신규 매출</b> 때문이다 — 그쪽은 발송도 상품 세부구분으로 만들어져 있어 짝이 맞는다.
     */
    @Query("""
            select s.salesDate as tradeDate, s.partner.id as partnerId,
                   coalesce(s.schoolCode, '') as schoolCode,
                   coalesce(s.tradeClass, p.salesDivision, '') as tradeClass,
                   coalesce(s.entryNo, '') as entryNo,
                   sum(s.qty) as qty
              from Sale s join s.product p
             where s.canceled = false
               and s.salesCategory <> com.daesung.sales.salestype.entity.SalesCategory.RETURN
               and s.salesDate between :from and :to
             group by s.salesDate, s.partner.id, coalesce(s.schoolCode, ''), coalesce(s.tradeClass, p.salesDivision, ''), coalesce(s.entryNo, '')
            """)
    List<ShipmentQtyAgg> shipmentQty(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 발송 단위의 도서별 상세. 작업요청서가 "무엇을 몇 개 포장할지" 지시하는 목록이다.
     * 근거: 작업요청서.vb 조회 SQL(도서코드·도서명·수량·정가·공급률·금액 컬럼).
     */
    @Query("""
            select s.salesDate as tradeDate, s.partner.id as partnerId,
                   coalesce(s.schoolCode, '') as schoolCode,
                   coalesce(s.tradeClass, p.salesDivision, '') as tradeClass,
                   coalesce(s.entryNo, '') as entryNo,
                   p.catCode as catCode, p.code as productCode, p.name as productName,
                   s.bookRound as bookRound,
                   s.unitPrice as unitPrice, s.supplyRate as supplyRate,
                   sum(s.qty) as qty, sum(s.supplyAmount) as amount
              from Sale s join s.product p
             where s.canceled = false
               and s.salesCategory <> com.daesung.sales.salestype.entity.SalesCategory.RETURN
               and s.salesDate between :from and :to
             group by s.salesDate, s.partner.id, coalesce(s.schoolCode, ''), coalesce(s.tradeClass, p.salesDivision, ''), coalesce(s.entryNo, ''),
                      p.catCode, p.code, p.name, s.bookRound, s.unitPrice, s.supplyRate
             order by p.catCode, p.code
            """)
    List<WorkOrderLineAgg> workOrderLines(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 발송 건(거래일자·거래처·학교·분류)별 <b>신청번호(reqCd)</b> 목록.
     *
     * <p>근거: 프론트 실측 지적(2026-10-01) — 작업요청서에서 발송취소를 부르려면 신청번호가 필요한데
     * 행 어디에도 없어 <b>"주문 조회에서 하세요"로 안내만</b> 하고 있었다.
     *
     * <p>★<b>한 발송 건에 신청이 여럿일 수 있다.</b> 같은 날 같은 학교로 두 주문이 들어오면
     * 물류는 한 번에 싸서 보낸다 — 그래서 목록이다. 하나만 돌려주면 나머지는 되돌릴 수 없다.
     *
     * <p>취소된 매출은 뺀다. 되돌린 건의 신청번호를 발송취소 대상으로 주면 안 된다.
     */
    @Query("""
            select s.salesDate as tradeDate, s.partner.id as partnerId,
                   coalesce(s.schoolCode, '') as schoolCode,
                   coalesce(s.tradeClass, p.salesDivision, '') as tradeClass,
                   coalesce(s.entryNo, '') as entryNo,
                   s.reqCd as reqCd
              from Sale s join s.product p
             where s.canceled = false and s.reqCd is not null
               and s.salesDate between :from and :to
             group by s.salesDate, s.partner.id, coalesce(s.schoolCode, ''),
                      coalesce(s.tradeClass, p.salesDivision, ''), coalesce(s.entryNo, ''), s.reqCd
             order by s.reqCd
            """)
    List<WorkOrderReqCd> workOrderReqCds(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 발송 건 ↔ 신청번호 한 줄. */
    interface WorkOrderReqCd {
        LocalDate getTradeDate();

        Long getPartnerId();

        /** 주문 식별자(매출등록번호). 옛 데이터는 빈 문자열. */
        String getEntryNo();

        String getSchoolCode();

        String getTradeClass();

        Integer getReqCd();
    }

    /**
     * 응시현황(연도별) 원자료 — 거래처×월 수량·금액. 근거: 레거시 응시현황.vb:330~.
     *
     * <p>레거시는 {@code salesData}를 학교(schCode)로 묶어 월별 bookCnt/totalAmt를 만들고
     * 거래처로 rollup한다. 반품은 레거시가 <b>음수로 저장</b>해 SUM만으로 순수량이 나오는데,
     * 우리는 RETURN을 양수+구분으로 저장하므로 <b>여기서 부호를 뒤집어</b> 같은 값이 되게 한다.
     * 취소 건은 양쪽 모두 제외한다.
     *
     * <p>필터(학년·상품구분)는 레거시 queryWhere와 같은 축이다(bookData.grade / bookData.type).
     */
    @Query("""
            select p.id as partnerId, p.code as partnerCode, p.name as partnerName,
                   p.cityName as cityName,
                   month(s.salesDate) as month,
                   sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.RETURN
                            then -s.qty else s.qty end) as qty,
                   sum(case when s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.RETURN
                            then -s.totalAmount else s.totalAmount end) as amount
              from Sale s join s.partner p join s.product b
             where s.canceled = false
               and year(s.salesDate) = :year
               and (:grade is null or b.grade = :grade)
               and (:productType is null or b.productType = :productType)
               and (:keyword is null or p.code like concat('%', :keyword, '%')
                                     or p.name like concat('%', :keyword, '%'))
             group by p.id, p.code, p.name, p.cityName, month(s.salesDate)
             order by p.code, month(s.salesDate)
            """)
    List<AttendanceAgg> attendanceYearly(@Param("year") int year,
                                         @Param("grade") String grade,
                                         @Param("productType") String productType,
                                         @Param("keyword") String keyword);

    /**
     * 응시현황(기간별, 18p) 원자료. 지역·거래처·학교·학년·연월·처리구분별 인원(수량).
     *
     * <p><b>더프 모의고사만</b> 집계한다. 판별은 레거시와 같은 분류코드 패턴이다 —
     * {@code catCode LIKE 'M%A%' OR 'M%B%' OR 'M%C%'}. 더프는 모의고사 전체가 아니라
     * 그중 A·B·C 계열이라, 대분류(모의고사)로 거르면 범위가 넓어진다(24p 더프모와 같은 기준).
     *
     * <p>★레거시({@code 고사별처리인원.vb})는 여기에 <b>연도를 박아</b> 걸렀다:
     * {@code catCode in ('M2022A','M2022B')}. 그래서 해마다 SQL을 새로 써야 했고,
     * 2022년 이후 분기를 아무도 안 써서 화면이 "2022년까지만 조회 가능"으로 멈췄다.
     * <b>패턴은 그대로 쓰되 연도만 빼면</b> 그 문제가 사라진다 —
     * 연도 자리(2~5)는 어차피 숫자라 {@code 'M%A%'}의 A는 접미부에서만 걸린다.
     *
     * <p>처리/비처리는 {@code proc_type}으로 가른다(미지정=비처리, 37p와 같은 규칙).
     * 레거시는 이걸 도서명 문자열({@code bookName like '%11월%고3%1영역%'})로 긁었는데,
     * 도서 명명 규칙이 해마다 바뀌는 것이 하드코딩의 근본 원인이었다.
     *
     * <p>매출(SALE)만 — 응시 인원이라 증정·반품은 대상이 아니다. 취소 제외.
     */
    @Query("""
            select p.region as region, p.code as partnerCode, p.name as partnerName,
                   coalesce(s.schoolCode, '') as schoolCode,
                   coalesce(s.schoolName, '') as schoolName,
                   coalesce(b.grade, '') as grade,
                   year(s.salesDate) as year, month(s.salesDate) as month,
                   sum(case when s.procType = com.daesung.sales.sale.entity.ProcType.GRADED
                            then s.qty else 0 end) as gradedQty,
                   sum(case when s.procType = com.daesung.sales.sale.entity.ProcType.GRADED
                            then 0 else s.qty end) as ungradedQty
              from Sale s join s.partner p join s.product b
                   join SalesDivision d on d.code = b.salesDivision
             where s.canceled = false
               and s.salesCategory = com.daesung.sales.salestype.entity.SalesCategory.SALE
               and b.catCode like 'M%'
               and (b.catCode like 'M%A%' or b.catCode like 'M%B%' or b.catCode like 'M%C%')
               and s.salesDate between :fromDate and :toDate
               and (:anyGrade = true or b.grade in :grades)
               and (:partnerId is null or p.id = :partnerId)
             group by p.region, p.code, p.name, s.schoolCode, s.schoolName, b.grade,
                      year(s.salesDate), month(s.salesDate)
             order by p.region, p.code, s.schoolCode, b.grade
            """)
    // ‼️더프 판별은 **대분류로 바꿀 수 없다**(2026-09-17 시도했다가 되돌림).
    //   대분류 MOCK_EXAM 으로 바꿔 봤더니 더프가 아닌 모의고사 계열(M…D)까지 섞여 들어왔다 —
    //   AttendancePeriodIntegrationTest 가 그 케이스를 일부러 넣어 두고 있어 바로 깨졌다.
    //   더프는 분류코드 M+A/B/C 계열이라는 레거시 규칙이 유일한 판별 기준이다.
    //   (분류코드 형식은 [영문1][연도4][영문·숫자1~3]로 이미 검증되고 있어 자유 문자열은 아니다.)
    List<AttendancePeriodAgg> attendancePeriod(@Param("fromDate") LocalDate fromDate,
                                               @Param("toDate") LocalDate toDate,
                                               @Param("anyGrade") boolean anyGrade,
                                               @Param("grades") java.util.Collection<String> grades,
                                               @Param("partnerId") Long partnerId);

    /**
     * 발송 단위(일자·거래처·학교·분류)의 출고 창고. 작업결과의 '출고창고' 컬럼·필터.
     * 한 발송 건을 여러 번에 나눠 등록하면 창고가 섞일 수 있어 창고별로 행이 나온다(화면에서 합쳐 표기).
     * 창고가 기록되기 전(V40 이전) 매출은 warehouse가 null이라 제외된다.
     */
    @Query("""
            select s.salesDate as tradeDate, s.partner.id as partnerId,
                   coalesce(s.schoolCode, '') as schoolCode,
                   coalesce(s.tradeClass, p.salesDivision, '') as tradeClass,
                   coalesce(s.entryNo, '') as entryNo,
                   w.id as warehouseId, w.name as warehouseName, w.type as warehouseType
              from Sale s join s.product p join s.warehouse w
             where s.canceled = false
               and s.salesCategory <> com.daesung.sales.salestype.entity.SalesCategory.RETURN
               and s.salesDate between :from and :to
             group by s.salesDate, s.partner.id, coalesce(s.schoolCode, ''),
                      coalesce(s.tradeClass, p.salesDivision, ''), coalesce(s.entryNo, ''),
                      w.id, w.name, w.type
            """)
    List<ShipmentWarehouseAgg> shipmentWarehouses(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
