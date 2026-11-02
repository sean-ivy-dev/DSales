package com.daesung.sales.receivable;

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
 * 외상매출현황 — <b>매입처는 행도 합계도 빼야 한다</b>. 근거: 10/27 검수 피드백 §7-C
 * "이 화면에서 구분이 '매입처'인 행은 모두 뺀다 / 합계 잔액에 매입처 잔액이 들어가면 안 된다"
 * (9월 기준 레거시 합계 2,403,280,543원 vs 테스트 버전 30,374,190,444원 — 차이가 매입처였다).
 *
 * <p>★<b>외상매출장(24p)은 그대로다.</b> 거기도 같이 걸러 버리면 매입처 원장과 일괄 메일이
 * 통째로 사라진다 — 화면이 다르면 기준도 다를 수 있어 조회 메서드에만 플래그를 줬다.
 *
 * <p>매입처 판정은 <b>매입입고(inboundType=PURCHASE)</b>가 있는가다 — 레거시의
 * '거래처코드 첫 글자 A' 규칙은 두 법인을 한 DB에 합친 뒤로 안 맞는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출현황 매입처 제외(10/27 피드백 §7-C)")
class ArPurchaserExclusionIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PX" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2092;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private String purchaserCode;
    private String sellerCode;
    private Long purchaserId;

    @BeforeAll
    void seed() {
        token();
        purchaserCode = "PXA" + SFX;
        sellerCode = "PXB" + SFX;
        Long purchaser = createId("/masters/clients",
                Map.of("code", purchaserCode, "name", "매입처(분리법인)", "type", "NORMAL"));
        purchaserId = purchaser;
        Long seller = createId("/masters/clients",
                Map.of("code", sellerCode, "name", "매출거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "PXW" + SFX, "name", "물류", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of("code", "PXP" + SFX, "name", "제외도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재"));

        // ★매입입고 — 이것이 '매입처' 판정의 유일한 근거다(정상입고는 매입처가 아니다).
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05",
                "supplierClientId", purchaser, "destinationWarehouseId", wh,
                "inboundType", "PURCHASE",
                "items", List.of(Map.of("productId", book, "unitCost", 5000, "qty", 1000))));

        // 매출거래처로 매출 1건 — 합계가 이 거래처 것만으로 설명돼야 한다.
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", seller, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 70, "qty", 10))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private JsonNode arStatus() {
        return data(get("/closing/ar-status" + RANGE));
    }

    private JsonNode rowOf(JsonNode arStatus, String code) {
        for (JsonNode row : arStatus.path("rows")) {
            if (code.equals(row.path("partnerCode").asText())) {
                return row;
            }
        }
        return null;
    }

    @Test
    @DisplayName("★매입처 행은 목록에 아예 없다 — 매출거래처는 그대로 보인다")
    void 매입처_행_제외() {
        JsonNode s = arStatus();

        assertThat(rowOf(s, purchaserCode)).as("매입처 행은 빠져야").isNull();
        assertThat(rowOf(s, sellerCode)).as("매출거래처는 남아야").isNotNull();
    }

    @Test
    @DisplayName("★매입처 소계는 null이다 — 보여줄 매입처 행이 없으면 소계도 없다")
    void 매입처_소계_없음() {
        // ‼️전역 JsonInclude.NON_NULL 이라 null 필드는 키 자체가 빠진다.
        assertThat(arStatus().hasNonNull("purchaseSubtotal")).isFalse();
    }

    @Test
    @DisplayName("★합계에 매입처 매입액·잔액이 섞이지 않는다")
    void 합계에_매입처_미포함() {
        JsonNode total = arStatus().path("total");

        // 이 기간의 매입입고는 매입처 것뿐이다(5,000,000원) — 합계에 들어가면 안 된다.
        assertThat(total.path("purchaseAmount").asLong()).as("합계 매입액: %s", total).isZero();
        assertThat(total.path("purchaseQty").asLong()).isZero();
    }

    @Test
    @DisplayName("외상매출장(24p)은 매입처를 계속 본다 — 같이 걸러 버리면 원장이 사라진다")
    void 외상매출장은_영향없음() {
        JsonNode led = data(get("/closing/ar-ledger/all" + RANGE + "&page=0&size=200"));
        boolean found = false;
        for (JsonNode p : led.path("content")) {
            // ‼️ArLedgerResponse 엔 거래처코드가 아직 없다(7단계에서 추가 예정) — id로 찾는다.
            if (p.path("partnerId").asLong() == purchaserId) {
                found = true;
                break;
            }
        }
        assertThat(found).as("외상매출장 전체에는 매입처도 있어야: %s", led).isTrue();
    }
}
