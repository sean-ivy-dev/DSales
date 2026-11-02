package com.daesung.sales.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 폐기 내역 조회(10p) 회귀 고정.
 * 근거: 프론트 실호출 실측(갭리포트 §P-2) — {@code GET /disposals}가 <b>405</b>였다.
 * 등록(POST)만 있고 조회가 없어 "무엇을 언제 왜 버렸는지"를 되짚을 수 없었다.
 *
 * <p>★중간보고서 10p에는 "도서별·창고별 조회 토글 구현"으로 나갔던 항목이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("폐기 내역 조회(10p)")
class DisposalListIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DP" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2056;

    private Long wh;
    private Long product;
    private String disposalNo;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "DPS" + SFX, "name", "인쇄", "type", "NORMAL"));
        wh = createId("/masters/warehouses", Map.of("code", "DPW" + SFX, "name", "폐기창고", "type", "MAIN"));
        product = createId("/masters/products", Map.of("code", "DPB" + SFX, "name", "폐기도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "catCode", "D2056" + (System.nanoTime() % 100), "catName", "폐기분류",
                "salesDivision", "교재"));

        post("/stock/inbound", Map.of("processedDate", YEAR + "-04-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", product, "unitCost", 3000, "qty", 100))));
        JsonNode d = post("/disposals", Map.of("processedDate", YEAR + "-04-10", "warehouseId", wh,
                "items", List.of(Map.of("productId", product, "qty", 7, "reason", "파본"))));
        assertThat(d.path("success").asBoolean()).as("폐기 등록: %s", d).isTrue();
        disposalNo = d.path("data").path("disposalNo").asText();
    }

    private JsonNode row() {
        for (JsonNode r : rows(get("/disposals?size=200&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&productId=" + product))) {
            if (disposalNo.equals(r.path("disposalNo").asText())) {
                return r;
            }
        }
        throw new AssertionError("폐기 내역에 없음: " + disposalNo);
    }

    @Test
    @DisplayName("★등록한 폐기가 조회된다 — 예전엔 405로 아예 못 불렀다")
    void 조회된다() {
        JsonNode r = row();
        assertThat(r.path("disposalNo").asText()).startsWith("P-");
        assertThat(r.path("date").asText()).isEqualTo(YEAR + "-04-10");
        assertThat(r.path("warehouse").asText()).isEqualTo("폐기창고");
        assertThat(r.path("bookName").asText()).isEqualTo("폐기도서");
        assertThat(r.path("catName").asText()).isEqualTo("폐기분류");
        assertThat(r.path("reason").asText()).isEqualTo("파본");
    }

    @Test
    @DisplayName("★수량은 양수로 보인다 — 원장은 음수지만 '7권 버렸다'가 −7로 보이면 안 된다")
    void 수량은_양수() {
        assertThat(row().path("qty").asInt()).isEqualTo(7);

        // 원장(수불부)에서는 여전히 음수다 — 재고를 깎는 이벤트이기 때문
        JsonNode ledger = data(get("/stock/ledger?fromDate=" + YEAR + "-01-01&toDate=" + YEAR
                + "-12-31&productId=" + product)).path("content").get(0);
        assertThat(ledger.path("dispose").asInt()).as("원장은 음수 그대로").isEqualTo(-7);
        assertThat(ledger.path("closing").asInt()).as("100 − 7").isEqualTo(93);
    }

    @Test
    @DisplayName("창고·기간으로 좁혀진다")
    void 필터() {
        String base = "/disposals?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";
        assertThat(data(get(base + "&warehouseId=" + wh)).size()).isPositive();
        // 폐기일 이전까지만 보면 안 잡힌다
        assertThat(rows(get("/disposals?size=200&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-04-09"
                + "&productId=" + product)).size()).isZero();
    }

    /**
     * 폐기 목록은 <b>페이지</b>로 온다(9/27 항목 10 "페이지네이션 필수").
     * 검증은 전량을 봐야 해서 size 를 크게 주고 content 만 꺼낸다.
     */
    private com.fasterxml.jackson.databind.JsonNode rows(com.fasterxml.jackson.databind.JsonNode res) {
        return data(res).path("content");
    }
}
