package com.daesung.sales.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
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
 * 수불부노출 · 단가노출 <b>분리</b>(V60) 회귀 고정.
 * 근거: 프론트 회신 2026-08-21 §E-7 — "한 필드에 <b>두 가지 뜻</b>이 실립니다."
 *
 * <p>33p 화면기획안 컬럼명이 「수불부/단가 노출(Y/N)」 하나라 우리도 한 값으로 만들었는데,
 * 두 판단은 실제로 다르다 —
 * <ul>
 *   <li><b>수불부 노출</b>: 제품수불부(11p) 집계에 넣을지. DSRE가 수불을 관리하면 빼야 한다.</li>
 *   <li><b>단가 노출</b>: 거래처별 단가를 매기는 도서인지.</li>
 * </ul>
 * "수불부엔 안 나오지만 단가는 매긴다"가 가능해야 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// ★순서가 있다 — 앞 테스트가 만든 상태(수불부 N·단가 Y 등)를 뒤 테스트가 필터로 확인한다.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("수불부노출·단가노출 분리(V60)")
class VisibilitySplitIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-VS" + (System.nanoTime() % 1_000_000L);
    private static final String CAT = "V2061" + (System.nanoTime() % 100);

    private Long bookA;
    private Long bookB;

    @BeforeAll
    void seed() {
        token();
        bookA = create("VSA" + SFX, "분리도서A");
        bookB = create("VSB" + SFX, "분리도서B");
    }

    /** Map.of는 10쌍까지라 항목이 더 많은 이 요청은 HashMap으로 만든다. */
    private Long create(String code, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("contentType", "SELF");
        m.put("price", 10000);
        m.put("supplyRate", 70);
        m.put("catCode", CAT);
        m.put("catName", "분리분류");
        m.put("salesDivision", "교재");
        return createId("/masters/products", m);
    }

    private JsonNode productOf(Long id) {
        return data(get("/masters/products/" + id));
    }

    @Test
    @Order(1)
    @DisplayName("★기존 값이 그대로 옮겨온다 — 나누는 것이지 판정을 바꾸는 게 아니다")
    void 초기값은_동일() {
        JsonNode p = productOf(bookA);
        assertThat(p.path("ledgerVisible").asBoolean()).isTrue();
        assertThat(p.path("priceVisible").asBoolean())
                .as("등록 시 단가노출은 수불부노출을 따른다").isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("★수불부에서만 빼도 단가는 살아 있다 — 이게 분리한 이유")
    void 따로_움직인다() {
        JsonNode r = put("/masters/products/flags",
                Map.of("productIds", List.of(bookA), "ledgerVisible", false));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        JsonNode p = productOf(bookA);
        assertThat(p.path("ledgerVisible").asBoolean()).as("수불부에서 뺐다").isFalse();
        assertThat(p.path("priceVisible").asBoolean())
                .as("‼️단가는 그대로 — 한 값이었으면 같이 꺼졌다").isTrue();

        // 반대도 성립: 단가만 끄고 수불부는 살린다
        put("/masters/products/flags",
                Map.of("productIds", List.of(bookB), "priceVisible", false));
        JsonNode q = productOf(bookB);
        assertThat(q.path("ledgerVisible").asBoolean()).isTrue();
        assertThat(q.path("priceVisible").asBoolean()).isFalse();
    }

    @Test
    @Order(3)
    @DisplayName("목록에서 두 축으로 따로 거른다")
    void 필터가_따로_먹는다() {
        // bookA: 수불부 N · 단가 Y   /   bookB: 수불부 Y · 단가 N
        assertThat(codes("?keyword=분리도서&ledgerVisible=false")).contains("VSA" + SFX)
                .doesNotContain("VSB" + SFX);
        assertThat(codes("?keyword=분리도서&priceVisible=false")).contains("VSB" + SFX)
                .doesNotContain("VSA" + SFX);
        assertThat(codes("?keyword=분리도서")).contains("VSA" + SFX, "VSB" + SFX);
    }

    @Test
    @Order(4)
    @DisplayName("분류 단위 일괄 적용도 두 축을 각각 건다")
    void 분류단위_적용() {
        put("/masters/products/flags", Map.of("catCode", CAT, "priceVisible", true));
        assertThat(productOf(bookB).path("priceVisible").asBoolean()).isTrue();
        assertThat(productOf(bookA).path("ledgerVisible").asBoolean())
                .as("단가만 건드렸으니 수불부는 그대로").isFalse();

        put("/masters/products/flags", Map.of("catCode", CAT, "ledgerVisible", true));
        assertThat(productOf(bookA).path("ledgerVisible").asBoolean()).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("변경이력에 '단가노출'이 따로 남는다 — 무엇을 껐는지 되짚을 수 있어야 한다")
    void 변경이력() {
        put("/masters/products/flags",
                Map.of("productIds", List.of(bookA), "priceVisible", false));

        boolean found = false;
        for (JsonNode l : data(get("/audit/master-changes?entityType=PRODUCT&size=200")).path("content")) {
            if ("단가노출".equals(l.path("fieldLabel").asText())) {
                found = true;
            }
        }
        assertThat(found).as("단가노출 변경이 이력에 남아야 한다").isTrue();

        put("/masters/products/flags",
                Map.of("productIds", List.of(bookA), "priceVisible", true));
    }

    private List<String> codes(String query) {
        List<String> out = new java.util.ArrayList<>();
        data(get("/masters/products" + query)).path("content")
                .forEach(n -> out.add(n.path("code").asText()));
        return out;
    }
}
