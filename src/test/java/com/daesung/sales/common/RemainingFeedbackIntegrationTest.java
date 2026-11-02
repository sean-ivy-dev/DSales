package com.daesung.sales.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.logistics.repository.ShipmentRepository;
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
 * 프론트 회신 2026-10-08 의 남은 항목들 — [A]5 · [C]17 · [C]18.
 *
 * <p>세 건 모두 "서버가 이미 한다고 적어 둔 것과 실제가 달랐다"는 공통점이 있다.
 * 그래서 고친 뒤의 <b>실제 동작</b>을 여기 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("프론트 회신 잔여(2026-10-08 [A]5·[C]17·[C]18)")
class RemainingFeedbackIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-RF" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2104-05-12";
    private static final String RANGE = "?fromDate=2104-01-01&toDate=2104-12-31";

    @Autowired private ShipmentRepository shipmentRepository;

    private String logisToken;

    private Long partner;
    private Long warehouse;
    private Long book;
    private Long mockExam;   // 재고 미관리(모의고사)

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "RFS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "RFP" + SFX, "name", "잔여검증거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "RFW" + SFX, "name", "잔여검증창고", "type", "MAIN"));

        logisToken = logisticsToken();
        book = product("RFA" + SFX, "배송X 교재", true);
        mockExam = product("RFB" + SFX, "미출고 모의고사", false);
        post("/stock/inbound", Map.of("processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 1000))));
    }

    /** 물류 역할 계정 하나를 만들어 토큰을 받는다(권한 분리를 실제 토큰으로 확인한다). */
    private String logisticsToken() {
        String user = "rf-logis" + SFX;
        exchangeRaw(org.springframework.http.HttpMethod.POST, "/auth/users", Map.of(
                "username", user, "password", "Pw123456!",
                "name", "잔여검증물류", "role", "LOGISTICS"), token(), null);
        var resp = exchangeRaw(org.springframework.http.HttpMethod.POST, "/auth/login",
                Map.of("username", user, "password", "Pw123456!"), null, null);
        try {
            return om.readTree(resp.getBody()).path("data").path("accessToken").asText();
        } catch (Exception e) {
            throw new AssertionError("물류 계정 로그인 실패: " + resp.getBody(), e);
        }
    }

    private Long product(String code, String name, boolean stockManaged) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "F2104A01");
        b.put("catName", "잔여검증분류");
        b.put("stockManaged", stockManaged);
        b.put("salesDivision", "교재");
        return createId("/masters/products", b);
    }

    private JsonNode sell(Long productId, Long warehouseId, Boolean onlySales, int qty) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", productId);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", qty);
        Map<String, Object> body = new HashMap<>();
        body.put("salesDate", DATE);
        body.put("partnerId", partner);
        body.put("warehouseId", warehouseId);
        body.put("onlySales", onlySales);
        body.put("items", List.of(item));
        return post("/sales/entries", body);
    }

    /** 그 도서의 창고 재고(수불부 단일 공식). */
    private long stock(Long productId) {
        JsonNode rows = data(get("/stock/ledger" + RANGE + "&size=200&productId=" + productId))
                .path("content");
        return rows.isEmpty() ? 0 : rows.get(0).path("closing").asLong();
    }

    private int workOrderCount() {
        JsonNode d = data(get("/logistics/work-orders" + RANGE + "&partnerId=" + partner)).path("content");
        JsonNode rows = d.isArray() ? d : d.path("content");
        return rows.size();
    }

    @Test
    @DisplayName("★★[C]17 「매출만(배송X)」 — 재고는 빠지고 작업요청서만 안 생긴다")
    void 배송X() {
        long before = stock(book);
        int ordersBefore = workOrderCount();

        JsonNode r = sell(book, warehouse, true, 10);
        assertThat(r.path("success").asBoolean()).as("배송X 매출: %s", r).isTrue();

        // ★레거시 CheckBox_OnlySales(UC_TabPages.vb:749)가 하는 일은 sendData INSERT 건너뛰기뿐이다.
        //   재고와 매출 기록은 평소와 같다 — 물건은 나가기 때문이다.
        assertThat(stock(book)).as("재고는 그대로 10부 빠진다").isEqualTo(before - 10);
        assertThat(workOrderCount()).as("작업요청서는 늘지 않는다").isEqualTo(ordersBefore);
    }

    @Test
    @DisplayName("[C]17 배송X를 안 주면 작업요청서가 생긴다 — 기본 동작은 그대로다")
    void 배송X_미지정() {
        int before = workOrderCount();

        assertThat(sell(book, warehouse, null, 3).path("success").asBoolean()).isTrue();

        assertThat(workOrderCount()).as("기본은 작업지시가 선다").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("★[C]17 창고 없음은 배송X와 다른 축 — 재고관리 상품은 창고 없이는 400")
    void 창고없음은_다른_축() {
        // 재고관리 상품을 창고 없이 보내면 거부한다. 재고를 조용히 안 건드리고 매출만 서면
        // 장부가 갈라진다 — 이 경우 필요한 것은 창고를 비우는 게 아니라 onlySales 다.
        JsonNode r = sell(book, null, null, 5);
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("창고를 지정");

        // 반대로 재고를 안 세는 상품(모의고사)은 창고 없이도 된다 — 미출고 매출이다.
        assertThat(sell(mockExam, null, null, 5).path("success").asBoolean())
                .as("미출고 매출은 창고가 없는 게 맞다").isTrue();
    }

    @Test
    @DisplayName("★★[C]18 물류도 월 잠금 여부를 읽는다 — 저장 단계에서야 막히면 다 입력한 뒤다")
    void 마감상태_조회() {
        JsonNode mine = data(get("/closing/period-status?year=2104"));
        assertThat(mine).as("1~12월").hasSize(12);
        assertThat(mine.get(0).path("locked").asBoolean()).isFalse();

        post("/closing/periods/lock", Map.of("year", 2104, "month", 5, "memo", "잔여검증 마감"));
        try {
            JsonNode byDate = data(get("/closing/period-status?date=" + DATE));
            assertThat(byDate).hasSize(1);
            assertThat(byDate.get(0).path("month").asInt()).isEqualTo(5);
            assertThat(byDate.get(0).path("locked").asBoolean()).as("잠긴 달").isTrue();

            // ★물류 계정으로도 읽힌다. 마감 화면(/closing/periods)은 재무 전용이라
            //   마감 메모·처리자·물류작업비 동결까지 딸려 간다 — 필요한 건 이 한 비트다.
            var asLogis = exchangeRaw(org.springframework.http.HttpMethod.GET,
                    "/closing/period-status?date=" + DATE, null, logisToken, null);
            assertThat(asLogis.getStatusCode().value())
                    .as("물류 조회: %s", asLogis.getBody()).isEqualTo(200);
            assertThat(asLogis.getBody()).contains("\"locked\":true");

            // 마감 화면 자체는 그대로 막혀 있어야 한다 — 한 비트만 열었다.
            assertThat(exchangeRaw(org.springframework.http.HttpMethod.GET,
                    "/closing/periods?year=2104", null, logisToken, null)
                    .getStatusCode().value())
                    .as("마감 화면은 재무 전용이다").isEqualTo(403);

            // 마감을 걸고 푸는 것도 막혀 있어야 한다.
            assertThat(exchangeRaw(org.springframework.http.HttpMethod.POST,
                    "/closing/periods/lock",
                    Map.of("year", 2104, "month", 6, "memo", "물류가 마감"), logisToken, null)
                    .getStatusCode().value())
                    .as("읽기를 열었다고 마감 권한까지 열리면 안 된다").isEqualTo(403);
        } finally {
            post("/closing/periods/unlock", Map.of("year", 2104, "month", 5, "memo", "검증 종료"));
        }
    }

    @Test
    @DisplayName("[A]5 신청번호로 발송 건을 되짚는 조회가 실제로 실행된다")
    void 신청번호_역참조() {
        // ⚠️DSRE 연동이 꺼져 있어 reqCd 가 붙은 매출을 만들 수 없다(주문·더프 경로만 붙인다).
        //    되돌리기 동작 자체는 단위테스트(OrderStateChangeTest)에서 고정했고,
        //    여기서는 상관 서브쿼리가 MySQL 에서 실제로 실행되는지만 본다 —
        //    JPQL 이 문법만 맞고 SQL 생성에서 터지는 경우를 잡으려는 것이다.
        assertThat(shipmentRepository.findByReqCd(999_999_99)).isEmpty();
    }
}
