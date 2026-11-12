package com.daesung.sales.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 특약점 신청 화면 <b>노출 도서</b>(10/26 §3 + 특약점팀 전달 2026-11-03 요청 1·3·5번).
 *
 * <pre>
 *   ① 도서 Web게시 = Y
 *   ② 거래처별 매핑(거래처 × 대분류)의 Web게시 = Y
 *   ③ 그 매핑의 사용여부 = Y
 *   ④ 매핑이 없으면 비노출
 * </pre>
 *
 * <p>★<b>목록과 접수·수정 검증이 같은 판정</b>이어야 한다 — 따로 두면 "목록엔 없는데
 * 주문은 되는" 구멍이 생긴다. 마지막 테스트가 그 일치를 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("특약점 노출 도서(10/26 §3)")
class DealerProductVisibilityIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DV" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2089-04-04";

    private String partnerCode;
    private Long partnerId;
    private String visibleCode;      // 노출되는 도서
    private String hiddenBookCode;   // 도서 Web게시 N
    private Long wh;

    @BeforeAll
    void seed() {
        token();
        partnerCode = "DVP" + SFX;
        partnerId = createId("/masters/clients", Map.of(
                "code", partnerCode, "name", "노출거래처", "type", "NORMAL",
                "bossName", "김대표", "tel1", "02-555-6666", "email1", "dv@example.com"));
        // ★특약점 접수는 설정(daesung.dealer-site.warehouse-code)이 가리키는 창고로 들어간다 —
        //   다른 코드로 만들면 접수가 "창고 설정이 가리키는 창고가 없습니다"로 막힌다.
        wh = findOrCreateDealerWarehouse();

        visibleCode = "DVB1" + SFX;
        createId("/masters/products", Map.of("code", visibleCode, "name", "노출도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", "A2089A", "catName", "노출분류",
                "webVisible", true));

        hiddenBookCode = "DVB2" + SFX;
        createId("/masters/products", Map.of("code", hiddenBookCode, "name", "비노출도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재", "catCode", "A2089A", "catName", "노출분류",
                "webVisible", false));

        // 거래처 × 대분류(교재=TEXTBOOK) 매핑 — Web게시 Y, 사용 Y
        put("/masters/partner-supply-rates/" + partnerId + "/TEXTBOOK",
                Map.of("supplyRate", 70, "webVisible", true, "useYn", true));
    }

    private Long findOrCreateDealerWarehouse() {
        for (JsonNode w : data(get("/masters/warehouses?keyword=" + DEALER_WAREHOUSE_CODE))
                .path("content")) {
            if (DEALER_WAREHOUSE_CODE.equals(w.path("code").asText())) {
                return w.path("id").asLong();
            }
        }
        return createId("/masters/warehouses", Map.of(
                "code", DEALER_WAREHOUSE_CODE, "name", "특약점주문 물류창고", "type", "MAIN"));
    }

    private List<String> visibleCodes() {
        JsonNode r = getAsDealerSite("/dealer-site/products?partnerCode=" + partnerCode, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        List<String> codes = new java.util.ArrayList<>();
        for (JsonNode p : r.path("data")) {
            codes.add(p.path("productCode").asText());
        }
        return codes;
    }

    @Test
    @DisplayName("★Web게시 Y 도서 + 매핑 Y 면 노출된다 / 도서 Web게시 N 은 안 보인다(①)")
    void 노출과_도서웹게시() {
        List<String> codes = visibleCodes();

        assertThat(codes).as("조건을 다 만족하는 도서").contains(visibleCode);
        assertThat(codes).as("도서 Web게시 N 은 비노출(①)").doesNotContain(hiddenBookCode);
    }

    @Test
    @DisplayName("★금액·Web게시 칸은 응답에 담기지 않는다 — §1·§3")
    void 금액칸_미노출() {
        JsonNode r = getAsDealerSite("/dealer-site/products?partnerCode=" + partnerCode, DEALER_SITE_KEY);
        JsonNode row = null;
        for (JsonNode p : r.path("data")) {
            if (visibleCode.equals(p.path("productCode").asText())) {
                row = p;
            }
        }
        assertThat(row).isNotNull();
        // 사이트 화면이 쓰는 칸은 있어야 한다.
        assertThat(row.path("name").asText()).isEqualTo("노출도서");
        assertThat(row.path("salesDivision").asText()).isEqualTo("교재");
        assertThat(row.path("catName").asText()).isEqualTo("노출분류");
        assertThat(row.has("isSet")).isTrue();
        // ‼️금액·노출플래그는 담지 않는다(두 곳에서 금액이 계산되기 시작하는 것을 막는다).
        assertThat(row.has("price")).as("정가").isFalse();
        assertThat(row.has("supplyRate")).as("공급률").isFalse();
        assertThat(row.has("webVisible")).as("Web게시").isFalse();
    }

    @Test
    @DisplayName("★매핑이 없는 거래처는 아무것도 안 보인다(④)")
    void 매핑없으면_비노출() {
        String otherCode = "DVQ" + SFX;
        createId("/masters/clients", Map.of("code", otherCode, "name", "매핑없는거래처", "type", "NORMAL"));

        JsonNode r = getAsDealerSite("/dealer-site/products?partnerCode=" + otherCode, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(r.path("data")).as("매핑이 없으면 비노출 — 빈 목록").isEmpty();
    }

    @Test
    @DisplayName("★매핑 Web게시를 끄면 그 거래처에서 사라진다(②) — 캐시 없이 바로")
    void 매핑_웹게시_끄면_사라진다() {
        String code = "DVR" + SFX;
        Long p = createId("/masters/clients", Map.of("code", code, "name", "웹게시끌거래처", "type", "NORMAL"));
        put("/masters/partner-supply-rates/" + p + "/TEXTBOOK",
                Map.of("supplyRate", 70, "webVisible", true, "useYn", true));

        JsonNode before = getAsDealerSite("/dealer-site/products?partnerCode=" + code, DEALER_SITE_KEY);
        assertThat(before.path("data")).as("켜면 보인다").isNotEmpty();

        put("/masters/partner-supply-rates/" + p + "/TEXTBOOK", Map.of("webVisible", false));

        JsonNode after = getAsDealerSite("/dealer-site/products?partnerCode=" + code, DEALER_SITE_KEY);
        assertThat(after.path("data")).as("끄면 즉시 사라진다(캐시 없음)").isEmpty();
    }

    @Test
    @DisplayName("★매핑 사용여부를 끄면 사라진다(③)")
    void 매핑_사용여부_끄면_사라진다() {
        String code = "DVS" + SFX;
        Long p = createId("/masters/clients", Map.of("code", code, "name", "사용끌거래처", "type", "NORMAL"));
        put("/masters/partner-supply-rates/" + p + "/TEXTBOOK",
                Map.of("supplyRate", 70, "webVisible", true, "useYn", true));
        assertThat(getAsDealerSite("/dealer-site/products?partnerCode=" + code, DEALER_SITE_KEY)
                .path("data")).isNotEmpty();

        put("/masters/partner-supply-rates/" + p + "/TEXTBOOK", Map.of("useYn", false));

        assertThat(getAsDealerSite("/dealer-site/products?partnerCode=" + code, DEALER_SITE_KEY)
                .path("data")).as("사용여부 N 이면 비노출").isEmpty();
    }

    @Test
    @DisplayName("★★접수도 같은 판정을 쓴다 — 노출 안 되는 도서는 PRODUCT_NOT_VISIBLE 로 거부")
    void 비노출도서는_접수거부() {
        Map<String, Object> req = new HashMap<>();
        req.put("partnerCode", partnerCode);
        req.put("orderDate", DATE);
        req.put("items", List.of(Map.of("productCode", hiddenBookCode, "qty", 1)));

        JsonNode r = postAsDealerSite("/dealer-site/orders", req, DEALER_SITE_KEY);

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("PRODUCT_NOT_VISIBLE");
        assertThat(r.path("error").path("message").asText())
                .as("어느 코드가 문제인지 알려준다").contains(hiddenBookCode);
    }

    @Test
    @DisplayName("노출되는 도서는 그대로 접수된다 — 막는 것이 과하지 않다")
    void 노출도서는_접수성공() {
        Map<String, Object> req = new HashMap<>();
        req.put("partnerCode", partnerCode);
        req.put("orderDate", DATE);
        req.put("items", List.of(Map.of("productCode", visibleCode, "qty", 2)));

        JsonNode r = postAsDealerSite("/dealer-site/orders", req, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    @Test
    @DisplayName("★거래처를 못 찾으면 PARTNER_NOT_LINKED — 사이트가 코드로 분기한다(요청 5번)")
    void 거래처미연결_전용코드() {
        JsonNode r = getAsDealerSite("/dealer-site/products?partnerCode=없는코드" + SFX, DEALER_SITE_KEY);

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText())
                .as("문구가 아니라 코드로 구분할 수 있어야 한다").isEqualTo("PARTNER_NOT_LINKED");
    }

    @Test
    @DisplayName("★담당자 기본값 조회 — 접수 때 채우는 값과 같은 출처(요청 3번)")
    void 담당자_기본값_조회() {
        JsonNode r = getAsDealerSite("/dealer-site/contact?partnerCode=" + partnerCode, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        JsonNode d = r.path("data");
        assertThat(d.path("partnerCode").asText()).isEqualTo(partnerCode);
        assertThat(d.path("receiverName").asText()).isEqualTo("김대표");
        assertThat(d.path("receiverPhone").asText()).isEqualTo("02-555-6666");
        assertThat(d.path("receiverEmail").asText()).isEqualTo("dv@example.com");
    }

    @Test
    @DisplayName("담당자 값이 없는 거래처는 빈 값으로 준다 — 오류가 아니다(요청 3번)")
    void 담당자_없으면_빈값() {
        String code = "DVT" + SFX;
        createId("/masters/clients", Map.of("code", code, "name", "담당자없는거래처", "type", "NORMAL"));

        JsonNode r = getAsDealerSite("/dealer-site/contact?partnerCode=" + code, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        // 전역 JsonInclude.NON_NULL — 값이 없으면 키 자체가 빠진다.
        assertThat(r.path("data").hasNonNull("receiverName")).isFalse();
    }

    /**
     * ★<b>단가를 못 구하는 도서는 목록에 없다</b>(특약점팀 요청 2026-11-12 §2).
     *
     * <p>그전엔 "노출 켜짐"만 보고 목록을 만들어, 정가가 없는 도서가 목록엔 뜨고
     * 신청하면 400 으로 거절됐다({@code DL-A2012A-01}). 접수가 거부할 도서는
     * 애초에 고르지 못하게 한다.
     *
     * <p>운영 실측(2026-11-12): 정가 0 인 노출 후보 2,506종 중 1,682종이 「해설」이고
     * 나머지도 지도서·교사용·쿠폰처럼 따로 파는 물건이 아니다. 레거시 특약점 교재
     * 목록 34종에도 해설·지도서는 하나도 없다.
     */
    @Test
    @DisplayName("★★정가가 0/없는 도서는 목록에서 빠진다 — 고를 수는 있는데 저장이 안 되던 것")
    void 정가없으면_목록에서_빠진다() {
        String noPrice = "DVB3" + SFX;
        Map<String, Object> b = new HashMap<>();
        b.put("code", noPrice);
        b.put("name", "해설(정가 0)");
        b.put("contentType", "SELF");
        b.put("price", 0);                       // ← 해설·지도서가 이 모양이다
        b.put("supplyRate", 70);
        b.put("salesDivision", "교재");
        b.put("catCode", "A2089A");
        b.put("catName", "노출분류");
        b.put("webVisible", true);               // 노출은 켜 둔다
        createId("/masters/products", b);

        assertThat(visibleCodes())
                .as("‼️정가가 없으면 단가를 못 구한다 — 목록에 있으면 신청이 400 으로 거절된다")
                .doesNotContain(noPrice)
                .as("다른 도서는 그대로 보인다").contains(visibleCode);
    }

    @Test
    @DisplayName("★공급률을 못 구해도 빠진다 — 거래처 매핑도 도서 기본값도 없을 때")
    void 공급률없으면_목록에서_빠진다() {
        // 대분류가 '기타고사'인 도서 — 이 거래처에는 교재 매핑만 있어 기타고사 매핑이 없다.
        // 도서 자체 공급률도 비워 두면 어느 쪽으로도 공급률을 구할 수 없다.
        String noRate = "DVB4" + SFX;
        Map<String, Object> b = new HashMap<>();
        b.put("code", noRate);
        b.put("name", "공급률 없는 도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);                   // 정가는 있다
        b.put("salesDivision", "기타고사");        // 매핑 없는 대분류
        b.put("catCode", "A2089A");
        b.put("catName", "노출분류");
        b.put("webVisible", true);
        createId("/masters/products", b);

        assertThat(visibleCodes())
                .as("공급률을 못 구하면 단가도 못 구한다")
                .doesNotContain(noRate);
    }
}
