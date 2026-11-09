package com.daesung.sales.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 웹 공통 설정.
 * - 모든 @RestController에 '/api/v1' 공통 prefix
 * - CORS(React 프론트 별도 오리진) — SecurityFilterChain이 이 빈을 사용
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * <b>쿼리 파라미터의 대분류를 한글로도 받는다</b>(F-31).
     *
     * <p>‼️{@code @JsonCreator}({@link com.daesung.sales.product.entity.MajorCategory#from})는
     * <b>요청 본문에만</b> 적용된다. 쿼리 파라미터는 스프링이 {@code Enum.valueOf} 로 바꾸므로
     * 한글을 보내면 400 이 된다 — 같은 enum 인데 본문은 한글이 되고 파라미터는 안 되는
     * 어긋남이 생긴다. 그 enum 주석이 경고한 상황이 그것이다
     * ("응답이 한글을 주는데 요청만 enum 이름을 요구하면 화면이 변환표를 따로 들어야 하고,
     * 그 표가 언젠가 갈린다 — 거래분류·권한키에서 같은 일을 겪었다").
     *
     * <p>★규칙은 {@code from()} <b>하나만</b> 쓴다. 여기서 매핑표를 다시 만들지 않는다.
     * 모르는 값은 그대로 400 이다(오타가 조용히 통과하면 안 된다).
     */
    @Override
    public void addFormatters(org.springframework.format.FormatterRegistry registry) {
        registry.addConverter(String.class, com.daesung.sales.product.entity.MajorCategory.class,
                com.daesung.sales.product.entity.MajorCategory::from);
    }

    /**
     * 허용 오리진. <b>정확히 일치</b>하는 값만 쓴다(로컬 개발 서버 등).
     * 운영/스테이징 도메인은 배포 환경변수로 덮어쓴다 —
     * {@code DAESUNG_CORS_ALLOWED-ORIGINS=https://a.com,https://b.com}
     */
    @Value("${daesung.cors.allowed-origins:"
            + "https://sales.d-dlab.link,http://localhost:3000,http://localhost:5173}")
    private List<String> allowedOrigins;

    /**
     * 허용 오리진 <b>패턴</b>(와일드카드 가능). Amplify처럼 브랜치마다 서브도메인이 생기는 호스팅용 —
     * {@code https://develop.d1z41nzmh4zrdi.amplifyapp.com} 하나만 넣어 두면
     * 브랜치를 딸 때마다 서버를 다시 배포해야 한다.
     *
     * <p>★와일드카드는 <b>한 단계 서브도메인만</b> 매칭한다. 앱 ID(`d1z41nzmh4zrdi`)를 고정해 두었으므로
     * 남의 amplifyapp.com 사이트가 우리 API를 부를 수는 없다. `https://*.amplifyapp.com`처럼
     * 넓게 열면 그게 가능해진다 — 자격증명을 함께 보내는 설정이라 더더욱 좁혀 둔다.
     */
    @Value("${daesung.cors.allowed-origin-patterns:https://*.d1z41nzmh4zrdi.amplifyapp.com}")
    private List<String> allowedOriginPatterns;

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // 우리 패키지 컨트롤러에만 '/api/v1' prefix (springdoc 등 라이브러리 컨트롤러 제외)
        configurer.addPathPrefix("/api/v1", HandlerTypePredicate.forBasePackage("com.daesung.sales"));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // ★allowCredentials=true 이면 "*"는 브라우저가 거부한다. 그래서 목록/패턴으로 명시한다.
        //   refresh 토큰이 httpOnly 쿠키라 자격증명 전송이 필수다(끄면 로그인 유지가 안 된다).
        config.setAllowedOrigins(clean(allowedOrigins));
        config.setAllowedOriginPatterns(clean(allowedOriginPatterns));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        // 파일 다운로드(엑셀)에서 파일명을 읽으려면 이 헤더가 브라우저에 보여야 한다.
        config.setExposedHeaders(List.of("Content-Disposition"));
        // preflight 캐시 — 목록 화면이 요청을 쏟아낼 때 OPTIONS가 매번 붙지 않게.
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** 빈 값·공백 제거. 환경변수를 "a, b" 처럼 넘겨도 동작하게. */
    private static List<String> clean(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    out.add(v.trim());
                }
            }
        }
        return out;
    }
}
