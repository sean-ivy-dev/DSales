package com.daesung.sales.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 자재 매칭 <b>전역 조회</b>({@code GET /masters/material-boms}).
 *
 * <p>세트 하나씩 보는 경로만 있어서 "자재가 어디에 몇 개 걸려 있나"를 볼 길이 없었다 —
 * 상품이 14,811개라 순회로는 답이 안 나온다. F-37 에서 발주처가
 * <b>"지금 「공통(회차 0)」으로 연결된 건을 새 구조로 어떻게 옮길지"</b> 회신을 요구해
 * 그 대상 목록을 뽑기 위해 신설했다.
 *
 * <p>★검증은 <b>자기 데이터로 범위를 좁힌다</b> — 전역 조회라 다른 테스트가 매칭을
 * 추가하면 전체 건수 단정은 깨진다(테스트 격리 규율).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("자재 매칭 전역 조회(공통/전체)")
class MaterialBomAllIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-BA" + (System.nanoTime() % 1_000_000L);

    private Long setProduct;
    private Long roundProduct;
    private Long commonMaterial;
    private Long roundMaterial;

    @BeforeAll
    void seed() {
        token();
        setProduct = product("BAS" + SFX, "세트상품");
        roundProduct = product("BAR" + SFX, "1회");

        commonMaterial = createId("/masters/materials", Map.of(
                "code", "BAMC" + SFX, "name", "공통 OMR", "materialType", "OMR"));
        roundMaterial = createId("/masters/materials", Map.of(
                "code", "BAMR" + SFX, "name", "1회 시험지", "materialType", "EXAM_PAPER"));

        // 공통(회차 미지정) 1건 — F-37 이 가리키는 그 모양
        JsonNode c = post("/masters/products/" + setProduct + "/materials", Map.of(
                "materialId", commonMaterial, "qtyPerSet", 6));
        assertThat(c.path("success").asBoolean()).as("공통 매칭 등록: %s", c).isTrue();

        // 회차별 1건
        JsonNode r = post("/masters/products/" + setProduct + "/materials", Map.of(
                "roundProductId", roundProduct, "materialId", roundMaterial, "qtyPerSet", 1));
        assertThat(r.path("success").asBoolean()).as("회차 매칭 등록: %s", r).isTrue();
    }

    private Long product(String code, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2092A01");
        b.put("catName", "전역매칭검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        return createId("/masters/products", b);
    }

    private List<JsonNode> mine(boolean commonOnly) {
        JsonNode res = get("/masters/material-boms?commonOnly=" + commonOnly);
        assertThat(res.path("success").asBoolean()).as("조회: %s", res).isTrue();
        List<JsonNode> out = new java.util.ArrayList<>();
        res.path("data").forEach(n -> {
            if (setProduct.equals(n.path("setProductId").asLong())) {
                out.add(n);
            }
        });
        return out;
    }

    @Test
    @DisplayName("전체 조회 — 공통·회차 매칭이 세트 정보를 달고 함께 나온다")
    void 전체조회() {
        List<JsonNode> rows = mine(false);
        assertThat(rows).as("내 세트의 매칭 2건").hasSize(2);

        // 세트 정보가 줄마다 실려야 한다 — 세트를 가로지르는 목록이라 머리말에 둘 수 없다.
        for (JsonNode n : rows) {
            assertThat(n.path("setCode").asText()).isEqualTo("BAS" + SFX);
            assertThat(n.path("setName").asText()).isEqualTo("세트상품");
            assertThat(n.path("setCatName").asText()).isEqualTo("전역매칭검증");
        }

        JsonNode common = rows.stream().filter(n -> n.path("common").asBoolean())
                .findFirst().orElseThrow();
        assertThat(common.path("roundLabel").asText()).isEqualTo("공통");
        // ‼️NON_NULL 직렬화라 null 필드는 **빠진다** — isNull() 이 아니라 has() 로 본다.
        assertThat(common.has("roundProductId")).as("공통은 회차 id가 없다").isFalse();
        // ‼️자재코드는 **서버가 자동 채번**한다(내가 준 code 는 무시된다) → id 로 본다.
        assertThat(common.path("materialId").asLong()).isEqualTo(commonMaterial);
        assertThat(common.path("materialName").asText()).isEqualTo("공통 OMR");
        assertThat(common.path("qtyPerSet").asInt()).isEqualTo(6);

        JsonNode round = rows.stream().filter(n -> !n.path("common").asBoolean())
                .findFirst().orElseThrow();
        assertThat(round.path("roundProductId").asLong()).isEqualTo(roundProduct);
        assertThat(round.path("roundLabel").asText()).isEqualTo("1회");
        assertThat(round.path("materialId").asLong()).isEqualTo(roundMaterial);
        assertThat(round.path("qtyPerSet").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("commonOnly=true — 공통 매칭만 남고 회차 매칭은 빠진다(F-37 추출 조건)")
    void 공통만() {
        List<JsonNode> rows = mine(true);
        assertThat(rows).as("내 세트의 공통 매칭 1건").hasSize(1);
        assertThat(rows.get(0).path("common").asBoolean()).isTrue();
        assertThat(rows.get(0).path("materialId").asLong()).isEqualTo(commonMaterial);
    }
}
