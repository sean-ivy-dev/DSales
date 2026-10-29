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

    @Column(name = "trade_seq", nullable = false)
    private int tradeSeq = 1;

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
     * ⚠️기본값을 두지 않는다 — 정본이 "자동 기본값이 있는지"를 미해결로 남겼다.
     * 임의로 정하면 물류가 고르지 않은 건까지 한쪽으로 발송된 것처럼 보인다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", length = 10)
    private DeliveryType deliveryType;

    /** 수령인 — 정본 26p "'택배' 선택 시 담당자 정보가 노출". */
    @Column(name = "receiver_name", length = 50)
    private String receiverName;

    @Column(name = "receiver_phone", length = 30)
    private String receiverPhone;

    /** 택배사(예: CJ대한통운). 수기·엑셀 일괄 입력. ⚠️택배사 API 연동은 하지 않는다. */
    @Column(name = "courier_name", length = 30)
    private String courierName;

    /** 송장번호. 수기·엑셀 일괄 입력. */
    @Column(name = "tracking_no", length = 50)
    private String trackingNo;

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
     * <p>‼️<b>접수완료·상품검수는 여기서 나오지 않는다.</b> 그 둘은 DSRE2 소관(주문 접수 단계)이고,
     * 작업결과는 <b>출고 작업 목록</b>이라 매출·출고가 선 뒤에야 행이 생긴다 —
     * 가장 이른 단계가 상품준비중이다.
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
        return com.daesung.sales.dsre.gateway.OrderState.PREPARING;
    }

    public static Shipment of(String tradeClass, LocalDate tradeDate, Partner partner,
                              String schoolCode, String schoolName, String memo) {
        Shipment s = new Shipment();
        s.tradeClass = tradeClass;
        s.tradeDate = tradeDate;
        s.partner = partner;
        s.schoolCode = schoolCode;
        s.schoolName = schoolName;
        s.memo = memo;
        return s;
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
    public void updateDelivery(DeliveryType deliveryType, String receiverName, String receiverPhone) {
        if (deliveryType != null) {
            this.deliveryType = deliveryType;
        }
        if (receiverName != null) {
            this.receiverName = receiverName;
        }
        if (receiverPhone != null) {
            this.receiverPhone = receiverPhone;
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
    public boolean fillDeliveryIfBlank(DeliveryType type, String name, String phone) {
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
        return changed;
    }

    /**
     * 발송구분이 아직 없으면 규칙대로 정한다(10/27 피드백 §8-6·작업지침5).
     * 우선순위: ① 이미 값이 있으면 그대로 둔다(명시적 지정이 여기서 이미 반영됐다 —
     * {@link #fillDeliveryIfBlank}를 먼저 호출한 뒤에 이 메서드를 호출하는 것이 전제다).
     * ② 담당자정보(수령인명·연락처)가 있으면 택배. ③ 그 외에는 화물(기본값).
     *
     * <p>★<b>생성 시점({@link #of})이 아니라 등록 흐름의 맨 끝에서 호출해야 한다.</b>
     * 생성할 때 바로 화물을 채우면, 뒤이어 담당자정보가 들어와도
     * {@link #fillDeliveryIfBlank}의 "비어있을 때만 채운다" 조건이 이미 거짓이 되어
     * "담당자정보가 있으면 택배"가 영영 못 먹는다 — 그래서 기본값을 둘로 쪼갰다.
     */
    public void decideDeliveryTypeIfBlank() {
        if (this.deliveryType != null) {
            return;
        }
        this.deliveryType = (!isBlank(this.receiverName) || !isBlank(this.receiverPhone))
                ? DeliveryType.COURIER
                : DeliveryType.FREIGHT;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    /**
     * 송장 기록(수기·엑셀 일괄). null인 항목은 건드리지 않는다.
     *
     * <p>택배사 API를 부르지 않는다 — 연동 여부가 아직 미확정이라(물류팀 인터뷰 대기)
     * 지금은 사람이 받아 적은 값을 그대로 보관만 한다.
     */
    public void updateTracking(String courierName, String trackingNo) {
        if (courierName != null) {
            this.courierName = courierName;
        }
        if (trackingNo != null) {
            this.trackingNo = trackingNo;
        }
    }

    public void markDeleted(String by) {
        this.deletedAt = LocalDateTime.now();
    }

    /** 발송 완료 여부 — 레거시 작업결과가 발송일 유무로 판단한다. */
    public boolean isSent() {
        return sentDate != null;
    }
}
