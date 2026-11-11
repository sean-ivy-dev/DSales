package com.daesung.sales.master;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 거래처 이름의 <b>공백을 무시한 검색</b>(발주처 2026-11-11 승인).
 *
 * <p>레거시는 개인 거래처를 {@code '조 병 준'} 처럼 글자 사이에 공백을 넣어 저장했고
 * 그대로 이관됐다 — 운영에 <b>75곳</b>이 그 모양이다. 담당자가 "조병준"으로 검색하면
 * 한 건도 안 나와 "그런 거래처가 없다"고 오진하게 된다(실제로 그렇게 보고된 적이 있다).
 *
 * <p>★<b>이름 데이터는 고치지 않는다</b> — 발주처 지시다. 검색만 양쪽 공백을 지워 비교한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("거래처 검색 — 이름 공백 무시(발주처 2026-11-11)")
class PartnerNameSpaceSearchIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "NS" + (System.nanoTime() % 1_000_000L);

    /** 레거시와 같은 모양: 글자 사이에 공백. */
    private static final String SPACED = "조 병 준 " + SFX;

    private Long spaced;
    private Long other;

    @BeforeAll
    void seed() {
        token();
        spaced = createId("/masters/clients",
                Map.of("code", "NSA" + SFX, "name", SPACED, "type", "NORMAL"));
        other = createId("/masters/clients",
                Map.of("code", "NSB" + SFX, "name", "무관거래처" + SFX, "type", "NORMAL"));
    }

    @Test
    @DisplayName("‼️공백 없이 검색해도 '조 병 준' 이 나온다 — 이게 핵심")
    void 공백없는_키워드로_찾힌다() {
        assertThat(found("/masters/clients?keyword=조병준" + SFX, spaced))
                .as("공백을 지운 비교가 되어야 한다").isTrue();
    }

    @Test
    @DisplayName("공백을 넣어 검색해도 나온다 — 원래 표기 그대로 찾는 경로도 살아 있다")
    void 공백있는_키워드로도_찾힌다() {
        assertThat(found("/masters/clients?keyword=조 병 준 " + SFX, spaced)).isTrue();
    }

    @Test
    @DisplayName("이름 데이터는 그대로다 — 공백을 지우는 건 검색뿐이다")
    void 이름은_안_고친다() {
        JsonNode res = get("/masters/clients/" + spaced);
        assertThat(res.path("data").path("name").asText())
                .as("저장된 이름에서 공백이 사라지면 안 된다").isEqualTo(SPACED);
    }

    @Test
    @DisplayName("무관한 거래처를 끌어오지 않는다 — 공백 무시가 조건을 넓히기만 하면 안 된다")
    void 무관한_거래처는_안_나온다() {
        assertThat(found("/masters/clients?keyword=조병준" + SFX, other))
                .as("이름이 전혀 다른 거래처가 섞이면 안 된다").isFalse();
    }

    private boolean found(String path, Long id) {
        for (JsonNode n : get(path).path("data").path("content")) {
            if (n.path("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }
}
