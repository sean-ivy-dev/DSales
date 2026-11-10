package com.daesung.sales.inventory.entity;

import com.daesung.sales.common.entity.BaseEntity;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.salestype.entity.ShipmentType;
import com.daesung.sales.warehouse.entity.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재고 이벤트. 근거: InvenData 정규화 + 🆕 창고/원가/txn_type.
 * qty는 부호 포함(입고/반품 +, 출고/폐기 -). 창고 잔량 = 상품×창고 qty 합.
 */
@Entity
@Table(name = "inventory_txn")
@org.hibernate.annotations.SQLRestriction("deleted_at is null")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryTxn extends com.daesung.sales.common.entity.SoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 도서(상품) 거래면 채운다. <b>자재 거래면 null</b> — 한 행은 도서이거나 자재다(V78 CHECK). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    /**
     * 자재 거래면 채운다. 도서 거래면 null.
     *
     * <p>근거: 9/27 회의 A-1 — 수불·자재를 매출프로그램이 단독 관리한다.
     * 같은 원장에 두는 이유는 자재도 입고·이고·폐기·실사를 똑같이 겪기 때문이다
     * (표를 나누면 그 동작과 전표취소·마감잠금이 전부 두 벌이 된다).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    private com.daesung.sales.material.entity.Material material;

    /**
     * 자재 입출고 구분(V79). 자재 거래에만 값이 있다.
     * {@link #txnType} 이 잔량을 계산하고, 이 축이 "무슨 일이었나"를 보존한다
     * (회수 2종·폐기/파손이 txnType 만으로는 서로 뭉개진다).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "material_io", length = 20)
    private MaterialIo materialIo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type", nullable = false, length = 20)
    private TxnType txnType;

    @Column(nullable = false)
    private int qty;

    /** 🆕 입고원가(순매출/이익률용). */
    @Column(name = "unit_cost")
    private Long unitCost;

    /**
     * 매입률 %(매입입고 전용, F-13). <b>매입단가 = 정가 × 매입률/100</b>.
     *
     * <p>근거: 레거시 `salesData` 입고행 33,221건의 `supRate` — 같은 행에서
     * {@code 정가 × 매입률/100 × 수량 = 금액} 이 검산된다.
     *
     * <p>‼️<b>입고 건별</b>이다. 도서 마스터에는 그 칸이 없고, 같은 도서가 같은 해에도
     * 매입률 3종을 갖는 경우가 있다. 그 시점 값이 여기 남아야 단가를 고쳐도 과거가 안 움직인다.
     */
    @Column(name = "purchase_rate")
    private Integer purchaseRate;

    /** 입고구분(INBOUND 이벤트만). PURCHASE=매입입고→16p 순매출조회 매입액으로 집계. 그 외 이벤트는 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "inbound_type", length = 20)
    private InboundType inboundType;

    @Column(name = "ref_no", length = 30)
    private String refNo;

    /** 출고/반품 이벤트의 출고유형 태그(수불부 매출/무상/교사용/반품 버킷 분해용). 그 외 이벤트는 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "shipment_type", length = 20)
    private ShipmentType shipmentType;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** 이고/BOM 짝 연결. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_txn_id")
    private InventoryTxn sourceTxn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id")
    private Partner partner;

    @Column(length = 1000)
    private String memo;

    /** 일반 입고 이벤트. qty는 양수. inboundType: NORMAL(정상)/PURCHASE(매입입고). */
    public static InventoryTxn inbound(Product product, Warehouse warehouse, int qty, Long unitCost,
                                       InboundType inboundType, LocalDate tradeDate, Partner partner,
                                       String memo, String refNo) {
        InventoryTxn t = inbound(product, warehouse, qty, unitCost, inboundType, tradeDate, partner, memo);
        t.refNo = refNo;
        return t;
    }

    /** 매입률을 붙인다(매입입고 전용, F-13). 호출부가 검증한 뒤 넘긴다. */
    public InventoryTxn withPurchaseRate(Integer purchaseRate) {
        this.purchaseRate = purchaseRate;
        return this;
    }

    /**
     * 원 이벤트를 <b>반대 부호로</b> 되돌리는 이벤트(전표 취소 역분개).
     *
     * <p>★축(도서/자재)을 원본에서 그대로 복사한다. 예전엔 취소가 product 만 실어
     * 자재 전표를 취소하면 NPE 로 500 이 났다 — 자재 축이 생긴 뒤의 함정이라 팩토리에 가둔다.
     */
    public static InventoryTxn reverseOf(InventoryTxn origin, int reverseQty,
                                         LocalDate tradeDate, String refNo, String memo) {
        InventoryTxn t = new InventoryTxn();
        t.product = origin.product;
        t.material = origin.material;
        t.warehouse = origin.warehouse;
        t.txnType = origin.txnType;
        t.shipmentType = origin.shipmentType;
        t.materialIo = origin.materialIo;
        t.qty = reverseQty;
        t.tradeDate = tradeDate;
        t.refNo = refNo;
        t.memo = memo;
        return t;
    }

    /**
     * 자재 거래 한 건(입고 외 — 이고·폐기·실사). 도서 쪽이 이벤트 종류마다 팩토리를 둔 것과 달리
     * 자재는 <b>하나로 받는다</b>. 자재는 출고유형·입고구분 같은 매출 축이 없어 종류별로
     * 다른 필드를 채울 일이 없기 때문이다 — 팩토리를 쪼개면 같은 코드가 네 벌이 된다.
     */
    public static InventoryTxn materialTxn(com.daesung.sales.material.entity.Material material,
                                           Warehouse warehouse, TxnType txnType, int qty,
                                           LocalDate tradeDate, String refNo, String memo) {
        return materialTxn(material, warehouse, txnType, null, qty, tradeDate, refNo, memo);
    }

    /** 입출고 구분까지 남기는 자재 거래(V79). 부호는 호출부가 이미 붙여서 넘긴다. */
    public static InventoryTxn materialTxn(com.daesung.sales.material.entity.Material material,
                                           Warehouse warehouse, TxnType txnType, MaterialIo io,
                                           int qty, LocalDate tradeDate, String refNo, String memo) {
        InventoryTxn t = new InventoryTxn();
        t.material = material;
        t.warehouse = warehouse;
        t.txnType = txnType;
        t.materialIo = io;
        t.qty = qty;
        t.tradeDate = tradeDate;
        t.refNo = refNo;
        t.memo = memo;
        return t;
    }

    /** 자재 입고. 도서 입고와 같은 원장에 남되 product 대신 material 을 채운다. */
    public static InventoryTxn materialInbound(com.daesung.sales.material.entity.Material material,
                                               Warehouse warehouse, int qty, Long unitCost,
                                               InboundType inboundType, LocalDate tradeDate,
                                               Partner partner, String memo, String refNo) {
        InventoryTxn t = new InventoryTxn();
        t.material = material;
        t.warehouse = warehouse;
        t.txnType = TxnType.INBOUND;
        t.materialIo = MaterialIo.INBOUND;   // 자재 내역 표에서 '입고'로 읽히도록 구분도 남긴다
        t.qty = qty;
        t.unitCost = unitCost;
        t.inboundType = (inboundType != null) ? inboundType : InboundType.NORMAL;
        t.tradeDate = tradeDate;
        t.partner = partner;
        t.memo = memo;
        t.refNo = refNo;
        return t;
    }

    public static InventoryTxn inbound(Product product, Warehouse warehouse, int qty, Long unitCost,
                                       InboundType inboundType, LocalDate tradeDate, Partner partner, String memo) {
        InventoryTxn t = new InventoryTxn();
        t.product = product;
        t.warehouse = warehouse;
        t.txnType = TxnType.INBOUND;
        t.qty = qty;
        t.unitCost = unitCost;
        t.inboundType = (inboundType != null) ? inboundType : InboundType.NORMAL;
        t.tradeDate = tradeDate;
        t.partner = partner;
        t.memo = memo;
        return t;
    }

    /**
     * 이고(창고 이동) 한 다리. 출발=−qty(sourceTxn=null), 도착=+qty(sourceTxn=출발 이벤트).
     *
     * <p>★{@code refNo}(전표번호)를 반드시 받는다. 예전엔 안 붙여서 NULL 로 남았고,
     * 취소·삭제가 전부 refNo 로 대상을 찾으므로 <b>이고는 되돌릴 방법이 아예 없었다</b>.
     */
    public static InventoryTxn transfer(Product product, Warehouse warehouse, int qty,
                                        LocalDate tradeDate, InventoryTxn sourceTxn,
                                        String refNo, String memo) {
        InventoryTxn t = new InventoryTxn();
        t.product = product;
        t.warehouse = warehouse;
        t.txnType = TxnType.TRANSFER;
        t.qty = qty;
        t.tradeDate = tradeDate;
        t.sourceTxn = sourceTxn;
        t.refNo = refNo;
        t.memo = memo;
        return t;
    }

    /** 물류작업비 대상 여부(8p 입고/대체등록). 입고 이벤트에서 사용. */
    @Column(name = "logis_cost_target", nullable = false)
    private boolean logisCostTarget;


    /** 물류작업비 대상 표시(입고 시). */
    public void applyLogisCostTarget(boolean target) {
        this.logisCostTarget = target;
    }


    /** 세트 조립·해체 한 줄. 이고와 같은 이유로 {@code refNo}를 받는다. */
    public static InventoryTxn bom(Product product, Warehouse warehouse, int qty,
                                   TxnType txnType, LocalDate tradeDate,
                                   String refNo, String memo) {
        InventoryTxn t = new InventoryTxn();
        t.product = product;
        t.warehouse = warehouse;
        t.txnType = txnType;
        t.qty = qty;
        t.tradeDate = tradeDate;
        t.refNo = refNo;
        t.memo = memo;
        return t;
    }

    /**
     * 출고/반품(매출 연동). 정상출고=OUTBOUND(qty 음수), 반품=RETURN(qty 양수).
     * shipmentType을 실어 수불부가 매출/무상/교사용/반품으로 분해. refNo=매출번호(I-...).
     */
    public static InventoryTxn shipment(Product product, Warehouse warehouse, int qty, TxnType txnType,
                                        ShipmentType shipmentType, LocalDate tradeDate, String refNo, String memo) {
        InventoryTxn t = new InventoryTxn();
        t.product = product;
        t.warehouse = warehouse;
        t.txnType = txnType;
        t.qty = qty;
        t.shipmentType = shipmentType;
        t.tradeDate = tradeDate;
        t.refNo = refNo;
        t.memo = memo;
        return t;
    }

    /** 폐기. qty는 음수(재고 차감). refNo=폐기번호(P-...). */
    public static InventoryTxn dispose(Product product, Warehouse warehouse, int qty,
                                       LocalDate tradeDate, String refNo, String memo) {
        InventoryTxn t = new InventoryTxn();
        t.product = product;
        t.warehouse = warehouse;
        t.txnType = TxnType.DISPOSE;
        t.qty = qty;
        t.tradeDate = tradeDate;
        t.refNo = refNo;
        t.memo = memo;
        return t;
    }

    /** 재고실사 조정. qty=실물−시스템(부호 포함). refNo=실사번호(ST-...). */
    public static InventoryTxn adjust(Product product, Warehouse warehouse, int qty,
                                      LocalDate tradeDate, String refNo, String memo) {
        InventoryTxn t = new InventoryTxn();
        t.product = product;
        t.warehouse = warehouse;
        t.txnType = TxnType.ADJUST;
        t.qty = qty;
        t.tradeDate = tradeDate;
        t.refNo = refNo;
        t.memo = memo;
        return t;
    }
    /**
     * 법인 축(V125). 그 **상품**을 따라간다. ‼️**자재 이벤트는 NULL** — 자재에는 법인 축이 없다.
     *
     * <p>★조회 조건에는 아직 쓰이지 않는다 — 다음 단계(법인별 조회)에서 붙는다.
     *   값만 등록 시점에 채워 둔다(안 채우면 다음 단계에서 또 백필해야 한다).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "corp", length = 10)
    private com.daesung.sales.common.corp.Corp corp;

    /** 법인 축 자동 채움. product 가 null 인 행(자재 이벤트)은 비워 둔다. */
    @PrePersist
    void fillCorp() {
        if (corp == null) {
            corp = (product != null) ? product.getCorp() : null;
        }
    }

}
