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
 * 세트 <b>조립·해체 현황</b>(30p) 회귀 고정.
 * 근거: 발주처 화면검토(2026-08-31) 화면30 — "화면 27의 비용 산출과는 <b>연동되지 않도록 분리</b>해,
 * 세트 조립·해체 작업을 진행한 <b>현황(결과)만</b> 보여주는 조회 화면으로 유지".
 *
 * <p>★고정하려는 것은 넷이다.
 * <ol>
 *   <li><b>완제품만</b> 행이 된다 — 구성품이 세트인 척 끼면 세트 수가 부풀어 오른다.</li>
 *   <li>조립과 해체가 <b>따로</b> 잡히고 순증이 맞는다.</li>
 *   <li>구성품 소모·복원이 <b>실제 원장</b>에서 나온다(BOM 비율 역산이 아니다).</li>
 *   <li>응답에 <b>비용 필드가 없다</b> — 발주처가 요구한 '분리'가 그것이다.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("세트 조립·해체 현황(30p)")
class BomWorkStatusIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-BW" + (System.nanoTime() % 1_000_000L);
    private static final String CAT = "B2063" + (System.nanoTime() % 100);
    private static final int YEAR = 2063;

    private Long wh;
    private Long set;
    private Long partA;
    private Long partB;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "BWS" + SFX, "name", "인쇄", "type", "NORMAL"));
        wh = createId("/masters/warehouses", Map.of("code", "BWW" + SFX, "name", "조립창고", "type", "MAIN"));

        set = product("BWSET" + SFX, "조립세트");
        partA = product("BWA" + SFX, "구성품A");
        partB = product("BWB" + SFX, "구성품B");

        // BOM: 세트 1개 = A 2개 + B 3개
        JsonNode br = put("/masters/products/" + set + "/bom", Map.of("components",
                List.of(Map.of("childProductId", partA, "ratio", 2),
                        Map.of("childProductId", partB, "ratio", 3))));
        assertThat(br.path("success").asBoolean()).as("BOM 등록: %s", br).isTrue();

        // 구성품 재고를 넉넉히 채운다
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", partA, "unitCost", 1000, "qty", 1000),
                        Map.of("productId", partB, "unitCost", 1000, "qty", 1000))));

        // 조립 10 + 조립 5 = 2건 15세트 / 해체 4 = 1건 4세트
        bomWork(set, "ASSEMBLE", 10, YEAR + "-03-01");
        bomWork(set, "ASSEMBLE", 5, YEAR + "-03-02");
        bomWork(set, "DISASSEMBLE", 4, YEAR + "-03-03");
    }

    private Long product(String code, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("contentType", "SELF");
        m.put("price", 10000);
        m.put("supplyRate", 70);
        m.put("catCode", CAT);
        m.put("catName", "조립분류");
        m.put("salesDivision", "교재");
        return createId("/masters/products", m);
    }

    private void bomWork(Long parent, String direction, int qty, String date) {
        JsonNode r = post("/stock/bom", Map.of("processedDate", date, "warehouseId", wh,
                "parentProductId", parent, "direction", direction, "workQty", qty));
        assertThat(r.path("success").asBoolean()).as("BOM 작업: %s", r).isTrue();
    }

    private JsonNode status() {
        return data(get("/stock/bom-work-status?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&warehouseIds=" + wh + "&catCode=" + CAT));
    }

    /**
     * 취소한 세트작업은 <b>현황에서 빠진다</b>(운영 실측 2026-11-09).
     *
     * <p>‼️예전엔 남았다. 취소 역분개는 {@code BOM_ASSEMBLE} + <b>음수</b>라
     * 집계 조건({@code qty > 0})에 걸리지 않아 <b>원본 조립만 세어졌고</b>,
     * 그래서 취소한 작업이 "조립 N부"로 영원히 보였다.
     *
     * <p>★부호 조건은 그대로 둔다 — 이 집계는 <b>부호로 완제품과 구성품을 가른다</b>.
     * 조건을 풀면 취소 역분개의 완제품(−)이 구성품으로 섞인다.
     * 그래서 <b>전표번호</b>로 뺀다(원본·역분개가 같은 번호라 둘이 함께 사라진다).
     *
     * <p>★재고 이력에는 두 줄이 <b>그대로 남는다</b> — 장부는 보존하고 실적 집계에서만 뺀다.
     */
    @Test
    @DisplayName("‼️취소한 세트작업은 현황에서 빠진다 — 재고 이력에는 남는다")
    void 취소한_작업은_현황에서_빠진다() {
        Long tmp = product("BWC" + SFX, "취소검증세트");
        // 구성품 하나로 BOM 을 만든다 — 구성품이 없으면 조립이 완제품만 움직인다.
        assertThat(put("/masters/products/" + tmp + "/bom", Map.of(
                "components", List.of(Map.of("childProductId", partA, "ratio", 1))))
                .path("success").asBoolean()).isTrue();

        bomWork(tmp, "ASSEMBLE", 7, YEAR + "-08-01");
        // 전표번호를 찾아 취소한다
        String refNo = null;
        for (JsonNode x : data(get("/stock/records?fromDate=" + YEAR + "-08-01&toDate=" + YEAR
                + "-08-01&productIds=" + tmp))) {
            refNo = x.path("refNo").asText();
        }
        assertThat(refNo).as("세트작업 전표번호(BW-)").isNotNull().startsWith("BW-");

        // 취소 전 — 현황에 보인다
        assertThat(found(tmp)).as("취소 전에는 보여야 한다").isTrue();

        assertThat(post("/stock/vouchers/" + refNo + "/cancel",
                Map.of("reason", "취소 검증")).path("success").asBoolean()).isTrue();

        // ‼️취소 후 — 현황에서 사라진다
        assertThat(found(tmp)).as("취소한 작업이 '조립 7부'로 남으면 안 된다").isFalse();

        // ★재고 이력에는 두 줄(원본·역분개)이 남는다 — 장부는 지우지 않는다.
        // ‼️역분개는 **취소한 날**로 들어간다(원본 거래일이 아니다) — 기간을 좁히면 한 줄만 잡힌다.
        int records = 0;
        long sum = 0;
        for (JsonNode x : data(get("/stock/records?fromDate=2000-01-01&toDate=2099-12-31"
                + "&productIds=" + tmp))) {
            if (refNo.equals(x.path("refNo").asText())) {
                records++;
                sum += x.path("qtyDelta").asLong();
            }
        }
        assertThat(records).as("재고 이력은 보존한다(원본 +7, 역분개 −7)").isEqualTo(2);
        assertThat(sum).as("두 줄의 합은 0 — 재고는 되돌아갔다").isZero();
    }

    /** 세트작업 현황에 그 완제품 행이 있는가. */
    private boolean found(Long productId) {
        for (JsonNode r : status().path("rows")) {
            if (productId.equals(r.path("productId").asLong())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("★완제품만 행이 된다 — 구성품이 세트인 척 끼면 세트 수가 부풀어 오른다")
    void 완제품만_행() {
        JsonNode rows = status().path("rows");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("productCode").asText()).isEqualTo("BWSET" + SFX);
        // 구성품은 조립·해체로 움직였지만 '세트 작업'은 아니다
        for (JsonNode r : rows) {
            assertThat(r.path("productCode").asText())
                    .as("구성품이 행으로 올라오면 안 된다")
                    .isNotIn("BWA" + SFX, "BWB" + SFX);
        }
    }

    @Test
    @DisplayName("★조립·해체가 따로 잡히고 순증이 맞는다")
    void 조립_해체_순증() {
        JsonNode row = status().path("rows").get(0);

        assertThat(row.path("assembleCount").asLong()).as("조립 2건").isEqualTo(2);
        assertThat(row.path("assembleQty").asLong()).as("10 + 5").isEqualTo(15);
        assertThat(row.path("disassembleCount").asLong()).as("해체 1건").isEqualTo(1);
        assertThat(row.path("disassembleQty").asLong()).as("해체 수량은 양수로").isEqualTo(4);
        assertThat(row.path("netQty").asLong()).as("15 − 4").isEqualTo(11);
        assertThat(row.path("lastWorkedAt").asText()).isEqualTo(YEAR + "-03-03");

        JsonNode d = status();
        assertThat(d.path("totalAssembleQty").asLong()).isEqualTo(15);
        assertThat(d.path("totalDisassembleQty").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("★구성품 소모·복원은 실제 원장에서 나온다")
    void 구성품_소모() {
        JsonNode comps = status().path("components");

        JsonNode a = null;
        JsonNode b = null;
        for (JsonNode c : comps) {
            if (("BWA" + SFX).equals(c.path("productCode").asText())) {
                a = c;
            } else if (("BWB" + SFX).equals(c.path("productCode").asText())) {
                b = c;
            }
        }
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();

        // A는 비율 2 → 조립 15세트에 30개 소모, 해체 4세트에 8개 복원
        assertThat(a.path("usedQty").asLong()).isEqualTo(30);
        assertThat(a.path("restoredQty").asLong()).isEqualTo(8);
        assertThat(a.path("netUsedQty").asLong()).isEqualTo(22);
        // B는 비율 3 → 45 소모, 12 복원
        assertThat(b.path("usedQty").asLong()).isEqualTo(45);
        assertThat(b.path("netUsedQty").asLong()).isEqualTo(33);
    }

    @Test
    @DisplayName("★응답에 비용이 없다 — 발주처가 요구한 '화면27과 분리'가 그것이다")
    void 비용_필드가_없다() {
        JsonNode d = status();
        JsonNode row = d.path("rows").get(0);

        for (String forbidden : List.of("workCost", "cost", "amount", "unitPrice", "logisCost")) {
            assertThat(row.has(forbidden))
                    .as("금액 칸이 있으면 화면27과 값이 갈리는 순간 어느 쪽이 맞는지 다투게 된다: %s", forbidden)
                    .isFalse();
            assertThat(d.has(forbidden)).isFalse();
        }
    }

    @Test
    @DisplayName("기간 밖 작업은 빠진다")
    void 기간_필터() {
        JsonNode d = data(get("/stock/bom-work-status?fromDate=" + YEAR + "-03-02&toDate=" + YEAR + "-03-02"
                + "&warehouseIds=" + wh + "&catCode=" + CAT));

        assertThat(d.path("rows").get(0).path("assembleCount").asLong()).as("3/2 조립 1건만").isEqualTo(1);
        assertThat(d.path("rows").get(0).path("assembleQty").asLong()).isEqualTo(5);
        assertThat(d.path("totalDisassembleQty").asLong()).as("3/3 해체는 밖").isZero();
    }
}
