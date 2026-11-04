package com.daesung.sales.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 특약점 노출 판단 순서 — <b>11/04 클라이언트 회신 §1·§2</b>(10/31 §1-1 대체).
 *
 * <pre>
 *   (1) 도서 Web게시 N            -&gt; 비노출 (단락)
 *   (2) 도서가 '지정 거래처 전용'  -&gt; 열기 있으면 노출 / 없으면 비노출 (대분류 매핑 무시)
 *   (3) 열기 지정 있음             -&gt; 노출
 *   (4) 거래처x대분류 매핑         -&gt; 그 행의 web_visible
 * </pre>
 *
 * <p>★<b>10/31 판에서 뒤집힌 것 둘</b> — 이 테스트가 그 전환을 고정한다.
 * <ul>
 *   <li><b>'닫기' 예외가 없어졌다.</b> 지정은 '열기' 하나뿐이고 닫는 것은 지정을 지우는 것이다.
 *       "그 외 전부 비노출"은 (2) 도서 단위 스위치가 맡는다 — 10/31 구조로는 거래처가
 *       새로 생길 때마다 닫기 행을 또 깔아야 했고, 안 깔면 기본 노출이라 의도와 반대였다.</li>
 *   <li><b>설정 축이 뒤집혔다.</b> [거래처관리] 거래처×도서 수천 권 → [도서관리] 도서 1권×거래처.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("특약점 노출 판단 순서(11/04 회신 §1·§2)")
class PartnerProductVisibilityIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PV" + (System.nanoTime() % 1_000_000L);

    private String partnerCode;
    private Long partnerId;
    /** 일괄 적용 테스트용 두 번째 거래처. */
    private String otherCode;
    private Long otherId;

    @BeforeAll
    void seed() {
        token();
        partnerCode = "PVP" + SFX;
        partnerId = createId("/masters/clients",
                Map.of("code", partnerCode, "name", "노출거래처", "type", "NORMAL"));
        otherCode = "PVQ" + SFX;
        otherId = createId("/masters/clients",
                Map.of("code", otherCode, "name", "노출거래처2", "type", "NORMAL"));
    }

    private Long book(String code, boolean webVisible, boolean restricted) {
        return createId("/masters/products", Map.of("code", code, "name", code + "도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", "A2092A", "catName", "노출분류",
                "webVisible", webVisible, "restrictedVisible", restricted));
    }

    /** 거래처×대분류 매핑((4)) — 이 테스트의 바탕값. */
    private void mapping(Long pid, boolean webVisible) {
        put("/masters/partner-supply-rates/" + pid + "/TEXTBOOK",
                Map.of("supplyRate", 70, "webVisible", webVisible, "useYn", true));
    }

    /** 열기 지정/해제 — 11/04 §2 의 새 경로(도서 축, 거래처 다중선택). */
    private JsonNode open(Long bookId, boolean open, Long... partnerIds) {
        return put("/masters/products/" + bookId + "/partner-visibility",
                Map.of("partnerIds", List.of(partnerIds), "open", open, "reason", "테스트"));
    }

    /** ★정답지는 사이트가 실제로 받는 목록이다 — 설정 화면이 아니라 이쪽을 기준으로 본다. */
    private boolean visibleToDealer(String bookCode, String pCode) {
        JsonNode r = getAsDealerSite("/dealer-site/products?partnerCode=" + pCode, DEALER_SITE_KEY);
        for (JsonNode p : r.path("data")) {
            if (bookCode.equals(p.path("productCode").asText())) {
                return true;
            }
        }
        return false;
    }

    private boolean visibleToDealer(String bookCode) {
        return visibleToDealer(bookCode, partnerCode);
    }

    /** 설정 화면 한 줄(도서 1권 × 그 거래처). */
    private JsonNode settingRow(Long bookId, String pCode) {
        JsonNode d = data(get("/masters/products/" + bookId
                + "/partner-visibility?keyword=" + pCode));
        return d.path("content").get(0);
    }

    @Test
    @DisplayName("(4) 지정 없음 — 매핑이 열려 있으면 노출, 닫혀 있으면 비노출")
    void 매핑만() {
        String opened = "PVA" + SFX;
        String closed = "PVB" + SFX;
        book(opened, true, false);
        book(closed, true, false);

        mapping(partnerId, true);
        assertThat(visibleToDealer(opened)).as("매핑 열림 + 지정 없음 → 노출").isTrue();

        mapping(partnerId, false);
        assertThat(visibleToDealer(closed)).as("매핑 닫힘 + 지정 없음 → 비노출").isFalse();
    }

    @Test
    @DisplayName("★(3) 열기 지정 — 매핑이 닫혀 있어도 노출된다")
    void 열기가_매핑을_이긴다() {
        mapping(partnerId, false);
        String code = "PVC" + SFX;
        Long id = book(code, true, false);
        assertThat(visibleToDealer(code)).as("지정 전에는 매핑(닫힘)을 따른다").isFalse();

        open(id, true, partnerId);

        assertThat(visibleToDealer(code)).as("(3) 열기 지정이 (4) 매핑(닫힘)을 이긴다").isTrue();
    }

    @Test
    @DisplayName("★★(2) 지정 거래처 전용 — 매핑이 열려 있어도 열기 없는 곳은 비노출")
    void 지정거래처전용_매핑을_무시한다() {
        mapping(partnerId, true);    // 매핑은 열려 있다
        mapping(otherId, true);
        String code = "PVD" + SFX;
        Long id = book(code, true, true);   // ★지정 거래처 전용

        assertThat(visibleToDealer(code))
                .as("(2) 전용인데 열기가 없다 → 매핑이 열려 있어도 비노출").isFalse();

        open(id, true, partnerId);

        assertThat(visibleToDealer(code)).as("열기를 준 거래처엔 노출").isTrue();
        assertThat(visibleToDealer(code, otherCode))
                .as("★다른 거래처는 매핑이 열려 있어도 비노출 — 이게 '지정 거래처에만'이다").isFalse();
    }

    @Test
    @DisplayName("★(1) 도서 Web게시 N — 열기를 줘도 비노출(모든 규칙보다 우선)")
    void 웹게시N이_최우선() {
        mapping(partnerId, true);
        String code = "PVE" + SFX;
        Long id = book(code, false, false);   // 도서 자체가 Web게시 N

        open(id, true, partnerId);

        assertThat(visibleToDealer(code)).as("(1) Web게시 N → 무엇을 지정해도 비노출").isFalse();
        assertThat(settingRow(id, partnerCode).path("productWebVisible").asBoolean())
                .as("화면이 경고를 띄울 수 있게 알려준다").isFalse();
    }

    @Test
    @DisplayName("★★'닫기'는 없다 — 해제는 지정 삭제이고, 매핑이 열려 있으면 다시 노출된다")
    void 해제는_강제비노출이_아니다() {
        mapping(partnerId, true);   // 매핑 열림
        String code = "PVF" + SFX;
        Long id = book(code, true, false);
        open(id, true, partnerId);
        assertThat(visibleToDealer(code)).isTrue();

        JsonNode r = open(id, false, partnerId);   // 해제
        assertThat(data(r).path("closed").asInt()).as("%s", r).isEqualTo(1);

        // ‼️10/31 판이라면 '닫기' 예외가 남아 비노출이었다. 지금은 지정이 사라지고 (4)로 떨어진다.
        assertThat(visibleToDealer(code))
                .as("해제 = 지정 삭제 → 매핑(열림)을 그대로 따라 노출").isTrue();
        assertThat(settingRow(id, partnerCode).path("open").asBoolean())
                .as("체크는 풀렸다").isFalse();
    }

    @Test
    @DisplayName("★선택 거래처 일괄 적용 — 두 곳을 한 번에, 이미 그 상태면 unchanged")
    void 일괄적용() {
        mapping(partnerId, false);
        mapping(otherId, false);
        String code = "PVG" + SFX;
        Long id = book(code, true, true);   // 전용이라 열기 없으면 둘 다 비노출

        JsonNode r1 = open(id, true, partnerId, otherId);
        assertThat(data(r1).path("opened").asInt()).as("%s", r1).isEqualTo(2);
        assertThat(data(r1).path("requested").asInt()).isEqualTo(2);
        assertThat(visibleToDealer(code)).isTrue();
        assertThat(visibleToDealer(code, otherCode)).isTrue();

        // 같은 요청을 또 보내면 아무것도 안 바뀐다(이력을 부풀리지 않는다).
        JsonNode r2 = open(id, true, partnerId, otherId);
        assertThat(data(r2).path("unchanged").asInt()).as("%s", r2).isEqualTo(2);
        assertThat(data(r2).path("opened").asInt()).isZero();
    }

    @Test
    @DisplayName("★★설정 화면 판정과 사이트 목록이 같다 — 규칙이 한 곳에만 있다")
    void 설정화면과_사이트목록_일치() {
        mapping(partnerId, true);
        String a = "PVH" + SFX;     // (4) 매핑으로 노출
        String b = "PVI" + SFX;     // (2) 전용 + 열기 없음 → 비노출
        String c = "PVJ" + SFX;     // (1) Web게시 N → 비노출
        Long ia = book(a, true, false);
        Long ib = book(b, true, true);
        Long ic = book(c, false, false);

        for (var e : Map.of(ia, a, ib, b, ic, c).entrySet()) {
            boolean fromSetting = settingRow(e.getKey(), partnerCode).path("visible").asBoolean();
            boolean fromSite = visibleToDealer(e.getValue());
            assertThat(fromSetting)
                    .as("도서 %s — 설정 화면(%s)과 사이트 목록(%s)이 갈리면 담당자가 "
                            + "'화면엔 보이는데 사이트엔 안 뜬다'를 겪는다", e.getValue(), fromSetting, fromSite)
                    .isEqualTo(fromSite);
        }
    }

    @Test
    @DisplayName("★★분류 단위 일괄 적용 — 같은 분류 도서 여러 권 × 거래처 여러 곳을 한 번에(11/04 §2)")
    void 분류단위_일괄적용() {
        mapping(partnerId, false);
        mapping(otherId, false);
        // 분류코드 형식: [영문1][연도4][영문·숫자1~3] — 뒤 2자로 실행마다 유니크하게 만든다.
        String catCode = "B2097" + (char) ('A' + (int) (System.nanoTime() % 26))
                + (int) (System.nanoTime() / 7 % 10);
        String a = "PVM" + SFX;
        String b = "PVN" + SFX;
        Long ia = createId("/masters/products", Map.of("code", a, "name", a + "도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", catCode, "catName", "분류일괄테스트",
                "webVisible", true, "restrictedVisible", true));
        Long ib = createId("/masters/products", Map.of("code", b, "name", b + "도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", catCode, "catName", "분류일괄테스트",
                "webVisible", true, "restrictedVisible", true));

        // 매핑은 닫혀 있고 둘 다 지정전용이라, 분류 일괄 열기 전엔 둘 다 비노출이어야 한다.
        assertThat(visibleToDealer(a)).isFalse();
        assertThat(visibleToDealer(b)).isFalse();

        JsonNode r = put("/masters/products/partner-visibility/bulk",
                Map.of("catCode", catCode, "partnerIds", List.of(partnerId, otherId),
                        "open", true, "reason", "분류 일괄 테스트"));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("productCount").asInt()).as("그 분류 도서 2권").isEqualTo(2);
        assertThat(data(r).path("partnerCount").asInt()).isEqualTo(2);
        assertThat(data(r).path("opened").asInt()).as("2권 x 2거래처 = 4조합").isEqualTo(4);

        assertThat(visibleToDealer(a)).as("분류 일괄로 열렸다").isTrue();
        assertThat(visibleToDealer(a, otherCode)).isTrue();
        assertThat(visibleToDealer(b)).isTrue();
        assertThat(visibleToDealer(b, otherCode)).isTrue();

        // 단건 API와 같은 규칙을 쓰는지 — 같은 요청을 또 보내면 전부 unchanged.
        JsonNode r2 = put("/masters/products/partner-visibility/bulk",
                Map.of("catCode", catCode, "partnerIds", List.of(partnerId, otherId), "open", true));
        assertThat(data(r2).path("unchanged").asInt()).as("%s", r2).isEqualTo(4);
    }

    @Test
    @DisplayName("일괄 플래그로도 '지정 거래처 전용'을 켤 수 있다(도서 Y/N 일괄 경로)")
    void 일괄플래그로_전용켜기() {
        mapping(partnerId, true);
        String code = "PVK" + SFX;
        Long id = book(code, true, false);
        assertThat(visibleToDealer(code)).as("처음엔 매핑으로 노출").isTrue();

        JsonNode r = put("/masters/products/flags",
                Map.of("productIds", List.of(id), "restrictedVisible", true));
        assertThat(data(r).path("changed").asInt()).as("%s", r).isEqualTo(1);

        assertThat(visibleToDealer(code))
                .as("전용으로 바뀌었고 열기가 없으니 비노출").isFalse();
    }

    @Test
    @DisplayName("목록(GET)과 접수 거부(POST) 판정이 일치한다 — 같은 쿼리를 쓴다")
    void 목록과_접수판정_일치() {
        mapping(partnerId, false);
        String code = "PVL" + SFX;
        Long id = book(code, true, false);
        open(id, true, partnerId);

        assertThat(visibleToDealer(code)).as("사이트 목록에 노출").isTrue();

        Map<String, Object> req = new java.util.HashMap<>();
        req.put("partnerCode", partnerCode);
        req.put("items", List.of(Map.of("productCode", code, "qty", 1)));
        JsonNode r = postAsDealerSite("/dealer-site/orders", req, DEALER_SITE_KEY);
        if (!r.path("success").asBoolean()) {
            assertThat(r.path("error").path("code").asText())
                    .as("노출된 도서라 PRODUCT_NOT_VISIBLE 이면 안 된다 — 다른 이유(재고 등)여야")
                    .isNotEqualTo("PRODUCT_NOT_VISIBLE");
        }
    }
}
