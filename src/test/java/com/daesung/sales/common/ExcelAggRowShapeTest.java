package com.daesung.sales.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.common.excel.ExcelExportUtil;
import com.daesung.sales.common.excel.ExcelExportUtil.Col;
import com.daesung.sales.common.excel.ExcelExportUtil.Heading;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 엑셀 집계행 모양 — <b>구분 칸 한글 · 단계별 색 · 학년 숫자형</b>.
 *
 * <p>2026-11-09 검증에서 세 가지가 드러났다(전부 재무팀이 받는 파일에 보이는 것이다):
 * <ul>
 *   <li>'구분' 칸에 {@code DETAIL}·{@code CAT_SUBTOTAL} 같은 <b>내부 코드가 그대로</b> 나갔다</li>
 *   <li><b>분류 계와 대분류 계가 같은 연노랑</b>이라 파일에서 두 소계가 구분되지 않았다</li>
 *   <li><b>학년이 문자</b>로 나가 레거시 실파일(int)과 달랐다 — 숫자 정렬이 안 된다</li>
 * </ul>
 *
 * <p>★{@code rowType} <b>원본 코드</b>는 집계행 판정·색칠에 계속 쓰인다 — 한글 번역은
 * 셀 값에만 적용된다. 그 둘이 뒤섞이면 색이 통째로 사라지므로 함께 못박는다.
 */
@DisplayName("엑셀 집계행 — 구분 한글·단계별 색·학년 숫자(검증 2026-11-09)")
class ExcelAggRowShapeTest {

    private final ExcelExportUtil excel = new ExcelExportUtil(
            new com.fasterxml.jackson.databind.ObjectMapper());

    private static Map<String, Object> row(String rowType, String grade, long amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowType", rowType);
        m.put("grade", grade);
        m.put("amount", amount);
        return m;
    }

    private Sheet sheet() throws Exception {
        List<Col> cols = List.of(new Col("구분", "rowType"), new Col("학년", "grade"),
                new Col("금액", "amount"));
        byte[] xlsx = excel.toXlsx("검증", cols, List.of(
                        row("DETAIL", "3", 100),
                        row("CAT_SUBTOTAL", null, 100),
                        row("MAJOR_SUBTOTAL", null, 100),
                        row("GRAND_TOTAL", null, 100)),
                Heading.period("검증표", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
                ExcelExportUtil.AGG_BY_ROW_TYPE);
        return new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheetAt(0);
    }

    /** 제목 2행 + 머리글 1행 뒤가 본문이다. */
    private static final int FIRST_BODY_ROW = 3;

    @Test
    @DisplayName("‼️'구분' 칸은 한글로 나간다 — 내부 코드가 보이면 안 된다")
    void 구분칸_한글() throws Exception {
        Sheet s = sheet();
        assertThat(cell(s, FIRST_BODY_ROW)).isEqualTo("명세");
        assertThat(cell(s, FIRST_BODY_ROW + 1)).isEqualTo("분류 계");
        assertThat(cell(s, FIRST_BODY_ROW + 2)).isEqualTo("대분류 계");
        assertThat(cell(s, FIRST_BODY_ROW + 3)).isEqualTo("총 계");
    }

    @Test
    @DisplayName("‼️분류 계와 대분류 계는 **다른 색**이다 — 같으면 단계를 구분할 수 없다")
    void 단계별_색이_다르다() throws Exception {
        Sheet s = sheet();
        String detail = fill(s, FIRST_BODY_ROW);
        String cat = fill(s, FIRST_BODY_ROW + 1);
        String major = fill(s, FIRST_BODY_ROW + 2);
        String total = fill(s, FIRST_BODY_ROW + 3);

        assertThat(cat).as("분류 계에는 색이 있어야 한다").isNotNull().isNotEqualTo(detail);
        assertThat(major).as("대분류 계 색: 분류 계와 달라야 한다").isNotNull().isNotEqualTo(cat);
        assertThat(total).as("총 계 색: 소계들과 달라야 한다").isNotNull()
                .isNotEqualTo(cat).isNotEqualTo(major);
    }

    @Test
    @DisplayName("학년은 **숫자 셀**이다 — 레거시 실파일이 int 다")
    void 학년은_숫자() throws Exception {
        Cell c = sheet().getRow(FIRST_BODY_ROW).getCell(1);
        assertThat(c.getCellType()).as("문자로 나가면 숫자 정렬이 안 된다").isEqualTo(CellType.NUMERIC);
        assertThat((int) c.getNumericCellValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("숫자가 아닌 학년은 글자로 둔다 — '고3' 같은 값을 잃지 않는다")
    void 숫자아닌_학년은_그대로() throws Exception {
        List<Col> cols = List.of(new Col("학년", "grade"));
        byte[] xlsx = excel.toXlsx("검증", cols, List.of(row("DETAIL", "고3", 0)));
        Sheet s = new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheetAt(0);
        Cell c = s.getRow(1).getCell(0);        // 제목 없음 → 머리글 1행 뒤
        assertThat(c.getCellType()).isEqualTo(CellType.STRING);
        assertThat(c.getStringCellValue()).isEqualTo("고3");
    }

    private static String cell(Sheet s, int r) {
        return s.getRow(r).getCell(0).getStringCellValue();
    }

    /** 배경색을 RGB 문자열로. 색이 없으면 null. */
    private static String fill(Sheet s, int r) {
        Row row = s.getRow(r);
        org.apache.poi.xssf.usermodel.XSSFColor c =
                ((org.apache.poi.xssf.usermodel.XSSFCellStyle) row.getCell(0).getCellStyle())
                        .getFillForegroundColorColor();
        if (c == null || c.getRGB() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : c.getRGB()) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
