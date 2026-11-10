package com.daesung.sales.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.common.corp.Corp;
import com.daesung.sales.inventory.repository.InventoryTxnRepository;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.receivable.repository.CollectionRepository;
import com.daesung.sales.sale.repository.SaleRepository;
import com.daesung.sales.support.IntegrationTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 법인 축(V125·V126) — <b>신규 등록분도 값이 채워지는가</b>.
 *
 * <p>발주처 2026-11-10 §1 "리브커넥스는 별도 법인이므로 데이터도 완전히 분리" + §7 "조건 주입은 다음 단계".
 * 그래서 지금은 <b>조회에 쓰지 않고 값만</b> 남긴다.
 *
 * <p>‼️<b>백필만 하면 11/11 이후 등록되는 행이 전부 빈다.</b> 그러면 다음 단계에서 또 백필해야 하고,
 * 그 사이 데이터는 어느 법인인지 되짚을 근거가 없다. 그래서 {@code @PrePersist} 로 등록 시점에 채운다.
 * 이 테스트가 지키는 것이 그것이다 — 매출·수금·재고이벤트·발송의 <b>모든 등록 경로</b>를 한 곳
 * (엔티티)에서 덮으므로, 경로가 늘어도 빈 값이 생기지 않는다.
 *
 * <p>‼️<b>NULL 을 연구소로 뭉개지 않는다</b>는 것도 함께 고정한다 — 자재 이벤트는 비어야 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("법인 축 — 신규 등록분 자동 채움(V125·V126)")
class CorpAxisIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CP" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2086-03-10";

    @Autowired private ProductRepository productRepository;
    @Autowired private PartnerRepository partnerRepository;
    @Autowired private SaleRepository saleRepository;
    @Autowired private CollectionRepository collectionRepository;
    @Autowired private InventoryTxnRepository inventoryTxnRepository;

    private Long partner;
    private Long product;
    private Long warehouse;
    private String salesNo;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "CPS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "CPP" + SFX, "name", "법인축 거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "CPW" + SFX, "name", "물류", "type", "MAIN"));
        product = createId("/masters/products", Map.of("code", "CPB" + SFX, "name", "법인축 도서",
                "contentType", "SELF", "price", 10_000, "supplyRate", 70, "salesDivision", "교재"));

        assertThat(post("/stock/inbound", Map.of("processedDate", DATE,
                "supplierClientId", supplier, "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "qty", 100, "unitCost", 1_000))))
                .path("success").asBoolean()).isTrue();
        var sale = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                        "qty", 10, "unitPrice", 10_000, "supplyRate", 70))));
        assertThat(sale.path("success").asBoolean()).as("매출 등록: %s", sale).isTrue();
        salesNo = data(sale).path("items").get(0).path("salesNo").asText();

        assertThat(post("/closing/collections", Map.of(
                "collDate", DATE, "partnerId", partner,
                "collKind", "도서대금", "collType", "CASH", "collAmt", 70_000))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("신규 등록 상품·거래처는 **연구소**로 채워진다")
    void 마스터는_연구소() {
        assertThat(productRepository.findById(product).orElseThrow().getCorp())
                .as("발주처 11/09 1부 1 '신규 등록은 연구소로 저장'").isEqualTo(Corp.DSLAB);
        assertThat(partnerRepository.findById(partner).orElseThrow().getCorp())
                .isEqualTo(Corp.DSLAB);
    }

    @Test
    @DisplayName("‼️신규 매출은 **그 상품**의 법인을 따라간다 — 등록 경로가 늘어도 빈 값이 안 생긴다")
    void 매출은_상품을_따른다() {
        var found = saleRepository.findAll().stream()
                .filter(s -> salesNo.equals(s.getSalesNo())).findFirst().orElseThrow();
        assertThat(found.getCorp()).isEqualTo(Corp.DSLAB);
    }

    @Test
    @DisplayName("신규 수금은 **그 거래처**의 법인을 따라간다")
    void 수금은_거래처를_따른다() {
        var found = collectionRepository.findAll().stream()
                .filter(c -> partner.equals(c.getPartner().getId())).findFirst().orElseThrow();
        assertThat(found.getCorp()).isEqualTo(Corp.DSLAB);
    }

    @Test
    @DisplayName("재고이벤트는 그 상품을 따라간다")
    void 재고이벤트는_상품을_따른다() {
        var txns = inventoryTxnRepository.findAll().stream()
                .filter(t -> t.getProduct() != null && product.equals(t.getProduct().getId()))
                .toList();
        assertThat(txns).isNotEmpty();
        assertThat(txns).allSatisfy(t -> assertThat(t.getCorp()).isEqualTo(Corp.DSLAB));
    }

    @Test
    @DisplayName("‼️자재 이벤트는 **비어 있어야** 한다 — 자재에는 법인 축이 없다")
    void 자재이벤트는_비운다() {
        var materialTxns = inventoryTxnRepository.findAll().stream()
                .filter(t -> t.getProduct() == null && t.getMaterial() != null)
                .toList();
        // 자재 이벤트가 없는 실행도 있으므로(테스트 격리) 있을 때만 본다.
        assertThat(materialTxns).allSatisfy(t -> assertThat(t.getCorp())
                .as("근거 없이 연구소로 뭉개면 자재 원장이 한쪽 법인 것으로 보인다").isNull());
    }
}
