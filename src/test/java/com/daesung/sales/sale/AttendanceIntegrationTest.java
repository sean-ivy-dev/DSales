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
 * (프론트 점검에서 확인). 실물은 <b>월 수량·매출 크로스탭</b>이라 그 구조를 값으로 박아둔다.
 *
 * <p>‼️<b>학교 마스터가 기준이고, 학교코드가 붙은 매출만 센다</b>(2026-11-10 레거시 대조로 정정).
 * 레거시가 학교 전체를 만들고 매출을 {@code LEFT OUTER JOIN ... ON AllSch.schCode = SD.schCode}
 * 로 붙이기 때문이다. 예전 구현은 매출(거래처)에서 시작해 <b>학교 없는 출고까지</b> 셌는데,
 * 그러면 보급률의 분자만 부풀어 분모(재학생)와 맞지 않는다.
 * 그래서 이 테스트의 매출에는 전부 학교코드를 붙이고, <b>학교 없는 매출이 빠지는 것</b>도 함께 고정한다.
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

        // ‼️응시현황은 **학교 마스터**가 기준이다 — 학교가 없으면 행 자체가 없다.
        school("ATH1" + SFX, "서울고", "1" + digits(SFX, 4), "서울도서", "서울", "강남", true, 300);
        school("ATH2" + SFX, "부산고", "2" + digits(SFX, 4), "부산도서", "부산", "해운대", true, 200);
        school("ATH3" + SFX, "본사원", "9" + digits(SFX, 4), "특약점외처", "본사", "본사", false, 0);

        sale(seoul, wh, book, YEAR + "-01-10", 100, "NORMAL_SHIP", "ATH1" + SFX);
        sale(seoul, wh, book, YEAR + "-03-10", 50, "NORMAL_SHIP", "ATH1" + SFX);
        sale(seoul, wh, book, YEAR + "-03-20", 10, "RETURN", "ATH1" + SFX);   // 반품은 음수로 반영
        sale(busan, wh, book, YEAR + "-01-15", 30, "NORMAL_SHIP", "ATH2" + SFX);
        sale(etc, wh, book, YEAR + "-06-01", 10, "NORMAL_SHIP", "ATH3" + SFX);

        // ★학교코드 없는 출고 — 응시현황에 **들어가면 안 된다**(레거시 조인에서 떨어진다).
        sale(seoul, wh, book, YEAR + "-05-10", 777, "NORMAL_SHIP", null);
    }

    private void school(String code, String name, String custCode, String custName,
                        String city, String loc, boolean isSchool, int students) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("schoolCode", code);
        m.put("schoolName", name);
        m.put("custCode", custCode);
        m.put("custName", custName);
        m.put("city", city);
        m.put("partnerLoc", loc);
        m.put("isSchool", isSchool);
        m.put("schoolType", isSchool ? "SCHOOL" : "HAKWON");
        m.put("manageYn", "Y");
        if (students > 0) {
            m.put("cls3ClassCnt", 10);
            m.put("cls3StudentCnt", students);
        }
        assertThat(post("/masters/schools", m).path("success").asBoolean())
                .as("학교 등록 %s", code).isTrue();
    }

    /** 코드 뒤 n자리 숫자만 뽑아 거래처코드를 5자리로 맞춘다. */
    private static String digits(String sfx, int n) {
        String d = sfx.replaceAll("[^0-9]", "");
        return (d + "0000").substring(0, n);
    }

    private void sale(Long partner, Long wh, Long book, String date, int qty, String type,
                      String schoolCode) {
        java.util.Map<String, Object> item = new java.util.HashMap<>();
        item.put("productId", book);
        item.put("shipmentType", type);
        item.put("qty", qty);
        if (schoolCode != null) {
            item.put("schoolCode", schoolCode);
        }
        post("/sales/entries", Map.of("salesDate", date, "partnerId", partner, "warehouseId", wh,
                "items", List.of(item)));
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
    @DisplayName("학년 필터가 도서 학년으로 걸린다 — 행은 남고 수량만 0이 된다")
    void 학년필터() {
        assertThat(data(get("/sales/attendance-yearly?year=" + YEAR + "&grade=3")).path("rows"))
                .as("3학년 도서라 조회된다").isNotEmpty();

        // ‼️없는 학년으로 걸러도 **거래처 행은 사라지지 않는다** — 학교 마스터가 기준이라
        //   매출이 0인 거래처·학교도 행이 나온다(레거시 LEFT OUTER JOIN 과 같다).
        //   예전 구현은 매출에서 시작했기 때문에 행이 통째로 사라졌고, 그러면 "안 판 학교"를
        //   볼 수 없어 보급률 화면의 목적이 깨진다.
        JsonNode none = data(get("/sales/attendance-yearly?year=" + YEAR + "&grade=1")).path("rows");
        JsonNode seoul = rowOf(none, "PARTNER", "서울도서");
        assertThat(seoul).as("1학년으로 걸러도 거래처 행은 남는다").isNotNull();
        assertThat(seoul.path("totalQty").asLong()).as("1학년 도서 매출이 없으니 0").isZero();
        assertThat(seoul.path("schoolCount").asLong()).as("학교 수는 학년과 무관하다").isEqualTo(1);
    }

    @Test
    @DisplayName("‼️학교코드 없는 출고는 응시현황에 안 잡힌다 — 레거시 조인에서 떨어진다")
    void 학교없는매출_제외() {
        JsonNode rows = data(get("/sales/attendance-yearly?year=" + YEAR)).path("rows");
        JsonNode seoul = rowOf(rows, "PARTNER", "서울도서");
        assertThat(seoul).isNotNull();
        // 학교 붙은 매출만: 1월 100 + 3월(50−10) = 140. 학교 없는 777부는 들어가면 안 된다.
        assertThat(seoul.path("totalQty").asLong())
                .as("777부(학교코드 없음)가 섞이면 917이 된다").isEqualTo(140);
    }

    @Test
    @DisplayName("학교 단위 보기 — 학교 행 + 거래처 소계 + 지역 소계 + 총계")
    void 학교단위() {
        JsonNode rows = data(get("/sales/attendance-yearly?year=" + YEAR + "&groupBy=SCHOOL")).path("rows");
        JsonNode school = null;
        boolean partnerSub = false;
        for (JsonNode r : rows) {
            if ("SCHOOL".equals(r.path("rowType").asText())
                    && ("ATH1" + SFX).equals(r.path("schoolCode").asText())) {
                school = r;
            }
            if ("PARTNER_SUBTOTAL".equals(r.path("rowType").asText())) {
                partnerSub = true;
            }
        }
        assertThat(school).as("학교 행: %s", rows).isNotNull();
        assertThat(school.path("schoolName").asText()).isEqualTo("서울고");
        assertThat(school.path("partnerLoc").asText()).as("상세지역(custLoc)").isEqualTo("강남");
        assertThat(school.path("studentCnt").asLong()).as("재학생 수").isEqualTo(300);
        assertThat(school.path("seq").asInt()).as("거래처 안 순번").isEqualTo(1);
        assertThat(school.path("totalQty").asLong()).isEqualTo(140);
        assertThat(partnerSub).as("거래처 소계 줄이 있어야 한다").isTrue();
    }

    @Test
    @DisplayName("학교/학원 조건 — 레거시는 isSchool 로 가른다")
    void 학교학원_조건() {
        JsonNode onlySchool = data(get("/sales/attendance-yearly?year=" + YEAR
                + "&schoolType=SCHOOL")).path("rows");
        assertThat(rowOf(onlySchool, "PARTNER", "서울도서")).isNotNull();
        assertThat(rowOf(onlySchool, "PARTNER", "특약점외처")).as("학원은 빠진다").isNull();

        JsonNode onlyHakwon = data(get("/sales/attendance-yearly?year=" + YEAR
                + "&schoolType=HAKWON")).path("rows");
        assertThat(rowOf(onlyHakwon, "PARTNER", "특약점외처")).isNotNull();
        assertThat(rowOf(onlyHakwon, "PARTNER", "서울도서")).as("학교는 빠진다").isNull();
    }

    @Test
    @DisplayName("거래처 조건(custCode) — 그 거래처만 남는다")
    void 거래처_조건() {
        JsonNode rows = data(get("/sales/attendance-yearly?year=" + YEAR
                + "&custCode=1" + digits(SFX, 4))).path("rows");
        assertThat(rowOf(rows, "PARTNER", "서울도서")).isNotNull();
        assertThat(rowOf(rows, "PARTNER", "부산도서")).isNull();
    }

    @Test
    @DisplayName("‼️모르는 조회구분은 400 — 조용히 기본값으로 답하면 안 된다")
    void 조회구분_오타는_400() {
        JsonNode r = get("/sales/attendance-yearly?year=" + YEAR + "&groupBy=SCHOOLS");
        assertThat(r.path("success").asBoolean()).as("%s", r).isFalse();
    }

    private static JsonNode rowOf(JsonNode rows, String rowType, String partnerName) {
        for (JsonNode r : rows) {
            if (rowType.equals(r.path("rowType").asText())
                    && partnerName.equals(r.path("partnerName").asText())) {
                return r;
            }
        }
        return null;
    }
}
