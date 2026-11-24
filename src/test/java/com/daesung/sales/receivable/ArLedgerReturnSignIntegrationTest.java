package com.daesung.sales.receivable;

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
 * 외상매출장의 <b>반품 칸은 양수</b>로 나간다(발주처 2026-11-23 9번).
 *
 * <p>‼️그전엔 {@code −1,344 / −12,919,975} 처럼 음수로 나갔다. 칸 이름이 이미
 * 「반품 수량」·「반품 금액」이라, 거기 음수를 넣으면 읽는 사람이 <b>두 번 빼서</b> 이해한다.
 * 기존 프로그램도 양수로 찍는다(발주처가 보낸 레거시 엑셀로 확인).
 *
 * <p>★<b>잔액 계산은 손대지 않았다.</b> 보여 주는 칸만 부호를 바꿨고,
 * 채권은 그대로 빼는 쪽이다 — 이 테스트가 두 가지를 함께 못박는다.
 * 하나만 보면 "양수로 바꾸다가 잔액까지 늘어난" 사고를 못 잡는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출장 반품 양수 표기(2026-11-23 발주처 9번)")
class ArLedgerReturnSignIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "RS" + (System.nanoTime() % 1_000_000L);
    /** ★다른 테스트가 쓰지 않는 연도 — 외상매출장은 전역 집계라 겹치면 숫자가 흔들린다. */
    private static final String YEAR = "2067";
    private static final String DAY = YEAR + "-05-20";

    private Long partner;
    private Long book;
    private Long wh;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "RSS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "RSP" + SFX, "name", "반품부호검증처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "RSW" + SFX, "name", "창고", "type", "MAIN"));
        Map<String, Object> b = new HashMap<>();
        b.put("code", "RSB" + SFX);
        b.put("name", "반품부호 검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S" + YEAR + "A01");
        b.put("catName", "반품부호검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 500, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();

        // 매출 20부(14만원) → 반품 5부(3.5만원)
        assertThat(post("/sales/entries", Map.of(
                "salesDate", DAY, "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 20, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
        assertThat(post("/sales/return-inbound", Map.of(
                "returnDate", DAY, "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 5,
                        "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★반품 수량·금액이 **양수**로 나간다 — 기존 프로그램과 같게")
    void 반품은_양수로_나간다() {
        JsonNode led = data(get("/closing/ar-ledger?partnerId=" + partner
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
        JsonNode ret = null;
        for (JsonNode l : led.path("lines")) {
            if ("DETAIL".equals(l.path("rowType").asText())
                    && l.path("returnQty").asLong() != 0) {
                ret = l;
            }
        }
        assertThat(ret).as("반품 명세행이 있어야 한다").isNotNull();
        assertThat(ret.path("returnQty").asLong())
                .as("‼️음수면 화면이 「반품 −5」로 보여 두 번 빼서 읽힌다")
                .isEqualTo(5);
        assertThat(ret.path("returnAmount").asLong()).isEqualTo(35_000);
    }

    @Test
    @DisplayName("‼️그래도 **잔액은 빠진다** — 보여 주는 칸만 바꿨지 계산은 그대로다")
    void 잔액은_여전히_차감된다() {
        JsonNode led = data(get("/closing/ar-ledger?partnerId=" + partner
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
        // 매출 140,000 − 반품 35,000 = 105,000
        assertThat(led.path("closing").asLong())
                .as("‼️여기가 175,000 이면 반품을 더해 버린 것이다")
                .isEqualTo(105_000);
    }

    @Test
    @DisplayName("일계·월계·누계도 양수로 쌓인다 — 명세행과 같은 값을 더하므로")
    void 소계도_양수다() {
        JsonNode led = data(get("/closing/ar-ledger?partnerId=" + partner
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
        for (JsonNode l : led.path("lines")) {
            String t = l.path("rowType").asText();
            if ("DAY_SUBTOTAL".equals(t) || "MONTH_SUBTOTAL".equals(t)
                    || "RUNNING_TOTAL".equals(t)) {
                assertThat(l.path("returnQty").asLong())
                        .as("%s 의 반품 수량", t).isGreaterThanOrEqualTo(0);
                assertThat(l.path("returnAmount").asLong())
                        .as("%s 의 반품 금액", t).isGreaterThanOrEqualTo(0);
            }
        }
    }
}
