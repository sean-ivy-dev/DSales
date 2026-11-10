package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.product.repository.ProductRepository;
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

/**
 * 프론트 실측 2026-11-10 후속 — 서버 결함 4건.
 *
 * <p>네 건 다 <b>테스트가 없던 축</b>이라 조용히 틀려 있었다. 값으로 못박는다.
 * <ol>
 *   <li><b>§1</b> 응시현황(연도별) 거래처 줄에 지역·상세지역이 비어 왔다 —
 *       소계 통을 만들 때 두 칸을 null 로 넘겼다.</li>
 *   <li><b>§2</b> 응시현황(기간별) 지역이 <b>거래처 마스터 칸</b>이라, 그 칸이 빈 거래처
 *       (909곳 중 382곳)의 학교 줄 260개가 빈칸이었다. 레거시는 <b>거래처코드</b>로 정하고
 *       안 맞으면 '기타' 로 묶어 빈칸이 없다.</li>
 *   <li><b>§3</b> 수량 0 매출(F-17)인데 <b>창고를 요구</b>했다 — 재고 증감이 0 인데도.</li>
 *   <li><b>§4</b> 상품년도가 비어 있어 `productYears` 를 어느 연도로 걸어도 0건이었다.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("프론트 11/10 후속 — 서버 결함 4건")
class FrontFix1110IntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-FX" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2085;

    @Autowired private ProductRepository productRepository;

    private Long seoulPartner;
    private Long warehouse;
    private Long product;
    private String seoulCode;
    private String etcCode;

    @BeforeAll
    void seed() {
        token();
        // ‼️레거시 지역 규칙은 **거래처코드 앞 3자리**다. A10 = 서울, 규칙에 없으면 '기타'.
        seoulCode = "A10" + digits(4);
        etcCode = "Z99" + digits(4);
        seoulPartner = createId("/masters/clients",
                Map.of("code", seoulCode, "name", "서울 거래처", "type", "NORMAL"));
        Long etcPartner = createId("/masters/clients",
                Map.of("code", etcCode, "name", "기타 거래처", "type", "NORMAL"));
        Long supplier = createId("/masters/clients",
                Map.of("code", "FXS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "FXW" + SFX, "name", "물류", "type", "MAIN"));

        // 더프 모의고사(기간별 집계 대상) — 분류코드 M+연도+A
        product = book("FXB" + SFX, "M" + YEAR + "A01", "모의고사");
        // 학교 — 연도별·기간별 둘 다 학교 마스터가 기준이다.
        school("FXH1" + SFX, "서울고", seoulCode, "서울 거래처", "서울", "강남");
        school("FXH2" + SFX, "기타원", etcCode, "기타 거래처", "본사", "본사");

        assertThat(post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05",
                "supplierClientId", supplier, "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "qty", 500, "unitCost", 1_000))))
                .path("success").asBoolean()).isTrue();
        for (Object[] s : new Object[][]{{seoulPartner, "FXH1" + SFX}, {etcPartner, "FXH2" + SFX}}) {
            assertThat(post("/sales/entries", Map.of(
                    "salesDate", YEAR + "-04-10", "partnerId", s[0], "warehouseId", warehouse,
                    "items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                            "qty", 10, "unitPrice", 10_000, "supplyRate", 70,
                            "schoolCode", s[1]))))
                    .path("success").asBoolean()).isTrue();
        }
    }

    private static String digits(int n) {
        String d = SFX.replaceAll("[^0-9]", "");
        return (d + "0000").substring(0, n);
    }

    private Long book(String code, String catCode, String division) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", code);
        b.put("contentType", "SELF");
        b.put("price", 10_000);
        b.put("catCode", catCode);
        b.put("catName", "결함검증");
        b.put("salesDivision", division);
        b.put("grade", "3");
        b.put("taxFree", true);
        return createId("/masters/products", b);
    }

    private void school(String code, String name, String custCode, String custName,
                        String city, String loc) {
        Map<String, Object> m = new HashMap<>();
        m.put("schoolCode", code);
        m.put("schoolName", name);
        m.put("custCode", custCode);
        m.put("custName", custName);
        m.put("city", city);
        m.put("partnerLoc", loc);
        m.put("manageYn", "Y");
        m.put("cls3StudentCnt", 100);
        assertThat(post("/masters/schools", m).path("success").asBoolean()).isTrue();
    }

    // ── §1 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("‼️§1 연도별 거래처 줄에 지역·상세지역이 실린다 — 레거시 MAX(custCity)·MAX(custLoc)")
    void 연도별_거래처줄_지역() {
        JsonNode rows = data(get("/sales/attendance-yearly?year=" + YEAR
                + "&keyword=" + seoulCode)).path("rows");
        JsonNode row = null;
        for (JsonNode r : rows) {
            if ("PARTNER".equals(r.path("rowType").asText())
                    && seoulCode.equals(r.path("partnerCode").asText())) {
                row = r;
            }
        }
        assertThat(row).as("거래처 줄: %s", rows).isNotNull();
        assertThat(row.path("cityName").asText()).as("지역이 비면 안 된다").isEqualTo("서울");
        assertThat(row.path("partnerLoc").asText()).as("상세지역이 비면 안 된다").isEqualTo("강남");
    }

    // ── §2 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("‼️§2 기간별 지역은 **거래처코드**로 정해진다 — 빈칸이 없다")
    void 기간별_지역은_코드규칙() {
        JsonNode rows = data(get("/sales/attendance-period?fromDate=" + YEAR + "-01-01"
                + "&toDate=" + YEAR + "-12-31&groupBy=SCHOOL")).path("rows");
        boolean seoul = false;
        boolean etc = false;
        for (JsonNode r : rows) {
            String region = r.path("region").asText("");
            if (!"TOTAL".equals(r.path("rowType").asText(""))) {
                assertThat(region).as("지역이 빈 줄이 있으면 안 된다: %s", r).isNotEmpty();
            }
            if (seoulCode.equals(r.path("partnerCode").asText()) && "서울".equals(region)) {
                seoul = true;
            }
            if (etcCode.equals(r.path("partnerCode").asText()) && "기타".equals(region)) {
                etc = true;
            }
        }
        assertThat(seoul).as("A10… → 서울").isTrue();
        assertThat(etc).as("규칙에 없는 코드 → '기타'(마스터 칸이 비어도 빈칸이 아니다)").isTrue();
    }

    // ── §3 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("‼️§3 수량 0 매출은 **창고 없이** 등록된다 — 재고 증감이 0 이다")
    void 수량0은_창고없이_등록() {
        JsonNode res = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-05-10", "partnerId", seoulPartner,
                "items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                        "qty", 0, "unitPrice", 10_000, "supplyRate", 70,
                        "supplyAmount", 500_000))));
        assertThat(res.path("success").asBoolean())
                .as("F-17 과 모순됐다 — 수량 0 인데 창고를 요구하고 있었다: %s", res).isTrue();
    }

    @Test
    @DisplayName("수량 1 이상은 종전대로 창고가 필요하다")
    void 수량있으면_창고필요() {
        JsonNode res = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-05-11", "partnerId", seoulPartner,
                "items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                        "qty", 3, "unitPrice", 10_000, "supplyRate", 70))));
        assertThat(res.path("success").asBoolean()).as("%s", res).isFalse();
        assertThat(res.path("error").path("message").asText()).contains("창고");
    }

    // ── §4 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("‼️§4 상품년도는 **분류코드 연도**로 자동 채워진다 — 등록 시점에도")
    void 상품년도_자동채움() {
        assertThat(productRepository.findById(product).orElseThrow().getProductYear())
                .as("분류코드 M%dA01 → %d", YEAR, YEAR).isEqualTo(YEAR);
    }

    @Test
    @DisplayName("상품년도 조건이 실제로 걸린다 — 전엔 어느 연도를 걸어도 0건이었다")
    void 상품년도_필터동작() {
        assertThat(periodRows("&productYears=" + YEAR)).as("그 해").isGreaterThan(0);
        assertThat(periodRows("&productYears=1999")).as("없는 해").isZero();
    }

    @Test
    @DisplayName("분류코드 형식 검증이 **먼저** 막는다 — 엉뚱한 해로 묶일 입구가 없다")
    void 형식검증이_먼저_막는다() {
        // 상품년도는 분류코드에서 뽑으므로, 형식이 틀린 분류코드가 들어오면 엉뚱한 해가 될 수 있다.
        // 그런데 등록 자체가 막힌다 — `[영문1][연도4][영문·숫자1~3]` 검증이 이미 있다.
        // 그래서 실무상 그 입구가 없다(엔티티의 형식 검사는 2중 방어로 남겨 둔다).
        Map<String, Object> b = new HashMap<>();
        b.put("code", "FXZ" + SFX);
        b.put("name", "형식검증");
        b.put("contentType", "SELF");
        b.put("price", 10_000);
        b.put("catCode", "ZZZZ9");
        b.put("catName", "형식검증");
        b.put("salesDivision", "교재");
        JsonNode res = post("/masters/products", b);
        assertThat(res.path("success").asBoolean()).as("%s", res).isFalse();
        assertThat(res.path("error").path("message").asText()).contains("분류코드 형식");
    }

    private int periodRows(String extra) {
        JsonNode rows = data(get("/sales/attendance-period?fromDate=" + YEAR + "-01-01"
                + "&toDate=" + YEAR + "-12-31&partnerId=" + seoulPartner + extra)).path("rows");
        int n = 0;
        for (JsonNode r : rows) {
            if (!"TOTAL".equals(r.path("rowType").asText(""))) {
                n++;
            }
        }
        return n;
    }
}
