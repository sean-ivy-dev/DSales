package com.daesung.sales.dealer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 특약점 사이트 서버 전용 경로({@value #PATH_PREFIX})의 문지기.
 *
 * <p>키가 맞으면 감사 주체를 {@code dealer-site}(설정값)로 세워 통과시키고, 틀리거나 없으면
 * <b>401</b>로 끊는다. SecurityConfig 가 이 경로를 {@code permitAll} 로 두는 이유가 이것이다 —
 * 인가를 JWT·권한표가 아니라 <b>이 필터가</b> 한다. 둘 다 걸면 사이트 서버가 사람 계정으로
 * 로그인해야 하고, 그러면 사이트에 사람 토큰이 상주한다.
 *
 * <p>‼️키 비교는 {@link MessageDigest#isEqual}로 한다 — {@code equals}는 앞에서 다른 글자가
 * 나오면 바로 끝나서, 응답 시간으로 키를 한 글자씩 맞춰 볼 수 있다.
 *
 * <p>‼️<b>이 키는 "어느 거래처냐"를 증명하지 않는다.</b> 어느 거래처의 주문인지는 요청 본문이
 * 말하고, 그 말을 믿는 근거는 <b>사이트 서버가 특약점 로그인 세션을 들고 있다</b>는 것뿐이다.
 * 따라서 이 키가 새면 남의 거래처로 주문을 넣을 수 있다 — 브라우저에 내려보내면 안 되고,
 * 사이트 <b>서버</b>에서만 호출해야 한다(명세 §10-3 권장안이 그 뜻이다).
 */
public class DealerSiteKeyFilter extends OncePerRequestFilter {

    public static final String PATH_PREFIX = "/api/v1/dealer-site/";
    public static final String HEADER = "X-Dealer-Site-Key";

    private final DealerSiteProperties properties;

    public DealerSiteKeyFilter(DealerSiteProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.enabled()) {
            // 키 미설정 = 아직 열지 않은 경로다. "설정하면 열린다"를 메시지로 알려 준다 —
            // 사이트 쪽이 403 만 보고 키가 틀렸다고 오해하면 엉뚱한 데를 고친다.
            deny(response, "특약점 사이트 연동이 설정되지 않았습니다(daesung.dealer-site.api-key).");
            return;
        }
        String given = request.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8),
                properties.apiKey().getBytes(StandardCharsets.UTF_8))) {
            deny(response, "특약점 사이트 키가 없거나 올바르지 않습니다(" + HEADER + ").");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(properties.auditName(), null, List.of()));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void deny(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        // 공통 응답 형식({success,data,error})을 지킨다 — 사이트 쪽 파서가 한 가지만 알면 된다.
        response.getWriter().write("{\"success\":false,\"data\":null,\"error\":{"
                + "\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}}");
    }
}
