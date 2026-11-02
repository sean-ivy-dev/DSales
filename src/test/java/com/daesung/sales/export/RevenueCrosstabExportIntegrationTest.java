package com.daesung.sales.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

/**
 * 수익신고 엑셀의 <b>월 가로펼침</b>(크로스탭).
 *
 * <p>근거: 레거시 {@code 수익신고.vb:281~297} — 고정 8칸 뒤에 월마다
 * {@code 건수(YY.MM) · 순매출금액(YY.MM) · 세액(YY.MM)} 3칸을 붙이고,
 * {@code :293} 에서 <b>"12개월을 벗어나면 패스"</b> 한다.
 *
 * <p>발주처 1차회신(2026-10-02 #11)이 "월별 구분은 옆으로 펼친 표로 기존대로 사용"으로
 * 확정했는데, 우리 엑셀은 총계 7칸뿐이라 <b>화면과 다른 표</b>가 내려가고 있었다.
 * 재무팀은 기존 파일과 눈으로 대조하는 환경이라 형식 차이가 곧 클레임이다.
 *
 * <p>‼️<b>다른 테스트의 픽스처에 달을 더하지 않는다.</b> 수익신고·세무 리포트는
 * 공유 픽스처의 합계를 세고 있어서, 매출을 하나 더 넣으면 엉뚱한 곳이 깨진다.
 * 이 클래스는 자기 거래처·자기 연도로만 논다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("수익신고 엑셀 — 월 가로펼침")
class RevenueCrosstabExportIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-RX" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2071;

    private Long partner;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "RXS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of(
                "code", "RXP" + SFX, "name", "크로스탭거래처", "type", "NORMAL",
                "bizNo", "111-22-33333", "bossName", "홍길동"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "RXW" + SFX, "name", "물류", "type", "MAIN"));
        Long product = createId("/masters/products", Map.of(
                "code", "RXB" + SFX, "name", "과세도서", "contentType", "SELF",
                "price", 10000, "taxFree", false, "useYn", true,
                "catCode", "R2071R01", "catName", "크로스탭",
                "salesDivision", "교재"));

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-02",
                "supplierClientId", sup, "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", product, "unitCost", 1000, "qty", 9999))));

        // 13개월에 걸쳐 판다 — 레거시 상한(12)을 **넘겨야** 상한이 동작하는지 보인다.
        for (int m = 1; m <= 12; m++) {
            sell(wh, product, String.format("%d-%02d-10", YEAR, m), m);
        }
        sell(wh, product, (YEAR + 1) + "-01-10", 99);   // 13번째 달
    }

    private void sell(Long wh, Long product, String date, int qty) {
        assertThat(post("/sales/entries", Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 50, "qty", qty, "tax", 100))))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★월마다 건수·순매출금액·세액 3칸이 선다 — 헤더는 (YY.MM)")
    void 월_가로펼침() {
        List<Object> header = header(range(YEAR + "-01-01", YEAR + "-12-31"));

        // 고정 8칸이 레거시 순서 그대로
        assertThat(header.subList(0, 8)).containsExactly(
                "순번", "거래처코드", "거래처명", "사업자번호", "대표자성명",
                "총건수", "총순매출금액", "총세액");

        // 12개월 × 3칸
        assertThat(header).hasSize(8 + 12 * 3);
        assertThat(header).contains("건수(71.01)", "순매출금액(71.01)", "세액(71.01)",
                "건수(71.12)", "순매출금액(71.12)", "세액(71.12)");

        // 달 순서가 섞이면 담당자가 엉뚱한 칸을 읽는다
        assertThat(header.indexOf("건수(71.01)")).isLessThan(header.indexOf("건수(71.02)"));
    }

    @Test
    @DisplayName("‼️13개월을 조회해도 12개월까지만 펼친다(레거시 vb:293)")
    void 열두달_상한() {
        List<Object> header = header(range(YEAR + "-01-01", (YEAR + 1) + "-12-31"));

        assertThat(header).hasSize(8 + 12 * 3);
        assertThat(header).contains("건수(71.01)");
        // 13번째 달은 칸이 빠진다 — 넣으면 레거시 양식보다 넓은 표가 된다.
        assertThat(header).doesNotContain("건수(72.01)");
    }

    @Test
    @DisplayName("값이 제 달 칸에 들어간다 — 총계는 기간 전체로 남는다")
    void 값이_제자리() {
        List<List<Object>> t = read(range(YEAR + "-01-01", YEAR + "-12-31"));
        List<Object> header = t.get(headerRow(t));
        List<Object> row = dataRow(t, header.indexOf("거래처명"));

        // 1월 1부 · 12월 12부, 단가 10,000 × 50% = 5,000
        assertThat(num(row, header.indexOf("순매출금액(71.01)"))).isEqualTo(5_000);
        assertThat(num(row, header.indexOf("순매출금액(71.12)"))).isEqualTo(60_000);

        // 총계는 1+2+…+12 = 78부 → 390,000. 월 칸의 합과 같아야 한다.
        assertThat(num(row, header.indexOf("총순매출금액"))).isEqualTo(390_000);
        long monthSum = 0;
        for (int m = 1; m <= 12; m++) {
            monthSum += num(row, header.indexOf(String.format("순매출금액(71.%02d)", m)));
        }
        assertThat(monthSum).as("월 칸의 합 = 총계").isEqualTo(390_000);
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private String range(String from, String to) {
        return "/closing/revenue-report/export?taxType=TAXABLE&fromDate=" + from + "&toDate=" + to;
    }

    private List<Object> header(String path) {
        List<List<Object>> t = read(path);
        return t.get(headerRow(t));
    }

    /** 우리 거래처 행. 다른 테스트가 같은 기간에 데이터를 넣어도 영향받지 않게 이름으로 찾는다. */
    private List<Object> dataRow(List<List<Object>> t, int nameCol) {
        return t.stream()
                .filter(r -> nameCol >= 0 && nameCol < r.size()
                        && "크로스탭거래처".equals(r.get(nameCol)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("우리 거래처 행이 없다"));
    }

    private static long num(List<Object> row, int col) {
        assertThat(col).as("그 컬럼이 헤더에 없다").isNotNegative();
        Object v = (col < row.size()) ? row.get(col) : null;
        return (v instanceof Number n) ? n.longValue() : 0L;
    }

    private static int headerRow(List<List<Object>> table) {
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).stream().filter(java.util.Objects::nonNull).count() > 1) {
                return i;
            }
        }
        return 0;
    }

    private List<List<Object>> read(String path) {
        ResponseEntity<byte[]> res = getBytes(path);
        assertThat(res.getStatusCode().value()).as("HTTP %s", path).isEqualTo(200);
        assertThat(res.getBody()).isNotNull();

        List<List<Object>> table = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(res.getBody()))) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                List<Object> cells = new ArrayList<>();
                for (int i = 0; i < row.getLastCellNum(); i++) {
                    Cell c = row.getCell(i);
                    cells.add(switch (c == null ? "NULL" : c.getCellType().name()) {
                        case "STRING" -> c.getStringCellValue();
                        case "NUMERIC" -> c.getNumericCellValue();
                        default -> null;
                    });
                }
                table.add(cells);
            }
        } catch (Exception e) {
            throw new AssertionError("xlsx 파싱 실패: " + path, e);
        }
        return table;
    }
}
