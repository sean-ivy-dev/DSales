package com.daesung.sales.logistics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발송 건의 송장 한 줄 — <b>박스마다 하나</b>(10/27 명세 §8-3, 프론트 E-2).
 *
 * <p>명세: "주문은 한 줄이지만 <b>송장번호는 박스 수만큼 여러 개</b> 등록할 수 있게 한다."
 *
 * <p>‼️레거시에는 송장 개념이 <b>아예 없다</b>({@code sendData} 에 BoxCount 만 있다).
 * 따를 선례가 없어 명세 문장만을 기준으로 만들었다.
 */
@Entity
@Table(name = "shipment_tracking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    /** 박스 번호(1부터). 한 발송 안에서 유일하다. */
    @Column(name = "box_no", nullable = false)
    private int boxNo;

    @Column(name = "courier_name", length = 50)
    private String courierName;

    @Column(name = "tracking_no", length = 50)
    private String trackingNo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    static ShipmentTracking of(Shipment shipment, int boxNo, String courierName,
                               String trackingNo, String by) {
        ShipmentTracking t = new ShipmentTracking();
        t.shipment = shipment;
        t.boxNo = boxNo;
        t.courierName = courierName;
        t.trackingNo = trackingNo;
        t.createdAt = LocalDateTime.now();
        t.createdBy = by;
        return t;
    }

    /**
     * 같은 줄을 <b>제자리에서</b> 고친다 — {@code null}이 아닌 항목만.
     *
     * <p>★{@link Shipment#updateTracking}이 이 메서드로 1번 박스를 고치는 이유 —
     * {@link Shipment#clearTrackings}로 지우고 다시 넣으면(delete+insert) orphanRemoval의
     * DELETE가 커밋 직전에야 나가는데 그 전에 같은 (shipment·박스번호)로 INSERT가 먼저
     * 나가 유니크 제약(`ux_shipment_tracking_box`)에 걸린다(실제로 재현됨 — 같은 발송
     * 건에 단건 송장 입력을 두 번째로 부르면 500). 제자리 UPDATE는 그 경합이 없다.
     */
    void apply(String courierName, String trackingNo) {
        if (courierName != null) {
            this.courierName = courierName;
        }
        if (trackingNo != null) {
            this.trackingNo = trackingNo;
        }
    }
}
