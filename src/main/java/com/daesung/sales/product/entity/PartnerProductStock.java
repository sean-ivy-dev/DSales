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
 * 특약점 재고현황 — 거래처 × 도서(product_id). 근거: 10/31 클라이언트 회신 §3
 * (10/26 §6 확정) "로직: 현행 그대로다. 구매·반품·재고를 수기로 입력하고, 출고는
 * 구매−재고−반품으로 계산해 표시만 한다 … 저장 위치: 도서 키가 매출 product_id로 바뀐다."
 *
 * <p>★<b>월 축이 없다.</b> 레거시(DSRE2 {@code tbl_booklist_cnt})와 같은 모양으로,
 * 조합당 값이 하나이고 다시 입력하면 <b>덮어쓴다</b>(이력을 남기지 않는다 — 레거시도
 * 안 남긴다). 출고는 이 엔티티에 저장하지 않고 조회 시점에 계산한다
 * ({@code purchaseQty − stockQty − returnQty}).
 *
 * <p>⚠️DSRE2 {@code tbl_booklist_cnt}와는 <b>별개 저장소</b>다 — 도서 키가 DSRE
 * LST_CD/DTL_CD에서 매출 {@code product_id}로 바뀌어 그대로 이어 쓸 수 없다(10/31 문서
 * 지시). DSRE 데스크톱이 보는 값과는 지금부터 갈린다 — 기존 입력값 이관은 별도 회신
 * 대기(§4-3)라 이관 로직을 만들지 않는다.
 */
@Entity
@Table(name = "partner_product_stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartnerProductStock extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id", nullable = false)
    private Partner partner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "purchase_qty", nullable = false)
    private int purchaseQty;

    @Column(name = "return_qty", nullable = false)
    private int returnQty;

    @Column(name = "stock_qty", nullable = false)
    private int stockQty;

    /**
     * 재고(stockQty)를 마지막으로 <b>저장한 날</b>(특약점팀 요청 2026-11-05, V118).
     *
     * <p>★{@code BaseEntity.updatedAt} 과 다르다 — 그쪽은 이관 시딩·매입/반품 변경에도
     * 바뀌는데, 이 값은 <b>재고를 고칠 때만</b> 바뀐다("내가 안 고쳤는데 날짜가 바뀌었다"가
     * 되면 안 된다). 이관 행은 DSRE {@code EDIT_WEB} 값을 그대로 넣는다.
     */
    @Column(name = "stock_updated_on")
    private java.time.LocalDate stockUpdatedOn;

    public static PartnerProductStock create(Partner partner, Product product,
                                              int purchaseQty, int returnQty, int stockQty) {
        PartnerProductStock e = new PartnerProductStock();
        e.partner = partner;
        e.product = product;
        e.apply(purchaseQty, returnQty, stockQty);
        return e;
    }

    /**
     * 현행(레거시)과 같은 덮어쓰기 — null인 항목은 그대로 둔다(부분 입력 허용).
     * <b>재고를 받은 경우에만</b> {@link #stockUpdatedOn} 을 오늘로 올린다.
     */
    public void apply(Integer purchaseQty, Integer returnQty, Integer stockQty) {
        if (purchaseQty != null) {
            this.purchaseQty = purchaseQty;
        }
        if (returnQty != null) {
            this.returnQty = returnQty;
        }
        if (stockQty != null) {
            this.stockQty = stockQty;
            this.stockUpdatedOn = java.time.LocalDate.now();
        }
    }

    /**
     * 이관(베이스라인 시딩) 전용 — 수량을 덮어쓰되 <b>최종수정일은 오늘로 찍지 않는다</b>.
     *
     * <p>‼️{@link #apply}와 결정적으로 다른 점. 거긴 "재고를 저장했다"는 뜻이라 재고를
     * 받으면 오늘로 찍는데, 이관은 특약점이 저장한 게 아니다. 처음엔 이관도 {@code apply}를
     * 써서 <b>DSRE {@code EDIT_WEB}이 비어 있던 행에 오늘 날짜가 찍혔다</b>(2026-11-05
     * 운영 검증에서 잡음) — 특약점 화면이 "안 고쳤는데 오늘 고친 것"으로 보이게 된다.
     *
     * <p>{@code stockUpdatedOn}은 <b>준 값 그대로</b> 설정한다 — {@code null}이면 비운다
     * (DSRE에 값이 없던 행은 우리도 없어야 한다). 이관이 베이스라인 전체를 정의하므로
     * 덮어쓰기가 맞다.
     */
    public void applyBaselineSeed(Integer purchaseQty, Integer returnQty, Integer stockQty,
                                  java.time.LocalDate stockUpdatedOn) {
        if (purchaseQty != null) {
            this.purchaseQty = purchaseQty;
        }
        if (returnQty != null) {
            this.returnQty = returnQty;
        }
        if (stockQty != null) {
            this.stockQty = stockQty;
        }
        this.stockUpdatedOn = stockUpdatedOn;
    }

    /** 출고 = 구매 − 재고 − 반품(저장하지 않고 조회 시 계산, 레거시와 같은 공식). */
    public int shipQty() {
        return purchaseQty - stockQty - returnQty;
    }
}
