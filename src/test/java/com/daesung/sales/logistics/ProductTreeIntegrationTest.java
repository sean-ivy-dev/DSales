package com.daesung.sales.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 물류비용등록(33p) 좌측 상품 트리 — <b>연도 &gt; 분류 &gt; 상품</b>(F-38).
 *
 * <p>★연도 축은 <b>분류코드 2~5번째</b>다 — 레거시 {@code substring(catCode,2,4) desc} 와 같다.
 * ‼️상품 마스터의 {@code 상품년도} 필드는 운영 14,811개 중 <b>3개만</b> 채워져 있어 쓰지 않는다.
 * 그 필드로 묶으면 전 상품이 '미지정' 한 묶음이 된다.
 *
 * <p>★전역 조회라 <b>자기 데이터로 범위를 좁혀</b> 단정한다. 다른 테스트가 상품을 추가하면
 * 전체 건수 단정은 깨진다(테스트 격리 규율).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("상품 트리 — 연도는 분류코드에서 파생(F-38)")
class ProductTreeIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PT" + (System.nanoTime() % 1_000_000L);
    /**
     * ‼️2093·2094 연도 자체는 <b>다른 테스트도 쓴다</b>(예: {@code S2094A01}).
     * 그래서 연도가 아니라 <b>분류코드를 고유하게</b>(`…Z`) 잡고, 단정도 그 분류·내 상품 id로만 한다.
     */
    private static final String CAT_93 = "S2093Z";
    private static final String CAT_94 = "S2094Z";

    private Long p93a;
    private Long p93b;
    private Long p94;

    @BeforeAll
    void seed() {
        token();
        p93a = product("PT93A" + SFX, "93년 상품A", CAT_93, "트리검증93");
        p93b = product("PT93B" + SFX, "93년 상품B", CAT_93, "트리검증93");
        p94 = product("PT94" + SFX, "94년 상품", CAT_94, "트리검증94");
    }

    private Long product(String code, String name, String catCode, String catName) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", catCode);
        b.put("catName", catName);
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        return createId("/masters/products", b);
    }

    private JsonNode yearNode(JsonNode tree, int year) {
        for (JsonNode n : tree) {
            if (n.path("year").asInt(-1) == year) {
                return n;
            }
        }
        return null;
    }

    @Test
    @DisplayName("연도 > 분류 > 상품 3단으로 내려오고, 연도는 분류코드에서 뽑힌다")
    void 트리구조() {
        JsonNode res = get("/logistics-costs/products/tree");
        assertThat(res.path("success").asBoolean()).as("조회: %s", res).isTrue();
        JsonNode tree = res.path("data");

        JsonNode y93 = yearNode(tree, 2093);
        assertThat(y93).as("분류코드 %s → 연도 2093 으로 묶인다", CAT_93).isNotNull();

        JsonNode cat = null;
        for (JsonNode c : y93.path("categories")) {
            if (CAT_93.equals(c.path("catCode").asText())) {
                cat = c;
            }
        }
        assertThat(cat).as("분류 노드").isNotNull();
        assertThat(cat.path("catName").asText()).isEqualTo("트리검증93");

        java.util.Set<Long> ids = new java.util.HashSet<>();
        cat.path("products").forEach(p -> ids.add(p.path("productId").asLong()));
        assertThat(ids).as("같은 분류의 상품 2개가 한 노드에").contains(p93a, p93b);

        // 단가를 안 걸었으니 hasRate=false 여야 한다 — 트리에서 골라 새로 등록하는 화면이다.
        cat.path("products").forEach(p -> {
            if (ids.contains(p.path("productId").asLong())) {
                assertThat(p.path("hasRate").asBoolean()).as("단가 미등록").isFalse();
            }
        });

        // 다른 연도는 별개 노드로 갈린다
        JsonNode y94 = yearNode(tree, 2094);
        assertThat(y94).as("연도 2094 노드").isNotNull();
        java.util.Set<Long> ids94 = new java.util.HashSet<>();
        y94.path("categories").forEach(c -> c.path("products")
                .forEach(p -> ids94.add(p.path("productId").asLong())));
        assertThat(ids94).contains(p94).doesNotContain(p93a, p93b);
    }

    @Test
    @DisplayName("연도 내림차순 — 최근 연도가 먼저 온다(레거시 정렬)")
    void 연도정렬() {
        JsonNode tree = get("/logistics-costs/products/tree").path("data");
        java.util.List<Integer> years = new java.util.ArrayList<>();
        tree.forEach(n -> {
            if (n.has("year")) {
                years.add(n.path("year").asInt());
            }
        });
        assertThat(years).as("연도가 하나 이상").isNotEmpty();
        assertThat(years).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("키워드로 좁히면 그 분류만 남는다")
    void 키워드() {
        JsonNode tree = get("/logistics-costs/products/tree?keyword=트리검증93").path("data");
        JsonNode y93 = yearNode(tree, 2093);
        assertThat(y93).isNotNull();
        assertThat(yearNode(tree, 2094)).as("다른 연도는 빠진다").isNull();
    }

    @Test
    @DisplayName("단가 목록 연도 필터 — 분류코드 연도로 걸러진다")
    void 단가목록_연도필터() {
        JsonNode res = get("/logistics-costs/products?productYear=2093");
        assertThat(res.path("success").asBoolean()).as("조회: %s", res).isTrue();
        // 단가를 안 걸었으니 2093 은 0건이어야 한다(목록은 단가 등록분만 낸다).
        res.path("data").forEach(n ->
                assertThat(n.path("productYear").asInt()).isEqualTo(2093));
    }
}
