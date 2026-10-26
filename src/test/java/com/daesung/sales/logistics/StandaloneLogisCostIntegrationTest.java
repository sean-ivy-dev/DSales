package com.daesung.sales.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.logistics.dto.LogisCostDetailResponse;
import com.daesung.sales.logistics.dto.LogisCostDetailResponse.MissingProduct;
import com.daesung.sales.logistics.dto.LogisCostDetailResponse.MissingRate;
import com.daesung.sales.logistics.dto.LogisCostDetailResponse.MissingReason;
import com.daesung.sales.logistics.dto.LogisCostDetailResponse.ReasonCount;
import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 단독 상품(수불부노출 Y)의 물류 작업비 — <b>'인원' 자리에 매출프로그램 출고수량</b>.
 *
 * <p>근거: 발주처 확정(2026-10-23 #10) "상품별 출처 구분, 기준=도서관리 수불부노출.
 * N(DSRE병행)→DSRE 인원 값. Y(단독)→'인원' 자리에 매출프로그램 출고수량.
 * <b>병행 상품 두 값 합산 시 이중계산 주의.</b> 계산식·단가 기존 그대로."
 *
 * <p>자재구분→단가는 구조보완 요청안(2026-08-31 §2-1) —
 * 시험지→시험지 · OMR→OMR · 단행본→단행본 · 라벨→라벨 · <b>해설지→시험지</b>.
 *
 * <p>‼️<b>HTTP 로 검증하지 않는다.</b> 물류작업비 화면(LogisticsController)은
 * {@code @ConditionalOnProperty(daesung.dsre.enabled=true)} 뒤에 있는데 테스트는 DSRE 를 끄고 돈다 —
 * 엔드포인트가 아예 없어 빈 응답이 오고, "행이 없다"는 검증이 <b>통과해 버린다</b>(실제로 그랬다).
 * 로직을 들고 있는 서비스를 직접 부른다. 단독 계산 자체는 DSRE 와 무관하다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("단독 상품 물류 작업비(출고수량 기준)")
class StandaloneLogisCostIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SL" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2090-03-10";
    private static final String RANGE = "?fromDate=2090-03-01&toDate=2090-03-31";

    private Long standalone;    // 수불부노출 Y — 우리가 세는 쪽
    private Long parallel;      // 수불부노출 N — DSRE 소관, 세면 안 되는 쪽
    private Long noRate;        // 단가 미등록
    private int packType;       // 작업구분(단가를 들고 있는 축)

    @Autowired
    private com.daesung.sales.logistics.service.StandaloneLogisCostService standaloneService;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "SLS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        Long partner = createId("/masters/clients",
                Map.of("code", "SLP" + SFX, "name", "단독거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "SLW" + SFX, "name", "물류창고", "type", "MAIN"));

        standalone = product("SLA" + SFX, "단독상품", true);
        parallel = product("SLB" + SFX, "병행상품", false);
        noRate = product("SLC" + SFX, "단가없음상품", true);

        // 자재 — 시험지 1 · 해설지 1 · OMR 2 (해설지는 시험지 단가를 쓴다)
        bom(standalone, "SLM1" + SFX, "시험지", "EXAM_PAPER", 1);
        bom(standalone, "SLM2" + SFX, "해설지", "ANSWER_SHEET", 1);
        bom(standalone, "SLM3" + SFX, "OMR", "OMR", 2);

        // 작업구분에 기준단가를 등록한다 — 시험지 50 · OMR 30 · 단행본 10 · 라벨 5 · 기본 100 · 출고 200
        // packType 은 작업구분 식별자다 — 실행별로 겹치지 않게 뽑는다.
        packType = 7000 + (int) (System.nanoTime() % 2000);
        JsonNode wt = post("/masters/work-types", Map.of(
                "packType", packType, "name", "검증봉투" + SFX,
                "paper", 50, "omr", 30, "etc", 10, "label", 5,
                "basic", 100, "trade", 200));
        assertThat(wt.path("success").asBoolean()).as("작업구분 등록: %s", wt).isTrue();

        rate(standalone);
        rate(parallel);

        for (Long p : List.of(standalone, parallel, noRate)) {
            assertThat(post("/stock/inbound", Map.of(
                    "processedDate", "2090-01-05", "supplierClientId", supplier,
                    "destinationWarehouseId", wh,
                    "items", List.of(Map.of("productId", p, "unitCost", 100, "qty", 999))))
                    .path("success").asBoolean()).isTrue();
            sell(partner, wh, p, 10);
        }
    }

    private Long product(String code, String name, boolean ledgerVisible) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "L2090A01");
        b.put("catName", "단독검증");
        b.put("taxFree", false);
        b.put("useYn", true);
        b.put("set", false);
        b.put("ledgerVisible", ledgerVisible);
        return createId("/masters/products", b);
    }

    private void bom(Long setId, String code, String name, String type, int qtyPerSet) {
        Long mat = createId("/masters/materials",
                Map.of("code", code, "name", name, "materialType", type));
        assertThat(post("/masters/products/" + setId + "/materials",
                Map.of("materialId", mat, "qtyPerSet", qtyPerSet)).path("success").asBoolean()).isTrue();
    }

    /**
     * 단가는 **작업구분**이 들고 있고 상품은 작업구분만 고른다
     * (물류비용등록 수정요청안 Page36 — "작업구분 관리" 탭 신설).
     */
    private void rate(Long productId) {
        JsonNode r = post("/logistics-costs/products", Map.of(
                "productId", productId, "packType", packType));
        assertThat(r.path("success").asBoolean()).as("상품 단가 등록: %s", r).isTrue();
    }

    private void sell(Long partner, Long wh, Long product, int qty) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", qty);
        assertThat(post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", wh,
                "items", List.of(item))).path("success").asBoolean()).isTrue();
    }

    private com.daesung.sales.dsre.gateway.LogisCostDetailRow rowOf(String productCode) {
        return standaloneService.rows(java.time.LocalDate.parse("2090-03-01"),
                        java.time.LocalDate.parse("2090-03-31")).stream()
                .filter(r -> productCode.equals(r.productCode()))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("★단독 상품이 출고수량으로 계산된다 — 해설지는 시험지 단가를 쓴다")
    void 단독상품_계산() {
        var r = rowOf("SLA" + SFX);
        assertThat(r).as("단독 상품 행이 있다").isNotNull();

        // 출고 10부 — '인원' 자리에 그대로 들어간다
        assertThat(r.inwon()).isEqualTo(10);

        // 시험지 1×10 + 해설지 1×10 = 20장이 **시험지 단가(50)** 를 탄다 → 1,000
        assertThat(r.paperQty()).as("해설지가 시험지에 합쳐진다").isEqualTo(20);
        assertThat(r.paperAmount()).isEqualTo(20L * 50);

        // OMR 2×10 = 20 × 30 = 600
        assertThat(r.omrQty()).isEqualTo(20);
        assertThat(r.omrAmount()).isEqualTo(20L * 30);

        // 노무 = 10 × (기본 100 + 출고 200) = 3,000
        assertThat(r.basicAmount()).isEqualTo(10L * 100);
        assertThat(r.tradeAmount()).isEqualTo(10L * 200);
        assertThat(r.totalAmount()).isEqualTo(1000 + 600 + 1000 + 2000);
    }

    @Test
    @DisplayName("‼️병행 상품은 세지 않는다 — 세면 DSRE 집계와 겹쳐 금액이 두 배가 된다")
    void 병행상품은_제외() {
        assertThat(rowOf("SLB" + SFX)).as("병행 상품은 우리가 더하지 않는다").isNull();
    }

    @Test
    @DisplayName("단가 미등록 상품은 행을 만들지 않는다 — 0원 행은 사실을 묻는다")
    void 단가없으면_행없음() {
        assertThat(rowOf("SLC" + SFX)).isNull();
    }

    @Test
    @DisplayName("‼️자재 구성이 없으면 0원 행 대신 빠지고, 사유가 실린다")
    void 자재없으면_행없이_사유만() {
        // 단가는 등록하되 **자재를 하나도 매칭하지 않는다** — 운영에서 실제로 이 상태였다
        //   ('생명과학 SET'이 단가 50/50/50 인데 자재 0건이라 전 항목 0원으로 나갔다).
        Long p = product("SLD" + SFX, "자재없는상품", true);
        // ★운영과 같은 모양으로 만든다 — 작업구분에 **기본·출고 작업비가 0**이다.
        //   그 둘이 있으면 자재가 없어도 노무비가 잡혀 행이 정상적으로 나온다(그게 맞다).
        //   운영의 '생명과학 SET'이 바로 basic=0·trade=0 + 자재 0건이라 전 항목이 0원이었다.
        int zeroLabor = 7500 + (int) (System.nanoTime() % 400);
        assertThat(post("/masters/work-types", Map.of(
                "packType", zeroLabor, "name", "노무비없음" + SFX,
                "paper", 50, "omr", 30, "etc", 10, "label", 5,
                "basic", 0, "trade", 0)).path("success").asBoolean()).isTrue();
        assertThat(post("/logistics-costs/products",
                Map.of("productId", p, "packType", zeroLabor)).path("success").asBoolean()).isTrue();
        Long sup2 = createId("/masters/clients",
                Map.of("code", "SLS2" + SFX, "name", "인쇄2", "type", "NORMAL"));
        Long pt2 = createId("/masters/clients",
                Map.of("code", "SLP2" + SFX, "name", "자재없는거래처", "type", "NORMAL"));
        Long wh2 = createId("/masters/warehouses",
                Map.of("code", "SLW2" + SFX, "name", "물류2", "type", "MAIN"));
        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2090-01-05", "supplierClientId", sup2,
                "destinationWarehouseId", wh2,
                "items", List.of(Map.of("productId", p, "unitCost", 100, "qty", 999))))
                .path("success").asBoolean()).isTrue();
        // ‼️**팔아야** 집계 대상이 된다 — missingRateProducts 는 그 기간에 출고된 상품만 훑는다.
        sell(pt2, wh2, p, 10);

        // ★0원 행이 아니라 **행이 아예 없어야** 한다.
        assertThat(rowOf("SLD" + SFX)).as("0원 행을 끼워 넣지 않는다").isNull();

        // ★대신 사유가 나와야 한다 — 아무 설명 없이 사라지면 빠진 줄이 있다는 것조차 모른다.
        List<MissingProduct> missing = missing();
        assertThat(missing).anyMatch(m -> m.productCode().equals("SLD" + SFX)
                && m.reason() == MissingReason.NO_MATERIAL);
        // 단가 자체가 없는 상품은 다른 사유로 잡힌다 — 열어야 할 화면이 다르기 때문이다.
        assertThat(missing).anyMatch(m -> m.productCode().equals("SLC" + SFX)
                && m.reason() == MissingReason.NO_RATE);
    }

    @Test
    @DisplayName("‼️자재가 없어도 노무비가 잡히면 행이 나가고, '빠졌다' 목록에는 없다")
    void 자재없어도_노무비있으면_목록에_없음() {
        // 전에는 자재 구성이 없으면 무조건 "자재 구성 없음"으로 올렸다 —
        //   그런데 기본·출고 작업비가 있으면 그 상품은 행이 정상으로 나간다.
        //   나간 상품을 빠졌다고 알리면 담당자는 할 필요 없는 매칭을 하러 간다.
        Long p = product("SLE" + SFX, "노무비만상품", true);
        rate(p);   // 기본 100 · 출고 200 이 있는 작업구분
        Long sup = createId("/masters/clients",
                Map.of("code", "SLS3" + SFX, "name", "인쇄3", "type", "NORMAL"));
        Long pt = createId("/masters/clients",
                Map.of("code", "SLP3" + SFX, "name", "노무비거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "SLW3" + SFX, "name", "물류3", "type", "MAIN"));
        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2090-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", p, "unitCost", 100, "qty", 999))))
                .path("success").asBoolean()).isTrue();
        sell(pt, wh, p, 10);

        var row = rowOf("SLE" + SFX);
        assertThat(row).as("노무비가 있으니 행이 나간다").isNotNull();
        assertThat(row.totalAmount()).isEqualTo(10L * (100 + 200));
        assertThat(missing()).noneMatch(m -> m.productCode().equals("SLE" + SFX));
    }

    @Test
    @DisplayName("빠진 상품은 전건 나열 대신 건수·사유별 건수·표본 10건으로 요약한다")
    void 빠진상품_요약() {
        // 운영 2026-09: 단가 마스터가 비어 400여 개가 걸렸다 — 전건 배열은 경고가 아니라 소음이다.
        List<MissingProduct> all = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            all.add(new MissingProduct(String.format("P%03d", i), "상품" + i, MissingReason.NO_RATE));
        }
        all.add(new MissingProduct("Z999", "자재없는세트", MissingReason.NO_MATERIAL));

        MissingRate s = MissingRate.of(all);
        assertThat(s.total()).isEqualTo(401);
        assertThat(s.reasons()).extracting(ReasonCount::reason, ReasonCount::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MissingReason.NO_RATE, 400),
                        org.assertj.core.groups.Tuple.tuple(MissingReason.NO_MATERIAL, 1));
        assertThat(s.samples()).hasSize(LogisCostDetailResponse.MISSING_SAMPLE_LIMIT);
        // ★1건짜리 사유가 400건에 묻히지 않는다 — 코드 순으로만 자르면 Z999 는 안 보인다.
        assertThat(s.samples()).anyMatch(m -> m.productCode().equals("Z999"));

        assertThat(MissingRate.of(List.of()).total()).isZero();
    }

    private List<MissingProduct> missing() {
        return standaloneService.missingProducts(
                java.time.LocalDate.parse("2090-03-01"), java.time.LocalDate.parse("2090-03-31"));
    }
}
