package com.daesung.sales.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 공통 자재의 <b>회차 반복형</b>(V61 {@code per_round}) 회귀 고정.
 *
 * <p>근거: 「도서관리·제품수불부현황 데이터 구조 보완 요청안」(2026-08-31) 각주 —
 * "세트당 소요수량은 '공통' 매칭 자재도 세트 내 <b>회차 반복 여부를 고려한 최종 수량</b>으로
 * 입력합니다 — 회차마다 반복 사용되는 자재(예: OMR, 위 표는 <b>4회차 구성 기준 4</b>)는
 * <b>회차 수만큼 반영한 값</b>을, 세트 전체에 한 번만 필요한 자재(예: 해설강의쿠폰)는
 * <b>1로 고정</b>한 값을 입력합니다."
 *
 * <p>★문서가 공통 자재를 두 종류로 나눠 놓았는데 <b>숫자만으로는 구분되지 않는다</b> —
 * {@code 4}가 "4회차 × 1"인지 "세트당 4개 고정"인지 알 수 없다. 그래서 플래그를 뒀고,
 * 이 테스트가 그 둘이 실제로 다르게 계산되는지를 고정한다.
 *
 * <p>픽스처는 문서 예시 그대로 <b>4회차 구성</b>이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("공통 자재 회차 반복형(V61)")
class MaterialPerRoundIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PR" + (System.nanoTime() % 1_000_000L);
    /** 다른 테스트의 전역 집계와 겹치지 않는 해. */
    private static final int YEAR = 2066;

    private Long setP;
    private final List<Long> rounds = new ArrayList<>();
    private Long omr;        // 회차 반복형 — 4회차 기준 4
    private Long coupon;     // 세트 1회형 — 1 고정

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "PRS" + SFX, "name", "인쇄", "type", "NORMAL"));
        Long partner = createId("/masters/clients", Map.of("code", "PRP" + SFX, "name", "거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "PRW" + SFX, "name", "물류창고", "type", "MAIN"));

        setP = createId("/masters/products", Map.of("code", "PRSET" + SFX, "name", "국어 시즌1 SET",
                "contentType", "SELF", "price", 40000, "supplyRate", 70,
                "salesDivision", "교재"));
        for (int i = 1; i <= 4; i++) {
            rounds.add(createId("/masters/products", Map.of("code", "PRR" + i + SFX, "name", i + "회",
                    "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재")));
        }

        omr = data(post("/masters/materials", Map.of("code", "PROM" + SFX,
                "name", "국어 OMR", "materialType", "OMR"))).path("id").asLong();
        coupon = data(post("/masters/materials", Map.of("code", "PRCP" + SFX,
                "name", "해설강의쿠폰", "materialType", "LABEL"))).path("id").asLong();

        // 회차 전용 시험지 4종 — 이게 있어야 '회차 수 4'가 성립한다
        for (int i = 0; i < 4; i++) {
            Long paper = data(post("/masters/materials", Map.of("code", "PRPP" + i + SFX,
                    "name", "시험지 " + (i + 1) + "회", "materialType", "EXAM_PAPER"))).path("id").asLong();
            post("/masters/products/" + setP + "/materials",
                    Map.of("materialId", paper, "roundProductId", rounds.get(i), "qtyPerSet", 1));
        }

        // ★문서 예시: OMR은 4회차 구성 기준 4(반복형) · 쿠폰은 1 고정(1회형)
        post("/masters/products/" + setP + "/materials",
                Map.of("materialId", omr, "qtyPerSet", 4, "perRound", true));
        post("/masters/products/" + setP + "/materials",
                Map.of("materialId", coupon, "qtyPerSet", 1, "perRound", false));

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", setP, "unitCost", 1000, "qty", 2000),
                        Map.of("productId", rounds.get(0), "unitCost", 300, "qty", 1000))));

        // 세트 500 출고 + 1회만 단독 100 출고
        post("/sales/entries", Map.of("salesDate", YEAR + "-02-01", "partnerId", partner, "warehouseId", wh,
                "items", List.of(
                        Map.of("productId", setP, "shipmentType", "NORMAL_SHIP",
                                "unitPrice", 40000, "supplyRate", 70, "qty", 500),
                        Map.of("productId", rounds.get(0), "shipmentType", "NORMAL_SHIP",
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
    @DisplayName("★반복형(OMR) — 회차 단독 출고에도 나간다. 회차당 = 4 ÷ 4회차 = 1")
    void 반복형은_회차단독분이_잡힌다() {
        JsonNode r = rowOf(detail(""), omr);

        assertThat(r.path("qtyPerSet").asInt()).as("입력값은 세트당 4 그대로").isEqualTo(4);
        assertThat(r.path("fromSet").asLong()).as("세트 500 × 4").isEqualTo(2000);
        assertThat(r.path("fromRound").asLong())
                .as("‼️1회 단독 100장 × (4÷4=1) = 100. 예전엔 0이라 이만큼이 통째로 빠져 있었다")
                .isEqualTo(100);
        assertThat(r.path("total").asLong()).isEqualTo(2100);
    }

    @Test
    @DisplayName("★1회형(쿠폰) — 세트를 사야 붙는다. 회차만 팔리면 0")
    void 일회형은_회차단독분이_없다() {
        JsonNode r = rowOf(detail(""), coupon);

        assertThat(r.path("fromSet").asLong()).as("세트 500 × 1").isEqualTo(500);
        assertThat(r.path("fromRound").asLong())
                .as("세트 전체에 한 번만 필요한 자재라 회차 단독 출고에는 붙지 않는다").isZero();
        assertThat(r.path("total").asLong()).isEqualTo(500);
    }

    @Test
    @DisplayName("★같은 숫자 4라도 반복형과 1회형은 다르게 계산된다 — 플래그를 둔 이유")
    void 같은_숫자라도_구분된다() {
        // 쿠폰을 4로 올리되 1회형 그대로 → 회차 단독분은 여전히 0이어야 한다.
        post("/masters/products/" + setP + "/materials",
                Map.of("materialId", coupon, "qtyPerSet", 4, "perRound", false));

        JsonNode c = rowOf(detail(""), coupon);
        JsonNode o = rowOf(detail(""), omr);

        assertThat(c.path("qtyPerSet").asInt()).isEqualTo(4);
        assertThat(o.path("qtyPerSet").asInt()).isEqualTo(4);
        assertThat(c.path("fromRound").asLong()).as("1회형: 0").isZero();
        assertThat(o.path("fromRound").asLong()).as("반복형: 100").isEqualTo(100);

        // 되돌린다 — 다른 테스트가 이 매칭을 본다
        post("/masters/products/" + setP + "/materials",
                Map.of("materialId", coupon, "qtyPerSet", 1, "perRound", false));
    }

    @Test
    @DisplayName("‼️1회만 골라도 회차 수는 4로 센다 — 1로 세면 OMR이 4배로 뻥튀기된다")
    void 회차수는_필터_전_기준() {
        JsonNode r = rowOf(detail("&roundProductId=" + rounds.get(0)), omr);

        assertThat(r.path("fromRound").asLong())
                .as("100 × (4÷4=1) = 100. 회차수를 1로 세면 100×4=400이 된다")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("회차 전용 자재는 종전대로 — 그 회차 단독출고 × 소요수량")
    void 회차전용은_그대로() {
        JsonNode res = detail("&roundProductId=" + rounds.get(0));
        JsonNode paper = null;
        for (JsonNode n : res.path("rows")) {
            if ("EXAM_PAPER".equals(n.path("materialType").asText())) {
                paper = n;
            }
        }
        assertThat(paper).isNotNull();
        assertThat(paper.path("fromSet").asLong()).as("500 × 1").isEqualTo(500);
        assertThat(paper.path("fromRound").asLong()).as("100 × 1").isEqualTo(100);
    }
}
