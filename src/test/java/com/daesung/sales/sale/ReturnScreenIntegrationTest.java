package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 화면28(입고/대체등록 — 물류) 회귀 고정.
 * 근거: 발주처 화면검토(2026-08-31) 화면28 4개 항목.
 *
 * <ol>
 *   <li>"입고등록과 반품등록은 처리 방식이 다른 만큼 <b>화면·기능이 구분</b>되어야 합니다"
 *       → 서버는 이미 별도 엔드포인트({@code /stock/inbound} vs {@code /sales/return-inbound}).
 *       재고만 늘리는 입고와 <b>매출 반품까지 만드는</b> 반품은 결과가 다르다.</li>
 *   <li>"반품등록시 공급률은 원 출고건 값을 그대로 따르지 않고 <b>수정 가능</b>하도록"(8/4 확정)</li>
 *   <li>"<b>공급률별 출고내역 구분 조회</b>"</li>
 *   <li>"출고내역보다 반품 등록 내역이 더 많이 입력되는 경우 <b>경고 알림(alert)</b>"</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// ★순서가 있다 — 앞 테스트의 반품이 뒤 테스트의 잔여를 바꾼다.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("화면28 입고/반품(물류)")
class ReturnScreenIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-RS" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2064;

    private Long partner;
    private Long book;
    private Long wh;

    @BeforeAll
    void seed() {
        token();
        partner = createId("/masters/clients", Map.of("code", "RSC" + SFX, "name", "반품거래처", "type", "NORMAL"));
        Long sup = createId("/masters/clients", Map.of("code", "RSS" + SFX, "name", "인쇄", "type", "NORMAL"));
        wh = createId("/masters/warehouses", Map.of("code", "RSW" + SFX, "name", "물류창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "RSB" + SFX);
        b.put("name", "반품도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "R" + YEAR + (System.nanoTime() % 100));
        b.put("catName", "반품분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));

        // ★같은 도서를 공급률 달리 두 번 출고한다 — 공급률별 구분 조회를 보려면 필요하다
        sale(70, 30);
        sale(50, 20);
    }

    private void sale(int supplyRate, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", supplyRate, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("출고: %s", r).isTrue();
    }

    private JsonNode returnInbound(int supplyRate, int qty) {
        return post("/sales/return-inbound", Map.of(
                "returnDate", YEAR + "-03-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book,
                        "unitPrice", 10000, "supplyRate", supplyRate, "qty", qty))));
    }

    private JsonNode returnableRow() {
        for (JsonNode n : data(get("/sales/returnable?partnerId=" + partner)).path("rows")) {
            if (("RSB" + SFX).equals(n.path("productCode").asText())) {
                return n;
            }
        }
        return null;
    }

    @Test
    @Order(1)
    @DisplayName("★공급률별로 출고내역이 구분돼 조회된다 — 같은 도서라도 70%와 50%가 따로 보인다")
    void 공급률별_구분조회() {
        // returnable은 도서 단위 합계(반품 범위 판정과 같은 기준)
        assertThat(returnableRow().path("returnableQty").asLong()).as("30 + 20").isEqualTo(50);

        // 공급률별 낱건은 통합매출조회에서 갈라 본다
        List<Integer> rates = new java.util.ArrayList<>();
        for (JsonNode n : data(get("/sales?startDate=" + YEAR + "-01-01&endDate=" + YEAR + "-12-31"
                + "&partnerIds=" + partner + "&salesCategories=SALE&size=200")).path("content")) {
            rates.add(n.path("supplyRate").asInt());
        }
        assertThat(rates).as("70%·50% 두 건이 구분돼야 한다").containsExactlyInAnyOrder(70, 50);
    }

    @Test
    @Order(2)
    @DisplayName("★공급률을 원 출고건과 다르게 넣어도 반품된다(8/4 확정) — 잠금이면 실무가 막힌다")
    void 공급률_수정가능() {
        JsonNode r = returnInbound(65, 10);   // 출고는 70·50뿐인데 65로 반품

        assertThat(r.path("success").asBoolean()).as("공급률 수정 반품: %s", r).isTrue();
        assertThat(r.path("data").path("warnings")).as("범위 안이라 경고 없음").isEmpty();
        assertThat(returnableRow().path("returnableQty").asLong()).as("50 − 10").isEqualTo(40);
    }

    @Test
    @Order(3)
    @DisplayName("★출고보다 반품이 많아도 막지 않고 경고한다 — 실제로 들어온 물건은 적을 수 있어야 한다")
    void 초과는_차단이_아니라_경고() {
        JsonNode r = returnInbound(70, 45);   // 잔여 40인데 45

        assertThat(r.path("success").asBoolean()).as("등록은 된다: %s", r).isTrue();

        JsonNode w = r.path("data").path("warnings");
        assertThat(w).hasSize(1);
        assertThat(w.get(0).path("code").asText()).isEqualTo("RETURN_EXCEEDS");
        assertThat(w.get(0).path("returnableQty").asLong()).isEqualTo(40);
        assertThat(w.get(0).path("requestedQty").asInt()).isEqualTo(45);
        assertThat(w.get(0).path("exceededQty").asLong()).as("45 − 40").isEqualTo(5);
        assertThat(w.get(0).path("message").asText())
                .as("화면에 그대로 띄울 수 있어야 한다").contains("RSB" + SFX);
    }

    @Test
    @Order(4)
    @DisplayName("★초과로 음수가 된 도서가 조회에서 사라지지 않는다 — 보여야 고친다")
    void 음수도_보인다() {
        assertThat(returnableRow())
                .as("‼️>0 으로 거르면 초과된 도서가 통째로 사라져 담당자가 고칠 방법이 없다")
                .isNotNull();
        assertThat(returnableRow().path("returnableQty").asLong()).as("40 − 45").isEqualTo(-5);
    }

    @Test
    @Order(5)
    @DisplayName("입고와 반품은 결과가 다르다 — 입고는 재고만, 반품은 매출 반품까지 만든다")
    void 입고와_반품은_다르다() {
        long before = returnCount();

        JsonNode in = post("/stock/inbound", Map.of("processedDate", YEAR + "-04-01",
                "supplierClientId", partner, "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 7))));
        assertThat(in.path("success").asBoolean()).as("일반입고도 정상 동작: %s", in).isTrue();

        assertThat(returnCount())
                .as("입고는 매출을 만들지 않는다 — 만들면 반품과 구분이 사라진다")
                .isEqualTo(before);

        returnInbound(70, 3);
        assertThat(returnCount()).as("반품은 매출 반품 라인을 만든다").isEqualTo(before + 1);
    }

    private long returnCount() {
        long n = 0;
        for (JsonNode x : data(get("/sales?startDate=" + YEAR + "-01-01&endDate=" + YEAR + "-12-31"
                + "&partnerIds=" + partner + "&salesCategories=RETURN&size=200")).path("content")) {
            if (x.path("productCode").asText().equals("RSB" + SFX)) {
                n++;
            }
        }
        return n;
    }
}
