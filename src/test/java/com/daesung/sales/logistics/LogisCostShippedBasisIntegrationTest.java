package com.daesung.sales.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 물류 작업비의 <b>수량 기준을 출고/반품 조회와 일치</b>시킨 것(10/27 명세 §10 · 작업지침 4 ·
 * 프론트 C-5·E-3·D-2).
 *
 * <p>정본 §10 원문 — "출처는 도서관리의 <b>수불부노출</b> 값으로 구분한다.
 * <b>Y(매출프로그램 단독 관리)</b>: <b>출고/반품 조회의 출고 수량을 가져와 계산한다</b>.
 * … <b>이중 계산 방지 장치</b>가 필요하다. 같은 상품·수량이 두 경로로 잡히지 않게
 * 한다(위탁출고 §4-5 포함)."
 * 작업지침 4 — "<b>매입처(법인 분리)는 이 화면과 물류 작업비 계산에서 뺀다</b>."
 *
 * <p>★<b>세 요구가 한 지점으로 모인다</b> — "출고/반품 조회와 같은 수량이냐".
 * 예전엔 물류비가 <b>자기 조건을 따로</b> 갖고 있어 그 화면과 어긋나 있었다:
 * <ul>
 *   <li>창고 없는 매출·매출만(배송X)·특강이 <b>물류비에만</b> 들어갔다</li>
 *   <li>매입처가 안 빠졌다</li>
 *   <li>위탁은 <b>정산분</b>만 잡혀, §4-4가 요구하는 <b>원출고</b>와 달랐다</li>
 * </ul>
 * 조건을 두 군데 적어 두면 반드시 또 어긋나므로 판정 조각
 * ({@code SaleRepository.SHIPPED_PREDICATE})과 특강 목록을 그 화면과 <b>공유</b>하게 했다.
 * 이 테스트는 그 일치를 항목별로 못 박는다.
 *
 * <p>‼️HTTP 로 검증하지 않는 이유는 {@link StandaloneLogisCostIntegrationTest} 와 같다 —
 * 물류작업비 화면은 DSRE 플래그 뒤에 있고 테스트는 DSRE 를 끄고 돈다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("물류 작업비 수량 = 출고/반품 조회 수량(§10)")
class LogisCostShippedBasisIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-LB" + (System.nanoTime() % 1_000_000L);
    private static final LocalDate FROM = LocalDate.parse("2091-04-01");
    private static final LocalDate TO = LocalDate.parse("2091-04-30");
    private static final String DATE = "2091-04-10";

    private Long partner;       // 평범한 매출처
    private Long purchaser;     // 매입처 — 지침4로 빠져야 한다
    private Long wh;
    private Long consignWh;
    private int packType;

    private Long normal;        // 정상출고 대조군
    private Long onlySalesP;    // 매출만(배송X)로 팔 상품
    private Long noWarehouse;   // 창고 미지정으로 팔 상품
    private Long lecture;       // 특강
    private Long purchaserP;    // 매입처에 팔 상품
    private Long consignP;      // 위탁출고

    @Autowired
    private com.daesung.sales.logistics.service.StandaloneLogisCostService standaloneService;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "LBS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "LBP" + SFX, "name", "일반거래처", "type", "NORMAL"));
        purchaser = createId("/masters/clients",
                Map.of("code", "LBQ" + SFX, "name", "매입처거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "LBW" + SFX, "name", "물류창고", "type", "MAIN"));
        consignWh = createId("/masters/warehouses", Map.of(
                "code", "LBC" + SFX, "name", "위탁창고", "type", "CONSIGN",
                "ownerClientId", partner));

        // 특강 세부구분 신설(대분류 SPECIAL_LECTURE) — 물류비가 이걸 빼야 한다
        JsonNode div = post("/masters/sales-divisions", Map.of(
                "code", "LBD" + SFX, "name", "특강구분" + SFX, "majorCategory", "SPECIAL_LECTURE"));
        assertThat(div.path("success").asBoolean()).as("특강 세부구분: %s", div).isTrue();

        packType = 9000 + (int) (System.nanoTime() % 900);
        assertThat(post("/masters/work-types", Map.of(
                "packType", packType, "name", "기준검증봉투" + SFX,
                "paper", 0, "omr", 0, "etc", 0, "label", 0,
                "basic", 100, "trade", 0)).path("success").asBoolean()).isTrue();

        normal = product("LBA" + SFX, "정상출고상품", "교재");
        onlySalesP = product("LBB" + SFX, "매출만상품", "교재");
        noWarehouse = product("LBE" + SFX, "창고없음상품", "교재");
        lecture = product("LBF" + SFX, "특강상품", "LBD" + SFX);
        purchaserP = product("LBG" + SFX, "매입처판매상품", "교재");
        consignP = product("LBH" + SFX, "위탁상품", "교재");

        for (Long p : List.of(normal, onlySalesP, noWarehouse, lecture, purchaserP, consignP)) {
            inbound(supplier, p, "NORMAL");
        }
        // ★이 입고가 purchaser 를 '매입처'로 만든다 — 매입(PURCHASE) 입고가 한 번이라도 있으면 매입처다.
        inbound(purchaser, normal, "PURCHASE");
    }

    private Long product(String code, String name, String salesDivision) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "L2091A01");
        b.put("catName", "기준검증");
        b.put("ledgerVisible", true);      // 전부 단독(Y) — 수불부노출 분기 자체는 기존 테스트가 고정
        b.put("salesDivision", salesDivision);
        Long id = createId("/masters/products", b);
        assertThat(post("/logistics-costs/products",
                Map.of("productId", id, "packType", packType)).path("success").asBoolean()).isTrue();
        return id;
    }

    private void inbound(Long supplierId, Long productId, String inboundType) {
        Map<String, Object> req = new HashMap<>();
        req.put("processedDate", "2091-01-05");
        req.put("supplierClientId", supplierId);
        req.put("destinationWarehouseId", wh);
        req.put("inboundType", inboundType);
        req.put("items", List.of(Map.of("productId", productId, "unitCost", 100, "qty", 999)));
        assertThat(post("/stock/inbound", req).path("success").asBoolean()).isTrue();
    }

    /** 매출 등록. {@code warehouseId} 를 null 로 주면 창고 없는 매출이 된다. */
    private JsonNode sell(Long partnerId, Long productId, int qty,
                          String shipmentType, Long warehouseId, Boolean onlySales) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", productId);
        item.put("shipmentType", shipmentType);
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", qty);

        Map<String, Object> req = new HashMap<>();
        req.put("salesDate", DATE);
        req.put("partnerId", partnerId);
        req.put("warehouseId", warehouseId);
        req.put("items", List.of(item));
        if (onlySales != null) {
            req.put("onlySales", onlySales);
        }
        return post("/sales/entries", req);
    }

    /** 물류비 행의 '인원'(=출고수량). 행이 없으면 0 — "안 세어졌다"는 뜻이다. */
    private long inwonOf(String productCode) {
        return standaloneService.rows(FROM, TO).stream()
                .filter(r -> productCode.equals(r.productCode()))
                .mapToLong(r -> r.inwon()).sum();
    }

    @Test
    @DisplayName("대조군 — 평범한 정상출고는 그대로 센다")
    void 정상출고는_센다() {
        assertThat(sell(partner, normal, 10, "NORMAL_SHIP", wh, null)
                .path("success").asBoolean()).isTrue();
        assertThat(inwonOf("LBA" + SFX)).isEqualTo(10);
    }

    @Test
    @DisplayName("★매출만(배송X)은 안 센다 — 물류 작업이 없었다")
    void 매출만은_안센다() {
        JsonNode r = sell(partner, onlySalesP, 10, "NORMAL_SHIP", wh, true);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        // 출고/반품 조회에도 안 나오는 건이다(§4-11) — 물류비도 같아야 한다.
        assertThat(inwonOf("LBB" + SFX)).isZero();
    }

    @Test
    @DisplayName("★창고 없는 매출은 '매출만'일 때만 생긴다 — 그 외엔 등록 자체가 거부된다")
    void 창고없는매출은_매출만일때만() {
        // 물류비 판정에 `창고 is not null` 을 둔 이유를 여기 적어 둔다.
        // ★지금은 등록 단계에서 이미 막힌다 — 재고가 움직이는 매출은 창고가 필수다.
        //   그래서 창고 없는 매출은 **'매출만(배송X)'** 이거나, 그 규칙이 생기기 전
        //   과거 등록분뿐이다. 과거분은 API 로 만들 수 없어 수량으로 검증할 수 없고,
        //   판정 조각의 `w.id is not null` 은 **그 과거분을 막는 방어선**으로 남는다
        //   (출고/반품 조회도 같은 조각을 쓴다 — 두 화면이 같이 막힌다).
        JsonNode r = sell(partner, noWarehouse, 10, "NORMAL_SHIP", null, null);

        assertThat(r.path("success").asBoolean()).as("창고 없는 일반 매출은 거부: %s", r).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("창고를 지정해야");
        assertThat(inwonOf("LBE" + SFX)).as("거부됐으니 물류비에도 없다").isZero();
    }

    @Test
    @DisplayName("★특강은 안 센다 — 출고/반품 조회에서 빠지는 셋 중 하나")
    void 특강은_안센다() {
        JsonNode r = sell(partner, lecture, 10, "NORMAL_SHIP", wh, null);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        assertThat(inwonOf("LBF" + SFX)).isZero();
    }

    @Test
    @DisplayName("★매입처 거래처는 안 센다 — 작업지침 4 '물류 작업비 계산에서 뺀다'")
    void 매입처는_안센다() {
        JsonNode r = sell(purchaser, purchaserP, 10, "NORMAL_SHIP", wh, null);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        assertThat(inwonOf("LBG" + SFX)).as("매입처 매출은 물류비 대상이 아니다").isZero();
    }

    @Test
    @DisplayName("★★위탁출고는 원출고수량으로 센다 — 정산해도 수량이 변하지 않는다(이중계산 방지)")
    void 위탁은_원출고수량() {
        // §4-4 예시 그대로 — 50부 보내고 40부 정산한다.
        JsonNode out = sell(partner, consignP, 50, "CONSIGN_SHIP", wh, null);
        assertThat(out.path("success").asBoolean()).as("%s", out).isTrue();

        // 보낸 시점: 물류 작업은 50부를 싸서 보낼 때 생겼다.
        assertThat(inwonOf("LBH" + SFX)).as("출고 직후 50").isEqualTo(50);

        long outId = -1;
        for (JsonNode p : data(get("/consignment/pending?partnerId=" + partner)).path("items")) {
            if (p.path("productId").asLong() == consignP) {
                outId = p.path("consignmentOutId").asLong();
            }
        }
        assertThat(outId).as("미결 행을 찾았다").isGreaterThan(0);

        JsonNode st = post("/consignment/settle", Map.of(
                "salesDate", DATE,
                // 단가는 화면이 미결 행에서 읽어 되돌려 보내는 계약이다(옛 미결은 담당자가 직접 입력).
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", 40,
                        "unitPrice", 10000, "supplyRate", 70))));
        assertThat(st.path("success").asBoolean()).as("%s", st).isTrue();

        // ★정산해도 50이다. 40으로 줄면 §4-4 위반이고, 90(50+40)이면 §10 이중계산이다.
        //   정산으로 생긴 매출(CONSIGN_SALES)은 판정 조각이 빼 준다.
        assertThat(inwonOf("LBH" + SFX))
                .as("정산 후에도 원출고 50 — 줄지도(40) 두 번 세지도(90) 않는다").isEqualTo(50);
    }
}
