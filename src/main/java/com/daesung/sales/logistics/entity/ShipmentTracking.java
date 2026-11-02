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
}
