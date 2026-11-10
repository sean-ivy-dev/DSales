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
 * 응시현황 조회 조건 — <b>「구분」(상품 type)</b>과 <b>상품년도</b>
 * (프론트 요청 2026-11-10 §1-4 · §1-2).
 *
 * <p>둘 다 "칸은 있는데 조건이 없거나 값이 비어 있던" 것이다.
 * <ul>
 *   <li>§1-4 「구분」 = {@code products.product_type}. 칸은 V24부터 있었는데 <b>이관 추출에서
 *       빠져</b>(`1_extract.sh` 의 bookData SELECT) 값이 전부 비어 콤보가 열리지 않았다.
 *       레거시 {@code ComboBox_구분} 은 <b>[전체] · [미분류] + bookData.type 값</b>이라
 *       <b>[미분류]</b>도 골라야 한다.</li>
 *   <li>§1-2 상품년도 = {@code products.product_year}. {@code attendance-period} 에 조건이 없었다.
 *       ‼️<b>도서의 상품년도</b>이며 거래일자 연도가 아니다.</li>
 * </ul>
 *
 * <p>★「구분」 규칙은 <b>연도별·지역비율 두 곳이 같아야 한다</b> — 같은 콤보를 쓰는 화면이
 * 다른 답을 내면 담당자는 어느 쪽이 맞는지 알 수 없다. 그래서 두 경로를 한 테스트에서 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("응시현황 — 구분(상품 type)·상품년도 조건(프론트 §1-4·§1-2)")
class AttendanceFilterIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-AF" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2089;
    private static final String SCHOOL = "AFS" + SFX;

    private Long partner;
    private Long typed;      // 구분 '모의고사' · 상품년도 2089
    private Long untyped;    // 구분 없음(미분류) · 상품년도 2088

    @BeforeAll
    void seed() {
        token();
        partner = createId("/masters/clients",
                Map.of("code", "AFP" + SFX, "name", "응시조건 거래처", "type", "NORMAL"));
        Long supplier = createId("/masters/clients",
                Map.of("code", "AFI" + SFX, "name", "인쇄소", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "AFW" + SFX, "name", "물류", "type", "MAIN"));

        // ‼️지역비율은 **재학생 수가 있는 관리대상 학교**만 센다 — 학교 마스터가 없으면 0행이다.
        //   (매출의 schoolCode 만으로는 집계되지 않는다. 인원이 학교 속성이기 때문이다.)
        Map<String, Object> sch = new HashMap<>();
        sch.put("schoolCode", SCHOOL);
        sch.put("schoolName", "조건검증학교");
        sch.put("custCode", "AFP" + SFX);
        sch.put("city", "검증시");
        sch.put("schoolType", "SCHOOL");
        sch.put("manageYn", "Y");
        sch.put("cls3ClassCnt", 10);
        sch.put("cls3StudentCnt", 300);
        assertThat(post("/masters/schools", sch).path("success").asBoolean())
                .as("학교 등록").isTrue();

        // ‼️응시현황(기간별)은 **더프 모의고사만** 센다 — 분류코드가 M+A/B/C 계열이어야 한다.
        typed = book("AFA" + SFX, "M2089A01", "모의고사", YEAR);
        untyped = book("AFB" + SFX, "M2089B01", null, YEAR - 1);

        for (Long b : List.of(typed, untyped)) {
            assertThat(post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05",
                    "supplierClientId", supplier, "destinationWarehouseId", wh,
                    "items", List.of(Map.of("productId", b, "qty", 500, "unitCost", 1_000))))
                    .path("success").asBoolean()).isTrue();
            assertThat(post("/sales/entries", Map.of(
                    "salesDate", YEAR + "-04-10", "partnerId", partner, "warehouseId", wh,
                    "items", List.of(Map.of("productId", b, "shipmentType", "NORMAL_SHIP",
                            "qty", 100, "unitPrice", 10_000, "supplyRate", 70,
                            "schoolCode", SCHOOL, "schoolName", "조건검증학교"))))
                    .path("success").asBoolean()).isTrue();
        }
    }

    private Long book(String code, String catCode, String productType, int productYear) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", code);
        b.put("contentType", "SELF");
        b.put("price", 10_000);
        b.put("catCode", catCode);
        b.put("catName", "응시조건검증");
        b.put("salesDivision", "모의고사");
        b.put("grade", "3");
        b.put("taxFree", true);
        b.put("productYear", productYear);
        if (productType != null) {
            b.put("productType", productType);
        }
        return createId("/masters/products", b);
    }

    // ---------- §1-2 상품년도 ----------

    @Test
    @DisplayName("상품년도로 좁히면 그 해 상품만 남는다 — 거래일자 연도가 아니다")
    void 상품년도_필터() {
        // 둘 다 YEAR-04-10 에 팔렸지만 상품년도는 YEAR / YEAR-1 로 다르다.
        assertThat(periodQty("&productYears=" + YEAR))
                .as("상품년도 %d 만", YEAR).isEqualTo(100);
        assertThat(periodQty("&productYears=" + (YEAR - 1)))
                .as("상품년도 %d 만", YEAR - 1).isEqualTo(100);
        assertThat(periodQty("&productYears=" + YEAR + "," + (YEAR - 1)))
                .as("‼️다중선택은 합집합이어야 한다").isEqualTo(200);
        assertThat(periodQty("")).as("미지정은 전체").isEqualTo(200);
        assertThat(periodQty("&productYears=1999")).as("없는 해는 0").isZero();
    }

    @Test
    @DisplayName("‼️엑셀도 같은 조건을 받는다 — 조회와 다른 표가 나오면 안 된다")
    void 상품년도_엑셀() {
        var res = getBytes("/sales/attendance-period/export?fromDate=" + YEAR + "-01-01"
                + "&toDate=" + YEAR + "-12-31&productYears=" + YEAR);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isNotEmpty();
    }

    // ---------- §1-4 구분(상품 type) ----------

    @Test
    @DisplayName("연도별 — 구분으로 좁히고, `(미분류)` 는 구분이 빈 상품을 고른다")
    void 구분_연도별() {
        assertThat(yearlyQty("&productType=모의고사")).as("구분 지정").isEqualTo(100);
        assertThat(yearlyQty("&productType=" + "(미분류)"))
                .as("‼️[미분류] = 구분이 비어 있는 상품").isEqualTo(100);
        assertThat(yearlyQty("")).as("미지정은 전체").isEqualTo(200);
        assertThat(yearlyQty("&productType=없는구분")).as("없는 구분은 0").isZero();
    }

    @Test
    @DisplayName("‼️지역비율도 같은 규칙 — 두 화면이 같은 콤보를 쓴다")
    void 구분_지역비율() {
        assertThat(ratioBooks("&productType=모의고사")).isEqualTo(100);
        assertThat(ratioBooks("&productType=(미분류)")).isEqualTo(100);
        assertThat(ratioBooks("")).isEqualTo(200);
        assertThat(ratioBooks("&productType=없는구분")).isZero();
    }

    // ---------- helpers ----------

    /** 기간별 — 우리 학교 행의 월 합계. 다른 테스트 데이터가 섞이지 않게 학교코드로 좁힌다. */
    private int periodQty(String extra) {
        JsonNode res = get("/sales/attendance-period?fromDate=" + YEAR + "-01-01"
                + "&toDate=" + YEAR + "-12-31&partnerId=" + partner + extra);
        assertThat(res.path("success").asBoolean()).as("기간별: %s", res).isTrue();
        int sum = 0;
        for (JsonNode r : res.path("data").path("rows")) {
            if (!SCHOOL.equals(r.path("schoolCode").asText())) {
                continue;
            }
            for (JsonNode m : r.path("monthlyTotal")) {
                sum += m.asInt(0);
            }
        }
        return sum;
    }

    /** 연도별 — 우리 거래처 행의 월 수량 합. */
    private int yearlyQty(String extra) {
        JsonNode res = get("/sales/attendance-yearly?year=" + YEAR
                + "&keyword=AFP" + SFX + extra);
        assertThat(res.path("success").asBoolean()).as("연도별: %s", res).isTrue();
        int sum = 0;
        for (JsonNode r : res.path("data").path("rows")) {
            // ‼️rows 에는 거래처 행 + 지역구분 소계 + 총계가 섞여 있다. 더하면 3배가 된다.
            if (!"PARTNER".equals(r.path("rowType").asText())) {
                continue;
            }
            for (JsonNode q : r.path("monthlyQty")) {
                sum += q.asInt(0);
            }
        }
        return sum;
    }

    /** 지역비율 — 우리 학교가 든 지역 행의 부수 합. */
    private int ratioBooks(String extra) {
        JsonNode res = get("/sales/attendance-ratio?year=" + YEAR + extra);
        assertThat(res.path("success").asBoolean()).as("지역비율: %s", res).isTrue();
        int sum = 0;
        for (JsonNode r : res.path("data").path("rows")) {
            // ‼️지역 이름으로 좁힐 수 없다 — `region` 은 **거래처코드**로 만들어지고
            //   우리 코드(AFP…)는 규칙에 걸리지 않아 늘 "특약점외" 가 된다.
            //   대신 **연도 2089** 로 격리한다(부수는 그 해 매출만 센다).
            if (!"REGION".equals(r.path("rowType").asText())) {
                continue;   // TOTAL 을 함께 더하면 두 배가 된다
            }
            sum += r.path("bookCntTotal").asInt(0);
        }
        return sum;
    }
}
