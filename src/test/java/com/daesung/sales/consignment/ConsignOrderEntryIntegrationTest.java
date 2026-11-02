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
 * 주문 등록에서 <b>위탁출고</b> — 10/27 명세 §4-1, 프론트 C-1.
 *
 * <p>명세: "원기획서대로 주문 등록의 <b>출고유형에 '위탁출고'를 포함</b>한다."
 * §4-2: "별도 등록 화면(버튼)을 없앤다."
 * 프론트: "C-1 이 오면 화면의 [위탁출고 등록] 창을 없애고 주문 등록에 위탁출고를 넣겠습니다
 * (<b>그 전에 없애면 위탁출고를 넣을 길이 사라집니다</b>)."
 *
 * <p>★<b>진입점만 넓힌다.</b> 미결 원장·자동이고·동시성 락은 위탁 쪽 로직을 그대로 쓴다 —
 * 여기서 다시 짜면 이중 장부가 둘로 갈린다. 전용 API({@code POST /consignment/out})도 남긴다.
 *
 * <p>‼️매출등록 요청에는 창고가 <b>하나</b>(출고 창고)인데 위탁출고는
 * <b>물류창고 → 위탁창고</b> 두 곳이 필요하다. 도착 창고는 창고 마스터의
 * <b>소속거래처</b>로 찾는다(정본 32p 확장).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("주문 등록에서 위탁출고(§4-1 · 프론트 C-1)")
class ConsignOrderEntryIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CO" + (System.nanoTime() % 1_000_000L);
    private static final String DAY = "2048-04-15";

    private Long partner;
    private Long noWhPartner;
    private Long main;
    private Long consign;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "COS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "COP" + SFX, "name", "위탁거래처", "type", "NORMAL"));
        noWhPartner = createId("/masters/clients",
                Map.of("code", "COQ" + SFX, "name", "창고없는거래처", "type", "NORMAL"));
        main = createId("/masters/warehouses",
                Map.of("code", "COM" + SFX, "name", "물류창고", "type", "MAIN"));
        // ★위탁창고는 **소속거래처**로 묶인다 — 주문 등록이 그걸로 도착 창고를 찾는다.
        consign = createId("/masters/warehouses", Map.of("code", "COC" + SFX, "name", "위탁창고",
                "type", "CONSIGN", "ownerClientId", partner));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "COB" + SFX);
        b.put("name", "위탁도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);
        post("/stock/inbound", Map.of("processedDate", "2048-01-05", "supplierClientId", sup,
                "destinationWarehouseId", main,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 900))));
    }

    private Map<String, Object> item(int qty) {
        return Map.of("productId", book, "shipmentType", "CONSIGN_SHIP", "qty", qty,
                "unitPrice", 10000, "supplyRate", 70);
    }

    @Test
    @DisplayName("★주문 등록에서 위탁출고를 받는다 — 전용 화면 없이도 된다")
    void 주문등록으로_위탁출고() {
        JsonNode r = post("/sales/entries", Map.of("salesDate", DAY,
                "partnerId", partner, "warehouseId", main, "items", List.of(item(50))));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        JsonNode line = data(r).path("items").get(0);
        assertThat(line.path("salesNo").asText()).as("매출번호 자리에 위탁출고번호").startsWith("OUT-");
        assertThat(line.path("qty").asInt()).isEqualTo(50);
        // 위탁출고는 매출이 아니다 — 금액은 정산에서 정해진다.
        assertThat(line.path("supplyAmount").asLong()).isZero();

        // 미결이 생겼는지 — 정산 화면이 불러올 대상이다.
        JsonNode pending = data(get("/consignment/pending?partnerId=" + partner));
        boolean found = false;
        for (JsonNode p : pending.path("items")) {
            if (p.path("remainingQty").asInt() == 50) {
                found = true;
            }
        }
        assertThat(found).as("미결 50이 생긴다: %s", pending).isTrue();
    }

    @Test
    @DisplayName("재고가 물류창고 → 위탁창고로 옮겨진다(자동이고)")
    void 자동이고() {
        JsonNode r = post("/sales/entries", Map.of("salesDate", DAY,
                "partnerId", partner, "warehouseId", main, "items", List.of(item(10))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        // 응답의 '위탁창고 잔량'이 곧 자동이고 결과다. 정본 31p — 위탁창고 잔량은
        // 실재고 합계에서 빠지고 **미결수량으로만** 집계된다.
        JsonNode line = data(r).path("items").get(0);
        assertThat(line.path("stockBalance").asInt())
                .as("위탁창고로 들어간다(누적): %s", line).isPositive();
    }

    @Test
    @DisplayName("★다른 출고유형과 섞어 보내면 거부한다 — 흐름이 다르다")
    void 섞으면_거부() {
        JsonNode r = post("/sales/entries", Map.of("salesDate", DAY,
                "partnerId", partner, "warehouseId", main,
                "items", List.of(item(5),
                        Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", 5,
                                "unitPrice", 10000, "supplyRate", 70))));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("같이 등록할 수 없습니다");
    }

    @Test
    @DisplayName("★위탁창고가 없는 거래처면 사유와 함께 거부한다 — 조용히 넘기면 물건이 엉뚱한 데로 간다")
    void 위탁창고_없음() {
        JsonNode r = post("/sales/entries", Map.of("salesDate", DAY,
                "partnerId", noWhPartner, "warehouseId", main, "items", List.of(item(5))));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("위탁창고가 없습니다");
    }

    @Test
    @DisplayName("출고 창고를 안 주면 거부한다 — 실물이 나가는 거래다")
    void 출고창고_필수() {
        JsonNode r = post("/sales/entries", Map.of("salesDate", DAY,
                "partnerId", partner, "items", List.of(item(5))));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("출고 창고");
    }

    @Test
    @DisplayName("★출고/반품 조회에 '위탁출고·위탁창고'로 나온다 — 등록 시점부터(§4-3)")
    void 출고반품조회에_나온다() {
        String day = "2048-06-01";
        post("/sales/entries", Map.of("salesDate", day,
                "partnerId", partner, "warehouseId", main, "items", List.of(item(50))));

        JsonNode rows = data(get("/sales?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner + "&shippedOnly=true&size=50")).path("content");

        assertThat(rows).as("정산 전인데도 보여야 한다: %s", rows).isNotEmpty();
        JsonNode r = rows.get(0);
        assertThat(r.path("shipmentType").asText()).isEqualTo("CONSIGN_SHIP");
        assertThat(r.path("warehouseName").asText()).as("위탁창고로 표시").contains("위탁");
        assertThat(r.path("qty").asInt()).isEqualTo(50);
        assertThat(r.path("salesNo").asText()).startsWith("OUT-");
    }

    @Test
    @DisplayName("★50 중 40을 정산해도 출고/반품에는 50 — 정산분은 통합 매출에만(§4-4)")
    void 정산해도_원출고수량() {
        String day = "2048-06-02";
        post("/sales/entries", Map.of("salesDate", day,
                "partnerId", partner, "warehouseId", main, "items", List.of(item(50))));

        // 미결 50 중 40 정산
        long outId = 0;
        for (JsonNode p : data(get("/consignment/pending?partnerId=" + partner)).path("items")) {
            if (p.path("tradeDate").asText().equals(day)) {
                outId = p.path("consignmentOutId").asLong();
            }
        }
        assertThat(outId).as("미결을 찾았다").isPositive();
        JsonNode st = post("/consignment/settle", Map.of("salesDate", day,
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", 40,
                        "unitPrice", 10000, "supplyRate", 70))));
        assertThat(st.path("success").asBoolean()).as("정산: %s", st).isTrue();

        // ★출고/반품 조회 — **50** 이어야 한다(40이 아니다). 정산 매출은 여기 안 나온다.
        long shippedQty = 0;
        int shippedRows = 0;
        for (JsonNode r : data(get("/sales?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner + "&shippedOnly=true&size=50")).path("content")) {
            shippedRows++;
            shippedQty += r.path("qty").asLong();
        }
        assertThat(shippedRows).as("위탁 한 줄만 — 정산 매출이 더해지면 두 줄이 된다").isEqualTo(1);
        assertThat(shippedQty).as("실제 출고 50(정산분 40이 아니다)").isEqualTo(50);

        // 통합 매출 조회에는 정산분(40)이 나온다.
        long saleQty = 0;
        for (JsonNode r : data(get("/sales?fromDate=" + day + "&toDate=" + day
                + "&partnerId=" + partner + "&size=50")).path("content")) {
            saleQty += r.path("qty").asLong();
        }
        assertThat(saleQty).as("통합 매출에는 정산분 40").isEqualTo(40);
    }
}
