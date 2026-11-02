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
 * 위탁 정산분이 제품수불부 매출 칸에 잡히는지 — 개발팀 점검(2026-09-11) 모수 503 차이.
 *
 * <p>정산 출고는 {@code CONSIGN_SHIP}으로 기록되는데, 수불부 매출 칸은
 * {@code NORMAL_SHIP}만 세고 있었다. 그래서 두 가지가 동시에 어긋났다.
 *
 * <ol>
 *   <li>수불부 매출이 순매출조회보다 <b>정산분만큼 적다</b>(실측 503 중 500).</li>
 *   <li>★<b>컬럼을 다 더해도 현재재고가 안 나온다</b> — 재고 잔량에는 반영되는데
 *       그걸 설명하는 칸이 없었다. 담당자가 장부를 검산할 방법이 사라진다.</li>
 * </ol>
 *
 * <p>위탁 회계기준은 <b>"정산 시점에 매출"</b>이다. 정산분은 매출이 맞다.
 * (위탁 <b>출고</b>는 매출 행을 만들지 않는다 — 재고 이동 + 미결일 뿐이다.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@DisplayName("위탁 정산분 ↔ 수불부 매출 칸(2026-09-11 모수 차이)")
class ConsignLedgerParityIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CL" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2078;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long mainWh;
    private Long consignWh;
    private Long partner;
    private Long book;
    private String bookCode;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "CLS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "CLP" + SFX, "name", "위탁거래처", "type", "NORMAL"));
        mainWh = createId("/masters/warehouses",
                Map.of("code", "CLW" + SFX, "name", "위탁모수물류창고", "type", "MAIN"));
        consignWh = createId("/masters/warehouses", Map.of(
                "code", "CLC" + SFX, "name", "위탁모수위탁창고", "type", "CONSIGN",
                "physicalStock", false, "ownerClientId", partner));

        bookCode = "CLB" + SFX;
        Map<String, Object> b = new HashMap<>();
        b.put("code", bookCode);
        b.put("name", "위탁모수도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "C" + YEAR + "A");
        b.put("catName", "위탁모수분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 1000))));

        // 위탁출고 300 — 여기서는 아직 매출이 아니다(미결).
        JsonNode out = post("/consignment/out", Map.of(
                "processedDate", YEAR + "-02-01", "partnerId", partner,
                "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", book, "unitPrice", 10000,
                        "supplyRate", 70, "qty", 300))));
        assertThat(out.path("success").asBoolean()).as("위탁출고: %s", out).isTrue();
    }

    private JsonNode ledgerRow(Long warehouseId) {
        for (JsonNode r : data(get("/stock/ledger" + RANGE + "&warehouseId=" + warehouseId
                + "&keyword=" + bookCode + "&size=50")).path("content")) {
            if (bookCode.equals(r.path("productCode").asText())) {
                return r;
            }
        }
        throw new IllegalStateException("수불부 행 없음 warehouseId=" + warehouseId);
    }

    private long netSalesQty() {
        for (JsonNode r : data(get("/sales/net-summary?fromDate=" + YEAR + "-01-01"
                + "&toDate=" + YEAR + "-12-31")).path("rows")) {
            if (bookCode.equals(r.path("productCode").asText())) {
                return r.path("saleQty").asLong();
            }
        }
        return 0;
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("★정산 200 → 수불부 매출 칸과 순매출조회가 같은 수를 낸다")
    void 정산분이_매출칸에_잡힌다() {
        long outId = data(get("/consignment/pending?partnerId=" + partner))
                .path("items").get(0).path("consignmentOutId").asLong();

        JsonNode settle = post("/consignment/settle", Map.of(
                "salesDate", YEAR + "-03-01",
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", 200,
                        "unitPrice", 10000, "supplyRate", 70))));
        assertThat(settle.path("success").asBoolean()).as("정산: %s", settle).isTrue();

        assertThat(netSalesQty()).as("순매출조회").isEqualTo(200);
        assertThat(ledgerRow(consignWh).path("sale").asLong())
                .as("★수불부 매출 칸에도 잡혀야 한다 — 예전엔 0이라 모수가 갈렸다").isEqualTo(-200);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("★재고 방정식이 성립한다 — 칸이 없으면 더해도 현재재고가 안 나온다")
    void 재고방정식() {
        JsonNode r = ledgerRow(consignWh);

        long sum = r.path("opening").asLong() + r.path("inbound").asLong()
                + r.path("transfer").asLong() + r.path("bom").asLong()
                + r.path("dispose").asLong() + r.path("sale").asLong()
                + r.path("free").asLong() + r.path("teacher").asLong()
                + r.path("salesReturn").asLong() + r.path("adjust").asLong();

        assertThat(sum).as("컬럼 합 = 현재재고").isEqualTo(r.path("closing").asLong());
        assertThat(r.path("reconciled").asBoolean()).isTrue();
        // 위탁창고: 자동이고 +300, 정산출고 −200 → 잔량 100 = 미결 잔여
        assertThat(r.path("closing").asLong()).as("잔량 = 미결 잔여").isEqualTo(100);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("위탁출고 자체는 매출이 아니다 — 미결일 뿐이다")
    void 출고는_매출이_아니다() {
        // 물류창고 쪽은 이고로 빠져나갔을 뿐 매출 칸이 아니다.
        assertThat(ledgerRow(mainWh).path("sale").asLong())
                .as("위탁출고는 매출 칸에 잡히면 안 된다").isZero();
        assertThat(ledgerRow(mainWh).path("transfer").asLong())
                .as("이고로 나간다").isEqualTo(-300);
    }
}
