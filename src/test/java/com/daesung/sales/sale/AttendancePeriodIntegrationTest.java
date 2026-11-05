package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 응시현황(기간별, 18p) 회귀 고정.
 *
 * <p>★이 테스트가 지키려는 것은 <b>레거시가 죽은 방식</b>이다.
 * {@code 고사별처리인원.vb}는 모의고사 판별을 연도가 박힌 분류코드({@code catCode in ('M22A','M22B')})로 하고
 * 월·학년·영역을 SQL에 하드코딩한 뒤 <b>연도마다 복붙</b>했다. 2022년 이후 분기를 아무도 쓰지 않아
 * "2022년 까지만 조회 가능합니다"라는 안내와 함께 화면이 멈췄다.
 * 그래서 여기서는 <b>해를 넘기는 기간</b>과 <b>분류코드가 제각각인 도서</b>로 검증한다 —
 * 연도에 의존하는 코드가 다시 생기면 이 테스트가 먼저 깨진다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("응시현황(기간별) 18p")
class AttendancePeriodIntegrationTest extends IntegrationTestSupport {

    private final String sfx = String.valueOf(System.nanoTime()).substring(9);

    private Long partner;
    private Long wh;

    @BeforeAll
    void seed() {
        token();
        wh = createId("/masters/warehouses", Map.of("code", "APW" + sfx, "name", "응시창고", "type", "MAIN"));
        partner = createId("/masters/clients", Map.of(
                "code", "APC" + sfx, "name", "응시특약점", "type", "NORMAL", "region", "서울"));

        // 모의고사 도서 둘 — ‼️분류코드를 일부러 다르게 준다(연도 인코딩에 기대지 않는다는 증거).
        long mock1 = mockBook("APM1" + sfx, "M2036A01");
        long mock2 = mockBook("APM2" + sfx, "M2037B99");
        // 모의고사가 아닌 도서(교재) — 집계에서 빠져야 한다
        long book = product("APB" + sfx, "H2036H01", "교재");
        // ‼️모의고사이지만 더프가 아닌 계열(M…D) — 대분류로 걸렀다면 섞여 들어왔을 건이다
        long nonDuff = mockBook("APN" + sfx, "M2036D01");

        // ‼️연도 선택 주의: 대시보드 테스트가 2030~2032년 목표와 그 전년 실적을 전역 집계로 검증한다.
        //   그 근처에 매출을 만들면 남의 기대값이 깨진다(실제로 한 번 깨뜨렸다).
        //   2036~2037은 어느 테스트도 쓰지 않는 구간이다. 해를 넘기는 기간인 것이 이 테스트의 핵심이다.
        sale("2036-11-05", mock1, 100, "GRADED");
        sale("2036-11-05", mock1, 30, null);          // 미지정 = 비처리
        sale("2037-03-10", mock2, 50, "GRADED");
        sale("2037-03-10", book, 999, "GRADED");      // 교재 → 대상 아님
        sale("2037-03-10", nonDuff, 777, "GRADED");   // 비더프 모의고사 → 대상 아님
    }

    @Test
    @DisplayName("해를 넘기는 기간도 월 칸이 만들어진다 — 레거시가 막혔던 지점")
    void 해를_넘기는_기간() {
        JsonNode res = data(get(url("2036-11-01", "2037-03-31")));
        // 2036-11 ~ 2037-03 = 5개월
        assertThat(res.path("months")).hasSize(5);
        assertThat(res.path("months").get(0).asText()).isEqualTo("2036-11");
        assertThat(res.path("months").get(4).asText()).isEqualTo("2037-03");
    }

    @Test
    @DisplayName("처리/비처리/계가 월 칸에 정확히 들어가고, 모의고사만 집계된다")
    void 처리_비처리_집계() {
        JsonNode res = data(get(url("2036-11-01", "2037-03-31")));
        JsonNode total = totalRow(res);

        // 2036-11: 처리 100 · 비처리 30 / 2037-03: 처리 50
        assertThat(total.path("monthlyGraded").get(0).asLong()).isEqualTo(100);
        assertThat(total.path("monthlyUngraded").get(0).asLong()).isEqualTo(30);
        assertThat(total.path("monthlyTotal").get(0).asLong()).isEqualTo(130);
        assertThat(total.path("monthlyGraded").get(4).asLong()).isEqualTo(50);

        // 교재 999도, 더프 아닌 모의고사 777도 섞이지 않는다.
        // 더프 판별은 분류코드 M+A/B/C 계열(레거시 그대로) — 대분류로 걸렀다면 777이 섞였을 것이다.
        assertThat(total.path("gradedTotal").asLong()).isEqualTo(150);
        assertThat(total.path("ungradedTotal").asLong()).isEqualTo(30);
        assertThat(total.path("total").asLong()).isEqualTo(180);
    }

    @Test
    @DisplayName("소계가 학교 → 특약점 → 지역 → 총계 순으로 붙는다")
    void rollup_구성() {
        JsonNode res = data(get(url("2036-11-01", "2037-03-31")));
        List<String> types = new java.util.ArrayList<>();
        res.path("rows").forEach(r -> types.add(r.path("rowType").asText()));

        assertThat(types).contains("SCHOOL", "PARTNER_SUBTOTAL", "REGION_SUBTOTAL", "TOTAL");
        assertThat(types.get(types.size() - 1)).isEqualTo("TOTAL");
        // 총계는 정확히 한 번만
        assertThat(types.stream().filter("TOTAL"::equals).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("엑셀: 월 컬럼이 기간에 따라 늘고 준다(고정 열이 아니다)")
    void 엑셀_월컬럼_동적() throws Exception {
        assertThat(monthColumnCount("2036-11-01", "2036-11-30")).isEqualTo(1);
        assertThat(monthColumnCount("2036-11-01", "2037-03-31")).isEqualTo(5);
    }

    /** 월 칸 헤더 형식 yyyy-MM[처리]. ‼️'합계[처리]'와 구분해야 한다(둘 다 [처리]로 끝난다). */
    private static final java.util.regex.Pattern MONTH_HEADER =
            java.util.regex.Pattern.compile("^\\d{4}-\\d{2}\\[처리]$");

    /** xlsx 헤더에서 월 칸 수를 센다. */
    private int monthColumnCount(String from, String to) throws Exception {
        byte[] xlsx = getBytes("/sales/attendance-period/export?fromDate=" + from + "&toDate=" + to)
                .getBody();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(xlsx))) {
            // ‼️0행을 헤더로 보면 안 된다 — 제목·조회기준 2행이 붙으면(2026-11-05 F-19 대응으로
            //   응시현황에도 붙였다) 헤더가 아래로 밀린다. 칸이 2개 이상 채워진 첫 행이 헤더다.
            var sheet = wb.getSheetAt(0);
            org.apache.poi.ss.usermodel.Row header = null;
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                int filled = 0;
                for (int i = 0; i < row.getLastCellNum(); i++) {
                    if (row.getCell(i) != null) {
                        filled++;
                    }
                }
                if (filled > 1) {
                    header = row;
                    break;
                }
            }
            assertThat(header).as("헤더 행을 찾지 못했다").isNotNull();
            int n = 0;
            for (int i = 0; i < header.getLastCellNum(); i++) {
                var cell = header.getCell(i);
                if (cell != null && MONTH_HEADER.matcher(cell.getStringCellValue()).matches()) {
                    n++;
                }
            }
            return n;
        }
    }

    private String url(String from, String to) {
        return "/sales/attendance-period?fromDate=" + from + "&toDate=" + to
                + "&partnerId=" + partner;
    }

    private JsonNode totalRow(JsonNode res) {
        for (JsonNode r : res.path("rows")) {
            if ("TOTAL".equals(r.path("rowType").asText())) {
                return r;
            }
        }
        throw new AssertionError("총계 행이 없다: " + res);
    }

    private long mockBook(String code, String catCode) {
        return product(code, catCode, "모의고사");
    }

    private long product(String code, String catCode, String division) {
        long id = createId("/masters/products", Map.of(
                "code", code, "name", code, "contentType", "SELF", "set", false,
                "price", 10000, "taxFree", true, "catCode", catCode, "catName", catCode,
                "salesDivision", division,
                // 모의고사는 인원 기반이라 재고를 안 탄다(V18 stockManaged=false와 같은 취급)
                "stockManaged", false));
        return id;
    }

    private void sale(String date, long productId, int qty, String procType) {
        Map<String, Object> item = new java.util.HashMap<>();
        item.put("productId", productId);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 75);
        item.put("qty", qty);
        item.put("schoolCode", "S" + sfx);
        item.put("schoolName", "응시고등학교");
        if (procType != null) {
            item.put("procType", procType);
        }
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", wh,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출등록 성공: %s", r).isTrue();
    }
}
