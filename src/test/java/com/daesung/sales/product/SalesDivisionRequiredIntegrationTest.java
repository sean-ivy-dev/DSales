package com.daesung.sales.product;

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
 * 상품 세부구분 <b>필수</b> — 10/27 명세 §7-C 하단 3번, 프론트 G-3.
 *
 * <p>명세: "신규 상품에서 미분류가 생기지 않게 하는 방안(예: <b>상품 등록 시 대분류 필수</b>)".
 * 프론트 G-3: "상품 등록·수정 때 세부구분 필수(<b>서버 검사</b>) — 화면 입력칸도 필수로 맞추겠습니다."
 *
 * <p>★<b>등록과 수정의 규칙이 다르다.</b> 수정 화면은 <b>부분 수정</b>이라
 * {@code null} = "안 보냈다 = 기존 값 유지"다. 거기까지 필수로 치면 정가만 고치려 보낸
 * 요청이 거부된다. 그래서 수정에서는 <b>비우려는 것({@code ""})만</b> 막는다 —
 * {@code null}(안 보냄)과 {@code ""}(비우겠다)는 다른 의사표시다(상품명과 같은 규칙).
 *
 * <p>예전엔 빈 값을 통과시켰고 그 근거가 "세부구분 미지정 상품이 실재한다"였는데,
 * 운영 실측(2026-10-30) 결과 <b>상품 14,808건 전부 채워져 있어</b> 전제가 깨졌다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("상품 세부구분 필수(§7-C · 프론트 G-3)")
class SalesDivisionRequiredIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SD" + (System.nanoTime() % 1_000_000L);

    @BeforeAll
    void seed() {
        token();
    }

    private Map<String, Object> body(String code) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", code + " 도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        return b;
    }

    @Test
    @DisplayName("★세부구분 없이 등록하면 거부한다 — 그 상품 매출이 전부 미분류로 떨어진다")
    void 등록시_필수() {
        JsonNode r = post("/masters/products", body("SDA" + SFX));

        assertThat(r.path("success").asBoolean()).as("%s", r).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("세부구분");
    }

    @Test
    @DisplayName("빈 문자열로 등록해도 거부한다 — 공백만 넣어 빠져나갈 수 없다")
    void 등록시_공백도_거부() {
        Map<String, Object> b = body("SDB" + SFX);
        b.put("salesDivision", "   ");

        assertThat(post("/masters/products", b).path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("세부구분을 주면 등록된다")
    void 주면_등록된다() {
        Map<String, Object> b = body("SDC" + SFX);
        b.put("salesDivision", "교재");

        JsonNode r = post("/masters/products", b);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("salesDivision").asText()).isEqualTo("교재");
    }

    @Test
    @DisplayName("★수정에서 세부구분을 비우면 거부한다")
    void 수정시_비우기_금지() {
        Map<String, Object> b = body("SDD" + SFX);
        b.put("salesDivision", "교재");
        long id = data(post("/masters/products", b)).path("id").asLong();

        JsonNode r = put("/masters/products/" + id, Map.of("salesDivision", ""));

        assertThat(r.path("success").asBoolean()).as("%s", r).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("비울 수 없습니다");
    }

    @Test
    @DisplayName("★수정에서 세부구분을 안 보내면 그대로 둔다 — 부분 수정을 막으면 안 된다")
    void 수정시_미전송은_유지() {
        Map<String, Object> b = body("SDE" + SFX);
        b.put("salesDivision", "교재");
        long id = data(post("/masters/products", b)).path("id").asLong();

        // 정가만 고친다 — 세부구분은 보내지 않는다(null = 유지)
        JsonNode r = put("/masters/products/" + id, Map.of("price", 20000));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("salesDivision").asText()).as("그대로 남아야").isEqualTo("교재");
        assertThat(data(r).path("price").asInt()).isEqualTo(20000);
    }
}
