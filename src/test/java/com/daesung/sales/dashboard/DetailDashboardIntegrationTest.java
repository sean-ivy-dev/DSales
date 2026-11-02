package com.daesung.sales.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 매출 상세 대시보드 3집계 — 출고유형별 · 지역별 · 창고별 재고.
 *
 * <p>배포된 화면이 이 세 칸을 <b>"이 집계는 아직 서버에 없습니다"</b>로 비워 두고 있었다
 * (2026-09-16 프론트 번들 {@code byShipType/byRegion/byWarehouseQty = null}).
 * 실제로 {@code /dashboard/overview} 응답에 없던 축이다.
 *
 * <p>★<b>축을 새 쿼리로 파지 않았다.</b> 순매출 식이 여러 벌로 갈라지면 언젠가 한 벌만
 * 고쳐지고, 그때부터 화면마다 매출이 달라진다. 기존 {@code netSalesBreakdown} 한 벌에
 * 컬럼 두 개를 더해 접는 축만 늘렸다.
 *
 * <p>‼️재고는 <b>금액이 아니라 수량</b>이라 다른 그릇(WarehouseStock)에 담는다.
 * 같은 Share에 담으면 언젠가 매출과 더해진다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("매출 상세 대시보드 3집계(2026-09-16)")
class DetailDashboardIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DD" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = LocalDate.now().getYear();

    private Long wh;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "DDS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        // 지역이 다른 거래처 둘 — 지역 축이 실제로 갈리는지 보려면 최소 둘이 필요하다.
        Long seoul = createId("/masters/clients", Map.of(
                "code", "DDA" + SFX, "name", "서울거래처", "type", "NORMAL", "region", "서울"));
        Long busan = createId("/masters/clients", Map.of(
                "code", "DDB" + SFX, "name", "부산거래처", "type", "NORMAL", "region", "부산"));
        wh = createId("/masters/warehouses",
                Map.of("code", "DDW" + SFX, "name", "대시보드창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "DDBK" + SFX);
        b.put("name", "대시보드도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "D" + YEAR + "A");
        b.put("catName", "대시보드분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 1000))));

        sale(seoul, "NORMAL_SHIP", 100, 70);
        sale(busan, "NORMAL_SHIP", 40, 70);
        sale(seoul, "TEACHER_USE", 10, 70);
    }

    private void sale(Long partner, String type, int qty, int rate) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", type,
                        "unitPrice", 10000, "supplyRate", rate, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private JsonNode overview() {
        return data(get("/dashboard/overview"));
    }

    private long amountOf(JsonNode arr, String name) {
        for (JsonNode n : arr) {
            if (name.equals(n.path("name").asText())) {
                return n.path("netSales").asLong();
            }
        }
        return -1;
    }

    @Test
    @DisplayName("★출고유형별 — 한글 표기까지 서버가 붙인다")
    void 출고유형별() {
        JsonNode rows = overview().path("shipTypeShares");

        assertThat(rows).as("없으면 화면이 '서버에 없습니다'를 띄운다").isNotEmpty();
        // 키는 enum, 이름은 한글 — 화면이 라벨을 다시 만들지 않아도 된다.
        boolean found = false;
        for (JsonNode n : rows) {
            if ("NORMAL_SHIP".equals(n.path("key").asText())) {
                assertThat(n.path("name").asText()).isEqualTo("정상출고");
                found = true;
            }
        }
        assertThat(found).as("정상출고 축이 있어야 한다").isTrue();

        // 교사용(무가)은 순매출에 안 들어가므로 0이다 — 매출로 섞이면 안 된다.
        assertThat(amountOf(rows, "정상출고")).isGreaterThanOrEqualTo(140 * 7000);
    }

    @Test
    @DisplayName("★지역별 — 거래처 지역으로 갈린다")
    void 지역별() {
        JsonNode rows = overview().path("regionShares");

        assertThat(rows).isNotEmpty();
        assertThat(amountOf(rows, "서울")).as("서울 100부").isGreaterThanOrEqualTo(100 * 7000);
        assertThat(amountOf(rows, "부산")).as("부산 40부").isGreaterThanOrEqualTo(40 * 7000);
    }

    @Test
    @DisplayName("★창고별 재고는 수량이다 — 금액 그릇에 담지 않는다")
    void 창고별재고() {
        JsonNode rows = overview().path("warehouseStocks");

        assertThat(rows).isNotEmpty();
        boolean found = false;
        for (JsonNode n : rows) {
            if (wh.equals(n.path("warehouseId").asLong())) {
                assertThat(n.path("name").asText()).isEqualTo("대시보드창고");
                assertThat(n.path("type").asText()).isEqualTo("MAIN");
                // 입고 1000 − 출고(100+40+10) = 850
                assertThat(n.path("qty").asLong()).isEqualTo(850);
                assertThat(n.has("netSales")).as("금액 필드가 있으면 안 된다").isFalse();
                found = true;
            }
        }
        assertThat(found).as("방금 만든 창고가 있어야 한다").isTrue();
    }

    @Test
    @DisplayName("기존 집계가 그대로다 — 축을 늘리면서 총액이 바뀌면 안 된다")
    void 기존집계_불변() {
        JsonNode o = overview();

        assertThat(o.path("topProducts")).isNotEmpty();
        assertThat(o.path("partnerShares")).isNotEmpty();
        assertThat(o.path("monthlyTrend")).hasSize(12);
        assertThat(o.path("kpi").path("cumulativeNetSales").asLong()).isPositive();
    }
}
