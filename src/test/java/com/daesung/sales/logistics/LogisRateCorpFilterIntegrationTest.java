package com.daesung.sales.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 물류비용등록 — <b>연구소 상품만 보인다</b>(발주처 2026-11-23 3번).
 *
 * <p>"상품 목록(상품년도 2026)에 리브커넥스 법인 상품(감각 2과목·대성학원 평가고사·
 * 국수잔치 등)이 보인다 — <b>연구소만 남게</b>."
 *
 * <p>★판정은 새로 만들 것이 없었다 — 법인 축이 <b>이미 데이터에 있다</b>(V125·V126).
 * 상품은 코드 접두어로 채워져 있고(`DL-` 연구소 / `DT-` 리브커넥스), 그 근거는
 * 레거시 소스가 직접 적어 둔 「대성교재 법인 = 리브커넥스(주)」다
 * ({@code 외상매출장조회.vb:58} + {@code Common.vb:23}).
 *
 * <p>‼️접두어가 없는 이관분 4종은 법인이 <b>비어 있다</b> — <b>연구소로 본다</b>.
 * 비었다고 빼면 그 넷이 어느 화면에도 안 나와 사라진다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("물류비용등록 — 법인 필터(2026-11-23 3번)")
class LogisRateCorpFilterIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "CF" + (System.nanoTime() % 1_000_000L);
    private static final String CAT = "C2079A" + (System.nanoTime() % 90 + 10);

    private String dlCode;      // 연구소
    private String dtCode;      // 리브커넥스
    private String noneCode;    // 법인 비어 있음(접두어 없는 이관분과 같은 모양)

    @Autowired
    private EntityManager em;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    @BeforeAll
    void seed() {
        token();
        dlCode = "DL-" + SFX;
        dtCode = "DT-" + SFX;
        noneCode = "NC-" + SFX;
        Long dl = book(dlCode, "연구소 상품");
        Long dt = book(dtCode, "리브커넥스 상품");
        Long none = book(noneCode, "법인없는 상품");

        // ‼️등록 API 로는 법인을 정할 수 없다 — @PrePersist 가 **전부 연구소**로 채운다.
        //   이관분과 같은 모양을 만들려면 값을 직접 바꿔야 한다.
        setCorp(dt, "DSTXTBOOK");
        setCorp(none, null);
    }

    private Long book(String code, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", CAT);
        b.put("catName", "법인필터검증");
        b.put("salesDivision", "교재");
        return createId("/masters/products", b);
    }

    /** 법인 값을 직접 바꾼다 — 같은 클래스의 @Transactional 은 프록시를 안 거쳐 안 먹는다. */
    private void setCorp(Long productId, String corp) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(status -> {
                    em.createQuery("update Product p set p.corp = :c where p.id = :id")
                            .setParameter("c", (corp == null) ? null
                                    : com.daesung.sales.common.corp.Corp.valueOf(corp))
                            .setParameter("id", productId)
                            .executeUpdate();
                    em.clear();
                });
    }

    @Test
    @DisplayName("★★기본 조회는 **연구소만** — 리브커넥스 상품이 빠진다")
    void 기본은_연구소만() {
        var codes = treeCodes("");
        assertThat(codes).as("연구소 상품은 있다").contains(dlCode);
        assertThat(codes)
                .as("‼️리브커넥스 상품이 보이면 발주처 3번이 안 고쳐진 것이다: %s", codes)
                .doesNotContain(dtCode);
    }

    @Test
    @DisplayName("‼️법인이 **비어 있는** 상품은 연구소로 본다 — 빼면 그 상품이 사라진다")
    void 법인_없으면_연구소로() {
        assertThat(treeCodes(""))
                .as("‼️없으면 접두어 없는 이관분 4종이 어느 화면에도 안 나온다")
                .contains(noneCode);
    }

    @Test
    @DisplayName("리브커넥스만 / 전 법인도 볼 수 있다")
    void 법인_선택() {
        var lib = treeCodes("&corp=DSTXTBOOK");
        assertThat(lib).contains(dtCode);
        assertThat(lib).doesNotContain(dlCode, noneCode);

        var all = treeCodes("&allCorp=true");
        assertThat(all).contains(dlCode, dtCode, noneCode);
    }

    @Test
    @DisplayName("목록(단가 등록분)도 같은 규칙 — 트리와 조회가 갈리면 담당자가 둘 다 못 믿는다")
    void 목록도_같은_규칙() {
        // 단가를 등록해야 목록에 뜬다. 리브 상품에만 등록해 둔다.
        Long dtId = productId(dtCode);
        assertThat(post("/logistics-costs/products", Map.of(
                "productId", dtId, "packType", 1, "paper", 50, "omr", 50,
                "etc", 50, "label", 50, "basic", 10, "trade", 10))
                .path("success").asBoolean()).isTrue();

        assertThat(listCodes(""))
                .as("기본(연구소)에는 리브 상품이 없어야 한다")
                .doesNotContain(dtCode);
        assertThat(listCodes("&corp=DSTXTBOOK")).contains(dtCode);
    }

    private long productId(String code) {
        for (JsonNode n : data(get("/masters/products?keyword=" + code + "&size=10"))
                .path("content")) {
            if (code.equals(n.path("code").asText())) {
                return n.path("id").asLong();
            }
        }
        throw new AssertionError("도서 없음: " + code);
    }

    private java.util.List<String> treeCodes(String extra) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode year : data(get("/logistics-costs/products/tree?keyword=" + SFX + extra))) {
            for (JsonNode cat : year.path("categories")) {
                cat.path("products").forEach(p -> out.add(p.path("code").asText()));
            }
        }
        return out;
    }

    private java.util.List<String> listCodes(String extra) {
        java.util.List<String> out = new java.util.ArrayList<>();
        data(get("/logistics-costs/products?keyword=" + SFX + extra))
                .forEach(n -> out.add(n.path("productCode").asText()));
        return out;
    }
}
