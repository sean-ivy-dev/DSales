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
 * 마스터 <b>동시 수정</b>이 앞사람 수정을 덮어쓰지 않는다(프론트 실측 2026-10-22 ⑤).
 *
 * <p>‼️거래처 수정은 <b>전체 교체</b>라 특히 위험하다 — 실제로 부산지사 내용을 날린 적이 있다.
 *
 * <p>★{@code @Version} 컬럼만으로는 못 막는다. 요청마다 새로 읽으면 버전이 늘 일치하기 때문이다.
 * <b>읽을 때 받은 버전을 되돌려 보내야</b> 그 사이 남이 고쳤는지 드러난다 —
 * 그래서 이 테스트는 "버전을 보낸 경우"와 "안 보낸 경우"를 <b>둘 다</b> 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("마스터 동시 수정 충돌(버전 검사)")
class ConcurrentEditIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CE" + (System.nanoTime() % 1_000_000L);

    private Long partner;
    private Long product;

    @BeforeAll
    void seed() {
        token();
        partner = createId("/masters/clients",
                Map.of("code", "CEP" + SFX, "name", "동시수정거래처", "type", "NORMAL"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "CEB" + SFX);
        b.put("name", "동시수정 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "C2094A01");
        b.put("catName", "동시수정");
        b.put("taxFree", false);
        b.put("useYn", true);
        b.put("set", false);
        b.put("salesDivision", "교재");
        product = createId("/masters/products", b);
    }

    private long version(String path) {
        JsonNode d = data(get(path));
        assertThat(d.hasNonNull("version")).as("응답에 version 이 실려야 되돌려 보낼 수 있다: %s", d).isTrue();
        return d.path("version").asLong();
    }

    @Test
    @DisplayName("★먼저 읽은 버전으로 나중에 저장하면 409 — 앞사람 수정을 덮지 않는다")
    void 거래처_동시수정_충돌() {
        String path = "/masters/clients/" + partner;
        long stale = version(path);   // A 가 화면을 열었다

        // B 가 먼저 저장한다(버전이 올라간다)
        assertThat(put(path, Map.of("name", "B가 고친 이름", "type", "NORMAL",
                "version", stale)).path("success").asBoolean()).as("B 저장").isTrue();

        // ‼️A 는 아직 옛 버전을 들고 있다. 그대로 저장하면 B 의 수정이 사라진다.
        JsonNode a = put(path, Map.of("name", "A가 고친 이름", "type", "NORMAL", "version", stale));
        assertThat(a.path("success").asBoolean()).as("A 저장은 막혀야 한다: %s", a).isFalse();
        assertThat(a.path("error").path("code").asText()).isEqualTo("DUPLICATE_REQUEST");

        // B 가 고친 값이 그대로 살아 있어야 한다 — 이게 진짜 보장이다.
        assertThat(data(get(path)).path("name").asText()).isEqualTo("B가 고친 이름");

        // 새로 읽은 버전으로는 저장된다(영영 막히면 그것도 고장이다).
        assertThat(put(path, Map.of("name", "A가 다시 고침", "type", "NORMAL",
                "version", version(path))).path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("도서도 같은 규칙 — 부분 수정이어도 같은 칸을 동시에 고치면 막힌다")
    void 도서_동시수정_충돌() {
        String path = "/masters/products/" + product;
        long stale = version(path);

        assertThat(put(path, Map.of("name", "B 교재", "version", stale)).path("success").asBoolean()).isTrue();

        JsonNode a = put(path, Map.of("name", "A 교재", "version", stale));
        assertThat(a.path("success").asBoolean()).isFalse();
        assertThat(a.path("error").path("code").asText()).isEqualTo("DUPLICATE_REQUEST");
        assertThat(data(get(path)).path("name").asText()).isEqualTo("B 교재");
    }

    @Test
    @DisplayName("‼️버전을 안 보내면 예전처럼 그냥 덮어쓴다 — 기존 호출을 깨지 않는다")
    void 버전없으면_통과() {
        String path = "/masters/products/" + product;

        // 버전 없이 두 번 저장 — 둘 다 통과해야 한다(보호받지 못한다는 뜻이기도 하다).
        assertThat(put(path, Map.of("name", "버전없이1")).path("success").asBoolean()).isTrue();
        assertThat(put(path, Map.of("name", "버전없이2")).path("success").asBoolean()).isTrue();
        assertThat(data(get(path)).path("name").asText()).isEqualTo("버전없이2");
    }
}
