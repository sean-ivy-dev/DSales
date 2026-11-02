package com.daesung.sales.master;

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
 * 코드값 정규화 회귀 고정 — 거래처구분·상품구분·학년.
 *
 * <p>★왜 서버에서 막는가: 화면이 셀렉트로 좁혀도 <b>API를 직접 부르면 그대로 뚫린다.</b>
 * 실제로 운영 데이터에 학년이 {@code 고3} 9건 · {@code 3} 1건으로 갈려 있었다.
 * 두 값은 필터에서 서로 안 잡혀 같은 상품이 조회 조건에 따라 나왔다 안 나왔다 하고,
 * 리포트는 두 줄로 쪼개진다 — 오류가 아니라서 아무도 모른다.
 *
 * <p>정책: <b>받는 건 너그럽게, 저장은 하나로.</b> 모르는 값은 400으로 거부한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("코드값 정규화(거래처구분·상품구분·학년)")
class CodeValueIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CV" + (System.nanoTime() % 1_000_000L);

    @BeforeAll
    void seed() {
        token();
    }

    private JsonNode createProduct(String code, String grade, String productType) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", "코드도서");
        m.put("contentType", "SELF");
        m.put("price", 10000);
        m.put("supplyRate", 70);
        m.put("catCode", "V2071A1");
        m.put("catName", "코드분류");
        if (grade != null) {
            m.put("grade", grade);
        }
        if (productType != null) {
            m.put("productType", productType);
        }
        m.put("salesDivision", "교재");
        return post("/masters/products", m);
    }

    @Test
    @DisplayName("★'고3'으로 넣어도 '3'으로 저장된다 — 표기가 갈리면 필터에서 서로 안 잡힌다")
    void 학년_정규화() {
        JsonNode r = createProduct("CVA" + SFX, "고3", null);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        JsonNode p = data(r);
        assertThat(p.path("grade").asText()).as("저장은 숫자로 통일").isEqualTo("3");
        assertThat(p.path("gradeName").asText()).as("표기는 서버가 붙여 준다").isEqualTo("3학년");
    }

    @Test
    @DisplayName("'3학년'·'3' 도 같은 값으로 모인다")
    void 학년_별칭() {
        assertThat(data(createProduct("CVB" + SFX, "3학년", null)).path("grade").asText()).isEqualTo("3");
        assertThat(data(createProduct("CVC" + SFX, "1", null)).path("grade").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("★모르는 학년은 400 — 조용히 통과시키면 축이 하나 더 생긴다")
    void 학년_거부() {
        JsonNode r = createProduct("CVD" + SFX, "중3", null);

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("학년", "중3");
    }

    @Test
    @DisplayName("⛔상품구분은 아직 자유 입력 — 무엇을 담는 칸인지 확정 전이다")
    void 상품구분은_아직_열려있다() {
        // 정본 6탭은 "상품구분(세트/단품)"이라 하는데, 우리 모델은 세트 여부를 boolean `set`으로
        // 따로 갖고 있고 productType 에는 "교재"·"모의고사"가 들어가 있다(레거시 원시값).
        // 무엇을 담는 칸인지 확정되기 전에 값을 좁히면 등록이 막힌다 → 발주처 확인 대상.
        assertThat(data(createProduct("CVE" + SFX, null, "교재")).path("productType").asText())
                .isEqualTo("교재");
    }

    @Test
    @DisplayName("★거래처구분은 정본 5값만 — 그 밖은 거부")
    void 거래처구분() {
        JsonNode ok = post("/masters/clients", Map.of(
                "code", "CVP" + SFX, "name", "코드거래처", "type", "NORMAL",
                "clientCategory", "기타학원"));
        assertThat(ok.path("success").asBoolean()).as("%s", ok).isTrue();
        assertThat(data(ok).path("clientCategory").asText()).isEqualTo("기타학원");

        JsonNode bad = post("/masters/clients", Map.of(
                "code", "CVQ" + SFX, "name", "코드거래처2", "type", "NORMAL",
                "clientCategory", "학원"));
        assertThat(bad.path("success").asBoolean()).isFalse();
        assertThat(bad.path("error").path("message").asText()).contains("거래처구분", "특약점");
    }

    @Test
    @DisplayName("미지정(null)은 그대로 통과 — 선택 항목이라 '안 보냈다'는 뜻이다")
    void 미지정은_통과() {
        JsonNode r = createProduct("CVG" + SFX, null, null);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    @Test
    @DisplayName("코드값 목록 — 고정(거래처구분)과 열림(지역)을 나눠 준다")
    void 코드목록() {
        JsonNode d = data(get("/masters/clients/codes"));

        assertThat(d.path("clientCategories")).hasSize(5);
        assertThat(d.path("clientCategories").toString()).contains("특약점", "자사몰");
        assertThat(d.has("regions")).as("지역은 쓰이는 값 목록").isTrue();
        assertThat(d.has("zones")).as("관할지역은 별개 축이라 따로").isTrue();
    }
}
