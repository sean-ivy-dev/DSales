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
 * 반품 가능내역 — 출고조건(정가·공급률)별 내역 · 외상매출장 전체 거래처.
 * 프론트 번들 실측(2026-09-16)으로 드러난 두 건.
 *
 * <pre>
 *   "반품 가능내역은 아직 준비 중입니다 — 출고조건(정가·공급률)별로 나뉜 내역이 제공되지 않습니다."
 *   "외상매출장은 거래처 1곳의 원장입니다 … (전체 거래처 한 번에 보기는 준비 중입니다)"
 * </pre>
 *
 * <p>★반품은 <b>판정 단위를 바꾸지 않았다.</b> 반품 가능 여부는 여전히 도서 단위 합계로
 * 본다(발주처 확정 2026-08-05). 조건별 내역은 <b>보여주기용</b>으로 덧붙였을 뿐이고,
 * 그래서 조건마다 '반품가능수량'을 두지 않았다 — 두면 화면이 조건마다 입력을 막게 되고
 * 그건 확정과 다른 동작이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("반품 출고조건별 내역 · 외상매출장 전체(2026-09-16)")
class ReturnableConditionIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-RC" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2086;

    private Long partner;
    private Long other;
    private Long book;
    private String bookCode;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "RCS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "RCP" + SFX, "name", "조건거래처", "type", "NORMAL"));
        other = createId("/masters/clients",
                Map.of("code", "RCO" + SFX, "name", "둘째거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "RCW" + SFX, "name", "조건창고", "type", "MAIN"));

        bookCode = "RCB" + SFX;
        Map<String, Object> b = new HashMap<>();
        b.put("code", bookCode);
        b.put("name", "조건도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "R" + YEAR + "A");
        b.put("catName", "조건분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 1000))));

        // ★같은 도서를 공급률 달리 두 번 내보낸다 — 조건이 갈리는지 보려면 이게 필요하다.
        ship(wh, partner, 70, 100);
        ship(wh, partner, 80, 40);
        ship(wh, other, 70, 30);
    }

    private void ship(Long wh, Long p, int rate, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", p, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", rate, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private JsonNode row() {
        for (JsonNode r : data(get("/sales/returnable?partnerId=" + partner)).path("rows")) {
            if (bookCode.equals(r.path("productCode").asText())) {
                return r;
            }
        }
        throw new IllegalStateException("반품가능 행 없음");
    }

    @Test
    @DisplayName("★출고조건별 내역이 실린다 — 공급률 70/80이 갈린다")
    void 조건별_내역() {
        JsonNode r = row();
        JsonNode conds = r.path("conditions");

        assertThat(conds).as("없으면 화면이 '준비 중'으로 막는다").hasSize(2);

        Map<Integer, Long> byRate = new HashMap<>();
        for (JsonNode c : conds) {
            byRate.put(c.path("supplyRate").asInt(), c.path("saleQty").asLong());
        }
        assertThat(byRate).containsEntry(70, 100L).containsEntry(80, 40L);

        // ‼️조건별 '반품가능수량'은 일부러 없다 — 판정은 도서 단위다.
        assertThat(conds.get(0).has("returnableQty"))
                .as("조건마다 가능수량을 주면 화면이 조건별로 입력을 막는다").isFalse();
    }

    @Test
    @DisplayName("★판정은 그대로 도서 단위 — 조건을 나눴다고 합계가 달라지면 안 된다")
    void 판정은_도서단위() {
        JsonNode r = row();

        assertThat(r.path("saleQty").asLong()).as("100 + 40").isEqualTo(140);
        assertThat(r.path("returnableQty").asLong()).isEqualTo(140);

        long condSum = 0;
        for (JsonNode c : r.path("conditions")) {
            condSum += c.path("saleQty").asLong();
        }
        assertThat(condSum).as("조건 합 = 도서 합계").isEqualTo(r.path("saleQty").asLong());
    }

    @Test
    @DisplayName("다른 거래처 출고분은 섞이지 않는다")
    void 거래처_격리() {
        assertThat(row().path("saleQty").asLong()).as("둘째거래처 30부는 빠져야").isEqualTo(140);
    }

    @Test
    @DisplayName("★외상매출장 전체 거래처 — 거래처마다 한 벌씩 나온다")
    void 외상매출장_전체() {
        JsonNode all = data(get("/closing/ar-ledger/all?fromDate=" + YEAR + "-01-01"
                + "&toDate=" + YEAR + "-12-31")).path("content");

        assertThat(all).as("배열이어야 한다").isNotEmpty();

        // 두 거래처가 각각 자기 원장을 갖는다 — 한 표로 합치면 러닝밸런스가 의미를 잃는다.
        boolean hasP = false;
        boolean hasO = false;
        for (JsonNode led : all) {
            if (partner.equals(led.path("partnerId").asLong())) {
                hasP = true;
                assertThat(led.path("lines")).as("원장 줄이 있어야").isNotEmpty();
            }
            if (other.equals(led.path("partnerId").asLong())) {
                hasO = true;
            }
        }
        assertThat(hasP && hasO).as("두 거래처 다 있어야").isTrue();
    }

    @Test
    @DisplayName("전체 원장도 키워드로 좁힌다")
    void 전체원장_키워드() {
        String p = "/closing/ar-ledger/all?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";
        JsonNode hit = data(get(p + "&keyword=조건거래처")).path("content");

        assertThat(hit).isNotEmpty();
        for (JsonNode led : hit) {
            assertThat(led.path("partnerName").asText()).contains("조건거래처");
        }
    }
}
