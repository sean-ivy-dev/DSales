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
 * 응시현황 <b>지역비율(보급률)</b> — 레거시 식 그대로(F-22·F-23).
 *
 * <p>레거시 {@code 응시현황.vb:495~590} 로 확정한 세 칸의 뜻을 못박는다.
 * <ul>
 *   <li><b>인원 = 학교 재학생 수</b>({@code schools.clsN_student_cnt}) — 매출 수량이 아니다</li>
 *   <li><b>매출 = 판매 부수</b>({@code sum(qty)}) — 금액이 아니다(레거시에서 금액 줄은 주석)</li>
 *   <li><b>비율 = 부수 ÷ 재학생 × 100</b> = 보급률 %, 재학생 0이면 <b>빈 칸</b></li>
 * </ul>
 *
 * <p>★<b>이 테스트가 막는 것</b> — 누군가 '매출'을 금액으로 바꾸거나 '인원'을 매출 수량으로
 * 되돌리면 숫자가 전혀 달라진다. 이름만 보면 그렇게 고치는 것이 자연스러워 보여서 위험하다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("응시현황 지역비율 — 인원=재학생·매출=부수·비율=보급률(F-22)")
class AttendanceRatioIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-AR" + (System.nanoTime() % 1_000_000L);
    /** 다른 테스트와 겹치지 않는 연도. 지역비율은 전역 집계라 연도로 격리한다. */
    private static final int YEAR = 2093;
    private static final String DATE = YEAR + "-05-20";

    /** 레거시 지역구분 규칙이 '특약점_3' 묶음이 되도록 3으로 시작하는 거래처코드를 쓴다. */
    private static final String CUST = "39" + (System.nanoTime() % 1000L);

    /**
     * ‼️한글 파라미터를 <b>직접 인코딩하지 않는다.</b> {@code TestRestTemplate} 이 이미 인코딩하므로
     * {@code URLEncoder} 로 미리 바꾸면 이중 인코딩이 되어 <b>필터가 조용히 0건</b>이 된다
     * (다른 테스트도 {@code &divisions=교재} 처럼 한글을 그대로 넣는다).
     */
    private static final String RATIO_PATH =
            "/sales/attendance-ratio?year=" + YEAR + "&clientCategory=특약점" + SFX;

    private Long partner;
    private Long warehouse;
    private Long grade3Book;
    private String schoolCode;

    @BeforeAll
    void seed() {
        token();
        partner = createId("/masters/clients",
                Map.of("code", CUST, "name", "비율검증 특약점", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "ARW" + SFX, "name", "창고", "type", "MAIN"));

        // 3학년 도서 — 학년은 **도서**에 붙는다(레거시도 bookData.grade)
        Map<String, Object> b = new HashMap<>();
        b.put("code", "ARB" + SFX);
        b.put("name", "비율검증 3학년 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2093A01");
        b.put("catName", "비율검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        b.put("grade", "3");
        grade3Book = createId("/masters/products", b);

        // 학교 — 재학생 3학년 200명
        schoolCode = "AR" + (System.nanoTime() % 100000L);
        Map<String, Object> s = new HashMap<>();
        s.put("schoolCode", schoolCode);
        s.put("custCode", CUST);
        s.put("custName", "비율검증 특약점");
        s.put("city", "비율시");
        s.put("schoolName", "비율고등학교");
        s.put("clientCategory", "특약점" + SFX);   // ★이 값으로 조회를 좁혀 전역 오염을 피한다
        s.put("cls3ClassCnt", 8);
        s.put("cls3StudentCnt", 200);
        JsonNode created = post("/masters/schools", s);
        assertThat(created.path("success").asBoolean()).as("학교 등록: %s", created).isTrue();
        // ‼️등록 응답에 재학생 수가 돌아와야 한다 — 안 실리면 지역비율이 통째로 빈다.
        assertThat(created.path("data").path("cls3StudentCnt").asLong())
                .as("등록 응답의 3학년 재학생: %s", created).isEqualTo(200L);

        // 입고 → 3학년 교재 50부 판매
        Long supplier = createId("/masters/clients",
                Map.of("code", "ARS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", grade3Book, "qty", 500, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();
        assertThat(post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", grade3Book, "shipmentType", "NORMAL_SHIP",
                        "qty", 50, "unitPrice", 10000, "supplyRate", 70,
                        "schoolCode", schoolCode))))
                .path("success").asBoolean()).isTrue();
    }

    private JsonNode myRow() {
        JsonNode res = get(RATIO_PATH);
        assertThat(res.path("success").asBoolean()).as("조회: %s", res).isTrue();
        for (JsonNode r : res.path("data").path("rows")) {
            if ("REGION".equals(r.path("rowType").asText())) {
                return r;
            }
        }
        throw new AssertionError("지역 행이 없다: " + res);
    }

    @Test
    @DisplayName("‼️인원은 **재학생 수**(200) — 매출 수량(50)이 아니다")
    void 인원은_재학생수() {
        JsonNode r = myRow();
        assertThat(r.path("studentCnt3").asLong())
                .as("3학년 재학생: %s", r).isEqualTo(200L);
        assertThat(r.path("studentCntTotal").asLong()).isEqualTo(200L);
        // 1·2학년은 자료를 넣지 않았으니 0이다 — 그리고 ‼️1학년 값이 3학년 칸에 복사되지 않는다
        assertThat(r.path("studentCnt1").asLong()).isZero();
        assertThat(r.path("studentCnt2").asLong()).isZero();
    }

    @Test
    @DisplayName("‼️매출은 **부수**(50) — 금액(350,000)이 아니다")
    void 매출은_부수() {
        JsonNode r = myRow();
        assertThat(r.path("bookCnt3").asLong()).as("3학년 부수: %s", r).isEqualTo(50L);
        assertThat(r.path("bookCntTotal").asLong()).isEqualTo(50L);
        // 금액이 흘러들면 바로 드러난다(50 × 10,000 × 70% = 350,000)
        assertThat(r.path("bookCnt3").asLong()).isNotEqualTo(350_000L);
    }

    @Test
    @DisplayName("비율 = 부수 ÷ 재학생 × 100 — 50/200 = **25.0%**")
    void 비율은_보급률() {
        JsonNode r = myRow();
        assertThat(r.path("ratio3").asDouble()).isEqualTo(25.0);
        assertThat(r.path("ratioTotal").asDouble()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("‼️재학생 0인 학년은 비율이 **빈 칸** — 0으로 나누지 않는다")
    void 인원0이면_비율없음() {
        JsonNode r = myRow();
        // Jackson NON_NULL 이라 null 필드는 **키 자체가 없다** — has() 로 본다.
        assertThat(r.has("ratio1")).as("1학년 재학생 0 → 비율 없음: %s", r).isFalse();
        assertThat(r.has("ratio2")).isFalse();
    }

    @Test
    @DisplayName("지역 표시와 합계행 — 레거시 규칙(도시명 / '합계')")
    void 지역표시와_합계() {
        JsonNode res = get(RATIO_PATH);
        JsonNode rows = res.path("data").path("rows");
        assertThat(myRow().path("region").asText()).isEqualTo("비율시");
        assertThat(myRow().path("regionGroup").asText()).startsWith("특약점_3");
        // 합계행이 맨 끝에 하나 온다
        JsonNode last = rows.get(rows.size() - 1);
        assertThat(last.path("rowType").asText()).isEqualTo("TOTAL");
        assertThat(last.path("region").asText()).isEqualTo("합계");
        assertThat(last.path("studentCntTotal").asLong()).isEqualTo(200L);
        // 모집단을 알 수 있어야 한다
        assertThat(myRow().path("schoolCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("‼️관리대상이 아닌 학교(manageYn=N)는 **집계에서 뺀다** — 레거시가 거르는 조건이다")
    void 관리대상아닌학교_제외() {
        String off = "AN" + (System.nanoTime() % 100000L);
        Map<String, Object> s = new HashMap<>();
        s.put("schoolCode", off);
        s.put("custCode", CUST);
        s.put("city", "비율시");
        s.put("schoolName", "관리대상아닌고등학교");
        s.put("clientCategory", "특약점" + SFX);
        s.put("cls3ClassCnt", 10);
        s.put("cls3StudentCnt", 999);      // 섞이면 인원이 1,199 가 된다
        s.put("manageYn", "N");            // ★레거시가 거르는 칸
        JsonNode created = post("/masters/schools", s);
        assertThat(created.path("success").asBoolean()).as("등록: %s", created).isTrue();
        assertThat(created.path("data").path("manageYn").asText()).isEqualTo("N");

        JsonNode r = myRow();
        assertThat(r.path("studentCnt3").asLong())
                .as("manageYn=N 학교가 섞이면 안 된다(999 가 더해지면 1,199): %s", r).isEqualTo(200L);
        assertThat(r.path("schoolCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("‼️같은 학교코드가 거래처별로 여러 행이어도 **한 번만** 센다")
    void 학교코드_중복은_한번만() {
        // 같은 학교코드를 **다른 거래처**로 한 번 더 등록한다(schools 는 거래처×학교다).
        Map<String, Object> s = new HashMap<>();
        s.put("schoolCode", schoolCode);                 // 같은 학교
        s.put("custCode", CUST + "9");                   // 다른 거래처
        s.put("custName", "비율검증 특약점2");
        s.put("city", "비율시");
        s.put("schoolName", "비율고등학교");
        s.put("clientCategory", "특약점" + SFX);
        s.put("cls3ClassCnt", 8);
        s.put("cls3StudentCnt", 200);
        assertThat(post("/masters/schools", s).path("success").asBoolean())
                .as("거래처×학교 조합이 다르면 등록된다").isTrue();

        JsonNode r = myRow();
        assertThat(r.path("studentCnt3").asLong())
                .as("두 번 세면 400 이 된다 — 재학생은 학교의 속성이다: %s", r).isEqualTo(200L);
        assertThat(r.path("ratio3").asDouble()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("재학생 자료가 없는 학교는 **집계에 들지 않는다** — 분모가 커지면 비율이 작아진다")
    void 자료없는학교는_제외() {
        String bare = "AZ" + (System.nanoTime() % 100000L);
        Map<String, Object> s = new HashMap<>();
        s.put("schoolCode", bare);
        s.put("custCode", CUST);
        s.put("city", "비율시");
        s.put("schoolName", "자료없는고등학교");
        s.put("clientCategory", "특약점" + SFX);
        assertThat(post("/masters/schools", s).path("success").asBoolean()).isTrue();

        JsonNode r = myRow();
        assertThat(r.path("schoolCount").asInt())
                .as("재학생 칸이 빈 학교가 모집단에 들어가면 안 된다: %s", r).isEqualTo(1);
        assertThat(r.path("ratio3").asDouble()).isEqualTo(25.0);
    }
}
