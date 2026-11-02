package com.daesung.sales.inventory;

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
 * 폐기·입고 취소(역분개). 근거: 발주처 회신 「삭제권한」 —
 * "마감 확정 전에는 잘못 등록한 건을 삭제할 수 있어야. 확정 후에는 물리 삭제 없이 취소 처리".
 *
 * <p>★<b>지우지 않는다.</b> 재고는 {@code inventory_txn}(이벤트 로그)이 유일 진실이라
 * 원본을 지우면 "언제 왜 되돌렸나"가 사라진다. 반대 부호 이벤트로 상쇄한다 —
 * 매출취소가 이미 쓰는 방식이고, 그걸 폐기·입고로 넓혔다.
 *
 * <p>‼️입고는 <b>전표번호가 없었다.</b> 폐기는 {@code P-…}가 있는데 입고만 없어
 * "무엇을 되돌릴지" 특정할 수가 없었다. {@code IN-…}을 신설했다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@DisplayName("폐기·입고 취소(역분개)")
class VoucherCancelIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-VC" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2094;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long wh;
    private Long sup;
    private Long book;
    private String bookCode;

    @BeforeAll
    void seed() {
        token();
        sup = createId("/masters/clients",
                Map.of("code", "VCS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "VCW" + SFX, "name", "취소창고", "type", "MAIN"));

        bookCode = "VCB" + SFX;
        Map<String, Object> b = new HashMap<>();
        b.put("code", bookCode);
        b.put("name", "취소도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "V" + YEAR + "A");
        b.put("catName", "취소분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);
    }

    private int balance() {
        for (JsonNode r : data(get("/stock/ledger" + RANGE + "&warehouseId=" + wh
                + "&keyword=" + bookCode + "&size=50")).path("content")) {
            if (bookCode.equals(r.path("productCode").asText())) {
                return r.path("closing").asInt();
            }
        }
        return 0;
    }

    private String inbound(int qty) {
        JsonNode r = post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("입고: %s", r).isTrue();
        return data(r).path("inboundNo").asText();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("★입고 취소 — 재고가 입고 전으로 돌아온다")
    void 입고_취소() {
        String no = inbound(300);
        assertThat(no).as("입고 전표번호가 있어야 되돌릴 수 있다").startsWith("IN-");
        assertThat(balance()).isEqualTo(300);

        JsonNode r = post("/stock/inbound/" + no + "/cancel?reason=오등록", Map.of());
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("reversed").asInt()).isEqualTo(1);
        assertThat(balance()).as("입고 전으로").isZero();
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("★폐기 취소 — 버린 만큼 재고가 돌아온다")
    void 폐기_취소() {
        inbound(100);
        JsonNode d = post("/disposals", Map.of(
                "processedDate", YEAR + "-02-01", "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 40, "reason", "파손"))));
        String no = data(d).path("disposalNo").asText();
        assertThat(balance()).isEqualTo(60);

        JsonNode r = post("/disposals/" + no + "/cancel?reason=수량착오", Map.of());
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(balance()).as("폐기 전으로").isEqualTo(100);
    }

    @Test
    @org.junit.jupiter.api.Order(20)
    @DisplayName("★폐기 삭제(마감 前) — 재고가 돌아온다. 9/27 항목 7 '폐기한 재고는 되돌릴 수 있게'")
    void 폐기_삭제로도_복원() {
        inbound(100);
        int before = balance();
        JsonNode d = post("/disposals", Map.of(
                "processedDate", YEAR + "-03-01", "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 25))));
        String no = data(d).path("disposalNo").asText();
        assertThat(balance()).isEqualTo(before - 25);

        // ★취소(역분개)와 다른 축이다 — 삭제는 '없던 일'로 만든다.
        //   회의는 되돌리는 방법을 지정하지 않았으므로 두 경로 다 재고가 돌아와야 한다.
        JsonNode r = del("/disposals/" + no, Map.of("reason", "오입력"));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(balance()).as("폐기 전으로 돌아온다").isEqualTo(before);
    }

    @Test
    @org.junit.jupiter.api.Order(21)
    @DisplayName("삭제한 폐기는 목록에서도 사라진다 — 재고만 돌아오고 내역이 남으면 두 번 센다")
    void 삭제하면_목록에서도_빠진다() {
        inbound(60);
        JsonNode d = post("/disposals", Map.of(
                "processedDate", YEAR + "-03-02", "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 15))));
        String no = data(d).path("disposalNo").asText();

        del("/disposals/" + no, Map.of("reason", "오입력"));

        boolean found = false;
        for (JsonNode r : data(get("/disposals" + RANGE + "&size=200")).path("content")) {
            found |= no.equals(r.path("refNo").asText());
        }
        assertThat(found).as("삭제된 전표가 폐기 내역에 남아 있으면 안 된다").isFalse();
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("★두 번 취소하면 400 — 막지 않으면 재고가 반대로 밀린다")
    void 중복_취소() {
        String no = inbound(50);
        int before = balance();

        assertThat(post("/stock/inbound/" + no + "/cancel", Map.of())
                .path("success").asBoolean()).isTrue();
        int afterFirst = balance();

        JsonNode second = post("/stock/inbound/" + no + "/cancel", Map.of());
        assertThat(second.path("success").asBoolean()).isFalse();
        assertThat(second.path("error").path("message").asText()).contains("이미 취소");

        assertThat(balance()).as("두 번째는 재고를 건드리지 않는다").isEqualTo(afterFirst);
        assertThat(afterFirst).isEqualTo(before - 50);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("없는 전표는 404")
    void 없는_전표() {
        JsonNode r = post("/disposals/P-99999999-9/cancel", Map.of());

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("★원본은 지우지 않는다 — 되돌린 기록이 수불부에 함께 남는다")
    void 이력_보존() {
        String no = inbound(70);
        post("/stock/inbound/" + no + "/cancel", Map.of());

        // 입고 +70 과 역분개 −70 이 **둘 다** 남아 합이 0이 된다.
        // 원본을 지웠다면 입고 자체가 없던 일이 되어 "왜 되돌렸나"를 추적할 수 없다.
        for (JsonNode r : data(get("/stock/ledger" + RANGE + "&warehouseId=" + wh
                + "&keyword=" + bookCode)).path("content")) {
            if (bookCode.equals(r.path("productCode").asText())) {
                assertThat(r.path("inbound").asLong()).as("입고 이벤트는 남아 있다").isPositive();
                assertThat(r.path("reconciled").asBoolean()).as("대사는 맞는다").isTrue();
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("★마감된 달은 취소할 수 없다 — 되돌리면 그 달 숫자가 바뀐다")
    void 마감월_차단() {
        String no = inbound(20);
        post("/closing/periods/lock", Map.of("year", YEAR, "month", 1, "memo", "취소테스트 마감"));

        JsonNode r = post("/stock/inbound/" + no + "/cancel", Map.of());
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("PERIOD_LOCKED");

        post("/closing/periods/unlock", Map.of("year", YEAR, "month", 1, "memo", "테스트 정리"));
    }
}
