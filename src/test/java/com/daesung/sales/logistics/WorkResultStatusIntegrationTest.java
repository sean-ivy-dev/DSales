package com.daesung.sales.logistics;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 작업결과 <b>진행상태 6단계</b>(발주처 확정 2026-10-23 #17).
 *
 * <p>"진행상태 한 컬럼 6단계 / 완료·삭제 컬럼은 진행상태로 통합 / 조회조건에 진행상태 포함".
 *
 * <p>★판정은 <b>발송 날짜</b>로 한다 — 삭제 &gt; 발송일 &gt; 출력 &gt; 그 밖.
 * ‼️'완료'(completedAt)는 쓰지 않는다. 이관분 46,218건이 가짜 1900-01-01 로 채워져
 * 사실상 전건 참이었고, 레거시에서 실제로 쓰인 건 13건뿐이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("작업결과 진행상태 6단계")
class WorkResultStatusIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-WS" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2092-06-10";
    private static final String RANGE = "?fromDate=" + DATE + "&toDate=" + DATE + "&page=0&size=50";

    @Autowired
    private JdbcTemplate jdbc;

    private Long warehouse;
    private Long prepPartner;
    private Long readyPartner;
    private Long shippedPartner;
    private Long deletedPartner;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "WSS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "WSW" + SFX, "name", "본사물류창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "WSB" + SFX);
        b.put("name", "진행상태 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "W2092A01");
        b.put("catName", "진행상태");
        b.put("taxFree", false);
        b.put("useYn", true);
        b.put("set", false);
        b.put("salesDivision", "교재");
        Long book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2092-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 999))))
                .path("success").asBoolean()).isTrue();

        // 네 거래처 = 네 상태. 발송은 (일자·거래처·학교·분류)로 묶이므로 거래처를 갈라야 섞이지 않는다.
        prepPartner = partnerWithSale("WSP", book);
        readyPartner = partnerWithSale("WSR", book);
        shippedPartner = partnerWithSale("WSD", book);
        deletedPartner = partnerWithSale("WSX", book);

        // 출력됨 → 발송준비중
        jdbc.update("UPDATE shipment SET printed_at = NOW() WHERE partner_id = ?", readyPartner);
        // 발송일 있음 → 발송완료(출력도 함께 — 실제로 그 순서다)
        jdbc.update("UPDATE shipment SET printed_at = NOW(), sent_date = ? WHERE partner_id = ?",
                java.sql.Date.valueOf(DATE), shippedPartner);
        // 삭제
        jdbc.update("UPDATE shipment SET deleted_at = NOW() WHERE partner_id = ?", deletedPartner);

        // ‼️'완료'를 전건 켜 둔다 — 판정이 completedAt 을 보면 전부 같은 상태가 되어 테스트가 무너진다.
        //   그게 이관분에서 실제로 벌어진 일이다.
        jdbc.update("UPDATE shipment SET completed_at = NOW() WHERE trade_date = ?",
                java.sql.Date.valueOf(DATE));
    }

    private Long partnerWithSale(String prefix, Long book) {
        Long p = createId("/masters/clients",
                Map.of("code", prefix + SFX, "name", prefix + "거래처", "type", "NORMAL"));
        Map<String, Object> item = new HashMap<>();
        item.put("productId", book);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", 5);
        assertThat(post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", p, "warehouseId", warehouse,
                "items", List.of(item))).path("success").asBoolean()).isTrue();
        return p;
    }

    private String statusOf(JsonNode rows, Long partnerId, String partnerName) {
        for (JsonNode r : rows) {
            if (partnerName.equals(r.path("partnerName").asText())) {
                return r.path("status").asText();
            }
        }
        return null;
    }

    @Test
    @DisplayName("★발송 날짜로 네 상태가 갈린다 — '완료'가 전건 켜져 있어도 흔들리지 않는다")
    void 상태_판정() {
        JsonNode rows = data(get("/logistics/work-results" + RANGE)).path("content");

        // ★(가)안(2026-11-16) — 아무 조작도 안 한 건은 「상품준비중」이 아니라 **접수완료**다.
        //   ‼️작업결과는 접수완료를 빼지 않는다(작업요청서만 뺀다) — 그래서 여기 보인다.
        assertThat(statusOf(rows, prepPartner, "WSP거래처")).isEqualTo("RECEIVED");
        assertThat(statusOf(rows, readyPartner, "WSR거래처")).isEqualTo("READY_TO_SHIP");
        assertThat(statusOf(rows, shippedPartner, "WSD거래처")).isEqualTo("SHIPPED");

        // 한글 표기도 서버가 준다 — 화면이 매핑표를 들고 있지 않게 한다.
        for (JsonNode r : rows) {
            if ("WSD거래처".equals(r.path("partnerName").asText())) {
                assertThat(r.path("statusLabel").asText()).isEqualTo("발송완료");
            }
        }
    }

    @Test
    @DisplayName("‼️삭제는 지정할 때만 나온다 — 평소 목록에 섞이면 안 된다")
    void 삭제는_지정할때만() {
        JsonNode normal = data(get("/logistics/work-results" + RANGE)).path("content");
        assertThat(statusOf(normal, deletedPartner, "WSX거래처")).as("평소엔 안 보인다").isNull();

        JsonNode withDeleted = data(get("/logistics/work-results" + RANGE + "&statuses=CANCELED"))
                .path("content");
        assertThat(statusOf(withDeleted, deletedPartner, "WSX거래처")).isEqualTo("CANCELED");
        // 삭제만 골랐으니 다른 상태는 안 나와야 한다.
        assertThat(statusOf(withDeleted, shippedPartner, "WSD거래처")).isNull();
    }

    @Test
    @DisplayName("진행상태 다중선택 — 두 상태를 함께 고를 수 있다")
    void 다중선택() {
        JsonNode rows = data(get("/logistics/work-results" + RANGE
                + "&statuses=SHIPPED,READY_TO_SHIP")).path("content");

        assertThat(statusOf(rows, shippedPartner, "WSD거래처")).isEqualTo("SHIPPED");
        assertThat(statusOf(rows, readyPartner, "WSR거래처")).isEqualTo("READY_TO_SHIP");
        assertThat(statusOf(rows, prepPartner, "WSP거래처")).as("안 고른 상태는 빠진다").isNull();
    }
}
