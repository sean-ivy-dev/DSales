package com.daesung.sales.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * 인증 보안 강화 통합테스트: refresh httpOnly 쿠키 + 재발급 회전 + 재사용(탈취) 탐지.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("인증 보안(쿠키·재사용탐지) 통합테스트")
class AuthSecurityIntegrationTest extends IntegrationTestSupport {

    @BeforeAll
    void seedUser() {
        // 관리자 권한으로 전용 테스트 계정 생성(family-revoke를 admin과 격리)
        exchangeRaw(HttpMethod.POST, "/auth/users", Map.of(
                "username", "reuse-user", "password", "Reuse1234!", "name", "재사용테스트", "role", "SALES"),
                token(), null);
        // RBAC 검증용 역할별 계정
        // ★조회전용(VIEWER) 역할은 없앴다(발주처 확정: 역할 4종).
        //   "조회만 되는 상태"는 이제 권한 표에서 그 화면을 READ로 둔 결과로 나타난다.
        createUser("rbac-finance", "FINANCE");
        createUser("rbac-sales", "SALES");
        createUser("rbac-logis", "LOGISTICS");
    }

    private void createUser(String username, String role) {
        exchangeRaw(HttpMethod.POST, "/auth/users", Map.of(
                "username", username, "password", "Pw123456!", "name", username, "role", role), token(), null);
    }

    /** 해당 계정 로그인 → access 토큰. */
    private String loginToken(String username) throws Exception {
        var resp = exchangeRaw(HttpMethod.POST, "/auth/login",
                Map.of("username", username, "password", "Pw123456!"), null, null);
        return om.readTree(resp.getBody()).path("data").path("accessToken").asText();
    }

    private int status(HttpMethod method, String path, Object body, String bearer) {
        return exchangeRaw(method, path, body, bearer, null).getStatusCode().value();
    }

    @Test
    @DisplayName("★권한거부(403)가 접근로그에 남는다 — 예전엔 403만 통째로 빠져 있었다")
    void 권한거부_기록() throws Exception {
        String finance = loginToken("rbac-finance");

        // 관리자 전용 경로를 재무 계정으로 두드린다 — GET이라 평소엔 안 남는 종류다.
        assertThat(status(HttpMethod.GET, "/audit/access-log", null, finance)).isEqualTo(403);

        JsonNode rows = om.readTree(
                exchangeRaw(HttpMethod.GET, "/audit/access-log?size=200&actions=DENIED", null, token(), null)
                        .getBody()).path("data").path("content");

        JsonNode hit = null;
        for (JsonNode r : rows) {
            if ("rbac-finance".equals(r.path("username").asText())
                    && r.path("path").asText().contains("/audit/access-log")) {
                hit = r;
                break;
            }
        }
        assertThat(hit).as("403 시도가 DENIED로 남아야 한다").isNotNull();
        // ★누가 시도했는지가 핵심이다 — 필터를 시큐리티 앞으로 당겼다면 여기가 (비로그인)이 된다.
        assertThat(hit.path("role").asText()).isEqualTo("FINANCE");
        assertThat(hit.path("status").asInt()).isEqualTo(403);
        assertThat(hit.path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★계정 중지 — 지우지 않고 끈다. 자기 자신·마지막 관리자는 막는다")
    void 계정_중지() throws Exception {
        createUser("deact-target", "SALES");
        assertThat(loginToken("deact-target")).isNotBlank();   // 끄기 전엔 로그인된다

        long id = -1;
        JsonNode users = om.readTree(
                exchangeRaw(HttpMethod.GET, "/permissions/users", null, token(), null).getBody())
                .path("data");
        long adminId = -1;
        for (JsonNode u : users) {
            if ("deact-target".equals(u.path("username").asText())) {
                id = u.path("userId").asLong();
            }
            if ("admin".equals(u.path("username").asText())) {
                adminId = u.path("userId").asLong();
            }
        }
        assertThat(id).isPositive();

        // 중지 → 로그인이 막힌다(401)
        assertThat(status(HttpMethod.PUT, "/auth/users/" + id + "/active",
                Map.of("active", false), token())).isEqualTo(200);
        assertThat(exchangeRaw(HttpMethod.POST, "/auth/login",
                Map.of("username", "deact-target", "password", "Pw123456!"), null, null)
                .getStatusCode().value()).isEqualTo(401);

        // 같은 요청을 또 보내도 오류가 아니다(멱등)
        assertThat(status(HttpMethod.PUT, "/auth/users/" + id + "/active",
                Map.of("active", false), token())).isEqualTo(200);

        // 재개 → 다시 로그인된다
        assertThat(status(HttpMethod.PUT, "/auth/users/" + id + "/active",
                Map.of("active", true), token())).isEqualTo(200);
        assertThat(loginToken("deact-target")).isNotBlank();

        // ★자기 자신은 못 끈다 — 끄면 되돌릴 사람이 없어진다
        assertThat(adminId).isPositive();
        assertThat(status(HttpMethod.PUT, "/auth/users/" + adminId + "/active",
                Map.of("active", false), token())).isEqualTo(400);

        // 관리자 아닌 계정은 이 경로 자체가 막힌다
        assertThat(status(HttpMethod.PUT, "/auth/users/" + id + "/active",
                Map.of("active", false), loginToken("rbac-sales"))).isEqualTo(403);
    }

    @Test
    @DisplayName("RBAC — 판정이 권한 표(V52)에서 나온다")
    void rbac매트릭스() throws Exception {
        String finance = loginToken("rbac-finance");
        String sales = loginToken("rbac-sales");
        String logis = loginToken("rbac-logis");

        // 마감/세무: 재무 WRITE, 물류 NONE(비노출이라 조회도 막힌다)
        assertThat(status(HttpMethod.GET, "/closing/revenue-report?taxType=FREE", null, finance)).isEqualTo(200);
        assertThat(status(HttpMethod.GET, "/closing/revenue-report?taxType=FREE", null, logis)).isEqualTo(403);

        // 매출등록(쓰기): 영업 WRITE, 재무는 READ라 막힌다
        //   — 발주처 확정 "재무×매출관리는 조회만, 편집은 관리자 계정으로".
        assertThat(status(HttpMethod.POST, "/sales/entries", Map.of(), finance)).isEqualTo(403);

        // 같은 재무 계정이 매출 '조회'는 된다 — READ와 WRITE가 갈린다는 증거
        assertThat(status(HttpMethod.GET, "/sales/statement?fromDate=2026-06-01&toDate=2026-06-30",
                null, finance)).isEqualTo(200);

        // 기초관리 쓰기: 관리자만. 영업은 READ
        assertThat(status(HttpMethod.POST, "/masters/clients",
                Map.of("code", "X", "name", "X", "type", "NORMAL"), sales)).isEqualTo(403);

        // 물류단가 수정: 물류 WRITE, 영업은 READ
        assertThat(status(HttpMethod.PUT, "/logistics-costs/rates/1", Map.of(), sales)).isEqualTo(403);

        // 입고/대체등록: 물류 WRITE, 영업 NONE(2단계 표에서 영업 N)
        assertThat(status(HttpMethod.POST, "/stock/inbound", Map.of(), sales)).isEqualTo(403);

        // ★제품수불부는 물류가 본다 — /stock/ledger 패턴이 /stock 보다 앞이라
        //   입고등록 권한(물류 WRITE)과 별개로 잡힌다. 순서가 어긋나면 이 검증이 깨진다.
        assertThat(status(HttpMethod.GET, "/stock/ledger", null, logis)).isEqualTo(200);

        // /auth/me 역할 반영
        var me = om.readTree(exchangeRaw(HttpMethod.GET, "/auth/me", null, finance, null).getBody());
        assertThat(me.path("data").path("role").asText()).isEqualTo("FINANCE");
    }

    @Test
    @DisplayName("로그인 — refresh는 바디에 없고 httpOnly 쿠키로 발급")
    void 로그인_쿠키() throws Exception {
        ResponseEntity<String> resp = login();
        JsonNode body = om.readTree(resp.getBody());
        // access는 바디, refresh는 바디에 없음(null/생략)
        assertThat(body.path("data").path("accessToken").asText()).isNotBlank();
        assertThat(body.path("data").path("refreshToken").asText("")).isEmpty();
        // Set-Cookie에 httpOnly refresh 쿠키
        String setCookie = firstRefreshSetCookie(resp);
        assertThat(setCookie).contains("refresh_token=").contains("HttpOnly");
    }

    @Test
    @DisplayName("재사용 탐지 — 회전된 옛 refresh 재사용 시 전체 세션 무효화")
    void 재사용탐지() throws Exception {
        String cookie1 = refreshCookieValue(login());       // 최초 refresh

        // 1차 재발급(정상 회전) → 새 refresh(cookie2)
        ResponseEntity<String> r1 = exchangeRaw(HttpMethod.POST, "/auth/refresh", null, null, cookie1);
        assertThat(r1.getStatusCode().is2xxSuccessful()).isTrue();
        String cookie2 = refreshCookieValue(r1);

        // 옛 refresh(cookie1) 재사용 → 재사용 탐지로 401 + 전체 무효화
        ResponseEntity<String> reuse = exchangeRaw(HttpMethod.POST, "/auth/refresh", null, null, cookie1);
        assertThat(reuse.getStatusCode().value()).isEqualTo(401);
        assertThat(om.readTree(reuse.getBody()).path("error").path("message").asText()).contains("재사용");

        // family-revoke로 정상 회전본(cookie2)도 무효화됨
        ResponseEntity<String> after = exchangeRaw(HttpMethod.POST, "/auth/refresh", null, null, cookie2);
        assertThat(after.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("★비밀번호 — 관리자 초기화 · 본인 변경(발주처 11/12 ③). 바꾸면 옛 비밀번호·옛 세션은 끝난다")
    void 비밀번호_초기화와_변경() throws Exception {
        String user = "pw-user-" + System.nanoTime() % 1_000_000L;
        createUser(user, "SALES");
        long id = 0;
        for (JsonNode u : om.readTree(
                exchangeRaw(HttpMethod.GET, "/permissions/users", null, token(), null).getBody())
                .path("data")) {
            if (user.equals(u.path("username").asText())) {
                id = u.path("userId").asLong();
            }
        }
        assertThat(id).as("만든 계정이 목록에 있다").isPositive();
        String oldCookie = refreshCookieValue(exchangeRaw(HttpMethod.POST, "/auth/login",
                Map.of("username", user, "password", "Pw123456!"), null, null));

        // ① 관리자 초기화 — 관리자만, 8자 이상
        assertThat(status(HttpMethod.PUT, "/auth/users/" + id + "/password",
                Map.of("newPassword", "Temp1234!"), loginToken("rbac-sales"))).isEqualTo(403);
        assertThat(status(HttpMethod.PUT, "/auth/users/" + id + "/password",
                Map.of("newPassword", "short"), token())).isEqualTo(400);
        assertThat(status(HttpMethod.PUT, "/auth/users/" + id + "/password",
                Map.of("newPassword", "Temp1234!"), token())).isEqualTo(200);
        assertThat(loginStatus(user, "Pw123456!")).as("옛 비밀번호는 안 된다").isEqualTo(401);
        assertThat(loginStatus(user, "Temp1234!")).isEqualTo(200);
        assertThat(exchangeRaw(HttpMethod.POST, "/auth/refresh", null, null,
                oldCookie).getStatusCode().value())
                .as("초기화 전 세션은 재발급이 막힌다").isEqualTo(401);

        // ② 본인 변경 — 역할과 무관하게 자기 것. 지금 비밀번호가 맞아야 하고, 같은 값은 안 된다
        String bearer = om.readTree(exchangeRaw(HttpMethod.POST, "/auth/login",
                Map.of("username", user, "password", "Temp1234!"), null, null).getBody())
                .path("data").path("accessToken").asText();
        assertThat(status(HttpMethod.PUT, "/auth/me/password",
                Map.of("currentPassword", "wrong-pass", "newPassword", "Mine5678!"), bearer)).isEqualTo(400);
        assertThat(status(HttpMethod.PUT, "/auth/me/password",
                Map.of("currentPassword", "Temp1234!", "newPassword", "Temp1234!"), bearer)).isEqualTo(400);
        assertThat(status(HttpMethod.PUT, "/auth/me/password",
                Map.of("currentPassword", "Temp1234!", "newPassword", "Mine5678!"), bearer)).isEqualTo(200);
        assertThat(loginStatus(user, "Temp1234!")).isEqualTo(401);
        assertThat(loginStatus(user, "Mine5678!")).isEqualTo(200);
        assertThat(status(HttpMethod.PUT, "/auth/me/password",
                Map.of("currentPassword", "Mine5678!", "newPassword", "Other999!"), null))
                .as("로그인 안 하면 못 바꾼다").isEqualTo(401);
    }

    private int loginStatus(String username, String password) {
        return exchangeRaw(HttpMethod.POST, "/auth/login",
                Map.of("username", username, "password", password), null, null).getStatusCode().value();
    }

    // ── helpers ──

    private ResponseEntity<String> login() {
        return exchangeRaw(HttpMethod.POST, "/auth/login",
                Map.of("username", "reuse-user", "password", "Reuse1234!"), null, null);
    }

    /** 응답 Set-Cookie 중 refresh_token 헤더 원문. */
    private String firstRefreshSetCookie(ResponseEntity<String> resp) {
        var cookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull();
        return cookies.stream().filter(c -> c.startsWith("refresh_token=")).findFirst().orElseThrow();
    }

    /** 다음 요청에 보낼 "refresh_token=값" 쿠키 문자열. */
    private String refreshCookieValue(ResponseEntity<String> resp) {
        String setCookie = firstRefreshSetCookie(resp);
        return setCookie.split(";", 2)[0];   // "refresh_token=xxxx"
    }
}
