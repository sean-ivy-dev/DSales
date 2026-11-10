package com.daesung.sales.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * <b>단품에 직접 붙인 자재</b>가 출고·폐기에서 빠지는가(프론트 작업중 §3 ①② · 2026-11-10).
 *
 * <p>근거 둘:
 * <ul>
 *   <li>발주처 <b>F-37 ①</b> — "회차 도서를 <b>'단품'으로 둔 채</b> 자재 연결, 세트 구성 포함,
 *       세트 조립·해체가 모두 되게 해 주세요"</li>
 *   <li>발주처 <b>F-37 ④⑤</b> — "회차 단독 출고 시 그 회차 <b>자재 낱장이 빠지는지</b>,
 *       회차 단독 재고가 자재 잔여로 표시되는지 확인할 수 있게 해 주세요"</li>
 * </ul>
 * 단품 「자재 연결」 탭의 매칭은 {@code setProduct=그 상품 / roundProduct=null} 로 저장돼
 * {@code findByRoundProductId} 에 걸리지 않았고, 그래서 출고에서 자재가 빠지지 않았다.
 *
 * <p>‼️<b>세트는 제외한다.</b> 세트 자재는 <b>조립 시점</b>에 빠지므로 출고에서 또 깎으면
 * 이중 차감이다. 단품은 조립 없이 바로 나가니 출고가 유일한 차감 지점이다 —
 * 이 구분이 이 테스트의 핵심이다.
 *
 * <p>폐기도 같은 경로를 지난다. 8/31 화면기획 보완요청안이 "<b>폐기는 매출·교사용과 동일하게</b>
 * 소요량에 합산"이고, 출고는 자재를 깎는데 폐기는 안 깎으면 "회차 단독 재고 = 자재 잔여"가
 * 폐기만큼 실제보다 많이 남는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("단품 직접연결 자재 — 출고·폐기에서 빠진다(F-37 ①④⑤)")
class DirectMaterialConsumeIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DM" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2087-04-10";

    private Long partner;
    private Long warehouse;
    private Long single;        // 단품 + 자재 직접연결
    private Long setProduct;    // 세트 + 자재 직접연결 (출고 때 깎이면 안 된다)
    private Long matSingle;
    private Long matSet;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "DMS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "DMP" + SFX, "name", "자재검증 거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "DMW" + SFX, "name", "물류", "type", "MAIN"));

        single = book("DMA" + SFX, false);
        setProduct = book("DMB" + SFX, true);

        matSingle = createId("/masters/materials", Map.of(
                "code", "DMM1" + SFX, "name", "단품 시험지", "materialType", "EXAM_PAPER"));
        matSet = createId("/masters/materials", Map.of(
                "code", "DMM2" + SFX, "name", "세트 시험지", "materialType", "EXAM_PAPER"));

        // 자재 직접연결(회차 지정 없음) — 1부당 2개
        assertThat(post("/masters/products/" + single + "/materials",
                Map.of("materialId", matSingle, "qtyPerSet", 2)).path("success").asBoolean())
                .as("단품 자재연결").isTrue();
        assertThat(post("/masters/products/" + setProduct + "/materials",
                Map.of("materialId", matSet, "qtyPerSet", 2)).path("success").asBoolean())
                .as("세트 자재연결").isTrue();

        // 자재·도서 입고
        for (Long m : List.of(matSingle, matSet)) {
            assertThat(post("/stock/materials/inbound", Map.of(
                    "processedDate", "2087-01-05", "supplierClientId", supplier,
                    "destinationWarehouseId", warehouse,
                    "items", List.of(Map.of("materialId", m, "qty", 1_000))))
                    .path("success").asBoolean()).as("자재 입고").isTrue();
        }
        for (Long p : List.of(single, setProduct)) {
            assertThat(post("/stock/inbound", Map.of(
                    "processedDate", "2087-01-05", "supplierClientId", supplier,
                    "destinationWarehouseId", warehouse,
                    "items", List.of(Map.of("productId", p, "qty", 500, "unitCost", 1_000))))
                    .path("success").asBoolean()).isTrue();
        }
    }

    private Long book(String code, boolean isSet) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", code);
        b.put("contentType", "SELF");
        b.put("price", 10_000);
        b.put("catCode", "S2087A01");
        b.put("catName", "자재검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        b.put("set", isSet);
        return createId("/masters/products", b);
    }

    @Test
    @DisplayName("‼️단품을 출고하면 직접 붙인 자재가 빠진다 — 10부 × 2개 = 20")
    void 단품출고_자재차감() {
        int before = materialQty(matSingle);
        assertThat(post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", single, "shipmentType", "NORMAL_SHIP",
                        "qty", 10, "unitPrice", 10_000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
        assertThat(materialQty(matSingle))
                .as("단품 직접연결 자재가 출고에서 빠져야 한다(F-37 ④⑤)")
                .isEqualTo(before - 20);
    }

    @Test
    @DisplayName("‼️세트 출고는 자재를 깎지 않는다 — 조립에서 이미 빠졌으므로 이중 차감 방지")
    void 세트출고는_자재불변() {
        int before = materialQty(matSet);
        assertThat(post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", setProduct, "shipmentType", "NORMAL_SHIP",
                        "qty", 10, "unitPrice", 10_000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
        assertThat(materialQty(matSet))
                .as("세트 자재는 조립 시점에 빠진다 — 출고에서 또 깎으면 이중 차감이다")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("‼️단품을 폐기하면 자재도 빠진다 — 5부 × 2개 = 10")
    void 단품폐기_자재차감() {
        int before = materialQty(matSingle);
        JsonNode res = post("/disposals", Map.of(
                "processedDate", DATE, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", single, "qty", 5, "note", "파손"))));
        assertThat(res.path("success").asBoolean()).as("폐기: %s", res).isTrue();
        assertThat(materialQty(matSingle))
                .as("폐기도 매출·교사용과 동일하게 자재를 소모한다(8/31 화면기획 보완요청안)")
                .isEqualTo(before - 10);
    }

    @Test
    @DisplayName("폐기 전표를 지우면 자재도 돌아온다 — 되돌림이 한쪽만 되면 장부가 어긋난다")
    void 폐기삭제_자재복구() {
        int before = materialQty(matSingle);
        JsonNode res = post("/disposals", Map.of(
                "processedDate", DATE, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", single, "qty", 7, "note", "되돌림 검증"))));
        assertThat(res.path("success").asBoolean()).isTrue();
        String refNo = res.path("data").path("disposalNo").asText();
        assertThat(materialQty(matSingle)).isEqualTo(before - 14);

        JsonNode del = del("/disposals/" + refNo, Map.of("reason", "오입력"));
        assertThat(del.path("success").asBoolean()).as("전표 삭제: %s", del).isTrue();
        assertThat(materialQty(matSingle)).as("자재도 원래대로").isEqualTo(before);
    }

    private int materialQty(Long materialId) {
        JsonNode rows = data(get("/stock/materials?materialId=" + materialId
                + "&warehouseId=" + warehouse));
        return rows.isEmpty() ? 0 : rows.get(0).path("qty").asInt();
    }
}
