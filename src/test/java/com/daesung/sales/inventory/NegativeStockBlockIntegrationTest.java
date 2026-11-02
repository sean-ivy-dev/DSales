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
 * 재고 음수 차단 회귀 고정 — 개발팀 점검(2026-09-11).
 *
 * <p>발단: 실서버에서 현재고 <b>5,006</b>인 도서를 <b>99,999</b> 폐기했는데 그대로 통과해
 * 잔량이 <b>−94,993</b>이 됐다. 되돌릴 방법은 역분개뿐이고, 그동안 수불부·순매출이 전부 틀린다.
 *
 * <p>★이 테스트가 고정하려는 것은 <b>차단 지점이 하나</b>라는 사실이다.
 * 재고를 깎는 경로는 폐기·매출출고·창고이고·세트조립 넷인데 모두
 * {@code InventoryService.applyDelta} 를 지난다. 넷을 다 걸어 두는 이유는,
 * 나중에 누가 경로를 하나 더 만들면서 원자적 UPDATE를 우회하면
 * <b>그 경로만 조용히 뚫리기</b> 때문이다.
 *
 * <p>⚠️차단 자체는 지시가 갈린 항목이다(발주처 2026-08-31은 "음수 허용"). 설정
 * {@code daesung.inventory.allow-negative-stock=true} 로 되돌릴 수 있고, 이 테스트는
 * <b>기본값(차단)</b>을 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// ★재고를 깎아 가며 검증하므로 순서가 있다. 각 테스트는 직전 잔량을 기준으로 판단한다.
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@DisplayName("재고 음수 — 막지 않고 경고만(발주처 2026-08-31)")
class NegativeStockBlockIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-NS" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2074;

    private Long wh;
    private Long wh2;
    private Long book;
    private Long supplier;
    private Long setBook;

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients",
                Map.of("code", "NSS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        wh = createId("/masters/warehouses", Map.of("code", "NSW" + SFX, "name", "음수창고", "type", "MAIN"));
        wh2 = createId("/masters/warehouses", Map.of("code", "NSW2" + SFX, "name", "음수창고2", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "NSB" + SFX);
        b.put("name", "음수도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "N" + YEAR + "A");
        b.put("catName", "음수분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        // 딱 100부만 넣는다 — 경계가 분명해야 "막혔다"가 무슨 뜻인지 읽힌다.
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 100))));

        // 조립 경고용 세트 — 구성품(book)이 wh2에는 한 부도 없다.
        Map<String, Object> sb = new HashMap<>(b);
        sb.put("code", "NSSET" + SFX);
        sb.put("name", "음수세트");
        sb.put("salesDivision", "교재");
        sb.put("set", true);
        setBook = createId("/masters/products", sb);
        put("/masters/products/" + setBook + "/bom",
                Map.of("components", List.of(Map.of("childProductId", book, "ratio", 1))));
    }

    /** 창고별 현재고. ‼️기간을 안 주면 기본 범위가 올해라 2074년 이벤트가 통째로 빠진다. */
    private int balance(Long warehouseId) {
        String range = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";
        for (JsonNode r : data(get("/stock/ledger" + range + "&warehouseId=" + warehouseId
                + "&keyword=NSB" + SFX + "&size=50")).path("content")) {
            if (("NSB" + SFX).equals(r.path("productCode").asText())) {
                return r.path("closing").asInt();
            }
        }
        return 0;
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("★현재고보다 많은 폐기도 통과한다 — 발주처: 마이너스가 정상")
    void 폐기_초과는_통과() {
        // ‼️2026-09-11 사내 점검은 이걸 결함으로 봤지만 발주처 기준으로는 정상이다(화면7).
        //   "입고 전 출고되는 상품은 재고 (−)로 처리되며 … 마이너스로 표시되는 게 정상입니다."
        JsonNode r = post("/disposals", Map.of(
                "processedDate", YEAR + "-02-01", "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 150))));
        assertThat(r.path("success").asBoolean()).as("막으면 안 된다: %s", r).isTrue();
        assertThat(balance(wh)).as("100 − 150").isEqualTo(-50);

        // ★막지 않는 대신 조용히 넘기지도 않는다 — 오타 하나로 999,999가 지나가면 안 된다.
        JsonNode w = r.path("data").path("warnings");
        assertThat(w).as("경고가 실려야: %s", r).isNotEmpty();
        assertThat(w.get(0).path("code").asText()).isEqualTo("NEGATIVE_STOCK");
        assertThat(w.get(0).path("balance").asInt()).isEqualTo(-50);
    }

    @Test
    @org.junit.jupiter.api.Order(99)
    @DisplayName("‼️재고를 채우는 작업엔 경고가 없다 — 반품은 느는 쪽인데 걸렸다")
    void 증가작업은_경고없음() {
        // ★자기 상품으로 논다 — 다른 테스트의 잔량에 기대면 순서가 바뀔 때 깨진다.
        Map<String, Object> b = new HashMap<>();
        b.put("code", "NSP" + SFX);
        b.put("name", "증가검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("salesDivision", "교재");
        b.put("catCode", "N" + YEAR + "A");
        b.put("catName", "음수분류");
        Long p = createId("/masters/products", b);

        // 입고 없이 50부 폐기 → 잔량 −50 (발주처 기준 정상 상태)
        JsonNode out = post("/disposals", Map.of(
                "processedDate", YEAR + "-03-01", "warehouseId", wh,
                "items", List.of(Map.of("productId", p, "qty", 50))));
        assertThat(out.path("success").asBoolean()).isTrue();
        assertThat(out.path("data").path("warnings"))
                .as("줄이는 작업에는 그대로 경고가 붙는다").isNotEmpty();

        // 여기서 **입고**로 20부 채운다. 잔량은 −30 으로 여전히 음수지만 늘린 작업이다.
        JsonNode in = post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-03-02", "supplierClientId", supplier,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", p, "unitCost", 3000, "qty", 20))));
        assertThat(in.path("success").asBoolean()).isTrue();

        // ‼️예전엔 "잔량이 음수"만 보고 붙여서 여기에 경고가 달렸다.
        //   이관 데이터에 음수 재고가 흔해 반품·입고마다 경고가 붙으면 아무도 안 본다.
        assertThat(in.path("data").path("warnings"))
                .as("재고를 채우는 작업엔 경고가 붙지 않는다: %s", in).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("재고 범위 안이면 경고가 없다 — 경고가 늘 붙으면 아무도 안 본다")
    void 정상범위는_경고없음() {
        JsonNode r = post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-02-02", "supplierClientId", supplier,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 100))));
        assertThat(r.path("success").asBoolean()).isTrue();

        JsonNode d = post("/disposals", Map.of(
                "processedDate", YEAR + "-02-03", "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 10))));
        assertThat(d.path("success").asBoolean()).isTrue();
        assertThat(d.path("data").path("warnings")).as("음수가 아니면 경고 없음: %s", d).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("★입고 한 번 없는 창고에서 바로 출고 — 이게 발주처가 말한 그 상황이다")
    void 입고전_출고() {
        Long fresh = createId("/masters/warehouses",
                Map.of("code", "NSF" + SFX, "name", "빈창고", "type", "MAIN"));
        JsonNode r = post("/stock/transfer", Map.of(
                "processedDate", YEAR + "-03-01", "fromWarehouseId", fresh, "toWarehouseId", wh2,
                "items", List.of(Map.of("productId", book, "qty", 7))));
        assertThat(r.path("success").asBoolean()).as("막으면 안 된다: %s", r).isTrue();
        assertThat(balance(fresh)).isEqualTo(-7);
        assertThat(r.path("data").path("warnings")).isNotEmpty();
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("세트 조립도 자재가 모자라면 통과하고 경고만 남는다")
    // ‼️앞 테스트가 wh2 에 7부를 옮겨 놓는다 — 확실히 음수가 되도록 크게 잡는다.
    void 조립_자재부족() {
        JsonNode r = post("/stock/bom", Map.of(
                "processedDate", YEAR + "-03-02", "warehouseId", wh2,
                "direction", "ASSEMBLE", "parentProductId", setBook, "workQty", 500));
        assertThat(r.path("success").asBoolean()).as("막으면 안 된다: %s", r).isTrue();
        assertThat(r.path("data").path("warnings")).as("경고는 남아야: %s", r).isNotEmpty();
    }
}
