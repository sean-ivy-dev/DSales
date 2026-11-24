package com.daesung.sales.sale;

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
 * <b>출고 반품 ↔ 매출 반품</b> — 가르는 값은 <b>「출고 창고」</b>다(발주처 2026-11-23 1번 표).
 *
 * <p>그 표가 매출 반품을 이렇게 적었다 — "재고: <b>출고 창고 고른 때만</b> /
 * 출고·반품 조회: 출고 창고 고른 때만 / 통합 매출 조회: 음수 / 재고 영향 없음".
 * 즉 <b>새 구분값을 만들 것이 아니라</b> 창고를 비울 수 있게 하면 그 표가 그대로 성립한다.
 *
 * <p>‼️막혀 있던 것은 {@code assertWarehouseWhenStockMoves} 한 곳뿐이었다 —
 * 재고관리 Y 상품은 창고를 <b>무조건</b> 요구해 「창고 없는 반품」을 넣을 수 없었다.
 *
 * <p>★구분값을 새로 만들면 창고와 둘이 되어 "어느 쪽이 진짜냐"가 생긴다. 그래서 만들지 않았다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("반품 구분 — 출고 창고가 그 값이다(2026-11-23 1번)")
class SalesReturnKindIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "RK" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2078;

    private Long partner;
    private Long warehouse;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "RKS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "RKW" + SFX, "name", "반품구분창고" + SFX, "type", "MAIN"));
        partner = createId("/masters/clients",
                Map.of("code", "RKP" + SFX, "name", "반품구분거래처", "type", "NORMAL"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "RKB" + SFX);
        b.put("name", "반품구분도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "K" + YEAR + "A");
        b.put("catName", "반품구분");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        b.put("stockManaged", true);        // ★재고관리 Y — 예전엔 이 때문에 창고가 필수였다
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 500, "unitCost", 3000))))
                .path("success").asBoolean()).isTrue();

        // 반품할 원 출고를 먼저 만든다(출고 창고 지정).
        assertThat(post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 50, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★「매출 반품」 — 창고를 비우고 등록된다. 예전엔 재고관리 Y 라 **거부**됐다")
    void 매출반품_창고없이_등록된다() {
        int before = stock();
        JsonNode d = data(post("/sales/entries", Map.of(
                "salesDate", YEAR + "-03-05", "partnerId", partner,
                "items", List.of(Map.of("productId", book, "shipmentType", "RETURN",
                        "qty", 4, "unitPrice", 10000, "supplyRate", 70)))));

        assertThat(d.path("items")).as("‼️거부되면 가드가 아직 창고를 요구하는 것이다").hasSize(1);
        assertThat(codes(d.path("warnings")))
                .as("재고가 안 돌아온다는 사실을 알려야 한다: %s", d.path("warnings"))
                .contains("RETURN_WITHOUT_WAREHOUSE");
        assertThat(stock()).as("‼️재고가 움직이면 「매출 반품」이 아니다").isEqualTo(before);
    }

    @Test
    @DisplayName("★「매출 반품」은 **통합 매출엔 음수로 나오고 출고/반품 조회엔 안 나온다** — 표 그대로")
    void 매출반품_조회축() {
        String day = YEAR + "-03-07";
        assertThat(post("/sales/entries", Map.of(
                "salesDate", day, "partnerId", partner,
                "items", List.of(Map.of("productId", book, "shipmentType", "RETURN",
                        "qty", 6, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();

        assertThat(qty(day, false)).as("통합 매출 — 반품이라 음수").isEqualTo(-6L);
        assertThat(data(get(url(day) + "&shippedOnly=true&groupBy=PRODUCT")))
                .as("‼️출고/반품 조회에 나오면 재고가 안 움직인 줄이 거기 섞인다")
                .isEmpty();
    }

    @Test
    @DisplayName("★「출고 반품」 — 창고를 고르면 재고가 돌아오고 출고/반품 조회에도 나온다")
    void 출고반품_창고지정() {
        String day = YEAR + "-03-09";
        int before = stock();
        JsonNode d = data(post("/sales/entries", Map.of(
                "salesDate", day, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "shipmentType", "RETURN",
                        "qty", 7, "unitPrice", 10000, "supplyRate", 70)))));

        assertThat(codes(d.path("warnings")))
                .as("창고를 골랐으니 그 경고는 없어야 한다")
                .doesNotContain("RETURN_WITHOUT_WAREHOUSE");
        assertThat(stock()).as("실물이 돌아왔다").isEqualTo(before + 7);
        assertThat(qty(day, true)).as("출고/반품 조회에도 음수로 나온다").isEqualTo(-7L);
    }

    @Test
    @DisplayName("‼️반품이 아닌 줄은 **여전히 창고를 요구한다** — 가드를 통째로 풀면 안 된다")
    void 정상출고는_창고가_필수() {
        JsonNode res = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-03-11", "partnerId", partner,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 3, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(res.path("success").asBoolean())
                .as("‼️통과하면 재고가 움직이는 매출이 창고 없이 들어온다: %s", res)
                .isFalse();
    }

    @Test
    @DisplayName("★2번 **입고/대체 내역에 반품이 나온다** — 그 화면에서 등록하고 재고를 늘리는 작업이다")
    void 입고대체_내역에_반품() {
        String day = YEAR + "-04-02";
        assertThat(post("/sales/return-inbound", Map.of(
                "returnDate", day, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 5,
                        "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();

        JsonNode rows = data(get("/stock/records?fromDate=" + day + "&toDate=" + day
                + "&productId=" + book));
        assertThat(rows).as("‼️비면 반품이 또 쿼리에서 빠진 것이다").isNotEmpty();
        JsonNode r = rows.get(0);
        assertThat(r.path("kind").asText()).isEqualTo("반품");
        assertThat(r.path("qtyDelta").asInt()).as("물류창고로 돌아오니 양수").isEqualTo(5);

        // 구분 필터로도 잡혀야 한다.
        assertThat(data(get("/stock/records?fromDate=" + day + "&toDate=" + day
                + "&productId=" + book + "&kind=RETURN"))).hasSize(1);
        // 다른 구분만 고르면 안 나온다 — 필터가 실제로 먹는지.
        assertThat(data(get("/stock/records?fromDate=" + day + "&toDate=" + day
                + "&productId=" + book + "&kind=INBOUND"))).isEmpty();
    }

    private String url(String day) {
        return "/sales/group-totals?fromDate=" + day + "&toDate=" + day
                + "&partnerIds=" + partner;
    }

    private long qty(String day, boolean shippedOnly) {
        JsonNode rows = data(get(url(day) + "&groupBy=PRODUCT&shippedOnly=" + shippedOnly));
        long sum = 0;
        for (JsonNode n : rows) {
            sum += n.path("qty").asLong();
        }
        return sum;
    }

    /**
     * 그 도서의 <b>현재 재고</b> — 제품수불부로 읽는다(다른 통합테스트가 쓰는 그 경로).
     *
     * <p>★`inventory.qty` 를 직접 보지 않는다 — 정본은 재고이벤트 합이고
     * 수불부가 그 유일한 공식이다(CLAUDE.md 재고 정의 단일화).
     */
    private int stock() {
        for (JsonNode n : data(get("/stock/ledger?fromDate=" + YEAR + "-01-01&toDate=" + YEAR
                + "-12-31&productId=" + book + "&size=50")).path("content")) {
            return n.path("closing").asInt();
        }
        return 0;
    }

    private List<String> codes(JsonNode warnings) {
        List<String> out = new java.util.ArrayList<>();
        warnings.forEach(w -> out.add(w.path("code").asText()));
        return out;
    }
}
