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
import org.springframework.beans.factory.annotation.Autowired;

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

    @Autowired
    private com.daesung.sales.inventory.repository.InventoryTxnRepository inventoryTxnRepository;

    @Autowired
    private com.daesung.sales.consignment.repository.ConsignmentOutRepository consignmentOutRepository;

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

    @Test
    @DisplayName("★★자동이고 재고이벤트에 전표번호(OUT-)가 찍힌다(2026-11-04 결함수정)")
    void 자동이고_전표번호가_찍힌다() {
        JsonNode r = post("/sales/entries", Map.of("salesDate", "2048-08-01",
                "partnerId", partner, "warehouseId", main, "items", List.of(item(12))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        String outNo = data(r).path("items").get(0).path("salesNo").asText();
        assertThat(outNo).startsWith("OUT-");

        // javadoc(InventoryService.moveStock)이 "OUT-를 그대로 넘겨 한 전표로 묶인다"고
        // 적어 뒀는데, 예전엔 실제로 refNo 없이 나가 이 조회가 0건이었다.
        var txns = inventoryTxnRepository.findAllByRefNo(outNo);
        assertThat(txns).as("출발·도착 두 다리가 refNo=%s 로 묶여야 한다", outNo).hasSize(2);
    }

    @Test
    @DisplayName("★미결 집계(건수·합계)가 같은 조건을 쓴다(2026-11-04 결함수정)")
    void 미결집계_건수와_합계가_같은조건() {
        // 이 테스트만의 미결을 만든 뒤, 전체 건수·합계 증가분이 그 미결 하나와 일치하는지 본다
        // (초과정산 차단이 없어 remainingQty가 음수여도 건수·합 둘 다 반영돼야 한다).
        long beforeCount = countPending();
        long beforeSum = sumPending();

        post("/sales/entries", Map.of("salesDate", "2048-08-02",
                "partnerId", partner, "warehouseId", main, "items", List.of(item(9))));

        assertThat(countPending() - beforeCount).as("건수 +1").isEqualTo(1);
        assertThat(sumPending() - beforeSum).as("합계도 +9(같은 조건이면 건수·합이 같은 건을 센다)")
                .isEqualTo(9);
    }

    private long countPending() {
        return consignmentOutRepository.countPending();
    }

    private long sumPending() {
        return consignmentOutRepository.sumPendingQty();
    }

    @Test
    @DisplayName("★★품목마다 학교가 다르면 거부한다(2026-11-04 결함수정) — 조용히 첫 품목 학교로 뭉개지 않는다")
    void 품목별_학교가_다르면_거부() {
        Map<String, Object> itemA = new HashMap<>(item(5));
        itemA.put("schoolCode", "SCA" + SFX);
        Map<String, Object> itemB = new HashMap<>(item(3));
        itemB.put("schoolCode", "SCB" + SFX);

        JsonNode r = post("/sales/entries", Map.of("salesDate", "2048-08-03",
                "partnerId", partner, "warehouseId", main, "items", List.of(itemA, itemB)));

        assertThat(r.path("success").asBoolean())
                .as("품목마다 학교가 다르면 거부돼야 한다: %s", r).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("같은 학교거나 일부만 학교를 줘도(나머지 null) 통과한다")
    void 같은학교이거나_일부null이면_통과() {
        Map<String, Object> itemA = new HashMap<>(item(5));
        itemA.put("schoolCode", "SCC" + SFX);
        Map<String, Object> itemB = item(3);   // schoolCode 없음(null)

        JsonNode r = post("/sales/entries", Map.of("salesDate", "2048-08-04",
                "partnerId", partner, "warehouseId", main, "items", List.of(itemA, itemB)));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    @Test
    @DisplayName("★정산 목록에는 위탁출고로 들어온 건만 — 일반 매출은 섞이지 않는다(§4-2 · C-2)")
    void 정산대상은_위탁출고만() {
        // §4-2: "위탁 미결 정산은 **위탁출고 유형으로 들어온 건만** 불러와서 처리한다."
        //
        // ★구조상 이미 그렇다 — 정산 목록은 `ConsignmentOut` 만 읽고, 그 행은
        //   위탁출고 경로(`ConsignmentService.consignmentOut`) **한 곳에서만** 생긴다.
        //   C-1 로 주문 등록까지 그 경로로 모였으니 다른 입구가 없다.
        //   이 테스트는 그 사실이 **나중에 깨지지 않도록** 못 박는 것이다 —
        //   누군가 매출을 미결로 함께 올리면 정산 화면에 팔지도 않은 물건이 뜬다.
        String day = "2048-07-07";

        // 같은 날·같은 거래처로 **일반 매출**과 **위탁출고**를 따로 등록한다.
        post("/sales/entries", Map.of("salesDate", day, "partnerId", partner, "warehouseId", main,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 33, "unitPrice", 10000, "supplyRate", 70))));
        post("/sales/entries", Map.of("salesDate", day,
                "partnerId", partner, "warehouseId", main, "items", List.of(item(7))));

        int consignRows = 0;
        for (JsonNode p : data(get("/consignment/pending?partnerId=" + partner)).path("items")) {
            if (day.equals(p.path("tradeDate").asText())) {
                consignRows++;
                assertThat(p.path("originalQty").asInt())
                        .as("위탁으로 넣은 7만 — 일반 매출 33은 들어오면 안 된다").isEqualTo(7);
            }
        }
        assertThat(consignRows).as("그 날 미결은 위탁 한 건뿐").isEqualTo(1);
    }

    /**
     * 위탁창고가 <b>두 곳 이상</b>인 거래처 — 화면이 고른 창고를 받는다
     * (프론트 전달 2026-11-12 §4).
     *
     * <p>예전엔 서버가 그 거래처의 위탁창고를 혼자 정해서(목록의 첫 번째),
     * 두 곳 이상이면 <b>등록 자체가 막혔다.</b> 이제 {@code consignWarehouseId} 로 고른다.
     *
     * <p>★<b>남의 위탁창고는 거부</b>한다 — 화면 값을 그대로 믿으면 미결원장이
     * 엉뚱한 거래처에 선다.
     */
    @Test
    @DisplayName("★위탁창고가 2곳이어도 **고른 창고**로 들어간다 — 예전엔 등록이 막혔다")
    void 위탁창고를_고를_수_있다() {
        // ‼️**별도 거래처**로 만든다 — 공용 `partner` 에 위탁창고를 하나 더 붙이면
        //   "위탁창고가 2개" 가 되어 **다른 테스트가 전부 깨진다**(실제로 그렇게 깨뜨렸다).
        Long p2 = createId("/masters/clients",
                Map.of("code", "CMP" + SFX, "name", "위탁창고2개거래처", "type", "NORMAL"));
        createId("/masters/warehouses", Map.of("code", "CM1" + SFX,
                "name", "위탁창고A", "type", "CONSIGN", "ownerClientId", p2));
        Long consign2 = createId("/masters/warehouses", Map.of("code", "CM2" + SFX,
                "name", "위탁창고B", "type", "CONSIGN", "ownerClientId", p2));

        String day = "2049-03-03";
        Map<String, Object> req = new HashMap<>();
        req.put("salesDate", day);
        req.put("partnerId", p2);
        req.put("warehouseId", main);
        req.put("consignWarehouseId", consign2);        // ← 화면이 고른 창고(둘 중 뒤엣것)
        req.put("items", List.of(item(5)));

        JsonNode res = post("/sales/entries", req);
        assertThat(res.path("success").asBoolean())
                .as("‼️위탁창고가 2곳이라고 막히면 안 된다: %s", res).isTrue();

        // 고른 쪽(위탁창고2)에 들어갔는지 — 첫 번째 창고가 아니다
        JsonNode rows = get("/stock/records?fromDate=" + day + "&toDate=" + day);
        boolean intoChosen = false;
        for (JsonNode r : rows.path("data")) {
            if (consign2.equals(r.path("warehouseId").asLong()) && r.path("qtyDelta").asInt() > 0) {
                intoChosen = true;
            }
        }
        assertThat(intoChosen).as("고른 위탁창고로 재고가 들어와야 한다: %s", rows).isTrue();

        // 안 고르면 종전대로 거부 — 서버가 임의로 정하지 않는다
        req.remove("consignWarehouseId");
        req.put("salesDate", "2049-03-05");
        JsonNode no = post("/sales/entries", req);
        assertThat(no.path("success").asBoolean()).as("안 고르면 거부: %s", no).isFalse();
        assertThat(no.path("error").path("message").asText()).contains("위탁창고가 2개");
    }

    @Test
    @DisplayName("‼️남의 거래처 위탁창고를 고르면 거부 — 미결이 엉뚱한 거래처에 서면 안 된다")
    void 남의_위탁창고는_거부() {
        Long other = createId("/masters/clients",
                Map.of("code", "COX" + SFX, "name", "다른거래처", "type", "NORMAL"));
        Long othersWarehouse = createId("/masters/warehouses", Map.of("code", "COY" + SFX,
                "name", "남의위탁창고", "type", "CONSIGN", "ownerClientId", other));

        Map<String, Object> req = new HashMap<>();
        req.put("salesDate", "2049-03-04");
        req.put("partnerId", partner);
        req.put("warehouseId", main);
        req.put("consignWarehouseId", othersWarehouse);
        req.put("items", List.of(item(5)));

        JsonNode res = post("/sales/entries", req);
        assertThat(res.path("success").asBoolean()).as("거부돼야 한다: %s", res).isFalse();
        assertThat(res.path("error").path("message").asText()).contains("위탁창고가 아닙니다");
    }
}
