package com.daesung.sales.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

/**
 * 엑셀 <b>서식</b>이 레거시 실파일 기준으로 나가는지(F-04 · F-33).
 *
 * <p>F-04: "**모든** 엑셀 다운로드 = 조회 화면(또는 레거시) 양식과 동일. 컬럼 색·소계 색·테두리 포함."
 * F-33 피드백에 레거시 색 코드가 그대로 적혀 있어 그 값을 검증한다.
 *
 * <pre>
 *   머리글        D3D3D3 회색 + 굵게 + 가운데        (예전엔 C0C0C0 였다)
 *   소계          FFFFE0 연노랑 + 파란 굵은 글씨
 *   누계(학년)    D3D3D3 회색   + 파란 굵은 글씨
 *   합계(상품)    90EE90 연두   + 파란 굵은 글씨
 *   총계          FFB6C1 분홍   + 파란 굵은 글씨      (예전엔 전 단계가 한 색이었다)
 *   글꼴          맑은 고딕 9pt                       (예전엔 지정이 없어 Calibri 11)
 *   제목 2행      표 너비만큼 병합
 * </pre>
 *
 * <p>★서식은 <b>44종 공통 유틸</b>이 입힌다. 한 리포트로 고정해 두면 유틸이 바뀔 때
 * 전부가 함께 깨져 알 수 있다 — 리포트마다 적으면 한 곳만 고쳐지고 나머지는 모른다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("엑셀 서식 — 머리글·집계 단계별 색·글꼴·제목 병합(F-04·F-33)")
class ExcelStyleIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-XS" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2097;

    @BeforeAll
    void seed() {
        token();
        Long partner = createId("/masters/clients",
                Map.of("code", "XSP" + SFX, "name", "서식검증거래처", "type", "NORMAL"));
        Long warehouse = createId("/masters/warehouses",
                Map.of("code", "XSW" + SFX, "name", "창고", "type", "MAIN"));
        Long supplier = createId("/masters/clients",
                Map.of("code", "XSS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        Long product = product("XSB" + SFX);

        post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "qty", 500, "unitCost", 1000))));
        post("/sales/entries", Map.of(
                "salesDate", YEAR + "-03-10", "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                        "qty", 100, "unitPrice", 10000, "supplyRate", 70))));
    }

    private Long product(String code) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", code);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2097A01");
        b.put("catName", "서식검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        return createId("/masters/products", b);
    }

    /** 스타일 검증은 워크북을 열어 둔 채로 해야 한다 — 콜백으로 받는다. */
    private void withSheet(String path, java.util.function.Consumer<Sheet> body) {
        ResponseEntity<byte[]> res = getBytes(path);
        assertThat(res.getStatusCode().value()).as("HTTP %s", path).isEqualTo(200);
        assertThat(res.getBody()).as("본문 %s", path).isNotNull();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(res.getBody()))) {
            body.accept(wb.getSheetAt(0));
        } catch (Exception e) {
            throw new AssertionError("xlsx 파싱 실패: " + path, e);
        }
    }

    private static String rgb(CellStyle style) {
        if (style instanceof XSSFCellStyle x && x.getFillForegroundColorColor() != null) {
            byte[] b = x.getFillForegroundColorColor().getRGB();
            return (b == null) ? null : String.format("%02X%02X%02X", b[0], b[1], b[2]);
        }
        return null;
    }

    /** 첫 칸 말고 여러 칸이 채워진 첫 행 = 머리글(제목 2행은 첫 칸만 찬다). */
    private static int headerRow(Sheet sheet) {
        for (Row r : sheet) {
            int filled = 0;
            for (int i = 0; i < r.getLastCellNum(); i++) {
                Cell c = r.getCell(i);
                if (c != null && c.getCellType() != org.apache.poi.ss.usermodel.CellType.BLANK) {
                    filled++;
                }
            }
            if (filled > 1) {
                return r.getRowNum();
            }
        }
        throw new AssertionError("머리글 행을 찾지 못했다");
    }

    @Test
    @DisplayName("머리글 — D3D3D3 + 굵게 + 가운데 + 테두리, 글꼴 맑은 고딕 9pt")
    void 머리글서식() {
        withSheet("/sales/statement/export?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31",
                sheet -> {
                    Row h = sheet.getRow(headerRow(sheet));
                    Cell c = h.getCell(0);
                    CellStyle st = c.getCellStyle();

                    assertThat(rgb(st)).as("머리글 배경 — 예전엔 C0C0C0 였다").isEqualTo("D3D3D3");
                    assertThat(st.getAlignment())
                            .isEqualTo(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
                    assertThat(st.getBorderTop())
                            .isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.THIN);

                    var f = sheet.getWorkbook().getFontAt(st.getFontIndex());
                    assertThat(f.getBold()).isTrue();
                    assertThat(f.getFontName()).as("글꼴").isEqualTo("맑은 고딕");
                    assertThat(f.getFontHeightInPoints()).as("크기").isEqualTo((short) 9);
                });
    }

    @Test
    @DisplayName("집계행 — 단계마다 **다른 색** + 파란 굵은 글씨")
    void 집계행서식() {
        withSheet("/sales/statement/export?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31",
                sheet -> {
                    int hr = headerRow(sheet);
                    java.util.Set<String> aggColors = new java.util.LinkedHashSet<>();
                    boolean blueFound = false;
                    for (int i = hr + 1; i <= sheet.getLastRowNum(); i++) {
                        Row r = sheet.getRow(i);
                        if (r == null || r.getCell(0) == null) {
                            continue;
                        }
                        CellStyle st = r.getCell(0).getCellStyle();
                        String color = rgb(st);
                        if (color == null) {
                            continue;   // 상세행(배경 없음)
                        }
                        aggColors.add(color);
                        var f = sheet.getWorkbook().getFontAt(st.getFontIndex());
                        if (f.getBold()
                                && f.getColor() == org.apache.poi.ss.usermodel.IndexedColors.BLUE.getIndex()) {
                            blueFound = true;
                        }
                    }
                    assertThat(aggColors).as("집계행에 색이 입어야 한다").isNotEmpty();
                    assertThat(blueFound).as("집계행은 파란 굵은 글씨").isTrue();
                    // 레거시 4색 중 하나여야 한다 — 임의 색이 섞이면 규칙이 갈린 것이다.
                    assertThat(aggColors)
                            .as("레거시 색 코드만 쓰여야 한다: %s", aggColors)
                            .allMatch(c -> List.of("FFFFE0", "D3D3D3", "90EE90", "FFB6C1").contains(c));
                });
    }

    @Test
    @DisplayName("제목·조회기준 2행이 표 너비만큼 병합된다")
    void 제목병합() {
        withSheet("/sales/statement/export?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31",
                sheet -> {
                    int hr = headerRow(sheet);
                    assertThat(hr).as("제목·조회기준 2행이 위에 붙는다").isGreaterThan(0);
                    int cols = sheet.getRow(hr).getLastCellNum();
                    assertThat(sheet.getNumMergedRegions()).as("병합 영역").isPositive();
                    var m = sheet.getMergedRegion(0);
                    assertThat(m.getFirstRow()).isZero();
                    assertThat(m.getLastColumn())
                            .as("표 끝열까지 병합(열 %d)", cols).isEqualTo(cols - 1);
                });
    }
}
