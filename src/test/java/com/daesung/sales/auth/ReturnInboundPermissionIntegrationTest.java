package com.daesung.sales.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * <b>반품 입고는 물류 계정이 쓸 수 있어야 한다</b>(발주처 확인요청 2026-11-24 · PDF 1쪽).
 *
 * <p>‼️<b>현상</b>: 입고/대체 등록(물류) &gt; 반품 등록에서 <b>관리자는 반품 도서가 보이고
 * 물류 담당은 「반품할 수 있는 도서가 없습니다」</b> 가 떴다.
 *
 * <p>‼️<b>원인</b>: 그 화면이 부르는 {@code /sales/returnable}·{@code /sales/return-inbound} 가
 * 권한표의 {@code /sales/**}(SALES, 60)에 먹혀 <b>LOGISTICS = NONE → 403</b> 이었다.
 * 화면은 조회 실패를 「목록이 비었다」로 보여 주고 있어 권한 문제가 「도서가 없다」로 나타났다.
 *
 * <p>★{@code /sales/**} 를 물류에 열지 않았다 — 그러면 통합 매출 조회·매출 등록·취소까지
 * 함께 열린다. <b>반품 입고에 필요한 두 경로만</b> 앞 순서로 따로 뒀다(V134).
 *
 * <p>★<b>이 테스트가 없어서 못 잡았다.</b> 권한은 코드가 아니라 DB 표라
 * 컴파일도 기존 테스트도 걸러 주지 않는다 — 역할별로 실제 요청을 보내야 드러난다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("반품 입고 권한 — 물류 계정(2026-11-24)")
class ReturnInboundPermissionIntegrationTest extends IntegrationTestSupport {

    private static final String PW = "Pw123456!";
    private static final String SFX = "rip" + (System.nanoTime() % 1_000_000L);

    @BeforeAll
    void seed() {
        token();
        for (String[] u : new String[][]{{"logis", "LOGISTICS"}, {"sales", "SALES"},
                                        {"fin", "FINANCE"}}) {
            exchangeRaw(HttpMethod.POST, "/auth/users", Map.of(
                    "username", u[0] + "-" + SFX, "password", PW,
                    "name", u[0] + SFX, "role", u[1]), token(), null);
        }
    }

    private String login(String who) throws Exception {
        ResponseEntity<String> r = exchangeRaw(HttpMethod.POST, "/auth/login",
                Map.of("username", who + "-" + SFX, "password", PW), null, null);
        return om.readTree(r.getBody()).path("data").path("accessToken").asText();
    }

    private int status(HttpMethod m, String path, Object body, String bearer) {
        return exchangeRaw(m, path, body, bearer, null).getStatusCode().value();
    }

    @Test
    @DisplayName("★★물류 계정이 **반품 가능내역을 조회**한다 — 403 이면 화면이 「도서가 없다」로 보인다")
    void 물류는_반품가능내역_조회() throws Exception {
        assertThat(status(HttpMethod.GET, "/sales/returnable?partnerId=1", null, login("logis")))
                .as("‼️403 이면 V134 권한이 안 먹은 것이다")
                .isNotEqualTo(403);
    }

    @Test
    @DisplayName("★물류 계정이 **반품 입고를 등록**할 수 있다 — 반품 입고는 물류 담당 업무다")
    void 물류는_반품입고_등록() throws Exception {
        // 본문은 비워 보낸다 — 400(검증 실패)이면 **권한은 통과**한 것이다.
        assertThat(status(HttpMethod.POST, "/sales/return-inbound", Map.of(), login("logis")))
                .as("‼️403 이면 물류가 반품을 등록할 수 없다")
                .isNotEqualTo(403);
    }

    @Test
    @DisplayName("‼️물류에게 **매출관리 전체를 열지는 않았다** — 통합 매출 조회·등록은 그대로 막힌다")
    void 물류는_매출관리_여전히_막힘() throws Exception {
        String logis = login("logis");
        assertThat(status(HttpMethod.GET, "/sales?page=0&size=1", null, logis))
                .as("통합 매출 조회는 물류 업무가 아니다").isEqualTo(403);
        assertThat(status(HttpMethod.POST, "/sales/entries", Map.of(), logis))
                .as("매출 등록도 막혀야 한다").isEqualTo(403);
    }

    @Test
    @DisplayName("‼️**다른 역할이 종전대로 된다** — 구체 패턴이 /sales/** 를 이기므로 줄이 없으면 막힌다")
    void 다른_역할도_그대로() throws Exception {
        // 영업: 종전 /sales/** = WRITE → 조회·등록 둘 다 열려야 한다
        String sales = login("sales");
        assertThat(status(HttpMethod.GET, "/sales/returnable?partnerId=1", null, sales))
                .as("영업 조회").isNotEqualTo(403);
        assertThat(status(HttpMethod.POST, "/sales/return-inbound", Map.of(), sales))
                .as("영업 등록").isNotEqualTo(403);

        // 재무: 종전 /sales/** = READ → 조회만
        String fin = login("fin");
        assertThat(status(HttpMethod.GET, "/sales/returnable?partnerId=1", null, fin))
                .as("재무 조회").isNotEqualTo(403);
        assertThat(status(HttpMethod.POST, "/sales/return-inbound", Map.of(), fin))
                .as("‼️재무가 반품을 등록하면 안 된다 — 종전 /sales/** 가 READ 였다").isEqualTo(403);

        // 관리자
        assertThat(status(HttpMethod.GET, "/sales/returnable?partnerId=1", null, token()))
                .as("관리자 조회").isNotEqualTo(403);
    }
}
