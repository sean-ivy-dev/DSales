package com.daesung.sales.logistics.repository;

import com.daesung.sales.logistics.entity.DeliveryType;
import com.daesung.sales.logistics.entity.Shipment;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    /**
     * 그 <b>신청번호(DSRE reqCd)</b>로 세운 매출이 묶인 발송 건들.
     *
     * <p>근거: 프론트 실측 지적(2026-10-01, 2026-10-08 [A]5) — 「발송취소가 실제로 상태를
     * 되돌리지 못한다」. 원인은 <b>축이 둘</b>이었다. 발송취소는 DSRE {@code STATE} 를 D→W 로
     * 되돌리는데, 작업요청서 화면의 '발송완료'는 <b>우리 {@code shipment.sent_date}</b> 로
     * 판정한다 — DSRE 는 돌아갔는데 우리 화면은 계속 발송완료로 보였다.
     *
     * <p>발송 건은 (거래일자·거래처·학교·분류)로 묶이므로, 신청번호에서 그 키를 거쳐 되짚는다.
     * ★취소된 매출은 제외한다 — 되돌린 건의 신청번호로 남의 발송 건을 내리면 안 된다.
     */
    @Query("""
            select distinct sh from Shipment sh
             where exists (select 1 from Sale sa join sa.product p
                            where sa.reqCd = :reqCd and sa.canceled = false
                              and sa.salesDate = sh.tradeDate
                              and sa.partner.id = sh.partner.id
                              and coalesce(sa.schoolCode, '') = coalesce(sh.schoolCode, '')
                              and coalesce(p.salesDivision, '') = coalesce(sh.tradeClass, ''))
            """)
    List<Shipment> findByReqCd(@Param("reqCd") Integer reqCd);

    /**
     * 발송 건에 묶인 신청번호들 — {@link #findByReqCd}의 역방향(작업요청서 {@code reqCds} 와 같은 기준).
     * 취소된 매출은 뺀다.
     *
     * <p>★<b>연결은 직접 외래키가 아니라 4칸 복합키다</b>(거래일자·거래처·학교코드·거래분류).
     * {@code shipment} 에는 {@code req_cd} 컬럼이 없고, {@code sales.req_cd} 를 경유한다.
     * 그래서 결과가 <b>단건이 아니라 목록</b>이다 — 같은 날 같은 거래처·학교·분류로 두 신청이
     * 들어오면 둘 다 붙는다.
     *
     * <p>‼️<b>{@code sales.req_cd} 는 DSRE 신청을 거친 매출만 갖는다.</b>
     * {@code Sale.linkOrder} 호출부가 셋뿐이다 — 매출일괄등록 · 더프 일괄등록 ·
     * DSRE 신규 주문 등록. 수기 매출등록 · 엑셀 업로드 · 반품입고 · 위탁정산 ·
     * <b>특약점 새 주문</b>은 전부 {@code NULL} 이다.
     *
     * <p>★<b>그래서 {@code shipment} 에 {@code req_cd} 를 추가하지 않는다</b>(10/26 §10-2 숙제의 답).
     * 10/27 명세 §1 이 "송장·거래명세서 표시(§4·§5)는 <b>새 탭 주문에 적용</b>한다,
     * 기존 모의고사 탭에는 적용하지 않는다"로 적용 대상을 바꿨고, 새 탭 주문은
     * "<b>매출 주문으로 저장</b>"이라 {@code tbl_request_info} 에 들어가지 않는다 —
     * 담을 값이 없다. 사이트용 송장·거래명세서는 <b>거래처(서버 세션)+기간</b>으로
     * 우리 주문({@code entry_no})을 직접 읽는다.
     * 조사 상세: {@code docs-local/1028-docs/조사_REQCD-SHIPMENT_연결경로.md}.
     */
    @Query("""
            select distinct sa.reqCd from Sale sa join sa.product p, Shipment sh
             where sh.id = :shipmentId
               and sa.reqCd is not null and sa.canceled = false
               and sa.salesDate = sh.tradeDate
               and sa.partner.id = sh.partner.id
               and coalesce(sa.schoolCode, '') = coalesce(sh.schoolCode, '')
               and coalesce(p.salesDivision, '') = coalesce(sh.tradeClass, '')
             order by sa.reqCd
            """)
    List<Integer> findReqCds(@Param("shipmentId") Long shipmentId);

    /**
     * 여러 발송 건의 신청번호를 <b>한 번에</b>. 반환 {@code Object[]}: [shipmentId, reqCd].
     *
     * <p>★목록 화면이 쓴다. {@link #findReqCds} 를 행마다 부르면 N+1 이고,
     * 그 뒤에 DSRE 왕복까지 붙으면 한 페이지에 수십 번이 된다.
     */
    @Query("""
            select distinct sh.id, sa.reqCd from Sale sa join sa.product p, Shipment sh
             where sh.id in :shipmentIds
               and sa.reqCd is not null and sa.canceled = false
               and sa.salesDate = sh.tradeDate
               and sa.partner.id = sh.partner.id
               and coalesce(sa.schoolCode, '') = coalesce(sh.schoolCode, '')
               and coalesce(p.salesDivision, '') = coalesce(sh.tradeClass, '')
            """)
    List<Object[]> findReqCdsOf(@Param("shipmentIds") java.util.Collection<Long> shipmentIds);

    /** 한 주문(등록 묶음)이 만든 발송 건들. 주문 수정 뒤 빈 것을 걷을 때 쓴다. */
    @Query("select s from Shipment s where s.entryNo = :entryNo")
    List<Shipment> findByEntryNo(@Param("entryNo") String entryNo);

    /**
     * 발송 묶음 키 — (일자·거래처·학교·분류·<b>주문</b>).
     *
     * <p>★{@code entryNo}(주문 식별자 = 매출등록번호)가 키에 들어간다. 10/27 명세 §8-2
     * "출고 등록을 하면 <b>주문 단위</b>로 작업요청서에 보이게 한다" — 즉
     * <b>등록이 다르면 작업요청서도 갈려야</b> 한다. 예전엔 이 값이 없어 같은 날 같은
     * 학교로 다섯 번 주문해도 한 건에 합쳐졌고, 거래순번도 1개뿐이었다(프론트 B-2② 지적).
     *
     * <p>{@code entryNo} 가 null 이면 예전처럼 (일자·거래처·학교·분류)로만 묶인다 —
     * 위탁출고가 그 경우다(출고번호가 품목마다 생겨 주문 식별자로 못 쓴다).
     */
    @Query("""
            select s from Shipment s
             where s.tradeDate = :tradeDate and s.partner.id = :partnerId
               and ((:schoolCode is null and s.schoolCode is null) or s.schoolCode = :schoolCode)
               and ((:tradeClass is null and s.tradeClass is null) or s.tradeClass = :tradeClass)
               and ((:entryNo is null and s.entryNo is null) or s.entryNo = :entryNo)
            """)
    Optional<Shipment> findKey(@Param("tradeDate") LocalDate tradeDate,
                               @Param("partnerId") Long partnerId,
                               @Param("schoolCode") String schoolCode,
                               @Param("tradeClass") String tradeClass,
                               @Param("entryNo") String entryNo);

    /**
     * 기간·조건 조회. printed: true=출력분만, false=미출력분만, null=전체
     * (레거시 작업요청서의 '출력 안 된 건만' 필터에 대응).
     * acknowledged 도 같은 규칙이다 — 확인은 출력 다음 단계라 "출력됐는데 아직 확인 안 된 건"을
     * {@code printed=true &amp; acknowledged=false} 로 추릴 수 있어야 한다.
     */
    @Query("""
            select s from Shipment s join fetch s.partner p
             where s.tradeDate between :from and :to
               and (:tradeClass is null or s.tradeClass = :tradeClass)
               and (:partnerId is null or p.id = :partnerId)
               and (:printed is null
                    or (:printed = true  and s.printedAt is not null)
                    or (:printed = false and s.printedAt is null))
               and (:acknowledged is null
                    or (:acknowledged = true  and s.acknowledgedAt is not null)
                    or (:acknowledged = false and s.acknowledgedAt is null))
               and (:deliveryType is null or s.deliveryType = :deliveryType)
             order by s.tradeDate desc, p.code, s.schoolCode
            """)
    List<Shipment> search(@Param("from") LocalDate from, @Param("to") LocalDate to,
                          @Param("tradeClass") String tradeClass,
                          @Param("partnerId") Long partnerId,
                          @Param("printed") Boolean printed,
                          @Param("acknowledged") Boolean acknowledged,
                          @Param("deliveryType") DeliveryType deliveryType);

    /**
     * 미확인(미출력) 발송 건수. 대시보드 '미확인 출고요청' 카드와 메뉴 배지가 쓴다.
     *
     * <p>판정은 {@link #search} 의 {@code printed=false} 와 <b>같다</b>(printedAt is null).
     * 따로 세면 카드에는 3건인데 화면을 열면 5건인 상황이 생긴다.
     */
    @Query("""
            select count(s) from Shipment s
             where s.tradeDate between :from and :to
               and s.printedAt is null
            """)
    int countUnprinted(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 작업요청서 조회 — <b>삭제된 건까지</b>(9/27 회의 항목 20 ②).
     *
     * <p>★{@code @SQLRestriction} 때문에 JPQL 로는 삭제분을 볼 수 없다. 네이티브로 우회한다.
     *
     * <p>★<b>왜 삭제분을 보여줘야 하는가</b> — 레거시가 그렇게 한다.
     * 목록에서 지우지 않고 <b>취소선+회색</b>으로 남긴다({@code 작업요청서.vb:768}).
     * 숨겨 버리면 "취소된 건"과 "원래 없던 건"이 구분되지 않아, 담당자가 같은 발송을 다시 만든다.
     * 대신 출력·확인·발송 대상에서는 빠진다(레거시 377·890·1016행도 {@code Continue For}).
     */
    @Query(value = """
            SELECT s.* FROM shipment s
              JOIN partners p ON p.id = s.partner_id
             WHERE s.trade_date BETWEEN :from AND :to
               AND (:tradeClass IS NULL OR s.trade_class = :tradeClass)
               AND (CAST(:partnerId AS SIGNED) IS NULL OR p.id = :partnerId)
               AND (CAST(:printed AS SIGNED) IS NULL
                    OR (:printed = 1 AND s.printed_at IS NOT NULL)
                    OR (:printed = 0 AND s.printed_at IS NULL))
               AND (CAST(:acknowledged AS SIGNED) IS NULL
                    OR (:acknowledged = 1 AND s.acknowledged_at IS NOT NULL)
                    OR (:acknowledged = 0 AND s.acknowledged_at IS NULL))
               AND (:deliveryType IS NULL OR s.delivery_type = :deliveryType)
             ORDER BY s.trade_date DESC, p.code, s.school_code
            """, nativeQuery = true)
    List<Shipment> findAllIncludingDeleted(@Param("from") LocalDate from,
                                           @Param("to") LocalDate to,
                                           @Param("tradeClass") String tradeClass,
                                           @Param("partnerId") Long partnerId,
                                           @Param("printed") Integer printed,
                                           @Param("acknowledged") Integer acknowledged,
                                           @Param("deliveryType") String deliveryType);

    /**
     * 위와 같은 조건의 <b>페이지</b>. 작업요청서 목록이 쓴다.
     *
     * <p>‼️<b>왜 페이징이 필요한가</b> — 이관 뒤 발송이 48,755건이 되자 넓은 기간(2020~2035)
     * 조회가 <b>17,866건 / 응답 11.6MB / 27.6초</b>가 됐고, 배포 서버(힙 478MB)에서
     * <b>OutOfMemoryError 로 앱이 죽었다</b>(2026-10-19 실측).
     * 화면이 기간을 비우면 그 넓은 기간으로 부른다.
     *
     * <p>★전량을 못 읽게 막는 것이 요점이다. 줄(도서 목록)은 <b>이 페이지의 발송 건</b>에
     * 대해서만 모으므로, 페이지가 작으면 줄 조회 범위도 같이 줄어든다.
     */
    @Query(value = """
            SELECT s.* FROM shipment s
              JOIN partners p ON p.id = s.partner_id
             WHERE s.trade_date BETWEEN :from AND :to
               AND (:tradeClass IS NULL OR s.trade_class = :tradeClass)
               AND (CAST(:partnerId AS SIGNED) IS NULL OR p.id = :partnerId)
               AND (CAST(:printed AS SIGNED) IS NULL
                    OR (:printed = 1 AND s.printed_at IS NOT NULL)
                    OR (:printed = 0 AND s.printed_at IS NULL))
               AND (CAST(:acknowledged AS SIGNED) IS NULL
                    OR (:acknowledged = 1 AND s.acknowledged_at IS NOT NULL)
                    OR (:acknowledged = 0 AND s.acknowledged_at IS NULL))
               AND (:deliveryType IS NULL OR s.delivery_type = :deliveryType)
             ORDER BY s.trade_date DESC, p.code, s.school_code, s.id
            """,
            countQuery = """
            SELECT COUNT(*) FROM shipment s
              JOIN partners p ON p.id = s.partner_id
             WHERE s.trade_date BETWEEN :from AND :to
               AND (:tradeClass IS NULL OR s.trade_class = :tradeClass)
               AND (CAST(:partnerId AS SIGNED) IS NULL OR p.id = :partnerId)
               AND (CAST(:printed AS SIGNED) IS NULL
                    OR (:printed = 1 AND s.printed_at IS NOT NULL)
                    OR (:printed = 0 AND s.printed_at IS NULL))
               AND (CAST(:acknowledged AS SIGNED) IS NULL
                    OR (:acknowledged = 1 AND s.acknowledged_at IS NOT NULL)
                    OR (:acknowledged = 0 AND s.acknowledged_at IS NULL))
               AND (:deliveryType IS NULL OR s.delivery_type = :deliveryType)
            """, nativeQuery = true)
    org.springframework.data.domain.Page<Shipment> findPageIncludingDeleted(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("tradeClass") String tradeClass,
            @Param("partnerId") Long partnerId,
            @Param("printed") Integer printed,
            @Param("acknowledged") Integer acknowledged,
            @Param("deliveryType") String deliveryType,
            org.springframework.data.domain.Pageable pageable);

    /** 삭제된 건 포함 단건 조회 — 삭제 대상을 찾을 때 쓴다(@SQLRestriction 우회). */
    @Query(value = "SELECT * FROM shipment WHERE id = :id", nativeQuery = true)
    Optional<Shipment> findByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 진행상태 <b>랭크</b> 식(SQL). {@code OrderProgress.rankOf} 와 <b>같은 값</b>을 내야 한다.
     *
     * <p>★{@link com.daesung.sales.logistics.entity.Shipment#progressState()} 의 판정 순서를
     * 그대로 옮긴 것이다 — 삭제 &gt; 발송 &gt; 출력 &gt; 접수단계 &gt; 상품준비중.
     * 주문 목록을 <b>DB에서 걸러 페이징</b>하려면 상태를 SQL 로 알아야 해서 한 번 더 적는다.
     * ‼️어긋나면 "상태로 걸렀는데 다른 상태가 나온다"가 된다 — 테스트가 전 상태를 맞춰 본다.
     */
    String ORDER_STATE_RANK = """
            CASE WHEN s.deleted_at IS NOT NULL      THEN 99
                 WHEN s.sent_date  IS NOT NULL      THEN 5
                 WHEN s.printed_at IS NOT NULL      THEN 4
                 WHEN s.intake_state = 'INSPECTING' THEN 2
                 WHEN s.intake_state = 'RECEIVED'   THEN 1
                 ELSE 3 END
            """;

    /** 주문 식별 키 식(SQL). {@code OrderProgress.orderKey} 와 같아야 한다. */
    String ORDER_KEY = "COALESCE(s.entry_no, CONCAT('#', s.id))";

    /**
     * 주문조회(§2-1) <b>주문 단위</b> 키 페이지.
     *
     * <p>근거: 10/27 명세 §1 — "조회 대상을 <b>새 특약점 주문 + 매출에서 수기 등록한 주문</b>으로
     * 바꾼다. DSRE에서 운영하는 상품을 끌어오는 구조가 아니다." · §2-1 "수기 주문과
     * 온라인(특약점) 주문을 <b>함께</b> 보여준다".
     *
     * <p>★<b>왜 키만 먼저 뽑나.</b> 주문 하나가 발송 여러 건으로 갈릴 수 있어(§3-6)
     * 발송 단위로 페이징하면 같은 주문이 두 줄로 나오고 페이지 경계에서 쪼개진다.
     * 키로 페이징하고 그 키들의 발송을 모아 한 줄로 만든다.
     *
     * <p>‼️<b>삭제된 발송도 읽는다</b>({@code @SQLRestriction} 우회 네이티브) — 레거시처럼
     * 삭제 주문도 목록에 남기고 표시만 한다. 숨기면 담당자가 같은 주문을 다시 넣는다.
     */
    @Query(value = "SELECT " + ORDER_KEY + " AS okey"
            + " FROM shipment s JOIN partners p ON p.id = s.partner_id"
            + " WHERE s.trade_date BETWEEN :from AND :to"
            + "   AND (CAST(:partnerId AS SIGNED) IS NULL OR p.id = :partnerId)"
            + "   AND (:partnerCode IS NULL OR p.code = :partnerCode)"
            + "   AND (:keyword IS NULL OR LOWER(p.name) LIKE :keyword"
            + "        OR LOWER(COALESCE(s.school_name,'')) LIKE :keyword"
            + "        OR LOWER(COALESCE(s.entry_no,'')) LIKE :keyword)"
            // ★접수경로(프론트 I-2). 경로는 **매출** 에 있고 이 쿼리는 발송 건을 센다 —
            //   등록번호로 매출을 되짚는다. 'MANUAL' 은 **값이 비어 있는 옛 주문까지** 포함한다
            //   (특약점 연동이 2026-11 에 열렸으므로 그 전 주문은 전부 수기다).
            + "   AND (:channel IS NULL"
            + "        OR (:channel = 'MANUAL' AND NOT EXISTS ("
            + "              SELECT 1 FROM sales sx WHERE sx.entry_no = s.entry_no"
            + "                AND sx.channel = 'ONLINE'))"
            + "        OR (:channel <> 'MANUAL' AND EXISTS ("
            + "              SELECT 1 FROM sales sx WHERE sx.entry_no = s.entry_no"
            + "                AND sx.channel = :channel)))"
            + " GROUP BY okey"
            + " HAVING (CAST(:stateRank AS SIGNED) IS NULL"
            + "         OR MIN(" + ORDER_STATE_RANK + ") = :stateRank)"
            + " ORDER BY MAX(s.trade_date) DESC, MIN(p.code), MIN(s.trade_seq), okey",
            countQuery = "SELECT COUNT(*) FROM (SELECT " + ORDER_KEY + " AS okey"
            + " FROM shipment s JOIN partners p ON p.id = s.partner_id"
            + " WHERE s.trade_date BETWEEN :from AND :to"
            + "   AND (CAST(:partnerId AS SIGNED) IS NULL OR p.id = :partnerId)"
            + "   AND (:partnerCode IS NULL OR p.code = :partnerCode)"
            + "   AND (:keyword IS NULL OR LOWER(p.name) LIKE :keyword"
            + "        OR LOWER(COALESCE(s.school_name,'')) LIKE :keyword"
            + "        OR LOWER(COALESCE(s.entry_no,'')) LIKE :keyword)"
            // ★접수경로(프론트 I-2). 경로는 **매출** 에 있고 이 쿼리는 발송 건을 센다 —
            //   등록번호로 매출을 되짚는다. 'MANUAL' 은 **값이 비어 있는 옛 주문까지** 포함한다
            //   (특약점 연동이 2026-11 에 열렸으므로 그 전 주문은 전부 수기다).
            + "   AND (:channel IS NULL"
            + "        OR (:channel = 'MANUAL' AND NOT EXISTS ("
            + "              SELECT 1 FROM sales sx WHERE sx.entry_no = s.entry_no"
            + "                AND sx.channel = 'ONLINE'))"
            + "        OR (:channel <> 'MANUAL' AND EXISTS ("
            + "              SELECT 1 FROM sales sx WHERE sx.entry_no = s.entry_no"
            + "                AND sx.channel = :channel)))"
            + " GROUP BY okey"
            + " HAVING (CAST(:stateRank AS SIGNED) IS NULL"
            + "         OR MIN(" + ORDER_STATE_RANK + ") = :stateRank)) g",
            nativeQuery = true)
    org.springframework.data.domain.Page<String> findOrderKeyPage(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("partnerId") Long partnerId,
            @Param("partnerCode") String partnerCode,
            @Param("keyword") String keyword,
            @Param("stateRank") Integer stateRank,
            @Param("channel") String channel,
            org.springframework.data.domain.Pageable pageable);

    /** 주문 키들에 묶인 발송 건 전부(삭제분 포함). {@link #findOrderKeyPage} 와 짝이다. */
    @Query(value = "SELECT s.* FROM shipment s WHERE " + ORDER_KEY + " IN (:keys)",
            nativeQuery = true)
    List<Shipment> findByOrderKeys(@Param("keys") Collection<String> keys);
}
