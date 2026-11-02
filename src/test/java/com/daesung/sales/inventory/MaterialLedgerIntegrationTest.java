package com.daesung.sales.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 제품수불부 <b>자재 상세</b>(11p 2단계) 회귀 고정.
 * 근거: 발주처 「도서관리·제품수불부현황 데이터 구조 보완 요청안」(2026-08-31) 예시 —
 *
 * <pre>
 * 요약  2026 D.ARCHIVE 국어 시즌1 SET   500
 * 요약   └ 1회                        100
 * 상세  (1회 선택) └ 시험지            600   ← 세트 500 + 회차단독 100
 * 상세  (1회 선택) └ 해설지            600
 * </pre>
 *
 * <p>★<b>자재 재고가 아니라 소요량</b>이다. 자재를 얼마나 들여왔는지는 보지 않는다 —
 * 세트가 나간 만큼 자재가 몇 장 들어갔는지만 센다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// ★순서가 있다 — 마지막 반품 테스트가 수치를 바꾸므로 예시 검증이 먼저 와야 한다.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("제품수불부 자재 상세(11p 2단계)")
class MaterialLedgerIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-ML" + (System.nanoTime() % 1_000_000L);
    /** 다른 테스트의 전역 집계와 겹치지 않는 해. */
    private static final int YEAR = 2059;

    private Long setP;
    private Long round1;
    private Long paper;
    private Long answer;
    private Long omr;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "MLS" + SFX, "name", "인쇄", "type", "NORMAL"));
        Long partner = createId("/masters/clients", Map.of("code", "MLP" + SFX, "name", "거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "MLW" + SFX, "name", "물류창고", "type", "MAIN"));

        setP = createId("/masters/products", Map.of("code", "MLSET" + SFX, "name", "국어 시즌1 SET",
                "contentType", "SELF", "price", 40000, "supplyRate", 70,
                "salesDivision", "교재"));
        round1 = createId("/masters/products", Map.of("code", "MLR1" + SFX, "name", "1회",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재"));

        paper = data(post("/masters/materials", Map.of("code", "MLPP" + SFX,
                "name", "국어 시즌1_01회", "materialType", "EXAM_PAPER"))).path("id").asLong();
        answer = data(post("/masters/materials", Map.of("code", "MLAN" + SFX,
                "name", "국어 시즌1_01회_정답및해설", "materialType", "ANSWER_SHEET"))).path("id").asLong();
        omr = data(post("/masters/materials", Map.of("code", "MLOM" + SFX,
                "name", "국어 OMR", "materialType", "OMR"))).path("id").asLong();

        // 1회 전용: 시험지 1 · 해설지 1 / 공통: OMR 4
        post("/masters/products/" + setP + "/materials",
                Map.of("materialId", paper, "roundProductId", round1, "qtyPerSet", 1));
        post("/masters/products/" + setP + "/materials",
                Map.of("materialId", answer, "roundProductId", round1, "qtyPerSet", 1));
        post("/masters/products/" + setP + "/materials", Map.of("materialId", omr, "qtyPerSet", 4));

        // 입고 후 SET 500 · 1회 100 출고 (발주처 예시 수치)
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", setP, "unitCost", 1000, "qty", 1000),
                        Map.of("productId", round1, "unitCost", 300, "qty", 1000))));
        post("/sales/entries", Map.of("salesDate", YEAR + "-02-01", "partnerId", partner, "warehouseId", wh,
                "items", List.of(
                        Map.of("productId", setP, "shipmentType", "NORMAL_SHIP",
                                "unitPrice", 40000, "supplyRate", 70, "qty", 500),
                        Map.of("productId", round1, "shipmentType", "NORMAL_SHIP",
                                "unitPrice", 10000, "supplyRate", 70, "qty", 100))));
    }

    private JsonNode detail(String extra) {
        return data(get("/stock/ledger/materials?setProductId=" + setP
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31" + extra));
    }

    private JsonNode rowOf(JsonNode res, long materialId) {
        for (JsonNode r : res.path("rows")) {
            if (r.path("materialId").asLong() == materialId) {
                return r;
            }
        }
        throw new AssertionError("자재 행 없음: " + materialId + " / " + res);
    }

    @Test
    @Order(1)
    @DisplayName("★1회 선택 — 시험지·해설지 600 (세트 500 + 회차단독 100)")
    void 발주처_예시_그대로() {
        JsonNode res = detail("&roundProductId=" + round1);

        assertThat(res.path("setConsumedQty").asLong()).as("세트 출고 500").isEqualTo(500);

        for (long id : new long[] {paper, answer}) {
            JsonNode r = rowOf(res, id);
            assertThat(r.path("qtyPerSet").asInt()).isEqualTo(1);
            assertThat(r.path("fromSet").asLong()).as("500 × 1").isEqualTo(500);
            assertThat(r.path("fromRound").asLong()).as("100 × 1").isEqualTo(100);
            assertThat(r.path("total").asLong()).as("문서의 600").isEqualTo(600);
            assertThat(r.path("roundLabel").asText()).isEqualTo("1회");
        }
    }

    @Test
    @Order(2)
    @DisplayName("공통 1회형 자재는 세트 출고분만 — 세트를 사야 붙는다")
    void 공통자재는_세트분만() {
        JsonNode r = rowOf(detail(""), omr);

        assertThat(r.path("roundLabel").asText()).isEqualTo("공통");
        assertThat(r.path("fromSet").asLong()).as("500 × 4").isEqualTo(2000);
        // perRound를 안 켰으므로 '세트 전체에 한 번' 자재로 본다(V61 기본값).
        // 회차 반복형(OMR 등)은 MaterialPerRoundIntegrationTest에서 따로 고정한다.
        assertThat(r.path("fromRound").asLong())
                .as("1회형은 회차만 팔릴 때 나가지 않는다").isZero();
        assertThat(r.path("total").asLong()).isEqualTo(2000);
    }

    @Test
    @Order(3)
    @DisplayName("회차를 안 고르면 세트 전체 자재가 나온다")
    void 세트전체() {
        assertThat(detail("").path("rows")).hasSize(3);   // 시험지·해설지·OMR
    }

    @Test
    @Order(4)
    @DisplayName("★반품은 소요량에서 차감된다 — 되돌아온 만큼 자재도 안 쓴 것이다")
    void 반품은_차감() {
        long before = rowOf(detail("&roundProductId=" + round1), paper).path("total").asLong();

        // 1회 20부 반품 → 회차 단독분이 20 줄어야 한다
        post("/sales/return-inbound", Map.of("returnDate", YEAR + "-03-01",
                "partnerId", partnerIdOf(), "warehouseId", warehouseIdOf(),
                "items", List.of(Map.of("productId", round1, "qty", 20,
                        "unitPrice", 10000, "supplyRate", 70))));

        long after = rowOf(detail("&roundProductId=" + round1), paper).path("total").asLong();
        assertThat(after).as("600 − 20").isEqualTo(before - 20);
    }

    private Long partnerIdOf() {
        for (JsonNode c : data(get("/masters/clients?size=200")).path("content")) {
            if (("MLP" + SFX).equals(c.path("code").asText())) {
                return c.path("id").asLong();
            }
        }
        throw new AssertionError("거래처 없음");
    }

    private Long warehouseIdOf() {
        for (JsonNode w : data(get("/masters/warehouses?size=200")).path("content")) {
            if (("MLW" + SFX).equals(w.path("code").asText())) {
                return w.path("id").asLong();
            }
        }
        throw new AssertionError("창고 없음");
    }
}
