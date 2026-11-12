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
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 엑셀 날짜 칸 — <b>날짜 값 + yyyy-mm-dd 서식</b>.
 *
 * <p>발주처 2026-11-12 ④: "거래일이 날짜가 아니라 텍스트('2026-02-28')로 들어가 있습니다.
 * 엑셀 필터·피벗에서 연·월 집계가 안 됩니다." — 값이 Jackson 평탄화를 거쳐 문자열로 오기 때문이었다.
 */
@DisplayName("엑셀 날짜 칸 — 텍스트가 아니라 날짜로(발주처 11/12 ④)")
class ExcelDateCellTest {

    private final ExcelExportUtil excel = new ExcelExportUtil(
            new com.fasterxml.jackson.databind.ObjectMapper());

    private static final int FIRST_BODY_ROW = 3;   // 제목 2행 + 머리글 1행

    private static Map<String, Object> row(String rowType, Object date, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowType", rowType);
        m.put("tradeDate", date);
        m.put("code", code);
        return m;
    }

    private Sheet sheet(List<Map<String, Object>> rows) throws Exception {
        List<Col> cols = List.of(new Col("구분", "rowType"), new Col("거래일", "tradeDate"),
                new Col("코드", "code"));
        byte[] xlsx = excel.toXlsx("검증", cols, rows,
                Heading.period("검증표", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 11)),
                ExcelExportUtil.AGG_BY_ROW_TYPE);
        return new XSSFWorkbook(new ByteArrayInputStream(xlsx)).getSheetAt(0);
    }

    @Test
    @DisplayName("★거래일은 날짜 셀이고 yyyy-mm-dd 서식이다 — 집계행 색은 그대로")
    void 날짜칸() throws Exception {
        Sheet s = sheet(List.of(row("DETAIL", "2026-02-28", "A10A195"),
                row("GRAND_TOTAL", "2026-11-11", null)));

        Cell d = s.getRow(FIRST_BODY_ROW).getCell(1);
        assertThat(d.getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(DateUtil.isCellDateFormatted(d)).isTrue();
        assertThat(d.getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(d.getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd");

        Cell agg = s.getRow(FIRST_BODY_ROW + 1).getCell(1);
        assertThat(DateUtil.isCellDateFormatted(agg)).isTrue();
        assertThat(agg.getCellStyle().getFillPattern())
                .as("집계행 바탕색이 날짜 칸에서 빠지면 안 된다")
                .isEqualTo(s.getRow(FIRST_BODY_ROW + 1).getCell(0).getCellStyle().getFillPattern());
    }

    @Test
    @DisplayName("날짜가 아닌 값은 글자 그대로 — 코드·시각·없는 날짜를 날짜로 바꾸지 않는다")
    void 날짜아님() throws Exception {
        Sheet s = sheet(List.of(row("DETAIL", "2026-11-11T10:00:00", "2026-02-30"),
                row("DETAIL", "", "DL-H2026A01-06")));

        assertThat(s.getRow(FIRST_BODY_ROW).getCell(1).getCellType()).isEqualTo(CellType.STRING);
        assertThat(s.getRow(FIRST_BODY_ROW).getCell(2).getStringCellValue()).isEqualTo("2026-02-30");
        assertThat(s.getRow(FIRST_BODY_ROW + 1).getCell(2).getStringCellValue()).isEqualTo("DL-H2026A01-06");
    }
}
