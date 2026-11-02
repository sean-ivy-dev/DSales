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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 전표번호가 <b>없는</b> 폐기 행만 있는 페이지도 조회된다.
 *
 * <p>근거: 프론트 실측(2026-10-22) — {@code GET /disposals}가 조건과 무관하게 500이었다.
 * 원인은 {@code canceledRefNos()}가 빈 결과를 {@code Set.of()}로 돌려준 것.
 * 불변 Set은 <b>null-hostile</b>이라 {@code contains(null)}이 NPE를 던진다.
 * 이관한 폐기 4,374건이 전부 {@code ref_no} NULL이라 어느 페이지를 열어도 터졌다.
 *
 * <p>★전표번호 없는 행은 <b>이관분에만</b> 있다 — 화면 등록은 늘 채번한다.
 * 그래서 API만으로는 재현되지 않고, 여기서 직접 NULL로 만들어 이관 상태를 흉내 낸다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("폐기 내역 — 전표번호 없는 행(이관분)이 섞여도 조회된다")
class DisposalNullRefNoIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DN" + (System.nanoTime() % 1_000_000L);
    private static final String YEAR = "2097";
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    @Autowired
    private JdbcTemplate jdbc;

    private Long warehouse;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "DNS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "DNW" + SFX, "name", "전표없음창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "DNB" + SFX);
        b.put("name", "전표없음 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "D2097A01");
        b.put("catName", "폐기검증");
        b.put("taxFree", false);
        b.put("useYn", true);
        b.put("set", false);
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 500))))
                .path("success").asBoolean()).isTrue();

        assertThat(post("/disposals", Map.of(
                "processedDate", YEAR + "-02-10", "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 30, "reason", "파손"))))
                .path("success").asBoolean()).isTrue();

        // ★이관분 흉내 — 이 창고의 폐기 전표번호를 지운다.
        int cleared = jdbc.update(
                "UPDATE inventory_txn SET ref_no = NULL "
                        + "WHERE txn_type = 'DISPOSE' AND warehouse_id = ?", warehouse);
        assertThat(cleared).as("전표번호를 지운 폐기 행").isPositive();
    }

    @Test
    @DisplayName("전표번호가 전부 NULL 인 페이지도 500 이 아니다 — Set.of().contains(null) 회귀")
    void 전표번호_없어도_조회된다() {
        JsonNode d = data(get("/disposals" + RANGE + "&warehouseIds=" + warehouse + "&page=0&size=20"));
        assertThat(d.path("content")).hasSize(1);

        JsonNode row = d.path("content").get(0);
        assertThat(row.path("qty").asLong()).as("수량은 양수로 보인다").isEqualTo(30);
        // 전표번호가 없으면 취소될 수 없다 — null 을 contains 에 흘리지 않는다는 것이 이 검증의 핵심.
        assertThat(row.path("canceled").asBoolean()).isFalse();
        assertThat(row.hasNonNull("refNo")).as("이관분은 전표번호가 비어 있다").isFalse();
    }

    @Test
    @DisplayName("엑셀 다운로드(전량)도 같은 경로를 탄다 — 같은 NPE가 여기서도 터졌다")
    void 전량조회도_안터진다() {
        // ‼️xlsx 바이너리라 JSON 파서를 태우면 안 된다. 상태코드만 본다.
        assertThat(exchangeRaw(org.springframework.http.HttpMethod.GET,
                "/disposals/export" + RANGE + "&warehouseIds=" + warehouse, null, token(), null)
                .getStatusCode().is2xxSuccessful()).isTrue();
    }
}
