package com.daesung.sales.document.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 특약점 PDF 파일명 파서. <b>파일명이 곧 메타데이터다.</b>
 *
 * <p>형식: {@code {종류}_{거래처코드}_{거래처명}_{yyyyMMdd}-{yyyyMMdd}.pdf}<br>
 * 예: {@code SALES_10003_강남 하람도서_20260101-20260831.pdf}
 *
 * <p>근거: 레거시 DSRE {@code FM_BASE_CustMng.cs:618~690} 이 같은 규칙으로 읽고
 * {@code tbl_doc_info} 에 넣는다(10/26 특약점 명세 §7). 기존에 올려 둔 파일과 같은 체계를
 * 유지해야 사이트에서 섞어 보여 줄 수 있다.
 *
 * <p>★<b>거래처명에 {@code _} 가 들어갈 수 있다.</b> 그래서 앞에서 둘(종류·코드), 뒤에서
 * 하나(기간)를 떼고 <b>남은 가운데를 전부 이름으로</b> 본다 — 단순히 {@code split("_")} 로
 * 토막 수를 세면 이름에 밑줄이 있는 거래처가 통째로 거부된다.
 */
public final class DocumentFileName {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    /** 레거시가 쓰는 두 종류. {@code EXAM}(거래처 없는 파일)은 범위 밖이다(명세 §7). */
    private static final String[] TYPES = {"SALES", "RESULT"};

    /**
     * 파일명의 종류 토막 → 정식 표기. 못 찾으면 null.
     *
     * <p>★<b>대소문자를 가린다(정확히 일치).</b> 명세 §7과 실물 파일
     * ({@code SALES_…}·{@code RESULT_…})이 전부 대문자로 고정이고, 레거시 C# 도 그렇게 쓴다.
     *
     * <p>‼️{@code toUpperCase}·{@code equalsIgnoreCase} 를 쓰지 않는 이유 — 케이스 매핑은
     * 글자 수·내용이 바뀌는 경우가 있어(터키어 i, {@code 'ſ'.toUpperCase()=='S'})
     * 정적분석(FindSecBugs {@code IMPROPER_UNICODE})이 보안 결함으로 잡는다.
     * 소문자로 온 파일은 거부되지만, 오류 문구가 기대 형식을 그대로 알려 준다.
     */
    private static String canonicalType(String raw) {
        for (String t : TYPES) {
            if (t.equals(raw)) {
                return t;
            }
        }
        return null;
    }

    private DocumentFileName() {
    }

    /**
     * 파싱 결과. {@code error} 가 있으면 나머지는 신뢰할 수 없다 —
     * 호출부는 그 파일만 빼고 나머지를 계속 올린다(명세 §7 "그 파일만 뺀다").
     */
    public record Parsed(String docType, String partnerCode, String partnerName,
                         LocalDate from, LocalDate to, String baseMonth, String error) {

        public boolean ok() {
            return error == null;
        }

        static Parsed fail(String message) {
            return new Parsed(null, null, null, null, null, null, message);
        }
    }

    public static Parsed parse(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Parsed.fail("파일명이 비어 있습니다");
        }
        String name = fileName.trim();
        // ★확장자도 **문자열을 변환하지 않고** 끝 4글자만 대소문자 무시로 맞춰 본다.
        //   toLowerCase 로 통째로 바꾸면 위 canonicalType 과 같은 이유로 보안 결함이 된다.
        if (name.length() < 4 || !name.regionMatches(true, name.length() - 4, ".pdf", 0, 4)) {
            return Parsed.fail("PDF 파일이 아닙니다: " + fileName);
        }
        String body = name.substring(0, name.length() - 4);

        int first = body.indexOf('_');
        int second = (first < 0) ? -1 : body.indexOf('_', first + 1);
        int last = body.lastIndexOf('_');
        if (first < 0 || second < 0 || last <= second) {
            return Parsed.fail("파일명 형식이 다릅니다 — {종류}_{거래처코드}_{거래처명}_{기간}.pdf 여야 합니다: " + fileName);
        }

        String rawType = body.substring(0, first);
        String docType = canonicalType(rawType);
        if (docType == null) {
            return Parsed.fail("알 수 없는 종류: " + rawType + " (SALES/RESULT만 받습니다)");
        }
        String partnerCode = body.substring(first + 1, second).trim();
        if (partnerCode.isEmpty()) {
            return Parsed.fail("거래처코드가 비어 있습니다: " + fileName);
        }
        // 가운데 전부가 이름이다 — 이름에 '_' 가 있어도 살아남는다.
        String partnerName = body.substring(second + 1, last).trim();
        String period = body.substring(last + 1).trim();

        int dash = period.indexOf('-');
        if (dash < 0) {
            return Parsed.fail("기간 형식이 다릅니다(yyyyMMdd-yyyyMMdd): " + period);
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(period.substring(0, dash), YYYYMMDD);
            to = LocalDate.parse(period.substring(dash + 1), YYYYMMDD);
        } catch (DateTimeParseException e) {
            return Parsed.fail("기간 날짜를 읽을 수 없습니다(yyyyMMdd-yyyyMMdd): " + period);
        }
        if (to.isBefore(from)) {
            return Parsed.fail("기간 종료일이 시작일보다 빠릅니다: " + period);
        }
        // ★기준월 = **기간 종료일의 월**(명세 §7). 시작일로 잡으면 1/1~8/31 파일이
        //   1월 것이 되어, 사이트에서 8월을 고른 특약점에게 안 보인다.
        String baseMonth = String.format("%04d-%02d", to.getYear(), to.getMonthValue());
        return new Parsed(docType, partnerCode, partnerName, from, to, baseMonth, null);
    }
}
