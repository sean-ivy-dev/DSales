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
 * 응시현황(연도별) 회귀 고정.
 *
 * <p>이 화면은 이름 때문에 '시험 신청·응시율'로 오해돼 한 번 잘못 만들어진 적이 있다
 * (프론트 점검에서 확인). 실물은 <b>거래처×월 수량·매출 크로스탭</b>이라 그 구조를 값으로 박아둔다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("응시현황(연도별)")
class AttendanceIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-AT" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2032;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "ATS" + SFX, "name", "인쇄", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "ATW" + SFX, "name", "물류", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of("code", "ATB" + SFX, "name", "응시도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 100, "grade", "3",
                "productType", "교재",
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 9999))));

        // 지역구분 3규칙이 갈리도록 거래처코드를 레거시 형식(숫자 5자리)으로 만든다.
        Long seoul = createId("/masters/clients",
                Map.of("code", "1" + digits(SFX, 4), "name", "서울도서", "type", "NORMAL", "cityName", "서울"));
        Long busan = createId("/masters/clients",
                Map.of("code", "2" + digits(SFX, 4), "name", "부산도서", "type", "NORMAL", "cityName", "부산"));
        Long etc = createId("/masters/clients",
                Map.of("code", "9" + digits(SFX, 4), "name", "특약점외처", "type", "NORMAL", "cityName", "본사"));

        sale(seoul, wh, book, YEAR + "-01-10", 100, "NORMAL_SHIP");
        sale(seoul, wh, book, YEAR + "-03-10", 50, "NORMAL_SHIP");
        sale(seoul, wh, book, YEAR + "-03-20", 10, "RETURN");   // 반품은 음수로 반영
        sale(busan, wh, book, YEAR + "-01-15", 30, "NORMAL_SHIP");
        sale(etc, wh, book, YEAR + "-06-01", 10, "NORMAL_SHIP");
    }

    /** 코드 뒤 n자리 숫자만 뽑아 거래처코드를 5자리로 맞춘다. */
    private static String digits(String sfx, int n) {
        String d = sfx.replaceAll("[^0-9]", "");
        return (d + "0000").substring(0, n);
    }

    private void sale(Long partner, Long wh, Long book, String date, int qty, String type) {
        post("/sales/entries", Map.of("salesDate", date, "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", type, "qty", qty))));
    }

    @Test
    @DisplayName("거래처×월 크로스탭 — 반품은 음수, 합계가 월별 합과 일치")
    void 연도별_크로스탭() {
        JsonNode rows = data(get("/sales/attendance-yearly?year=" + YEAR)).path("rows");

        JsonNode seoul = null;
        JsonNode total = null;
        for (JsonNode r : rows) {
            if ("PARTNER".equals(r.path("rowType").asText())
                    && "서울도서".equals(r.path("partnerName").asText())) {
                seoul = r;
            }
            if ("TOTAL".equals(r.path("rowType").asText())) {
                total = r;
            }
        }
        assertThat(seoul).isNotNull();
        assertThat(total).isNotNull();

        assertThat(seoul.path("monthlyQty").get(0).asLong()).as("1월").isEqualTo(100);
        assertThat(seoul.path("monthlyQty").get(2).asLong()).as("3월 = 50 − 반품10").isEqualTo(40);
        assertThat(seoul.path("totalQty").asLong()).isEqualTo(140);

        // 총계는 월별 합과 반드시 같아야 한다(레거시도 12개월 합으로 합계를 만든다).
        long monthSum = 0;
        for (JsonNode m : total.path("monthlyQty")) {
            monthSum += m.asLong();
        }
        assertThat(monthSum).isEqualTo(total.path("totalQty").asLong());
    }

    @Test
    @DisplayName("지역구분 3규칙이 레거시대로 갈린다")
    void 지역구분_규칙() {
        JsonNode rows = data(get("/sales/attendance-yearly?year=" + YEAR)).path("rows");
        String seoulGroup = null;
        String busanGroup = null;
        String etcGroup = null;
        for (JsonNode r : rows) {
            switch (r.path("partnerName").asText()) {
                case "서울도서" -> seoulGroup = r.path("regionGroup").asText();
                case "부산도서" -> busanGroup = r.path("regionGroup").asText();
                case "특약점외처" -> etcGroup = r.path("regionGroup").asText();
                default -> { }
            }
        }
        // 앞자리 '2'는 두 자리, 그 외 90001 미만은 한 자리, 90001 이상은 '특약점외'
        assertThat(seoulGroup).startsWith("특약점_1").endsWith("서울");
        assertThat(busanGroup).startsWith("특약점_2").endsWith("부산");
        assertThat(etcGroup).isEqualTo("특약점외");
    }

    @Test
    @DisplayName("학년 필터가 도서 학년으로 걸린다")
    void 학년필터() {
        assertThat(data(get("/sales/attendance-yearly?year=" + YEAR + "&grade=3")).path("rows"))
                .as("3학년 도서라 조회된다").isNotEmpty();
        // 없는 학년으로 거르면 거래처 행이 사라진다(총계만 남는다)
        JsonNode none = data(get("/sales/attendance-yearly?year=" + YEAR + "&grade=1")).path("rows");
        for (JsonNode r : none) {
            assertThat(r.path("rowType").asText()).isNotEqualTo("PARTNER");
        }
    }
}
