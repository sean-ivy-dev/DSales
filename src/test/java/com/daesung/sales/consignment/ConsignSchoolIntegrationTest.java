package com.daesung.sales.consignment;

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
 * 위탁출고의 <b>학교/학원 축</b>과 <b>작업요청서 연계</b>
 * (검수피드백 2026-09-30 p.12 / 프론트 [A]1).
 *
 * <p>발주처 원문 둘 —
 * <ul>
 *   <li>"위탁출고 등록 시 물류 [작업요청서] 내역에 반영되지 않는 문제가 있습니다.
 *       <b>위탁출고도 실제 출고가 발생하므로</b> 작업요청서에 포함되어야 합니다."</li>
 *   <li>"위탁창고 선택뿐 아니라 <b>하위 학교/학원까지 선택 가능</b>해야 하고 …
 *       ★위탁 미결 매출 확정 시에도 <b>세부거래처별로 나누어 확정</b>할 수 있어야 합니다★"</li>
 * </ul>
 *
 * <p>★<b>미결원장 행에 학교를 다는 것이 핵심</b>이다. 출고 전표에만 달면 "A학교 몫 70부만 확정"을
 * 표현할 수 없다 — 확정(정산)이 행 단위로 일어나기 때문이다. 행이 갈려 있으면 정산 로직을
 * 건드리지 않고도 학교별 확정이 된다. 그 구조를 여기서 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("위탁출고 학교 축 · 작업요청서 연계(2026-09-30)")
class ConsignSchoolIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CS" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2106-07-20";
    private static final String RANGE = "?fromDate=2106-01-01&toDate=2106-12-31";

    private Long partner;
    private Long mainWh;
    private Long consignWh;
    private Long book;
    private String schoolA;
    private String schoolB;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "CSS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "CSP" + SFX, "name", "위탁학교검증거래처", "type", "NORMAL"));
        mainWh = createId("/masters/warehouses",
                Map.of("code", "CSM" + SFX, "name", "위탁검증 물류창고", "type", "MAIN"));
        consignWh = createId("/masters/warehouses",
                Map.of("code", "CSC" + SFX, "name", "위탁검증 위탁창고", "type", "CONSIGN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "CSB" + SFX);
        b.put("name", "위탁검증 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "S2106A01");
        b.put("catName", "위탁검증분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 1000))));

        schoolA = "CSA" + SFX;
        schoolB = "CSZ" + SFX;
        school(schoolA, "가학교");
        school(schoolB, "나학교");
    }

    private void school(String code, String name) {
        JsonNode r = post("/masters/schools", Map.of(
                "schoolCode", code, "custCode", "CSP" + SFX, "custName", "위탁학교검증거래처",
                "schoolName", name, "city", "서울"));
        assertThat(r.path("success").asBoolean()).as("학교 등록: %s", r).isTrue();
    }

    private void consignOut(String schoolCode, int qty) {
        Map<String, Object> body = new HashMap<>();
        body.put("processedDate", DATE);
        body.put("partnerId", partner);
        body.put("fromWarehouseId", mainWh);
        body.put("toWarehouseId", consignWh);
        body.put("schoolCode", schoolCode);
        body.put("items", List.of(Map.of("productId", book, "qty", qty)));
        JsonNode r = post("/consignment/out", body);
        assertThat(r.path("success").asBoolean()).as("위탁출고: %s", r).isTrue();
    }

    private JsonNode pending(String query) {
        return data(get("/consignment/pending?partnerId=" + partner + query)).path("items");
    }

    @Test
    @DisplayName("★★미결이 학교별로 갈린다 — 그래야 'A학교 몫만 확정'이 된다")
    void 학교별_미결() {
        consignOut(schoolA, 100);
        consignOut(schoolB, 60);

        JsonNode all = pending("");
        assertThat(all).as("두 학교 몫").hasSizeGreaterThanOrEqualTo(2);

        JsonNode onlyA = pending("&schoolCode=" + schoolA);
        assertThat(onlyA).hasSize(1);
        assertThat(onlyA.get(0).path("schoolCode").asText()).isEqualTo(schoolA);
        assertThat(onlyA.get(0).path("schoolName").asText()).isEqualTo("가학교");
        assertThat(onlyA.get(0).path("remainingQty").asInt()).isEqualTo(100);
        // 출고일이 없으면 담당자가 어느 건을 먼저 확정할지 판단할 수 없다.
        assertThat(onlyA.get(0).path("tradeDate").asText()).isEqualTo(DATE);

        // ★학교별 확정 — 정산 로직은 그대로다. 행이 갈려 있어 그 행만 확정하면 된다.
        long outId = onlyA.get(0).path("consignmentOutId").asLong();
        JsonNode settled = post("/consignment/settle", Map.of(
                "salesDate", DATE,
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", 70,
                        "unitPrice", 10000, "supplyRate", 70))));
        assertThat(settled.path("success").asBoolean()).as("정산: %s", settled).isTrue();

        assertThat(pending("&schoolCode=" + schoolA).get(0).path("remainingQty").asInt())
                .as("A학교만 줄어든다").isEqualTo(30);
        assertThat(pending("&schoolCode=" + schoolB).get(0).path("remainingQty").asInt())
                .as("B학교는 그대로").isEqualTo(60);
    }

    @Test
    @DisplayName("★★위탁출고도 작업요청서에 뜨고, 지시 목록이 비어 있지 않다")
    void 위탁출고_작업요청서() {
        String school = "CSW" + SFX;
        school(school, "작업요청서학교");
        consignOut(school, 40);
        // ★(가)안(2026-11-16) — 등록 직후는 「접수완료」라 작업요청서에 안 나온다.
        //   담당자가 상품준비중으로 넘긴 주문부터 나온다(발주처 확정).
        handOverToLogistics("2106-01-01", "2106-12-31", partner);

        JsonNode d = data(get("/logistics/work-orders" + RANGE + "&partnerId=" + partner)).path("content");
        JsonNode rows = d.isArray() ? d : d.path("content");

        JsonNode mine = null;
        for (JsonNode r : rows) {
            if (school.equals(r.path("schoolCode").asText())) {
                mine = r;
            }
        }
        assertThat(mine).as("위탁출고 건이 작업요청서에 떠야 한다: %s", rows).isNotNull();

        // ‼️예전엔 발송 건은 생겨도 **지시 목록이 비어** 있었다 — 위탁출고는 매출이 없어서
        //   매출 기반 목록에 안 잡히기 때문이다. 물류는 뭘 싸야 할지 알 수 없었다.
        assertThat(mine.path("lines")).as("무엇을 몇 개 넣을지가 있어야 한다").isNotEmpty();
        assertThat(mine.path("totalQty").asLong()).isEqualTo(40);
        assertThat(mine.path("lines").get(0).path("qty").asLong()).isEqualTo(40);
        assertThat(mine.path("schoolName").asText()).isEqualTo("작업요청서학교");
    }

    @Test
    @DisplayName("★★A-1 재현 — 재고관리 상품인데 **물류창고 재고가 음수**인 경우")
    void 음수재고_위탁반품() {
        // 실측 건(OUT-20261008-16)은 재고관리 상품이다. 입고 없이 위탁출고만 된 상태라
        // 물류창고 재고가 음수로 남아 있다("본사 −1 / 위탁창고 +1").
        // ★그 상태에서 반품하면 재고 경고를 만들면서 창고 **이름**을 읽는데,
        //   그 시점엔 영속성 컨텍스트가 이미 비워져 있어 터진다.
        Map<String, Object> b = new HashMap<>();
        b.put("code", "CSX" + SFX);
        b.put("name", "음수재고 교재");
        b.put("contentType", "SELF");
        b.put("price", 12000);
        b.put("supplyRate", 70);
        b.put("catCode", "S2106B01");
        b.put("catName", "음수재고분류");
        b.put("salesDivision", "교재");
        Long noStockBook = createId("/masters/products", b);   // 입고를 **하지 않는다**

        String school = "CSX1" + SFX;
        school(school, "음수재고학교");

        Map<String, Object> body = new HashMap<>();
        body.put("processedDate", DATE);
        body.put("partnerId", partner);
        body.put("fromWarehouseId", mainWh);
        body.put("toWarehouseId", consignWh);
        body.put("schoolCode", school);
        body.put("items", List.of(Map.of("productId", noStockBook, "qty", 2)));
        JsonNode out = post("/consignment/out", body);
        assertThat(out.path("success").asBoolean()).as("위탁출고(재고 음수): %s", out).isTrue();

        long outId = pending("&schoolCode=" + school).get(0).path("consignmentOutId").asLong();

        // 1권 반품 → 물류창고 −2 + 1 = −1 (여전히 음수) → 경고 생성 경로를 탄다
        JsonNode r = post("/consignment/return", Map.of(
                "processedDate", DATE,
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 1))));
        assertThat(r.path("success").asBoolean()).as("음수재고 상태 반품: %s", r).isTrue();

        assertThat(pending("&schoolCode=" + school).get(0).path("remainingQty").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★★위탁 반품 A-1 재현 — 재고 미관리 상품(모의고사)")
    void 재고미관리_위탁반품() {
        // 실측 상품 SEED-B01 은 **모의고사**다. 시드가 stock_managed 를 안 주지만
        // 운영 데이터에는 인원기반(재고 미관리) 모의고사가 실제로 있다.
        Map<String, Object> b = new HashMap<>();
        b.put("code", "CSN" + SFX);
        b.put("name", "재고미관리 모의고사");
        b.put("contentType", "SELF");
        b.put("price", 12000);
        b.put("supplyRate", 70);
        b.put("catCode", "M2106A01");
        b.put("catName", "위탁검증모의고사");
        b.put("salesDivision", "모의고사");
        b.put("stockManaged", false);
        Long mock = createId("/masters/products", b);

        String school = "CSN1" + SFX;
        school(school, "재고미관리학교");

        Map<String, Object> body = new HashMap<>();
        body.put("processedDate", DATE);
        body.put("partnerId", partner);
        body.put("fromWarehouseId", mainWh);
        body.put("toWarehouseId", consignWh);
        body.put("schoolCode", school);
        body.put("items", List.of(Map.of("productId", mock, "qty", 10)));
        JsonNode out = post("/consignment/out", body);
        assertThat(out.path("success").asBoolean()).as("위탁출고: %s", out).isTrue();

        long outId = pending("&schoolCode=" + school).get(0).path("consignmentOutId").asLong();
        JsonNode r = post("/consignment/return", Map.of(
                "processedDate", DATE,
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 1))));
        assertThat(r.path("success").asBoolean()).as("재고 미관리 상품 반품: %s", r).isTrue();

        // 실측이 짚은 두 번째 경로 — 정산 화면의 반품수량 칸(정산 0 · 반품 1).
        JsonNode viaSettle = post("/consignment/settle", Map.of(
                "salesDate", DATE,
                "settlements", List.of(Map.of("consignmentOutId", outId,
                        "settleQty", 0, "returnQty", 1))));
        assertThat(viaSettle.path("success").asBoolean())
                .as("정산 경로 반품: %s", viaSettle).isTrue();

        assertThat(pending("&schoolCode=" + school).get(0).path("remainingQty").asInt())
                .as("10 − 1 − 1").isEqualTo(8);
    }

    @Test
    @DisplayName("★★위탁 반품 — 프론트 실측 A-1(500). HTTP 경로라 요청마다 새 컨텍스트다")
    void 학교지정_위탁반품() {
        String school = "CSR" + SFX;
        school(school, "반품검증학교");
        consignOut(school, 10);

        long outId = pending("&schoolCode=" + school).get(0).path("consignmentOutId").asLong();

        JsonNode r = post("/consignment/return", Map.of(
                "processedDate", DATE,
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 1))));
        assertThat(r.path("success").asBoolean()).as("위탁 반품: %s", r).isTrue();

        assertThat(pending("&schoolCode=" + school).get(0).path("remainingQty").asInt())
                .as("1부 돌아와 9부 남는다").isEqualTo(9);

        // ★두 번째 반품 — 실측(A-1)은 같은 미결을 여러 번 건드린 뒤에 500이 났을 수 있다.
        //   반품의 역-이고가 **또 도착다리를 만들면** 위탁창고 역추적이 2건이 되어 터진다.
        JsonNode again = post("/consignment/return", Map.of(
                "processedDate", DATE,
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 1))));
        assertThat(again.path("success").asBoolean()).as("두 번째 반품: %s", again).isTrue();
        assertThat(pending("&schoolCode=" + school).get(0).path("remainingQty").asInt())
                .isEqualTo(8);
    }

    @Test
    @DisplayName("★정산 경로의 반품수량도 된다 — 프론트 실측 A-1 두 번째 경로")
    void 학교지정_정산반품() {
        String school = "CSQ" + SFX;
        school(school, "정산반품학교");
        consignOut(school, 10);

        long outId = pending("&schoolCode=" + school).get(0).path("consignmentOutId").asLong();

        JsonNode r = post("/consignment/settle", Map.of(
                "salesDate", DATE,
                "settlements", List.of(Map.of("consignmentOutId", outId,
                        "settleQty", 0, "returnQty", 1))));
        assertThat(r.path("success").asBoolean()).as("정산(반품만): %s", r).isTrue();

        assertThat(pending("&schoolCode=" + school).get(0).path("remainingQty").asInt())
                .isEqualTo(9);
    }

    @Test
    @DisplayName("학교를 안 주면 예전처럼 동작한다 — 기존 위탁출고를 깨지 않는다")
    void 학교_미지정() {
        consignOut(null, 25);

        boolean found = false;
        for (JsonNode r : pending("")) {
            if (!r.hasNonNull("schoolCode") && r.path("remainingQty").asInt() == 25) {
                found = true;
            }
        }
        assertThat(found).as("학교 없는 미결도 그대로 조회된다").isTrue();
    }
}
