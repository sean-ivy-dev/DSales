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
 * 물류비용등록(36p) — 매출프로그램 상품 "신규등록". 근거: 발주처 회신 §1-10 원문 —
 * "현재 '신규등록'은 DSRE 상품 기준 데이터를 불러오는 기능 → <b>'DSRE 상품 가져오기'로 명칭 변경</b>하고,
 *  매출프로그램에 등록된 상품을 불러와 <b>작업구분을 선택하면 단가가 자동 적용되는 별도 '신규등록'</b> 신설".
 *
 * <p>화면이 "물류비용 신규 등록은 시행코드가 있어야 합니다"로 막아 뒀던 자리다.
 * DSRE {@code tbl_logis_cost}는 {@code DTL_CD}(시행일코드)로 키를 잡는데 우리 상품엔 시행코드가 없다.
 *
 * <p>★<b>단가는 복사이지 참조가 아니다.</b> 참조로 두면 기준단가를 고치는 순간 과거 출고의
 * 작업비까지 따라 바뀐다 — 발주처 §1-1 "저장된 출고 작업비는 단가 변경에 소급되지 않아야 함".
 * 이 테스트가 그 불변식을 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("물류비용등록 — 매출프로그램 상품 신규등록(36p)")
class ProductLogisRateIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PR" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2092;

    private int packType;
    private long workTypeId;
    private Long product;
    private Long dsreOnlyProduct;

    @BeforeAll
    void seed() {
        token();
        packType = 300 + (int) (System.nanoTime() % 50);
        workTypeId = data(post("/masters/work-types", Map.of(
                "packType", packType, "name", "단가검증구분",
                "paper", 50, "omr", 60, "etc", 70,
                "label", 80, "basic", 90, "trade", 100))).path("id").asLong();

        product = book("PRA" + SFX, true);
        // 단가노출 N = DSRE 병행 상품 — 등록은 되지만 경고가 붙어야 한다.
        dsreOnlyProduct = book("PRB" + SFX, false);
    }

    private Long book(String code, boolean priceVisible) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", "단가교재" + code);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "P" + YEAR + "A");
        b.put("catName", "단가분류");
        b.put("priceVisible", priceVisible);
        b.put("salesDivision", "교재");
        return createId("/masters/products", b);
    }

    @Test
    @DisplayName("★작업구분만 고르면 단가가 자동으로 채워진다 — 요청에 단가를 넣지 않는다")
    void 단가_자동적용() {
        JsonNode d = data(post("/logistics-costs/products",
                Map.of("productId", product, "packType", packType)));
        JsonNode rate = d.path("rate");

        assertThat(rate.path("paper").asInt()).isEqualTo(50);
        assertThat(rate.path("omr").asInt()).isEqualTo(60);
        assertThat(rate.path("basic").asInt()).isEqualTo(90);
        assertThat(rate.path("trade").asInt()).isEqualTo(100);
        assertThat(rate.path("workTypeName").asText()).isEqualTo("단가검증구분");
        assertThat(rate.path("overridden").asBoolean()).as("갓 등록한 행은 예외가 아니다").isFalse();
        assertThat(d.path("warnings")).as("단독관리 상품이라 경고 없음").isEmpty();
    }

    @Test
    @DisplayName("★상품 하나에 단가는 하나 — 두 번 등록하면 400")
    void 중복_등록_거부() {
        Long p = book("PRC" + SFX, true);
        post("/logistics-costs/products", Map.of("productId", p, "packType", packType));

        JsonNode r = post("/logistics-costs/products", Map.of("productId", p, "packType", packType));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("이미 물류단가가 등록된");
    }

    @Test
    @DisplayName("★DSRE 병행 상품은 막지 않고 경고 — 구분값 구조가 확인 대기다")
    void 병행상품은_경고() {
        JsonNode r = post("/logistics-costs/products",
                Map.of("productId", dsreOnlyProduct, "packType", packType));

        assertThat(r.path("success").asBoolean()).as("등록 자체는 된다: %s", r).isTrue();
        assertThat(data(r).path("warnings")).hasSize(1);
        assertThat(data(r).path("warnings").get(0).asText())
                .contains("DSRE 병행", "쓰이지 않습니다");
    }

    @Test
    @DisplayName("★개별 수정한 행은 일괄반영이 건너뛴다 — 조용히 덮이면 잘못된 단가로 청구된다")
    void 개별수정은_예외() {
        // ‼️공유 작업구분(packType)을 쓰지 않는다 — 이 테스트는 기준단가를 바꾸므로,
        //   공유하면 실행 순서에 따라 다른 테스트가 바뀐 단가를 보고 깨진다(실제로 겪었다).
        int pt = 600 + (int) (System.nanoTime() % 50);
        long wt = data(post("/masters/work-types", Map.of(
                "packType", pt, "name", "예외검증구분", "paper", 50, "omr", 60, "etc", 70,
                "label", 80, "basic", 90, "trade", 100))).path("id").asLong();
        Long p = book("PRD" + SFX, true);
        long id = data(post("/logistics-costs/products",
                Map.of("productId", p, "packType", pt))).path("rate").path("id").asLong();

        // 라벨만 고친다 — 나머지는 유지돼야 한다.
        JsonNode after = data(put("/logistics-costs/products/" + id, Map.of("label", 999)))
                .path("rate");
        assertThat(after.path("label").asInt()).isEqualTo(999);
        assertThat(after.path("basic").asInt()).as("지정 안 한 항목은 그대로").isEqualTo(90);
        assertThat(after.path("overridden").asBoolean()).as("예외로 표시된다").isTrue();

        // 기준단가를 바꾸고 일괄반영 → 이 행은 건너뛴다.
        put("/masters/work-types/" + wt, Map.of(
                "name", "예외검증구분", "paper", 11, "omr", 11, "etc", 11,
                "label", 11, "basic", 11, "trade", 11));
        post("/masters/work-types/" + wt + "/apply", Map.of());

        assertThat(rateOf(id).path("label").asInt()).as("★개별 수정분은 안 덮인다").isEqualTo(999);
    }

    @Test
    @DisplayName("★일괄반영이 우리 상품 단가도 덮는다 — 안 그러면 버튼이 절반만 반영한다")
    void 일괄반영이_우리행도_덮는다() {
        int pt = 400 + (int) (System.nanoTime() % 50);
        long wt = data(post("/masters/work-types", Map.of(
                "packType", pt, "name", "일괄대상", "paper", 1, "omr", 1, "etc", 1,
                "label", 1, "basic", 1, "trade", 1))).path("id").asLong();
        Long p = book("PRE" + SFX, true);
        long id = data(post("/logistics-costs/products", Map.of("productId", p, "packType", pt)))
                .path("rate").path("id").asLong();
        assertThat(rateOf(id).path("paper").asInt()).isEqualTo(1);

        put("/masters/work-types/" + wt, Map.of(
                "name", "일괄대상", "paper", 777, "omr", 1, "etc", 1,
                "label", 1, "basic", 1, "trade", 1));
        JsonNode applied = data(post("/masters/work-types/" + wt + "/apply", Map.of()));

        assertThat(rateOf(id).path("paper").asInt()).as("★우리 상품 단가도 덮인다").isEqualTo(777);
        assertThat(applied.path("ourProductCount").asInt()).as("우리 쪽 건수를 갈라 준다").isEqualTo(1);
        assertThat(applied.path("dsreApplied").asBoolean())
                .as("DSRE는 꺼져 있다는 걸 숨기지 않는다").isFalse();
    }

    @Test
    @DisplayName("미리보기는 우리 상품 단가도 바꾸지 않는다")
    void 미리보기는_안바꾼다() {
        int pt = 500 + (int) (System.nanoTime() % 50);
        long wt = data(post("/masters/work-types", Map.of(
                "packType", pt, "name", "미리보기구분", "paper", 5, "omr", 5, "etc", 5,
                "label", 5, "basic", 5, "trade", 5))).path("id").asLong();
        Long p = book("PRF" + SFX, true);
        long id = data(post("/logistics-costs/products", Map.of("productId", p, "packType", pt)))
                .path("rate").path("id").asLong();

        put("/masters/work-types/" + wt, Map.of(
                "name", "미리보기구분", "paper", 8888, "omr", 5, "etc", 5,
                "label", 5, "basic", 5, "trade", 5));
        JsonNode d = data(get("/masters/work-types/" + wt + "/apply/preview"));

        assertThat(d.path("preview").asBoolean()).isTrue();
        assertThat(d.path("ourProductCount").asInt()).as("덮일 건수는 센다").isEqualTo(1);
        assertThat(rateOf(id).path("paper").asInt()).as("★실제로는 안 바뀐다").isEqualTo(5);
    }

    @Test
    @DisplayName("등록되지 않은 작업구분은 400 — 사용 가능한 값을 알려준다")
    void 모르는_작업구분() {
        Long p = book("PRG" + SFX, true);

        JsonNode r = post("/logistics-costs/products",
                Map.of("productId", p, "packType", 99_999));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("등록되지 않은 작업구분");
    }

    @Test
    @DisplayName("삭제하면 목록에서 빠지고 같은 상품으로 다시 등록할 수 있다")
    void 삭제_후_재등록() {
        Long p = book("PRH" + SFX, true);
        long id = data(post("/logistics-costs/products", Map.of("productId", p, "packType", packType)))
                .path("rate").path("id").asLong();

        assertThat(del("/logistics-costs/products/" + id).path("success").asBoolean()).isTrue();
        assertThat(rateOf(id)).as("목록에서 빠진다").isNull();

        // del_key 가 없으면 유니크에 걸려 재등록이 막힌다.
        assertThat(post("/logistics-costs/products", Map.of("productId", p, "packType", packType))
                .path("success").asBoolean()).as("같은 상품으로 다시 등록된다").isTrue();
    }

    /** 목록에서 그 단가 행을 찾는다(없으면 null). */
    private JsonNode rateOf(long id) {
        for (JsonNode r : data(get("/logistics-costs/products?keyword=" + SFX.substring(1)))) {
            if (r.path("id").asLong() == id) {
                return r;
            }
        }
        return null;
    }
}
