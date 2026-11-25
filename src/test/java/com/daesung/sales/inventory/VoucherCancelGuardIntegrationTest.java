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
 * 재고 전표 취소·삭제는 <b>매출이 따라오는 전표를 거부한다</b>(프론트 지적 2026-11-24).
 *
 * <p>‼️<b>이 구멍은 2026-11-24 에 길이 열렸다.</b> 그전엔 반품이 입고/대체 내역에 안 보여
 * 화면에서 그 번호를 누를 수 없었는데, 발주처 2번으로 <b>반품을 보이게 하면서</b> 닿게 됐다.
 *
 * <p>취소·삭제는 <b>재고 이벤트만</b> 되돌린다. 반품({@code RETURN})·매출 출고({@code OUTBOUND})
 * 번호를 넣으면 <b>매출 줄은 그대로 남고 재고만 움직여</b> 두 장부가 어긋나고,
 * 취소 기록은 종류 기본값 때문에 <b>「폐기」로</b> 남는다.
 *
 * <p>화면에서 버튼을 막는 것과 별개로 <b>서버가 거부해야</b> 한다 — 주소로 직접 부를 수 있다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("재고 전표 취소·삭제 — 반품·출고 거부(2026-11-24)")
class VoucherCancelGuardIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "VG" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2081;

    private Long partner;
    private Long warehouse;
    private Long book;
    private String saleNo;      // 매출 출고 전표(= 매출번호)
    private String returnNo;    // 반품 전표
    private String inboundNo;   // 입고 전표 — 이건 통과해야 한다

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "VGS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "VGW" + SFX, "name", "전표가드창고", "type", "MAIN"));
        partner = createId("/masters/clients",
                Map.of("code", "VGP" + SFX, "name", "전표가드거래처", "type", "NORMAL"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "VGB" + SFX);
        b.put("name", "전표가드도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "V" + YEAR + "A");
        b.put("catName", "전표가드");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);

        JsonNode in = data(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 300, "unitCost", 3000)))));
        inboundNo = in.path("inboundNo").asText();

        saleNo = data(post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 30, "unitPrice", 10000, "supplyRate", 70)))))
                .path("items").get(0).path("salesNo").asText();

        returnNo = data(post("/sales/return-inbound", Map.of(
                "returnDate", YEAR + "-03-05", "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 7,
                        "unitPrice", 10000, "supplyRate", 70)))))
                .path("items").get(0).path("salesNo").asText();
    }

    @Test
    @DisplayName("★★**반품 전표**는 취소·삭제를 거부한다 — 매출만 남고 재고가 움직이면 장부가 갈린다")
    void 반품전표_거부() {
        int before = stock();

        JsonNode c = post("/stock/vouchers/" + returnNo + "/cancel?reason=가드검증", Map.of());
        assertThat(c.path("success").asBoolean()).as("취소: %s", c).isFalse();
        assertThat(c.path("error").path("message").asText()).contains("반품");

        JsonNode d = del("/stock/vouchers/" + returnNo + "?reason=가드검증", Map.of());
        assertThat(d.path("success").asBoolean()).as("삭제: %s", d).isFalse();

        assertThat(stock()).as("‼️재고가 움직였으면 가드가 뚫린 것이다").isEqualTo(before);
    }

    @Test
    @DisplayName("★**매출 출고 전표**도 거부한다 — 프론트는 반품만 말했지만 같은 구멍이다")
    void 매출출고전표_거부() {
        int before = stock();
        JsonNode c = post("/stock/vouchers/" + saleNo + "/cancel?reason=가드검증", Map.of());
        assertThat(c.path("success").asBoolean()).as("취소: %s", c).isFalse();
        assertThat(c.path("error").path("message").asText()).contains("매출");
        assertThat(stock()).isEqualTo(before);
    }

    @Test
    @DisplayName("‼️**입고 전표는 그대로 취소된다** — 가드를 통째로 막으면 쓰던 기능이 죽는다")
    void 입고전표는_통과() {
        JsonNode c = post("/stock/vouchers/" + inboundNo + "/cancel?reason=가드검증", Map.of());
        assertThat(c.path("success").asBoolean()).as("입고 취소: %s", c).isTrue();
        assertThat(data(c).path("voucherKind").asText()).isEqualTo("INBOUND");
    }

    @Test
    @DisplayName("★★**자재 출고·회수 전표 수정은 막지 않는다** — 가드가 쓰던 기능을 죽였다(2026-11-25)")
    void 자재전표_수정은_통과() {
        // 자재 입출고는 출고=OUTBOUND · 회수=RETURN 을 쓰고, 그 전표 **수정**은 서버 안에서
        // deleteVoucher 를 부른다. 종류만 보고 막았더니 수정이 통째로 거부됐다.
        Long material = createId("/masters/materials",
                Map.of("code", "VGM" + SFX, "name", "전표가드자재", "materialType", "EXAM_PAPER"));
        JsonNode min = post("/stock/materials/inbound", Map.of(
                "processedDate", YEAR + "-01-06", "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("materialId", material, "qty", 100, "unitCost", 100))));
        assertThat(min.path("success").asBoolean()).as("자재 입고: %s", min).isTrue();

        // 자재 출고 전표를 만든다(TxnType.OUTBOUND — 매출 출고와 같은 종류다).
        JsonNode out = post("/stock/materials/io", Map.of(
                "processedDate", YEAR + "-04-01", "warehouseId", warehouse, "io", "OUTBOUND",
                "items", List.of(Map.of("materialId", material, "qty", 10))));
        assertThat(out.path("success").asBoolean()).as("자재 출고: %s", out).isTrue();
        String refNo = data(out).path("refNo").asText();

        // ★그 전표를 수정한다 — 서버가 지우고 다시 등록한다. 가드에 걸리면 안 된다.
        JsonNode rev = patch("/stock/materials/io/" + refNo, Map.of(
                "processedDate", YEAR + "-04-01", "warehouseId", warehouse, "io", "OUTBOUND",
                "items", List.of(Map.of("materialId", material, "qty", 4))));
        assertThat(rev.path("success").asBoolean())
                .as("‼️거부되면 가드가 자재 전표까지 막은 것이다: %s", rev)
                .isTrue();

        // 회수(반품) 전표도 같다 — TxnType.RETURN 을 쓴다.
        JsonNode rec = post("/stock/materials/io", Map.of(
                "processedDate", YEAR + "-04-02", "warehouseId", warehouse, "io", "RECOVER_RETURN",
                "items", List.of(Map.of("materialId", material, "qty", 3))));
        assertThat(rec.path("success").asBoolean()).as("자재 회수: %s", rec).isTrue();
        JsonNode rec2 = patch("/stock/materials/io/" + data(rec).path("refNo").asText(), Map.of(
                "processedDate", YEAR + "-04-02", "warehouseId", warehouse, "io", "RECOVER_RETURN",
                "items", List.of(Map.of("materialId", material, "qty", 2))));
        assertThat(rec2.path("success").asBoolean())
                .as("‼️회수 수정도 막히면 안 된다: %s", rec2).isTrue();
    }

    private int stock() {
        for (JsonNode n : data(get("/stock/ledger?fromDate=" + YEAR + "-01-01&toDate=" + YEAR
                + "-12-31&productId=" + book + "&size=50")).path("content")) {
            return n.path("closing").asInt();
        }
        return 0;
    }
}
