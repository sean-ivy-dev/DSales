package com.daesung.sales.inventory;

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
 * 재고엔진(로직A) + 매출취소 역분개 + 월마감 잠금 회귀테스트.
 * 감사에서 '구현완료인데 회귀 미고정'으로 지적된 핵심로직을 자동검증으로 고정.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("재고엔진·취소·월마감 통합테스트")
class InventoryEngineIntegrationTest extends IntegrationTestSupport {

    private Long supplier;
    private Long whA;
    private Long whB;

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients", Map.of("code", "IE-SUP", "name", "인쇄소", "type", "NORMAL"));
        whA = createId("/masters/warehouses", Map.of("code", "IE-A", "name", "A창고", "type", "MAIN"));
        whB = createId("/masters/warehouses", Map.of("code", "IE-B", "name", "B창고", "type", "MAIN"));
    }

    @Test
    @DisplayName("이고 — A→B 재고 이동(출발 −, 도착 +)")
    void 이고() {
        Long p = product("IE-TR");
        inbound(whA, p, 1000);
        post("/stock/transfer", Map.of(
                "processedDate", "2026-06-01", "fromWarehouseId", whA, "toWarehouseId", whB,
                "items", List.of(Map.of("productId", p, "qty", 300, "reason", "이고테스트"))));
        assertThat(balance(whA, "IE-TR")).isEqualTo(700);
        assertThat(balance(whB, "IE-TR")).isEqualTo(300);
    }

    @Test
    @DisplayName("폐기 — 재고 차감")
    void 폐기() {
        Long p = product("IE-DP");
        inbound(whA, p, 500);
        post("/disposals", Map.of(
                "processedDate", "2026-06-01", "warehouseId", whA,
                "items", List.of(Map.of("productId", p, "qty", 100, "reason", "파손"))));
        assertThat(balance(whA, "IE-DP")).isEqualTo(400);
    }

    @Test
    @DisplayName("재고실사 — 실물 대조 후 차이만큼 ADJUST")
    void 실사() {
        Long p = product("IE-ST");
        inbound(whA, p, 500);
        post("/stock/stocktakes", Map.of(
                "warehouseId", whA, "stocktakeDate", "2026-06-05", "memo", "정기실사",
                "items", List.of(Map.of("productId", p, "countedQty", 480))));
        assertThat(balance(whA, "IE-ST")).isEqualTo(480);   // 500 → 480 (ADJUST −20)
    }

    @Test
    @DisplayName("BOM 조립/해체 — 완제품+구성품 재고 변환")
    void bom조립해체() {
        Long parent = createId("/masters/products",
                Map.of("code", "IE-SET", "name", "세트", "contentType", "SELF", "set", true,
                "salesDivision", "교재"));
        Long c1 = product("IE-C1");
        Long c2 = product("IE-C2");
        // 세트 1개 = c1 2개 + c2 1개
        put("/masters/products/" + parent + "/bom", Map.of("components",
                List.of(Map.of("childProductId", c1, "ratio", 2), Map.of("childProductId", c2, "ratio", 1))));
        inbound(whA, c1, 100);
        inbound(whA, c2, 100);

        // 조립 20세트 → c1 −40, c2 −20, 세트 +20
        post("/stock/bom", Map.of("processedDate", "2026-06-01", "warehouseId", whA,
                "direction", "ASSEMBLE", "parentProductId", parent, "workQty", 20));
        assertThat(balance(whA, "IE-C1")).isEqualTo(60);
        assertThat(balance(whA, "IE-C2")).isEqualTo(80);
        assertThat(balance(whA, "IE-SET")).isEqualTo(20);

        // 해체 5세트 → 역방향(c1 +10, c2 +5, 세트 −5)
        post("/stock/bom", Map.of("processedDate", "2026-06-01", "warehouseId", whA,
                "direction", "DISASSEMBLE", "parentProductId", parent, "workQty", 5));
        assertThat(balance(whA, "IE-C1")).isEqualTo(70);
        assertThat(balance(whA, "IE-C2")).isEqualTo(85);
        assertThat(balance(whA, "IE-SET")).isEqualTo(15);
    }

    @Test
    @DisplayName("매출취소 — 재고 역분개(복구)")
    void 매출취소() {
        Long p = product("IE-CX");
        inbound(whA, p, 100);
        // 9월로 격리한 매출 30
        post("/sales/entries", Map.of("salesDate", "2026-09-15", "partnerId", supplier, "warehouseId", whA,
                "items", List.of(Map.of("productId", p, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 100, "qty", 30))));
        assertThat(balance(whA, "IE-CX")).isEqualTo(70);

        long saleId = data(get("/sales?startDate=2026-09-01&endDate=2026-09-30&partnerId=" + supplier))
                .path("content").get(0).path("id").asLong();
        JsonNode cancel = post("/sales/" + saleId + "/cancel", Map.of());
        assertThat(cancel.path("success").asBoolean()).as("취소: %s", cancel).isTrue();
        assertThat(balance(whA, "IE-CX")).isEqualTo(100);   // 재고 복구
    }

    @Test
    @DisplayName("월마감 — 잠긴 기간 매출등록 차단(PERIOD_LOCKED), 해제 후 허용")
    void 월마감잠금() {
        Long p = product("IE-LK");
        inbound(whA, p, 100);
        post("/closing/periods/lock", Map.of("year", 2026, "month", 10, "memo", "10월 마감"));

        JsonNode blocked = post("/sales/entries", Map.of(
                "salesDate", "2026-10-15", "partnerId", supplier, "warehouseId", whA,
                "items", List.of(Map.of("productId", p, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 100, "qty", 10))));
        assertThat(blocked.path("success").asBoolean()).isFalse();
        assertThat(blocked.path("error").path("code").asText()).isEqualTo("PERIOD_LOCKED");

        // 해제 후 허용
        post("/closing/periods/unlock", Map.of("year", 2026, "month", 10));
        JsonNode ok = post("/sales/entries", Map.of(
                "salesDate", "2026-10-15", "partnerId", supplier, "warehouseId", whA,
                "items", List.of(Map.of("productId", p, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 100, "qty", 10))));
        assertThat(ok.path("success").asBoolean()).as("해제 후: %s", ok).isTrue();
    }

    @Test
    @DisplayName("제품수불부 엑셀 다운로드 — xlsx(한글 헤더·데이터)")
    void 수불부엑셀() throws Exception {
        Long p = product("IE-XL");
        inbound(whA, p, 500);
        var resp = getBytes("/stock/ledger/export?warehouseId=" + whA);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        byte[] xlsx = resp.getBody();
        assertThat(new String(xlsx, 0, 2)).isEqualTo("PK");
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("제품수불부");
            // 0행=제목, 1행=조회기준, 2행=헤더 (재무팀 실파일 형식)
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("제품수불부현황");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).startsWith("조회기준 : ");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("도서코드");
            assertThat(sheet.getLastRowNum()).isGreaterThan(0);
        }
    }

    // ── helpers ──

    private Long product(String code) {
        return createId("/masters/products",
                Map.of("code", code, "name", code, "contentType", "SELF", "price", 10000,
                "salesDivision", "교재"));
    }

    private void inbound(Long wh, Long productId, int qty) {
        post("/stock/inbound", Map.of(
                "processedDate", "2026-06-01", "supplierClientId", supplier, "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", productId, "unitCost", 3000, "qty", qty))));
    }

    /** 창고×상품 현재 재고(inventory.qty 캐시) — 제품수불부 cachedBalance. */
    private int balance(Long wh, String code) {
        JsonNode rows = data(get("/stock/ledger?warehouseId=" + wh)).path("content");
        for (JsonNode r : rows) {
            if (code.equals(r.path("productCode").asText())) {
                return r.path("cachedBalance").asInt();
            }
        }
        throw new AssertionError("재고행 없음: " + code + " @wh" + wh);
    }
}
