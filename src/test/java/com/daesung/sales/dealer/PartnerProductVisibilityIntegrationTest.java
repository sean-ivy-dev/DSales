package com.daesung.sales.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 거래처×도서 노출 예외(10/31 클라이언트 회신 §1 — 10/26 §3·§9-1 대체).
 *
 * <pre>
 *   ① p.webVisible = N                     → 비노출 (예외보다 우선)
 *   ② 거래처×도서 예외 있음                  → 그 행의 visible (열기/닫기)
 *   ③ 거래처×대분류 매핑(use_yn=Y·미삭제)    → 그 행의 web_visible
 *   ④ 매핑 없음                              → 비노출
 * </pre>
 *
 * <p>★<b>이 규칙을 처음엔 놓쳤다</b> — 10/26 명세 §3만 보고 ①③④로 구현했는데, 10/31 회신이
 * §3을 통째로 대체하며 ②를 추가한 걸 늦게 발견했다(2026-11-03). `GET /dealer-site/products`
 * 가 한동안 확정 규칙과 다르게 동작했다 — 이 테스트가 그 회귀를 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("거래처×도서 노출 예외(10/31 회신 §1)")
class PartnerProductVisibilityIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PV" + (System.nanoTime() % 1_000_000L);

    private String partnerCode;
    private Long partnerId;

    @BeforeAll
    void seed() {
        token();
        partnerCode = "PVP" + SFX;
        partnerId = createId("/masters/clients",
                Map.of("code", partnerCode, "name", "노출예외거래처", "type", "NORMAL"));
    }

    private Long book(String code, boolean webVisible) {
        return createId("/masters/products", Map.of("code", code, "name", code + "도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", "A2092A", "catName", "노출예외분류",
                "webVisible", webVisible));
    }

    private void mapping(boolean webVisible) {
        put("/masters/partner-supply-rates/" + partnerId + "/TEXTBOOK",
                Map.of("supplyRate", 70, "webVisible", webVisible, "useYn", true));
    }

    private boolean visibleToDealer(String bookCode) {
        JsonNode r = getAsDealerSite("/dealer-site/products?partnerCode=" + partnerCode, DEALER_SITE_KEY);
        for (JsonNode p : r.path("data")) {
            if (bookCode.equals(p.path("productCode").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("③④만 있을 때(예외 없음) — 매핑이 열려 있으면 노출, 없으면 비노출")
    void 예외없음_매핑기본값() {
        String open = "PVA" + SFX;
        String closed = "PVB" + SFX;
        book(open, true);
        book(closed, true);
        mapping(true);   // 매핑 열림 — ③
        assertThat(visibleToDealer(open)).as("매핑 열림 + 예외없음 → 노출").isTrue();

        mapping(false);  // 매핑 닫힘
        assertThat(visibleToDealer(closed)).as("매핑 닫힘 + 예외없음 → 비노출").isFalse();
    }

    @Test
    @DisplayName("★②'열기' 예외 — 매핑이 닫혀 있거나 없어도 노출된다")
    void 예외_열기가_매핑을_이긴다() {
        mapping(false);   // ③ 매핑을 일단 닫아 둔다
        String code = "PVC" + SFX;
        Long id = book(code, true);
        assertThat(visibleToDealer(code)).as("예외 전에는 매핑(닫힘)을 따른다").isFalse();

        put("/masters/clients/" + partnerId + "/product-visibility/" + id,
                Map.of("visible", true, "reason", "테스트 열기"));

        assertThat(visibleToDealer(code)).as("② 열기 예외가 ③ 매핑(닫힘)을 이긴다").isTrue();
    }

    @Test
    @DisplayName("★②'닫기' 예외 — 매핑이 열려 있어도 비노출된다")
    void 예외_닫기가_매핑을_이긴다() {
        mapping(true);   // ③ 매핑은 열려 있다
        String code = "PVD" + SFX;
        Long id = book(code, true);
        assertThat(visibleToDealer(code)).as("예외 전에는 매핑(열림)을 따른다").isTrue();

        put("/masters/clients/" + partnerId + "/product-visibility/" + id,
                Map.of("visible", false, "reason", "테스트 닫기"));

        assertThat(visibleToDealer(code)).as("② 닫기 예외가 ③ 매핑(열림)을 이긴다").isFalse();
    }

    @Test
    @DisplayName("★①도서 자체 Web게시 N — 열기 예외를 걸어도 비노출(예외보다 우선)")
    void 도서웹게시N이_열기예외보다_우선() {
        String code = "PVE" + SFX;
        Long id = book(code, false);   // 도서 자체가 Web게시 N
        mapping(true);

        put("/masters/clients/" + partnerId + "/product-visibility/" + id,
                Map.of("visible", true, "reason", "그래도 열어본다"));

        assertThat(visibleToDealer(code))
                .as("① 도서 Web게시 N 이 ② 예외(열기)보다 우선 — 그래도 비노출").isFalse();
    }

    @Test
    @DisplayName("★★체크를 기본값과 같게 돌리면 예외 행이 지워진다(10/31 §1-4)")
    void 기본값과_같아지면_예외삭제() {
        mapping(true);   // 기본값(③) = 노출
        String code = "PVF" + SFX;
        Long id = book(code, true);

        // 일단 닫기로 예외를 만든다.
        put("/masters/clients/" + partnerId + "/product-visibility/" + id,
                Map.of("visible", false));
        JsonNode list1 = data(get("/masters/clients/" + partnerId
                + "/product-visibility?keyword=" + code));
        JsonNode row1 = list1.path("content").get(0);
        assertThat(row1.path("exception").asBoolean()).as("예외 생성됨").isTrue();
        assertThat(row1.path("visible").asBoolean()).isFalse();

        // 다시 기본값(열기=true)과 같게 되돌린다 — 예외가 지워져야 한다.
        put("/masters/clients/" + partnerId + "/product-visibility/" + id,
                Map.of("visible", true));
        JsonNode list2 = data(get("/masters/clients/" + partnerId
                + "/product-visibility?keyword=" + code));
        JsonNode row2 = list2.path("content").get(0);
        assertThat(row2.path("exception").asBoolean())
                .as("기본값과 같아져서 예외 행이 지워졌다").isFalse();
        assertThat(row2.path("visible").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("'기본값으로 되돌리기' — DELETE 로 예외를 바로 지운다")
    void 되돌리기_버튼() {
        mapping(true);
        String code = "PVG" + SFX;
        Long id = book(code, true);
        put("/masters/clients/" + partnerId + "/product-visibility/" + id,
                Map.of("visible", false));
        assertThat(visibleToDealer(code)).isFalse();

        JsonNode r = del("/masters/clients/" + partnerId + "/product-visibility/" + id);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        assertThat(visibleToDealer(code)).as("되돌리기 후 매핑(열림) 그대로").isTrue();
    }

    @Test
    @DisplayName("목록(GET)과 접수 거부(POST) 판정이 일치한다 — 같은 쿼리를 쓴다")
    void 목록과_접수판정_일치() {
        mapping(false);
        String code = "PVH" + SFX;
        Long id = book(code, true);
        put("/masters/clients/" + partnerId + "/product-visibility/" + id,
                Map.of("visible", true));   // 열기 예외

        assertThat(visibleToDealer(code)).as("사이트 목록에 노출").isTrue();

        // 노출된 도서이니 접수도 통과해야 한다(재고 없어 500은 날 수 있지만 PRODUCT_NOT_VISIBLE은 아니어야).
        Map<String, Object> req = new java.util.HashMap<>();
        req.put("partnerCode", partnerCode);
        req.put("items", java.util.List.of(Map.of("productCode", code, "qty", 1)));
        JsonNode r = postAsDealerSite("/dealer-site/orders", req, DEALER_SITE_KEY);
        if (!r.path("success").asBoolean()) {
            assertThat(r.path("error").path("code").asText())
                    .as("노출된 도서라 PRODUCT_NOT_VISIBLE 이면 안 된다 — 다른 이유(재고 등)여야")
                    .isNotEqualTo("PRODUCT_NOT_VISIBLE");
        }
    }
}
