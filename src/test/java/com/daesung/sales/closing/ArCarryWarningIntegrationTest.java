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
 * 외상매출현황 <b>이월 스냅샷 경고</b> — 거래처를 지정했을 때 잘못 뜨던 것
 * (프론트 전달 2026-11-13 §5).
 *
 * <p>경고는 {@code carry.isEmpty()} 로 판단했는데, {@code carry} 는
 * {@code sumByYear(연도, partnerId)} 라 <b>거래처를 좁히면 그 거래처 이월만</b> 담긴다.
 * 전년말 잔액이 0인 거래처는 <b>원래 이월 행이 없다</b>(운영 A14C122·A14C125 가 그렇다).
 * 그걸 "스냅샷이 없다"로 읽어 경고를 띄우면 <b>담당자가 멀쩡한 이월을 다시 만든다</b>.
 *
 * <p>★이 테스트가 지키는 것: <b>그 해 스냅샷이 있으면, 이월 없는 거래처를 지정해도 경고하지 않는다.</b>
 * 그리고 <b>정말 없을 때는 여전히 경고한다</b>(경고를 없애는 게 아니라 오판만 막는다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출현황 이월 경고 — 거래처 지정 시 오판 금지(프론트 2026-11-13 §5)")
class ArCarryWarningIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "CW" + (System.nanoTime() % 1_000_000L);

    /** 스냅샷이 **있는** 해. 이월 생성 API 로 만든다. */
    private static final int YEAR_WITH = 2094;
    /** 스냅샷이 **없는** 해 — 아무도 이월을 만들지 않은 연도. */
    private static final int YEAR_WITHOUT = 2095;

    private Long carried;    // 전년 잔액이 남아 이월이 생기는 거래처
    private Long noCarry;    // 전년 거래가 없어 이월 행이 없는 거래처
    private Long warehouse;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "CWS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "CWW" + SFX, "name", "창고", "type", "MAIN"));
        carried = createId("/masters/clients",
                Map.of("code", "CWA" + SFX, "name", "이월있는거래처", "type", "NORMAL"));
        noCarry = createId("/masters/clients",
                Map.of("code", "CWB" + SFX, "name", "이월없는거래처", "type", "NORMAL"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "CWB1" + SFX);
        b.put("name", "이월경고 검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2094A01");
        b.put("catName", "이월경고검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", (YEAR_WITH - 1) + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 100, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();

        // 전년도에 `carried` 만 매출 → 그 거래처만 이월이 생긴다.
        assertThat(post("/sales/entries", Map.of(
                "salesDate", (YEAR_WITH - 1) + "-06-01", "partnerId", carried,
                "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 3, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();

        // YEAR_WITH 이월 생성 — 스냅샷이 **있는** 상태를 만든다.
        JsonNode gen = post("/closing/carryforward?fiscalYear=" + YEAR_WITH, Map.of());
        assertThat(gen.path("success").asBoolean()).as("이월 생성: %s", gen).isTrue();

        // 올해(YEAR_WITH) 두 거래처 모두 거래를 넣어 조회에 행이 서게 한다.
        sell(carried);
        sell(noCarry);
    }

    private void sell(Long partner) {
        assertThat(post("/sales/entries", Map.of(
                "salesDate", YEAR_WITH + "-03-03", "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 1, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★이월 없는 거래처를 지정해도 — 그 해 스냅샷이 있으면 경고하지 않는다")
    void 거래처지정시_오판하지_않는다() {
        JsonNode res = ar(YEAR_WITH, noCarry);
        assertThat(warningText(res))
                .as("‼️전년 잔액 0 인 거래처는 원래 이월 행이 없다 — 경고하면 멀쩡한 이월을 다시 만든다: %s", res)
                .doesNotContain("이월 스냅샷이 없습니다");
    }

    @Test
    @DisplayName("이월 있는 거래처를 지정해도 경고 없음")
    void 이월있는_거래처도_경고없음() {
        assertThat(warningText(ar(YEAR_WITH, carried))).doesNotContain("이월 스냅샷이 없습니다");
    }

    @Test
    @DisplayName("‼️정말 스냅샷이 없는 해는 **여전히 경고한다** — 경고를 없애는 게 아니다")
    void 스냅샷_없는_해는_경고_유지() {
        assertThat(warningText(ar(YEAR_WITHOUT, noCarry)))
                .as("오판만 막는 것이지 경고 자체를 없애면 안 된다")
                .contains("이월 스냅샷이 없습니다");
    }

    @Test
    @DisplayName("거래처 미지정(전체 조회)도 종전대로 — 스냅샷 있으면 경고 없음")
    void 전체조회는_종전대로() {
        assertThat(warningText(ar(YEAR_WITH, null))).doesNotContain("이월 스냅샷이 없습니다");
    }

    private JsonNode ar(int year, Long partnerId) {
        String q = "/closing/ar-status?fromDate=" + year + "-01-01&toDate=" + year + "-12-31"
                + ((partnerId == null) ? "" : "&partnerId=" + partnerId);
        JsonNode res = get(q);
        assertThat(res.path("success").asBoolean()).as("조회: %s", res).isTrue();
        return res;
    }

    private static String warningText(JsonNode res) {
        return res.path("data").path("warnings").toString();
    }
}
