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
 * 폐기 <b>분류명별 요약</b>(10p) + 수불부 노출 <b>분류 단위 일괄 설정</b> 회귀 고정.
 * 근거: 발주처 화면검토 확인요청서·구조보완요청안(2026-08-31).
 *
 * <ul>
 *   <li>화면6: "폐기 내역 조회시에도 <b>분류명 별로</b> 요약(전체)/상세가 모두 조회 가능한지"</li>
 *   <li>구조보완 2-2: "수불부/단가 노출 Y/N 값은 … <b>분류명(콘텐츠) 단위로 설정</b>됩니다"</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("폐기 분류별 요약 · 수불부노출 분류 단위 설정")
class DisposalSummaryIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DS" + (System.nanoTime() % 1_000_000L);
    private static final String CAT = "D2060" + (System.nanoTime() % 100);
    private static final int YEAR = 2060;

    private Long wh;
    private Long bookA;
    private Long bookB;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "DSS" + SFX, "name", "인쇄", "type", "NORMAL"));
        wh = createId("/masters/warehouses", Map.of("code", "DSW" + SFX, "name", "폐기창고", "type", "MAIN"));

        // 같은 분류의 도서 2권 — 요약이 둘을 합쳐야 한다
        bookA = createId("/masters/products", Map.of("code", "DSA" + SFX, "name", "폐기도서A",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "catCode", CAT, "catName", "폐기분류",
                "salesDivision", "교재"));
        bookB = createId("/masters/products", Map.of("code", "DSB" + SFX, "name", "폐기도서B",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "catCode", CAT, "catName", "폐기분류",
                "salesDivision", "교재"));

        post("/stock/inbound", Map.of("processedDate", YEAR + "-04-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", bookA, "unitCost", 3000, "qty", 100),
                        Map.of("productId", bookB, "unitCost", 3000, "qty", 100))));
        post("/disposals", Map.of("processedDate", YEAR + "-04-10", "warehouseId", wh,
                "items", List.of(Map.of("productId", bookA, "qty", 7, "reason", "파본"),
                        Map.of("productId", bookB, "qty", 3, "reason", "파본"))));
    }

    @Test
    @DisplayName("★분류별 요약 — 같은 분류의 도서가 한 줄로 합쳐진다")
    void 분류별_요약() {
        JsonNode d = data(get("/disposals/summary?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&warehouseId=" + wh));

        assertThat(d.path("rows")).hasSize(1);
        JsonNode row = d.path("rows").get(0);
        assertThat(row.path("catCode").asText()).isEqualTo(CAT);
        assertThat(row.path("catName").asText()).isEqualTo("폐기분류");
        assertThat(row.path("count").asLong()).as("도서 2건").isEqualTo(2);
        assertThat(row.path("qty").asLong()).as("7 + 3, 양수로").isEqualTo(10);

        assertThat(d.path("totalQty").asLong()).isEqualTo(10);
    }

    @Test
    @DisplayName("요약과 상세가 같은 수량을 말한다 — 두 화면이 갈리면 둘 다 못 믿는다")
    void 요약과_상세가_일치() {
        String range = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31&warehouseId=" + wh;
        long detailSum = 0;
        for (JsonNode r : rows(get("/disposals?size=200&" + range.substring(1)))) {
            detailSum += r.path("qty").asLong();
        }
        assertThat(data(get("/disposals/summary" + range)).path("totalQty").asLong())
                .isEqualTo(detailSum);
    }

    @Test
    @DisplayName("★수불부노출을 분류 단위로 끈다 — 그 분류 도서가 한 번에 빠진다")
    void 수불부노출_분류단위() {
        String range = "/stock/ledger?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";
        assertThat(codesIn(range)).contains("DSA" + SFX, "DSB" + SFX);

        // 분류코드만 주면 그 분류 전체가 대상(도서 id를 일일이 고르지 않는다)
        JsonNode raw = put("/masters/products/flags",
                Map.of("catCode", CAT, "ledgerVisible", false));
        assertThat(raw.path("success").asBoolean()).as("일괄 적용: %s", raw).isTrue();
        assertThat(raw.path("data").path("changed").asInt()).as("도서 2권: %s", raw).isEqualTo(2);

        assertThat(codesIn(range))
                .as("분류 단위로 껐으니 둘 다 빠져야 한다")
                .doesNotContain("DSA" + SFX, "DSB" + SFX);

        // 되돌린다 — 다른 테스트가 이 도서를 볼 수 있다
        put("/masters/products/flags", Map.of("catCode", CAT, "ledgerVisible", true));
        assertThat(codesIn(range)).contains("DSA" + SFX, "DSB" + SFX);
    }

    @Test
    @DisplayName("대상이 없으면 거부 — 조용히 0건 처리하면 눌렀는데 아무 일도 안 난 걸 모른다")
    void 대상없으면_거부() {
        JsonNode r = put("/masters/products/flags",
                Map.of("catCode", "없는분류코드", "ledgerVisible", false));
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("대상이 없습니다");
    }

    private List<String> codesIn(String url) {
        List<String> out = new java.util.ArrayList<>();
        data(get(url)).path("content").forEach(n -> out.add(n.path("productCode").asText()));
        return out;
    }

    /**
     * 폐기 목록은 <b>페이지</b>로 온다(9/27 항목 10 "페이지네이션 필수").
     * 검증은 전량을 봐야 해서 size 를 크게 주고 content 만 꺼낸다.
     */
    private com.fasterxml.jackson.databind.JsonNode rows(com.fasterxml.jackson.databind.JsonNode res) {
        return data(res).path("content");
    }
}
