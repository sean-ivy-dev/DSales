package com.daesung.sales.logistics.entity;

import com.daesung.sales.common.entity.BaseEntity;
import com.daesung.sales.partner.entity.Partner;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.SQLRestriction;

/**
 * 발송(작업) 단위. 레거시 {@code sendData} 대응.
 *
 * <p>매출이 "얼마 팔렸나"라면 이쪽은 <b>"그걸 언제 뽑아서 몇 박스로 언제 보냈나"</b>다.
 * 작업요청서가 값을 채우고 작업결과가 읽는다.
 */
@Entity
@Table(name = "shipment")
@SQLRestriction("deleted_at is null")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 분류(레거시 tradeClass). 매출구분에서 따온다. */
    @Column(name = "trade_class", length = 20)
    private String tradeClass;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /**
     * 거래순번 — 그 거래처의 그 날 <b>몇 번째 주문</b>인가(10/27 명세 §3-6·§8-2).
     * 같은 {@link #entryNo} 에서 생긴 발송은 모두 같은 값을 갖는다.
     */
    @Column(name = "trade_seq", nullable = false)
    private int tradeSeq = 1;

    /**
     * 주문 식별자 = <b>매출등록번호</b>({@code E-20261027-55}).
     *
     * <p>★이 값이 발송 묶음 키에 들어간다 — <b>등록이 다르면 작업요청서도 갈린다</b>
     * (명세 §8-2 "출고 등록을 하면 주문 단위로 작업요청서에 보이게 한다").
     * 예전에는 (일자+거래처+학교+분류)로만 묶어, 같은 날 같은 학교로 다섯 번 주문해도
     * 발송 1건에 합쳐지고 거래순번도 1개뿐이었다.
     *
     * <p>위탁출고는 {@code null} 이다 — 출고번호(OUT-)가 품목마다 생겨 주문 식별자로
     * 쓸 수 없다. NULL 이면 예전처럼 (일자+거래처+학교+분류)로만 묶인다.
     */
    @Column(name = "entry_no", length = 30)
    private String entryNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id", nullable = false)
    private Partner partner;

    @Column(name = "school_code", length = 30)
    private String schoolCode;

    @Column(name = "school_name", length = 100)
    private String schoolName;

    /** 작업요청서 출력 시각 = 작업결과의 '출력' ○. */
    @Column(name = "printed_at")
    private LocalDateTime printedAt;

    /** ⚠️레거시에 쓰기 경로가 없다(읽기만). 우리도 임의로 만들지 않는다. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 작업 확인 시각 = 작업결과의 '확인' ○. 출력 다음 단계다(V73).
     *
     * <p>{@link #completedAt}과 <b>다른 축</b>이다 — 그쪽은 레거시에 쓰기 경로가 없어 늘 비어 있고,
     * 거기에 확인을 얹으면 나중에 레거시 데이터를 대조할 때 완료인지 확인인지 가릴 수 없다.
     */
    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by", length = 50)
    private String acknowledgedBy;

    @Column(name = "box_count", nullable = false)
    private int boxCount;

    @Column(name = "sent_date")
    private LocalDate sentDate;

    @Column(name = "send_memo", length = 1000)
    private String sendMemo;

    @Column(length = 500)
    private String memo;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 발송구분(택배/화물). 근거: 정본 26p 클라이언트 정정 2026-07-25.
     * 생성 시점엔 null로 둔다 — {@link #decideDeliveryTypeIfBlank()}가 명시적 지정·
     * 담당자정보 적용을 끝낸 뒤 기본값(화물, 담당자정보 있으면 택배)을 확정한다
     * (10/27 피드백 §8-6 — "기본값 미해결"이 뒤집혔다).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", length = 10)
    private DeliveryType deliveryType;

    /** 수령인 — 정본 26p "'택배' 선택 시 담당자 정보가 노출". */
    @Column(name = "receiver_name", length = 50)
    private String receiverName;

    @Column(name = "receiver_phone", length = 30)
    private String receiverPhone;

    /**
     * 수령인 이메일 — 10/26 특약점 명세 §4 "담당자 정보"의 세 칸 중 하나(담당자명·연락처·
     * 이메일). <b>그 신청 건에만</b> 저장한다 — 거래처 마스터({@code partners.email1})
     * 원본은 UPDATE하지 않는다.
     */
    @Column(name = "receiver_email", length = 100)
    private String receiverEmail;

    /** 택배사(예: CJ대한통운). 수기·엑셀 일괄 입력. ⚠️택배사 API 연동은 하지 않는다. */
    @Column(name = "courier_name", length = 30)
    private String courierName;

    /** 송장번호. 수기·엑셀 일괄 입력. */
    @Column(name = "tracking_no", length = 50)
    private String trackingNo;

    /**
     * <b>접수 단계</b>(접수완료·상품검수)만 저장한다. 비어 있으면 아래 날짜 파생을 쓴다.
     *
     * <p>근거: 10/27 명세 §1(새 탭 주문은 매출 주문으로 저장) · §2-8(선택지는
     * 접수완료/상품검수/상품준비중) · 발주처 1차회신 #4(6단계, 상품검수 포함).
     *
     * <p>★<b>발송준비중·발송완료는 여기 담지 않는다.</b> 그 둘은 지금처럼 출력·발송일에서
     * 파생한다 — 이관분 46,218건이 그 파생에 기대고 있어 축을 옮기면 전건이 틀어진다.
     * 즉 이 컬럼은 "날짜가 아직 아무것도 없는 구간"을 둘로 쪼개는 역할만 한다.
     */
    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "intake_state", length = 20)
    private com.daesung.sales.dsre.gateway.OrderState intakeState;

    /**
     * 진행상태 6단계 중 이 발송이 어디인지. 발주처 확정(2026-10-23 #17) —
     * "진행상태 한 컬럼 6단계 / 완료·삭제 컬럼은 진행상태로 통합".
     *
     * <p>판정 순서가 곧 우선순위다. 앞에서 걸리면 뒤는 보지 않는다.
     * <pre>
     *   삭제됨            → 삭제(CANCELED)      레거시 sendData.isDelete 가 여기로 왔다
     *   발송일 있음        → 발송완료(SHIPPED)
     *   출력됨            → 발송준비중(READY_TO_SHIP)   거래명세서를 뽑은 시점
     *   그 밖            → 상품준비중(PREPARING)
     * </pre>
     *
     * <p>★<b>정정(2026-11-02)</b>: 예전엔 "접수완료·상품검수는 여기서 나오지 않는다 —
     * 그 둘은 DSRE2 소관"이었다. 10/27 명세 §1이 <b>새 특약점 주문을 매출 주문으로
     * 저장</b>하기로 바꿔, 그 접수 단계가 <b>우리 몫</b>이 됐다({@link #intakeState}).
     * 날짜가 아직 아무것도 없는 구간에서만 그 값이 쓰이고, 비어 있으면 종전처럼
     * 상품준비중이다 — 이관분은 전부 비어 있어 영향이 없다.
     *
     * <p>‼️'완료'(completedAt)는 판정에 쓰지 않는다. 이관분 46,218건이 가짜 1900-01-01 로
     * 채워져 사실상 전건 참이었고(V97 에서 비웠다), 레거시에서 실제로 쓰인 건 13건뿐이다.
     * 발주처도 "완료인데 출력·발송일 없는 건 → 전환 로직 확인"이라고 짚었다.
     */
    public com.daesung.sales.dsre.gateway.OrderState progressState() {
        if (getDeletedAt() != null) {
            return com.daesung.sales.dsre.gateway.OrderState.CANCELED;
        }
        if (sentDate != null) {
            return com.daesung.sales.dsre.gateway.OrderState.SHIPPED;
        }
        if (printedAt != null) {
            return com.daesung.sales.dsre.gateway.OrderState.READY_TO_SHIP;
        }
        // ★접수 단계는 **물류가 아직 아무것도 안 한** 구간에서만 이긴다.
        //   물류가 출력·발송을 했으면 위에서 이미 걸렸다 — 접수 단계가 물류 진행을
        //   되돌리는 일은 없어야 한다(담당자가 상태를 A 로 되돌려도 발송완료는 유지된다).
        if (intakeState != null) {
            return intakeState;
        }
        return com.daesung.sales.dsre.gateway.OrderState.PREPARING;
    }

    /**
     * 접수 단계를 지정한다. {@code null} 또는 상품준비중이면 <b>컬럼을 비워</b>
     * 날짜 파생으로 되돌린다 — 상품준비중은 파생의 기본값이라 따로 저장할 필요가 없고,
     * 저장해 두면 나중에 파생 규칙이 바뀌어도 이 행만 옛 규칙에 묶인다.
     */
    public void applyIntakeState(com.daesung.sales.dsre.gateway.OrderState state) {
        this.intakeState = (state == null
                || state == com.daesung.sales.dsre.gateway.OrderState.PREPARING) ? null : state;
    }

    public static Shipment of(String tradeClass, LocalDate tradeDate, Partner partner,
                              String schoolCode, String schoolName, String memo) {
        return of(tradeClass, tradeDate, partner, schoolCode, schoolName, memo, null);
    }

    /** 주문 식별자({@link #entryNo})까지 함께 받는다 — 매출등록 경로가 쓴다. */
    public static Shipment of(String tradeClass, LocalDate tradeDate, Partner partner,
                              String schoolCode, String schoolName, String memo,
                              String entryNo) {
        Shipment s = new Shipment();
        s.tradeClass = tradeClass;
        s.tradeDate = tradeDate;
        s.partner = partner;
        s.schoolCode = schoolCode;
        s.schoolName = schoolName;
        s.memo = memo;
        s.entryNo = entryNo;
        return s;
    }

    /**
     * 거래순번(10/27 피드백 §3-6·8-2) — "거래처별로 매일 1번부터". 생성 시 1회만
     * 호출한다(필드 기본값 1에 멈춰 있던 걸 실제 채번으로 대체).
     */
    public void assignTradeSeq(int seq) {
        this.tradeSeq = seq;
    }

    /** 작업요청서 출력 처리. 재출력해도 최초 시각을 덮지 않는다 — '언제 처음 지시가 나갔나'가 기록의 의미다. */
    public boolean markPrinted(LocalDateTime at) {
        if (printedAt != null) {
            return false;
        }
        printedAt = at;
        return true;
    }

    /**
     * 출력 표시를 내린다 — 잘못 출력한 건을 되돌리는 경로(B-14).
     *
     * <p>‼️<b>사유는 호출부가 status_history 에 남긴다.</b> "언제 처음 지시가 나갔나"를 지우는
     * 행위라 누가·왜가 없으면 나중에 소명할 수 없다. 여기서는 값만 내린다.
     *
     * @return 실제로 내려갔으면 true(애초에 출력 전이면 false)
     */
    public boolean revertPrinted() {
        if (printedAt == null) {
            return false;
        }
        printedAt = null;
        return true;
    }

    /** 작업 확인 표시. 이미 확인된 건이면 최초 시각·처리자를 덮지 않는다(출력과 같은 규칙). */
    public boolean acknowledge(String by, LocalDateTime at) {
        if (acknowledgedAt != null) {
            return false;
        }
        acknowledgedAt = at;
        acknowledgedBy = by;
        return true;
    }

    /** 확인 표시를 내린다. 사유는 호출부가 status_history 에 남긴다. */
    public boolean revertAcknowledged() {
        if (acknowledgedAt == null) {
            return false;
        }
        acknowledgedAt = null;
        acknowledgedBy = null;
        return true;
    }

    /** 물류 발송정보 입력(작업요청서.vb:915 UPDATE와 동일 항목). null인 항목은 건드리지 않는다. */
    public void updateShipping(Integer boxCount, LocalDate sentDate, String sendMemo) {
        if (boxCount != null) {
            this.boxCount = boxCount;
        }
        if (sentDate != null) {
            this.sentDate = sentDate;
        }
        if (sendMemo != null) {
            this.sendMemo = sendMemo;
        }
    }

    /**
     * 발송구분·수령인 입력(26p). null인 항목은 건드리지 않는다 —
     * 발송구분만 고치려다 수령인이 지워지면 택배가 누구에게 가는지 알 수 없게 된다.
     */
    public void updateDelivery(DeliveryType deliveryType, String receiverName, String receiverPhone,
                               String receiverEmail) {
        if (deliveryType != null) {
            this.deliveryType = deliveryType;
        }
        if (receiverName != null) {
            this.receiverName = receiverName;
        }
        if (receiverPhone != null) {
            this.receiverPhone = receiverPhone;
        }
        if (receiverEmail != null) {
            this.receiverEmail = receiverEmail;
        }
    }

    /**
     * 발송 기록을 내린다(발송취소, 2026-10-08 [A]5).
     *
     * <p>★<b>발송일만 내린다.</b> 박스 수·송장번호·수령인은 그대로 둔다 — 물건을 실제로 싸 둔
     * 사실은 취소되지 않고, 다시 보낼 때 그 값이 필요하다. 지웠다가 담당자가 다시 치게 하면
     * 송장번호 같은 것은 어디서도 복원하지 못한다.
     *
     * <p>이 값이 작업요청서 화면의 '발송완료' 판정({@link #isSent()})이라, 이것을 내리지 않으면
     * DSRE 상태만 되돌아가고 화면은 계속 발송완료로 보인다 — 그게 "발송취소가 안 된다"의 정체였다.
     *
     * @return 실제로 내려갔으면 true(애초에 발송 전이면 false — 이미 원하는 상태다)
     */
    public boolean revertSent() {
        if (this.sentDate == null) {
            return false;
        }
        this.sentDate = null;
        return true;
    }

    /**
     * 주문의 배송구분·담당자를 <b>비어 있을 때만</b> 채운다(2026-10-08 프론트 요청 [A]4).
     *
     * <p>근거: 정본 26p "'택배' 선택 시 담당자 정보가 노출" + 주문 등록 화면의 「택배 발송」 체크.
     * 주문에 이미 들어 있는 값(LGS_GN·담당자·연락처)을 물류가 작업요청서에서 다시 치고 있었다.
     *
     * <p>★<b>이미 들어 있는 값은 덮지 않는다.</b> 발송구분은 "물류가 거래명세서를 보고 판단"하는
     * 항목이고(26p), 한 발송 건에 여러 주문이 묶일 수 있다. 나중 주문이 앞 주문의 판단이나
     * 물류가 손으로 고친 수령인을 덮으면 <b>택배가 엉뚱한 사람에게 간다</b>.
     *
     * @return 한 항목이라도 채웠으면 true
     */
    public boolean fillDeliveryIfBlank(DeliveryType type, String name, String phone, String email) {
        boolean changed = false;
        if (this.deliveryType == null && type != null) {
            this.deliveryType = type;
            changed = true;
        }
        if (isBlank(this.receiverName) && name != null && !name.isBlank()) {
            this.receiverName = name.trim();
            changed = true;
        }
        if (isBlank(this.receiverPhone) && phone != null && !phone.isBlank()) {
            this.receiverPhone = phone.trim();
            changed = true;
        }
        if (isBlank(this.receiverEmail) && email != null && !email.isBlank()) {
            this.receiverEmail = email.trim();
            changed = true;
        }
        return changed;
    }

    /**
     * 발송구분이 아직 없으면 규칙대로 정한다(10/27 피드백 §8-6·작업지침5).
     * 우선순위: ① 이미 값이 있으면 그대로 둔다(명시적 지정이 여기서 이미 반영됐다 —
     * {@link #fillDeliveryIfBlank}를 먼저 호출한 뒤에 이 메서드를 호출하는 것이 전제다).
     * ② {@code courierByReceiverInfo}일 때 담당자정보(수령인명·연락처)가 있으면 택배.
     * ③ 그 외에는 화물(기본값).
     *
     * <p>★<b>생성 시점({@link #of})이 아니라 등록 흐름의 맨 끝에서 호출해야 한다.</b>
     * 생성할 때 바로 화물을 채우면, 뒤이어 담당자정보가 들어와도
     * {@link #fillDeliveryIfBlank}의 "비어있을 때만 채운다" 조건이 이미 거짓이 되어
     * "담당자정보가 있으면 택배"가 영영 못 먹는다 — 그래서 기본값을 둘로 쪼갰다.
     *
     * @param courierByReceiverInfo 담당자정보로 택배를 판정할지. <b>특약점 사이트 주문
     *     (channel=ONLINE)은 false</b> — 발주처 추가요청 J-1(2026-11-03)·명세 §2-10
     *     "특약점 사이트에서는 고르지 않는다". 그 경로는 담당자정보를 거래처 마스터
     *     대표자명·연락처로 <b>자동으로 채우기 때문에</b>(10/26 §4) 이 판정을 그대로 두면
     *     마스터에 대표자명이 있는 거래처(운영 180곳 중 178곳) 주문이 사실상 전부 택배가
     *     된다 — 그게 클라이언트가 §8-6에서 버그로 지적한 "전부 택배" 그 증상이다.
     *     담당자정보는 계속 주문에 저장된다(택배 판정에만 안 쓴다). 택배로 보낼 건은
     *     물류가 작업요청서 「발송 구분」에서 바꾼다.
     */
    public void decideDeliveryTypeIfBlank(boolean courierByReceiverInfo) {
        if (this.deliveryType != null) {
            return;
        }
        boolean hasReceiverInfo = !isBlank(this.receiverName) || !isBlank(this.receiverPhone);
        this.deliveryType = (courierByReceiverInfo && hasReceiverInfo)
                ? DeliveryType.COURIER
                : DeliveryType.FREIGHT;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    /**
     * 박스별 송장 — <b>이쪽이 정본</b>(10/27 명세 §8-3 "송장번호는 박스 수만큼 여러 개").
     *
     * <p>{@link #courierName}·{@link #trackingNo} 는 <b>1번 박스를 비추는 거울</b>로 남겨 둔다.
     * 화면·엑셀 양식·일괄 업로드가 아직 그 단일 필드를 쓰기 때문이다. 둘이 갈리지 않도록
     * <b>갱신은 {@link #replaceTrackings} 한 곳</b>에서만 한다.
     */
    @jakarta.persistence.OneToMany(mappedBy = "shipment",
            cascade = jakarta.persistence.CascadeType.ALL, orphanRemoval = true)
    @jakarta.persistence.OrderBy("boxNo asc")
    private java.util.List<ShipmentTracking> trackings = new java.util.ArrayList<>();

    /**
     * 송장 기록(수기·엑셀 일괄) — 단건. null인 항목은 건드리지 않는다.
     *
     * <p>택배사 API를 부르지 않는다 — 연동 여부가 아직 미확정이라(물류팀 인터뷰 대기)
     * 지금은 사람이 받아 적은 값을 그대로 보관만 한다.
     *
     * <p>★<b>1번 박스로 들어간다.</b> 기존 경로(발송정보 입력·엑셀 업로드)가 송장 하나만
     * 보내므로, 그것이 곧 첫 박스다. 여러 개는 {@link #replaceTrackings} 를 쓴다.
     */
    public void updateTracking(String courierName, String trackingNo) {
        if (courierName == null && trackingNo == null) {
            return;
        }
        String c = (courierName != null) ? courierName : this.courierName;
        String n = (trackingNo != null) ? trackingNo : this.trackingNo;
        if (n == null || n.isBlank()) {
            // 송장번호 없이 택배사만 바꾸는 경우 — 거울만 갱신한다(박스 줄을 만들 수 없다).
            this.courierName = c;
            return;
        }
        replaceTrackings(java.util.List.of(new TrackingLine(1, c, n)), null);
    }

    /** 박스 한 줄의 입력값. */
    public record TrackingLine(int boxNo, String courierName, String trackingNo) {
    }

    /**
     * 박스별 송장을 <b>통째로 교체</b>한다.
     *
     * <p>★줄 단위로 고치지 않고 교체하는 이유 — 박스 수가 줄면 남는 줄을 지워야 하고,
     * 번호가 바뀌면 어느 줄이 어느 박스인지 짝짓기가 애매해진다. 화면도 "표를 통째로 저장"이다.
     *
     * <p>단일 필드({@code courierName}·{@code trackingNo})는 <b>1번 박스를 그대로 비춘다</b> —
     * 여기서만 갱신하므로 자식 테이블과 갈릴 수 없다.
     */
    public void replaceTrackings(java.util.List<TrackingLine> lines, String by) {
        clearTrackings();
        applyTrackings(lines, by);
    }

    /**
     * 송장 줄을 비운다. <b>채우기 전에 반드시 flush 해야 한다</b> —
     * {@code orphanRemoval} 은 DELETE 를 커밋 직전에 내보내서, 그 전에 같은
     * (발송·박스번호)로 INSERT 가 먼저 나가면 유니크 제약에 걸린다(실제로 500 이 났다).
     * 그래서 서비스가 {@code clear → flush → apply} 순서로 부른다.
     */
    public void clearTrackings() {
        this.trackings.clear();
        this.courierName = null;
        this.trackingNo = null;
    }

    /** 비운 뒤 채운다. 단일 필드는 1번 박스를 비춘다. */
    public void applyTrackings(java.util.List<TrackingLine> lines, String by) {
        if (lines != null) {
            // ★번호 순으로 담는다. 화면이 보낸 순서(2,1,3)가 그대로 응답에 나오면
            //   담당자가 박스 순서를 못 믿는다. @OrderBy 는 **다시 읽을 때만** 듣는다.
            java.util.List<TrackingLine> sorted = new java.util.ArrayList<>(lines);
            sorted.sort(java.util.Comparator.comparingInt(TrackingLine::boxNo));
            for (TrackingLine l : sorted) {
                this.trackings.add(ShipmentTracking.of(this, l.boxNo(),
                        l.courierName(), l.trackingNo(), by));
            }
        }
        ShipmentTracking first = this.trackings.isEmpty() ? null : this.trackings.get(0);
        this.courierName = (first == null) ? null : first.getCourierName();
        this.trackingNo = (first == null) ? null : first.getTrackingNo();
    }

    public void markDeleted(String by) {
        this.deletedAt = LocalDateTime.now();
    }

    /** 발송 완료 여부 — 레거시 작업결과가 발송일 유무로 판단한다. */
    public boolean isSent() {
        return sentDate != null;
    }
}
