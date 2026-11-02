package com.daesung.sales.inventory;

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
 * 폐기 내역의 <b>취소 표시</b>와 <b>합계 일치</b>.
 *
 * <p>근거: 프론트 실측 지적(2026-10-01) — 취소한 전표의 원 행과 역분개 행이 같은 전표번호·같은 수량으로
 * 둘 다 내려와, 화면이 취소 여부를 알 수 없고 합계도 두 배가 됐다.
 *
 * <p>★<b>표시 문제가 아니라 집계 오류였다.</b> 상세는 수량을 양수로 뒤집어 보여주느라
 * {@code 원 행 200 + 역분개 행 200 = 400} 이 됐는데, 분류별 요약은 {@code SUM(-qty)} 라
 * 취소분이 0으로 상쇄되고 있었다 — <b>같은 화면의 두 숫자가 어긋나 있었다</b>.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("폐기 취소 표시 · 상세와 요약 일치")
class DisposalCancelFlagIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DC" + (System.nanoTime() % 1_000_000L);
    private static final String YEAR = "2099";
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long warehouse;
    // ‼️테스트마다 다른 도서를 쓴다. 하나를 공유하면 먼저 돈 테스트의 폐기까지 합계에 섞여
    //   "살아 있는 폐기는 50" 같은 검증이 다른 테스트 수량까지 세게 된다(실제로 그렇게 깨졌다).
    private Long bookA;   // 상세와_요약이_일치
    private Long bookB;   // 역분개행은_빠진다
    private Long bookC;   // 정상건은_false

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "DCS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "DCW" + SFX, "name", "취소표시창고", "type", "MAIN"));

        bookA = book(supplier, "DCA" + SFX);
        bookB = book(supplier, "DCB" + SFX);
        bookC = book(supplier, "DCC" + SFX);
    }

    private Long book(Long supplier, String code) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", code + " 취소표시 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "D2099A01");
        b.put("catName", "취소표시분류");
        b.put("salesDivision", "교재");
        Long id = createId("/masters/products", b);
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", id, "unitCost", 1000, "qty", 1000))));
        return id;
    }

    private String dispose(Long book, int qty) {
        JsonNode r = post("/disposals", Map.of(
                "processedDate", YEAR + "-02-01", "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("폐기 등록: %s", r).isTrue();
        return data(r).path("disposalNo").asText();
    }

    /** 그 도서의 폐기 행만. */
    private JsonNode rows(Long book) {
        return data(get("/disposals" + RANGE + "&size=200&productIds=" + book)).path("content");
    }

    private long summaryQty(Long book) {
        return data(get("/disposals/summary" + RANGE + "&productIds=" + book))
                .path("totalQty").asLong();
    }

    @Test
    @DisplayName("★★상세 합계와 요약이 같다 — 예전엔 상세만 400이었다(역분개 행이 함께 실려서)")
    void 상세와_요약이_일치() {
        dispose(bookA, 50);
        String canceledNo = dispose(bookA, 200);
        post("/disposals/" + canceledNo + "/cancel?reason=수량착오", Map.of());

        long detailSum = 0;
        int canceledRows = 0;
        for (JsonNode r : rows(bookA)) {
            detailSum += r.path("qty").asLong();
            if (r.path("canceled").asBoolean()) {
                canceledRows++;
            }
        }

        assertThat(canceledRows).as("취소된 전표도 목록에 남고 표시가 붙는다").isEqualTo(1);
        // ★취소분을 빼지 않는다. 취소는 **취소한 달**에 역분개로 잡히기 때문이다(마감 보호).
        //   그래서 이 기간 합계에는 그대로 들어가고, 요약도 같은 숫자를 낸다.
        assertThat(detailSum).as("50 + 200").isEqualTo(250);
        assertThat(summaryQty(bookA)).as("요약과 같아야 한다 — 두 숫자가 갈리면 둘 다 못 믿는다")
                .isEqualTo(detailSum);
    }

    @Test
    @DisplayName("★역분개 행은 목록에 싣지 않는다 — 같은 전표가 두 줄로 나오면 합계가 두 배다")
    void 역분개행은_빠진다() {
        String no = dispose(bookB, 70);
        post("/disposals/" + no + "/cancel?reason=오입력", Map.of());

        int same = 0;
        for (JsonNode r : rows(bookB)) {
            if (no.equals(r.path("disposalNo").asText())) {
                same++;
                assertThat(r.path("canceled").asBoolean()).as("그 한 줄에 취소 표시가 붙는다").isTrue();
                assertThat(r.path("qty").asInt()).as("수량은 원래대로 양수").isEqualTo(70);
            }
        }
        assertThat(same).as("한 전표는 한 줄이다").isEqualTo(1);
    }

    @Test
    @DisplayName("취소하지 않은 전표는 canceled=false — 화면이 버튼을 열어도 되는 근거")
    void 정상건은_false() {
        String no = dispose(bookC, 9);

        boolean found = false;
        for (JsonNode r : rows(bookC)) {
            if (no.equals(r.path("disposalNo").asText())) {
                found = true;
                assertThat(r.path("canceled").asBoolean()).isFalse();
            }
        }
        assertThat(found).isTrue();
    }
}
