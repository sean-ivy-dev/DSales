package com.daesung.sales.consignment;

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
 * 위탁 미결 '반품수량' 컬럼 + 제품수불부 창고구분 필터 회귀 고정.
 * 근거: 발주처 회신 2026-08-21 —
 * 「위탁출고 반품 Case1/Case2 + "반품수량" 컬럼」,
 * "제품수불부현황 조회조건에 창고구분(전체/물류창고/위탁창고) 추가 …
 *  현재는 전체 합산 수량만 보여서 위탁 미결잔여수량이 실제로 어느 창고에 남아있는지 확인이 어렵다".
 *
 * <p>★반품수량이 없으면 <b>반품한 흔적 자체가 사라진다</b>: 위탁 반품은 불변식
 * {@code total = settled + remaining}을 지키려고 총출고를 함께 깎기 때문에,
 * 반품 뒤에 보면 처음부터 그만큼만 나간 것처럼 보인다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("위탁 반품수량·수불부 창고구분")
class ConsignReturnQtyIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CR" + (System.nanoTime() % 1_000_000L);
    // ‼️연도 주의: 위탁정산이 매출을 만들어 대시보드의 전역 연간 집계를 오염시킨다.
    //   2044~2046은 비교연도 테스트가 쓰고 있어 2052로 옮겼다(실제로 한 번 깨뜨렸다).

    private Long partner;
    private Long product;
    private Long mainWh;
    private Long consignWh;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "CRS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "CRP" + SFX, "name", "위탁처", "type", "NORMAL"));
        mainWh = createId("/masters/warehouses", Map.of("code", "CRW" + SFX, "name", "물류창고", "type", "MAIN"));
        consignWh = createId("/masters/warehouses", Map.of("code", "CRC" + SFX, "name", "위탁창고",
                "type", "CONSIGN", "ownerClientId", partner));
        product = createId("/masters/products", Map.of("code", "CRB" + SFX, "name", "위탁도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2052-03-01", "supplierClientId", sup,
                "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", product, "unitCost", 3000, "qty", 500))));

        // 위탁출고 100 → 자동이고로 위탁창고에 100이 쌓인다
        JsonNode out = post("/consignment/out", Map.of("processedDate", "2052-03-05",
                "partnerId", partner, "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", product, "qty", 100))));
        assertThat(out.path("success").asBoolean()).as("위탁출고 성공: %s", out).isTrue();
    }

    private JsonNode pending() {
        return data(get("/consignment/pending?partnerId=" + partner)).path("items").get(0);
    }

    @Test
    @DisplayName("반품하면 반품수량이 쌓이고 원출고는 그대로 — 흔적이 남는다")
    void 반품수량_누적() {
        JsonNode before = pending();
        long outId = before.path("consignmentOutId").asLong();
        assertThat(before.path("originalQty").asInt()).isEqualTo(100);
        assertThat(before.path("returnedQty").asInt()).isZero();

        // 30 정산 + 20 반품
        JsonNode st = post("/consignment/settle", Map.of("salesDate", "2052-03-10",
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", 30,
                        "unitPrice", 10000, "supplyRate", 75))));
        assertThat(st.path("success").asBoolean()).as("정산: %s", st).isTrue();
        JsonNode rt = post("/consignment/return", Map.of("processedDate", "2052-03-12",
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 20))));
        assertThat(rt.path("success").asBoolean()).as("반품: %s", rt).isTrue();

        JsonNode after = pending();
        assertThat(after.path("originalQty").asInt()).as("원출고는 안 줄어든다").isEqualTo(100);
        assertThat(after.path("returnedQty").asInt()).isEqualTo(20);
        assertThat(after.path("settledQty").asInt()).isEqualTo(30);
        assertThat(after.path("remainingQty").asInt()).isEqualTo(50);
        // ‼️이 식이 읽혀야 한다: 원출고 = 정산 + 반품 + 미결잔여
        assertThat(after.path("settledQty").asInt() + after.path("returnedQty").asInt()
                + after.path("remainingQty").asInt()).isEqualTo(100);
        // totalQty는 '아직 살아 있는 출고량'이라 반품만큼 줄어 있다(불변식 유지)
        assertThat(after.path("totalQty").asInt()).isEqualTo(80);
    }

    @Test
    @DisplayName("수불부 창고구분 — 위탁 미결잔여가 어느 창고에 있는지 갈라 본다")
    void 수불부_창고구분() {
        String range = "/stock/ledger?fromDate=2052-01-01&toDate=2052-12-31&productId=" + product;

        long all = sumClosing(range);
        long main = sumClosing(range + "&warehouseType=MAIN");
        long consign = sumClosing(range + "&warehouseType=CONSIGN");

        assertThat(main + consign).as("갈라 봐도 합은 전체와 같다").isEqualTo(all);
        // 위탁창고에 남은 것 = 미결잔여 50 (정산분은 위탁창고에서도 빠진다)
        assertThat(consign).as("위탁 미결잔여가 위탁창고에 남아 있다").isEqualTo(50);
        assertThat(main).isEqualTo(all - 50);
    }

    /** 창고 행들의 기말 재고 합. */
    private long sumClosing(String url) {
        long sum = 0;
        for (JsonNode r : data(get(url)).path("content")) {
            sum += r.path("closing").asLong();
        }
        return sum;
    }
}
