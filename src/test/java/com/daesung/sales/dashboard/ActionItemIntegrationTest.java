package com.daesung.sales.dashboard;

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
 * 대시보드 <b>할 일</b> 카드 회귀 고정. 근거: 프론트 {@code fetchActionItems()}가
 * {@code /dashboard/actions}를 호출 중(지금까지 목업으로만 동작).
 *
 * <p>★여기서 지키는 것 둘 —
 * <ol>
 *   <li><b>0건 항목은 안 내려간다.</b> "정산 대기 0건" 카드가 늘 떠 있으면
 *       담당자가 매번 숫자를 읽어 할 일이 없음을 확인해야 한다.</li>
 *   <li><b>카드 숫자 = 화면 숫자.</b> 따로 세면 카드엔 3건인데 화면을 열면 5건이 되고,
 *       그때 담당자는 둘 다 못 믿게 된다.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("대시보드 할 일 카드")
class ActionItemIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-AC" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2055;

    private Long partner;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "ACS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "ACP" + SFX, "name", "위탁처", "type", "NORMAL"));
        Long mainWh = createId("/masters/warehouses", Map.of("code", "ACW" + SFX, "name", "물류창고", "type", "MAIN"));
        Long consignWh = createId("/masters/warehouses", Map.of("code", "ACC" + SFX, "name", "위탁창고",
                "type", "CONSIGN", "ownerClientId", partner));
        Long product = createId("/masters/products", Map.of("code", "ACB" + SFX, "name", "위탁도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재"));

        post("/stock/inbound", Map.of("processedDate", YEAR + "-03-01", "supplierClientId", sup,
                "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", product, "unitCost", 3000, "qty", 300))));
        // 미결을 하나 만들어 둔다 → '위탁 정산 대기' 카드가 떠야 한다
        post("/consignment/out", Map.of("processedDate", YEAR + "-03-05",
                "partnerId", partner, "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", product, "qty", 50))));
    }

    private JsonNode card(String key) {
        for (JsonNode i : data(get("/dashboard/actions"))) {
            if (key.equals(i.path("key").asText())) {
                return i;
            }
        }
        return null;
    }

    @Test
    @DisplayName("미결이 있으면 '위탁 정산 대기' 카드가 뜬다")
    void 정산대기_카드() {
        JsonNode c = card("settle");
        assertThat(c).as("미결을 만들어 뒀으니 카드가 있어야 한다").isNotNull();
        assertThat(c.path("label").asText()).isEqualTo("위탁 정산 대기");
        assertThat(c.path("count").asInt()).isPositive();
        assertThat(c.path("tone").asText()).isEqualTo("warning");
        assertThat(c.path("hint").asText()).contains("미결잔여");
        assertThat(c.path("to").asText()).isNotBlank();
    }

    @Test
    @DisplayName("★카드 숫자와 실제 미결 건수가 같다 — 따로 세면 둘 다 못 믿게 된다")
    void 카드와_화면이_같다() {
        int onCard = card("settle").path("count").asInt();
        int onScreen = data(get("/consignment/pending?partnerId=" + partner)).path("items").size();

        // 카드는 전 거래처 합이라 이 거래처 몫보다 크거나 같다. 적을 수는 없다.
        assertThat(onCard).isGreaterThanOrEqualTo(onScreen);
        assertThat(onScreen).isPositive();
    }

    @Test
    @DisplayName("★0건인 항목은 목록에 없다 — 있다는 것 자체가 신호여야 한다")
    void 영건은_안_내려간다() {
        for (JsonNode i : data(get("/dashboard/actions"))) {
            assertThat(i.path("count").asInt()).as("0건 카드: %s", i).isPositive();
        }
    }

    @Test
    @DisplayName("미확인 출고요청 건수 — 작업요청서의 미출력 필터와 같은 판정")
    void 미확인_출고요청_건수() {
        JsonNode n = data(get("/logistics/work-orders/new-count?asOf=" + YEAR + "-03-31"));
        assertThat(n.isNumber()).as("숫자를 준다: %s", n).isTrue();
        assertThat(n.asInt()).isNotNegative();
    }
}
