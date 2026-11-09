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
 * 매입률 — <b>매입단가 = 정가 × 매입률/100</b>(F-13).
 *
 * <p>발주처 요청: "입고구분 「매입입고」 선택 시 <b>매입률 입력칸</b> 추가".
 *
 * <p>★식은 우리가 정한 게 아니라 <b>레거시 그대로</b>다. `DSTxtBook.salesData` 입고행
 * 33,221건에 `supRate` 가 실재하고(85%·75%·50%·60% …), 그 행에서
 * {@code 정가 × supRate/100 × 수량 = 금액} 이 검산된다
 * (예: 정가 12,000 · 률 55 · 수량 6 → 39,600).
 *
 * <p>‼️<b>도서 마스터 기본값으로 두지 않았다.</b> 레거시 `bookData` 에 그 칸이 없고,
 * 같은 도서가 <b>같은 해에도 매입률 3종</b>을 갖는 경우가 실제로 있다
 * (H2016F-06·H2018R-01). 기본값을 두면 담당자가 넣은 값과 어긋난다 — 그래서 <b>건별</b>이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("매입률 — 정가 × 매입률/100 = 매입단가(F-13)")
class PurchaseRateIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PR" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2097-04-20";

    private Long supplier;
    private Long warehouse;
    private Long product;

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients",
                Map.of("code", "PRS" + SFX, "name", "매입처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "PRW" + SFX, "name", "창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "PRB" + SFX);
        b.put("name", "매입률 검증도서");
        b.put("contentType", "EXTERNAL");
        b.put("price", 12000);                 // ★레거시 검산 행의 정가
        b.put("catCode", "S2097A01");
        b.put("catName", "매입률검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        product = createId("/masters/products", b);
    }

    private JsonNode inbound(String inboundType, Map<String, Object> item) {
        Map<String, Object> req = new HashMap<>();
        req.put("processedDate", DATE);
        req.put("supplierClientId", supplier);
        req.put("destinationWarehouseId", warehouse);
        req.put("inboundType", inboundType);
        req.put("items", List.of(item));
        return post("/stock/inbound", req);
    }

    @Test
    @DisplayName("매입입고 + 매입률 55% — 단가가 6,600으로 자동 계산된다(12,000×55/100)")
    void 매입률로_단가계산() {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("qty", 6);
        item.put("purchaseRate", 55);          // 단가는 보내지 않는다
        JsonNode res = inbound("PURCHASE", item);
        assertThat(res.path("success").asBoolean()).as("등록: %s", res).isTrue();

        JsonNode line = res.path("data").path("items").get(0);
        assertThat(line.path("unitCost").asLong())
                .as("정가 12,000 × 55/100 = 6,600 이어야 한다: %s", line).isEqualTo(6_600L);
        assertThat(line.path("purchaseRate").asInt()).isEqualTo(55);

        // ‼️레거시 검산: 단가 × 수량 = 39,600(정가 × 률/100 × 수량)
        assertThat(line.path("unitCost").asLong() * 6).isEqualTo(39_600L);
    }

    @Test
    @DisplayName("단가를 직접 넣으면 그 값이 이긴다 — 매입률은 기록만 남는다")
    void 직접입력이_우선() {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("qty", 10);
        item.put("purchaseRate", 55);
        item.put("unitCost", 5_000);           // 률로 계산하면 6,600 인데 직접 5,000
        JsonNode res = inbound("PURCHASE", item);
        assertThat(res.path("success").asBoolean()).as("등록: %s", res).isTrue();

        JsonNode line = res.path("data").path("items").get(0);
        assertThat(line.path("unitCost").asLong()).isEqualTo(5_000L);
        // 률은 버리지 않는다 — "왜 이 단가인지"의 근거가 사라지면 안 된다.
        assertThat(line.path("purchaseRate").asInt()).isEqualTo(55);
    }

    @Test
    @DisplayName("‼️매입입고가 아닌데 매입률을 주면 거부 — 조용히 버리면 값이 사라진 걸 모른다")
    void 정상입고에_매입률은_거부() {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("qty", 3);
        item.put("purchaseRate", 75);
        JsonNode res = inbound("NORMAL", item);
        assertThat(res.path("success").asBoolean()).as("거부돼야 한다: %s", res).isFalse();
        assertThat(res.path("error").path("message").asText()).contains("매입률");
    }

    @Test
    @DisplayName("매입반품도 매입 축이라 허용 — 수량만 음수로 들어간다")
    void 매입반품은_허용() {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("qty", 2);
        item.put("purchaseRate", 50);
        JsonNode res = inbound("PURCHASE_RETURN", item);
        assertThat(res.path("success").asBoolean()).as("등록: %s", res).isTrue();
        assertThat(res.path("data").path("items").get(0).path("unitCost").asLong())
                .isEqualTo(6_000L);           // 12,000 × 50/100
    }

    @Test
    @DisplayName("매입률 없이 매입입고 — 종전대로 동작한다(률은 null)")
    void 매입률없이도_종전대로() {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("qty", 4);
        item.put("unitCost", 7_000);
        JsonNode res = inbound("PURCHASE", item);
        assertThat(res.path("success").asBoolean()).as("등록: %s", res).isTrue();
        JsonNode line = res.path("data").path("items").get(0);
        assertThat(line.path("unitCost").asLong()).isEqualTo(7_000L);
        // ‼️Jackson NON_NULL 이라 null 필드는 **키 자체가 없다** — has() 로 본다.
        assertThat(line.has("purchaseRate")).as("률은 비어야 한다: %s", line).isFalse();
    }

    @Test
    @DisplayName("입고내역 조회에 매입률이 보인다 — 왜 이 단가인지 되짚을 수 있어야 한다")
    void 조회에_노출() {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("qty", 5);
        item.put("purchaseRate", 85);
        assertThat(inbound("PURCHASE", item).path("success").asBoolean()).isTrue();

        JsonNode res = get("/stock/records?fromDate=" + DATE + "&toDate=" + DATE
                + "&productIds=" + product);
        assertThat(res.path("success").asBoolean()).as("조회: %s", res).isTrue();

        boolean found = false;
        for (JsonNode n : res.path("data")) {
            if (n.path("purchaseRate").asInt(0) == 85) {
                found = true;
                assertThat(n.path("unitCost").asLong()).isEqualTo(10_200L);  // 12,000 × 85/100
            }
        }
        assertThat(found).as("매입률 85 행이 보여야 한다: %s", res).isTrue();
    }
}
