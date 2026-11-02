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
 * 미분류 매출 목록 — 10/27 검수 피드백 §7-C 하단 5번
 * "데이터 이관 때 매핑되지 않는 누락 건은 반드시 목록으로 뽑아 확인한다".
 *
 * <p>대분류는 {@code 매출 → 상품.세부구분 → 세부구분 마스터.대분류} 로 이어진다.
 * 이 사슬이 끊기는 경우는 둘이다 — 세부구분이 <b>비어 있는</b> 경우({@code EMPTY})와,
 * 값은 있는데 <b>마스터에 없는</b> 경우({@code UNKNOWN}).
 *
 * <p>★<b>{@code UNKNOWN}은 API로 만들 수 없다</b>(아래 {@code 모르는값은_등록거부}가 고정).
 * 상품 등록·수정이 세부구분 코드를 검증하고, 사용 중인 세부구분을 지워도 '비활성'이 될 뿐
 * 행이 남아 매핑은 유지된다. 그래서 {@code UNKNOWN}은 <b>이관 데이터에만</b> 존재한다 —
 * 신규 유입 경로는 이미 닫혀 있고, 남은 건 {@code EMPTY}(이관 때 안 채워진 상품)다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("미분류 매출 목록(10/27 피드백 §7-C-5)")
class UnclassifiedSalesIntegrationTest extends IntegrationTestSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final String SFX = "-UC" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2085;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private String emptyCode;
    private String okCode;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "UCS" + SFX, "name", "인쇄", "type", "NORMAL"));
        Long partner = createId("/masters/clients",
                Map.of("code", "UCP" + SFX, "name", "미분류거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "UCW" + SFX, "name", "물류", "type", "MAIN"));

        emptyCode = "UCE" + SFX;       // 세부구분 없음 → EMPTY
        okCode = "UCO" + SFX;          // 정상 매핑 → 목록에 안 나와야 한다

        // ‼️**세부구분 없이는 더 이상 등록할 수 없다**(G-3 — 10/27 §7-C 3번).
        //   그래서 정상 등록한 뒤 **SQL로 비워** 이관분을 흉내 낸다 — 운영에서 미분류가
        //   생길 수 있는 길이 이제 그것뿐이고, 테스트도 그 사실을 그대로 반영해야 한다.
        Long pEmpty = book(emptyCode, "미분류도서(빈값)", "교재");
        jdbc.update("UPDATE products SET sales_division = NULL WHERE id = ?", pEmpty);
        Long pOk = book(okCode, "정상도서", "교재");

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(
                        Map.of("productId", pEmpty, "unitCost", 1000, "qty", 500),
                        Map.of("productId", pOk, "unitCost", 1000, "qty", 500))));

        sell(partner, wh, pEmpty, 100);
        sell(partner, wh, pOk, 50);
    }

    private Long book(String code, String name, String division) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "A" + YEAR + "A");
        b.put("catName", "미분류분류");
        b.put("salesDivision", division);
        return createId("/masters/products", b);
    }

    private void sell(Long partner, Long wh, Long product, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-03-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private JsonNode report() {
        return data(get("/sales/unclassified" + RANGE));
    }

    private JsonNode rowOf(String productCode) {
        for (JsonNode r : report().path("rows")) {
            if (productCode.equals(r.path("productCode").asText())) {
                return r;
            }
        }
        return null;
    }

    @Test
    @DisplayName("★세부구분이 비어 있으면 EMPTY로 잡힌다")
    void 빈값은_EMPTY() {
        JsonNode r = rowOf(emptyCode);

        assertThat(r).as("목록에 없다").isNotNull();
        assertThat(r.path("reason").asText()).isEqualTo("EMPTY");
        assertThat(r.path("saleQty").asLong()).isEqualTo(100);
        assertThat(r.path("saleAmount").asLong()).isEqualTo(100 * 7000);
        assertThat(r.path("lastSalesDate").asText()).isEqualTo(YEAR + "-03-10");
    }

    @Test
    @DisplayName("★미분류는 API로 만들 수 없다 — 모르는 값도, 빈 값도 등록이 거부한다")
    void 모르는값은_등록거부() {
        // ‼️그래서 UNKNOWN(세부구분 값은 있는데 마스터에 없음)은 **이관 데이터에만** 존재한다.
        //   신규 등록 경로는 이미 막혀 있다(ProductService 가 salesDivisionService.validateCode 호출).
        //   사용 중인 세부구분을 지워도 '비활성'이 될 뿐 행이 남아 매핑은 유지된다.
        Map<String, Object> b = new HashMap<>(Map.of(
                "code", "UCX" + SFX, "name", "모르는구분도서", "contentType", "SELF",
                "price", 10000, "supplyRate", 70, "salesDivision", "존재하지않는구분" + SFX));
        JsonNode r = post("/masters/products", b);

        assertThat(r.path("success").asBoolean()).as("모르는 세부구분이 통과했다: %s", r).isFalse();
    }

    @Test
    @DisplayName("정상 매핑된 상품은 목록에 없다 — 멀쩡한 걸 작업 목록에 올리면 아무도 안 쓴다")
    void 정상상품은_제외() {
        assertThat(rowOf(okCode)).as("정상 상품이 미분류로 잡혔다").isNull();
    }

    @Test
    @DisplayName("★매출액 큰 순서로 나온다 — 어느 상품부터 고쳐야 장부가 빨리 맞는지가 먼저 보여야 한다")
    void 매출_큰순_정렬() {
        List<Long> amounts = new java.util.ArrayList<>();
        for (JsonNode r : report().path("rows")) {
            amounts.add(r.path("saleAmount").asLong());
        }
        assertThat(amounts).as("%s", amounts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("★미분류 비율을 함께 낸다 — 목록 길이만으로는 심각도를 알 수 없다")
    void 비율() {
        JsonNode d = report();

        assertThat(d.path("amount").asLong()).as("미분류 금액").isPositive();
        assertThat(d.path("totalAmount").asLong()).as("전체 매출(분모)")
                .isGreaterThanOrEqualTo(d.path("amount").asLong());
        assertThat(d.path("ratio").asDouble()).isBetween(0.0, 100.0);
    }

    @Test
    @DisplayName("엑셀로도 내려받는다 — 담당자가 들고 다니며 채우는 작업 목록이다")
    void 엑셀() {
        assertThat(getBytes("/sales/unclassified/export" + RANGE).getBody()).isNotEmpty();
    }
}
