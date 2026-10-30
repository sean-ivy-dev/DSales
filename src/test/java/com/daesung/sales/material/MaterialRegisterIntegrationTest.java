package com.daesung.sales.material;

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
 * 자재 등록 → 세트 회차 매칭이 <b>끝까지 되는지</b> 고정한다.
 *
 * <p>근거: 1차 테스트 피드백(2026-09-17) p21 — "세트구성(BOM) 회차별 자재 '등록 불가'".
 *
 * <p>★<b>이 테스트가 통과하면 서버 경로는 문제가 아니다.</b> 그러면 남는 원인은
 * <b>자재 마스터가 0건</b>이라 화면에서 고를 자재가 없는 것이다(프론트도 "자재 마스터 0건"으로
 * 같은 사실을 적었다). 그건 코드가 아니라 자료 문제라 시드로 푼다.
 *
 * <p>추측으로 "데이터 문제일 것"이라고 답하지 않기 위해 경로를 통째로 한 번 밟아 둔다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("자재 등록 → 세트 회차 매칭(p21)")
class MaterialRegisterIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-MR" + (System.nanoTime() % 1_000_000L);

    private Long setProduct;
    private Long round1;

    @BeforeAll
    void seed() {
        token();
        setProduct = book("MRSET" + SFX, "자재검증세트");
        round1 = book("MR1" + SFX, "1회");
    }

    private Long book(String code, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "M2089A");
        b.put("catName", "자재분류");
        return createId("/masters/products", b);
    }

    @Test
    @DisplayName("★자재 마스터 등록 — 자재구분을 한글로도 받는다")
    void 자재_등록() {
        JsonNode r = post("/masters/materials", Map.of(
                "code", "MAT1" + SFX, "name", "2089 국어 시즌1_01회 시험지",
                "materialType", "시험지"));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("materialType").asText()).isEqualTo("EXAM_PAPER");
    }

    @Test
    @DisplayName("★'책자'도 단행본으로 받는다 — DSRE 실데이터는 책자 305건 ↔ 단행본 2건")
    void 책자는_단행본으로() {
        // DSRE는 책자/단행본을 나눠 두었지만 단행본이 2건뿐이라 우리는 BOOK 한 항목이다.
        // 표기는 '단행본'이고, 압도적으로 많은 쪽('책자')이 들어와도 400을 내지 않는다.
        JsonNode r = post("/masters/materials", Map.of(
                "name", "책자별칭검증" + SFX, "materialType", "책자"));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("materialType").asText()).isEqualTo("BOOK");
        assertThat(data(r).path("materialTypeName").asText()).as("표기는 단행본").isEqualTo("단행본");
    }

    @Test
    @DisplayName("★회차 자재 매칭 — 회차를 지정해 붙인다")
    void 회차_매칭() {
        long matId = data(post("/masters/materials", Map.of(
                "code", "MAT2" + SFX, "name", "회차전용 시험지", "materialType", "EXAM_PAPER")))
                .path("id").asLong();

        JsonNode r = post("/masters/products/" + setProduct + "/materials", Map.of(
                "materialId", matId, "roundProductId", round1, "qtyPerSet", 1));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("rows")).as("매칭 결과가 돌아온다").isNotEmpty();
    }

    @Test
    @DisplayName("★공통 자재 — 회차를 비우면 세트 전체에 붙는다")
    void 공통_매칭() {
        long matId = data(post("/masters/materials", Map.of(
                "code", "MAT3" + SFX, "name", "공통 OMR", "materialType", "OMR")))
                .path("id").asLong();

        JsonNode r = post("/masters/products/" + setProduct + "/materials", Map.of(
                "materialId", matId, "qtyPerSet", 4, "perRound", true));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        boolean found = false;
        for (JsonNode row : data(r).path("rows")) {
            if (row.path("materialId").asLong() == matId) {
                found = true;
                assertThat(row.path("perRound").asBoolean()).as("회차 반복형").isTrue();
            }
        }
        assertThat(found).as("공통 자재가 목록에 있다").isTrue();
    }

    @Test
    @DisplayName("같은 조합을 다시 넣으면 수량이 갱신된다 — 중복 행이 생기지 않는다")
    void 같은조합은_갱신() {
        long matId = data(post("/masters/materials", Map.of(
                "code", "MAT4" + SFX, "name", "갱신검증 라벨", "materialType", "LABEL")))
                .path("id").asLong();

        post("/masters/products/" + setProduct + "/materials",
                Map.of("materialId", matId, "qtyPerSet", 1));
        JsonNode r = post("/masters/products/" + setProduct + "/materials",
                Map.of("materialId", matId, "qtyPerSet", 9));

        int count = 0;
        int qty = 0;
        for (JsonNode row : data(r).path("rows")) {
            if (row.path("materialId").asLong() == matId) {
                count++;
                qty = row.path("qtyPerSet").asInt();
            }
        }
        assertThat(count).as("행이 하나여야").isEqualTo(1);
        assertThat(qty).as("수량만 갱신").isEqualTo(9);
    }

    @Test
    @DisplayName("회차로 세트 자신을 넣으면 400 — 무엇이 잘못됐는지 알려준다")
    void 회차가_세트자신() {
        long matId = data(post("/masters/materials", Map.of(
                "code", "MAT5" + SFX, "name", "오입력검증", "materialType", "ETC")))
                .path("id").asLong();

        JsonNode r = post("/masters/products/" + setProduct + "/materials", Map.of(
                "materialId", matId, "roundProductId", setProduct, "qtyPerSet", 1));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("회차가 세트 자신");
    }
}
