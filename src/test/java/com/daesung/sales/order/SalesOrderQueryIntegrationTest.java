package com.daesung.sales.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 주문조회(통합) — 조회 대상을 <b>우리 주문</b>으로(§1·§2-1·§2-2 · 프론트 A-2·A-3).
 *
 * <p>정본 §1 — "조회 대상 … 지금은 DSRE 주문(신청번호·지사코드)을 읽어 온다. 이를
 * <b>새 특약점 주문 + 매출에서 수기 등록한 주문</b>으로 바꾼다."
 * §2-2 — "<b>신규 주문 등록 → 조회 미표시(버그)</b>: 등록한 주문이 [주문 조회]에 나오지 않고
 * [출고/반품 조회]와 [작업요청서]에만 넘어간다. <b>등록 즉시</b> 주문 조회에 보여야 한다."
 *
 * <p>★<b>이 테스트의 급소는 상태 필터다.</b> 주문 목록을 DB 에서 걸러 페이징하려면
 * 진행상태를 SQL 로 알아야 해서, 랭크 식이 {@code ShipmentRepository.ORDER_STATE_RANK}(SQL)와
 * {@code Shipment.progressState()}(Java) <b>두 군데</b>에 있다. 어긋나면 "상태로 걸렀는데
 * 다른 상태가 나온다"가 된다 — 그래서 <b>전 상태를 하나씩</b> 맞춰 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("주문조회(통합) = 우리 주문(§1 · A-2·A-3)")
class SalesOrderQueryIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SO" + (System.nanoTime() % 1_000_000L);
    private static final String PARTNER_CODE = "SOP" + SFX;
    private static final String DATE = "2094-06-15";
    private static final String RANGE = "?fromDate=2094-06-01&toDate=2094-06-30";

    private Long partner;
    private Long wh;
    private Long book;
    private Long mockBook;      // 세부구분이 달라 발송이 갈리는 도서

    @Autowired
    private ShipmentRepository shipmentRepository;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "SOS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", PARTNER_CODE, "name", "주문조회거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "SOW" + SFX, "name", "물류창고", "type", "MAIN"));

        book = product("SOB" + SFX, "주문조회도서", "교재");
        mockBook = product("SOM" + SFX, "주문조회모의고사", "모의고사");

        for (Long p : List.of(book, mockBook)) {
            assertThat(post("/stock/inbound", Map.of(
                    "processedDate", "2094-01-05", "supplierClientId", supplier,
                    "destinationWarehouseId", wh,
                    "items", List.of(Map.of("productId", p, "unitCost", 100, "qty", 999))))
                    .path("success").asBoolean()).isTrue();
        }
    }

    private Long product(String code, String name, String salesDivision) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "S2094A01");
        b.put("catName", "주문조회검증");
        b.put("salesDivision", salesDivision);
        return createId("/masters/products", b);
    }

    /** 매출등록(수기 주문). 등록번호를 돌려준다. */
    private String register(Long... products) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Long p : products) {
            Map<String, Object> it = new HashMap<>();
            it.put("productId", p);
            it.put("shipmentType", "NORMAL_SHIP");
            it.put("unitPrice", 10000);
            it.put("supplyRate", 70);
            it.put("qty", 10);
            items.add(it);
        }
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", wh, "items", items));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r).path("entryNo").asText();
    }

    private List<JsonNode> ordersOf(String extraQuery) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode o : data(get("/orders" + RANGE + "&partnerId=" + partner + extraQuery))
                .path("content")) {
            out.add(o);
        }
        return out;
    }

    @Test
    @DisplayName("★★등록 즉시 주문조회에 나온다 — §2-2 버그(DSRE 신청을 읽어 한 줄도 안 늘던 것)")
    void 등록즉시_조회된다() {
        String entryNo = register(book);

        List<JsonNode> rows = ordersOf("");
        assertThat(rows).as("우리 주문이 목록에 있다").isNotEmpty();

        JsonNode mine = rows.stream()
                .filter(o -> entryNo.equals(o.path("orderNo").asText()))
                .findFirst().orElse(null);
        assertThat(mine).as("등록한 주문번호로 찾힌다: %s", rows).isNotNull();
        assertThat(mine.path("partnerCode").asText()).isEqualTo(PARTNER_CODE);
        assertThat(mine.path("totalQty").asLong()).isEqualTo(10);
        assertThat(mine.path("totalAmount").asLong()).as("10,000×70%×10").isEqualTo(70_000L);
        assertThat(mine.path("lines")).hasSize(1);
        assertThat(mine.path("tradeSeq").asInt()).isPositive();
        // 수기 등록은 접수 단계를 지나 상품준비중에서 시작한다(특약점 주문만 접수완료).
        assertThat(mine.path("progressState").asText()).isEqualTo("PREPARING");
        assertThat(mine.path("progressStateName").asText()).isEqualTo("상품준비중");
    }

    @Test
    @DisplayName("★한 주문이 분류 때문에 발송 둘로 갈려도 한 줄이다(§3-6) — 섞인 칸은 비운다")
    void 발송이_갈려도_한줄() {
        // 교재 + 모의고사를 한 번에 등록 → 거래분류가 달라 발송 건이 둘로 갈린다.
        String entryNo = register(book, mockBook);

        JsonNode mine = ordersOf("").stream()
                .filter(o -> entryNo.equals(o.path("orderNo").asText()))
                .findFirst().orElse(null);
        assertThat(mine).as("한 줄로 찾힌다").isNotNull();

        assertThat(mine.path("shipmentIds")).as("갈린 발송이 둘").hasSize(2);
        assertThat(mine.path("lines")).as("줄은 둘 다 담긴다").hasSize(2);
        assertThat(mine.path("totalQty").asLong()).as("20부").isEqualTo(20);
        // 분류가 섞였으니 비운다 — 아무거나 고르면 담당자가 그 값으로 주문을 특정했다고 믿는다.
        assertThat(mine.path("tradeClass").isNull() || mine.path("tradeClass").asText().isEmpty())
                .as("분류는 비어야: %s", mine.path("tradeClass")).isTrue();
    }

    @Test
    @DisplayName("★★진행상태 필터 — 여섯 상태를 실제로 만들어, SQL 랭크식과 Java 판정이 일치하는지 본다")
    void 상태필터가_판정과_일치() {
        // 여섯 상태를 **실제로** 만든다. 하나라도 안 밟으면 랭크 식이 어긋나도 테스트가 통과한다.
        String preparing = register(book);                 // 아무것도 안 하면 상품준비중
        String received = registerWithIntake(OrderState.RECEIVED);
        String inspecting = registerWithIntake(OrderState.INSPECTING);

        String readyNo = register(book);                   // 출력하면 발송준비중
        for (long id : shipmentIdsOf(readyNo)) {
            assertThat(post("/logistics/work-orders/" + id + "/print", Map.of())
                    .path("success").asBoolean()).isTrue();
        }

        String shippedNo = register(book);                 // 발송일이 있으면 발송완료
        for (long id : shipmentIdsOf(shippedNo)) {
            assertThat(put("/logistics/work-orders/" + id + "/shipping",
                    Map.of("boxCount", 1, "sentDate", DATE)).path("success").asBoolean())
                    .as("발송 처리").isTrue();
        }

        String canceledNo = register(book);                // 발송 건을 지우면 삭제
        for (long id : shipmentIdsOf(canceledNo)) {
            assertThat(del("/logistics/work-orders/" + id).path("success").asBoolean())
                    .as("발송 건 삭제").isTrue();
        }

        Map<String, String> expected = new HashMap<>();
        expected.put(received, "RECEIVED");
        expected.put(inspecting, "INSPECTING");
        expected.put(preparing, "PREPARING");
        expected.put(readyNo, "READY_TO_SHIP");
        expected.put(shippedNo, "SHIPPED");
        expected.put(canceledNo, "CANCELED");

        // ① 필터 없이 받은 목록의 상태가 기대와 같다(Java 판정 쪽).
        Map<String, String> actual = new HashMap<>();
        for (JsonNode o : ordersOf("")) {
            actual.put(o.path("orderNo").asText(), o.path("progressState").asText());
        }
        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertThat(actual.get(e.getKey()))
                    .as("주문 %s 의 상태", e.getKey()).isEqualTo(e.getValue());
        }

        // ② 상태로 걸렀을 때 **그 주문이 들어오고, 다른 상태는 안 섞인다**(SQL 랭크식 쪽).
        //    둘이 어긋나면 "상태로 걸렀는데 다른 상태가 나온다"가 된다.
        for (Map.Entry<String, String> e : expected.entrySet()) {
            List<String> nos = new ArrayList<>();
            for (JsonNode o : ordersOf("&state=" + e.getValue())) {
                assertThat(o.path("progressState").asText())
                        .as("%s 로 걸렀는데 다른 상태: %s", e.getValue(), o).isEqualTo(e.getValue());
                nos.add(o.path("orderNo").asText());
            }
            assertThat(nos).as("%s 필터에 %s 가 들어온다", e.getValue(), e.getKey())
                    .contains(e.getKey());
        }

        // ③ 상태별 합 == 전체. 어느 상태에도 안 걸리는 행이 있으면 랭크 식이 Java 와 어긋난 것이다.
        int all = ordersOf("").size();
        int sum = 0;
        for (OrderState st : OrderState.values()) {
            sum += ordersOf("&state=" + st.name()).size();
        }
        assertThat(sum).as("상태별 합 == 전체(빠지는 상태가 없다)").isEqualTo(all);
    }

    /**
     * 접수 단계를 세운 주문. ‼️접수완료·상품검수로 <b>바꾸는 API 는 아직 없다</b>(A-4) —
     * 특약점 접수(A-1)만 접수완료를 만든다. 여기서는 저장값을 직접 세운다.
     */
    private String registerWithIntake(OrderState state) {
        String entryNo = register(book);
        for (Shipment s : shipmentRepository.findByEntryNo(entryNo)) {
            s.applyIntakeState(state);
            shipmentRepository.save(s);
        }
        return entryNo;
    }

    private List<Long> shipmentIdsOf(String entryNo) {
        List<Long> ids = new ArrayList<>();
        for (Shipment s : shipmentRepository.findByEntryNo(entryNo)) {
            ids.add(s.getId());
        }
        assertThat(ids).as("등록번호 %s 의 발송 건", entryNo).isNotEmpty();
        return ids;
    }

    @Test
    @DisplayName("키워드로 거래처명·주문번호를 함께 훑는다")
    void 키워드_조회() {
        String entryNo = register(book);

        assertThat(ordersOf("&keyword=주문조회거래처")).as("거래처명").isNotEmpty();
        List<JsonNode> byNo = ordersOf("&keyword=" + entryNo);
        assertThat(byNo).as("주문번호").isNotEmpty();
        assertThat(byNo.get(0).path("orderNo").asText()).isEqualTo(entryNo);
    }

    @Test
    @DisplayName("엑셀도 같은 조건으로 내려온다")
    void 엑셀() {
        register(book);
        var resp = getBytes("/orders/export" + RANGE + "&partnerId=" + partner);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("%s", resp.getStatusCode()).isTrue();
        assertThat(resp.getBody()).as("빈 파일이 아니다").isNotEmpty();
    }
}
