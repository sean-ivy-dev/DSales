package com.daesung.sales.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * API 문서(Swagger)는 <b>운영에서 인증 뒤로</b> 옮겼다(보안 점검 2026-11-19).
 *
 * <p>‼️그전엔 운영에서 인증 없이 404개 엔드포인트의 경로·파라미터·응답 구조가 통째로 나갔다
 * (`/v3/api-docs` 796 KB). 데이터는 401 로 막혀 있었지만 설계도를 그대로 주는 셈이었다.
 * 더 나쁜 것은 <b>swagger-ui 번들 자체가 취약</b>했다는 점이다 — 품고 있던 DOMPurify 에
 * XSS 권고가 23건 있었고, 그 1.4 MB 짜리 .js 를 누구나 받아 갈 수 있었다.
 *
 * <p>★<b>끄지 않고 인증 뒤로 옮겼다.</b> 지우면 프론트·특약점팀이 보던 문서가 사라진다 —
 * 로그인하면 그대로 보인다. 설정 {@code daesung.security.api-docs-public} 기본값이
 * {@code true} 라 <b>개발·시험 서버는 지금처럼 열려 있고</b>, 운영({@code aws} 프로파일)에서만
 * {@code false} 로 닫는다.
 *
 * <p>이 테스트는 <b>기본값 쪽</b>(열림)을 고정한다 — 닫는 쪽을 잘못 건드려 개발 서버 문서가
 * 막히면 프론트 작업이 멈춘다. 운영 쪽 값은 {@code application-aws.yml} 한 줄이다.
 */
@DisplayName("API 문서 노출 — 기본은 열림, 운영만 인증 뒤(2026-11-19)")
class ApiDocsExposureTest extends IntegrationTestSupport {

    // ‼️문서 경로는 `/api/v1` **밖**이다(springdoc 기본). 베이스의 exchangeRaw 는
    //   `/api/v1` 을 자동으로 붙이므로 그걸 쓰면 `/api/v1/v3/api-docs` 를 때려 401 이 난다
    //   — 실제로 그렇게 짰다가 이 테스트가 잡았다. 그래서 여기선 절대경로로 부른다.
    @Autowired
    private TestRestTemplate raw;

    @Test
    @DisplayName("기본값(개발·시험)은 인증 없이도 열린다 — 프론트가 쓰는 길이다")
    void 기본값은_열려있다() {
        for (String p : new String[] {"/v3/api-docs", "/swagger-ui/index.html"}) {
            int code = raw.getForEntity(p, String.class).getStatusCode().value();
            assertThat(code).as("%s 는 기본값에서 열려 있어야 한다", p).isNotEqualTo(401);
        }
    }

    @Test
    @DisplayName("‼️데이터 API 는 여전히 인증을 요구한다 — 문서를 연다고 데이터가 열리지 않는다")
    void 데이터는_여전히_막힌다() {
        assertThat(raw.getForEntity("/api/v1/sales", String.class).getStatusCode().value())
                .isEqualTo(401);
    }
}
