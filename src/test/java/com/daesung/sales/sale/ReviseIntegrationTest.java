package com.daesung.sales.sale;

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
 * 매출·폐기 <b>수정</b>(프론트 회신 2026-10-08 [A]2 / 9/27 회의 항목 7·9·12).
 *
 * <p>★<b>레거시에 참고구현이 없다.</b> {@code UC_TabPages.vb:800} 주변의 UPDATE 문은
 * 주석 처리돼 있고 "update의 경우에는 생각할게 많다 … 그냥 삭제하고 넣는게 최선인가?"라는
 * 당시 개발자 메모가 남아 있다. 그래서 두 방식이 갈린 이유를 테스트로 고정한다 —
 * <ul>
 *   <li><b>매출은 제자리 수정</b>(매출번호 유지). 거래명세서·작업요청서·DSRE 신청번호가 그 번호에 매달려 있다.</li>
 *   <li><b>폐기는 삭제+재등록</b>(새 전표번호). 제자리 수정이면 폐기 목록과 요약이 어긋나고,
 *       취소+재등록이면 원 수량이 기간 합계에 남는다.</li>
 * </ul>
 *
 * <p>가장 중요한 검증은 <b>장부와 재고가 갈리지 않는가</b>다. 수량을 고쳤는데 재고가 그대로면
 * 매출은 15부인데 창고는 10부만 나간 것으로 남아, 실사 때까지 아무도 모른다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("매출·폐기 수정(2026-10-08 [A]2)")
class ReviseIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-RV" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2103-04-10";
    private static final String RANGE = "?fromDate=2103-01-01&toDate=2103-12-31";

    private Long partner;
    private Long warehouse;
    private Long supplier;
    private Long bookQty;      // 수량 수정
    private Long bookPrice;    // 금액 수정
    private Long bookGuard;    // 거부 경로
    private Long bookTaxed;    // 과세(세액 입력 가능)
    private Long bookDispose;  // 폐기 수정

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients",
                Map.of("code", "RVS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "RVP" + SFX, "name", "수정검증거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "RVW" + SFX, "name", "수정검증창고", "type", "MAIN"));

        bookQty = book("RVA" + SFX, "수량수정 교재", true);
        bookPrice = book("RVB" + SFX, "금액수정 교재", true);
        bookGuard = book("RVC" + SFX, "거부경로 교재", true);
        bookTaxed = book("RVD" + SFX, "과세 교재", false);
        bookDispose = book("RVE" + SFX, "폐기수정 교재", true);
    }

    private Long book(String code, String name, boolean taxFree) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "R2103A01");
        b.put("catName", "수정검증분류");
        b.put("taxFree", taxFree);
        b.put("salesDivision", "교재");
        Long id = createId("/masters/products", b);
        post("/stock/inbound", Map.of("processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", id, "unitCost", 1000, "qty", 1000))));
        return id;
    }

    /** 매출 1건 등록 → 그 매출의 id. */
    private long sale(Long product, int qty, int unitPrice) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", unitPrice);
        item.put("supplyRate", 70);
        item.put("qty", qty);
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출등록: %s", r).isTrue();
        String salesNo = data(r).path("items").get(0).path("salesNo").asText();
        return idOfSale(salesNo);
    }

    private long idOfSale(String salesNo) {
        JsonNode rows = data(get("/sales" + RANGE + "&size=200&keyword=" + salesNo)).path("content");
        for (JsonNode row : rows) {
            if (salesNo.equals(row.path("salesNo").asText())) {
                return row.path("id").asLong();
            }
        }
        throw new AssertionError("매출을 못 찾았다: " + salesNo + " / " + rows);
    }

    private JsonNode saleRow(long id) {
        JsonNode rows = data(get("/sales" + RANGE + "&size=200&partnerIds=" + partner)).path("content");
        for (JsonNode row : rows) {
            if (row.path("id").asLong() == id) {
                return row;
            }
        }
        throw new AssertionError("매출 id=" + id + " 가 조회에 없다");
    }

    /** 그 도서의 창고 재고(수불부의 단일 공식 = 이벤트 합계). */
    private long stock(Long product) {
        JsonNode rows = data(get("/stock/ledger" + RANGE + "&size=200&productId=" + product))
                .path("content");
        assertThat(rows).as("수불부에 그 도서가 있어야 한다").isNotEmpty();
        return rows.get(0).path("closing").asLong();
    }

    @Test
    @DisplayName("★★수량을 고치면 금액과 재고가 함께 따라간다 — 갈리면 실사 때까지 모른다")
    void 수량_수정() {
        long id = sale(bookQty, 10, 10_000);
        long stockBefore = stock(bookQty);     // 1000 − 10 = 990

        JsonNode r = patch("/sales/" + id, Map.of("qty", 15));
        assertThat(r.path("success").asBoolean()).as("수정: %s", r).isTrue();

        JsonNode row = saleRow(id);
        assertThat(row.path("qty").asInt()).isEqualTo(15);
        assertThat(row.path("supplyAmount").asLong()).as("7,000 × 15").isEqualTo(105_000);
        assertThat(row.path("totalAmount").asLong()).as("면세라 세액 0").isEqualTo(105_000);

        // ★재고는 5부 더 빠져야 한다. 차액 이벤트가 같은 매출번호·원 거래일로 들어간다.
        assertThat(stock(bookQty)).as("990 → 985").isEqualTo(stockBefore - 5);
    }

    @Test
    @DisplayName("★수량을 줄이면 재고가 돌아온다 — 반품 버킷으로 가지 않는다")
    void 수량_감소() {
        long id = sale(bookQty, 20, 10_000);
        long before = stock(bookQty);

        patch("/sales/" + id, Map.of("qty", 8));

        assertThat(saleRow(id).path("qty").asInt()).isEqualTo(8);
        assertThat(stock(bookQty)).as("12부가 돌아온다").isEqualTo(before + 12);

        // ‼️차액을 반품으로 기록하면 수불부의 반품 칸이 부풀고 매출 칸이 줄지 않는다.
        //   원 이벤트와 같은 버킷(매출)이라 매출 수량 합계가 곧 수정 후 수량이어야 한다.
        JsonNode led = data(get("/stock/ledger" + RANGE + "&size=200&productId=" + bookQty))
                .path("content").get(0);
        assertThat(led.path("returnQty").asLong()).as("반품 칸은 그대로 0").isZero();
    }

    @Test
    @DisplayName("★보내지 않은 항목은 유지된다 — 수량만 고치려다 메모가 지워지면 안 된다")
    void 미지정_항목_유지() {
        long id = sale(bookPrice, 10, 10_000);
        patch("/sales/" + id, Map.of("memo", "오입력 정정", "bookRound", 3));

        JsonNode row = saleRow(id);
        assertThat(row.path("memo").asText()).isEqualTo("오입력 정정");
        assertThat(row.path("bookRound").asInt()).isEqualTo(3);
        assertThat(row.path("qty").asInt()).as("수량은 그대로").isEqualTo(10);
        assertThat(row.path("unitPrice").asInt()).as("정가도 그대로").isEqualTo(10_000);

        // 정가만 바꿔도 금액이 다시 계산된다.
        patch("/sales/" + id, Map.of("unitPrice", 20_000));
        JsonNode after = saleRow(id);
        assertThat(after.path("supplyAmount").asLong()).as("14,000 × 10").isEqualTo(140_000);
        assertThat(after.path("memo").asText()).as("메모는 살아 있다").isEqualTo("오입력 정정");
    }

    @Test
    @DisplayName("★★메모만 고쳐도 금액이 0원이 되던 것 — 42.9억이 걸려 있었다")
    void 금액입력_안보내면_금액을_건드리지_않는다() {
        // 이관 자료의 그 모양을 만든다 — **계산값과 저장금액이 다른 줄**.
        //   실제 줄은 수량·정가·공급률이 0이고 금액만 있다(올해 116줄·약 42.9억,
        //   예: DL-364378 47,636,030원). 정가는 @Positive 라 0으로 못 넣으니
        //   공급률 0으로 같은 상태(계산값 0원)를 만든다.
        Map<String, Object> item = new HashMap<>();
        item.put("productId", bookPrice);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10_000);
        item.put("supplyRate", 0);          // → 계산값 0원
        item.put("qty", 1);
        item.put("supplyAmount", 47_636_030L);
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("등록: %s", r).isTrue();
        long id = idOfSale(data(r).path("items").get(0).path("salesNo").asText());
        assertThat(saleRow(id).path("supplyAmount").asLong()).isEqualTo(47_636_030L);

        // ‼️메모만 고친다. 예전엔 여기서 금액이 0원이 됐다.
        patch("/sales/" + id, Map.of("memo", "담당자 메모"));

        JsonNode after = saleRow(id);
        assertThat(after.path("supplyAmount").asLong())
                .as("금액 입력을 안 보냈으니 저장된 금액 그대로")
                .isEqualTo(47_636_030L);
        assertThat(after.path("memo").asText()).isEqualTo("담당자 메모");

        // 회차·성적처리처럼 금액과 무관한 칸도 마찬가지다.
        patch("/sales/" + id, Map.of("bookRound", 2));
        assertThat(saleRow(id).path("supplyAmount").asLong()).isEqualTo(47_636_030L);

        // ★단, 금액 입력을 **보내면** 종전대로 다시 계산한다 — 이 규칙은 그대로다.
        patch("/sales/" + id, Map.of("supplyRate", 70));
        assertThat(saleRow(id).path("supplyAmount").asLong())
                .as("공급률을 보냈으니 10,000 × 70% × 1").isEqualTo(7_000);
    }

    @Test
    @DisplayName("★매출번호는 바뀌지 않는다 — 거래명세서·작업요청서가 그 번호에 매달려 있다")
    void 매출번호_유지() {
        long id = sale(bookPrice, 5, 10_000);
        String before = saleRow(id).path("salesNo").asText();

        patch("/sales/" + id, Map.of("qty", 6));

        assertThat(saleRow(id).path("salesNo").asText()).isEqualTo(before);
    }

    @Test
    @DisplayName("세액은 자동산출하지 않는다 — 면세 상품에 넣으면 400")
    void 세액_규칙() {
        long free = sale(bookQty, 3, 10_000);
        JsonNode r = patch("/sales/" + free, Map.of("tax", 500));
        assertThat(r.path("success").asBoolean()).as("면세에 세액: %s", r).isFalse();

        long taxed = sale(bookTaxed, 3, 10_000);
        assertThat(patch("/sales/" + taxed, Map.of("tax", 500)).path("success").asBoolean()).isTrue();
        assertThat(saleRow(taxed).path("tax").asLong()).isEqualTo(500);
        // 수량만 바꿔도 직접 입력한 세액은 유지된다(다시 10%로 계산하지 않는다).
        patch("/sales/" + taxed, Map.of("qty", 4));
        assertThat(saleRow(taxed).path("tax").asLong()).as("세액은 담당자가 넣은 값 그대로").isEqualTo(500);
    }

    @Test
    @DisplayName("빈 요청·취소된 매출은 400 — 조용히 성공하면 담당자는 반영된 줄 안다")
    void 거부_경로() {
        long id = sale(bookGuard, 7, 10_000);

        JsonNode empty = patch("/sales/" + id, Map.of());
        assertThat(empty.path("success").asBoolean()).isFalse();
        assertThat(empty.path("error").path("message").asText()).contains("바꿀 항목이 없");

        post("/sales/" + id + "/cancel?reason=오입력", Map.of());
        JsonNode canceled = patch("/sales/" + id, Map.of("qty", 1));
        assertThat(canceled.path("success").asBoolean()).isFalse();
        assertThat(canceled.path("error").path("message").asText()).contains("취소된 매출");
    }

    @Test
    @DisplayName("★★마감된 달은 수정할 수 없다 — 신고가 끝난 숫자가 조용히 바뀌면 안 된다")
    void 마감된_달() {
        long id = sale(bookGuard, 9, 10_000);
        JsonNode locked = post("/closing/periods/lock",
                Map.of("year", 2103, "month", 4, "memo", "수정검증 마감"));
        // ‼️마감이 실제로 걸렸는지 먼저 본다. 안 걸린 채 아래를 돌면 "수정이 막혔다"가 아니라
        //   "마감 요청이 실패했다"를 통과시킨다(처음에 엔드포인트 경로를 틀려 그렇게 통과했다).
        assertThat(locked.path("success").asBoolean()).as("마감: %s", locked).isTrue();
        try {
            JsonNode r = patch("/sales/" + id, Map.of("qty", 1));
            assertThat(r.path("success").asBoolean()).isFalse();
            assertThat(r.path("error").path("code").asText()).isEqualTo("PERIOD_LOCKED");

            // 폐기도 같은 규칙이다.
            JsonNode d = patch("/disposals/P-없는전표", Map.of(
                    "processedDate", DATE, "warehouseId", warehouse,
                    "items", List.of(Map.of("productId", bookDispose, "qty", 1))));
            assertThat(d.path("success").asBoolean()).isFalse();
            assertThat(d.path("error").path("code").asText())
                    .as("마감 검사가 전표 조회보다 먼저여도, 둘 중 하나로 막히면 된다: %s", d)
                    .isIn("PERIOD_LOCKED", "NOT_FOUND");
        } finally {
            // ‼️반드시 풀어 둔다. 마감은 월 단위 전역 상태라 남겨 두면 뒤에 도는
            //   다른 테스트의 2103년 4월 쓰기가 전부 PERIOD_LOCKED 로 깨진다.
            post("/closing/periods/unlock", Map.of("year", 2103, "month", 4, "memo", "검증 종료"));
        }
    }

    @Test
    @DisplayName("★★폐기 수정 — 새 전표번호가 나가고 기간 합계는 수정 후 수량만 남는다")
    void 폐기_수정() {
        JsonNode created = post("/disposals", Map.of(
                "processedDate", DATE, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", bookDispose, "qty", 200, "reason", "파본"))));
        String oldNo = data(created).path("disposalNo").asText();

        JsonNode r = patch("/disposals/" + oldNo, Map.of(
                "processedDate", DATE, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", bookDispose, "qty", 150, "reason", "수량 정정"))));
        assertThat(r.path("success").asBoolean()).as("폐기 수정: %s", r).isTrue();

        String newNo = data(r).path("disposalNo").asText();
        assertThat(newNo).as("새 전표번호가 나간다").isNotEqualTo(oldNo);

        // ★원 전표는 목록에서 사라진다(삭제 = 애초에 없던 일).
        JsonNode rows = data(get("/disposals" + RANGE + "&size=200&productIds=" + bookDispose))
                .path("content");
        long sum = 0;
        for (JsonNode row : rows) {
            assertThat(row.path("disposalNo").asText()).as("원 전표가 남아 있다").isNotEqualTo(oldNo);
            sum += row.path("qty").asLong();
        }
        // ‼️취소+재등록으로 했다면 200(취소)+150 = 350 이 됐을 자리다.
        assertThat(sum).as("상세 합계는 수정 후 수량만").isEqualTo(150);
        assertThat(data(get("/disposals/summary" + RANGE + "&productIds=" + bookDispose))
                .path("totalQty").asLong()).as("요약도 같은 숫자").isEqualTo(150);
    }

    @Test
    @DisplayName("폐기가 아닌 전표는 수정 대상이 아니다")
    void 폐기_아닌_전표() {
        JsonNode inbound = post("/stock/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", bookDispose, "unitCost", 1000, "qty", 10))));
        String inboundNo = data(inbound).path("inboundNo").asText();

        JsonNode r = patch("/disposals/" + inboundNo, Map.of(
                "processedDate", DATE, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", bookDispose, "qty", 1))));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("폐기 전표가 아닙니다");
    }
}
