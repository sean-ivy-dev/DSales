package com.daesung.sales.common.excel;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * 범용 엑셀(xlsx) 익스포터. 리포트 행(record/DTO) 리스트를 Jackson으로 평탄화 → 필드명 헤더 + 값 셀.
 * 아마란스(회계ERP) 자동연동 대신 '모든 항목 엑셀 다운로드'(수동 업로드) 방침 대응. 근거: 재무팀 확정.
 * 중첩 객체/배열 값은 JSON 문자열로 기록(대부분 리포트 행은 평면 구조).
 */
@Component
@RequiredArgsConstructor
public class ExcelExportUtil {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper om;

    /** 컬럼 스펙: 드라이브 샘플 형식의 한글 헤더 + DTO 필드명. */
    public record Col(String header, String field) {
    }

    /**
     * 제목·조회기준 머리글(2행). 근거: 재무팀 실파일이 전부
     * <b>제목 / 조회기준 / 헤더 / 데이터</b> 구조이고, 프론트도 같은 2행을 붙이고 있다
     * (백엔드 전달 2026-08-20 §A-2 — 서버 export로 일원화하면 이 2행이 사라진다는 지적).
     *
     * <p>재무팀은 <b>기존 파일과 눈으로 대조</b>하는 환경이라 형식 차이가 곧 클레임이다.
     *
     * @param title    1행: 화면 제목(예: 외상매출현황조회)
     * @param criteria 2행: 조회기준(예: {@code 조회기준 : 2026.01.01 ~ 2026.06.30}). null이면 1행만
     */
    public record Heading(String title, String criteria) {

        /** 기간 조회 화면의 표준 문구. 날짜 표기는 재무팀 파일과 같은 점 구분이다. */
        public static Heading period(String title, java.time.LocalDate from, java.time.LocalDate to) {
            return new Heading(title, "조회기준 : " + dot(from) + " ~ " + dot(to));
        }

        /** 기준일 하나짜리 화면(잔액·현황 등). */
        public static Heading asOf(String title, java.time.LocalDate baseDate) {
            return new Heading(title, "조회기준 : " + dot(baseDate));
        }

        /**
         * 기간 뒤에 조건을 덧붙인다(2026-09-28 화면 정리안 — 엑셀 조회기준에 "거래처 N곳" 표시).
         *
         * <p>★<b>왜 필요한가</b> — 거래처를 여러 곳 골라 내려받은 파일은 전량과 겉모습이 같다.
         * 나중에 그 파일만 보면 "전체를 뽑은 것"인지 "몇 곳만 고른 것"인지 알 수 없고,
         * 그대로 보고서에 붙으면 빠진 거래처가 있다는 사실이 드러나지 않는다.
         *
         * @param extra 덧붙일 문구. 비면 기간만 나온다
         */
        public static Heading period(String title, java.time.LocalDate from, java.time.LocalDate to,
                                     String extra) {
            String base = "조회기준 : " + dot(from) + " ~ " + dot(to);
            return new Heading(title,
                    (extra == null || extra.isBlank()) ? base : base + "   |   " + extra.trim());
        }

        private static String dot(java.time.LocalDate d) {
            return (d == null) ? "" : d.format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        }
    }

    /**
     * 분류별 소계 스펙. 근거: 재무팀 실파일(매출액정리_6월.xlsx)이 분류별 '소 계'와 총계를 포함하고,
     * 레거시 {@code 매출액명세서.vb}도 ROLLUP으로 같은 행을 만든다.
     *
     * <p>★<b>정렬된 순서에서 값이 바뀌는 지점</b>에 소계가 들어간다. 그래서 rows가
     * groupBy 기준으로 정렬돼 있어야 한다 — 흩어져 있으면 같은 분류의 소계가 여러 번 찍힌다.
     *
     * @param groupBy  묶음 기준 필드(예: catCode)
     * @param labelKey 소계 행에 표시할 이름 필드(예: catName)
     * @param sumKeys  합계를 낼 숫자 필드들
     */
    public record Subtotal(String groupBy, String labelKey, List<String> sumKeys) {
    }

    /**
     * 집계행 판정자 — <b>{@code rowType} 이 {@code DETAIL} 이 아니면 집계행</b>.
     *
     * <p>★같은 식을 리포트마다 새로 쓰지 않기 위해 상수로 둔다. 우리 리포트는 전부
     * {@code rowType} 에 {@code DETAIL}/소계/합계/총계를 담는 같은 규약을 쓴다
     * (물류작업비 {@code DTL_SUBTOTAL}·{@code GRADE_SUBTOTAL}·{@code PRODUCT_SUBTOTAL}·
     * {@code TOTAL}, 매출액명세서·월별매출액명세서 {@code MAJOR_SUBTOTAL}·
     * {@code GRAND_TOTAL} 등). 판정식을 export 마다 적으면 한 곳만 고쳐져 어긋난다.
     *
     * <p>근거: 발주처 검수 F-19·F-28·F-33 — "집계행에 색이 없다". 행 자체는 이미 들어가는데
     * 이 판정자를 {@code toXlsx} 에 넘기지 않아 색이 안 입던 export 가 8개였다.
     */
    public static final java.util.function.Predicate<Map<String, Object>> AGG_BY_ROW_TYPE =
            m -> m.get("rowType") != null && !"DETAIL".equals(m.get("rowType"));

    /**
     * 필드 값 추출. 평범한 키 외에 <b>{@code 이름[인덱스]}</b> 표기를 지원한다.
     *
     * <p>월별 크로스탭(1월[수량] … 12월[수량])처럼 값이 리스트로 오는 리포트가 있는데,
     * 평면 키 조회만 하면 그 칸이 통째로 빈다(실제로 응시현황에서 빈 채로 나왔다).
     */
    /** 집계 단계 문자열 — {@code rowType} 을 쓴다(없으면 범용 소계). */
    private static String aggKind(Map<String, Object> row) {
        Object t = row.get("rowType");
        return (t == null) ? "SUBTOTAL" : String.valueOf(t);
    }

    /**
     * 집계 단계 코드 → <b>사람이 읽는 한글</b>.
     *
     * <p>‼️예전엔 '구분' 칸에 {@code DETAIL}·{@code CAT_SUBTOTAL} 같은 <b>내부 코드가 그대로</b>
     * 나갔다(검증 2026-11-09). 재무팀이 받는 파일이라 영문 enum 이 보이면 안 된다.
     *
     * <p>★여기 한 곳에서만 바꾼다 — '구분' 칸을 쓰는 리포트가 5종(매출액명세서·월별매출액명세서·
     * 수금원장·외상매출장·제품수불부)이고, 각자 번역하면 같은 코드가 파일마다 다른 말로 나온다.
     * 모르는 코드는 <b>그대로 둔다</b>(새 단계가 생겼을 때 조용히 빈칸이 되면 더 나쁘다).
     */
    static String rowTypeLabel(String code) {
        return switch (code) {
            case "DETAIL" -> "명세";
            case "GRAND_TOTAL", "TOTAL" -> "총 계";
            case "MAJOR_SUBTOTAL" -> "대분류 계";
            case "CAT_SUBTOTAL" -> "분류 계";
            case "PRODUCT_SUBTOTAL" -> "상품 계";
            case "GRADE_SUBTOTAL" -> "학년 계";
            case "DTL_SUBTOTAL" -> "시행 계";
            case "GROUP_SUBTOTAL" -> "소 계";
            case "DAY_SUBTOTAL" -> "일 계";
            case "MONTH_SUBTOTAL" -> "월 계";
            case "SUBTOTAL" -> "소 계";
            default -> code;
        };
    }

    /** 숫자로만 된 문자열이면 정수로, 아니면 원래 값 그대로. 학년처럼 숫자 칸이어야 하는 값에 쓴다. */
    private static Object asIntIfNumeric(Object v) {
        if (!(v instanceof String str) || str.isBlank()) {
            return v;
        }
        try {
            return Integer.valueOf(str.trim());
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static Object resolve(Map<String, Object> row, String field) {
        // ★'구분' 칸(rowType)은 **한글로 바꿔서** 낸다. 집계행 판정·색칠은 원본 코드를
        //   맵에서 직접 읽으므로(AGG_BY_ROW_TYPE·aggKind) 여기 번역은 그쪽에 영향이 없다.
        if ("rowType".equals(field)) {
            Object v = row.get(field);
            return (v == null) ? null : rowTypeLabel(String.valueOf(v));
        }
        // ★학년은 **숫자 칸**이다. 레거시 실파일(물류비계산_20260901-20260930.xlsx)의 학년·시행코드가
        //   둘 다 int 인데 우리는 학년을 문자로 보내 왼쪽 정렬·숫자 정렬 불가였다(검증 2026-11-09).
        //   ‼️숫자로 읽히는 값만 바꾼다 — "고3" 같은 값이 섞이면 그대로 글자로 둬야 한다.
        if ("grade".equals(field)) {
            return asIntIfNumeric(row.get(field));
        }
        int open = field.indexOf('[');
        if (open < 0 || !field.endsWith("]")) {
            return row.get(field);
        }
        Object base = row.get(field.substring(0, open));
        if (!(base instanceof List<?> list)) {
            return null;
        }
        try {
            int idx = Integer.parseInt(field.substring(open + 1, field.length() - 1));
            return (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 컬럼 스펙 지정 xlsx(드라이브 재무파일 형식 대응). 헤더=한글, 값=행의 field 추출.
     * 근거: '엑셀 형식은 드라이브 파일 참고'(재무팀).
     */
    public byte[] toXlsx(String sheetName, List<Col> cols, List<?> rows) {
        return toXlsx(sheetName, cols, rows, null, (Subtotal) null);
    }

    /** 제목·조회기준 2행을 붙인 xlsx(재무팀 실파일 형식). */
    public byte[] toXlsx(String sheetName, List<Col> cols, List<?> rows, Heading heading) {
        return toXlsx(sheetName, cols, rows, heading, (Subtotal) null);
    }

    /**
     * 집계행에 <b>색</b>을 입힌 xlsx(10/27 피드백 §7-6 "일 계·누 계·월 계, 학교(원) 계를
     * 색으로 구분한다"). {@code aggregate}가 참을 돌려주는 행은 회색 배경 + 굵은 글씨가 된다.
     *
     * <p>판정은 <b>평탄화된 맵</b>으로 받는다 — 행이 record든 Map이든 같은 방식으로 쓰이고,
     * 컬럼에 안 실은 필드(예: {@code rowType})로도 가를 수 있다.
     */
    public byte[] toXlsx(String sheetName, List<Col> cols, List<?> rows, Heading heading,
                         java.util.function.Predicate<Map<String, Object>> aggregate) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object r : rows) {
            maps.add(om.convertValue(r, MAP_TYPE));
        }
        List<String> headers = cols.stream().map(Col::header).toList();
        List<BodyRow> body = maps.stream()
                .map(m -> new BodyRow(cols.stream().map(c -> resolve(m, c.field())).toList(),
                        (aggregate != null && aggregate.test(m)) ? aggKind(m) : null))
                .toList();
        return write(sheetName, headers, body, heading);
    }

    /**
     * 본문 한 줄 + <b>집계 단계</b>. 단계마다 색이 다르다(F-33 레거시 색 코드).
     *
     * @param aggKind 집계 종류 문자열({@code rowType} 또는 소계 라벨). 상세행은 null
     */
    private record BodyRow(List<Object> values, String aggKind) {
        boolean aggregate() {
            return aggKind != null;
        }
    }

    /**
     * 집계 단계 → 배경색. <b>레거시 물류비계산 실파일에서 뽑은 값</b>이다
     * (F-33 피드백에 색 코드가 그대로 적혀 있었다).
     *
     * <pre>
     *   소계(시행별)  FFFFE0 연노랑
     *   누계(학년별)  D3D3D3 회색
     *   합계(상품별)  90EE90 연두
     *   총계          FFB6C1 분홍
     * </pre>
     *
     * <p>‼️모르는 단계는 <b>소계색</b>으로 둔다 — 리포트마다 {@code rowType} 이름이 달라
     * (매출액명세서 {@code MAJOR_SUBTOTAL}·{@code CAT_SUBTOTAL} 등) 전부 표에 적을 수 없다.
     * 색이 없는 것보다 "집계행이구나"가 보이는 쪽이 낫고, 4단계가 뚜렷한 리포트는 4색이 된다.
     */
    private static byte[] aggFill(String kind) {
        String k = (kind == null) ? "" : kind.toUpperCase(java.util.Locale.ROOT);
        if (k.contains("TOTAL") && !k.contains("SUBTOTAL")) {
            return new byte[]{(byte) 0xFF, (byte) 0xB6, (byte) 0xC1};   // 총계 분홍
        }
        if (k.contains("PRODUCT")) {
            return new byte[]{(byte) 0x90, (byte) 0xEE, (byte) 0x90};   // 상품 합계 연두
        }
        if (k.contains("GRADE")) {
            return new byte[]{(byte) 0xD3, (byte) 0xD3, (byte) 0xD3};   // 학년 누계 회색
        }
        // ★대분류 계는 분류 계보다 **한 단계 위**다 — 같은 색이면 파일에서 두 소계가 구분되지 않는다
        //   (검증 2026-11-09: 월별매출액명세서에서 분류 소계와 대분류 소계가 둘 다 연노랑이었다).
        if (k.contains("MAJOR")) {
            return new byte[]{(byte) 0xFF, (byte) 0xE4, (byte) 0xB5};   // 대분류 계 살구
        }
        return new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xE0};       // 그 외 소계 연노랑
    }

    /**
     * 제목 2행 + 분류별 소계·총계를 붙인 xlsx.
     *
     * <p>★소계는 <b>본문 행 사이</b>에 끼워 넣는다. 파일을 열었을 때 분류가 끝나는 자리에
     * 바로 '소 계'가 보여야 재무팀이 기존 파일과 줄을 맞춰 볼 수 있다.
     */
    public byte[] toXlsx(String sheetName, List<Col> cols, List<?> rows,
                         Heading heading, Subtotal subtotal) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object r : rows) {
            maps.add(om.convertValue(r, MAP_TYPE));
        }
        List<String> headers = cols.stream().map(Col::header).toList();
        List<BodyRow> body = (subtotal == null)
                ? maps.stream().map(m -> new BodyRow(
                        cols.stream().map(c -> resolve(m, c.field())).toList(), null)).toList()
                : withSubtotals(cols, maps, subtotal);
        return write(sheetName, headers, body, heading);
    }

    /**
     * 본문 + 분류 소계 + 총계 행렬. 숫자 합계는 {@code sumKeys} 컬럼에만 찍고
     * 나머지 칸은 비운다 — 코드·이름 칸에 합계가 찍히면 데이터처럼 읽힌다.
     */
    private List<BodyRow> withSubtotals(List<Col> cols, List<Map<String, Object>> maps,
                                        Subtotal spec) {
        List<BodyRow> out = new ArrayList<>();
        Map<String, java.math.BigDecimal> groupSum = new java.util.LinkedHashMap<>();
        Map<String, java.math.BigDecimal> grandSum = new java.util.LinkedHashMap<>();
        Object curGroup = null;
        String curLabel = null;
        boolean open = false;

        for (Map<String, Object> m : maps) {
            Object g = m.get(spec.groupBy());
            if (open && !java.util.Objects.equals(g, curGroup)) {
                out.add(new BodyRow(totalRow(cols, spec, curLabel + " 소 계", groupSum), "SUBTOTAL"));
                groupSum.clear();
                open = false;
            }
            if (!open) {
                curGroup = g;
                curLabel = String.valueOf(m.getOrDefault(spec.labelKey(), ""));
                open = true;
            }
            out.add(new BodyRow(cols.stream().map(c -> resolve(m, c.field())).toList(), null));
            for (String k : spec.sumKeys()) {
                java.math.BigDecimal v = decimalOf(m.get(k));
                groupSum.merge(k, v, java.math.BigDecimal::add);
                grandSum.merge(k, v, java.math.BigDecimal::add);
            }
        }
        if (open) {
            out.add(new BodyRow(totalRow(cols, spec, curLabel + " 소 계", groupSum), "SUBTOTAL"));
        }
        // 본문이 비어도 총계는 낸다 — 빈 파일과 "0으로 집계됐다"는 다른 말이다.
        out.add(new BodyRow(totalRow(cols, spec, "총 계", grandSum), "TOTAL"));
        return out;
    }

    /** 합계 행 한 줄. 라벨은 첫 칸에, 숫자는 해당 컬럼에만. */
    private static List<Object> totalRow(List<Col> cols, Subtotal spec, String label,
                                         Map<String, java.math.BigDecimal> sums) {
        List<Object> row = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            String field = cols.get(i).field();
            if (i == 0) {
                row.add(label);
            } else if (spec.sumKeys().contains(field)) {
                java.math.BigDecimal v = sums.get(field);
                row.add((v == null) ? 0L : v);
            } else {
                row.add(null);
            }
        }
        return row;
    }

    private static java.math.BigDecimal decimalOf(Object v) {
        if (v instanceof Number n) {
            return new java.math.BigDecimal(n.toString());
        }
        return java.math.BigDecimal.ZERO;
    }

    /** 행 리스트 → xlsx byte[]. 컬럼은 각 행 필드명의 합집합(첫 행 순서 우선). 헤더=필드명. */
    public byte[] toXlsx(String sheetName, List<?> rows) {
        List<Map<String, Object>> maps = new ArrayList<>();
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        for (Object r : rows) {
            Map<String, Object> m = om.convertValue(r, MAP_TYPE);
            maps.add(m);
            columns.addAll(m.keySet());
        }
        List<String> cols = new ArrayList<>(columns);
        return write(sheetName, cols, maps.stream()
                .map(m -> new BodyRow(cols.stream().map(m::get).toList(), null))
                .toList(), null);
    }

    /**
     * 칸 너비 상한(문자 수). 10/27 피드백 §7-A "불필요하게 넓은 칸 — 내용에 맞춰 줄인다".
     * 담보내용(주소)처럼 긴 글이 들어가는 칸이 {@code autoSizeColumn} 그대로면 화면을 밀어낸다.
     */
    private static final int MAX_COL_CHARS = 40;

    /** 칸 너비 하한 — 헤더 글자가 잘리지 않을 만큼. */
    private static final int MIN_COL_CHARS = 6;

    /** 헤더 + 값 행렬 → xlsx byte[](공용 엔진). */
    private byte[] write(String sheetName, List<String> headers, List<BodyRow> rows,
                         Heading heading) {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet((sheetName == null || sheetName.isBlank()) ? "Sheet1" : sheetName);

            // ★표에 검은색 테두리를 두른다(§7-6 "표에는 검은색 테두리를 그려 칸을 구분한다").
            //
            // ★★글꼴은 **맑은 고딕 9pt** — 레거시 실파일이 그 글꼴이고 F-33 이 그렇게 지적했다.
            //   지정하지 않으면 엑셀 기본(Calibri 11)이 되어 레거시와 줄 수·폭이 달라진다.
            Font bold = font(wb, true, null);
            Font plain = font(wb, false, null);
            // 집계행은 **파란 굵은 글씨**(레거시 그대로).
            Font blueBold = font(wb, true, org.apache.poi.ss.usermodel.IndexedColors.BLUE.getIndex());

            CellStyle headerStyle = bordered(wb);
            headerStyle.setFont(bold);
            headerStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
            // 머리글 D3D3D3 — 예전엔 IndexedColors.GREY_25(C0C0C0)라 레거시와 달랐다.
            fill(headerStyle, new byte[]{(byte) 0xD3, (byte) 0xD3, (byte) 0xD3});

            CellStyle dataStyle = bordered(wb);
            dataStyle.setFont(plain);

            // ★집계행은 **단계마다 다른 색**이다(F-33 레거시 색 코드). 예전엔 전 단계가 한 색이라
            //   소계·누계·합계·총계가 구분되지 않았다 — 같은 회색이면 어디가 끝인지 안 보인다.
            java.util.Map<String, CellStyle> aggStyles = new java.util.HashMap<>();

            // ‼️제목·조회기준 2행은 **맨 마지막에** 쓴다. 먼저 쓰면 그 긴 문구가 A열에 들어가
            //   autoSizeColumn 이 A열을 그 길이에 맞춰 버린다(실측: 날짜 칸이 26.5자로 부풀었다).
            int headerRow = (heading == null) ? 0
                    : (heading.criteria() == null || heading.criteria().isBlank()) ? 1 : 2;

            Row header = sheet.createRow(headerRow);
            for (int c = 0; c < headers.size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(headers.get(c));
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = headerRow + 1;
            for (BodyRow r : rows) {
                Row row = sheet.createRow(rowIdx++);
                CellStyle style = dataStyle;
                if (r.aggregate()) {
                    style = aggStyles.computeIfAbsent(r.aggKind(), k -> {
                        CellStyle a = bordered(wb);
                        a.setFont(blueBold);
                        fill(a, aggFill(k));
                        return a;
                    });
                }
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.createCell(c);
                    setCell(cell, c < r.values().size() ? r.values().get(c) : null);
                    cell.setCellStyle(style);
                }
            }

            // 너비는 **본문 기준**으로 잡고 상·하한으로 조인다(제목 2행을 아직 안 썼으므로 안 섞인다).
            for (int c = 0; c < headers.size(); c++) {
                sheet.autoSizeColumn(c);
                int chars = sheet.getColumnWidth(c) / 256;
                sheet.setColumnWidth(c, Math.clamp(chars, MIN_COL_CHARS, MAX_COL_CHARS) * 256);
            }

            // 제목·조회기준(있을 때만). 재무팀 실파일이 전부 이 구조라
            // 파일만 받아도 무엇을 언제 기준으로 뽑았는지 알 수 있어야 한다.
            if (heading != null) {
                CellStyle titleStyle = wb.createCellStyle();
                Font titleFont = wb.createFont();
                titleFont.setBold(true);
                titleFont.setFontHeightInPoints((short) 14);
                titleFont.setFontName("맑은 고딕");
                titleStyle.setFont(titleFont);

                Cell t = sheet.createRow(0).createCell(0);
                t.setCellValue(heading.title());
                t.setCellStyle(titleStyle);
                if (heading.criteria() != null && !heading.criteria().isBlank()) {
                    Cell c = sheet.createRow(1).createCell(0);
                    c.setCellValue(heading.criteria());
                    c.setCellStyle(titleStyle);
                }
                // ★제목·조회기준을 표 너비만큼 **병합**한다(레거시 A1:R1~A5:R5).
                //   병합하지 않으면 긴 문구가 오른쪽 칸을 덮어 인쇄 시 잘린다.
                if (headers.size() > 1) {
                    int last = headers.size() - 1;
                    sheet.addMergedRegion(
                            new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, last));
                    if (heading.criteria() != null && !heading.criteria().isBlank()) {
                        sheet.addMergedRegion(
                                new org.apache.poi.ss.util.CellRangeAddress(1, 1, 0, last));
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "엑셀 생성 실패: " + e.getMessage());
        }
    }

    /**
     * 글꼴 — <b>맑은 고딕 9pt</b>(레거시 실파일 기준, F-33).
     *
     * @param color 글자색 인덱스. null 이면 기본(검정)
     */
    private static Font font(Workbook wb, boolean bold, Short color) {
        Font f = wb.createFont();
        f.setFontName("맑은 고딕");
        f.setFontHeightInPoints((short) 9);
        f.setBold(bold);
        if (color != null) {
            f.setColor(color);
        }
        return f;
    }

    /** 배경색을 RGB 로 입힌다. 인덱스 색은 레거시 코드값을 그대로 못 쓴다. */
    private static void fill(CellStyle style, byte[] rgb) {
        if (style instanceof org.apache.poi.xssf.usermodel.XSSFCellStyle x) {
            x.setFillForegroundColor(new org.apache.poi.xssf.usermodel.XSSFColor(rgb, null));
        }
        style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
    }

    /** 네 변에 얇은 검은 테두리를 두른 기본 스타일(§7-6). */
    private static CellStyle bordered(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        s.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        s.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        s.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        return s;
    }

    private void setCell(Cell cell, Object v) {
        if (v == null) {
            cell.setBlank();
        } else if (v instanceof java.math.BigDecimal d) {
            cell.setCellValue(d.doubleValue());
        } else if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (v instanceof Boolean b) {
            cell.setCellValue(b);
        } else if (v instanceof String s) {
            cell.setCellValue(s);
        } else {
            // 중첩 객체/배열 등은 JSON 문자열로
            try {
                cell.setCellValue(om.writeValueAsString(v));
            } catch (Exception e) {
                cell.setCellValue(String.valueOf(v));
            }
        }
    }

    /** xlsx byte[] → 첨부 다운로드 응답(파일명 UTF-8 인코딩). */
    public ResponseEntity<byte[]> asDownload(byte[] xlsx, String filename) {
        // ★헤더를 직접 만든다. ContentDisposition.filename(name, UTF_8) 은 MIME 인코딩 워드
        //   (`=?UTF-8?Q?=EA=B1=B0...?=`)로 내보내는데, 이건 메일용 형식이라 HTTP에서는
        //   브라우저마다 해석이 갈리고 접근로그에도 그 문자열이 그대로 남았다(2026-09-11 발견).
        //   RFC 6266 권장 형식인 `filename*=UTF-8''<percent>` 로 내보내고,
        //   그걸 못 읽는 구형 클라이언트를 위해 ASCII fallback `filename=` 을 함께 둔다.
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        return ResponseEntity.ok().headers(headers).body(xlsx);
    }
}
