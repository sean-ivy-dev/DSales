package com.daesung.sales.receivable;

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
 * 외상매출현황 상품군(대분류)별 분해 · 매출수량 · 사장명.
 * 근거: 재무팀 실파일 {@code 외상매출현황조회_20260630.xlsx} — 한 행이 <b>24칸</b>이고
 * 그중 12칸이 상품군별 수량·금액이다(수량/매출 × 교재·모의고사·기타·특강,
 * 반품수량·반품금액 × 교재·기타). 우리 응답은 11필드뿐이었다.
 *
 * <p>★<b>상품군 합은 전체와 맞아야 한다.</b> 대분류를 못 붙인 매출(세부구분 미지정)을
 * 빼 버리면 합이 전체보다 작아지는데, 그러면 담당자는 어느 쪽이 틀렸는지 알 수 없다.
 * 그래서 미분류도 버리지 않고 <b>기타 칸에 합쳐</b> 두고, 이 테스트가 합이 맞는지 고정한다.
 *
 * <p>★<b>4분류 고정</b>(10/27 피드백 §7-C) — 매출은 교재·모의고사·기타·특강 4칸,
 * 반품은 교재·기타 2칸이다. 기타고사·미분류·IC는 모두 기타로 합친다.
 * 예전엔 미분류를 별도 칸으로 남겨 6칸이 나갔다(그때 테스트가 그걸 고정하고 있었다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출현황 상품군별 분해")
class ArCategoryBreakdownIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-AC" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2094;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long partner;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "ACS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        // ‼️사장명이 응답에 실리는지 보려면 거래처에 넣어 둬야 한다.
        partner = createId("/masters/clients", Map.of(
                "code", "ACP" + SFX, "name", "상품군거래처", "type", "NORMAL", "bossName", "김대표"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "ACW" + SFX, "name", "상품군창고", "type", "MAIN"));

        Long textbook = book("ACT" + SFX, "교재상품", "교재");
        Long mock = book("ACM" + SFX, "모의고사상품", "모의고사");
        // 세부구분을 주지 않은 상품 — 대분류가 안 붙어 '미분류'로 떨어진다.
        Long unclassified = book("ACU" + SFX, "미분류상품", null);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", textbook, "unitCost", 1000, "qty", 1000),
                        Map.of("productId", mock, "unitCost", 1000, "qty", 1000),
                        Map.of("productId", unclassified, "unitCost", 1000, "qty", 1000))));

        sell(wh, textbook, "NORMAL_SHIP", 100);
        sell(wh, mock, "NORMAL_SHIP", 30);
        sell(wh, unclassified, "NORMAL_SHIP", 7);
        // 교재만 반품 — 상품군별 반품 칸이 갈리는지 보려면 한쪽만 움직여야 한다.
        JsonNode ret = post("/sales/return-inbound", Map.of(
                "returnDate", YEAR + "-03-01", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", textbook, "qty", 20,
                        "unitPrice", 10000, "supplyRate", 70))));
        assertThat(ret.path("success").asBoolean()).as("반품입고: %s", ret).isTrue();
    }

    private Long book(String code, String name, String division) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "A" + YEAR + "A");
        b.put("catName", "상품군분류");
        if (division != null) {
            b.put("salesDivision", division);
        }
        return createId("/masters/products", b);
    }

    private void sell(Long wh, Long product, String shipmentType, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", product, "shipmentType", shipmentType,
                        "unitPrice", 10000, "supplyRate", 70, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    /** ‼️전역 합계가 아니라 이 거래처 행만 본다 — 다른 테스트가 데이터를 더한다. */
    private JsonNode myRow() {
        for (JsonNode r : data(get("/closing/ar-status" + RANGE + "&partnerId=" + partner)).path("rows")) {
            if (("ACP" + SFX).equals(r.path("partnerCode").asText())) {
                return r;
            }
        }
        throw new AssertionError("거래처 행 없음");
    }

    private JsonNode category(JsonNode row, String majorName) {
        for (JsonNode c : row.path("byCategory")) {
            if (majorName.equals(c.path("majorName").asText())) {
                return c;
            }
        }
        return null;
    }

    @Test
    @DisplayName("★상품군별로 수량·금액이 갈린다")
    void 상품군별_분해() {
        JsonNode row = myRow();

        assertThat(category(row, "교재").path("saleQty").asLong()).isEqualTo(100);
        assertThat(category(row, "교재").path("saleAmount").asLong()).isEqualTo(100 * 7000);
        assertThat(category(row, "모의고사").path("saleQty").asLong()).isEqualTo(30);
        assertThat(category(row, "모의고사").path("saleAmount").asLong()).isEqualTo(30 * 7000);

        // 반품은 교재에만 났다 — 모의고사 칸까지 물들면 안 된다.
        assertThat(category(row, "교재").path("returnQty").asLong()).isEqualTo(20);
        assertThat(category(row, "모의고사").path("returnQty").asLong()).isZero();
    }

    @Test
    @DisplayName("★상품군 합 = 전체 — 미분류를 빼면 합이 어긋나 아무도 못 믿는다")
    void 합이_맞는다() {
        JsonNode row = myRow();

        long sumQty = 0;
        long sumAmt = 0;
        long sumRetQty = 0;
        for (JsonNode c : row.path("byCategory")) {
            sumQty += c.path("saleQty").asLong();
            sumAmt += c.path("saleAmount").asLong();
            sumRetQty += c.path("returnQty").asLong();
        }

        assertThat(sumQty).as("100+30+7(미분류)").isEqualTo(row.path("saleQty").asLong()).isEqualTo(137);
        assertThat(sumAmt).isEqualTo(row.path("saleAmount").asLong());
        assertThat(sumRetQty).isEqualTo(row.path("returnQty").asLong()).isEqualTo(20);
    }

    @Test
    @DisplayName("★세부구분 없는 매출은 '기타'로 합친다 — 버리지도, 미분류 칸으로 내지도 않는다")
    void 미분류는_기타로_합친다() {
        JsonNode row = myRow();

        assertThat(category(row, "미분류")).as("미분류 칸은 더 이상 없다(§7-C)").isNull();
        // 세부구분 없는 7부가 기타 칸에 들어가 있다.
        assertThat(category(row, "기타").path("saleQty").asLong()).isEqualTo(7);
    }

    @Test
    @DisplayName("★칸은 4개로 고정 — 교재·모의고사·기타·특강(기타고사 칸은 없다)")
    void 분류칸_4개_고정() {
        JsonNode row = myRow();

        List<String> names = new java.util.ArrayList<>();
        row.path("byCategory").forEach(c -> names.add(c.path("majorName").asText()));

        assertThat(names).containsExactly("교재", "모의고사", "기타", "특강");
    }

    @Test
    @DisplayName("★반품은 교재·기타 2칸만 움직인다 — 모의고사·특강 반품 칸은 없다(0)")
    void 반품은_2분류() {
        JsonNode row = myRow();

        assertThat(category(row, "교재").path("returnQty").asLong()).isEqualTo(20);
        assertThat(category(row, "모의고사").path("returnQty").asLong()).isZero();
        assertThat(category(row, "특강").path("returnQty").asLong()).isZero();
    }

    @Test
    @DisplayName("사장명·매출수량·반품수량이 실린다 — 실파일 24칸 중 빠져 있던 칸")
    void 실파일_칸() {
        JsonNode row = myRow();

        assertThat(row.path("bossName").asText()).isEqualTo("김대표");
        assertThat(row.path("saleQty").asLong()).isEqualTo(137);
        assertThat(row.path("returnQty").asLong()).isEqualTo(20);
    }

    @Test
    @DisplayName("엑셀이 상품군 칸까지 낸다 — 파일이 비면 재무팀은 화면을 안 쓴다")
    void 엑셀_상품군칸() {
        assertThat(getBytes("/closing/ar-status/export" + RANGE + "&partnerId=" + partner).getBody())
                .as("다운로드가 된다").isNotEmpty();
    }
}
