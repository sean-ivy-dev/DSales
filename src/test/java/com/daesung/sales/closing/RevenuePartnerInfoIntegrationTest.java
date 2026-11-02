package com.daesung.sales.closing;

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
 * 수익신고가 <b>거래처코드·사업자번호·대표자</b>를 함께 실어 주는지 고정한다.
 *
 * <p>근거: 프론트 지적(2026-09-21) — 화면이 이 셋을 컬럼으로 두는데 응답에
 * {@code partnerId}·{@code partnerName} 만 있어 <b>세 칸이 계속 빈칸</b>이었다.
 *
 * <p>★<b>빈 문자열이 아니라 마스터의 값</b>이어야 한다. 신고에 쓰는 표라
 * 사업자번호·대표자가 비면 담당자가 거래처관리를 따로 열어 옮겨 적게 된다 —
 * 그러면 화면에 컬럼을 둔 의미가 없다. 그래서 값 일치까지 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("수익신고 거래처 식별정보(코드·사업자번호·대표자)")
class RevenuePartnerInfoIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-RP" + (System.nanoTime() % 1_000_000L);
    private static final String CODE = "RPC" + SFX;
    private static final String BIZ_NO = "220-81-62517";
    private static final String BOSS = "김대표";

    /** 다른 테스트의 매출과 섞이지 않게 자기 기간을 쓴다. */
    private static final String FROM = "2091-04-01";
    private static final String TO = "2091-04-30";

    @BeforeAll
    void seed() {
        token();
        Long partner = createId("/masters/clients", Map.of(
                "code", CODE, "name", "수익신고검증거래처", "type", "NORMAL",
                "bizNo", BIZ_NO, "bossName", BOSS));
        Long supplier = createId("/masters/clients",
                Map.of("code", "RPS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "RPW" + SFX, "name", "검증창고", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of(
                "code", "RPB" + SFX, "name", "검증교재", "contentType", "SELF",
                "price", 10000, "taxFree", false, "grade", "고3",
                "catCode", "R2091A01", "catName", "검증분류", "useYn", true,
                "salesDivision", "교재"));

        post("/stock/inbound", Map.of("processedDate", FROM, "supplierClientId", supplier,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 100))));

        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2091-04-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 70, "qty", 10, "tax", 7000))));
        assertThat(r.path("success").asBoolean()).as("매출등록: %s", r).isTrue();
    }

    private JsonNode row() {
        JsonNode d = data(get("/closing/revenue-report?taxType=TAXABLE&fromDate=" + FROM + "&toDate=" + TO
                + "&keyword=" + CODE));
        assertThat(d.path("rows")).as("키워드로 우리 거래처만 남는다").hasSize(1);
        return d.path("rows").get(0);
    }

    @Test
    @DisplayName("★세 필드가 거래처 마스터 값 그대로 실린다")
    void 식별정보가_실린다() {
        JsonNode row = row();
        assertThat(row.path("partnerCode").asText()).isEqualTo(CODE);
        assertThat(row.path("bizNo").asText()).isEqualTo(BIZ_NO);
        assertThat(row.path("bossName").asText()).isEqualTo(BOSS);
        // 기존 필드가 그대로인지도 함께 — 인덱스가 밀리면 여기서 먼저 깨진다.
        assertThat(row.path("partnerName").asText()).isEqualTo("수익신고검증거래처");
        assertThat(row.path("totalNetSupply").asLong()).isEqualTo(70_000);
        assertThat(row.path("totalNetTax").asLong()).isEqualTo(7_000);
        assertThat(row.path("months").get(0).path("yearMonth").asText()).isEqualTo("209104");
    }

    @Test
    @DisplayName("코드·사업자번호·대표자로도 검색된다 — 표에 보이는 값으로 찾을 수 있어야 한다")
    void 검색된다() {
        for (String kw : List.of(CODE, BIZ_NO, BOSS)) {
            JsonNode d = data(get("/closing/revenue-report?taxType=TAXABLE&fromDate=" + FROM + "&toDate=" + TO
                    + "&keyword=" + kw));
            assertThat(d.path("rows")).as("키워드 '%s'", kw).hasSize(1);
        }
    }

    @Test
    @DisplayName("합계행은 식별정보를 비운다 — 채우면 그 거래처 신고분으로 읽힌다")
    void 합계행은_비운다() {
        JsonNode total = data(get("/closing/revenue-report?taxType=TAXABLE&fromDate=" + FROM + "&toDate=" + TO))
                .path("total");
        assertThat(total.path("partnerName").asText()).isEqualTo("합계");
        // ‼️Jackson 전역 NON_NULL 이라 null 필드는 아예 사라진다 — hasNonNull 로 본다.
        assertThat(total.hasNonNull("partnerCode")).isFalse();
        assertThat(total.hasNonNull("bizNo")).isFalse();
        assertThat(total.hasNonNull("bossName")).isFalse();
    }

    @Test
    @DisplayName("엑셀에도 세 컬럼이 나간다 — 화면만 고치면 다운로드가 또 빈칸이 된다")
    void 엑셀에도_나간다() throws Exception {
        var res = getBytes("/closing/revenue-report/export?taxType=TAXABLE&fromDate=" + FROM + "&toDate=" + TO);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();

        StringBuilder text = new StringBuilder();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                new java.io.ByteArrayInputStream(res.getBody()))) {
            for (org.apache.poi.ss.usermodel.Row row : wb.getSheetAt(0)) {
                for (org.apache.poi.ss.usermodel.Cell c : row) {
                    if (c.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                        text.append(c.getStringCellValue()).append('');
                    }
                }
            }
        }
        assertThat(text.toString()).contains("거래처코드", "사업자번호", "대표자성명");
        assertThat(text.toString()).as("헤더만이 아니라 값도").contains(CODE, BIZ_NO, BOSS);
    }
}
