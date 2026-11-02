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
 * 교사용(증정 포함) 칸 — 프론트 번들 실측(2026-09-16)으로 드러난 두 화면.
 *
 * <p>배포된 화면 번들에 <b>서버가 안 주는 칸</b> 목록이 문자열로 박혀 있었다.
 * <pre>
 *   /sales/net-summary   absentColumns.teacherCnt "교사용·증정 수량은 아직 따로 집계되지 않습니다"
 *   /closing/ar-status   absentColumns.teacher    "교사용 금액은 아직 제공되지 않습니다"
 * </pre>
 *
 * <p>둘의 성격이 다르다.
 * <ul>
 *   <li><b>순매출조회는 값이 이미 있었다.</b> {@code freeQty}가 곧 교사용(증정 포함)인데
 *       — 회계구분 FREE는 증정·교사용 둘뿐이다 — 이름 때문에 화면이 다른 값으로 읽었다.
 *       같은 값을 {@code teacherQty}로도 낸다.</li>
 *   <li><b>외상매출현황은 진짜 없었다.</b> 집계에 FREE 버킷 자체가 없었다.</li>
 * </ul>
 *
 * <p>‼️<b>유가 교사용은 채권에 들어간다</b>(받을 돈이다). 무가는 금액이 0이라 영향이 없다.
 * 수량 칸은 유가·무가를 합치고 금액 칸은 유가분만 잡힌다 — 이 구분을 함께 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("교사용(증정포함) 칸 — 순매출·외상매출현황")
class TeacherColumnIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-TC" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2080;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long partner;
    private String bookCode;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "TCS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "TCP" + SFX, "name", "교사용거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "TCW" + SFX, "name", "교사용창고", "type", "MAIN"));

        bookCode = "TCB" + SFX;
        Map<String, Object> b = new HashMap<>();
        b.put("code", bookCode);
        b.put("name", "교사용도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "T" + YEAR + "A");
        b.put("catName", "교사용분류");
        b.put("salesDivision", "교재");
        Long book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 1000))));

        // 매출 100(유가) · 교사용 20(유가) · 증정 5(무가)
        // ★교사용을 유가·무가 둘 다 두는 이유: 수량 칸은 둘을 합치지만
        //   채권은 **유가분만** 잡힌다. 한쪽만 넣으면 그 구분이 테스트에서 사라진다.
        ship(wh, book, "NORMAL_SHIP", 100, 70);
        ship(wh, book, "TEACHER_USE", 20, 70);
        ship(wh, book, "GIFT", 5, 0);
    }

    private void ship(Long wh, Long book, String type, int qty, int rate) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", type,
                        "unitPrice", 10000, "supplyRate", rate, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("%s: %s", type, r).isTrue();
    }

    private JsonNode netRow() {
        for (JsonNode r : data(get("/sales/net-summary" + RANGE)).path("rows")) {
            if (bookCode.equals(r.path("productCode").asText())) {
                return r;
            }
        }
        throw new IllegalStateException("순매출 행 없음");
    }

    private JsonNode arRow() {
        for (JsonNode r : data(get("/closing/ar-status" + RANGE)).path("rows")) {
            if (("TCP" + SFX).equals(r.path("partnerCode").asText())) {
                return r;
            }
        }
        throw new IllegalStateException("외상매출 행 없음");
    }

    @Test
    @DisplayName("★순매출조회 — teacherQty = 교사용 20 + 증정 5 = 25")
    void 순매출_교사용() {
        JsonNode r = netRow();

        assertThat(r.path("teacherQty").asLong()).as("교사용 20 + 증정 5").isEqualTo(25);
        assertThat(r.path("teacherAmount").asLong())
                .as("금액은 유가분만 — 증정은 공급률 0이라 0원").isEqualTo(20 * 7000);

        // 같은 값을 두 이름으로 낸다 — 한쪽만 고치면 화면마다 수가 갈린다.
        assertThat(r.path("freeQty").asLong()).isEqualTo(r.path("teacherQty").asLong());
        assertThat(r.path("freeAmount").asLong()).isEqualTo(r.path("teacherAmount").asLong());

        // 교사용은 매출이 아니다.
        assertThat(r.path("saleQty").asLong()).as("매출에 섞이면 안 된다").isEqualTo(100);
    }

    @Test
    @DisplayName("★외상매출현황 — 교사용 금액 칸이 생기되 매출액과 섞이지 않는다")
    void 외상매출_교사용() {
        JsonNode r = arRow();

        assertThat(r.path("teacherAmount").asLong()).as("교사용 공급가액(유가분)").isEqualTo(20 * 7000);
        // 수량은 유가·무가를 합친다(교사용 20 + 증정 5) — 금액과 기준이 다르다.
        assertThat(r.path("teacherQty").asLong()).as("교사용 수량(증정 포함)").isEqualTo(25);

        // ‼️교사용이 '매출액'에 섞이면 매출 통계가 통째로 부푼다. 별도 칸이어야 한다.
        assertThat(r.path("saleAmount").asLong()).as("매출액은 정상출고만").isEqualTo(100 * 7000);

        // ★채권에는 **유가 교사용이 들어간다** — 받을 돈이기 때문이다(무가는 0원이라 영향 없음).
        //   "교사용이니까 채권에서 빼야 한다"고 고치지 말 것.
        assertThat(r.path("receivableGen").asLong())
                .as("채권발생 = 매출 + 유가교사용 + 세액 − 반품")
                .isEqualTo(100 * 7000 + 20 * 7000);
    }

    @Test
    @DisplayName("합계행에도 교사용이 누적된다 — 행은 맞는데 합계만 0이면 더 헷갈린다")
    void 합계행() {
        assertThat(data(get("/closing/ar-status" + RANGE)).path("total").path("teacherAmount").asLong())
                .isGreaterThanOrEqualTo(20 * 7000);
        assertThat(data(get("/sales/net-summary" + RANGE)).path("total").path("teacherQty").asLong())
                .isGreaterThanOrEqualTo(25);
    }
}
