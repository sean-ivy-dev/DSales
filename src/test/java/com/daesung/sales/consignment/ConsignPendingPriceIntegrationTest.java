package com.daesung.sales.consignment;

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
 * 위탁 미결 <b>단가 노출</b>(V62) 회귀 고정.
 * 근거: 개발팀 점검(2026-09-09) P0-2 — "응답에 정가·공급률·할인액이 없어 정산 공급가액을
 * 만들 수 없습니다. 위탁 미결정산 흐름 전체가 막힙니다."
 *
 * <p>★고정하려는 것은 셋이다.
 * <ol>
 *   <li>출고 시점 단가가 미결에 <b>박힌다</b> — 매출등록과 같은 우선순위로 정해진다.</li>
 *   <li>출고 뒤 마스터 정가·공급률이 바뀌어도 <b>미결 금액이 흔들리지 않는다</b>.</li>
 *   <li>금액은 <b>서버가</b> 만든다(Amounts 단일소스).</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// ★순서가 있다 — 뒤 테스트가 도서 마스터를 바꿔 '흔들리지 않음'을 확인한다.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("위탁 미결 단가 노출(P0-2)")
class ConsignPendingPriceIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CP" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2068;

    private Long partner;
    private Long book;
    private Long main;
    private Long consign;

    @BeforeAll
    void seed() {
        token();
        partner = createId("/masters/clients", Map.of("code", "CPC" + SFX, "name", "위탁거래처", "type", "NORMAL"));
        Long sup = createId("/masters/clients", Map.of("code", "CPS" + SFX, "name", "인쇄", "type", "NORMAL"));
        main = createId("/masters/warehouses", Map.of("code", "CPM" + SFX, "name", "물류창고", "type", "MAIN"));
        consign = createId("/masters/warehouses", Map.of("code", "CPG" + SFX, "name", "위탁창고",
                "type", "CONSIGN", "ownerClientId", partner));   // 위탁창고는 소속 거래처 필수

        Map<String, Object> b = new HashMap<>();
        b.put("code", "CPB" + SFX);
        b.put("name", "위탁도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("taxFree", true);          // 세액은 수동입력이라 면세로 두어 금액만 본다
        b.put("catCode", "C" + YEAR + (System.nanoTime() % 100));
        b.put("catName", "위탁분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", main,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));

        // 단가 미입력 → 도서 마스터(10000 · 70%)가 자동 적용되어야 한다
        JsonNode r = post("/consignment/out", Map.of(
                "processedDate", YEAR + "-02-01", "partnerId", partner,
                "fromWarehouseId", main, "toWarehouseId", consign,
                "items", List.of(Map.of("productId", book, "qty", 100))));
        assertThat(r.path("success").asBoolean()).as("위탁출고: %s", r).isTrue();
    }

    private JsonNode line() {
        JsonNode items = data(get("/consignment/pending?partnerId=" + partner)).path("items");
        assertThat(items).as("미결이 있어야 한다").isNotEmpty();
        return items.get(0);
    }

    @Test
    @Order(1)
    @DisplayName("★미결에 정가·공급률이 실린다 — 없으면 화면이 정산 금액을 못 만든다")
    void 단가가_실린다() {
        JsonNode l = line();

        assertThat(l.path("unitPrice").asInt()).as("도서 마스터 정가 자동적용").isEqualTo(10000);
        assertThat(l.path("supplyRate").asInt()).as("도서 기본공급률 자동적용").isEqualTo(70);
        assertThat(l.path("remainingQty").asInt()).isEqualTo(100);
    }

    @Test
    @Order(2)
    @DisplayName("★공급가액은 서버가 계산한다 — 화면이 곱하면 반올림이 갈린다")
    void 서버가_금액을_준다() {
        assertThat(line().path("remainingSupplyAmount").asLong())
                .as("10000 × 70% × 100").isEqualTo(700_000);
    }

    @Test
    @Order(3)
    @DisplayName("★출고 뒤 마스터 정가를 바꿔도 미결 금액은 그대로 — 나간 물건의 조건은 나갈 때 값이다")
    void 마스터를_바꿔도_흔들리지_않는다() {
        JsonNode before = line();
        assertThat(before.path("unitPrice").asInt()).isEqualTo(10000);

        // 도서 정가를 20000으로 인상
        JsonNode up = put("/masters/products/" + book, Map.of(
                "name", "위탁도서", "contentType", "SELF", "price", 20000, "supplyRate", 90));
        assertThat(up.path("success").asBoolean()).as("정가 인상: %s", up).isTrue();

        JsonNode after = line();
        assertThat(after.path("unitPrice").asInt())
                .as("‼️마스터를 다시 읽었다면 20000이 됐을 것이다").isEqualTo(10000);
        assertThat(after.path("supplyRate").asInt()).isEqualTo(70);
        assertThat(after.path("remainingSupplyAmount").asLong()).isEqualTo(700_000);
    }

    @Test
    @Order(4)
    @DisplayName("출고 시 단가를 직접 주면 그 값이 박힌다(입력값 우선)")
    void 입력값_우선() {
        JsonNode r = post("/consignment/out", Map.of(
                "processedDate", YEAR + "-03-01", "partnerId", partner,
                "fromWarehouseId", main, "toWarehouseId", consign,
                "items", List.of(Map.of("productId", book, "qty", 50,
                        "unitPrice", 12000, "supplyRate", 60))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        JsonNode items = data(get("/consignment/pending?partnerId=" + partner)).path("items");
        JsonNode second = null;
        for (JsonNode n : items) {
            if (n.path("remainingQty").asInt() == 50) {
                second = n;
            }
        }
        assertThat(second).isNotNull();
        assertThat(second.path("unitPrice").asInt()).isEqualTo(12000);
        assertThat(second.path("supplyRate").asInt()).isEqualTo(60);
        assertThat(second.path("remainingSupplyAmount").asLong())
                .as("12000 × 60% × 50").isEqualTo(360_000);
    }
}
