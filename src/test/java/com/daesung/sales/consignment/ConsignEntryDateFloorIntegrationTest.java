package com.daesung.sales.consignment;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 위탁 <b>정산·반품</b>도 입력 최소일을 본다 — 프론트 지적(2026-11-18 §2-2).
 *
 * <p>‼️<b>왜 빠져 있었나</b>: 입력 하한을 「매출 등록·수금」 쪽 9곳에만 걸었는데,
 * <b>위탁정산이 매출을 만든다</b>(정본 2탭 1015행 "정산 시점에 매출로 잡는다").
 * 그래서 하한을 걸어 둬도 <b>정산으로는 11월 이전 날짜 매출이 계속 생겼다</b> —
 * 12/10 이관이 같은 기간을 다시 실으면 같은 거래가 두 줄이 된다.
 *
 * <p>위탁반품도 Case1(확정매출분 반품)에서 <b>매출 반품 행</b>을 만든다.
 * ‼️그 경로는 <b>월마감 검사가 애초에 없었다</b> — 마감된 달에도 반품 매출이 들어가
 * 마감 숫자가 바뀔 수 있었다. 마감월에 이미 들어간 건이 <b>0건</b>인 것을 운영에서
 * 확인한 뒤 PM 지시로 메웠다(2026-11-18). 화면은 이 경로를 부르지 않는다
 * (화면의 위탁반품은 {@code settle} 의 {@code returnQty} 로 간다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("위탁 정산·반품도 입력 최소일을 본다(2026-11-18)")
class ConsignEntryDateFloorIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "CF" + (System.nanoTime() % 1_000_000L);

    /** ★다른 테스트가 쓰지 않는 연도. 위탁정산은 매출을 만들어 전역 집계를 오염시킨다. */
    private static final String FLOOR = "2064-07-01";
    private static final String BEFORE = "2064-06-25";

    private Long partner;
    private Long product;
    private Long mainWh;
    private Long consignWh;
    private long outId;

    @BeforeAll
    void seed() {
        token();
        setFloor(null);
        Long sup = createId("/masters/clients",
                Map.of("code", "CFS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "CFP" + SFX, "name", "하한위탁처", "type", "NORMAL"));
        mainWh = createId("/masters/warehouses",
                Map.of("code", "CFW" + SFX, "name", "물류창고", "type", "MAIN"));
        consignWh = createId("/masters/warehouses", Map.of("code", "CFC" + SFX,
                "name", "위탁창고", "type", "CONSIGN", "ownerClientId", partner));
        product = createId("/masters/products", Map.of("code", "CFB" + SFX, "name", "하한위탁도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75, "salesDivision", "교재"));

        assertThat(post("/stock/inbound", Map.of("processedDate", "2064-01-05",
                "supplierClientId", sup, "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", product, "unitCost", 3000, "qty", 500))))
                .path("success").asBoolean()).isTrue();

        JsonNode out = post("/consignment/out", Map.of("processedDate", "2064-02-01",
                "partnerId", partner, "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", product, "qty", 200))));
        assertThat(out.path("success").asBoolean()).as("위탁출고: %s", out).isTrue();
        outId = data(get("/consignment/pending?partnerId=" + partner))
                .path("items").get(0).path("consignmentOutId").asLong();
    }

    @AfterAll
    void 되돌린다() {
        setFloor(null);
    }

    @Test
    @DisplayName("★★위탁정산이 하한 이전 날짜면 거부된다 — 정산은 매출을 만든다")
    void 정산_하한이전_거부() {
        setFloor(FLOOR);
        JsonNode res = settle(BEFORE, 10);
        assertThat(res.path("success").asBoolean()).isFalse();
        assertThat(res.path("error").path("code").asText())
                .as("‼️여기가 통과하면 하한을 걸어도 정산으로 11월 이전 매출이 계속 생긴다")
                .isEqualTo("ENTRY_DATE_TOO_OLD");

        setFloor(null);
        assertThat(settle(BEFORE, 10).path("success").asBoolean())
                .as("풀면 다시 정산된다")
                .isTrue();
    }

    @Test
    @DisplayName("★위탁반품도 하한 이전이면 거부된다 — Case1 은 매출 반품 행을 만든다")
    void 반품_하한이전_거부() {
        setFloor(FLOOR);
        JsonNode res = post("/consignment/return", Map.of("processedDate", BEFORE,
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 5))));
        assertThat(res.path("success").asBoolean()).isFalse();
        assertThat(res.path("error").path("code").asText()).isEqualTo("ENTRY_DATE_TOO_OLD");

        setFloor(null);
        assertThat(post("/consignment/return", Map.of("processedDate", BEFORE,
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 5))))
                .path("success").asBoolean()).isTrue();
    }


    @Test
    @DisplayName("★★위탁반품이 **마감된 달**도 거부한다 — 그전엔 날짜 검사가 하나도 없었다")
    void 반품_마감월_거부() {
        setFloor(null);
        // 그 달을 마감한다(이 테스트 전용 연도라 다른 테스트와 겹치지 않는다)
        assertThat(post("/closing/periods/lock",
                Map.of("year", 2064, "month", 2, "memo", "위탁반품 월마감 검증"))
                .path("success").asBoolean()).isTrue();
        try {
            JsonNode res = post("/consignment/return", Map.of("processedDate", "2064-02-20",
                    "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 3))));
            assertThat(res.path("success").asBoolean()).isFalse();
            assertThat(res.path("error").path("code").asText())
                    .as("‼️여기가 통과하면 마감된 달에 반품 매출이 들어가 신고 숫자가 틀어진다")
                    .isEqualTo("PERIOD_LOCKED");
        } finally {
            assertThat(post("/closing/periods/unlock",
                    Map.of("year", 2064, "month", 2, "memo", "검증 종료"))
                    .path("success").asBoolean()).isTrue();
        }
    }

    private JsonNode settle(String date, int qty) {
        return post("/consignment/settle", Map.of("salesDate", date,
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", qty,
                        "unitPrice", 10000, "supplyRate", 75))));
    }

    private void setFloor(String date) {
        Map<String, Object> body = new HashMap<>();
        body.put("floorDate", date);
        assertThat(put("/closing/entry-date-floor", body).path("success").asBoolean()).isTrue();
    }
}
