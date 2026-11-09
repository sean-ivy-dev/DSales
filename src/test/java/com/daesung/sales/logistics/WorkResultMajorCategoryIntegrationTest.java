package com.daesung.sales.logistics;

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
 * 작업결과의 분류를 <b>대분류</b>로(F-31).
 *
 * <p>발주처 요청: "분류 필터가 「전체/교재/IC」뿐(대분류 일부 + 안 쓰는 IC), 실제 분류 컬럼
 * 값은 세부구분. <b>필터·컬럼 모두 대분류로</b>. 가능하면 세부구분 컬럼 별도 추가."
 *
 * <p>★<b>발송의 {@code tradeClass} 문자열로는 가릴 수 없다.</b> 그 칸에 축이 섞여 있다 —
 * 2026년 운영 실측 2,229건에서 기타고사 1,120(대분류) · <b>대성교재 833(법인명)</b> ·
 * 교재 268(대분류) · D모의고사 7(세부구분) · 기타 1 이었다. 문자열 매핑만 하면 37%가 미지정이다.
 * 그래서 <b>상품 → 세부구분 → 대분류</b>로 파생한다.
 *
 * <p>여기서는 <b>세부구분명과 대분류명이 다른</b> 상품을 써서 그 파생이 실제로 도는지 본다
 * — 세부구분 {@code D모의고사} 의 대분류는 {@code 기타고사}(ETC_EXAM)다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("작업결과 분류 = 대분류(F-31)")
class WorkResultMajorCategoryIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-MJ" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2096-04-11";
    private static final String RANGE = "?fromDate=" + DATE + "&toDate=" + DATE
            + "&page=0&size=50";

    private Long partner;
    private Long warehouse;
    /** 세부구분 'D모의고사' → 대분류 '기타고사'. 이름이 달라야 파생을 검증할 수 있다. */
    private Long bookEtcExam;
    /** 세부구분 '교재' → 대분류 '교재'. */
    private Long bookTextbook;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "MJS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "MJP" + SFX, "name", "대분류거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "MJW" + SFX, "name", "본사물류창고", "type", "MAIN"));

        bookEtcExam = book("MJA" + SFX, "D모의고사");
        bookTextbook = book("MJB" + SFX, "교재");

        for (Long id : List.of(bookEtcExam, bookTextbook)) {
            assertThat(post("/stock/inbound", Map.of(
                    "processedDate", "2096-01-05", "supplierClientId", supplier,
                    "destinationWarehouseId", warehouse,
                    "items", List.of(Map.of("productId", id, "qty", 500, "unitCost", 1000))))
                    .path("success").asBoolean()).isTrue();
        }

        // ‼️여기서 **둘 다 판다.** 테스트 메서드 순서는 보장되지 않아, 판매를 각 테스트에 두면
        //   먼저 도는 테스트가 빈 목록을 보고 실패한다(실제로 그렇게 깨졌다).
        sell(bookEtcExam, 10);
        sell(bookTextbook, 5);
    }

    private Long book(String code, String salesDivision) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", code);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2096A01");
        b.put("catName", "대분류검증");
        b.put("salesDivision", salesDivision);
        b.put("taxFree", true);
        return createId("/masters/products", b);
    }

    private void sell(Long productId, int qty) {
        JsonNode res = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", productId, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(res.path("success").asBoolean()).as("매출등록: %s", res).isTrue();
    }

    /** 내 거래처의 작업결과 행만. */
    private List<JsonNode> mine(String extra) {
        JsonNode res = get("/logistics/work-results" + RANGE + extra);
        assertThat(res.path("success").asBoolean()).as("조회: %s", res).isTrue();
        List<JsonNode> out = new java.util.ArrayList<>();
        res.path("data").path("content").forEach(n -> {
            if (("MJP" + SFX).equals(n.path("partnerCode").asText())) {
                out.add(n);
            }
        });
        return out;
    }

    @Test
    @DisplayName("세부구분과 대분류가 **다른 칸**으로 함께 나온다 — D모의고사 → 기타고사")
    void 대분류파생() {
        List<JsonNode> rows = mine("");
        assertThat(rows).as("작업결과 행").isNotEmpty();
        JsonNode r = rows.get(0);

        // ‼️여기가 F-31의 핵심 — 두 칸이 서로 다른 값이어야 한다.
        assertThat(r.path("tradeClass").asText()).as("세부구분 칸").isEqualTo("D모의고사");
        assertThat(r.path("majorCategory").asText()).as("대분류 코드").isEqualTo("ETC_EXAM");
        assertThat(r.path("majorCategoryName").asText()).as("대분류 한글").isEqualTo("기타고사");
    }

    @Test
    @DisplayName("대분류 다중선택 필터 — 한글로 보내도 걸린다")
    void 대분류필터() {
        // 기타고사로 거르면 D모의고사 상품이 붙은 발송이 남는다
        List<JsonNode> etc = mine("&majorCategories=기타고사");
        assertThat(etc).as("기타고사 필터").isNotEmpty();
        for (JsonNode n : etc) {
            assertThat(n.path("majorCategoryName").asText()).contains("기타고사");
        }

        // 특강으로 거르면 내 데이터는 없다
        assertThat(mine("&majorCategories=특강")).as("특강 필터는 비어야 한다").isEmpty();

        // 코드명으로도 받는다
        assertThat(mine("&majorCategories=ETC_EXAM")).as("코드명 필터").isNotEmpty();
    }

    @Test
    @DisplayName("기존 세부구분 필터(tradeClass)는 그대로 — 화면 전환 기간에 둘 다 쓴다")
    void 기존필터유지() {
        assertThat(mine("&tradeClass=D모의고사")).as("세부구분 필터").isNotEmpty();
        assertThat(mine("&tradeClass=없는구분")).as("없는 값이면 빈 목록").isEmpty();
    }
}
