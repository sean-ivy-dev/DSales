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
 * 외상매출장 — <b>전체 거래처를 한 장부로</b>(발주처 2026-11-23 9번).
 *
 * <p>‼️기존 {@code /ar-ledger/all} 은 <b>거래처마다 한 벌</b>이라 화면이 117묶음을 늘어놓았다.
 * 기존 프로그램은 「거래처 전체」로 뽑으면 <b>이어진 한 장부</b>가 나온다.
 *
 * <p>★이 테스트가 못박는 것은 <b>합쳐도 숫자가 그대로</b>라는 점이다 —
 * 이월·명세행 수·마지막 잔액이 거래처별 장부의 합과 같아야 한다.
 * 합치면서 줄이 늘거나 잔액이 달라지면 그건 다른 장부다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출장 전체 거래처 한 장부(2026-11-23 발주처 9번)")
class ArLedgerMergedIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "MG" + (System.nanoTime() % 1_000_000L);
    /** ★다른 테스트가 쓰지 않는 연도 — 합친 장부는 전 거래처를 긁어 오므로 겹치면 흔들린다. */
    private static final String YEAR = "2068";

    private Long pa;
    private Long pb;
    private Long book;
    private Long wh;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "MGS" + SFX, "name", "인쇄", "type", "NORMAL"));
        pa = createId("/masters/clients",
                Map.of("code", "MGA" + SFX, "name", "합본검증 가", "type", "NORMAL"));
        pb = createId("/masters/clients",
                Map.of("code", "MGB" + SFX, "name", "합본검증 나", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "MGW" + SFX, "name", "창고", "type", "MAIN"));
        Map<String, Object> b = new HashMap<>();
        b.put("code", "MGK" + SFX);
        b.put("name", "합본 검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S" + YEAR + "A01");
        b.put("catName", "합본검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);
        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 900, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();

        // 두 거래처 · 서로 다른 날 — 합치면 날짜순으로 섞여야 한다
        sell(pa, YEAR + "-03-10", 10);
        sell(pb, YEAR + "-03-05", 7);
        sell(pa, YEAR + "-04-02", 4);
    }

    private void sell(Long partner, String day, int qty) {
        assertThat(post("/sales/entries", Map.of(
                "salesDate", day, "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).as("매출 등록").isTrue();
    }

    private String range() {
        return "fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";
    }

    @Test
    @DisplayName("★★한 장부로 나온다 — 머리말이 「전체 거래처」이고 거래처 칸은 비어 있다")
    void 한_장부로_나온다() {
        JsonNode m = data(get("/closing/ar-ledger/all/merged?" + range()));
        assertThat(m.path("partnerName").asText()).isEqualTo("전체 거래처");
        assertThat(m.path("partnerId").isNull() || m.path("partnerId").isMissingNode()).isTrue();
        assertThat(m.path("lines").size()).as("줄이 있어야 한다").isPositive();
        assertThat(m.path("lines").get(0).path("rowType").asText())
                .as("첫 줄은 이월 금액이다").isEqualTo("OPENING");
    }

    @Test
    @DisplayName("★★명세행이 **날짜순**으로 섞인다 — 거래처별로 뭉쳐 있으면 한 장부가 아니다")
    void 날짜순으로_섞인다() {
        JsonNode m = data(get("/closing/ar-ledger/all/merged?" + range()));
        String prev = null;
        for (JsonNode l : m.path("lines")) {
            if (!"DETAIL".equals(l.path("rowType").asText())) {
                continue;
            }
            String d = l.path("date").asText();
            if (prev != null) {
                assertThat(d).as("날짜가 거꾸로 가면 안 된다").isGreaterThanOrEqualTo(prev);
            }
            prev = d;
        }
    }

    @Test
    @DisplayName("‼️합쳐도 **숫자가 그대로** — 거래처별 장부의 합과 같다")
    void 합계가_같다() {
        JsonNode merged = data(get("/closing/ar-ledger/all/merged?" + range()));
        JsonNode all = data(get("/closing/ar-ledger/all?" + range() + "&size=200"));

        long openSum = 0;
        long detailCount = 0;
        for (JsonNode led : all.path("content")) {
            openSum += led.path("opening").asLong();
            for (JsonNode l : led.path("lines")) {
                if ("DETAIL".equals(l.path("rowType").asText())) {
                    detailCount++;
                }
            }
        }
        assertThat(merged.path("opening").asLong())
                .as("이월은 거래처별 이월의 합이다").isEqualTo(openSum);

        long mergedDetail = 0;
        for (JsonNode l : merged.path("lines")) {
            if ("DETAIL".equals(l.path("rowType").asText())) {
                mergedDetail++;
            }
        }
        assertThat(mergedDetail)
                .as("‼️명세행이 늘거나 줄면 합치면서 자료가 바뀐 것이다")
                .isEqualTo(detailCount);
    }

    @Test
    @DisplayName("누계가 이월에서 출발해 마지막이 기말잔액과 맞는다")
    void 누계가_이어진다() {
        JsonNode m = data(get("/closing/ar-ledger/all/merged?" + range()));
        long last = -1;
        for (JsonNode l : m.path("lines")) {
            if ("RUNNING_TOTAL".equals(l.path("rowType").asText())) {
                last = l.path("balance").asLong();
            }
        }
        assertThat(last).as("누 계 줄이 있어야 한다").isNotEqualTo(-1);
        assertThat(last).as("마지막 누계 = 기말잔액").isEqualTo(m.path("closing").asLong());
    }
}
