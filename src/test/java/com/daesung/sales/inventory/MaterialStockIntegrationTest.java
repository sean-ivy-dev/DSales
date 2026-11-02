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
 * 자재 재고(V78) — 입고·잔량, 그리고 <b>도서 리포트를 흔들지 않는지</b>.
 *
 * <p>근거: 9/27 회의 A-1(항목 4·5·6·22) — "dsre는 매출만 변경, 매출에서는 수불·자재 다 관리" /
 * "자재별로 입고 가능하게".
 *
 * <p>★<b>이 테스트의 절반은 격리 확인이다.</b> 자재를 도서와 <b>같은 원장</b>({@code inventory_txn})에
 * 넣기로 했기 때문에, 자재 거래가 도서 리포트 숫자에 새어 들어갈 위험이 생겼다.
 * 대부분의 리포트는 products 를 조인해 자연히 걸러지지만 그렇지 않은 집계도 있어,
 * "사람이 매번 기억한다"에 기대지 않고 <b>같은 값이 나오는지</b>를 여기서 고정한다.
 * 새 리포트를 만들 때 이 테스트가 깨지면 그 쿼리에 자재가 새고 있다는 뜻이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("자재 재고 — 입고·잔량·도서 리포트 격리")
class MaterialStockIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-MS" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2092-03-10";

    private Long warehouse;
    private Long supplier;
    private Long book;
    private Long material;      // 자재_입고 전용
    private Long material2;     // 자재구분_필터 전용
    private Long material3;     // 자재전표_취소 전용
    private Long material4;     // 도서리포트_격리 전용
    private Long material5;     // 이고 전용
    private Long material6;     // 폐기 전용
    private Long material7;     // 폐기/파손 구분 전용
    private Long material8;     // 출고·회수 전용
    private Long warehouse2;    // 이고 도착창고

    @BeforeAll
    void seed() {
        token();
        warehouse = createId("/masters/warehouses",
                Map.of("code", "MSW" + SFX, "name", "자재창고", "type", "MAIN"));
        supplier = createId("/masters/clients",
                Map.of("code", "MSS" + SFX, "name", "인쇄소", "type", "NORMAL"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "MSB" + SFX);
        b.put("name", "자재격리검증 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "X2092A01");
        b.put("catName", "격리검증분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        material = createId("/masters/materials", Map.of(
                "code", "MSM1" + SFX, "name", "격리검증 시험지", "materialType", "시험지"));
        material2 = createId("/masters/materials", Map.of(
                "code", "MSM2" + SFX, "name", "격리검증 OMR", "materialType", "OMR"));
        // ‼️테스트마다 자재를 따로 쓴다. 하나를 공유하면 실행 순서에 따라 수량이 섞여
        //   "누적된다" 같은 검증이 다른 테스트의 입고량까지 세게 된다(실제로 그렇게 깨졌다).
        material3 = createId("/masters/materials", Map.of(
                "code", "MSM3" + SFX, "name", "격리검증 라벨", "materialType", "라벨"));
        material4 = createId("/masters/materials", Map.of(
                "code", "MSM4" + SFX, "name", "격리검증 해설지", "materialType", "해설지"));
        material5 = createId("/masters/materials", Map.of(
                "code", "MSM5" + SFX, "name", "이고검증 시험지", "materialType", "시험지"));
        material6 = createId("/masters/materials", Map.of(
                "code", "MSM6" + SFX, "name", "폐기검증 시험지", "materialType", "시험지"));
        material7 = createId("/masters/materials", Map.of(
                "code", "MSM7" + SFX, "name", "파손검증 시험지", "materialType", "시험지"));
        material8 = createId("/masters/materials", Map.of(
                "code", "MSM8" + SFX, "name", "출고검증 OMR", "materialType", "OMR"));
        warehouse2 = createId("/masters/warehouses",
                Map.of("code", "MSW2" + SFX, "name", "자재창고2", "type", "MAIN"));
    }

    private JsonNode materialInbound(Long materialId, int qty, Long unitCost) {
        Map<String, Object> item = new HashMap<>();
        item.put("materialId", materialId);
        item.put("qty", qty);
        item.put("unitCost", unitCost);
        return post("/stock/materials/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse, "items", List.of(item)));
    }

    private int materialQty(Long materialId) {
        JsonNode rows = data(get("/stock/materials?materialId=" + materialId
                + "&warehouseId=" + warehouse));
        return rows.isEmpty() ? 0 : rows.get(0).path("qty").asInt();
    }

    @Test
    @DisplayName("★자재를 입고하면 자재 잔량이 는다 — 도서와 같은 원장, 다른 축")
    void 자재_입고() {
        JsonNode r = materialInbound(material, 5000, 120L);

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        JsonNode line = data(r).path("lines").get(0);
        assertThat(data(r).path("inboundNo").asText()).startsWith("IN-");
        assertThat(line.path("qty").asInt()).isEqualTo(5000);
        assertThat(line.path("currentQty").asInt()).isEqualTo(5000);

        materialInbound(material, 300, 120L);
        assertThat(materialQty(material)).as("누적된다").isEqualTo(5300);
    }

    @Test
    @DisplayName("자재구분으로 걸러진다 — 자재가 늘면 목록에서 찾을 수 있어야 한다")
    void 자재구분_필터() {
        materialInbound(material2, 700, 30L);

        JsonNode omr = data(get("/stock/materials?materialId=" + material2 + "&materialType=OMR"));
        assertThat(omr).hasSize(1);
        assertThat(omr.get(0).path("qty").asInt()).isEqualTo(700);

        // 한글 자재구분도 받는다(화면이 목록에서 고른 한글을 그대로 되보낸다)
        assertThat(data(get("/stock/materials?materialId=" + material2 + "&materialType=OMR")))
                .hasSize(1);
        assertThat(data(get("/stock/materials?materialId=" + material2 + "&materialType=LABEL")))
                .as("다른 구분으로는 안 잡힌다").isEmpty();
    }

    @Test
    @DisplayName("없는 자재는 404 — 조용히 0으로 넘기지 않는다")
    void 없는_자재() {
        JsonNode r = post("/stock/materials/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("materialId", 99_999_999L, "qty", 1))));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("자재가 없습니다");
    }

    @Test
    @DisplayName("★자재 전표도 취소된다 — 예전 구조라면 여기서 500이 났다")
    void 자재전표_취소() {
        int before = materialQty(material3);
        String refNo = data(materialInbound(material3, 900, 100L)).path("inboundNo").asText();
        assertThat(materialQty(material3)).isEqualTo(before + 900);

        JsonNode c = post("/stock/vouchers/" + refNo + "/cancel?reason=오입력", Map.of());
        assertThat(c.path("success").asBoolean()).as("%s", c).isTrue();
        assertThat(materialQty(material3)).as("되돌아온다").isEqualTo(before);
    }

    @Test
    @DisplayName("★★자재 거래를 넣어도 도서 리포트 숫자가 그대로다 — 같은 원장을 쓰는 대가를 여기서 막는다")
    void 도서리포트_격리() {
        // 도서 쪽 기준값을 먼저 찍는다.
        post("/stock/inbound", Map.of("processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse, "inboundType", "PURCHASE",
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 100))));

        String period = "fromDate=2092-01-01&toDate=2092-12-31";
        long ledgerBefore = ledgerClosing();
        String netBefore = data(get("/sales/net-summary?" + period)).toString();
        int disposalsBefore = data(get("/disposals?" + period)).size();
        int recordsBefore = data(get("/stock/records?" + period)).size();
        String bookInoutBefore = data(get("/sales/book-inout?" + period)).toString();

        // 자재를 잔뜩 넣는다 — 매입입고로도 넣어 본다(집계에 가장 새기 쉬운 경로).
        materialInbound(material4, 12_345, 999L);
        post("/stock/materials/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse, "inboundType", "PURCHASE",
                "items", List.of(Map.of("materialId", material4, "qty", 7_777, "unitCost", 555))));

        assertThat(ledgerClosing()).as("제품수불부 현재고").isEqualTo(ledgerBefore);
        assertThat(data(get("/sales/net-summary?" + period)).toString())
                .as("순매출·매입 집계").isEqualTo(netBefore);
        assertThat(data(get("/disposals?" + period)).size())
                .as("폐기 내역 건수").isEqualTo(disposalsBefore);
        assertThat(data(get("/stock/records?" + period)).size())
                .as("입고/대체 내역 — 도서 화면이라 자재가 섞이면 안 된다").isEqualTo(recordsBefore);
        assertThat(data(get("/sales/book-inout?" + period)).toString())
                .as("도서입출고현황").isEqualTo(bookInoutBefore);
    }

    @Test
    @DisplayName("★자재 이고 — 출발에서 빠지고 도착으로 들어간다(한 트랜잭션)")
    void 자재_이고() {
        materialInbound(material5, 1000, 50L);

        JsonNode r = post("/stock/materials/transfer", Map.of(
                "processedDate", DATE, "fromWarehouseId", warehouse, "toWarehouseId", warehouse2,
                "items", List.of(Map.of("materialId", material5, "qty", 400))));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("transferNo").asText()).startsWith("TR-");
        JsonNode line = data(r).path("lines").get(0);
        assertThat(line.path("fromQty").asInt()).isEqualTo(600);
        assertThat(line.path("toQty").asInt()).isEqualTo(400);

        // 합은 보존된다 — 이고는 총량을 바꾸지 않는다.
        JsonNode rows = data(get("/stock/materials?materialId=" + material5));
        int sum = 0;
        for (JsonNode row : rows) {
            sum += row.path("qty").asInt();
        }
        assertThat(sum).as("창고를 옮겨도 총량은 그대로").isEqualTo(1000);
    }

    @Test
    @DisplayName("같은 창고로 이고하면 400 — 아무 일도 안 일어나는 전표를 만들지 않는다")
    void 같은창고_이고() {
        JsonNode r = post("/stock/materials/transfer", Map.of(
                "processedDate", DATE, "fromWarehouseId", warehouse, "toWarehouseId", warehouse,
                "items", List.of(Map.of("materialId", material5, "qty", 1))));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("같습니다");
    }

    private JsonNode io(Long materialId, String gubun, int qty) {
        return post("/stock/materials/io", Map.of(
                "processedDate", DATE, "warehouseId", warehouse, "io", gubun,
                "items", List.of(Map.of("materialId", materialId, "qty", qty, "memo", "검증"))));
    }

    @Test
    @DisplayName("★자재 폐기 — 양수로 넣고 원장엔 음수로 남는다(사유 칸 없음, A-3)")
    void 자재_폐기() {
        materialInbound(material6, 500, 40L);

        JsonNode r = io(material6, "DISPOSE", 120);

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("refNo").asText()).startsWith("P-");
        JsonNode line = data(r).path("lines").get(0);
        assertThat(line.path("qty").asInt()).as("화면엔 양수").isEqualTo(120);
        assertThat(line.path("currentQty").asInt()).isEqualTo(380);

        // 원장은 음수로 남는다
        JsonNode rec = data(get("/stock/materials/records?materialIds=" + material6
                + "&kinds=DISPOSE"));
        assertThat(rec).hasSize(1);
        assertThat(rec.get(0).path("qtyDelta").asInt()).as("원장엔 음수").isEqualTo(-120);
        assertThat(rec.get(0).path("kind").asText()).isEqualTo("폐기");
        assertThat(rec.get(0).path("io").asText()).isEqualTo("DISPOSE");
    }

    @Test
    @DisplayName("★★폐기와 파손이 갈린다 — 잔량 효과가 같아 구분을 안 남기면 되돌릴 수 없다")
    void 폐기와_파손() {
        materialInbound(material7, 1000, 40L);

        io(material7, "폐기", 100);        // 한글로도 받는다
        io(material7, "파손", 30);

        JsonNode rows = data(get("/stock/materials/records?materialIds=" + material7));
        String kinds = rows.toString();
        assertThat(kinds).contains("폐기").contains("파손");

        int dispose = 0;
        int damage = 0;
        for (JsonNode r : rows) {
            if ("DISPOSE".equals(r.path("io").asText())) {
                dispose += -r.path("qtyDelta").asInt();
            }
            if ("DAMAGE".equals(r.path("io").asText())) {
                damage += -r.path("qtyDelta").asInt();
            }
        }
        assertThat(dispose).as("폐기 100").isEqualTo(100);
        assertThat(damage).as("파손 30 — txnType 만 보면 둘 다 DISPOSE 라 뭉친다").isEqualTo(30);
        assertThat(materialQty(material7)).isEqualTo(870);
    }

    @Test
    @DisplayName("★자재 출고·회수 — 나가고 되돌아온다(DSRE 6종)")
    void 출고와_회수() {
        materialInbound(material8, 2000, 40L);

        io(material8, "OUTBOUND", 800);
        assertThat(materialQty(material8)).as("출고하면 준다").isEqualTo(1200);

        io(material8, "RECOVER_ACCIDENT", 50);
        io(material8, "RECOVER_RETURN", 30);
        assertThat(materialQty(material8)).as("회수하면 는다").isEqualTo(1280);

        JsonNode rows = data(get("/stock/materials/records?materialIds=" + material8));
        assertThat(rows.toString())
                .contains("출고").contains("회수(사고처리용)").contains("회수(반품)");
    }

    @Test
    @DisplayName("입고는 이 API 로 안 받는다 — 거래처·단가가 빠진 채 들어가지 않게")
    void 입고는_거부() {
        JsonNode r = io(material8, "INBOUND", 10);

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("/stock/materials/inbound");
    }

    @Test
    @DisplayName("모르는 구분이면 400 — 무엇이 가능한지 알려준다")
    void 모르는_구분() {
        JsonNode r = io(material8, "분실", 1);

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText())
                .contains("분실").contains("파손");
    }

    @Test
    @DisplayName("★자재 내역(항목22) — 도서 내역과 서로 섞이지 않는다")
    void 자재_내역() {
        JsonNode rows = data(get("/stock/materials/records?fromDate=2092-01-01&toDate=2092-12-31"));
        assertThat(rows).as("자재 거래가 잡힌다").isNotEmpty();
        for (JsonNode r : rows) {
            assertThat(r.hasNonNull("materialCode")).as("자재 행만 나온다").isTrue();
        }

        // 반대 방향 — 도서 내역에는 자재가 없다
        for (JsonNode r : data(get("/stock/records?fromDate=2092-01-01&toDate=2092-12-31"))) {
            assertThat(r.hasNonNull("bookCode")).as("도서 행만 나온다").isTrue();
        }
    }

    /**
     * 검증용 도서의 수불부 현재고.
     *
     * <p>‼️수불부는 <b>페이지</b>로 온다. 예전엔 content 를 안 꺼내고 응답 객체를 그대로
     * 돌면서 합을 냈는데, 그러면 언제나 0이 나와 <b>검증이 헛돈다</b>(0 == 0 으로 통과).
     */
    private long ledgerClosing() {
        JsonNode rows = data(get("/stock/ledger?size=200&fromDate=2092-01-01&toDate=2092-12-31"
                + "&productId=" + book)).path("content");
        long sum = 0;
        for (JsonNode r : rows) {
            sum += r.path("closing").asLong();
        }
        return sum;
    }
}
