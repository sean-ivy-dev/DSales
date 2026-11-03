package com.daesung.sales.receivable;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 외상매출현황 담보만기 경고등급(assureExpiryLevel). 근거: 10/27 검수 피드백 §7-C
 * "담보비율과 담보만기 칸에 각각 색을 칠해 구분한다(초록·빨강)" — 담보비율엔 이미
 * {@code assureLevel}(OVER/WARN/WATCH/NORMAL)이 있었지만, 담보만기(assureExpiry)는
 * 날짜값만 내려가고 프론트가 색을 칠할 등급 판정이 없었다(재조사로 발견한 전수대조 갭).
 *
 * <p>기준일은 조회기간(toDate)이 아니라 <b>오늘</b> — 기존 담보만기 알림
 * ({@code GET /masters/clients/collateral-expiry}, 기본 30일)과 같은 기준.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출현황 담보만기 경고등급(10/27 피드백 §7-C)")
class ArStatusCollateralExpiryLevelIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CE" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2093;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long wh;
    private Long book;

    @BeforeAll
    void auth() {
        token();
        wh = createId("/masters/warehouses", Map.of("code", "CEW" + SFX, "name", "담보만기창고", "type", "MAIN"));
        book = createId("/masters/products", Map.of("code", "CEP" + SFX, "name", "담보만기도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70, "salesDivision", "교재"));
    }

    // ★arStatus()는 "조회기간(또는 그 전)에 거래가 있는 거래처만" 행을 낸다(ReceivableService.arStatus
    // ids 구성 — 매출/수금/매입/기초 중 하나라도 있어야). 담보만 설정하고 거래가 없으면 행 자체가 없다.
    private void seedSale(Long partnerId) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partnerId, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 70, "qty", 1))));
        if (!r.path("success").asBoolean()) {
            throw new AssertionError("매출등록 실패: " + r);
        }
    }

    private JsonNode rowOf(String code) {
        JsonNode s = data(get("/closing/ar-status" + RANGE));
        for (JsonNode row : s.path("rows")) {
            if (code.equals(row.path("partnerCode").asText())) {
                return row;
            }
        }
        throw new AssertionError("row not found: " + code);
    }

    @Test
    @DisplayName("담보만기가 이미 지났으면 EXPIRED")
    void 만료() {
        String code = "CEA" + SFX;
        Long id = createId("/masters/clients", Map.of("code", code, "name", "담보만료거래처", "type", "NORMAL"));
        put("/masters/clients/" + id, Map.of("name", "담보만료거래처", "type", "NORMAL",
                "assureAmount", 100_000, "assureExpiry", LocalDate.now().minusDays(5).toString()));
        seedSale(id);

        JsonNode row = rowOf(code);
        assertThat(row.path("assureExpiryLevel").asText()).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("담보만기가 30일 이내로 다가왔으면 IMMINENT")
    void 임박() {
        String code = "CEB" + SFX;
        Long id = createId("/masters/clients", Map.of("code", code, "name", "담보임박거래처", "type", "NORMAL"));
        put("/masters/clients/" + id, Map.of("name", "담보임박거래처", "type", "NORMAL",
                "assureAmount", 100_000, "assureExpiry", LocalDate.now().plusDays(10).toString()));
        seedSale(id);

        JsonNode row = rowOf(code);
        assertThat(row.path("assureExpiryLevel").asText()).isEqualTo("IMMINENT");
    }

    @Test
    @DisplayName("담보만기가 충분히 여유 있으면 등급 없음(null)")
    void 여유() {
        String code = "CEC" + SFX;
        Long id = createId("/masters/clients", Map.of("code", code, "name", "담보여유거래처", "type", "NORMAL"));
        put("/masters/clients/" + id, Map.of("name", "담보여유거래처", "type", "NORMAL",
                "assureAmount", 100_000, "assureExpiry", LocalDate.now().plusYears(1).toString()));
        seedSale(id);

        JsonNode row = rowOf(code);
        assertThat(row.hasNonNull("assureExpiryLevel")).as("%s", row).isFalse();
    }

    @Test
    @DisplayName("담보 자체가 없으면(만기도 없음) 등급도 없음")
    void 담보없음() {
        String code = "CED" + SFX;
        Long id = createId("/masters/clients", Map.of("code", code, "name", "담보없음거래처", "type", "NORMAL"));
        seedSale(id);

        JsonNode row = rowOf(code);
        assertThat(row.hasNonNull("assureExpiryLevel")).as("%s", row).isFalse();
    }

    @Test
    @DisplayName("담보비율 등급(assureLevel)과는 독립된 축이다 — 비율 경고가 없어도 만기 경고는 뜬다")
    void 비율과_독립() {
        String code = "CEE" + SFX;
        Long id = createId("/masters/clients", Map.of("code", code, "name", "비율여유만기임박", "type", "NORMAL"));
        // 담보금액을 매출 발생 가능성 없는 아주 크게 둬서 assureLevel은 NORMAL(또는 null)이 되게 하고,
        // 만기만 임박시킨다.
        put("/masters/clients/" + id, Map.of("name", "비율여유만기임박", "type", "NORMAL",
                "assureAmount", 1_000_000_000, "assureExpiry", LocalDate.now().plusDays(1).toString()));
        seedSale(id);

        JsonNode row = rowOf(code);
        assertThat(row.path("assureExpiryLevel").asText()).isEqualTo("IMMINENT");
        assertThat(row.path("assureLevel").asText()).isEqualTo("NORMAL");
    }
}
