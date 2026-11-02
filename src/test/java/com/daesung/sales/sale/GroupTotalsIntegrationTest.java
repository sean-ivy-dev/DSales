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
 * 묶음 합계 — 통합 매출 조회의 <b>소계 행</b>(프론트 B-3).
 *
 * <p>요청: "정렬 우선순위 소계용 묶음 합계 조회를 추가해 주세요.
 * {@code GET /sales/group-totals?groupBy=PARTNER|DATE}, <b>조건은 /sales 와 같고</b>,
 * 응답은 묶음 키·묶음 이름·수량·공급가액·세액·합계금액.
 * 지금은 전량을 받아 계산하는데 1~10월(27,187건)에 <b>약 4분</b>이 걸려,
 * 1만 건이 넘으면 소계를 못 붙입니다."
 *
 * <p>★이 테스트가 지키는 것은 "빠르다"가 아니라 <b>"목록과 같은 집합을 센다"</b>이다.
 * 소계가 목록과 다른 걸 세면 화면에서 합이 안 맞고, 그건 조용히 틀린다.
 * 그래서 서버는 {@code search} 와 <b>같은 WHERE 절 상수</b>를 공유하고,
 * 여기서는 <b>같은 조건으로 부른 목록의 합</b>과 대조한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("묶음 합계(프론트 B-3)")
class GroupTotalsIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-GT" + (System.nanoTime() % 1_000_000L);
    private static final String D1 = "2046-02-10";
    private static final String D2 = "2046-02-11";

    private Long pa;
    private Long pb;
    private Long wh;
    private Long book;
    private Long coupon;
    private Long consignWh;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "GTS" + SFX, "name", "인쇄", "type", "NORMAL"));
        pa = createId("/masters/clients",
                Map.of("code", "GTA" + SFX, "name", "묶음가", "type", "NORMAL"));
        pb = createId("/masters/clients",
                Map.of("code", "GTB" + SFX, "name", "묶음나", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "GTW" + SFX, "name", "물류", "type", "MAIN"));
        Map<String, Object> b = new HashMap<>();
        b.put("code", "GTK" + SFX);
        b.put("name", "묶음도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);
        post("/stock/inbound", Map.of("processedDate", "2046-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 900))));

        sell(pa, D1, 10);
        sell(pa, D1, 20);   // 같은 거래처·같은 날 두 건 → 합쳐져야 한다
        sell(pa, D2, 5);
        sell(pb, D1, 30);

        // ★아래 셋이 2026-11-02 프론트 실측(I-4)에서 소계를 어긋나게 한 축이다.
        //   예전 시드는 전부 정상출고라 세 버그를 **하나도 못 잡았다**.
        // ① 쿠폰 — 도서명에 '쿠폰'이 들어가면 수량만 0으로 센다(레거시 조회.vb:1335)
        Map<String, Object> cb = new HashMap<>(b);
        cb.put("code", "GTC" + SFX);
        cb.put("name", "강의쿠폰" + SFX);
        coupon = createId("/masters/products", cb);
        post("/stock/inbound", Map.of("processedDate", "2046-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", coupon, "unitCost", 1000, "qty", 500))));
        sellProduct(pa, D1, coupon, 7);

        // ② 반품 — 목록은 음수로 내려준다(§3-2). 소계도 음수로 상쇄해야 한다
        JsonNode ret = post("/sales/return-inbound", Map.of("returnDate", D2,
                "partnerId", pa, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "qty", 4,
                        "unitPrice", 10000, "supplyRate", 70))));
        assertThat(ret.path("success").asBoolean()).as("반품입고 %s", ret).isTrue();

        // ③ 위탁 미결 — 출고/반품 조회 목록에만 있고 소계엔 없었다
        consignWh = createId("/masters/warehouses", Map.of("code", "GTCW" + SFX,
                "name", "묶음위탁창고", "type", "CONSIGN", "ownerClientId", pb));
        JsonNode co = post("/sales/entries", Map.of("salesDate", D1,
                "partnerId", pb, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "CONSIGN_SHIP",
                        "qty", 50, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(co.path("success").asBoolean()).as("위탁출고 %s", co).isTrue();
    }

    private void sellProduct(Long partner, String date, Long productId, int qty) {
        JsonNode r = post("/sales/entries", Map.of("salesDate", date,
                "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", productId, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private void sell(Long partner, String date, int qty) {
        JsonNode r = post("/sales/entries", Map.of("salesDate", date,
                "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private String range() {
        return "fromDate=" + D1 + "&toDate=" + D2;
    }

    private JsonNode totals(String groupBy, String extra) {
        JsonNode raw = get("/sales/group-totals?groupBy=" + groupBy + "&" + range() + extra);
        assertThat(raw.path("success").asBoolean()).as("%s", raw).isTrue();
        return data(raw);
    }

    private JsonNode row(JsonNode rows, String name) {
        for (JsonNode r : rows) {
            if (name.equals(r.path("name").asText())) {
                return r;
            }
        }
        throw new AssertionError("묶음 '" + name + "' 없음. 응답=" + rows);
    }

    @Test
    @DisplayName("★거래처별로 묶는다 — 같은 거래처의 여러 건이 한 줄로 합쳐진다")
    void 거래처별() {
        JsonNode rows = totals("PARTNER", "&partnerIds=" + pa + "&partnerIds=" + pb);

        // 10+20+5 − 반품4 = 31. 쿠폰 7은 수량에서 0으로 센다.
        assertThat(row(rows, "묶음가").path("qty").asLong()).as("10+20+5−4").isEqualTo(31);
        assertThat(row(rows, "묶음나").path("qty").asLong()).isEqualTo(30);
        // 공급가액 = 10,000×70%×(35−4) + 쿠폰 7×7,000 (쿠폰은 **금액은 그대로**)
        assertThat(row(rows, "묶음가").path("supplyAmount").asLong())
                .isEqualTo(31 * 7_000 + 7 * 7_000);
    }

    @Test
    @DisplayName("거래일자별로도 묶는다")
    void 일자별() {
        JsonNode rows = totals("DATE", "&partnerIds=" + pa + "&partnerIds=" + pb);

        assertThat(row(rows, D1).path("qty").asLong()).as("10+20+30 (쿠폰 7은 0)").isEqualTo(60);
        assertThat(row(rows, D2).path("qty").asLong()).as("5 − 반품4").isEqualTo(1);
    }

    @Test
    @DisplayName("★목록과 같은 집합을 센다 — 조건을 걸어도 둘이 어긋나지 않는다")
    void 목록과_같은_집합() {
        // 거래처 하나로 좁히고, 목록을 전량 받아 직접 더한 값과 대조한다.
        String cond = "&partnerIds=" + pa;
        long listQty = 0;
        for (JsonNode l : data(get("/sales?" + range() + cond + "&size=200")).path("content")) {
            listQty += l.path("qty").asLong();
        }

        JsonNode rows = totals("PARTNER", cond);
        long sumQty = 0;
        for (JsonNode r : rows) {
            sumQty += r.path("qty").asLong();
        }

        assertThat(sumQty).as("소계 합 = 목록 합").isEqualTo(listQty).isEqualTo(31);
        assertThat(rows).as("조건으로 좁혔으니 그 거래처만").hasSize(1);
    }

    @Test
    @DisplayName("shippedOnly 도 같이 먹는다 — 출고/반품 조회의 소계가 목록과 달라지면 안 된다")
    void 출고조건도_같이() {
        JsonNode rows = totals("PARTNER", "&partnerIds=" + pa + "&shippedOnly=true");
        assertThat(row(rows, "묶음가").path("qty").asLong()).isEqualTo(31);
    }

    @Test
    @DisplayName("★★반품은 소계에서도 음수로 상쇄된다 — 양수로 세면 두 배가 된다")
    void 반품_부호() {
        // 프론트 실측(I-4): 한 거래처가 목록 35 ↔ 합계 37, 금액 10,500원 차이로 잡혔다.
        // 반품 1건(수량 1·5,250원)을 양수로 세서 2배(2건·10,500원)가 된 것이었다.
        assertThat(row(totals("PARTNER", "&partnerIds=" + pa), "묶음가").path("qty").asLong())
                .as("반품 4부가 빠진 31이어야(39가 되면 양수로 센 것)").isEqualTo(31);
    }

    @Test
    @DisplayName("★★쿠폰은 수량만 0 — couponCount=true 면 센다")
    void 쿠폰_수량() {
        // 프론트 실측(I-4): 한 거래처가 목록 288 ↔ 합계 576(정확히 두 배)으로 잡혔다.
        long off = row(totals("PARTNER", "&partnerIds=" + pa), "묶음가").path("qty").asLong();
        long on = row(totals("PARTNER", "&partnerIds=" + pa + "&couponCount=true"), "묶음가")
                .path("qty").asLong();

        assertThat(off).as("기본은 쿠폰 7을 0으로").isEqualTo(31);
        assertThat(on).as("couponCount=true 면 7을 더해 38").isEqualTo(38);
        // 금액은 둘이 같다 — 쿠폰은 '부수'로만 안 세고 팔린 것은 맞다.
        assertThat(row(totals("PARTNER", "&partnerIds=" + pa), "묶음가")
                .path("supplyAmount").asLong())
                .isEqualTo(row(totals("PARTNER", "&partnerIds=" + pa + "&couponCount=true"),
                        "묶음가").path("supplyAmount").asLong());
    }

    @Test
    @DisplayName("★★shippedOnly 면 위탁 미결도 소계에 더한다 — 목록에만 있고 합계엔 없었다")
    void 위탁미결도_소계에() {
        // 프론트 실측(I-4): 위탁 줄만 있는 거래처 2곳이 합계에서 **통째로 빠졌다**.
        JsonNode rows = totals("PARTNER", "&partnerIds=" + pb + "&shippedOnly=true");
        // 묶음나 = 정상출고 30 + 위탁 원출고 50
        assertThat(row(rows, "묶음나").path("qty").asLong()).as("30 + 위탁 50").isEqualTo(80);
        // 금액은 위탁 몫을 **더하지 않는다** — 미결이라 매출이 아직 서지 않았다.
        assertThat(row(rows, "묶음나").path("supplyAmount").asLong()).isEqualTo(30 * 7_000);
    }

    @Test
    @DisplayName("★★★목록 전량 합 = 묶음 합계 — 프론트가 손으로 하던 대조를 고정한다")
    void 목록전량_합_대조() {
        for (String flag : new String[] {"", "&shippedOnly=true"}) {
            for (String cp : new String[] {"", "&couponCount=true"}) {
                String cond = "&partnerIds=" + pa + "&partnerIds=" + pb + flag + cp;
                Map<String, long[]> byName = new java.util.HashMap<>();
                for (JsonNode l : data(get("/sales?" + range() + cond + "&size=200"))
                        .path("content")) {
                    long[] v = byName.computeIfAbsent(l.path("partnerName").asText(),
                            k -> new long[2]);
                    v[0] += l.path("qty").asLong();
                    v[1] += l.path("supplyAmount").asLong();
                }
                for (JsonNode r : totals("PARTNER", cond)) {
                    long[] v = byName.getOrDefault(r.path("name").asText(), new long[2]);
                    assertThat(r.path("qty").asLong())
                            .as("수량 — %s (조건%s%s)", r.path("name").asText(), flag, cp)
                            .isEqualTo(v[0]);
                    assertThat(r.path("supplyAmount").asLong())
                            .as("금액 — %s (조건%s%s)", r.path("name").asText(), flag, cp)
                            .isEqualTo(v[1]);
                }
            }
        }
    }
}
