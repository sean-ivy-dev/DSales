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
}
