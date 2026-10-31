package com.daesung.sales.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합테스트 공용 베이스. 실 MySQL 8.4 + Redis(Testcontainers) 위에서 앱 전체(HTTP→서비스→쿼리→DB)를 관통.
 * ★이미지 태그는 운영 RDS 버전(8.4.x)에 맞춘다 — 8.0으로 검증하면 8.4에서 달라진 동작이 검증 밖으로 샌다.
 * Flyway가 스키마 생성, 인증은 부트스트랩→로그인으로 실제 토큰 사용. DSRE는 off(복제본 불필요).
 * 컨테이너는 static 블록에서 직접 start(싱글톤 패턴) — @DynamicPropertySource 해석 전 기동 보장, JVM 내 재사용.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestSupport {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("sales").withUsername("sales").withPassword("sales")
            // ‼️특약점 PDF 보관함이 파일을 BLOB 으로 넣는다. 기본 1MB 로는 2.4MB PDF 가
            //   "Packet for query is too large" 로 **500** 이 난다(프론트 B-6 의 진짜 원인).
            //   운영 DB 에도 같은 설정이 필요하다 — 여기만 올리면 테스트만 통과하고 운영은 깨진다.
            .withCommand("--max-allowed-packet=67108864");

    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("daesung.dsre.enabled", () -> "false");
        r.add("daesung.supplier.name", () -> "(주)대성테스트");
        r.add("daesung.supplier.biz-no", () -> "111-11-11111");
        r.add("daesung.supplier.tel", () -> "02-880-2301");
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper om;

    private String cachedToken;

    /** 부트스트랩 admin 후 로그인 → access 토큰(클래스 내 캐시). */
    protected String token() {
        if (cachedToken == null) {
            // 최초 관리자 생성(이미 있으면 400이나 무시하고 로그인 진행)
            exchange(HttpMethod.POST, "/auth/bootstrap-admin",
                    Map.of("username", "admin", "password", "Admin1234!", "name", "관리자", "role", "ADMIN"), false);
            JsonNode login = exchange(HttpMethod.POST, "/auth/login",
                    Map.of("username", "admin", "password", "Admin1234!"), false);
            cachedToken = login.path("data").path("accessToken").asText();
        }
        return cachedToken;
    }

    /**
     * <b>/api/v1 접두어 없이</b> 그대로 GET(액추에이터 등). 인증도 붙이지 않는다 —
     * 배포 스크립트가 토큰 없이 부르는 경로를 같은 조건으로 확인하기 위해서다.
     */
    protected JsonNode getRaw(String path) {
        org.springframework.http.ResponseEntity<String> resp =
                rest.getForEntity(path, String.class);
        try {
            return om.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException("응답 파싱 실패: " + resp.getBody(), e);
        }
    }

    /** 인증 GET → ApiResponse JsonNode 반환. */
    protected JsonNode get(String path) {
        return exchange(HttpMethod.GET, path, null, true);
    }

    /** 인증 POST → ApiResponse JsonNode 반환. */
    protected JsonNode post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body, true);
    }

    /** 인증 PUT → ApiResponse JsonNode 반환. */
    protected JsonNode put(String path, Object body) {
        return exchange(HttpMethod.PUT, path, body, true);
    }

    /** 인증 DELETE → ApiResponse JsonNode 반환. */
    /** PATCH(부분 수정). 매출·폐기 수정이 이 메서드다. */
    protected JsonNode patch(String path, Object body) {
        return exchange(HttpMethod.PATCH, path, body, true);
    }

    protected JsonNode del(String path) {
        return exchange(HttpMethod.DELETE, path, null, true);
    }

    /** 본문 있는 DELETE(삭제 사유 등). */
    protected JsonNode del(String path, Object body) {
        return exchange(HttpMethod.DELETE, path, body, true);
    }

    /** data 노드만 반환(성공 응답 가정). */
    protected JsonNode data(JsonNode apiResponse) {
        return apiResponse.path("data");
    }

    /** POST 후 성공 검증하고 data.id 반환(실패 시 응답 전체를 담아 예외). */
    protected long createId(String path, Object body) {
        JsonNode r = post(path, body);
        if (!r.path("success").asBoolean()) {
            throw new AssertionError("생성 실패 " + path + " → " + r);
        }
        return r.path("data").path("id").asLong();
    }

    /** 헤더 접근이 필요한 원시 호출(Set-Cookie/Cookie 검증용). bearer·cookie는 선택. */
    protected ResponseEntity<String> exchangeRaw(HttpMethod method, String path, Object body,
                                                 String bearer, String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            h.setBearerAuth(bearer);
        }
        if (cookie != null) {
            h.add(HttpHeaders.COOKIE, cookie);
        }
        String json;
        try {
            json = (body == null) ? null : om.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return rest.exchange("/api/v1" + path, method, new HttpEntity<>(json, h), String.class);
    }

    /** 인증 GET → 바이너리(파일 다운로드) 응답. */
    protected ResponseEntity<byte[]> getBytes(String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token());
        return rest.exchange("/api/v1" + path, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
    }

    /**
     * 인증 multipart 업로드(엑셀 업로드 테스트용).
     * 파일명을 넘기는 이유: 서버가 확장자를 보고 파서를 고를 수 있어서 실제 요청과 같은 모양이어야 한다.
     */
    protected JsonNode multipart(String path, byte[] bytes, String filename) {
        return multipart(path, bytes, filename, "file");
    }

    /** 파트 이름이 {@code file} 이 아닌 업로드(예: 다건을 받는 {@code files}). */
    protected JsonNode multipart(String path, byte[] bytes, String filename, String partName) {
        return multipartMany(path, partName, List.of(new FilePart(filename, bytes)));
    }

    /** 다건 업로드 한 건. 하위 테스트가 직접 만들어 넘기므로 public 이다. */
    public record FilePart(String filename, byte[] bytes) {
    }

    /** 파트 이름 {@code files} 로 여러 개를 한 번에 올린다. */
    protected JsonNode multipartMany(String path, List<FilePart> parts) {
        return multipartMany(path, "files", parts);
    }

    protected JsonNode multipartMany(String path, String partName, List<FilePart> parts) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(token());

        org.springframework.util.MultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        for (FilePart p : parts) {
            form.add(partName, new org.springframework.core.io.ByteArrayResource(p.bytes()) {
                @Override
                public String getFilename() {
                    return p.filename();
                }
            });
        }

        ResponseEntity<String> resp = rest.exchange(
                "/api/v1" + path, HttpMethod.POST, new HttpEntity<>(form, h), String.class);
        try {
            return om.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException("응답 파싱 실패: " + resp.getBody(), e);
        }
    }

    private JsonNode exchange(HttpMethod method, String path, Object body, boolean auth) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (auth) {
            h.setBearerAuth(token());
        }
        String json;
        try {
            json = (body == null) ? null : om.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ResponseEntity<String> resp = rest.exchange(
                "/api/v1" + path, method, new HttpEntity<>(json, h), String.class);
        try {
            return om.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException("응답 파싱 실패: " + resp.getBody(), e);
        }
    }
}
