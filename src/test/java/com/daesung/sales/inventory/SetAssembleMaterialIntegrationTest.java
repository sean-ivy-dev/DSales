package com.daesung.sales.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 세트 조립·해체가 <b>자재 낱장</b>을 깎는다 — F-37 발주처 확정(2026-11-09).
 *
 * <p>원문: "재고는 <b>자재 낱장이 기준 원장</b>. SET 재고는 세트 조립·해체로 증감.
 * 회차 단독 재고는 해당 회차 자재 낱장 잔여에서 산출해 표시."
 *
 * <p>★<b>발주처 32장 예시를 그대로 검증한다.</b>
 * <pre>
 *   단계          시험지(회차별)   SET 재고
 *   자재 입고      각 1,000         0
 *   SET 800 조립   각   200       800
 *   SET 100 해체   각   300       700
 * </pre>
 * 그리고 "OMR 을 1종 공통 자재로 두면 SET 1부당 회차 수만큼 소요
 * (SET 800 조립 시 4,800장)" — 공통 자재는 <b>세트당 소요</b>에 회차 수가 이미 들어 있다.
 *
 * <p>‼️예전에는 조립이 {@code bom_items}(상품↔상품)만 깎아 <b>자재 낱장이 줄지 않았다</b>.
 * 전환이 가능했던 이유는 운영 조립·해체 이력이 <b>0건</b>이었기 때문이다(2026-11-09 실측).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@DisplayName("세트 조립·해체 → 자재 낱장 증감(F-37, 32장 예시)")
class SetAssembleMaterialIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-AM" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2095-03-04";

    private Long warehouse;
    private Long setProduct;
    private final List<Long> rounds = new java.util.ArrayList<>();
    private final List<Long> papers = new java.util.ArrayList<>();
    private Long omr;

    @BeforeAll
    void seed() {
        token();
        warehouse = createId("/masters/warehouses",
                Map.of("code", "AMW" + SFX, "name", "자재창고", "type", "MAIN"));
        setProduct = product("AMS" + SFX, "SET");

        // 회차 4개 — 발주처 예시가 "1~4회"다.
        for (int i = 1; i <= 4; i++) {
            Long round = product("AMR" + i + SFX, i + "회");
            rounds.add(round);
            Long paper = createId("/masters/materials", Map.of(
                    "code", "AMP" + i + SFX, "name", i + "회 시험지", "materialType", "EXAM_PAPER"));
            papers.add(paper);
            // 회차별 시험지 — 세트당 1장
            JsonNode b = post("/masters/products/" + setProduct + "/materials", Map.of(
                    "roundProductId", round, "materialId", paper, "qtyPerSet", 1));
            assertThat(b.path("success").asBoolean()).as("회차 자재 매칭: %s", b).isTrue();
        }

        // 공통 OMR — 세트당 4장(회차 수만큼). 발주처 "SET 1부당 회차 수만큼 소요".
        omr = createId("/masters/materials", Map.of(
                "code", "AMO" + SFX, "name", "공통 OMR", "materialType", "OMR"));
        JsonNode o = post("/masters/products/" + setProduct + "/materials", Map.of(
                "materialId", omr, "qtyPerSet", 4, "perRound", true));
        assertThat(o.path("success").asBoolean()).as("공통 자재 매칭: %s", o).isTrue();

        // 자재 입고 — 시험지 각 1,000 / OMR 10,000(조립 2회분이 충분하게)
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Long p : papers) {
            items.add(Map.of("materialId", p, "qty", 1000));
        }
        items.add(Map.of("materialId", omr, "qty", 10000));
        JsonNode in = post("/stock/materials/inbound", Map.of(
                "processedDate", DATE, "destinationWarehouseId", warehouse, "items", items));
        assertThat(in.path("success").asBoolean()).as("자재입고: %s", in).isTrue();
    }

    private Long product(String code, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2095A01");
        b.put("catName", "조립검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        return createId("/masters/products", b);
    }

    /** 자재 잔량 — 이 테스트 창고 기준. */
    private int materialQty(Long materialId) {
        JsonNode res = get("/stock/materials?materialId=" + materialId + "&warehouseId=" + warehouse);
        assertThat(res.path("success").asBoolean()).as("자재재고: %s", res).isTrue();
        int sum = 0;
        for (JsonNode n : res.path("data")) {
            sum += n.path("qty").asInt();
        }
        return sum;
    }

    // ‼️세트 상품 잔량은 **조립·해체 응답의 `parent.balance`** 로 본다.
    //   · `GET /stock?productId=` 는 없는 경로다(404).
    //   · 제품수불부(`/stock/ledger`)는 **'수불부노출' 필터**가 걸려 있어 그 값을 안 켠 상품은
    //     집계에 안 들어온다 — 재고가 0이라서가 아니라 축이 다르다. 여기서 쓰면 오판한다.
    //   응답 잔량은 서버가 그 자리에서 적용한 값이라 가장 확실하다.

    @Test
    @Order(1)
    @DisplayName("자재 입고 직후 — 시험지 각 1,000 · SET 0")
    void 입고직후() {
        for (Long p : papers) {
            assertThat(materialQty(p)).as("시험지 입고분").isEqualTo(1000);
        }
        assertThat(materialQty(omr)).isEqualTo(10000);
    }

    @Test
    @Order(2)
    @DisplayName("SET 800 조립 → 시험지 각 200 · SET 800 · OMR 3,200 차감")
    void 조립() {
        JsonNode res = post("/stock/bom", Map.of(
                "processedDate", DATE, "warehouseId", warehouse,
                "parentProductId", setProduct, "direction", "ASSEMBLE", "workQty", 800));
        assertThat(res.path("success").asBoolean()).as("조립: %s", res).isTrue();

        JsonNode data = res.path("data");
        assertThat(data.path("workNo").asText()).as("전표번호 BW-").startsWith("BW-");

        // 구성 줄이 **자재**로 내려온다 — productId 는 null, materialId 가 채워진다.
        List<JsonNode> comps = new java.util.ArrayList<>();
        data.path("components").forEach(comps::add);
        if (comps.isEmpty()) {
            data.path("componentLines").forEach(comps::add);
        }
        assertThat(comps).as("구성 줄(자재 5건): %s", data).hasSize(5);
        for (JsonNode c : comps) {
            assertThat(c.has("productId")).as("자재 줄은 상품 id가 없다: %s", c).isFalse();
            assertThat(c.path("materialId").asLong()).as("자재 id: %s", c).isPositive();
        }

        // ★발주처 32장 예시 그대로
        for (Long p : papers) {
            assertThat(materialQty(p)).as("시험지 1,000 − 소요1×800").isEqualTo(200);
        }
        // ‼️이 테스트는 **4회 구성**이라 소요 4 × 800 = 3,200 이다.
        //   발주처 32장의 "4,800장"은 **6회 구성**(소요 6 × 800)이라 나온 값 — 섞지 말 것.
        assertThat(materialQty(omr)).as("공통 OMR 10,000 − 소요4×800").isEqualTo(10000 - 3200);
        // 서버가 적용한 잔량을 응답에서 직접 본다(가장 확실한 축).
        assertThat(data.path("parent").path("balance").asInt()).as("세트 잔량(응답)").isEqualTo(800);
        assertThat(data.path("parent").path("delta").asInt()).isEqualTo(800);

    }

    @Test
    @Order(3)
    @DisplayName("SET 100 해체 → 시험지 각 300 · SET 700 (역부호)")
    void 해체() {
        JsonNode res = post("/stock/bom", Map.of(
                "processedDate", DATE, "warehouseId", warehouse,
                "parentProductId", setProduct, "direction", "DISASSEMBLE", "workQty", 100));
        assertThat(res.path("success").asBoolean()).as("해체: %s", res).isTrue();

        for (Long p : papers) {
            assertThat(materialQty(p)).as("시험지 200 + 소요1×100").isEqualTo(300);
        }
        assertThat(materialQty(omr)).as("OMR 6,800 + 소요4×100").isEqualTo(6800 + 400);
        assertThat(res.path("data").path("parent").path("balance").asInt())
                .as("세트 잔량(응답)").isEqualTo(700);

    }

    @Test
    @Order(4)
    @DisplayName("조립 이력에 그 시점 소요량이 남는다(발주처 조건) — 메모에 '세트당 N개'")
    void 소요량이력() {
        JsonNode res = get("/stock/materials/records?materialId=" + papers.get(0)
                + "&fromDate=2095-01-01&toDate=2095-12-31");
        assertThat(res.path("success").asBoolean()).as("자재내역: %s", res).isTrue();
        boolean found = false;
        for (JsonNode n : res.path("data")) {
            String memo = n.path("memo").asText("");
            if (memo.contains("세트당 1개")) {
                found = true;
            }
        }
        assertThat(found).as("조립·해체 행 메모에 당시 소요량이 남아야 한다: %s", res).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("회차 단독 출고 → 그 회차 자재만 차감(공통은 회차당 소요, 다른 회차는 그대로)")
    void 회차단독출고() {
        // 거래처가 필요하다 — 매출등록 경로로 출고한다.
        Long partner = createId("/masters/clients", Map.of(
                "code", "AMC" + SFX, "name", "회차출고 거래처", "type", "NORMAL"));

        int before1 = materialQty(papers.get(0));
        int before2 = materialQty(papers.get(1));
        int beforeOmr = materialQty(omr);

        // 1회 도서 10부 단독 출고
        JsonNode res = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of(
                        "productId", rounds.get(0), "shipmentType", "NORMAL_SHIP",
                        "qty", 10, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(res.path("success").asBoolean()).as("회차 단독 출고: %s", res).isTrue();

        // 1회 시험지만 10장 빠진다(세트당 1 × 10부)
        assertThat(materialQty(papers.get(0))).as("1회 시험지").isEqualTo(before1 - 10);
        // ‼️다른 회차 시험지는 건드리지 않는다 — 여기가 틀리면 세트 출고와 구분이 안 된 것이다
        assertThat(materialQty(papers.get(1))).as("2회 시험지는 그대로").isEqualTo(before2);
        // 공통 OMR은 **회차당 소요**(세트당 4 ÷ 4회차 = 1) × 10부 = 10장
        assertThat(materialQty(omr)).as("공통 OMR 회차당 1 × 10부").isEqualTo(beforeOmr - 10);
    }
}
