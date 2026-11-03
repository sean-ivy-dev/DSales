package com.daesung.sales.product.entity;

import com.daesung.sales.common.entity.BaseEntity;
import com.daesung.sales.partner.entity.Partner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 거래처 × 도서 노출 예외. 근거: 10/31 클라이언트 회신(10/26 §3·§9-1 대체) §1 —
 * "노출 판단 순서: ①도서 Web게시 N → 비노출(예외보다 우선) ②이 거래처×도서에 예외가 있음
 * → 열기=노출/닫기=비노출 ③거래처×대분류 매핑(use_yn=Y,미삭제) → 그 행의 web_visible
 * ④매핑 행 없음 → 비노출".
 *
 * <p>실제 판정 쿼리는 {@link com.daesung.sales.product.repository.ProductRepository#findDealerVisible}
 * 한 곳에만 있다 — 특약점 사이트의 신청 화면·접수·수정·재고현황 전부가 이 쿼리를 쓴다
 * (10/31 회신 §1-5 "노출 계산은 매출 API 한 곳에서 한다").
 *
 * <p>★<b>행이 없으면 "예외 없음"</b>이다(③④로 떨어진다). 예외를 해제하면 행을 <b>지운다</b>
 * (회신 원문 "예외를 해제하면 행을 지운다") — soft delete를 쓰지 않는다. 변경 이력(누가·언제·
 * 열기/닫기)은 {@code StatusHistoryService}(PARTNER_PRODUCT_VISIBILITY)에 별도로 남긴다.
 */
@Entity
@Table(name = "partner_product_visibility")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartnerProductVisibility extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id", nullable = false)
    private Partner partner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** true=열기(매핑이 없거나 닫혀 있어도 강제 노출) / false=닫기(매핑이 열려 있어도 강제 비노출). */
    @Column(name = "visible", nullable = false)
    private boolean visible;

    public static PartnerProductVisibility create(Partner partner, Product product, boolean visible) {
        PartnerProductVisibility e = new PartnerProductVisibility();
        e.partner = partner;
        e.product = product;
        e.visible = visible;
        return e;
    }

    public void apply(boolean visible) {
        this.visible = visible;
    }
}
