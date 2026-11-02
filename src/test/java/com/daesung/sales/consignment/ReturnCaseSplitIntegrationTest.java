package com.daesung.sales.consignment;

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
 * 위탁 반품 <b>Case1/Case2 자동 분해</b> 회귀 고정.
 * 근거: 발주처 화면검토 확인요청서(2026-08-31) 원문 예시 —
 *
 * <blockquote>
 * "100부 위탁출고 중 60부 정산 확정(미결잔여 40부) 상태에서 반품 50부가 등록되면,
 * 40부는 Case2(위탁창고→물류창고 재고 복구, 매출 영향 없음)로,
 * 초과분 10부는 Case1(기존 정산 확정분에 대한 반품, 정산 수량을 50부로 정정 +
 * 매출 마이너스 반영)으로 처리하여 운영합니다."
 * </blockquote>
 *
 * <p>★같은 회신에서 <b>초과 차단을 제거</b>하라고 했다 — "자동 차단하던 기존 로직은 제거,
 * 초과 시 경고 알림(alert)만 표시하고 이후 처리는 담당자가 수기로".
 * 그래서 이 테스트는 "막히는지"가 아니라 <b>어떻게 갈라지는지</b>를 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// ★순서가 있다 — 하나의 미결이 반품을 거치며 상태가 바뀌는 흐름을 따라간다.
//   순서를 안 정하면 "반품 결과"를 반품 전에 확인하게 된다.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("위탁 반품 Case1/Case2 분해(발주처 100/60/50 예시)")
class ReturnCaseSplitIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CS" + (System.nanoTime() % 1_000_000L);
    /** 다른 테스트의 전역 연간 집계와 겹치지 않는 해. */
    private static final int YEAR = 2058;

    private Long partner;
    private Long product;
    private Long mainWh;
    private Long consignWh;
    private long outId;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "CSS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "CSP" + SFX, "name", "위탁처", "type", "NORMAL"));
        mainWh = createId("/masters/warehouses", Map.of("code", "CSW" + SFX, "name", "물류창고", "type", "MAIN"));
        consignWh = createId("/masters/warehouses", Map.of("code", "CSC" + SFX, "name", "위탁창고",
                "type", "CONSIGN", "ownerClientId", partner));
        product = createId("/masters/products", Map.of("code", "CSB" + SFX, "name", "위탁도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재"));

        post("/stock/inbound", Map.of("processedDate", YEAR + "-03-01", "supplierClientId", sup,
                "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", product, "unitCost", 3000, "qty", 500))));
        // 위탁출고 100
        post("/consignment/out", Map.of("processedDate", YEAR + "-03-05",
                "partnerId", partner, "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", product, "qty", 100))));
        outId = pending().path("consignmentOutId").asLong();

        // 60 정산 확정 → 미결잔여 40
        JsonNode s = post("/consignment/settle", Map.of("salesDate", YEAR + "-03-10",
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", 60,
                        "unitPrice", 10000, "supplyRate", 70))));
        assertThat(s.path("success").asBoolean()).as("60 정산: %s", s).isTrue();
    }

    private JsonNode pending() {
        return data(get("/consignment/pending?partnerId=" + partner)).path("items").get(0);
    }

    @Test
    @Order(1)
    @DisplayName("★반품 50 = Case2 40 + Case1 10 — 발주처 예시 그대로")
    void 예시대로_갈린다() {
        JsonNode before = pending();
        assertThat(before.path("settledQty").asInt()).isEqualTo(60);
        assertThat(before.path("remainingQty").asInt()).isEqualTo(40);

        JsonNode r = post("/consignment/return", Map.of("processedDate", YEAR + "-03-20",
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 50))));
        assertThat(r.path("success").asBoolean()).as("초과여도 등록된다: %s", r).isTrue();

        JsonNode line = r.path("data").path("items").get(0);
        assertThat(line.path("returnCase2Qty").asInt()).as("미결잔여만큼").isEqualTo(40);
        assertThat(line.path("returnCase1Qty").asInt()).as("초과분 = 확정매출분 반품").isEqualTo(10);
        assertThat(line.path("overWarning").asText())
                .as("경고는 주되 막지는 않는다").contains("초과");

        // 잔여가 0이 되어 미결 목록에서는 빠진다(정상 — 닫힌 건이다).
        // 상태는 정산내역서로 확인한다.
        assertThat(line.path("remainingQty").asInt()).as("Case2로 다 빠짐").isZero();
        assertThat(line.path("status").asText()).isEqualTo("CLOSED");

        // ‼️정산내역서는 salesDate가 아니라 **settled_at(정산 처리 시각 = 지금)** 기준이라
        //   미래연도(YEAR)로 조회하면 안 잡힌다. 범위를 넓게 잡고 거래처로 좁힌다.
        JsonNode st = data(get("/consignment/settlement-statement"
                + "?fromDate=2020-01-01&toDate=2099-12-31&partnerId=" + partner)).path("rows").get(0);
        // 정산 60 → 10 되돌아가 50. 발주처 원문 "정산 수량을 50부로 정정"과 일치.
        assertThat(st.path("settledQtyCum").asInt()).as("60 − 10: %s", st).isEqualTo(50);
        assertThat(st.path("remainingQty").asInt()).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("★Case1은 매출 마이너스로 남는다 — 반품(RETURN) 라인이 생긴다")
    void case1은_매출반품을_만든다() {
        JsonNode sales = data(get("/sales?startDate=" + YEAR + "-01-01&endDate=" + YEAR + "-12-31"
                + "&partnerId=" + partner + "&salesCategory=RETURN"));

        JsonNode ret = null;
        for (JsonNode s : sales.path("content")) {
            if (s.path("productId").asLong() == product) {
                ret = s;
            }
        }
        assertThat(ret).as("Case1 반품 매출 라인이 있어야: %s", sales).isNotNull();
        // ★10/27 피드백 §3-2 — 반품은 음수로 내려온다(저장은 여전히 양수 10,
        //   qty=-10/supplyAmount=-70000은 /sales 응답(조회) 전용 변환이다). 합계가
        //   반품을 그냥 더하기만 해도 자동 상쇄되므로, 이 테스트 제목 "마이너스로 남는다"가
        //   이제 그대로 값에 드러난다.
        assertThat(ret.path("qty").asInt()).isEqualTo(-10);
        // 정가·공급률을 안 줬으므로 마지막 위탁정산 매출(10000·70%)을 따라간다
        assertThat(ret.path("unitPrice").asInt()).isEqualTo(10000);
        assertThat(ret.path("supplyRate").asInt()).isEqualTo(70);
        assertThat(ret.path("supplyAmount").asLong()).as("-(10000 × 70% × 10)").isEqualTo(-70_000);
    }

    @Test
    @Order(3)
    @DisplayName("나간 것보다 많이는 못 돌려받는다 — 이건 여전히 거부")
    void 출고수량_초과는_거부() {
        // 잔여 0 · 정산누적 50 → 최대 50까지만. 발주처가 푼 것은 '미결잔여 초과'이지
        // '원 출고수량 초과'가 아니다. 풀면 정산누적·총출고가 음수가 되어 장부가 깨진다.
        JsonNode r = post("/consignment/return", Map.of("processedDate", YEAR + "-03-25",
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 999))));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(r.path("error").path("message").asText()).contains("나간 수량을 초과");
    }
}
