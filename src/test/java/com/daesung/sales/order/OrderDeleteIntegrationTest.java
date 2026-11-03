package com.daesung.sales.order;

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
 * 주문조회 화면의 <b>주문 삭제</b> — 접수완료 상태일 때만(§2-9).
 *
 * <p>정본 §2-9 — "주문 삭제 … **접수완료 상태일 때만** 가능하게 한다." 10/27 명세
 * "## 2. 주문/출고관리 — 주문 조회/등록(통합)" 섹션에 있고, **매출등록 화면**의 거래순번
 * 삭제(§3-6, "## 3. 매출관리" 섹션)와는 <b>다른 화면 기능</b>이다.
 *
 * <p>★<b>급소</b>: 매출등록 삭제(`DELETE /sales/entries/{entryNo}`)는 이미 PREPARING 상태
 * 주문을 지우는 걸 정상 동작으로 고정하고 있다({@code EntryEditIntegrationTest.통째_삭제}).
 * 이 새 경로(`DELETE /orders/{orderNo}`)가 그 기존 동작을 건드리지 않는지도 함께 확인한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("주문 삭제 — 접수완료만(§2-9)")
class OrderDeleteIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-OD" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2099-05-05";

    private Long partner;
    private Long wh;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "ODS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "ODP" + SFX, "name", "주문삭제거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "ODW" + SFX, "name", "물류창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "ODB" + SFX);
        b.put("name", "주문삭제도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "D2099A01");
        b.put("catName", "주문삭제검증");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2099-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 100, "qty", 999))))
                .path("success").asBoolean()).isTrue();
    }

    private String register() {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", book);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", 3);
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", wh,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r).path("entryNo").asText();
    }

    private JsonNode toReceived(String orderNo) {
        return post("/orders/progress-state", Map.of(
                "orderNos", List.of(orderNo), "toState", "RECEIVED"));
    }

    @Test
    @DisplayName("★접수완료가 아니면 거부한다 — 수기 등록은 상품준비중에서 시작한다")
    void 접수완료아니면_거부() {
        String no = register();   // 아무것도 안 하면 상품준비중

        JsonNode r = del("/orders/" + no);
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("접수완료 상태일 때만");

        // 지워지지 않았다 — 매출등록 화면(/sales/entries)에서 여전히 조회된다.
        assertThat(data(get("/sales?fromDate=" + DATE + "&toDate=" + DATE + "&partnerId=" + partner))
                .path("totalElements").asInt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("★접수완료면 지워진다")
    void 접수완료면_삭제() {
        String no = register();
        assertThat(toReceived(no).path("success").asBoolean()).isTrue();

        JsonNode r = del("/orders/" + no);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(r.path("data").asInt()).isEqualTo(1);

        // ‼️같은 날짜·거래처로 다른 테스트가 만든 주문도 있을 수 있다 — 이 주문번호만 본다.
        for (JsonNode l : data(get("/sales?fromDate=" + DATE + "&toDate=" + DATE
                + "&partnerId=" + partner + "&size=200")).path("content")) {
            assertThat(l.path("entryNo").asText()).as("방금 지운 주문의 줄이 남아있다").isNotEqualTo(no);
        }
    }

    @Test
    @DisplayName("상품검수·발송준비중도 거부한다")
    void 다른상태도_거부() {
        String a = register();
        assertThat(toReceived(a).path("success").asBoolean()).isTrue();
        assertThat(post("/orders/progress-state", Map.of(
                "orderNos", List.of(a), "toState", "INSPECTING"))
                .path("success").asBoolean()).isTrue();
        JsonNode r1 = del("/orders/" + a);
        assertThat(r1.path("success").asBoolean()).isFalse();

        String b = register();
        for (JsonNode o : data(get("/orders?fromDate=" + DATE + "&toDate=" + DATE
                + "&partnerId=" + partner + "&keyword=" + b)).path("content")) {
            for (JsonNode sid : o.path("shipmentIds")) {
                assertThat(post("/logistics/work-orders/" + sid.asLong() + "/print", Map.of())
                        .path("success").asBoolean()).isTrue();
            }
        }
        JsonNode r2 = del("/orders/" + b);
        assertThat(r2.path("success").asBoolean()).isFalse();
        assertThat(r2.path("error").path("message").asText()).contains("발송준비중");
    }

    @Test
    @DisplayName("등록번호 없는 옛 주문(#id)은 지울 수 없다")
    void 옛주문은_거부() throws Exception {
        // ‼️"#"는 URL 프래그먼트 구분자라 그대로 넣으면 안 되고, 퍼센트 인코딩은
        //   RestTemplate 이 템플릿 문자열로 다시 인코딩해 깨진다 — URI 객체로 직접 부른다.
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setBearerAuth(token());
        var resp = rest.getRestTemplate().exchange(
                new java.net.URI(rest.getRootUri() + "/api/v1/orders/%23999999"),
                org.springframework.http.HttpMethod.DELETE,
                new org.springframework.http.HttpEntity<>(h), String.class);
        JsonNode r = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.getBody());
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("등록번호가 없는 옛 주문");
    }

    @Test
    @DisplayName("없는 주문번호는 404")
    void 없는주문() {
        JsonNode r = del("/orders/E-없는번호" + SFX);
        assertThat(r.path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★★매출등록 화면의 기존 삭제는 안 건드려진다 — PREPARING 상태도 그대로 지워진다")
    void 매출등록삭제는_영향없음() {
        String no = register();   // 상품준비중, 접수완료로 안 바꿈

        // 매출등록 화면 경로(§3-6) — 기존 EntryEditIntegrationTest.통째_삭제와 같은 흐름.
        JsonNode r = del("/sales/entries/" + no);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }
}
