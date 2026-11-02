package com.daesung.sales.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 엑셀 서식 — 10/27 검수 피드백 §7-6(색·테두리) · §7-A(불필요하게 넓은 칸 · 13칸) · §7-B(첫 열 라벨).
 *
 * <p>기존 {@code ExcelExportIntegrationTest}가 "칸에 값이 들어 있나"를 보는 테스트라면,
 * 이쪽은 <b>"어떻게 보이나"</b>를 본다 — 색·테두리·너비는 눈으로만 확인되던 영역이라
 * 바뀌어도 아무 테스트도 안 깨졌다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("엑셀 서식(10/27 피드백 §7-6·§7-A)")
class ExcelFormatIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-XF" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2086;

    private Long partner;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "XFS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "XFP" + SFX, "name", "서식거래처",
                "type", "NORMAL",
                // ★긴 글이 들어가는 칸 — autoSizeColumn 그대로면 터무니없이 넓어지던 자리다.
                "assureNote", "서울특별시 강남구 테헤란로 123길 45, 6층 601호 (역삼동, 대성빌딩) 보증보험증권 첨부"));
        Long wh = createId("/masters/warehouses", Map.of("code", "XFW" + SFX, "name", "물류", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of("code", "XFB" + SFX, "name", "서식도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "catCode", "A" + YEAR + "A", "catName", "서식분류",
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));
        JsonNode r = post("/sales/entries", Map.of("salesDate", YEAR + "-03-10",
                "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 10, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        post("/closing/collections", Map.of("collDate", YEAR + "-03-20", "partnerId", partner,
                "collType", "CASH", "collAmt", 30_000));
    }

    private Sheet sheet(String path) {
        var res = getBytes(path);
        assertThat(res.getStatusCode().value()).as("HTTP %s", path).isEqualTo(200);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(res.getBody()))) {
            Sheet s = wb.getSheetAt(0);
            // 워크북을 닫으면 읽을 수 없으므로 필요한 값만 미리 뽑도록 복제본을 만들지 않고
            // 이 메서드 안에서 끝내는 대신, 호출부가 쓰도록 시트를 그대로 돌려준다.
            // (XSSFWorkbook은 close 후에도 이미 메모리에 로드된 시트 접근이 가능하다)
            return s;
        } catch (Exception e) {
            throw new AssertionError("xlsx 파싱 실패: " + path, e);
        }
    }

    private String ledgerPath() {
        return "/closing/ar-ledger/export?partnerId=" + partner
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";
    }

    @Test
    @DisplayName("★집계행(일 계·월 계·누 계)은 색으로 구분된다 — 명세행은 색이 없다")
    void 집계행_색상() {
        Sheet s = sheet(ledgerPath());

        boolean foundFilledAggregate = false;
        boolean foundPlainDetail = false;
        for (Row row : s) {
            Cell first = row.getCell(0);
            if (first == null || first.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING) {
                continue;
            }
            String v = first.getStringCellValue();
            boolean filled = first.getCellStyle().getFillPattern() == FillPatternType.SOLID_FOREGROUND;
            if (v.contains("계")) {          // 일 계 · 월 계 · 누 계 · 이월 금액
                foundFilledAggregate |= filled;
            } else if (v.startsWith(String.valueOf(YEAR))) {   // 날짜로 시작하는 명세행
                foundPlainDetail |= !filled;
            }
        }
        assertThat(foundFilledAggregate).as("집계행에 색이 없다").isTrue();
        assertThat(foundPlainDetail).as("명세행까지 색이 칠해졌다").isTrue();
    }

    @Test
    @DisplayName("★표에 검은 테두리가 있다")
    void 테두리() {
        Sheet s = sheet(ledgerPath());
        Row header = s.getRow(2);   // 제목·조회기준 2행 다음이 헤더

        assertThat(header).isNotNull();
        assertThat(header.getCell(0).getCellStyle().getBorderBottom())
                .as("헤더 테두리").isEqualTo(BorderStyle.THIN);
    }

    @Test
    @DisplayName("★긴 글이 들어가는 칸도 과하게 넓어지지 않는다(§7-A 불필요하게 넓은 칸)")
    void 칸너비_상한() {
        Sheet s = sheet("/closing/ar-status/export/collateral?fromDate=" + YEAR
                + "-01-01&toDate=" + YEAR + "-12-31");

        for (int c = 0; c < 18; c++) {
            int chars = s.getColumnWidth(c) / 256;
            assertThat(chars).as("%d번 칸이 너무 넓다(%d자)", c, chars).isLessThanOrEqualTo(41);
        }
    }

    @Test
    @DisplayName("★제목 2행이 첫 칸 너비를 밀어내지 않는다 — 날짜 칸이 26자로 부풀던 버그")
    void 제목행이_너비를_안밀어낸다() {
        Sheet s = sheet(ledgerPath());

        // 1행(제목)·2행(조회기준)은 A열에 긴 문구가 들어간다. 그 길이에 A열이 맞춰지면 안 된다.
        int chars = s.getColumnWidth(0) / 256;
        assertThat(chars).as("A열이 제목 길이에 맞춰 부풀었다(%d자)", chars).isLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("★집계행 라벨이 첫 번째 열에 있다(§7-B '월 계는 첫 번째 열')")
    void 집계라벨_첫칸() {
        Sheet s = sheet(ledgerPath());

        boolean found = false;
        for (Row row : s) {
            Cell first = row.getCell(0);
            if (first != null && first.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                    && first.getStringCellValue().contains("월 계")) {
                found = true;
            }
        }
        assertThat(found).as("'월 계'가 첫 번째 열에 없다").isTrue();
    }

    @Test
    @DisplayName("★외상매출장 엑셀은 레거시 13칸이다 — 구분·전표번호·분류코드·도서코드는 없다")
    void 레거시_13칸() {
        Sheet s = sheet(ledgerPath());
        Row header = s.getRow(2);

        java.util.List<String> headers = new java.util.ArrayList<>();
        for (Cell c : header) {
            headers.add(c.getStringCellValue());
        }
        assertThat(headers).containsExactly("일자", "분류명", "도서명", "공급(%)",
                "매출수량", "매출금액", "세액", "교사용수량", "교사용금액",
                "반품수량", "반품금액", "수금액", "잔액");
    }
}
