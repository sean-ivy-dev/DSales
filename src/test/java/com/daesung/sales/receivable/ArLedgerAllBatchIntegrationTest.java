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
 * 외상매출장 <b>전체 거래처</b> 조회를 배치로 바꾼 것을 고정한다 — 10/30 프론트 D-3.
 *
 * <p>지적: {@code GET /closing/ar-ledger/all} 이 기간과 무관하게 8~9초. 거래처 수에 비례해
 * 늘고 1곳당 약 80ms. 운영 재측정 결과 그대로였다 — 페이지 20건 1.9초 / 100건 <b>8.5초</b> /
 * 200건 <b>19.9초</b>.
 *
 * <p>원인은 거래처마다 단건 조회를 통째로 다시 돌린 것이었다. 거래처 1곳당 6쿼리
 * (거래처 조회 1 + 이월잔액 3 + 매출라인 1 + 수금라인 1)에 <b>도서 LAZY N+1</b> 까지 얹혔다.
 * 이제 거래처 수와 무관하게 6쿼리로 한 번에 읽고 메모리에서 가른다.
 *
 * <p>★<b>이 테스트가 지키는 것은 "빠르다"가 아니라 "같다"</b>이다. 조회를 배치로 바꾸면서
 * 계산이 달라지면 채권 숫자가 조용히 틀어진다. 그래서 <b>전체 조회의 각 거래처 결과가
 * 단건 조회와 완전히 일치하는지</b>를 고정한다. 속도는 운영 실측으로 확인한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출장 전체 조회 배치화(10/30 D-3)")
class ArLedgerAllBatchIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-AB" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2091;
    private static final String FROM = YEAR + "-01-01";
    private static final String TO = YEAR + "-12-31";

    private final List<Long> partners = new java.util.ArrayList<>();

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "ABS" + SFX, "name", "인쇄", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "ABW" + SFX, "name", "물류", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of("code", "ABB" + SFX, "name", "배치도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "catCode", "A" + YEAR + "A", "catName", "배치분류"));
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 9000))));

        // 거래처 3곳 × 서로 다른 거래 모양(매출만 / 매출+반품 / 매출+수금)
        for (int i = 1; i <= 3; i++) {
            Long p = createId("/masters/clients",
                    Map.of("code", "ABP" + i + SFX, "name", "배치거래처" + i, "type", "NORMAL"));
            partners.add(p);
            post("/sales/entries", Map.of("salesDate", YEAR + "-03-1" + i,
                    "partnerId", p, "warehouseId", wh,
                    "items", List.of(Map.of("productId", book, "qty", 10 * i,
                            "price", 10000, "supplyRate", 70, "shipmentType", "NORMAL_SHIP"))));
        }
        // 2번 거래처: 반품 1건 더
        post("/sales/return-inbound", Map.of("returnDate", YEAR + "-04-02",
                "partnerId", partners.get(1), "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 3, "supplyRate", 70))));
        // 3번 거래처: 수금 1건 더
        post("/closing/collections/batch", Map.of("collDate", YEAR + "-05-02",
                "items", List.of(Map.of("partnerId", partners.get(2),
                        "collType", "CASH", "collAmt", 50_000))));
    }

    @Test
    @DisplayName("★전체 조회의 각 거래처 결과가 단건 조회와 완전히 같다")
    void 배치와_단건이_같다() {
        JsonNode all = data(get("/closing/ar-ledger/all?fromDate=" + FROM + "&toDate=" + TO + "&size=200"));

        for (Long p : partners) {
            JsonNode one = data(get("/closing/ar-ledger?partnerId=" + p
                    + "&fromDate=" + FROM + "&toDate=" + TO));
            JsonNode inAll = pick(all, p);

            assertThat(inAll).as("거래처 %s 가 전체 조회에 있다", p).isNotNull();
            // 헤더(이월·마감잔액·거래처코드)와 명세행 전체를 통째로 비교한다.
            // 한 칸이라도 다르면 배치화가 계산을 바꾼 것이다.
            assertThat(inAll).as("거래처 %s — 전체 조회가 단건과 같아야 한다", p).isEqualTo(one);
        }
    }

    @Test
    @DisplayName("거래가 서로 다른 거래처가 섞여도 라인이 엇갈리지 않는다")
    void 거래처별로_안섞인다() {
        JsonNode all = data(get("/closing/ar-ledger/all?fromDate=" + FROM + "&toDate=" + TO + "&size=200"));

        // 한 번에 읽어 메모리에서 가르는 구조라, 묶는 키가 틀리면 남의 거래가 섞여 들어온다.
        // 수량은 거래처마다 10 / 20 / 30 으로 다르게 깔았다.
        long[] expected = {10, 20, 30};
        for (int i = 0; i < 3; i++) {
            JsonNode led = pick(all, partners.get(i));
            long qty = 0;
            for (JsonNode l : led.path("lines")) {
                if ("DETAIL".equals(l.path("rowType").asText()) && !l.path("saleQty").isNull()) {
                    qty += l.path("saleQty").asLong();
                }
            }
            assertThat(qty).as("거래처%d 의 매출수량", i + 1).isEqualTo(expected[i]);
        }
    }

    /** ‼️{@code /ar-ledger/all} 은 **페이지 응답**이다 — content 안을 본다. */
    private JsonNode pick(JsonNode all, Long partnerId) {
        for (JsonNode n : all.path("content")) {
            if (n.path("partnerId").asLong() == partnerId) {
                return n;
            }
        }
        return null;
    }
}
