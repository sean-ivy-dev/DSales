package com.daesung.sales.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 분류코드 선택 목록 회귀 고정.
 *
 * <p>정본에 분류 등록 화면이 없어 분류코드는 도서에 직접 입력된다. 형식 검증은
 * <b>오타를 못 잡는다</b> — {@code K202601}을 {@code K202602}로 치면 형식은 통과하고
 * 리포트에 없던 분류가 한 줄 더 생긴다. 고르게 해서 그 경로를 줄이는 것이 이 API의 목적이다.
 *
 * <p>★고정하려는 것은 셋이다.
 * <ol>
 *   <li>쓰이는 분류가 <b>건수와 함께</b> 나온다.</li>
 *   <li>같은 코드에 이름이 갈리면 <b>여러 줄로 드러난다</b> — 숨기면 문제를 못 본다.</li>
 *   <li>사용여부가 꺼진 도서의 분류도 <b>빠지지 않는다</b> — 과거 매출이 그걸 가리킨다.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("분류코드 선택 목록")
class ProductCategoryListTest extends IntegrationTestSupport {

    private static final String SFX = "-CG" + (System.nanoTime() % 1_000_000L);
    /** 다른 테스트 분류와 겹치지 않도록 고유 연도를 쓴다. */
    private static final int YEAR = 2070;
    private static final String CAT = "C" + YEAR + "A";
    private static final String CAT_OFF = "C" + YEAR + "B";

    private Long offProduct;

    @BeforeAll
    void seed() {
        token();
        // 같은 분류 2건
        create("CGA" + SFX, "분류도서A", CAT, "선택분류");
        create("CGB" + SFX, "분류도서B", CAT, "선택분류");
        // ★같은 코드인데 이름만 다른 건 — 오타로 생기는 전형적 상황
        create("CGC" + SFX, "분류도서C", CAT, "선택 분류");
        // 사용여부를 끌 도서
        offProduct = create("CGD" + SFX, "분류도서D", CAT_OFF, "꺼진분류");
    }

    private Long create(String code, String name, String catCode, String catName) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("contentType", "SELF");
        m.put("price", 10000);
        m.put("supplyRate", 70);
        m.put("catCode", catCode);
        m.put("catName", catName);
        m.put("salesDivision", "교재");
        return createId("/masters/products", m);
    }

    private List<JsonNode> rows(String catCode) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : data(get("/masters/products/categories"))) {
            if (catCode.equals(n.path("catCode").asText())) {
                out.add(n);
            }
        }
        return out;
    }

    @Test
    @DisplayName("★쓰이는 분류가 건수와 함께 나온다")
    void 목록과_건수() {
        JsonNode main = null;
        for (JsonNode n : rows(CAT)) {
            if ("선택분류".equals(n.path("catName").asText())) {
                main = n;
            }
        }
        assertThat(main).isNotNull();
        assertThat(main.path("productCount").asLong()).as("같은 이름 2건").isEqualTo(2);
    }

    @Test
    @DisplayName("★같은 코드에 이름이 갈리면 여러 줄로 드러난다 — 숨기면 오타를 못 본다")
    void 이름이_갈리면_보인다() {
        List<JsonNode> found = rows(CAT);

        assertThat(found).as("'선택분류'와 '선택 분류' 두 줄이어야 한다").hasSize(2);
        // 건수 1짜리가 곧 오타 후보다
        assertThat(found).anyMatch(n -> n.path("productCount").asLong() == 1);
    }

    @Test
    @DisplayName("★사용여부를 꺼도 분류는 목록에 남는다 — 과거 매출이 그 분류를 가리킨다")
    void 비활성_도서의_분류도_남는다() {
        assertThat(rows(CAT_OFF)).as("끄기 전").isNotEmpty();

        JsonNode r = put("/masters/products/" + offProduct, Map.of("useYn", false));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        assertThat(rows(CAT_OFF))
                .as("‼️여기서 빠지면 담당자가 '없는 분류'로 알고 똑같은 걸 또 만든다")
                .isNotEmpty();
    }
}
