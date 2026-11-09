package com.daesung.sales.dashboard;

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
 * 상품별 매출목표 — <b>상품코드·명을 함께 준다</b>(F-25).
 *
 * <p>발주처 검수 F-25: *"기준대상을 '상품'으로 바꾸고 상품을 골라도 창 제목이 '(전사)'인
 * 같은 월별 창이 뜸 — 상품별 목표 입력 칸 없음"*.
 *
 * <p>★<b>등록·조회 자체는 전부터 됐다</b>(운영 실측으로 확인). 빠져 있던 것은
 * <b>상품코드·상품명</b>이다 — id 만 내려가서 화면이 "어느 상품의 목표인지" 보여주려면
 * 상품마다 따로 조회해야 했고, 그래서 창 제목이 '(전사)' 로 남았다.
 *
 * <p>★상품은 <b>묶어 읽는다</b>. 목표 한 줄마다 상품을 조회하면 목록 한 장에 수십 번이 된다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("상품별 매출목표 — 상품코드·명 노출(F-25)")
class ProductTargetIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PT" + (System.nanoTime() % 1_000_000L);
    /** 다른 테스트와 겹치지 않는 연도 — 목표 목록은 연도 전체를 낸다. */
    private static final int YEAR = 2095;

    private Long product;

    @BeforeAll
    void seed() {
        token();
        Map<String, Object> b = new HashMap<>();
        b.put("code", "PTB" + SFX);
        b.put("name", "목표검증 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2095A01");
        b.put("catName", "목표검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        product = createId("/masters/products", b);
    }

    @Test
    @DisplayName("상품별 목표를 등록하면 목록에 **상품코드·상품명**이 함께 온다")
    void 목록에_상품명() {
        JsonNode res = post("/dashboard/targets", Map.of(
                "year", YEAR, "month", 5, "scope", "PRODUCT",
                "productId", product, "targetAmount", 5_000_000));
        assertThat(res.path("success").asBoolean()).as("등록: %s", res).isTrue();

        JsonNode list = get("/dashboard/targets?year=" + YEAR);
        assertThat(list.path("success").asBoolean()).isTrue();

        JsonNode mine = null;
        for (JsonNode x : list.path("data")) {
            if (product.equals(x.path("productId").asLong())) {
                mine = x;
            }
        }
        assertThat(mine).as("등록한 상품 목표가 목록에 있어야 한다: %s", list).isNotNull();
        assertThat(mine.path("scope").asText()).isEqualTo("PRODUCT");
        // ‼️이 두 칸이 없어서 화면이 상품을 이름으로 보여줄 수 없었다.
        assertThat(mine.path("productCode").asText()).isEqualTo("PTB" + SFX);
        assertThat(mine.path("productName").asText()).isEqualTo("목표검증 교재");
        assertThat(mine.path("amount").asLong()).isEqualTo(5_000_000L);
    }

    @Test
    @DisplayName("상품별 대시보드 조회에도 상품코드·명이 온다 — 창 제목에 쓴다")
    void 대시보드에_상품명() {
        assertThat(post("/dashboard/targets", Map.of(
                "year", YEAR, "month", 7, "scope", "PRODUCT",
                "productId", product, "targetAmount", 3_000_000))
                .path("success").asBoolean()).isTrue();

        JsonNode res = get("/dashboard/sales?year=" + YEAR + "&scope=PRODUCT&productId=" + product);
        assertThat(res.path("success").asBoolean()).as("조회: %s", res).isTrue();
        JsonNode d = res.path("data");
        assertThat(d.path("scope").asText()).isEqualTo("PRODUCT");
        assertThat(d.path("productCode").asText()).isEqualTo("PTB" + SFX);
        assertThat(d.path("productName").asText()).isEqualTo("목표검증 교재");

        // 7월 목표가 그 상품 축으로 잡힌다
        boolean found = false;
        for (JsonNode m : d.path("months")) {
            if (m.path("month").asInt() == 7) {
                assertThat(m.path("target").asLong()).isEqualTo(3_000_000L);
                found = true;
            }
        }
        assertThat(found).as("7월 셀: %s", d.path("months")).isTrue();
    }

    @Test
    @DisplayName("‼️잘못 넣은 목표를 **지울 수 있다** — 0으로 덮는 것과 다르다")
    void 목표_삭제() {
        JsonNode created = post("/dashboard/targets", Map.of(
                "year", YEAR, "month", 11, "scope", "PRODUCT",
                "productId", product, "targetAmount", 1_111_111));
        assertThat(created.path("success").asBoolean()).isTrue();

        Long id = null;
        for (JsonNode x : get("/dashboard/targets?year=" + YEAR).path("data")) {
            if (x.path("month").asInt() == 11 && product.equals(x.path("productId").asLong())) {
                id = x.path("id").asLong();
            }
        }
        assertThat(id).as("11월 목표 id").isNotNull();

        assertThat(del("/dashboard/targets/" + id).path("success").asBoolean()).isTrue();

        // ‼️0으로 남는 게 아니라 **줄이 사라진다**. 대시보드는 0(미달)과 미입력(빈칸)을 다르게 본다.
        for (JsonNode x : get("/dashboard/targets?year=" + YEAR).path("data")) {
            assertThat(x.path("id").asLong()).as("지운 줄이 남아 있다: %s", x).isNotEqualTo(id);
        }
        // 없는 id 는 404
        assertThat(del("/dashboard/targets/" + id).path("success").asBoolean())
                .as("없는 목표 삭제는 거부한다").isFalse();
    }

    @Test
    @DisplayName("전사 목표는 상품 칸이 비어 온다 — 축이 섞이면 화면이 잘못 묶는다")
    void 전사목표는_상품없음() {
        assertThat(post("/dashboard/targets", Map.of(
                "year", YEAR, "month", 9, "scope", "COMPANY", "targetAmount", 9_000_000))
                .path("success").asBoolean()).isTrue();

        for (JsonNode x : get("/dashboard/targets?year=" + YEAR).path("data")) {
            if ("COMPANY".equals(x.path("scope").asText())) {
                // Jackson NON_NULL 이라 null 은 키가 없다.
                assertThat(x.has("productId")).as("전사 목표에 상품이 붙으면 안 된다: %s", x).isFalse();
                assertThat(x.has("productCode")).isFalse();
                assertThat(x.has("productName")).isFalse();
            }
        }
    }
}
