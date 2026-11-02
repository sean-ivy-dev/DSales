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
 * 위탁 <b>전량 반품</b> — 미결 총수량이 0이 되는 반품. 근거: 프론트 회신(2026-09-17) ②.
 *
 * <p>증상: 부분 반품은 되는데 <b>마지막 한 부를 반품하면 500</b>.
 * <pre>
 *   2부 위탁출고 → 1부 반품(201, 총 2→1) → 1부 반품(500)
 * </pre>
 *
 * <p>원인: V1의 {@code CHECK (total_qty > 0)}. 반품은 total_qty 를 함께 깎는데
 * 전량 반품이면 0이 되어 제약에 걸리고, DB 예외가 그대로 500으로 새어 나갔다.
 *
 * <p>★<b>0은 정상 상태다.</b> "전부 반품돼 남은 게 없는 미결"이고 불변식도 0=0+0 으로 성립한다.
 * 처음 나간 양은 {@code original_qty} 에 남아 이력을 잃지 않는다.
 *
 * <p>‼️음수까지 열지는 않았다. 0은 "다 돌아왔다", 음수는 "나간 것보다 더 돌아왔다"로 뜻이 다르다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("위탁 전량 반품(2026-09-17 프론트 회신 ②)")
class FullReturnIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-FR" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2090;

    private Long partner;
    private Long mainWh;
    private Long consignWh;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "FRS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "FRP" + SFX, "name", "전량반품거래처", "type", "NORMAL"));
        mainWh = createId("/masters/warehouses",
                Map.of("code", "FRW" + SFX, "name", "전량반품물류창고", "type", "MAIN"));
        consignWh = createId("/masters/warehouses", Map.of(
                "code", "FRC" + SFX, "name", "전량반품위탁창고", "type", "CONSIGN",
                "physicalStock", false, "ownerClientId", partner));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "FRB" + SFX);
        b.put("name", "전량반품도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "R" + YEAR + "A");
        b.put("catName", "전량반품분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 100))));
    }

    /** 위탁출고 한 건 만들고 미결 id를 준다. */
    private long out(int qty) {
        JsonNode r = post("/consignment/out", Map.of(
                "processedDate", YEAR + "-02-01", "partnerId", partner,
                "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", book, "unitPrice", 10000,
                        "supplyRate", 70, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("위탁출고: %s", r).isTrue();

        JsonNode items = data(get("/consignment/pending?partnerId=" + partner)).path("items");
        return items.get(items.size() - 1).path("consignmentOutId").asLong();
    }

    private JsonNode ret(long outId, int qty) {
        return post("/consignment/return", Map.of(
                "processedDate", YEAR + "-03-01",
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", qty))));
    }

    @Test
    @DisplayName("★2부 출고 → 1부 → 1부. 마지막 한 부에서 500이 났었다")
    void 나눠서_전량반품() {
        long id = out(2);

        JsonNode first = ret(id, 1);
        assertThat(first.path("success").asBoolean()).as("부분 반품: %s", first).isTrue();

        JsonNode last = ret(id, 1);
        assertThat(last.path("success").asBoolean())
                .as("★마지막 한 부 — 여기서 500이 났다: %s", last).isTrue();
    }

    @Test
    @DisplayName("★1부 출고 → 1부 반품(한 번에 전량)")
    void 한번에_전량반품() {
        long id = out(1);

        JsonNode r = ret(id, 1);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    @Test
    @DisplayName("전량 반품하면 재고가 물류창고로 온전히 돌아온다")
    void 재고_복귀() {
        long id = out(5);
        String range = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

        JsonNode r = ret(id, 5);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        // 위탁창고는 비고, 물류창고는 원래대로 — 나간 만큼 그대로 돌아온다.
        for (JsonNode row : data(get("/stock/ledger" + range + "&warehouseId=" + consignWh
                + "&keyword=FRB" + SFX)).path("content")) {
            assertThat(row.path("closing").asLong()).as("위탁창고 잔량").isZero();
        }
    }

    @Test
    @DisplayName("정산 경로로 반품수량만 보내도 같다 — 화면의 '반품 처리'가 이 경로다")
    void 정산경로_전량반품() {
        long id = out(3);

        JsonNode r = post("/consignment/settle", Map.of(
                "salesDate", YEAR + "-04-01",
                "settlements", List.of(Map.of("consignmentOutId", id, "returnQty", 3))));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }
}
