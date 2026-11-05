package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 매출 리포트 6종 통합테스트(수동 E2E를 자동 회귀 검증기로 고정).
 * 완전 빈 DB(Testcontainers)에 시드 → 각 리포트 호출 → 검증한 숫자를 assert.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("매출 리포트 통합테스트")
class SalesReportIntegrationTest extends IntegrationTestSupport {

    private Long partnerId;
    private Long whMain;
    private Long pA011;
    private Long pA012;
    private Long pA021;
    private Long pB011;

    @BeforeAll
    void seed() {
        token();
        // 창고(MAIN)
        Long whId = createId("/masters/warehouses",
                Map.of("code", "WH-MAIN", "name", "메인창고", "type", "MAIN"));
        whMain = whId;
        // 거래처(공급·판매 겸용).
        // ★사업자번호를 반드시 넣는다 — 38p 월별신고가 사업자번호 없는 거래처를 '미발행분'으로
        //   따로 센다. 비워 두면 이 클래스의 매출이 전부 미발행 버킷으로 가 계산서/세금계산서가 0이 된다.
        partnerId = createId("/masters/clients",
                Map.of("code", "CUST-1", "name", "테스트거래처", "type", "NORMAL",
                        "bizNo", "211-11-11111"));
        // 상품(catCode): 대분류 A(A2026A01×2, A2026A02), B(B2026B01)
        // 분류코드는 [영문1자][연도4자][영문·숫자1~3자] 형식이어야 한다(실데이터 규칙).
        pA011 = product("BK-A011", "A2026A01", "국어모의");
        pA012 = product("BK-A012", "A2026A01", "국어모의");
        pA021 = product("BK-A021", "A2026A02", "수학모의");
        pB011 = product("BK-B011", "B2026B01", "교재");
        // 입고 1000씩 @원가 3000
        for (Long pid : List.of(pA011, pA012, pA021, pB011)) {
            inbound(whId, pid);
        }
        // 당해(2026-06) 매출
        sale("2026-06-15", whId, pA011, "NORMAL_SHIP", 50, 10);
        sale("2026-06-15", whId, pA012, "NORMAL_SHIP", 50, 20);
        sale("2026-06-15", whId, pA021, "NORMAL_SHIP", 50, 5);
        sale("2026-06-15", whId, pB011, "NORMAL_SHIP", 50, 4);
        sale("2026-06-15", whId, pA011, "TEACHER_USE", 0, 3);  // 무가(교사용)
        sale("2026-06-20", whId, pA011, "RETURN", 50, 2);      // 반품
        // 전년(2025-06) 매출
        sale("2025-06-15", whId, pA011, "NORMAL_SHIP", 50, 8);
        sale("2025-06-15", whId, pA012, "NORMAL_SHIP", 50, 25);
    }

    private Long product(String code, String catCode, String catName) {
        // ‼️Map.of 는 10쌍이 한계다 — 세부구분(필수)까지 11쌍이라 HashMap 을 쓴다.
        Map<String, Object> b = new java.util.HashMap<>();
        b.put("code", code); b.put("name", code + " 도서"); b.put("contentType", "SELF");
        b.put("set", false); b.put("price", 10000); b.put("taxFree", false); b.put("grade", "고3");
        b.put("catCode", catCode); b.put("catName", catName); b.put("useYn", true);
        b.put("salesDivision", "교재");
        return createId("/masters/products", b);
    }

    private void inbound(Long whId, Long productId) {
        post("/stock/inbound", Map.of(
                "processedDate", "2026-06-01", "supplierClientId", partnerId, "destinationWarehouseId", whId,
                "items", List.of(Map.of("productId", productId, "unitCost", 3000, "qty", 1000))));
    }

    /** 입고구분 지정 입고(매입입고 필터 검증용). */
    private void inbound(Long whId, Long productId, String inboundType, int unitCost, int qty) {
        post("/stock/inbound", Map.of(
                "processedDate", "2026-06-01", "supplierClientId", partnerId, "destinationWarehouseId", whId,
                "inboundType", inboundType,
                "items", List.of(Map.of("productId", productId, "unitCost", unitCost, "qty", qty))));
    }

    private Long externalProduct(String code) {
        Map<String, Object> b = new java.util.HashMap<>();
        b.put("code", code); b.put("name", code + " 외부콘텐츠"); b.put("contentType", "EXTERNAL");
        b.put("set", false); b.put("price", 10000); b.put("taxFree", false); b.put("grade", "고3");
        b.put("catCode", "E2026E01"); b.put("catName", "이감국어"); b.put("useYn", true);
        b.put("salesDivision", "교재");
        return createId("/masters/products", b);
    }

    /**
     * 매출 등록. 세액은 <b>자동산출되지 않으므로</b>(발주처 확정) 명시적으로 넣는다 —
     * 세무 리포트들이 세액이 제대로 흘러가는지를 보기 때문에, 입력한 값으로 검증한다.
     * 학교명도 함께 넣는다(거래명세서 실물 양식의 "학교(원)" 칸 원천).
     */
    private void sale(String date, Long whId, Long productId, String shipmentType, int rate, int qty) {
        Map<String, Object> item = new java.util.HashMap<>();
        item.put("productId", productId);
        item.put("shipmentType", shipmentType);
        item.put("unitPrice", 10000);
        item.put("supplyRate", rate);
        item.put("qty", qty);
        item.put("tax", taxOf(rate, qty));
        item.put("schoolName", "엔컴잇올스파르타학원");
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", date, "partnerId", partnerId, "warehouseId", whId,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출등록 성공: %s", r).isTrue();
    }

    /** 공급가액(정가 10000 기준)의 10%. 담당자가 입력하는 세액을 테스트에서 재현. */
    private static int taxOf(int rate, int qty) {
        return (int) ((long) (10000L * rate / 100 * qty) / 10);
    }

    /** 성적처리 구분 포함 매출 등록(37p 검증용). 세액은 명시 입력. */
    private void saleP(String date, Long whId, Long productId, String shipmentType, int rate, int qty, String procType) {
        Map<String, Object> item = new java.util.HashMap<>();
        item.put("productId", productId);
        item.put("shipmentType", shipmentType);
        item.put("unitPrice", 10000);
        item.put("supplyRate", rate);
        item.put("qty", qty);
        item.put("tax", taxOf(rate, qty));
        item.put("procType", procType);
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", date, "partnerId", partnerId, "warehouseId", whId,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출등록 성공: %s", r).isTrue();
    }

    private JsonNode rowWhere(JsonNode rows, String field, String value) {
        for (JsonNode r : rows) {
            if (value.equals(r.path(field).asText())) {
                return r;
            }
        }
        throw new AssertionError(field + "=" + value + " 행 없음: " + rows);
    }

    @Test
    @DisplayName("매출액명세서 — 분류 rollup + 합계=금액+세액")
    void 매출액명세서() {
        JsonNode d = data(get("/sales/statement?fromDate=2026-06-01&toDate=2026-06-30&kind=SALE"));
        JsonNode rows = d.path("rows");
        JsonNode a01 = rowWhere(rows, "rowType", "CAT_SUBTOTAL");  // 첫 소계 = A01
        assertThat(a01.path("catCode").asText()).isEqualTo("A2026A01");
        assertThat(a01.path("qty").asLong()).isEqualTo(30);        // 10+20
        assertThat(a01.path("amount").asLong()).isEqualTo(150_000);
        JsonNode grand = rowWhere(rows, "rowType", "GRAND_TOTAL");
        assertThat(grand.path("qty").asLong()).isEqualTo(39);      // 10+20+5+4
        assertThat(grand.path("amount").asLong()).isEqualTo(195_000);
        assertThat(grand.path("tax").asLong()).isEqualTo(19_500);  // 과세 10%
        assertThat(grand.path("total").asLong()).isEqualTo(214_500); // 금액+세액
    }

    @Test
    @DisplayName("매출액명세서 구분 5종(15p) — 교사용·증정용이 '무상'으로 뭉치지 않는다")
    void 매출액명세서_구분5종() {
        String range = "/sales/statement?fromDate=2026-06-01&toDate=2026-06-30";

        // 시드: 매출 39(10+20+5+4) · 교사용 3 · 반품 2
        assertThat(grandQty(range)).as("전체").isEqualTo(39 + 3 + 2);
        assertThat(grandQty(range + "&kind=SALE")).isEqualTo(39);
        assertThat(grandQty(range + "&kind=RETURN")).isEqualTo(2);
        assertThat(grandQty(range + "&kind=TEACHER_USE")).as("교사용만").isEqualTo(3);
        // ‼️증정용은 교사용과 같은 회계구분(무상)이다. 회계구분으로만 걸렀다면 3이 나왔을 것 —
        //   정본 15p가 둘을 나눠 보라고 한 이유가 이것이다.
        assertThat(grandQty(range + "&kind=GIFT")).as("증정용은 따로").isZero();

        // 응답이 무엇으로 걸렀는지 알려준다
        JsonNode d = data(get(range + "&kind=TEACHER_USE"));
        assertThat(d.path("kindName").asText()).isEqualTo("교사용");
    }

    @Test
    @DisplayName("매출액명세서 매출유형 필터(15p) — 일반매출/위탁매출")
    void 매출액명세서_매출유형() {
        String range = "/sales/statement?fromDate=2026-06-01&toDate=2026-06-30";

        // 시드는 전부 일반매출이다 → 일반=전체, 위탁=0
        assertThat(grandQty(range + "&salesType=NORMAL_SALES")).isEqualTo(grandQty(range));
        assertThat(grandQty(range + "&salesType=CONSIGN_SALES")).isZero();

        // 구분과 함께 걸면 교집합
        assertThat(grandQty(range + "&kind=SALE&salesType=NORMAL_SALES")).isEqualTo(39);
        assertThat(grandQty(range + "&kind=SALE&salesType=CONSIGN_SALES")).isZero();
    }

    /** 명세서 총계 수량. 총계 행이 없으면(빈 결과) 0. */
    private long grandQty(String url) {
        for (JsonNode r : data(get(url)).path("rows")) {
            if ("GRAND_TOTAL".equals(r.path("rowType").asText())) {
                return r.path("qty").asLong();
            }
        }
        return 0L;
    }

    @Test
    @DisplayName("거래명세서 — 공급자/공급받는자 + 유가·무가 분리 + 실물 양식 항목(학교·전체수량·전화)")
    void 거래명세서() {
        JsonNode d = data(get("/sales/transaction-statement?partnerId=" + partnerId
                + "&fromDate=2026-06-01&toDate=2026-06-30"));
        assertThat(d.path("provider").path("name").asText()).isEqualTo("(주)대성테스트");
        assertThat(d.path("receiver").path("code").asText()).isEqualTo("CUST-1");
        assertThat(d.path("pricedLines")).hasSize(4);
        assertThat(d.path("freeLines")).hasSize(1);               // 교사용
        JsonNode t = d.path("totals");
        assertThat(t.path("supplyAmount").asLong()).isEqualTo(195_000);
        assertThat(t.path("total").asLong()).isEqualTo(214_500);
        assertThat(t.path("freeQty").asLong()).isEqualTo(3);

        // ★2026-08-05 거래명세서 실물 양식 대조로 채운 항목
        assertThat(d.path("provider").path("tel").asText())
                .as("공급자 블록 전화번호").isEqualTo("02-880-2301");
        assertThat(t.path("totalQty").asLong())
                .as("상단 '○권' = 유가+무가 전체 수량")
                .isEqualTo(t.path("pricedQty").asLong() + t.path("freeQty").asLong());
        assertThat(d.path("schoolName").asText())
                .as("학교(원) 칸 — 매출의 학교명이 올라와야 함").isEqualTo("엔컴잇올스파르타학원");
    }

    @Test
    @DisplayName("과목별매출현황 — 매출/반품/교사용 버킷 + 반품률")
    void 과목별매출현황() {
        JsonNode d = data(get("/sales/category-summary?fromDate=2026-06-01&toDate=2026-06-30&partnerId=" + partnerId));
        JsonNode a011 = rowWhere(d.path("rows"), "bookCode", "BK-A011");
        assertThat(a011.path("saleQty").asLong()).isEqualTo(10);
        assertThat(a011.path("returnQty").asLong()).isEqualTo(2);
        assertThat(a011.path("netQty").asLong()).isEqualTo(8);
        assertThat(a011.path("teacherQty").asLong()).isEqualTo(3);
        assertThat(a011.path("returnRate").asDouble()).isEqualTo(20.0);

        // ★금액 — 처리내역서(「거래처별 순매출 조회」)가 쓰는 칸이다. 정가 10,000 × 공급률 50% = 5,000.
        assertThat(a011.path("saleAmount").asLong()).isEqualTo(50_000);     // 10부
        assertThat(a011.path("returnAmount").asLong()).isEqualTo(10_000);   // 2부
        assertThat(a011.path("netAmount").asLong()).isEqualTo(40_000);
        // 교사용은 공급률 0으로 등록했다 — 수량은 3인데 금액은 0이다. 수량만 보면 알 수 없는 값이다.
        assertThat(a011.path("teacherAmount").asLong()).isZero();
    }

    @Test
    @DisplayName("도서입출고현황 — 매입+매출 이중장부 + 정본 재고")
    void 도서입출고현황() {
        JsonNode d = data(get("/sales/book-inout?fromDate=2026-06-01&toDate=2026-06-30&catCode=A2026A01"));
        JsonNode a011 = rowWhere(d.path("rows"), "bookCode", "BK-A011");
        assertThat(a011.path("inboundQty").asLong()).isEqualTo(1000);
        assertThat(a011.path("inboundAmount").asLong()).isEqualTo(3_000_000);  // 1000×3000
        assertThat(a011.path("outboundQty").asLong()).isEqualTo(10);
        assertThat(a011.path("returnQty").asLong()).isEqualTo(2);
        assertThat(a011.path("returnRate").asDouble()).isEqualTo(20.0);
        // 정본 재고(as-of 전체 날짜): 1000 −10(2026매출) −3(교사) +2(반품) −8(2025매출) = 981
        assertThat(a011.path("stockQty").asLong()).isEqualTo(981);
        assertThat(a011.path("grossMargin").asLong()).isEqualTo(40_000 - 3_000_000);
    }

    @Test
    @DisplayName("거래처별 매출대비표 — 전년 동기간 비교")
    void 매출대비표() {
        JsonNode d = data(get("/sales/yoy-comparison?fromDate=2026-06-01&toDate=2026-06-30&groupBy=PARTNER&partnerId="
                + partnerId));
        assertThat(d.path("prevFrom").asText()).isEqualTo("2025-06-01");
        JsonNode row = d.path("rows").get(0);
        assertThat(row.path("curQty").asLong()).isEqualTo(39);
        assertThat(row.path("prevQty").asLong()).isEqualTo(33);   // 8+25
        assertThat(row.path("diffQty").asLong()).isEqualTo(6);
        assertThat(row.path("qtyRatioPct").asDouble()).isCloseTo(118.18, within(0.01));
    }

    @Test
    @DisplayName("매출대비표 BOOK — 전년0이면 비율 null, 감소 처리")
    void 매출대비표_BOOK() {
        JsonNode d = data(get("/sales/yoy-comparison?fromDate=2026-06-01&toDate=2026-06-30&groupBy=BOOK&partnerId="
                + partnerId));
        JsonNode rows = d.path("rows");
        JsonNode a012 = rowWhere(rows, "bookCode", "BK-A012");
        assertThat(a012.path("diffQty").asLong()).isEqualTo(-5);          // 20 vs 25
        assertThat(a012.path("qtyRatioPct").asDouble()).isEqualTo(80.0);
        JsonNode a021 = rowWhere(rows, "bookCode", "BK-A021");
        assertThat(a021.path("prevQty").asLong()).isEqualTo(0);
        // 전년0 → 비율 null(Jackson NON_NULL이 필드 생략 → 숫자 아님)
        assertThat(a021.path("qtyRatioPct").isNumber()).isFalse();
    }

    @Test
    @DisplayName("잘못된 파라미터 — enum/날짜 오류·필수누락은 500 아닌 400")
    void 잘못된파라미터_400() {
        var t = token();
        // 잘못된 enum
        assertThat(exchangeRaw(org.springframework.http.HttpMethod.GET,
                "/sales?salesCategory=BOGUS", null, t, null).getStatusCode().value()).isEqualTo(400);
        // 잘못된 날짜
        assertThat(exchangeRaw(org.springframework.http.HttpMethod.GET,
                "/sales/statement?fromDate=2026-13-99&toDate=2026-06-30", null, t, null).getStatusCode().value()).isEqualTo(400);
        // 필수 파라미터 누락(partnerId)
        assertThat(exchangeRaw(org.springframework.http.HttpMethod.GET,
                "/sales/transaction-statement?fromDate=2026-06-01&toDate=2026-06-30", null, t, null)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("엑셀 다운로드 — 매출액명세서 xlsx(한글 헤더·데이터 행)")
    void 엑셀다운로드() throws Exception {
        var resp = getBytes("/sales/statement/export?fromDate=2026-06-01&toDate=2026-06-30&category=SALE");
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getHeaders().getContentType().toString()).contains("spreadsheetml");
        byte[] xlsx = resp.getBody();
        assertThat(xlsx).isNotEmpty();
        assertThat(new String(xlsx, 0, 2)).isEqualTo("PK");   // xlsx=zip 시그니처

        // POI로 파싱 — 한글 헤더 확인
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("매출액명세서");
            // 0행=제목, 1행=조회기준, 2행=헤더 (재무팀 실파일 형식 — 프론트 전달 2026-08-20 §A-2)
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("매출액명세서");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).startsWith("조회기준 : ");
            var header = sheet.getRow(2);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("구분");
            // 집계 기준이 대분류라 분류코드보다 앞에 온다(발주처 회신 2026-08-20)
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("대분류");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("분류코드");
            assertThat(sheet.getLastRowNum()).isGreaterThan(2);   // 제목 2행 + 헤더 뒤로 데이터 존재
        }
    }

    @Test
    @DisplayName("계산서 10일 분기 — 10일 이전=수정발행/이후=익월 마이너스")
    void 계산서_10일분기() {
        Long wh = createId("/masters/warehouses", Map.of("code", "WH-ADJ", "name", "조정창고", "type", "MAIN"));
        Long p = product("ADJ-BK", "Z2026Z01", "조정테스트");
        inbound(wh, p);   // 재고 확보(반품 재고복구 대상)
        sale("2026-02-01", wh, p, "NORMAL_SHIP", 100, 20);   // 교재식 반품 선행 출고(공급률 100, 20부)
        // 2월로 격리: 5일(≤10=수정발행) 반품 10부, 20일(>10=익월마이너스) 반품 5부
        returnInbound("2026-02-05", wh, p, 10);
        returnInbound("2026-02-20", wh, p, 5);

        JsonNode d = data(get("/closing/invoice-adjustments?year=2026&month=2"));
        assertThat(d.path("rows")).hasSize(2);
        JsonNode amend = rowWhere(d.path("rows"), "mode", "AMEND");
        assertThat(amend.path("reportingMonth").asText()).isEqualTo("202602");   // 당월
        JsonNode next = rowWhere(d.path("rows"), "mode", "NEXT_MONTH_MINUS");
        assertThat(next.path("reportingMonth").asText()).isEqualTo("202603");    // 익월
        JsonNode sum = d.path("summary");
        assertThat(sum.path("amendSupply").asLong()).isEqualTo(100_000);         // 10×10000
        assertThat(sum.path("amendTax").asLong()).isEqualTo(10_000);
        assertThat(sum.path("nextMonthSupply").asLong()).isEqualTo(50_000);      // 5×10000
        assertThat(sum.path("nextMonthTax").asLong()).isEqualTo(5_000);
    }

    /** 반품입고. 세액은 자동산출되지 않으므로 원 출고와 동일 기준으로 명시 입력한다. */
    private void returnInbound(String date, Long whId, Long productId, int qty) {
        JsonNode r = post("/sales/return-inbound", Map.of(
                "returnDate", date, "partnerId", partnerId, "warehouseId", whId,
                "items", List.of(Map.of("productId", productId, "unitPrice", 10000,
                        "supplyRate", 100, "qty", qty, "tax", taxOf(100, qty)))));
        assertThat(r.path("success").asBoolean()).as("반품입고 성공: %s", r).isTrue();
    }

    @Test
    @DisplayName("반품입고(29p) — 매출반품 라인 + 재고 복구 한 트랜잭션")
    void 반품입고() {
        Long wh = createId("/masters/warehouses", Map.of("code", "WH-RI", "name", "반품창고", "type", "MAIN"));
        Long p = product("RI-BK", "R2026R01", "반품테스트");
        inbound(wh, p);   // 재고 1000
        sale("2026-05-01", wh, p, "NORMAL_SHIP", 100, 20);   // 교재식 반품 선행 출고(공급률 100, 20부) → 재고 980

        // 반품입고 10부(정가10000, 공급률100) — 5월로 격리. 출고 20 범위 내
        JsonNode d = data(post("/sales/return-inbound", Map.of(
                "returnDate", "2026-05-10", "partnerId", partnerId, "warehouseId", wh,
                "items", List.of(Map.of("productId", p, "unitPrice", 10000, "supplyRate", 100, "qty", 10,
                        "sourceOutNo", "OUT-20260501-1")))));
        JsonNode line = d.path("items").get(0);
        assertThat(line.path("salesCategory").asText()).isEqualTo("RETURN");
        assertThat(line.path("stockBalance").asLong()).isEqualTo(990);   // 980 +10 자동 복구

        // 수불부: 매출 20·반품 +10, 현재재고 990(1000−20+10)
        JsonNode led = data(get("/stock/ledger?warehouseId=" + wh)).path("content");
        JsonNode row = rowWhere(led, "productCode", "RI-BK");
        assertThat(row.path("salesReturn").asLong()).isEqualTo(10);
        assertThat(row.path("closing").asLong()).isEqualTo(990);

        // 매출장부: RETURN 라인이 생겨 5월 순매출 음수 반영(취소 아님)
        JsonNode sales = data(get("/sales?startDate=2026-05-01&endDate=2026-05-31&salesCategory=RETURN"));
        assertThat(sales.path("content")).isNotEmpty();
    }

    @Test
    @DisplayName("월별매출액명세서(37p) — 성적처리/비처리 인원·금액 분리 + 과세·부가세")
    void 월별매출액명세서() {
        Long wh = createId("/masters/warehouses", Map.of("code", "WH-37", "name", "37창고", "type", "MAIN"));
        Long mp = product("MST-M01", "M2026M01", "더프모의");   // 과세(taxFree=false), 대분류 'M'
        inbound(wh, mp);   // 재고 확보(1000)
        // 3월로 격리: 성적처리 30명 + 비처리 20명 (같은 상품)
        saleP("2026-03-10", wh, mp, "NORMAL_SHIP", 100, 30, "GRADED");
        saleP("2026-03-10", wh, mp, "NORMAL_SHIP", 100, 20, "UNGRADED");

        JsonNode rows = data(get("/sales/monthly-statement?year=2026&month=3")).path("rows");
        JsonNode detail = rowWhere(rows, "bookCode", "MST-M01");
        assertThat(detail.path("gradedQty").asLong()).isEqualTo(30);
        assertThat(detail.path("gradedAmount").asLong()).isEqualTo(300_000);   // 10000×100%×30
        assertThat(detail.path("ungradedQty").asLong()).isEqualTo(20);
        assertThat(detail.path("ungradedAmount").asLong()).isEqualTo(200_000);
        assertThat(detail.path("totalQty").asLong()).isEqualTo(50);
        assertThat(detail.path("totalAmount").asLong()).isEqualTo(500_000);
        assertThat(detail.path("taxableAmount").asLong()).isEqualTo(500_000); // 전부 과세
        assertThat(detail.path("vat").asLong()).isEqualTo(50_000);            // 10%
        JsonNode grand = rowWhere(rows, "rowType", "GRAND_TOTAL");
        assertThat(grand.path("gradedQty").asLong()).isEqualTo(30);
        assertThat(grand.path("totalQty").asLong()).isEqualTo(50);

        // ★분류 계 행(발주처 검수 F-21) — 대분류 > **분류명** > 도서 3단계.
        //   이 행이 없어서 같은 도서명이 분류마다 따로 찍히는 게 "중복 표시"로 보였다(F-20).
        JsonNode catSub = rowWhere(rows, "rowType", "CAT_SUBTOTAL");
        assertThat(catSub).as("분류 계 행이 있어야 한다").isNotNull();
        assertThat(catSub.path("catName").asText()).as("분류 계는 분류명을 담는다").isNotBlank();
        // ‼️null 필드는 응답에서 **생략**된다(NON_NULL) — `path(..).isNull()` 은 '없는 필드'에
        //   false 를 주므로 쓰면 안 된다. 같은 함정에 두 번 걸렸다(stockUpdatedOn).
        assertThat(catSub.has("bookCode")).as("분류 계엔 도서코드가 없다").isFalse();
        assertThat(catSub.path("totalQty").asLong()).isEqualTo(50);

        // 순서: 분류 계가 대분류 계보다 **먼저** 나온다(분류들 → 대분류).
        int catIdx = -1;
        int majorIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            String t = rows.get(i).path("rowType").asText();
            if (catIdx < 0 && "CAT_SUBTOTAL".equals(t)) {
                catIdx = i;
            }
            if (majorIdx < 0 && "MAJOR_SUBTOTAL".equals(t)) {
                majorIdx = i;
            }
        }
        assertThat(catIdx).isGreaterThanOrEqualTo(0);
        assertThat(majorIdx).isGreaterThan(catIdx);
    }

    @Test
    @DisplayName("수익신고 — 거래처×월 순매출/세액(MySQL DATE_FORMAT 회귀 방어)")
    void 수익신고() {
        JsonNode d = data(get("/closing/revenue-report?taxType=TAXABLE&fromDate=2026-06-01&toDate=2026-06-30"));
        JsonNode total = d.path("total");
        assertThat(total.path("totalNetSupply").asLong()).isEqualTo(185_000);  // 195k−10k
        assertThat(total.path("totalNetTax").asLong()).isEqualTo(18_500);
        JsonNode months = d.path("rows").get(0).path("months");
        assertThat(months.get(0).path("yearMonth").asText()).isEqualTo("202606");
    }

    @Test
    @DisplayName("계산서·세금계산서 월별신고(38p) — 6월 과세=세금계산서 버킷, 순매출/세액")
    void 계산서월별신고() {
        JsonNode d = data(get("/closing/tax-filing?year=2026"));
        JsonNode jun = rowWhere(d.path("rows"), "month", "6");  // 공유 6월 시드(전부 과세)
        // 6월 매출 195,000(50k+100k+25k+20k), 반품 10,000(a011 rate50×2), 무상(교사용)은 제외
        assertThat(jun.path("taxInvoiceSale").asLong()).isEqualTo(195_000);
        assertThat(jun.path("taxInvoiceReturn").asLong()).isEqualTo(10_000);
        assertThat(jun.path("taxInvoiceNet").asLong()).isEqualTo(185_000);   // 195k−10k
        assertThat(jun.path("invoiceSale").asLong()).isEqualTo(0);           // 면세 상품 없음
        assertThat(jun.path("netTotal").asLong()).isEqualTo(185_000);
        assertThat(jun.path("tax").asLong()).isEqualTo(18_500);              // 19,500(매출) − 1,000(반품)
        // 이 거래처는 사업자번호가 있으므로 미발행분이 아니다. 매출총액 = 계산서+세금계산서+미발행
        assertThat(jun.path("unissuedSale").asLong()).isEqualTo(0);
        assertThat(jun.path("saleTotal").asLong()).isEqualTo(195_000);
        // 12개월 행 항상 존재
        assertThat(d.path("rows")).hasSize(12);
    }

    @Test
    @DisplayName("계산서월별신고 — 사업자번호 없는 거래처 매출은 '미발행분'으로 분리(계산서·세금계산서와 배타)")
    void 계산서월별신고_미발행분() {
        // 근거: 재무팀 양식 '2026 월별 신고내역' E4 셀 메모 —
        //   "학원매출+외부판매 / 온라인몰 / (사업자번호가없는 거래처의 합)".
        // ★6월은 공유 시드가 쓰므로 건드리지 않는다 — 9월에 따로 심어 그 달만 본다.
        // 실행별 고유 접미어. ‼️partners.code 는 VARCHAR(20) 이라 nanoTime 전체(19자)는 넘친다.
        String sfx = String.valueOf(System.nanoTime() % 100_000_000L);
        Long noBizPartner = createId("/masters/clients",
                Map.of("code", "NOBIZ-" + sfx, "name", "온라인몰" + sfx, "type", "NORMAL"));
        Map<String, Object> item = new java.util.HashMap<>();
        item.put("productId", pB011);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 50);
        item.put("qty", 10);
        item.put("tax", 0);            // 세액 0이어도 계산서 칸이 아니라 미발행으로 가야 한다
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2026-09-10", "partnerId", noBizPartner, "warehouseId", whMain,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출등록 성공: %s", r).isTrue();

        JsonNode sep = rowWhere(data(get("/closing/tax-filing?year=2026")).path("rows"), "month", "9");
        long unissued = sep.path("unissuedSale").asLong();
        assertThat(unissued).isPositive();
        // 사업자번호가 없으면 세액이 0이어도 계산서 칸으로 가면 안 된다 — 배타가 핵심이다.
        assertThat(sep.path("invoiceSale").asLong() + sep.path("taxInvoiceSale").asLong() + unissued)
                .isEqualTo(sep.path("saleTotal").asLong());
        assertThat(sep.path("unissuedNet").asLong()).isEqualTo(unissued);   // 9월 반품 없음
    }

    @Test
    @DisplayName("순매출조회 — 외부콘텐츠 매입액은 매입입고(PURCHASE)만 집계, 정상입고 제외")
    void 순매출_매입입고필터() {
        // 창고·외부콘텐츠 상품 별도 시드(다른 테스트와 격리)
        Long whId = createId("/masters/warehouses", Map.of("code", "WH-EXT", "name", "외부창고", "type", "MAIN"));
        Long ext = externalProduct("EXT-이감01");
        // 정상입고 @5000(매입원가에서 제외돼야 함) + 매입입고 @3000(집계 대상)
        inbound(whId, ext, "NORMAL", 5000, 100);
        inbound(whId, ext, "PURCHASE", 3000, 100);
        // 매출: 공급률 100%, 10부 → 매출액 100,000. 7월로 격리(공유 6월 집계 테스트와 분리)
        sale("2026-07-15", whId, ext, "NORMAL_SHIP", 100, 10);

        JsonNode d = data(get("/sales/net-summary?fromDate=2026-07-01&toDate=2026-07-31&contentType=EXTERNAL"));
        JsonNode row = rowWhere(d.path("rows"), "productCode", "EXT-이감01");
        assertThat(row.path("contentType").asText()).isEqualTo("EXTERNAL");
        assertThat(row.path("netAmount").asLong()).isEqualTo(100_000);
        // 매입단가 = 매입입고 3000만(정상입고 5000 제외돼 blended 4000이 아님)
        assertThat(row.path("purchaseUnitCost").asLong()).isEqualTo(3000);
        assertThat(row.path("purchaseAmount").asLong()).isEqualTo(30_000);      // 3000×10
        assertThat(row.path("profit").asLong()).isEqualTo(70_000);              // 100,000−30,000
        assertThat(row.path("marginPct").asDouble()).isEqualTo(70.0);
    }
}
