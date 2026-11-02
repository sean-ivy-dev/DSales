package com.daesung.sales.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 도서 수정에서 <b>안 보낸 필드가 지워지지 않는다</b>는 것을 고정한다.
 *
 * <p>예전에는 Boolean만 "미지정=유지"였고 나머지는 그대로 대입했다. 그래서 화면이
 * 정가만 고치려고 일부 필드만 보내면 <b>분류코드·학년이 조용히 사라졌다.</b>
 * 29개 필드를 하나도 빠뜨리지 않고 되돌려 보내야만 안전한 API였고,
 * 그 사실이 어디에도 드러나지 않아 데이터가 없어진 뒤에야 알게 된다.
 *
 * <p>지우려는 의사는 <b>빈 문자열</b>로 표현한다 — 그건 값이 실려 온 것이라 그대로 저장된다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// ★순서가 있다 — '빈 문자열로 비운다'가 먼저 돌면 뒤 테스트가 볼 학년이 이미 지워져 있다.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("도서 부분 수정 — null은 유지")
class ProductPartialUpdateTest extends IntegrationTestSupport {

    private static final String SFX = "-PU" + (System.nanoTime() % 1_000_000L);
    private Long id;

    @BeforeAll
    void seed() {
        token();
        Map<String, Object> m = new HashMap<>();
        m.put("code", "PU" + SFX);
        m.put("name", "부분수정도서");
        m.put("contentType", "SELF");
        m.put("price", 10000);
        m.put("supplyRate", 70);
        m.put("grade", "고3");
        m.put("catCode", "P2069A1");
        m.put("catName", "부분분류");
        m.put("productYear", 2069);
        m.put("productType", "교재");
        m.put("salesDivision", "교재");
        id = createId("/masters/products", m);
    }

    private JsonNode product() {
        return data(get("/masters/products/" + id));
    }

    @Test
    @Order(1)
    @DisplayName("★정가만 보내도 분류코드·학년·공급률이 살아 있다")
    void 안_보낸_필드는_유지() {
        JsonNode r = put("/masters/products/" + id, Map.of("price", 15000));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        JsonNode p = product();
        assertThat(p.path("price").asInt()).as("고친 값").isEqualTo(15000);
        assertThat(p.path("catCode").asText()).as("‼️예전엔 여기가 지워졌다").isEqualTo("P2069A1");
        assertThat(p.path("catName").asText()).isEqualTo("부분분류");
        assertThat(p.path("grade").asText()).isEqualTo("3");
        assertThat(p.path("supplyRate").asInt()).isEqualTo(70);
        assertThat(p.path("name").asText()).isEqualTo("부분수정도서");
        assertThat(p.path("productYear").asInt()).isEqualTo(2069);
        assertThat(p.path("productType").asText()).isEqualTo("교재");
    }

    @Test
    @Order(4)
    @DisplayName("빈 문자열은 '비운다'는 뜻 — 그대로 저장된다")
    void 빈문자열은_지움() {
        // ⚠️학년은 코드값 정규화 대상이라 빈 문자열이 null(=미지정)로 접힌다.
        //   그래서 '비우기'는 정규화를 타지 않는 자유 입력 항목으로 확인한다.
        put("/masters/products/" + id, Map.of("catName", ""));

        assertThat(product().path("catName").asText()).isEmpty();
        assertThat(product().path("catCode").asText()).as("다른 필드는 그대로").isEqualTo("P2069A1");
    }

    @Test
    @Order(3)
    @DisplayName("‼️상품명만은 비울 수 없다 — 이름 없는 상품은 목록에서 못 찾는다")
    void 상품명은_비울_수_없다() {
        JsonNode r = put("/masters/products/" + id, Map.of("name", "  "));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("상품명은 비울 수 없습니다");
        assertThat(product().path("name").asText()).as("거부됐으니 그대로").isEqualTo("부분수정도서");
    }

    @Test
    @Order(2)
    @DisplayName("낡은 형식의 분류코드를 가진 도서도 다른 필드는 고칠 수 있다")
    void 형식검증이_수정을_막지_않는다() {
        // 분류코드를 안 보내면 형식 검증 대상이 아니다(@Pattern은 null을 통과시킨다).
        JsonNode r = put("/masters/products/" + id, Map.of("price", 20000));

        assertThat(r.path("success").asBoolean())
                .as("분류코드를 건드리지 않는 수정은 통과해야 한다: %s", r).isTrue();
        assertThat(product().path("price").asInt()).isEqualTo(20000);
    }
}
