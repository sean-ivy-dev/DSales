package com.daesung.sales.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 자재 마스터 + 세트·회차 ↔ 자재 매칭(33p) 회귀 고정.
 * 근거: 발주처 「도서관리·제품수불부현황 데이터 구조 보완 요청안」(2026-08-31).
 *
 * <p>★이 구조를 만든 이유가 <b>범용 자재의 중복 매칭</b>이다 —
 * "OMR·교사용라벨 등 범용 자재는 1건을 여러 세트·회차에 중복 매칭할 수 있어야 합니다".
 * 상품 BOM(부모,자식 유일)으로는 세트마다 자재 행을 새로 만들어야 해서 성립하지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("자재 마스터·세트 자재 매칭(33p)")
class MaterialMasterIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-MT" + (System.nanoTime() % 1_000_000L);

    private Long setA;
    private Long setB;
    private Long round1;
    private Long round2;
    private Long omr;
    private Long paper1;
    /** ★자재코드는 **서버가 매긴다**(V102) — 보낸 코드가 아니라 응답에서 받아 둔다. */
    private String omrCode;
    private String paper1Code;

    @BeforeAll
    void seed() {
        token();
        // 세트 2개 + 회차 2개(모두 products — 회차도 단품으로 팔린다)
        setA = createId("/masters/products", Map.of("code", "SETA" + SFX, "name", "국어 시즌1 SET",
                "contentType", "SELF", "price", 40000, "supplyRate", 70,
                "salesDivision", "교재"));
        setB = createId("/masters/products", Map.of("code", "SETB" + SFX, "name", "수학 시즌1 SET",
                "contentType", "SELF", "price", 40000, "supplyRate", 70,
                "salesDivision", "교재"));
        round1 = createId("/masters/products", Map.of("code", "RD1" + SFX, "name", "1회",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재"));
        round2 = createId("/masters/products", Map.of("code", "RD2" + SFX, "name", "2회",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재"));

        JsonNode omrRes = data(post("/masters/materials", Map.of(
                "name", "국어 OMR" + SFX, "materialType", "OMR")));
        omr = omrRes.path("id").asLong();
        omrCode = omrRes.path("code").asText();
        JsonNode paperRes = data(post("/masters/materials", Map.of(
                "name", "국어 시즌1_01회" + SFX, "materialType", "EXAM_PAPER")));
        paper1 = paperRes.path("id").asLong();
        paper1Code = paperRes.path("code").asText();
    }

    @Test
    @Order(1)
    @DisplayName("자재 등록·조회 — 해설지는 단가 키가 시험지로 접힌다")
    void 자재등록() {
        JsonNode ans = data(post("/masters/materials", Map.of(
                "name", "국어 시즌1_01회_정답및해설" + SFX, "materialType", "ANSWER_SHEET")));

        assertThat(ans.path("materialTypeName").asText()).isEqualTo("해설지");
        // ‼️핵심: 해설지 단가는 신설하지 않는다 — 시험지 단가를 쓴다(발주처 확정)
        assertThat(ans.path("rateKey").asText()).as("해설지 → 시험지 단가").isEqualTo("EXAM_PAPER");

        assertThat(codesOf(data(get("/masters/materials?materialType=OMR"))))
                .contains(omrCode)
                .doesNotContain(paper1Code);
        assertThat(codesOf(data(get("/masters/materials?keyword=국어 시즌1_01회"))))
                .contains(paper1Code);
    }

    @Test
    @Order(2)
    @DisplayName("★자재코드는 서버가 매긴다 — 보낸 코드는 무시되고 중복이 생기지 않는다")
    void 코드는_서버가_매긴다() {
        // 예전엔 "같은 코드 재등록 거부"를 고정했으나, 10/27 피드백 §11-3으로 담당자 직접 입력이
        // 폐지돼 **중복이 애초에 불가능**해졌다. 그래서 새 규칙 쪽을 고정한다.
        JsonNode a = data(post("/masters/materials", Map.of(
                "code", omrCode, "name", "보낸코드무시A" + SFX, "materialType", "OMR")));
        JsonNode b = data(post("/masters/materials", Map.of(
                "code", omrCode, "name", "보낸코드무시B" + SFX, "materialType", "OMR")));

        assertThat(a.path("code").asText()).as("보낸 코드를 그대로 쓰지 않는다").isNotEqualTo(omrCode);
        assertThat(b.path("code").asText()).isNotEqualTo(omrCode);
        assertThat(a.path("code").asText()).as("두 건이 서로 다른 코드를 받는다")
                .isNotEqualTo(b.path("code").asText());
    }

    @Test
    @Order(3)
    @DisplayName("★범용 자재 1건을 여러 세트에 중복 매칭한다 — 이 구조를 만든 이유")
    void 범용자재_중복매칭() {
        // 국어 OMR을 세트A(공통 4)와 세트B(공통 2)에 각각 매칭
        data(post("/masters/products/" + setA + "/materials",
                Map.of("materialId", omr, "qtyPerSet", 4)));
        JsonNode b = data(post("/masters/products/" + setB + "/materials",
                Map.of("materialId", omr, "qtyPerSet", 2)));

        assertThat(b.path("rows")).hasSize(1);
        assertThat(b.path("rows").get(0).path("roundLabel").asText())
                .as("회차를 안 주면 공통").isEqualTo("공통");
        assertThat(b.path("rows").get(0).path("qtyPerSet").asInt()).isEqualTo(2);

        // 세트A는 그대로 4 — 서로 간섭하지 않는다
        JsonNode a = data(get("/masters/products/" + setA + "/materials"));
        assertThat(a.path("rows").get(0).path("qtyPerSet").asInt()).isEqualTo(4);
    }

    @Test
    @Order(4)
    @DisplayName("회차 전용 자재는 회차를 지정한다 — 공통과 함께 목록에 나온다")
    void 회차전용_매칭() {
        JsonNode r = data(post("/masters/products/" + setA + "/materials",
                Map.of("materialId", paper1, "roundProductId", round1, "qtyPerSet", 1)));

        assertThat(r.path("rows")).hasSize(2);   // 공통 OMR + 1회 시험지
        boolean hasRound = false;
        for (JsonNode row : r.path("rows")) {
            if ("1회".equals(row.path("roundLabel").asText())) {
                hasRound = true;
                assertThat(row.path("materialType").asText()).isEqualTo("EXAM_PAPER");
                assertThat(row.path("qtyPerSet").asInt()).isEqualTo(1);
            }
        }
        assertThat(hasRound).as("회차 매칭이 보여야: %s", r).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("★같은 조합을 다시 넣으면 행이 늘지 않고 수량만 갱신된다 — 두 배가 되면 안 된다")
    void 같은조합은_수량갱신() {
        int before = data(get("/masters/products/" + setA + "/materials")).path("rows").size();

        JsonNode r = data(post("/masters/products/" + setA + "/materials",
                Map.of("materialId", omr, "qtyPerSet", 8)));

        assertThat(r.path("rows")).hasSize(before);
        for (JsonNode row : r.path("rows")) {
            if ("공통".equals(row.path("roundLabel").asText())) {
                assertThat(row.path("qtyPerSet").asInt()).as("4 → 8로 갱신").isEqualTo(8);
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("★매칭된 자재는 삭제되지 않는다 — 지우면 소요수량이 조용히 사라진다")
    void 매칭된자재_삭제차단() {
        JsonNode r = del("/masters/materials/" + omr);
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("매칭된 자재는 삭제할 수 없습니다");

        // 매칭을 풀면 지워진다
        JsonNode boms = data(get("/masters/products/" + setA + "/materials"));
        long bomId = 0;
        for (JsonNode row : boms.path("rows")) {
            if (row.path("materialId").asLong() == omr) {
                bomId = row.path("id").asLong();
            }
        }
        del("/masters/products/" + setA + "/materials/" + bomId);
        del("/masters/products/" + setB + "/materials/"
                + data(get("/masters/products/" + setB + "/materials")).path("rows").get(0).path("id").asLong());

        assertThat(del("/masters/materials/" + omr).path("success").asBoolean())
                .as("매칭이 없으면 삭제된다").isTrue();
    }

    @Test
    @Order(7)
    @DisplayName("회차 자리에 세트 자신을 넣으면 거부 — 공통은 회차를 비워서 표현한다")
    void 자기자신은_회차가_아니다() {
        JsonNode r = post("/masters/products/" + setA + "/materials",
                Map.of("materialId", paper1, "roundProductId", setA, "qtyPerSet", 1));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("회차가 세트 자신");
    }

    private java.util.List<String> codesOf(JsonNode arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.path("code").asText()));
        return out;
    }
}
