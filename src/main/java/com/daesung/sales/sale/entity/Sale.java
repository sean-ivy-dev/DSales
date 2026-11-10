package com.daesung.sales.sale.entity;

import com.daesung.sales.common.entity.BaseEntity;
import com.daesung.sales.consignment.entity.ConsignmentSettlement;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.salestype.entity.SalesCategory;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매출 원장(확정 매출 라인). 근거: 기획서 P.12/P.13, API 스펙 §3.
 * 미결은 여기 없음 — consignment_out(OPEN/PARTIAL/CLOSED)이 단일 진실. 취소는 논리 취소(canceled).
 */
@Entity
@Table(name = "sales")
@org.hibernate.annotations.SQLRestriction("deleted_at is null")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sale extends com.daesung.sales.common.entity.SoftDeletableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sales_no", unique = true, length = 30)
    private String salesNo;

    @Column(name = "sales_date", nullable = false)
    private LocalDate salesDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id", nullable = false)
    private Partner partner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * 출고 창고(7p 재고위치 · 27p 출고창고).
     *
     * <p>지금까지 매출등록에서 창고를 받아 재고만 차감하고 버렸다 — 나중에 "이 매출이 어느 창고에서
     * 나갔나"를 되짚을 수 없었다. 위탁정산 매출은 위탁창고에서 나간다.
     * 이 컬럼이 생기기 전 데이터는 알 수 없어 null이다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    /** 매출유형(독립 축): 일반/위탁매출. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sales_type", nullable = false, length = 20)
    private SalesType salesType;

    /** 출고유형(6종). */
    @Enumerated(EnumType.STRING)
    @Column(name = "shipment_type", nullable = false, length = 20)
    private ShipmentType shipmentType;

    /** 구분(회계): 매출/무상/반품. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sales_category", nullable = false, length = 10)
    private SalesCategory salesCategory;

    @Column(name = "unit_price")
    private Integer unitPrice;

    @Column(name = "supply_rate")
    private Integer supplyRate;

    /**
     * 적용된 권당 할인액(34p). 값이 있으면 <b>공급가액 = (정가−할인액) × 수량</b>이고
     * 공급률은 금액에 쓰이지 않는다(레거시 매출가져오기.vb:425 — 둘 중 하나만 쓴다).
     *
     * <p>거래처×대분류 매핑에서 오지만 매핑은 언제든 바뀌므로, 정가·공급률과 같은 이유로
     * 매출 라인에 복사해 둔다 — 그래야 "이 건의 금액이 왜 이 값인가"를 나중에 설명할 수 있다.
     */
    @Column(name = "discount_amount")
    private Integer discountAmount;

    /** 수량(반품/취소는 음수). */
    @Column(nullable = false)
    private int qty;

    @Column(name = "supply_amount")
    private Long supplyAmount;

    private Long tax;

    @Column(name = "total_amount")
    private Long totalAmount;

    /** 성적처리 구분(37p 월별매출액명세서 인원 집계축). null=비처리로 간주. */
    @Enumerated(EnumType.STRING)
    @Column(name = "proc_type", length = 20)
    private ProcType procType;

    /** 원본 위탁출고번호(위탁정산 매출일 때). */
    @Column(name = "source_out_no", length = 30)
    private String sourceOutNo;

    /** 위탁 정산 이력 링크(위탁정산 매출일 때). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id")
    private ConsignmentSettlement settlement;

    @Column(length = 1000)
    private String memo;

    /**
     * DSRE2 주문번호(REQ_CD). 주문에서 생성된 매출만 채운다 — 일반 매출은 null.
     *
     * <p>★{@link #bulkImportKey} 에도 주문번호가 들어 있지만 <b>문자열 안에 섞여 있어</b>
     * 조인도 색인도 못 한다(형식도 두 가지다). 그건 멱등 dedup 용이고, 이건 조회 축이다.
     */
    @Column(name = "req_cd")
    private Integer reqCd;

    /** DSRE 매출일괄등록 소스키(req_cd:lst_cd:dtl_cd:req_gn). 멱등 dedup용. 일반 매출은 null. */
    @Column(name = "bulk_import_key", length = 60)
    private String bulkImportKey;

    /**
     * 거래분류(레거시 {@code salesData.tradeClass}) — <b>발송과 잇는 키</b>.
     *
     * <p>상품의 세부구분을 <b>등록 시점에 베껴 둔다</b>. 레거시가 그랬고, 이유가 있다 —
     * 상품 마스터의 세부구분은 나중에 바뀔 수 있는데, 그때 과거 발송과의 연결이 끊기면 안 된다.
     * ‼️이 칸이 없어서 발송↔매출을 <b>상품의 세부구분</b>으로 맞추고 있었고,
     * 발송 한 건에 여러 세부구분이 섞이는 탓에 작업결과가 2,219건 중 1,878건에서
     * 수량·출고창고를 못 찾았다(프론트 실측 2026-10-22).
     * 이관분은 레거시 값으로 채웠고, 비어 있으면 상품 세부구분으로 떨어진다.
     */
    @Column(name = "trade_class", length = 20)
    private String tradeClass;

    /** 학교/학원 코드(레거시 salesData.schCode). 매출 업로드 등에서 세부 거래단위. */
    @Column(name = "school_code", length = 30)
    private String schoolCode;

    /** 학교/학원명(레거시 salesData.schName). */
    @Column(name = "school_name", length = 100)
    private String schoolName;

    /** 세트 상품 회차(레거시 salesData.bookReqSeq). */
    /** 포장구분(개별1/개별2/반별) — IC회차별작업현황 집계축. 근거: 레거시 distData.packType. */
    @Enumerated(EnumType.STRING)
    @Column(name = "pack_type", length = 20)
    private PackType packType;

    @Column(name = "book_round")
    private Integer bookRound;

    /** 논리 취소 여부(물리삭제 아님, 이력 보존). */
    @Column(nullable = false)
    private boolean canceled = false;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    /** 일반(직접) 매출 라인 생성. 위탁 정산 매출은 별도(from-consign)로 생성. procType은 성적처리 구분(nullable). */
    public static Sale create(String salesNo, LocalDate salesDate, Partner partner, Product product,
                              SalesType salesType, ShipmentType shipmentType, SalesCategory salesCategory,
                              Integer unitPrice, Integer supplyRate, int qty,
                              Long supplyAmount, Long tax, Long totalAmount, ProcType procType, String memo) {
        Sale s = new Sale();
        s.salesNo = salesNo;
        s.salesDate = salesDate;
        s.partner = partner;
        s.product = product;
        // 발송과 잇는 키 — 등록 시점의 세부구분을 베껴 둔다(위 필드 주석 참고).
        s.tradeClass = (product == null) ? null : product.getSalesDivision();
        s.salesType = salesType;
        s.shipmentType = shipmentType;
        s.salesCategory = salesCategory;
        s.unitPrice = unitPrice;
        s.supplyRate = supplyRate;
        s.qty = qty;
        s.supplyAmount = supplyAmount;
        s.tax = tax;
        s.totalAmount = totalAmount;
        s.procType = procType;
        s.memo = memo;
        return s;
    }

    /** 위탁 정산 확정 매출 라인. sales_type=위탁, shipment_type=위탁출고, 회계=매출. */
    /**
     * <b>조회 전용</b> — 위탁 미결({@code ConsignmentOut})을 출고/반품 조회에 보여주기 위한
     * <b>저장하지 않는</b> 매출 모양 객체.
     *
     * <p>근거: 10/27 명세 §4-3 "위탁출고도 실제 출고다. <b>정산과 관계없이 등록(출고) 시점에</b>
     * 출고/반품 조회에 잡혀야 한다" · §4-4 "미결잔고 50 중 40을 정산 확정하면
     * <b>출고/반품에는 50부</b>(실제 출고)가 있어야 한다".
     *
     * <p>★왜 Sale 모양으로 만드나 — 출고/반품 조회 응답({@code SaleResponse})은 47개 칸이고
     * 거래처·학교·상품 마스터에서 붙는 값이 많다. 위탁 행을 손으로 만들면 칸이 늘 때마다
     * 어긋난다. 같은 매핑을 그대로 태우면 따라올 수밖에 없다.
     *
     * <p>‼️<b>절대 저장하지 않는다.</b> 저장하면 매출 원장에 없는 거래가 생겨 이중 장부가
     * 깨진다 — 위탁출고는 매출이 아니라 <b>미결</b>이고, 매출은 정산에서 확정된다.
     * {@code id} 가 {@code null} 이라 {@code /sales/{id}} 경로도 탈 수 없다.
     *
     * @param qty <b>원출고 수량</b>을 넣는다(정산·반품으로 줄어든 잔여가 아니다) — §4-4 그대로.
     */
    public static Sale forConsignmentView(String sourceOutNo, LocalDate tradeDate, Partner partner,
                                          Product product, Integer unitPrice, Integer supplyRate,
                                          int qty, String schoolCode, String schoolName,
                                          Warehouse consignWarehouse) {
        Sale s = new Sale();
        s.salesNo = sourceOutNo;          // 매출번호 자리에 위탁출고번호(OUT-)
        s.salesDate = tradeDate;
        s.partner = partner;
        s.product = product;
        s.tradeClass = (product == null) ? null : product.getSalesDivision();
        s.salesType = SalesType.CONSIGN_SALES;
        s.shipmentType = ShipmentType.CONSIGN_SHIP;
        s.salesCategory = SalesCategory.SALE;
        s.unitPrice = unitPrice;
        s.supplyRate = supplyRate;
        s.qty = qty;
        // 금액은 0이다 — 위탁출고는 매출이 아니다. 금액은 정산에서 정해진다.
        s.supplyAmount = 0L;
        s.tax = 0L;
        s.totalAmount = 0L;
        s.schoolCode = schoolCode;
        s.schoolName = schoolName;
        s.warehouse = consignWarehouse;   // 명세 §4-A "'위탁출고 · 위탁창고'로 표시"
        s.sourceOutNo = sourceOutNo;
        return s;
    }

    public static Sale createConsign(String salesNo, LocalDate salesDate, Partner partner, Product product,
                                     Integer unitPrice, Integer supplyRate, int qty,
                                     Long supplyAmount, Long tax, Long totalAmount,
                                     String sourceOutNo, ConsignmentSettlement settlement, String memo) {
        Sale s = new Sale();
        s.salesNo = salesNo;
        s.salesDate = salesDate;
        s.partner = partner;
        s.product = product;
        s.tradeClass = (product == null) ? null : product.getSalesDivision();
        s.salesType = SalesType.CONSIGN_SALES;
        s.shipmentType = ShipmentType.CONSIGN_SHIP;
        s.salesCategory = SalesCategory.SALE;
        s.unitPrice = unitPrice;
        s.supplyRate = supplyRate;
        s.qty = qty;
        s.supplyAmount = supplyAmount;
        s.tax = tax;
        s.totalAmount = totalAmount;
        s.sourceOutNo = sourceOutNo;
        s.settlement = settlement;
        s.memo = memo;
        return s;
    }

    /** DSRE 매출일괄등록 라인. 재무 매출(재고 미반영). bulkImportKey로 멱등. */
    public static Sale createBulk(String salesNo, LocalDate salesDate, Partner partner, Product product,
                                  ShipmentType shipmentType, SalesCategory salesCategory,
                                  Integer unitPrice, Integer supplyRate, int qty,
                                  Long supplyAmount, Long tax, Long totalAmount, String memo, String bulkImportKey) {
        Sale s = new Sale();
        s.salesNo = salesNo;
        s.salesDate = salesDate;
        s.partner = partner;
        s.product = product;
        s.salesType = SalesType.NORMAL_SALES;
        s.shipmentType = shipmentType;
        s.salesCategory = salesCategory;
        s.unitPrice = unitPrice;
        s.supplyRate = supplyRate;
        s.qty = qty;
        s.supplyAmount = supplyAmount;
        s.tax = tax;
        s.totalAmount = totalAmount;
        s.memo = memo;
        s.bulkImportKey = bulkImportKey;
        return s;
    }

    /** 반품 라인에 원본 출고번호 링크(역추적용). */
    public void linkSourceOut(String sourceOutNo) {
        this.sourceOutNo = sourceOutNo;
    }

    /** 매출 업로드 세부(학교·회차) 설정. */
    /** 포장구분 지정(물류 작업현황 집계용). */
    /** 출고 창고 지정(매출등록·위탁정산에서 호출). */
    /**
     * 접수경로(ONLINE/MANUAL) — 프론트 I-2. 비면 수기로 본다({@link OrderChannel#orDefault}).
     * 근거: 10/27 §2-1 "수기 주문과 온라인(특약점) 주문을 <b>함께</b> 보여주고".
     */
    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "channel", length = 10)
    private OrderChannel channel;

    /** 접수경로를 지정한다. 특약점 접수는 ONLINE, 매출등록은 보낸 값(없으면 비움=수기). */
    public void applyChannel(OrderChannel channel) {
        this.channel = channel;
    }

    public void applyWarehouse(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    /** 적용된 할인액 기록(매출등록·업로드·위탁정산·반품입고에서 호출). 할인 없으면 호출하지 않는다. */
    public void applyDiscount(Integer discountAmount) {
        this.discountAmount = discountAmount;
    }

    public void applyPackType(PackType packType) {
        this.packType = packType;
    }

    /** 주문에서 생성된 매출임을 표시한다(일괄등록·더프). */
    public void linkOrder(Integer reqCd) {
        this.reqCd = reqCd;
    }

    public void applyUploadDetail(String schoolCode, String schoolName, Integer bookRound) {
        this.schoolCode = schoolCode;
        this.schoolName = schoolName;
        this.bookRound = bookRound;
    }

    /**
     * 매출 수정(오입력 정정, 2026-10-08 [A]2). 금액은 호출자가
     * {@code Amounts} 로 산출한 값을 넘긴다 — <b>금액 계산은 한 곳(Amounts)만</b>이라는 규율이다.
     *
     * <p>바꾸지 않을 항목은 호출자가 기존 값을 그대로 넘긴다. 여기서 null 판정을 하지 않는 이유는,
     * 금액이 수량·정가·공급률·할인액의 함수라 <b>확정된 값 전부</b>를 받아야 산출이 맞기 때문이다.
     */
    public void applyRevision(int qty, Integer unitPrice, Integer supplyRate, Integer discountAmount,
                              long supplyAmount, long tax, long totalAmount,
                              String memo, Integer bookRound, ProcType procType, PackType packType) {
        this.qty = qty;
        this.unitPrice = unitPrice;
        this.supplyRate = supplyRate;
        this.discountAmount = discountAmount;
        this.supplyAmount = supplyAmount;
        this.tax = tax;
        this.totalAmount = totalAmount;
        this.memo = memo;
        this.bookRound = bookRound;
        this.procType = procType;
        this.packType = packType;
        // ‼️여기 들어오는 금액은 **계산값**이다. 그래서 '손으로 고친 금액' 표시를 내려야 한다.
        //   안 내리면 수량만 고친 뒤에도 화면이 계속 「손으로 고친 금액」으로 표시하고,
        //   세무 소명에서 묻는 "계산값인가 손으로 맞춘 값인가"가 틀어진다
        //   (프론트 실측 2026-10-16 B-1). 직접 입력이 함께 온 경우는 호출부가
        //   이 뒤에 overrideSupplyAmount 를 불러 다시 true 로 올린다.
        this.amountOverridden = false;
    }

    /**
     * 공급가액을 담당자가 <b>직접 입력</b>했나(현장 피드백 2026-10-14 B-3).
     *
     * <p>★금액만 저장하면 "계산값과 우연히 같은 건"과 "손으로 맞춘 건"을 나중에 가릴 수 없다.
     * 세무 소명에서 묻는 것이 그 구분이고, 화면도 이 값으로 「손으로 고친 금액」을 표시한다.
     */
    @Column(name = "amount_overridden", nullable = false)
    private boolean amountOverridden = false;

    /**
     * 등록 묶음번호(E-yyyyMMdd-n) — <b>한 번의 등록</b>을 가리킨다(B-4).
     * 매출번호(I-)는 줄마다 따로 채번돼 묶음을 표현하지 못한다.
     */
    @Column(name = "entry_no", length = 30)
    private String entryNo;

    /** 그 묶음 안의 등록 순번(1부터). 담당자가 친 순서다. */
    @Column(name = "entry_seq")
    private Integer entrySeq;

    /**
     * 거래순번 — 그 거래처의 그 날 <b>몇 번째 주문</b>인가(10/27 명세 §3-6, 1부터).
     *
     * <p>{@link #entrySeq}(묶음 <b>안</b>의 줄 번호)와 다르다. 이쪽은 <b>묶음 자체</b>의
     * 번호다 — 한 번 등록하면 그 안의 모든 줄이 같은 값을 갖는다.
     * 조회 조건 "거래처 + 거래일자 + 거래순번"(프론트 B-2③)이 이 값을 본다.
     */
    @Column(name = "trade_seq")
    private Integer tradeSeq;

    /**
     * 「매출만(배송X)」 체크 여부 — 담당자가 등록할 때 고른 값(프론트 B-4④).
     *
     * <p>‼️예전엔 요청에서만 쓰고 <b>버렸다</b>(작업요청서를 만들지 말지 판단하는 데만 썼다).
     * 그래서 주문을 불러와 고칠 때 체크 상태를 되살릴 수 없었다 — 다시 등록하면 풀린 채 저장된다.
     *
     * <p>★<b>"발송이 없는 매출"로 유추하면 안 된다.</b> 특강·창고없음 건도 작업요청서를
     * 만들지 않아 같이 걸린다. "담당자가 체크했다"와 "결과적으로 발송이 없다"는 다른 말이다.
     *
     * <p>이 컬럼 도입 전 매출은 {@code null} — 조회·수정에서는 '체크 안 함'으로 본다.
     */
    @Column(name = "only_sales")
    private Boolean onlySales;

    /** 등록 묶음·순번 기록. 한 번의 등록에서 줄마다 1,2,3… 으로 올린다. */
    public void applyEntry(String entryNo, int entrySeq) {
        this.entryNo = entryNo;
        this.entrySeq = entrySeq;
    }

    /** 거래순번(주문 단위). 등록 1회당 한 번 정해 그 묶음의 모든 줄에 같은 값을 준다. */
    public void applyTradeSeq(int tradeSeq) {
        this.tradeSeq = tradeSeq;
    }

    /** 「매출만(배송X)」 체크 여부. 등록 1회당 한 번 정해 그 묶음의 모든 줄에 같은 값을 준다. */
    public void applyOnlySales(boolean onlySales) {
        this.onlySales = onlySales;
    }

    /** 공급가액을 입력값으로 덮는다(계산값 대신). 총액도 세액과 합쳐 다시 맞춘다. */
    public void overrideSupplyAmount(long supplyAmount) {
        this.supplyAmount = supplyAmount;
        this.totalAmount = supplyAmount + (this.tax == null ? 0L : this.tax);
        this.amountOverridden = true;
    }

    /** 논리 취소. */
    public void cancel() {
        this.canceled = true;
        this.canceledAt = LocalDateTime.now();
    }
    /**
     * 법인 축(V125). 그 **상품**을 따라간다 — 레거시는 법인별 DB 였고 매출은 그 DB 의 도서를 팔았다.
     *
     * <p>★조회 조건에는 아직 쓰이지 않는다 — 다음 단계(법인별 조회)에서 붙는다.
     *   값만 등록 시점에 채워 둔다(안 채우면 다음 단계에서 또 백필해야 한다).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "corp", length = 10)
    private com.daesung.sales.common.corp.Corp corp;

    /** 법인 축 자동 채움. 상품이 없으면(있을 수 없지만) 비워 둔다 — 근거 없이 연구소로 뭉개지 않는다. */
    @PrePersist
    void fillCorp() {
        if (corp == null) {
            corp = (product != null) ? product.getCorp() : null;
        }
    }

}
