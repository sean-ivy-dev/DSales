package com.daesung.sales.sale;

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
 * 묶음 합계 — 통합 매출 조회의 <b>소계 행</b>(프론트 B-3).
 *
 * <p>요청: "정렬 우선순위 소계용 묶음 합계 조회를 추가해 주세요.
 * {@code GET /sales/group-totals?groupBy=PARTNER|DATE}, <b>조건은 /sales 와 같고</b>,
 * 응답은 묶음 키·묶음 이름·수량·공급가액·세액·합계금액.
 * 지금은 전량을 받아 계산하는데 1~10월(27,187건)에 <b>약 4분</b>이 걸려,
 * 1만 건이 넘으면 소계를 못 붙입니다."
 *
 * <p>★이 테스트가 지키는 것은 "빠르다"가 아니라 <b>"목록과 같은 집합을 센다"</b>이다.
 * 소계가 목록과 다른 걸 세면 화면에서 합이 안 맞고, 그건 조용히 틀린다.
 * 그래서 서버는 {@code search} 와 <b>같은 WHERE 절 상수</b>를 공유하고,
 * 여기서는 <b>같은 조건으로 부른 목록의 합</b>과 대조한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("묶음 합계(프론트 B-3)")
class GroupTotalsIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-GT" + (System.nanoTime() % 1_000_000L);
    private static final String D1 = "2046-02-10";
    private static final String D2 = "2046-02-11";

    private Long pa;
    private Long pb;
    private Long wh;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "GTS" + SFX, "name", "인쇄", "type", "NORMAL"));
        pa = createId("/masters/clients",
                Map.of("code", "GTA" + SFX, "name", "묶음가", "type", "NORMAL"));
        pb = createId("/masters/clients",
                Map.of("code", "GTB" + SFX, "name", "묶음나", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "GTW" + SFX, "name", "물류", "type", "MAIN"));
        Map<String, Object> b = new HashMap<>();
        b.put("code", "GTK" + SFX);
        b.put("name", "묶음도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);
        post("/stock/inbound", Map.of("processedDate", "2046-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 900))));

        sell(pa, D1, 10);
        sell(pa, D1, 20);   // 같은 거래처·같은 날 두 건 → 합쳐져야 한다
        sell(pa, D2, 5);
        sell(pb, D1, 30);
    }

    private void sell(Long partner, String date, int qty) {
        JsonNode r = post("/sales/entries", Map.of("salesDate", date,
                "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private String range() {
        return "fromDate=" + D1 + "&toDate=" + D2;
    }

    private JsonNode totals(String groupBy, String extra) {
        JsonNode raw = get("/sales/group-totals?groupBy=" + groupBy + "&" + range() + extra);
        assertThat(raw.path("success").asBoolean()).as("%s", raw).isTrue();
        return data(raw);
    }

    private JsonNode row(JsonNode rows, String name) {
        for (JsonNode r : rows) {
            if (name.equals(r.path("name").asText())) {
                return r;
            }
        }
        throw new AssertionError("묶음 '" + name + "' 없음. 응답=" + rows);
    }

    @Test
    @DisplayName("★거래처별로 묶는다 — 같은 거래처의 여러 건이 한 줄로 합쳐진다")
    void 거래처별() {
        JsonNode rows = totals("PARTNER", "&partnerIds=" + pa + "&partnerIds=" + pb);

        assertThat(row(rows, "묶음가").path("qty").asLong()).as("10+20+5").isEqualTo(35);
        assertThat(row(rows, "묶음나").path("qty").asLong()).isEqualTo(30);
        // 공급가액 = 10,000 × 70% × 수량
        assertThat(row(rows, "묶음가").path("supplyAmount").asLong()).isEqualTo(35 * 7_000);
    }

    @Test
    @DisplayName("거래일자별로도 묶는다")
    void 일자별() {
        JsonNode rows = totals("DATE", "&partnerIds=" + pa + "&partnerIds=" + pb);

        assertThat(row(rows, D1).path("qty").asLong()).as("10+20+30").isEqualTo(60);
        assertThat(row(rows, D2).path("qty").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("★목록과 같은 집합을 센다 — 조건을 걸어도 둘이 어긋나지 않는다")
    void 목록과_같은_집합() {
        // 거래처 하나로 좁히고, 목록을 전량 받아 직접 더한 값과 대조한다.
        String cond = "&partnerIds=" + pa;
        long listQty = 0;
        for (JsonNode l : data(get("/sales?" + range() + cond + "&size=200")).path("content")) {
            listQty += l.path("qty").asLong();
        }

        JsonNode rows = totals("PARTNER", cond);
        long sumQty = 0;
        for (JsonNode r : rows) {
            sumQty += r.path("qty").asLong();
        }

        assertThat(sumQty).as("소계 합 = 목록 합").isEqualTo(listQty).isEqualTo(35);
        assertThat(rows).as("조건으로 좁혔으니 그 거래처만").hasSize(1);
    }

    @Test
    @DisplayName("shippedOnly 도 같이 먹는다 — 출고/반품 조회의 소계가 목록과 달라지면 안 된다")
    void 출고조건도_같이() {
        JsonNode rows = totals("PARTNER", "&partnerIds=" + pa + "&shippedOnly=true");
        // 시드는 전부 정상 출고라 결과가 같아야 한다(조건이 **받아들여지는지**와 집합이 안 바뀌는지).
        assertThat(row(rows, "묶음가").path("qty").asLong()).isEqualTo(35);
    }
}
