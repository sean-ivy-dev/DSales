package com.daesung.sales.sale;

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
 * 순매출조회(16p)·제품수불부 결산내역(11p)의 <b>정본 데이터 항목</b> 회귀 고정.
 *
 * <p>정본 16p 항목 원문: {@code 상품분류/도서명, 매출(수량/공급가액), 교사용(증정용포함),
 * 반품(수량/반품률/공급가액), 순매출(수량/공급가액/세액/총금액), [외부콘텐츠] 입고/매입액, 이익금액/이익률}.
 * 정본 11p 항목 원문: {@code … /현재재고, 결산내역(연초~기준일 누적)}.
 *
 * <p>★여기서 지키는 것은 "칸이 있다"가 아니라 <b>값이 맞다</b>이다 —
 * 교사용을 순매출에서 빼지 않는지, 순매출 세액이 반품세액을 뺀 순액인지,
 * 결산내역의 시작일이 연초로 못 박혀 있는지.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("순매출조회(16p)·수불부 결산내역(11p) 데이터 항목")
class NetSalesColumnsIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-NS" + (System.nanoTime() % 1_000_000L);
    /** 분류코드는 형식 검증이 있다([영문1자][연도4자][영문·숫자1~3자]) — 접미어도 그 틀 안에서 만든다. */
    private static final String CAT = String.valueOf(System.nanoTime() % 1000L);
    /** 다른 테스트의 전역 연간 집계(대시보드)와 겹치지 않는 해. */
    private static final int YEAR = 2053;

    private Long partner;
    private Long mainWh;
    private Long selfBook;
    private Long extBook;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "NSS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "NSP" + SFX, "name", "거래처", "type", "NORMAL"));
        mainWh = createId("/masters/warehouses", Map.of("code", "NSW" + SFX, "name", "물류창고", "type", "MAIN"));

        selfBook = createId("/masters/products", Map.of("code", "NSA" + SFX, "name", "자체교재",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "catCode", "A2053" + CAT, "catName", "국어",
                "salesDivision", "교재"));
        extBook = createId("/masters/products", Map.of("code", "NSB" + SFX, "name", "외부콘텐츠",
                "contentType", "EXTERNAL", "price", 20000, "supplyRate", 80,
                "catCode", "B2053" + CAT, "catName", "수학",
                "salesDivision", "교재"));

        // 정상입고 200(원가 무관) + 매입입고 50@6000(외부콘텐츠 원가)
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-10", "supplierClientId", sup,
                "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", selfBook, "unitCost", 3000, "qty", 200))));
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-10", "supplierClientId", sup,
                "destinationWarehouseId", mainWh, "inboundType", "PURCHASE",
                "items", List.of(Map.of("productId", extBook, "unitCost", 6000, "qty", 50))));

        // 자체교재: 매출 100(정가10000·70% → 700,000) + 교사용 10 + 반품 20
        post("/sales/entries", Map.of("salesDate", YEAR + "-02-01", "partnerId", partner, "warehouseId", mainWh,
                "items", List.of(Map.of("productId", selfBook, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 70, "qty", 100))));
        post("/sales/entries", Map.of("salesDate", YEAR + "-02-02", "partnerId", partner, "warehouseId", mainWh,
                "items", List.of(Map.of("productId", selfBook, "shipmentType", "TEACHER_USE",
                        "unitPrice", 10000, "supplyRate", 50, "qty", 10))));
        post("/sales/entries", Map.of("salesDate", YEAR + "-02-03", "partnerId", partner, "warehouseId", mainWh,
                "items", List.of(Map.of("productId", selfBook, "shipmentType", "RETURN",
                        "unitPrice", 10000, "supplyRate", 70, "qty", 20))));

        // 외부콘텐츠: 매출 30(정가20000·80% → 480,000)
        post("/sales/entries", Map.of("salesDate", YEAR + "-02-05", "partnerId", partner, "warehouseId", mainWh,
                "items", List.of(Map.of("productId", extBook, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 20000, "supplyRate", 80, "qty", 30))));
    }

    private JsonNode netRow(long productId) {
        JsonNode d = data(get("/sales/net-summary?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
        for (JsonNode r : d.path("rows")) {
            if (r.path("productId").asLong() == productId) {
                return r;
            }
        }
        throw new AssertionError("행 없음: " + productId);
    }

    @Test
    @DisplayName("상품분류가 실린다 — 도서명만으로는 정본 '상품분류/도서명'을 못 채운다")
    void 상품분류() {
        JsonNode r = netRow(selfBook);
        assertThat(r.path("catCode").asText()).isEqualTo("A2053" + CAT);
        assertThat(r.path("catName").asText()).isEqualTo("국어");
    }

    @Test
    @DisplayName("★교사용은 수량까지 보이되 순매출에서는 빼지 않는다")
    void 교사용_수량과_순매출() {
        JsonNode r = netRow(selfBook);

        assertThat(r.path("freeQty").asInt()).as("교사용(증정용포함) 수량").isEqualTo(10);
        assertThat(r.path("freeAmount").asLong()).as("10 × 10000 × 50%").isEqualTo(50_000);

        // ‼️순매출 = 매출 − 반품. 교사용은 회계구분 FREE라 애초에 매출에 없다 —
        //   한 번 더 빼면 순매출이 무상 나간 만큼 아래로 밀린다.
        assertThat(r.path("saleAmount").asLong()).isEqualTo(700_000);
        assertThat(r.path("returnAmount").asLong()).isEqualTo(140_000);
        assertThat(r.path("netAmount").asLong()).as("700,000 − 140,000 (교사용 50,000은 관여 안 함)")
                .isEqualTo(560_000);
        assertThat(r.path("netQty").asInt()).as("100 − 20").isEqualTo(80);
    }

    @Test
    @DisplayName("순매출 세액·총금액 — 세액은 매출−반품 순액이다")
    void 순매출_세액_총금액() {
        JsonNode r = netRow(selfBook);
        // 세액은 자동산출하지 않는다(발주처 확정) → 미입력이라 0. 그래도 칸과 식은 있어야 한다.
        assertThat(r.path("netTax").asLong()).isZero();
        assertThat(r.path("netTotal").asLong()).as("순매출액 + 순매출세액")
                .isEqualTo(r.path("netAmount").asLong() + r.path("netTax").asLong());
    }

    @Test
    @DisplayName("외부콘텐츠 매입 입고수량 — 매입입고로 등록한 것만 잡힌다")
    void 매입_입고수량() {
        JsonNode ext = netRow(extBook);
        assertThat(ext.path("inboundQty").asInt()).as("매입입고 50건").isEqualTo(50);
        assertThat(ext.path("purchaseUnitCost").asLong()).isEqualTo(6000);
        assertThat(ext.path("purchaseAmount").asLong()).as("6000 × 순매출수량 30").isEqualTo(180_000);
        assertThat(ext.path("profit").asLong()).as("480,000 − 180,000").isEqualTo(300_000);

        // 자체교재는 매입 축이 없다 — 정상입고 200은 매입이 아니라서 잡히면 안 된다.
        // (null은 응답에서 아예 빠지므로 "숫자가 아니다"로 본다 — 0이면 "매입이 있었는데 0"으로 읽힌다)
        assertThat(netRow(selfBook).path("inboundQty").isNumber()).isFalse();
    }

    @Test
    @DisplayName("결산내역(11p) — 기준일만 주면 그 해 1월 1일부터 누적된다")
    void 결산내역_연초누적() {
        JsonNode rows = data(get("/stock/ledger/settlement?baseDate=" + YEAR + "-12-31"
                + "&productId=" + selfBook));

        JsonNode detail = null;
        JsonNode subtotal = null;
        JsonNode total = null;
        for (JsonNode r : rows) {
            switch (r.path("rowType").asText()) {
                case "DETAIL" -> detail = r;
                case "CAT_SUBTOTAL" -> subtotal = r;
                case "TOTAL" -> total = r;
                default -> throw new AssertionError("알 수 없는 행 구분: " + r);
            }
        }
        assertThat(detail).as("도서 상세행").isNotNull();
        assertThat(subtotal).as("분류 합계행").isNotNull();
        assertThat(total).as("총계행").isNotNull();

        // 연초 이전엔 아무것도 없었다 → 이월 0, 입고 200, 매출 −100, 교사용 −10, 반품 +20
        assertThat(detail.path("opening").asLong()).as("연초 이월").isZero();
        assertThat(detail.path("inbound").asLong()).isEqualTo(200);
        assertThat(detail.path("sale").asLong()).isEqualTo(-100);
        assertThat(detail.path("teacher").asLong()).isEqualTo(-10);
        assertThat(detail.path("salesReturn").asLong()).isEqualTo(20);
        assertThat(detail.path("closing").asLong()).as("200 −100 −10 +20").isEqualTo(110);

        // 분류에 이 도서 하나뿐이라 소계 = 상세
        assertThat(subtotal.path("closing").asLong()).isEqualTo(110);
        assertThat(subtotal.path("catName").asText()).isEqualTo("합 계");
        assertThat(total.path("catName").asText()).isEqualTo("총 계");
    }

    @Test
    @DisplayName("★결산내역은 시작일을 받지 않는다 — 이듬해 기준일이면 전년치가 '이월'로 접힌다")
    void 결산내역_이월로_접힌다() {
        JsonNode rows = data(get("/stock/ledger/settlement?baseDate=" + (YEAR + 1) + "-06-30"
                + "&productId=" + selfBook));

        JsonNode detail = null;
        for (JsonNode r : rows) {
            if ("DETAIL".equals(r.path("rowType").asText())) {
                detail = r;
            }
        }
        assertThat(detail).isNotNull();
        // 다음 해엔 움직임이 없다 → 전년 마감 110이 통째로 이월로 넘어온다
        assertThat(detail.path("opening").asLong()).as("전기이월").isEqualTo(110);
        assertThat(detail.path("inbound").asLong()).isZero();
        assertThat(detail.path("closing").asLong()).isEqualTo(110);
    }

    @Test
    @DisplayName("엑셀도 같은 항목으로 떨어진다")
    void 엑셀() {
        assertThat(getBytes("/sales/net-summary/export?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31")
                .getBody()).isNotEmpty();
        assertThat(getBytes("/stock/ledger/settlement/export?baseDate=" + YEAR + "-12-31")
                .getBody()).isNotEmpty();
    }
}
