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
 * 매출액 정리(13p) 구분·매출유형 필터 — 프론트 번들 실측(2026-09-16).
 *
 * <p>화면이 두 칸을 <b>"아직 그 기준으로 집계되지 않습니다"</b>로 비워 두고 있었다
 * ({@code unsupported:['summaryKind','salesType']}).
 *
 * <p>★<b>칸이 없던 게 아니라 필터가 없었다.</b> 응답에는 매출·반품·교사용·증정 수량이
 * 이미 다 있었고, 그 축으로 <b>좁히는</b> 방법이 없었을 뿐이다. 그래서 집계 형태를 바꾸지 않고
 * 필터만 더했다 — 행 구조를 바꾸면 이미 붙어 있는 엑셀·화면이 같이 깨진다.
 *
 * <p>‼️구분을 지정했을 때만 {@code summaryKind}에 값이 실린다. 안 지정하면 한 행에 네 구분이
 * 다 들어 있어(saleQty·returnQty·…) 한 값으로 적을 수가 없다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("매출액정리 구분·매출유형 필터(2026-09-16)")
class SummaryFilterIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SF" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2084;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long partner;
    private String bookCode;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "SFS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "SFP" + SFX, "name", "정리거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "SFW" + SFX, "name", "정리창고", "type", "MAIN"));

        bookCode = "SFB" + SFX;
        Map<String, Object> b = new HashMap<>();
        b.put("code", bookCode);
        b.put("name", "정리도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "S" + YEAR + "A");
        b.put("catName", "정리분류");
        b.put("salesDivision", "교재");
        Long book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 1000))));

        ship(wh, book, "NORMAL_SHIP", 100);
        ship(wh, book, "TEACHER_USE", 20);
        ship(wh, book, "GIFT", 5);
        post("/sales/return-inbound", Map.of(
                "returnDate", YEAR + "-02-20", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitPrice", 10000,
                        "supplyRate", 70, "qty", 8))));
    }

    private void ship(Long wh, Long book, String type, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", type,
                        "unitPrice", 10000, "supplyRate", 70, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("%s: %s", type, r).isTrue();
    }

    private JsonNode row(String query) {
        for (JsonNode r : data(get("/sales/summary" + RANGE + query)).path("rows")) {
            if (bookCode.equals(r.path("productCode").asText())) {
                return r;
            }
        }
        throw new IllegalStateException("행 없음: " + query);
    }

    @Test
    @DisplayName("★구분=매출 — 매출만 남고 교사용·반품은 0")
    void 구분_매출() {
        JsonNode r = row("&summaryKind=매출");

        assertThat(r.path("summaryKind").asText()).as("무엇으로 걸렀는지 행에 적힌다").isEqualTo("매출");
        assertThat(r.path("saleQty").asLong()).isEqualTo(100);
        assertThat(r.path("teacherQty").asLong()).as("교사용은 빠져야").isZero();
        assertThat(r.path("returnQty").asLong()).as("반품도 빠져야").isZero();
    }

    @Test
    @DisplayName("★구분=교사용 — 증정(GIFT)까지 딸려오면 안 된다")
    void 구분_교사용() {
        JsonNode r = row("&summaryKind=교사용");

        assertThat(r.path("teacherQty").asLong()).isEqualTo(20);
        assertThat(r.path("freeQty").asLong()).as("증정 5부는 다른 구분이다").isZero();
        assertThat(r.path("saleQty").asLong()).isZero();
    }

    @Test
    @DisplayName("구분=반품")
    void 구분_반품() {
        JsonNode r = row("&summaryKind=반품");

        assertThat(r.path("returnQty").asLong()).isEqualTo(8);
        assertThat(r.path("saleQty").asLong()).isZero();
    }

    @Test
    @DisplayName("매출유형=일반매출 — 위탁이 아닌 매출만")
    void 매출유형() {
        JsonNode r = row("&salesType=일반매출");

        assertThat(r.path("salesType").asText()).isEqualTo("일반매출");
        assertThat(r.path("saleQty").asLong()).isEqualTo(100);
    }

    @Test
    @DisplayName("★미지정이면 종전 그대로 — 필터를 더하면서 기본 동작이 바뀌면 안 된다")
    void 미지정은_전체() {
        JsonNode r = row("");

        assertThat(r.path("saleQty").asLong()).isEqualTo(100);
        assertThat(r.path("teacherQty").asLong()).isEqualTo(20);
        assertThat(r.path("freeQty").asLong()).isEqualTo(5);
        assertThat(r.path("returnQty").asLong()).isEqualTo(8);
        // 안 걸렀으면 한 행에 네 구분이 다 있으니 구분을 한 값으로 못 적는다 → 비운다.
        assertThat(r.hasNonNull("summaryKind")).as("미지정이면 비어 있어야").isFalse();
    }

    @Test
    @DisplayName("★모르는 값은 400 — 조용히 전체를 주면 걸러진 줄 알고 본다")
    void 모르는_값() {
        JsonNode bad = get("/sales/summary" + RANGE + "&summaryKind=무상");
        assertThat(bad.path("success").asBoolean()).isFalse();
        assertThat(bad.path("error").path("message").asText()).contains("구분", "증정용");

        JsonNode bad2 = get("/sales/summary" + RANGE + "&salesType=직판");
        assertThat(bad2.path("success").asBoolean()).isFalse();
        assertThat(bad2.path("error").path("message").asText()).contains("매출유형");
    }

    @Test
    @DisplayName("교사용 + 위탁매출 — 없는 조합이라 빈 결과가 맞다")
    void 없는_조합() {
        JsonNode d = data(get("/sales/summary" + RANGE + "&summaryKind=교사용&salesType=위탁매출"));

        boolean found = false;
        for (JsonNode r : d.path("rows")) {
            if (bookCode.equals(r.path("productCode").asText())) {
                found = true;
            }
        }
        assertThat(found).as("교사용이면서 위탁매출인 건은 없다").isFalse();
    }
}
