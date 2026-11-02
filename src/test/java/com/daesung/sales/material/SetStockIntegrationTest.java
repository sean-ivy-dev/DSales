package com.daesung.sales.material;

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
 * 세트 가용재고 = <b>구성 회차별 시험지 재고의 최소값</b>.
 *
 * <p>발주처 확정(2026-10-23) 원문 예시를 그대로 검증한다 —
 * "1~4회 100/120/105/111 → 세트 100".
 *
 * <p>★세트는 따로 쌓아 두는 물건이 아니라 회차 시험지를 모아 만드는 것이라,
 * <b>가장 모자란 회차</b>가 세트 수를 정한다. 120짜리가 아무리 많아도 100 세트까지다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("세트 가용재고(구성 회차 최소값)")
class SetStockIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SS" + (System.nanoTime() % 1_000_000L);

    private Long warehouse;
    private Long setProduct;
    private Long emptySet;
    private final List<Long> materials = new java.util.ArrayList<>();

    @BeforeAll
    void seed() {
        token();
        warehouse = createId("/masters/warehouses",
                Map.of("code", "SSW" + SFX, "name", "자재창고", "type", "MAIN"));
        setProduct = product("SSP" + SFX, "세트상품");
        emptySet = product("SSE" + SFX, "구성없는세트");

        // 1~4회 시험지 — 재고 100 / 120 / 105 / 111
        int[] stocks = {100, 120, 105, 111};
        for (int i = 0; i < stocks.length; i++) {
            Long round = product("SSR" + i + SFX, (i + 1) + "회");
            Long mat = createId("/masters/materials", Map.of(
                    "code", "SSM" + i + SFX, "name", (i + 1) + "회 시험지", "materialType", "EXAM_PAPER"));
            materials.add(mat);

            // 세트당 1장 — 발주처 예시가 그 전제다(최소값이 곧 재고).
            JsonNode bom = post("/masters/products/" + setProduct + "/materials", Map.of(
                    "roundProductId", round, "materialId", mat, "qtyPerSet", 1));
            assertThat(bom.path("success").asBoolean()).as("BOM 등록: %s", bom).isTrue();

            JsonNode in = post("/stock/materials/inbound", Map.of(
                    "processedDate", "2091-01-05", "destinationWarehouseId", warehouse,
                    "items", List.of(Map.of("materialId", mat, "qty", stocks[i]))));
            assertThat(in.path("success").asBoolean()).as("자재입고: %s", in).isTrue();
        }
    }

    private Long product(String code, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2091A01");
        b.put("catName", "세트검증");
        b.put("taxFree", false);
        b.put("useYn", true);
        b.put("set", true);
        b.put("salesDivision", "교재");
        return createId("/masters/products", b);
    }

    @Test
    @DisplayName("★발주처 예시 그대로 — 100/120/105/111 이면 세트 100")
    void 최소값이_세트수() {
        JsonNode d = data(get("/masters/products/" + setProduct + "/set-stock"));

        assertThat(d.path("availableSets").asInt()).isEqualTo(100);
        assertThat(d.path("rounds")).hasSize(4);

        // 어느 회차가 막고 있는지 짚어 준다 — 담당자가 그 회차를 채우면 되기 때문이다.
        long bottlenecks = 0;
        for (JsonNode r : d.path("rounds")) {
            if (r.path("bottleneck").asBoolean()) {
                bottlenecks++;
                assertThat(r.path("stockQty").asInt()).as("병목은 재고가 가장 적은 회차").isEqualTo(100);
            }
        }
        assertThat(bottlenecks).isEqualTo(1);
    }

    @Test
    @DisplayName("모자란 회차를 채우면 그다음 최소값으로 올라간다")
    void 채우면_올라간다() {
        // 100짜리를 105까지 채운다 → 이제 105가 최소(105·105·111·120 중)
        assertThat(post("/stock/materials/inbound", Map.of(
                "processedDate", "2091-02-05", "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("materialId", materials.get(0), "qty", 5))))
                .path("success").asBoolean()).isTrue();

        assertThat(data(get("/masters/products/" + setProduct + "/set-stock"))
                .path("availableSets").asInt()).isEqualTo(105);
    }

    @Test
    @DisplayName("‼️구성이 없으면 null — 0과 다르다")
    void 구성없으면_null() {
        JsonNode d = data(get("/masters/products/" + emptySet + "/set-stock"));
        // 0은 "못 만든다", null 은 "구성이 안 잡혀 있다"다. 섞으면 담당자가 할 일을 못 찾는다.
        assertThat(d.hasNonNull("availableSets")).isFalse();
        assertThat(d.path("rounds")).isEmpty();
    }
}
