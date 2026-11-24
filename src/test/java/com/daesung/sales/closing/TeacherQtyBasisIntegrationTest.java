package com.daesung.sales.closing;

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
 * 외상매출현황 <b>교사용</b> = 무가 중 <b>공급률이 있는 것</b>만(프론트 지적 2026-11-24).
 *
 * <p>근거: 레거시 {@code 외상매출현황조회.vb:200} — {@code tradeType='무상' AND supRate<>0}.
 *
 * <p>‼️전엔 무가(FREE) <b>전부</b>를 세어 <b>증정(공급률 0)이 교사용으로</b> 잡혔다.
 * 운영 실측(2026-01~10): 무가 3,333건이 <b>전부 공급률 0(증정)</b> 인데 교사용 수량이
 * <b>58,217</b> 로 나왔다 — 기존 프로그램은 <b>0</b> 이고 그게 맞다.
 * 금액은 공급률 0이면 0이라 <b>수량만</b> 틀렸다(그래서 잔액은 맞아 오래 안 드러났다).
 *
 * <p>★외상매출<b>장</b>은 이미 이 기준이었다 — 같은 화면군이 서로 다른 교사용을 보여주고 있었다.
 * 이 테스트가 두 화면을 같은 기준에 묶는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출현황 교사용 기준 — 증정은 제외(2026-11-24)")
class TeacherQtyBasisIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "TQ" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2083;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long partner;
    private Long warehouse;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "TQS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "TQW" + SFX, "name", "교사용검증창고", "type", "MAIN"));
        partner = createId("/masters/clients",
                Map.of("code", "TQP" + SFX, "name", "교사용검증거래처", "type", "NORMAL"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "TQB" + SFX);
        b.put("name", "교사용검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "T" + YEAR + "A");
        b.put("catName", "교사용검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 500, "unitCost", 3000))))
                .path("success").asBoolean()).isTrue();

        // 증정 — 무가 + 공급률 0. **교사용이 아니다.**
        free(YEAR + "-02-10", 40, 0);
        // 교사용 — 무가 + 공급률 50. 이것만 교사용이다.
        free(YEAR + "-02-11", 9, 50);
    }

    private void free(String date, int qty, int rate) {
        assertThat(post("/sales/entries", Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "shipmentType", "GIFT",
                        "qty", qty, "unitPrice", 10000, "supplyRate", rate))))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★증정(공급률 0)은 교사용에서 빠진다 — 40부가 아니라 **9부**")
    void 증정은_교사용이_아니다() {
        JsonNode row = statusRow();
        assertThat(row).as("거래처 행이 있어야 한다").isNotNull();
        assertThat(row.path("teacherQty").asInt())
                .as("‼️49 면 증정 40부가 섞인 것이다(옛 동작)")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("★외상매출**장**과 같은 숫자다 — 두 화면이 갈리면 담당자가 둘 다 못 믿는다")
    void 장부와_같다() {
        int status = statusRow().path("teacherQty").asInt();
        int ledger = 0;
        for (JsonNode l : data(get("/closing/ar-ledger?partnerId=" + partner
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31")).path("lines")) {
            if ("DETAIL".equals(l.path("rowType").asText())) {
                ledger += l.path("teacherQty").asInt();
            }
        }
        assertThat(status).as("현황 %d / 장부 %d", status, ledger).isEqualTo(ledger);
    }

    private JsonNode statusRow() {
        for (JsonNode r : data(get("/closing/ar-status" + RANGE)).path("rows")) {
            if (("TQP" + SFX).equals(r.path("partnerCode").asText())) {
                return r;
            }
        }
        return null;
    }
}
