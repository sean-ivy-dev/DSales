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
 * 계산서신고 <b>과세구분 필터</b> 회귀 고정.
 * 근거: 개발팀 점검(2026-09-09) P0-4 — "과세·면세·전체 어느 것으로 조회해도 같은 5건이 나온다.
 * 면세로 조회하면 세액 컬럼만 감춰질 뿐 <b>과세 매출 전체가 면세 신고 목록에 그대로 실린다</b>."
 *
 * <p>★세무 신고에 쓰는 화면이라 <b>조용한 실패가 가장 위험하다.</b>
 * 그래서 둘을 고정한다 — 필터가 실제로 걸리는가, 그리고 알 수 없는 값을 거부하는가.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("계산서신고 과세구분 필터(P0-4)")
class TaxInvoiceFilterIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-TF" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2067;
    private static final String D = YEAR + "-03-05";

    private Long partner;

    @BeforeAll
    void seed() {
        token();
        partner = createId("/masters/clients", Map.of("code", "TFC" + SFX, "name", "필터거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "TFW" + SFX, "name", "창고", "type", "MAIN"));
        Long sup = createId("/masters/clients", Map.of("code", "TFS" + SFX, "name", "인쇄", "type", "NORMAL"));

        Long taxable = book("TFT" + SFX, "과세도서", false);
        Long free = book("TFF" + SFX, "면세도서", true);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", taxable, "unitCost", 1000, "qty", 500),
                        Map.of("productId", free, "unitCost", 1000, "qty", 500))));

        // 세액은 자동산출하지 않는다(발주처 확정) — 과세 건만 담당자가 직접 넣는다.
        sale(wh, taxable, 10, 10000);
        sale(wh, free, 10, null);
    }

    private Long book(String code, String name, boolean taxFree) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("contentType", "SELF");
        m.put("price", 10000);
        m.put("supplyRate", 70);
        m.put("taxFree", taxFree);
        m.put("catCode", "T" + YEAR + (System.nanoTime() % 100));
        m.put("catName", "필터분류");
        m.put("salesDivision", "교재");
        return createId("/masters/products", m);
    }

    private void sale(Long wh, Long book, int qty, Integer tax) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", book);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", qty);
        if (tax != null) {
            item.put("tax", tax);
        }
        JsonNode r = post("/sales/entries", Map.of("salesDate", D, "partnerId", partner,
                "warehouseId", wh, "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출: %s", r).isTrue();
    }

    private JsonNode invoices(String query) {
        return data(get("/closing/tax-invoices?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&partnerId=" + partner + query)).path("invoices");
    }

    @Test
    @DisplayName("★면세로 조회하면 과세 건이 섞이지 않는다 — 이게 P0-4의 핵심")
    void 면세만() {
        JsonNode inv = invoices("&taxType=FREE");

        assertThat(inv).hasSize(1);
        assertThat(inv.get(0).path("taxType").asText()).isEqualTo("FREE");
        assertThat(inv.get(0).path("typeCode").asText()).as("면세는 홈택스 05").isEqualTo("05");
        assertThat(inv.get(0).path("taxTotal").asLong()).as("면세니 세액 0").isZero();
    }

    @Test
    @DisplayName("과세로 조회하면 과세만")
    void 과세만() {
        JsonNode inv = invoices("&taxType=TAXABLE");

        assertThat(inv).hasSize(1);
        assertThat(inv.get(0).path("taxType").asText()).isEqualTo("TAXABLE");
        assertThat(inv.get(0).path("typeCode").asText()).isEqualTo("01");
        assertThat(inv.get(0).path("taxTotal").asLong()).isPositive();
    }

    @Test
    @DisplayName("미지정은 전체 — 둘 다 나온다")
    void 전체() {
        assertThat(invoices("")).as("과세 1 + 면세 1").hasSize(2);
    }

    @Test
    @DisplayName("★알 수 없는 값은 400 — 조용히 전체로 넘기면 뒤섞인 목록이 걸러진 척 나간다")
    void 잘못된_값은_거부() {
        JsonNode r = get("/closing/tax-invoices?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&taxType=TAXABL");

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("과세구분", "TAXABL");
    }

    @Test
    @DisplayName("export도 같은 필터를 받는다 — 화면과 파일 범위가 다르면 안 된다")
    void export도_같은_필터() {
        JsonNode bad = get("/closing/tax-invoices/export?taxType=WRONG");
        assertThat(bad.path("success").asBoolean()).isFalse();
    }
}
