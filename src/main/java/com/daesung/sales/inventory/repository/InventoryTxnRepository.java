package com.daesung.sales.inventory.repository;

import com.daesung.sales.inventory.entity.InventoryTxn;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryTxnRepository extends JpaRepository<InventoryTxn, Long> {
    List<InventoryTxn> findByProductIdAndWarehouseId(Long productId, Long warehouseId);

    /**
     * 상품별 매입원가(매입입고 unit_cost 가중평균 = Σ(qty×unit_cost)/Σqty). 외부콘텐츠 이익 계산용.
     *
     * <p>‼️{@code product_id IS NOT NULL} — 같은 원장에 자재 거래가 함께 있다(V78).
     * 빼지 않으면 {@code GROUP BY product_id} 가 <b>키가 null 인 그룹</b>을 하나 더 내고,
     * 호출부가 상품 id 로 만드는 맵에서 터진다. products 를 조인하는 쿼리는 이 걱정이 없지만
     * 이 쿼리는 조인이 없어 그냥 딸려 온다.
     * 근거: 요구사항 8p 정정(2026-07-28) — PURCHASE(매입입고)로 등록된 입고만 매입 데이터로 집계.
     * 반환 Object[]: [productId, avgCost].
     */
    @Query(value = """
            SELECT product_id, COALESCE(SUM(qty * unit_cost) / NULLIF(SUM(qty), 0), 0) AS avg_cost
            FROM inventory_txn
            WHERE deleted_at IS NULL
              AND product_id IS NOT NULL
              AND txn_type = 'INBOUND' AND inbound_type = 'PURCHASE' AND unit_cost IS NOT NULL
            GROUP BY product_id
            """, nativeQuery = true)
    List<Object[]> avgInboundCostByProduct();

    /**
     * 상품별 <b>매입 입고수량</b>(기간). 근거: 정본 16p 데이터 항목 "입고/매입반품/매입액".
     *
     * <p>★단가(위 가중평균)와 달리 이것은 <b>기간으로 자른다</b>. 화면이 묻는 것은
     * "이 기간에 얼마나 들여왔나"이고, 단가는 "지금까지 평균 얼마에 들여왔나"라서
     * 자르는 기준이 다르다. 같은 기준으로 맞추면 기간 내 입고가 없는 달에 단가가 0이 되어
     * 이익이 매출 전액으로 부풀어 오른다.
     *
     * <p>반환 Object[]: [productId, inboundQty, inboundAmount].
     */
    @Query(value = """
            SELECT product_id, COALESCE(SUM(qty), 0), COALESCE(SUM(qty * COALESCE(unit_cost, 0)), 0)
            FROM inventory_txn
            WHERE deleted_at IS NULL
              AND product_id IS NOT NULL
              AND txn_type = 'INBOUND' AND inbound_type = 'PURCHASE'
              AND trade_date BETWEEN :fromDate AND :toDate
            GROUP BY product_id
            """, nativeQuery = true)
    List<Object[]> purchaseInboundByProduct(@Param("fromDate") LocalDate fromDate,
                                            @Param("toDate") LocalDate toDate);

    /**
     * 상품별 <b>매입반품</b>(기간). 근거: 발주처 1차회신 2026-10-02 #6 "매입반품 실제 발생".
     *
     * <p>‼️정본 16p 인터뷰(2026-07-25)가 "연말 일괄, 연중 반품 없음"으로 닫아 두었던 항목이다.
     * 그 근거로 칸을 안 만들었는데 발주처가 직접 뒤집었다.
     *
     * <p>저장은 <b>음수</b>다(재고가 준다). 화면이 쓰는 값은 양수라 여기서 부호를 뒤집어 돌려준다 —
     * 폐기 목록과 같은 규칙이다. 뒤집지 않으면 화면이 "-120부 반품"을 그리게 된다.
     *
     * <p>반환 Object[]: [productId, returnQty(양수), returnAmount(양수)].
     */
    @Query(value = """
            SELECT product_id, COALESCE(SUM(-qty), 0),
                   COALESCE(SUM(-qty * COALESCE(unit_cost, 0)), 0)
            FROM inventory_txn
            WHERE deleted_at IS NULL
              AND product_id IS NOT NULL
              AND txn_type = 'INBOUND' AND inbound_type = 'PURCHASE_RETURN'
              AND trade_date BETWEEN :fromDate AND :toDate
            GROUP BY product_id
            """, nativeQuery = true)
    List<Object[]> purchaseReturnByProduct(@Param("fromDate") LocalDate fromDate,
                                           @Param("toDate") LocalDate toDate);

    /**
     * 폐기 내역(10p 조회). 재고이벤트 DISPOSE를 최근순으로.
     *
     * <p>★<b>{@code qty < 0} — 역분개 행은 뺀다</b>(2026-10-01 프론트 실측 지적).
     * 취소하면 같은 전표번호로 <b>반대 부호</b> 이벤트가 하나 더 생기는데, 그 행까지 목록에 실리면
     * 수량을 양수로 뒤집어 보여주는 탓에 <b>원 행 200 + 역분개 행 200 = 400</b>이 된다.
     * 분류별 요약은 {@code SUM(-qty)} 라 0으로 상쇄되고 있어 <b>같은 화면의 두 숫자가 어긋났다</b>.
     * 원 폐기는 언제나 음수, 역분개는 양수라 부호로 가른다.
     *
     * <p>★별도 폐기 테이블을 만들지 않는다 — 재고는 {@code inventory_txn}이 유일 진실이고
     * 폐기 원장을 따로 두면 둘이 어긋날 수 있다(§재고 정의 단일화).
     *
     * <p>수량은 원장에 <b>음수</b>로 들어 있다(재고를 깎으므로). 화면에는 양수로 보여야 하니
     * 부호는 서비스에서 뒤집는다 — 쿼리에서 뒤집으면 "원장 값"과 "보이는 값"이
     * 어디서 갈렸는지 추적이 어려워진다.
     */
    @Query("""
            select t from InventoryTxn t
              join fetch t.product join fetch t.warehouse
             where t.txnType = com.daesung.sales.inventory.entity.TxnType.DISPOSE
               and t.qty < 0
               and (cast(:fromDate as date) is null or t.tradeDate >= :fromDate)
               and (cast(:toDate as date) is null or t.tradeDate <= :toDate)
               and (:anyProduct = true or t.product.id in :productIds)
               and (:anyWarehouse = true or t.warehouse.id in :warehouseIds)
             order by t.tradeDate desc, t.id desc
            """)
    List<InventoryTxn> findDisposalsAll(@Param("fromDate") LocalDate fromDate,
                                     @Param("toDate") LocalDate toDate,
                                     @Param("anyProduct") boolean anyProduct,
                                     @Param("productIds") java.util.Collection<Long> productIds,
                                     @Param("anyWarehouse") boolean anyWarehouse,
                                     @Param("warehouseIds") java.util.Collection<Long> warehouseIds);

    /**
     * 입고/대체 내역(8p·9p 조회). 재고를 움직이는 <b>입고·이고·세트작업</b>만 최근순으로.
     *
     * <p>★출고·폐기·반품·실사는 뺀다 — 그건 각자 화면이 따로 있다(매출조회·폐기조회·수불부).
     * 한 목록에 다 담으면 "입고/대체 등록" 화면인데 매출까지 섞여 보인다.
     *
     * <p>{@code kinds}가 비어 있으면 네 종류 전부. 화면의 작업모드 탭(일반입고/단순이고/세트)에 대응.
     */
    @Query("""
            select t from InventoryTxn t
              join fetch t.product join fetch t.warehouse
              left join fetch t.partner
             where t.txnType in :kinds
               and (cast(:fromDate as date) is null or t.tradeDate >= :fromDate)
               and (cast(:toDate as date) is null or t.tradeDate <= :toDate)
               and (:anyProduct = true or t.product.id in :productIds)
               and (:anyWarehouse = true or t.warehouse.id in :warehouseIds)
             order by t.tradeDate desc, t.id desc
            """)
    List<InventoryTxn> findStockRecords(@Param("kinds") java.util.Collection<com.daesung.sales.inventory.entity.TxnType> kinds,
                                        @Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate,
                                        @Param("anyProduct") boolean anyProduct,
                                        @Param("productIds") java.util.Collection<Long> productIds,
                                        @Param("anyWarehouse") boolean anyWarehouse,
                                        @Param("warehouseIds") java.util.Collection<Long> warehouseIds);

    /** 특정 전표(refNo)로 생성된 출고/반품 이벤트(매출취소 역분개용). product·warehouse 즉시 로드. */
    @Query("select t from InventoryTxn t join fetch t.product join fetch t.warehouse"
            + " where t.refNo = :refNo and t.shipmentType is not null")
    List<InventoryTxn> findShipmentsByRefNo(String refNo);

    /**
     * 특정 전표(refNo)의 <b>모든</b> 재고 이벤트. 폐기·입고 취소 역분개용.
     *
     * <p>{@link #findShipmentsByRefNo}와 달리 {@code shipmentType} 조건이 없다 —
     * 폐기(DISPOSE)·입고(INBOUND)에는 출고유형이 없기 때문이다.
     *
     * <p>‼️<b>product 는 LEFT JOIN 이어야 한다</b>(V78). 자재 거래는 product 가 null 이라
     * INNER JOIN 이면 통째로 빠지고, 취소·삭제가 "전표가 없습니다"로 실패한다
     * (자재 축을 넣고 실제로 이렇게 깨졌다). 이 메서드는 전표에 달린 <b>모든</b> 이벤트를
     * 돌려준다는 약속이라 한 종류라도 빠지면 안 된다.
     */
    @Query("select t from InventoryTxn t left join fetch t.product left join fetch t.material"
            + " join fetch t.warehouse where t.refNo = :refNo")
    List<InventoryTxn> findAllByRefNo(String refNo);

    /** 이고 도착다리 조회(위탁 반품 역-자동이고용). sourceTxn=출발다리인 도착 이벤트 → 위탁창고. */
    @Query("select t from InventoryTxn t join fetch t.warehouse where t.sourceTxn.id = :sourceTxnId")
    java.util.Optional<InventoryTxn> findBySourceTxnId(Long sourceTxnId);

    /**
     * 제품수불부 집계(상품×창고). 물류 이벤트를 CASE 버킷으로 합산 + 이월/마감.
     * 출고는 shipment_type으로 매출/무상/교사용/반품 분해(스펙 수불부 컬럼 대응).
     * 반환 Object[]: [productId, code, name, warehouseId, whName,
     *   opening, inbound, transfer, bom, dispose, sale, free, teacher, salesReturn, adjust, closing, cached]
     */
    @Query(value = """
            SELECT t.product_id, p.code, p.name, t.warehouse_id, w.name,
              COALESCE(SUM(CASE WHEN t.trade_date < :fromDate THEN t.qty ELSE 0 END), 0) AS opening,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type = 'INBOUND' THEN t.qty ELSE 0 END), 0) AS inbound,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type = 'TRANSFER' THEN t.qty ELSE 0 END), 0) AS transfer,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type IN ('BOM_ASSEMBLE','BOM_DISASSEMBLE') THEN t.qty ELSE 0 END), 0) AS bom,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type = 'DISPOSE' THEN t.qty ELSE 0 END), 0) AS dispose,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.shipment_type IN ('NORMAL_SHIP','CONSIGN_SHIP') THEN t.qty ELSE 0 END), 0) AS sale,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.shipment_type = 'GIFT' THEN t.qty ELSE 0 END), 0) AS free,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.shipment_type = 'TEACHER_USE' THEN t.qty ELSE 0 END), 0) AS teacher,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.shipment_type = 'RETURN' THEN t.qty ELSE 0 END), 0) AS sales_return,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type = 'ADJUST' THEN t.qty ELSE 0 END), 0) AS adjust,
              COALESCE(SUM(CASE WHEN t.trade_date <= :toDate THEN t.qty ELSE 0 END), 0) AS closing,
              COALESCE(MAX(inv.qty), 0) AS cached
            FROM inventory_txn t
              JOIN products p ON p.id = t.product_id
              JOIN warehouses w ON w.id = t.warehouse_id
              LEFT JOIN inventory inv ON inv.product_id = t.product_id AND inv.warehouse_id = t.warehouse_id
            WHERE t.deleted_at IS NULL
              -- ★분류 조건(2026-10-08). 없던 동안 화면이 기간 전체를 받아 걸러 쓰고 있었다.
              AND (:anyCat = TRUE OR p.cat_code IN (:catCodes))
              AND (CAST(:productId AS SIGNED) IS NULL OR t.product_id = :productId)
              AND (CAST(:warehouseId AS SIGNED) IS NULL OR t.warehouse_id = :warehouseId)
              AND (:warehouseType IS NULL OR w.type = :warehouseType)
              AND p.ledger_visible = TRUE
            GROUP BY t.product_id, p.code, p.name, t.warehouse_id, w.name
            ORDER BY p.code, w.name
            """, nativeQuery = true)
    List<Object[]> stockLedger(@Param("fromDate") LocalDate fromDate,
                               @Param("toDate") LocalDate toDate,
                               @Param("productId") Long productId,
                               @Param("warehouseId") Long warehouseId,
                               @Param("warehouseType") String warehouseType,
                               @Param("anyCat") boolean anyCat,
                               @Param("catCodes") java.util.Collection<String> catCodes);

    /**
     * 제품수불부 <b>결산내역</b> 집계(분류×상품, 창고 합산). 근거: 정본 11p "결산내역(연초~기준일 누적)" +
     * 레거시 제품수불부 「결산내역」 체크박스.
     *
     * <p>버킷 구성은 {@link #stockLedger}와 같다. 다른 것은 두 가지뿐이다 —
     * <b>창고로 나누지 않고</b>(레거시엔 창고 축이 없다) <b>분류코드로 묶는다</b>(rollup 대상).
     * 창고구분 필터는 남긴다(2026-08-21 회신 「위탁 미결잔여가 어느 창고에 있는지」).
     *
     * <p>기간의 시작(연초)은 호출부가 정한다 — 이 쿼리는 받은 fromDate를 그대로 쓴다.
     *
     * <p>‼️<b>매출 칸은 NORMAL_SHIP + CONSIGN_SHIP 둘 다</b>다(2026-09-11 정정).
     * 위탁 <b>정산분</b> 출고가 CONSIGN_SHIP으로 기록되는데 예전엔 어느 칸에도 안 들어가
     * ① 수불부 매출이 순매출조회보다 적고(실측 503 중 500),
     * ② <b>컬럼을 다 더해도 현재재고가 안 나왔다</b> — 재고에는 반영되는데 칸이 없었으니까.
     * 위탁은 "정산 시점에 매출"이 확정 기준이므로 정산분은 매출이 맞다.
     * (위탁 <b>출고</b> 자체는 매출 행을 만들지 않는다 — 재고 이동 + 미결일 뿐이다.)
     *
     * <p>반환 Object[]: [catCode, catName, productId, code, name,
     *   opening, inbound, transfer, bom, dispose, sale, free, teacher, salesReturn, adjust, closing].
     */
    @Query(value = """
            SELECT p.cat_code, p.cat_name, t.product_id, p.code, p.name,
              COALESCE(SUM(CASE WHEN t.trade_date < :fromDate THEN t.qty ELSE 0 END), 0) AS opening,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type = 'INBOUND' THEN t.qty ELSE 0 END), 0) AS inbound,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type = 'TRANSFER' THEN t.qty ELSE 0 END), 0) AS transfer,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type IN ('BOM_ASSEMBLE','BOM_DISASSEMBLE') THEN t.qty ELSE 0 END), 0) AS bom,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type = 'DISPOSE' THEN t.qty ELSE 0 END), 0) AS dispose,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.shipment_type IN ('NORMAL_SHIP','CONSIGN_SHIP') THEN t.qty ELSE 0 END), 0) AS sale,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.shipment_type = 'GIFT' THEN t.qty ELSE 0 END), 0) AS free,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.shipment_type = 'TEACHER_USE' THEN t.qty ELSE 0 END), 0) AS teacher,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.shipment_type = 'RETURN' THEN t.qty ELSE 0 END), 0) AS sales_return,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type = 'ADJUST' THEN t.qty ELSE 0 END), 0) AS adjust,
              COALESCE(SUM(CASE WHEN t.trade_date <= :toDate THEN t.qty ELSE 0 END), 0) AS closing
            FROM inventory_txn t
              JOIN products p ON p.id = t.product_id
              JOIN warehouses w ON w.id = t.warehouse_id
            WHERE t.deleted_at IS NULL
              AND (CAST(:productId AS SIGNED) IS NULL OR t.product_id = :productId)
              AND (:warehouseType IS NULL OR w.type = :warehouseType)
              AND p.ledger_visible = TRUE
            GROUP BY p.cat_code, p.cat_name, t.product_id, p.code, p.name
            ORDER BY p.cat_code, p.code
            """, nativeQuery = true)
    List<Object[]> stockSettlement(@Param("fromDate") LocalDate fromDate,
                                   @Param("toDate") LocalDate toDate,
                                   @Param("productId") Long productId,
                                   @Param("warehouseType") String warehouseType);

    /**
     * 세트 <b>조립·해체 현황</b>(30p) — 완제품(세트) 기준 집계.
     * 근거: 발주처 화면검토(2026-08-31) 화면30 — "화면 27(물류 작업비 계산)의 비용 산출과는
     * <b>연동되지 않도록 분리</b>해, <b>세트 조립·해체 작업을 진행한 현황(결과)만</b> 보여주는 조회 화면".
     *
     * <p>★<b>완제품 행을 부호로 가려낸다.</b> BOM 작업 한 번은 완제품 1행 + 구성품 N행을 남기는데
     * 둘 다 같은 {@code txn_type}이라 구분할 표시가 없다. 다만 방향이 정반대다 —
     * <pre>
     *   조립: 완제품 +workQty / 구성품 −(비율×workQty)
     *   해체: 완제품 −workQty / 구성품 +(비율×workQty)
     * </pre>
     * 그래서 {@code (조립 AND qty>0) OR (해체 AND qty<0)} 이 정확히 완제품 행이다.
     * 이 규칙이 깨지려면 {@code InventoryService.bom()}이 부호를 뒤집어야 하는데,
     * 그러면 재고 자체가 반대로 움직여 훨씬 먼저 드러난다.
     *
     * <p>작업 건수는 완제품 행 수다 — 작업 1회에 완제품 행이 정확히 하나 생긴다.
     *
     * <p>반환 Object[]: [catCode, catName, productId, code, name,
     * 조립건수, 조립수량, 해체건수, 해체수량, 최근작업일].
     */
    @Query(value = """
            SELECT p.cat_code, p.cat_name, p.id, p.code, p.name,
                   SUM(CASE WHEN t.txn_type = 'BOM_ASSEMBLE' THEN 1 ELSE 0 END) AS asm_cnt,
                   COALESCE(SUM(CASE WHEN t.txn_type = 'BOM_ASSEMBLE' THEN t.qty ELSE 0 END), 0) AS asm_qty,
                   SUM(CASE WHEN t.txn_type = 'BOM_DISASSEMBLE' THEN 1 ELSE 0 END) AS dis_cnt,
                   COALESCE(SUM(CASE WHEN t.txn_type = 'BOM_DISASSEMBLE' THEN -t.qty ELSE 0 END), 0) AS dis_qty,
                   MAX(t.trade_date) AS last_date
            FROM inventory_txn t JOIN products p ON p.id = t.product_id
            WHERE t.deleted_at IS NULL
              AND ((t.txn_type = 'BOM_ASSEMBLE' AND t.qty > 0)
                OR (t.txn_type = 'BOM_DISASSEMBLE' AND t.qty < 0))
              AND (CAST(:fromDate AS DATE) IS NULL OR t.trade_date >= :fromDate)
              AND (CAST(:toDate   AS DATE) IS NULL OR t.trade_date <= :toDate)
              AND (:anyWarehouse = TRUE OR t.warehouse_id IN (:warehouseIds))
              AND (:catCode IS NULL OR p.cat_code = :catCode)
            GROUP BY p.cat_code, p.cat_name, p.id, p.code, p.name
            ORDER BY p.cat_code, p.code
            """, nativeQuery = true)
    List<Object[]> bomWorkByProduct(@Param("fromDate") LocalDate fromDate,
                                    @Param("toDate") LocalDate toDate,
                                    @Param("anyWarehouse") boolean anyWarehouse,
                                    @Param("warehouseIds") java.util.Collection<Long> warehouseIds,
                                    @Param("catCode") String catCode);

    /**
     * 세트 조립·해체로 <b>움직인 구성품</b>(30p 상세). 완제품과 반대 부호인 행만 모은다.
     *
     * <p>★<b>어느 세트 작업에 쓰였는지는 나누지 않는다.</b> 원장에 완제품↔구성품 링크가 없어
     * 귀속을 알 수 없다. 지금 BOM 비율로 역산하면 그럴듯한 숫자가 나오지만,
     * 작업한 뒤에 BOM이 바뀌었으면 <b>실제와 다른 값을 사실처럼</b> 보여주게 된다.
     * 그래서 구성품은 기간 합계로만 준다 — 모르는 것은 모른다고 두는 편이 낫다.
     *
     * <p>반환 Object[]: [productId, code, name, 소모수량(조립), 복원수량(해체)].
     */
    @Query(value = """
            SELECT p.id, p.code, p.name,
                   COALESCE(SUM(CASE WHEN t.txn_type = 'BOM_ASSEMBLE' THEN -t.qty ELSE 0 END), 0) AS used,
                   COALESCE(SUM(CASE WHEN t.txn_type = 'BOM_DISASSEMBLE' THEN t.qty ELSE 0 END), 0) AS back
            FROM inventory_txn t JOIN products p ON p.id = t.product_id
            WHERE t.deleted_at IS NULL
              AND ((t.txn_type = 'BOM_ASSEMBLE' AND t.qty < 0)
                OR (t.txn_type = 'BOM_DISASSEMBLE' AND t.qty > 0))
              AND (CAST(:fromDate AS DATE) IS NULL OR t.trade_date >= :fromDate)
              AND (CAST(:toDate   AS DATE) IS NULL OR t.trade_date <= :toDate)
              AND (:anyWarehouse = TRUE OR t.warehouse_id IN (:warehouseIds))
            GROUP BY p.id, p.code, p.name
            ORDER BY p.code
            """, nativeQuery = true)
    List<Object[]> bomWorkComponents(@Param("fromDate") LocalDate fromDate,
                                     @Param("toDate") LocalDate toDate,
                                     @Param("anyWarehouse") boolean anyWarehouse,
                                     @Param("warehouseIds") java.util.Collection<Long> warehouseIds);

    /**
     * 폐기 <b>분류명별 요약</b>(10p). 근거: 발주처 화면검토(2026-08-31) —
     * "폐기 내역 조회시에도 <b>분류명 별로</b> 해당 내역 요약(전체)/상세가 모두 조회 가능한지".
     *
     * <p>상세({@link #findDisposals})가 낱건이라면 이건 분류 단위 합계다.
     * 수량은 원장에 음수로 들어 있어 양수로 뒤집는다 — "몇 부 버렸나"에 음수를 주면
     * 읽는 사람이 매번 뒤집어야 한다.
     *
     * <p>반환 Object[]: [catCode, catName, 폐기건수, 폐기수량].
     *
     * <p>★필터는 {@link #findDisposals}(상세)와 <b>같은 축·같은 규칙</b>이어야 한다.
     * 한쪽만 다중선택이 되면 담당자가 창고 둘을 고른 순간 요약과 상세의 수량이 갈리고,
     * 그러면 <b>둘 다 못 믿는다</b>.
     */
    @Query(value = """
            SELECT p.cat_code, p.cat_name, COUNT(*) AS cnt, COALESCE(SUM(-t.qty), 0) AS qty
            FROM inventory_txn t JOIN products p ON p.id = t.product_id
            WHERE t.deleted_at IS NULL
              AND t.txn_type = 'DISPOSE'
              AND (CAST(:fromDate AS DATE) IS NULL OR t.trade_date >= :fromDate)
              AND (CAST(:toDate   AS DATE) IS NULL OR t.trade_date <= :toDate)
              AND (:anyProduct = TRUE OR t.product_id IN (:productIds))
              AND (:anyWarehouse = TRUE OR t.warehouse_id IN (:warehouseIds))
            GROUP BY p.cat_code, p.cat_name
            ORDER BY p.cat_code
            """, nativeQuery = true)
    List<Object[]> disposalSummaryByCategory(@Param("fromDate") LocalDate fromDate,
                                             @Param("toDate") LocalDate toDate,
                                             @Param("anyProduct") boolean anyProduct,
                                             @Param("productIds") java.util.Collection<Long> productIds,
                                             @Param("anyWarehouse") boolean anyWarehouse,
                                             @Param("warehouseIds") java.util.Collection<Long> warehouseIds);

    /**
     * 상품별 <b>소요 기준 출고량</b>(자재 상세용). 근거: 발주처 구조보완요청안(2026-08-31)
     * "세트 출고분·회차 단독 출고분은 각각 세트·회차의 <b>매출+교사용 합계</b>(실제 출고된 수량)".
     *
     * <p>같은 문서의 소요량 반영 기준을 그대로 담았다 —
     * <ul>
     *   <li><b>폐기</b> → 매출·교사용과 동일하게 <b>합산</b></li>
     *   <li><b>반품</b> → 위탁출고 반품과 동일하게 <b>차감</b></li>
     *   <li><b>대체</b> → 세트조립·해체만 반영(조립 시 소진·해체 시 복원),
     *       <b>창고이동은 무관</b> — 창고만 옮긴 것은 소비가 아니다</li>
     * </ul>
     *
     * <p>부호를 양수로 뒤집어 낸다. 원장은 출고를 음수로 적지만 "몇 부 나갔나"를 묻는
     * 화면에 음수를 주면 읽는 사람이 매번 뒤집어야 한다.
     *
     * <p>증정(GIFT)도 포함한다 — 문서 문구는 "매출+교사용"이지만 증정도 실제로 물건이 나가고,
     * 정본 수불부 컬럼에 증정 칸이 따로 없어 교사용·무상으로 묶여 있다.
     *
     * <p>반환 Object[]: [productId, 소요기준 출고량].
     */
    @Query(value = """
            SELECT product_id,
              COALESCE(SUM(CASE WHEN shipment_type IN ('NORMAL_SHIP','CONSIGN_SHIP','GIFT','TEACHER_USE') THEN -qty ELSE 0 END), 0)
              + COALESCE(SUM(CASE WHEN txn_type = 'DISPOSE' THEN -qty ELSE 0 END), 0)
              - COALESCE(SUM(CASE WHEN shipment_type = 'RETURN' THEN qty ELSE 0 END), 0)
              + COALESCE(SUM(CASE WHEN txn_type IN ('BOM_ASSEMBLE','BOM_DISASSEMBLE') THEN qty ELSE 0 END), 0)
                AS consumed
            FROM inventory_txn
            WHERE deleted_at IS NULL
              AND product_id IN (:productIds)
              AND (CAST(:fromDate AS DATE) IS NULL OR trade_date >= :fromDate)
              AND (CAST(:toDate   AS DATE) IS NULL OR trade_date <= :toDate)
            GROUP BY product_id
            """, nativeQuery = true)
    List<Object[]> consumedQtyByProduct(@Param("productIds") java.util.Collection<Long> productIds,
                                        @Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate);

    /**
     * 도서입출고현황의 매입측(상품별 입고/취소) + 재고. 근거: 레거시 도서입출고현황.vb 입고/취소 버킷.
     * 입고=INBOUND qty&gt;0(원가금액=qty×unit_cost), 취소(매입취소)=INBOUND qty&lt;0(역분개), 재고=SUM(qty≤종료일)(정본 재고).
     * 상품 전 창고 합산. 반환 Object[]: [productId, code, name, catCode, catName, price,
     *   inboundQty, inboundAmt, cancelQty, cancelAmt, stockQty].
     */
    @Query(value = """
            SELECT t.product_id, p.code, p.name, p.cat_code, p.cat_name, p.price,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type='INBOUND' AND t.qty>0 THEN t.qty ELSE 0 END),0) AS inbound_qty,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type='INBOUND' AND t.qty>0 THEN t.qty*COALESCE(t.unit_cost,0) ELSE 0 END),0) AS inbound_amt,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type='INBOUND' AND t.qty<0 THEN -t.qty ELSE 0 END),0) AS cancel_qty,
              COALESCE(SUM(CASE WHEN t.trade_date BETWEEN :fromDate AND :toDate AND t.txn_type='INBOUND' AND t.qty<0 THEN -t.qty*COALESCE(t.unit_cost,0) ELSE 0 END),0) AS cancel_amt,
              COALESCE(SUM(CASE WHEN t.trade_date <= :toDate THEN t.qty ELSE 0 END),0) AS stock_qty
            FROM inventory_txn t JOIN products p ON p.id = t.product_id
            WHERE t.deleted_at IS NULL
              AND (CAST(:catCode AS CHAR) IS NULL OR p.cat_code = :catCode)
              AND (CAST(:productId AS SIGNED) IS NULL OR t.product_id = :productId)
            GROUP BY t.product_id, p.code, p.name, p.cat_code, p.cat_name, p.price
            ORDER BY p.code
            """, nativeQuery = true)
    List<Object[]> bookPurchaseAgg(@Param("fromDate") LocalDate fromDate,
                                   @Param("toDate") LocalDate toDate,
                                   @Param("catCode") String catCode,
                                   @Param("productId") Long productId);


    /**
     * 자재 거래 내역(V78). 근거: 9/27 회의 항목 22 —
     * "도서관리-자재관리, 입고/대체등록에 <b>자재 등록한 내역이 보이게</b>".
     *
     * <p>‼️{@code join fetch t.material} 은 INNER 라 도서 거래가 자연히 빠진다 —
     * 도서 내역({@link #findStockRecords})과 서로 섞이지 않는 것이 이 조인의 역할이다.
     */
    @Query("""
            select t from InventoryTxn t
              join fetch t.material m join fetch t.warehouse w
             where (cast(:fromDate as date) is null or t.tradeDate >= :fromDate)
               and (cast(:toDate as date) is null or t.tradeDate <= :toDate)
               and (:anyMaterial = true or m.id in :materialIds)
               and (:anyWarehouse = true or w.id in :warehouseIds)
               and (:anyKind = true or t.txnType in :kinds)
             order by t.tradeDate desc, t.id desc
            """)
    List<InventoryTxn> findMaterialRecords(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("anyMaterial") boolean anyMaterial,
            @Param("materialIds") java.util.Collection<Long> materialIds,
            @Param("anyWarehouse") boolean anyWarehouse,
            @Param("warehouseIds") java.util.Collection<Long> warehouseIds,
            @Param("anyKind") boolean anyKind,
            @Param("kinds") java.util.Collection<com.daesung.sales.inventory.entity.TxnType> kinds);

    /**
     * 폐기 내역 <b>페이지</b>(항목 10 "엑셀다운로드·페이지네이션 필수").
     *
     * <p>목록 전체를 한 번에 주던 것을 페이지로 바꾼다 — 폐기가 쌓이면 화면이 못 버틴다.
     * 엑셀 다운로드는 페이지를 나누지 않는다(그건 전량이 나가야 의미가 있다).
     *
     * <p>★{@code countQuery} 를 따로 준다. 안 주면 Spring Data 가 fetch 조인이 붙은
     * 본 쿼리로 count 를 만들어 오류가 난다. product·warehouse 는 단일 연관이라
     * fetch 조인과 페이징을 같이 써도 DB 가 limit 을 적용한다(컬렉션 조인과 다르다).
     */
    @Query(value = """
            select t from InventoryTxn t
              join fetch t.product join fetch t.warehouse
             where t.txnType = com.daesung.sales.inventory.entity.TxnType.DISPOSE
               and t.qty < 0
               and (cast(:fromDate as date) is null or t.tradeDate >= :fromDate)
               and (cast(:toDate as date) is null or t.tradeDate <= :toDate)
               and (:anyProduct = true or t.product.id in :productIds)
               and (:anyWarehouse = true or t.warehouse.id in :warehouseIds)
            """,
            countQuery = """
            select count(t) from InventoryTxn t
             where t.txnType = com.daesung.sales.inventory.entity.TxnType.DISPOSE
               and t.qty < 0
               and (cast(:fromDate as date) is null or t.tradeDate >= :fromDate)
               and (cast(:toDate as date) is null or t.tradeDate <= :toDate)
               and (:anyProduct = true or t.product.id in :productIds)
               and (:anyWarehouse = true or t.warehouse.id in :warehouseIds)
            """)
    org.springframework.data.domain.Page<InventoryTxn> findDisposals(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("anyProduct") boolean anyProduct,
            @Param("productIds") java.util.Collection<Long> productIds,
            @Param("anyWarehouse") boolean anyWarehouse,
            @Param("warehouseIds") java.util.Collection<Long> warehouseIds,
            org.springframework.data.domain.Pageable pageable);

    /**
     * <b>거래처별 매입액</b>(기간). 미수금현황·외상매출장이 쓴다.
     *
     * <p>★<b>왜 채권 화면에 매입이 들어가나</b> — 레거시 외상매출현황이 그렇게 계산한다.
     * {@code 외상매출현황조회.vb:676} —
     * <pre>이월 + 매출 + 반품 + (입고 + 취소) − 수금 = 잔액</pre>
     * 화면에 `입고수량`·`입고금액`·`실입고금액` 컬럼이 따로 있고, 한 거래처 원장에
     * <b>받을 돈(매출)과 줄 돈(매입)을 함께</b> 본다.
     *
     * <p>‼️이걸 빼면 <b>매입처가 "수금만 있고 매출이 없는" 이상한 거래처</b>로 보인다.
     * 실제로 그랬다(프론트 실측 2026-10-20 A-1) — 2022~2025 해마다 25곳 안팎이
     * 매출 0에 수금 130~197억으로 찍혀 잔액이 −190억까지 내려갔다.
     * 그 수금은 <b>그 해 매입대금 정산</b>이고(금액이 그 해 입고액과 거의 일치), 짝이 되는
     * 매입이 없으니 한쪽 다리로 서 있던 것이다.
     *
     * <p>반환 Object[]: [partnerId, 매입수량, 매입액].
     */
    @Query(value = """
            SELECT partner_id, COALESCE(SUM(qty), 0),
                   COALESCE(SUM(qty * COALESCE(unit_cost, 0)), 0)
            FROM inventory_txn
            WHERE deleted_at IS NULL AND partner_id IS NOT NULL
              AND txn_type = 'INBOUND' AND inbound_type = 'PURCHASE'
              AND trade_date BETWEEN :fromDate AND :toDate
              AND (CAST(:partnerId AS SIGNED) IS NULL OR partner_id = :partnerId)
            GROUP BY partner_id
            """, nativeQuery = true)
    List<Object[]> purchaseByPartner(@Param("fromDate") LocalDate fromDate,
                                     @Param("toDate") LocalDate toDate,
                                     @Param("partnerId") Long partnerId);

    /**
     * <b>매입처</b> 거래처 id 전부 — 매입(입고)이 한 번이라도 있던 곳.
     *
     * <p>근거: 작업지침 4번 — "<b>매입처(법인 분리)는 이 화면과 물류 작업비 계산에서 뺀다</b>".
     * 외상매출현황은 {@code ReceivableService}가 이미 빼고 있었고, <b>물류 작업비 쪽에만
     * 빠져 있었다</b>.
     *
     * <p>‼️<b>기간을 받지 않는다</b> — 조회기간 안에 매입이 없어도 매입처는 매입처다.
     * 기간으로 자르면 그 달에 매입이 없던 매입처가 매출처로 섞여 들어와, 달마다
     * 물류비 대상이 달라진다. {@code ReceivableService}도 같은 이유로 기간 밖 매입을
     * 함께 본다(그쪽 주석: "이월에 그 매입이 녹아 있어서").
     */
    @Query(value = """
            SELECT DISTINCT partner_id
            FROM inventory_txn
            WHERE deleted_at IS NULL AND partner_id IS NOT NULL
              AND txn_type = 'INBOUND' AND inbound_type = 'PURCHASE'
            """, nativeQuery = true)
    List<Long> purchaserPartnerIds();
}
