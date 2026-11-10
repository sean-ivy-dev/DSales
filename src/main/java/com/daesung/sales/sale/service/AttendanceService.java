package com.daesung.sales.sale.service;

import com.daesung.sales.sale.dto.AttendancePeriodAgg;
import com.daesung.sales.sale.dto.AttendancePeriodResponse;
import com.daesung.sales.sale.dto.AttendanceRatioAgg;
import com.daesung.sales.sale.dto.AttendanceRatioResponse;
import com.daesung.sales.sale.dto.AttendanceResponse;
import com.daesung.sales.sale.dto.SchoolStudentAgg;
import com.daesung.sales.sale.repository.SaleRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응시현황(연도별). 근거: 레거시 응시현황.vb — 거래처별 월 수량·매출 크로스탭 + 지역구분 rollup.
 *
 * <p>발주처 회신(자료요청서 2-2): 레거시 화면이 "2022년까지만 조회 가능"으로 막혀 있고
 * 전산담당자가 부재라, <b>매출 데이터로 다시 만들어 달라</b>는 요청이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceService {

    private static final int MONTHS = 12;

    private final SaleRepository saleRepository;
    /** 지역비율의 '인원'(재학생 수) 원천 — 매출이 아니라 학교 마스터에서 온다(F-22). */
    private final com.daesung.sales.school.repository.SchoolRepository schoolRepository;

    public AttendanceResponse yearly(int year, String grade, String productType) {
        return yearly(year, grade, productType, null);
    }

    /**
     * 응시현황(연도별) — <b>거래처 검색(keyword)</b> 포함.
     * 근거: 발주처 질의 2026-10-26 ②-① "거래처 검색(keyword)" 요청.
     * 거래처코드·거래처명 부분일치다(통합매출조회 등 다른 화면의 keyword 검색과 같은 규칙).
     */
    public AttendanceResponse yearly(int year, String grade, String productType, String keyword) {
        return yearly(year, grade, productType, keyword, null, null, null);
    }

    /**
     * 응시현황(연도별) — <b>조회구분(거래처/학교) · 학교·학원 · 거래처</b> 조건까지(프론트 §1-1).
     *
     * <p>레거시 두 화면은 같은 쿼리의 rollup 깊이 차이다 —
     * 지역/특약점 {@code rollup(지역구분, custCode)} · 학교/원 {@code rollup(지역구분, custCode, schCode)}.
     * 경로를 둘로 두면 계산식이 두 곳에 생기므로 {@code groupBy} 로 가른다.
     *
     * <p>★<b>학교 마스터가 기준이다.</b> 레거시가 학교 전체를 만들고 매출을 {@code LEFT OUTER JOIN}
     * 으로 붙이므로 <b>매출이 없는 거래처·학교도 행이 나온다</b> — "안 판 학교"를 보는 화면이다.
     *
     * <p>‼️<b>학교코드가 붙은 매출만</b> 센다. 학교에 안 붙은 출고(본사 판매실·온라인몰 등)는
     * 레거시 조인에서 떨어져 어느 행에도 들어가지 않는다. 섞으면 보급률의 분자만 부푼다.
     *
     * @param schoolType 학교/학원. 판정은 {@code isSchool} 로 한다({@link #isSchoolOf} 참고)
     * @param custCode   거래처 조건(레거시 {@code ComboBox_CustList}). null=전체
     * @param groupBy    {@code PARTNER}(기본) / {@code SCHOOL}
     */
    public AttendanceResponse yearly(int year, String grade, String productType, String keyword,
                                     com.daesung.sales.school.entity.SchoolType schoolType,
                                     String custCode, String groupBy) {
        String unit = normalizeYearlyGroupBy(groupBy);
        String gradeFilter = blankToNull(grade);

        // 매출: 학교×월. 학교코드에만 붙는다.
        Map<String, long[]> qtyBySchool = new java.util.HashMap<>();
        Map<String, long[]> amtBySchool = new java.util.HashMap<>();
        for (com.daesung.sales.sale.dto.SchoolMonthAgg m : saleRepository.attendanceYearlyBySchool(
                year, gradeFilter, blankToNull(productType))) {
            int idx = m.getMonth() - 1;
            if (idx < 0 || idx >= MONTHS) {
                continue;
            }
            qtyBySchool.computeIfAbsent(m.getSchoolCode(), k -> new long[MONTHS])[idx] +=
                    (m.getQty() == null) ? 0L : m.getQty();
            amtBySchool.computeIfAbsent(m.getSchoolCode(), k -> new long[MONTHS])[idx] +=
                    (m.getAmount() == null) ? 0L : m.getAmount();
        }

        // 학교 마스터. ‼️같은 학교코드가 거래처별로 여러 행인 경우가 있다(실측 96곳) —
        //   매출은 학교코드에만 붙으므로 그대로 두면 **같은 매출이 여러 거래처 행에 복사**되고
        //   총계가 부풀어 오른다. 재학생 수도 두 번 세어진다. 그래서 **첫 행만** 쓴다
        //   (쿼리가 custCode·schoolCode 로 정렬돼 있어 어느 거래처를 고를지 결정적이다).
        //   지역비율(regionRatio)도 같은 규칙이다.
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Bucket> schools = new ArrayList<>();
        Boolean isSchool = (schoolType == null)
                ? null : schoolType == com.daesung.sales.school.entity.SchoolType.SCHOOL;
        for (com.daesung.sales.sale.dto.SchoolAttendanceAgg sc : schoolRepository.attendanceSchools(
                isSchool, blankToNull(custCode))) {
            if (!seen.add(sc.getSchoolCode())) {
                continue;
            }
            Bucket b = new Bucket(regionGroup(sc.getCustCode(), sc.getCity()),
                    sc.getCustCode(), sc.getCustName(), sc.getCity(), sc.getPartnerLoc());
            b.schoolCode = sc.getSchoolCode();
            b.schoolName = sc.getSchoolName();
            b.isSchool = sc.getIsSchool();
            b.schoolCount = 1;
            b.studentCnt = studentsOf(sc, gradeFilter);
            long[] q = qtyBySchool.get(sc.getSchoolCode());
            long[] a = amtBySchool.get(sc.getSchoolCode());
            for (int i = 0; i < MONTHS; i++) {
                b.qty[i] = (q == null) ? 0L : q[i];
                b.amount[i] = (a == null) ? 0L : a[i];
            }
            schools.add(b);
        }

        // 지역구분 → 거래처 → 학교 순서(레거시 rollup·ORDER BY 와 같다)
        schools.sort((x, y) -> {
            int c = nz(x.regionGroup).compareTo(nz(y.regionGroup));
            if (c != 0) {
                return c;
            }
            c = nz(x.partnerCode).compareTo(nz(y.partnerCode));
            return (c != 0) ? c : nz(x.schoolCode).compareTo(nz(y.schoolCode));
        });

        String kw = blankToNull(keyword);
        List<AttendanceResponse.Row> rows = new ArrayList<>();
        Bucket grand = new Bucket("", null, null, null, null);
        int i = 0;
        while (i < schools.size()) {
            String region = nz(schools.get(i).regionGroup);
            Bucket regionSub = new Bucket(region, null, null, null, null);
            boolean regionHasRow = false;
            while (i < schools.size() && region.equals(nz(schools.get(i).regionGroup))) {
                String partner = nz(schools.get(i).partnerCode);
                // ‼️거래처 줄에 **지역·상세지역을 함께 싣는다**(프론트 실측 2026-11-10 §1).
                //   레거시 `Refresh_DatagridView_특약점` 은 이 줄에 MAX(custCity)='지역' ·
                //   MAX(custLoc)='상세지역' 을 낸다 — 소 계·총 계 줄만 '' 이다.
                //   예전엔 null 로 만들어 거래처 보기에서 두 칸이 늘 비었다.
                Bucket partnerSub = new Bucket(region, schools.get(i).partnerCode,
                        schools.get(i).partnerName,
                        schools.get(i).cityName, schools.get(i).partnerLoc);
                List<Bucket> mine = new ArrayList<>();
                while (i < schools.size() && region.equals(nz(schools.get(i).regionGroup))
                        && partner.equals(nz(schools.get(i).partnerCode))) {
                    mine.add(schools.get(i++));
                }
                // 키워드는 거래처 코드·명에 건다(레거시 조회화면의 거래처 찾기와 같은 축).
                if (kw != null && !matches(mine.get(0), kw)) {
                    continue;
                }
                regionHasRow = true;
                int seq = 0;
                for (Bucket b : mine) {
                    partnerSub.add(b);
                    if ("SCHOOL".equals(unit)) {
                        b.seq = ++seq;
                        rows.add(b.toRow("SCHOOL"));
                    }
                }
                rows.add(partnerSub.toRow("SCHOOL".equals(unit) ? "PARTNER_SUBTOTAL" : "PARTNER"));
                regionSub.add(partnerSub);
                grand.add(partnerSub);
            }
            if (regionHasRow) {
                rows.add(regionSub.toRow("REGION_SUBTOTAL"));
            }
        }
        rows.add(grand.toRow("TOTAL"));
        return new AttendanceResponse(year, rows);
    }

    private static boolean matches(Bucket b, String keyword) {
        return nz(b.partnerCode).contains(keyword) || nz(b.partnerName).contains(keyword);
    }

    /**
     * 연도별 조회구분. 미지정이면 거래처 단위(레거시 기본 라디오).
     *
     * <p>‼️모르는 값은 400 이다 — 조용히 기본값으로 답하면 행 수가 수십 배 달라지는데
     * 오류가 안 나서 담당자는 그 표가 맞다고 읽는다.
     */
    private static String normalizeYearlyGroupBy(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            return "PARTNER";
        }
        String v = groupBy.trim();
        if (!"PARTNER".equals(v) && !"SCHOOL".equals(v)) {
            throw new com.daesung.sales.common.exception.BusinessException(
                    com.daesung.sales.common.exception.ErrorCode.INVALID_INPUT,
                    "조회구분은 PARTNER(거래처별) 또는 SCHOOL(학교별)만 됩니다. 받은 값=" + groupBy);
        }
        return v;
    }

    /** 학년을 지정하면 그 학년 재학생, 미지정이면 1~3학년 합(레거시 querySel 과 같다). */
    private static long studentsOf(com.daesung.sales.sale.dto.SchoolAttendanceAgg sc, String grade) {
        long c1 = (sc.getCls1StudentCnt() == null) ? 0L : sc.getCls1StudentCnt();
        long c2 = (sc.getCls2StudentCnt() == null) ? 0L : sc.getCls2StudentCnt();
        long c3 = (sc.getCls3StudentCnt() == null) ? 0L : sc.getCls3StudentCnt();
        if (grade == null) {
            return c1 + c2 + c3;
        }
        return switch (grade.trim()) {
            case "1" -> c1;
            case "2" -> c2;
            case "3" -> c3;
            default -> c1 + c2 + c3;
        };
    }

    /**
     * 응시현황(기간별, 18p). 지역·거래처·학교·학년별 월 크로스탭 + 처리/비처리/계.
     *
     * <p>★레거시({@code 고사별처리인원.vb})가 죽은 이유를 반복하지 않는 것이 이 구현의 요지다.
     * 그쪽은 월·학년·영역을 SQL에 하드코딩하고 <b>연도마다 3천 줄을 복붙</b>했다:
     * <pre>
     *   If catYear &lt;= 2017 Then ... ElseIf catYear = 2021 Then ...
     *   ElseIf catYear &gt; 2023 Then MessageBox("2022년 까지만 조회 가능합니다")
     * </pre>
     * 그래야 했던 까닭은 두 가지다 — 모의고사 판별을 <b>연도가 박힌 분류코드</b>
     * ({@code catCode in ('M22A','M22B')})로 했고, 영역·월을
     * <b>도서명 문자열</b>({@code bookName like '%11월%고3%1영역%'})로 긁었기 때문이다.
     * 도서 명명 규칙이 해마다 바뀌니 매년 사람이 SQL을 새로 써야 했고, 2022년에 멈췄다.
     *
     * <p>우리는 둘 다 쓰지 않는다. 모의고사는 <b>대분류</b>로 거르고(연도 무관),
     * 처리/비처리는 {@code proc_type}으로 가르며, 월 칸은 <b>조회 기간에서 만든다</b>.
     * 그래서 해가 바뀌어도 손댈 곳이 없다.
     *
     * @param grade     학년 필터(null=전체)
     * @param partnerId 거래처 필터(null=전체)
     */
    public AttendancePeriodResponse period(LocalDate fromDate, LocalDate toDate,
                                           String grade, Long partnerId) {
        return period(fromDate, toDate, grade, null, partnerId, null, null);
    }

    /**
     * 응시현황(기간별) — <b>조회구분·학년 다중선택</b>까지 받는다.
     *
     * <p>근거: 테스트 피드백 1차(2026-09-17) — "레거시와 조회구분·컬럼 축이 다름".
     * 레거시({@code 고사별처리인원.vb})는 <b>지역별/특약점별/학교별</b> 라디오와
     * <b>학년 다중 체크(1·2·3)</b>를 갖고 있었다.
     *
     * <p>★<b>레거시의 분류코드 해독은 옮기지 않았다.</b> 레거시는 3,422줄에 걸쳐
     * 연도마다 {@code catCode='M{2}A' and bookCode='11' → 1학년 4월} 식으로 코드 글자를 뒤진다.
     * 학년·회차 필드가 없어서 그랬던 것이고, <b>우리는 그 필드를 갖고 있다</b>
     * ({@code products.grade}·{@code sales.book_round}·{@code procType}).
     * 그래서 코드 규칙(이슈2)이 정해지지 않아도 같은 숫자를 낼 수 있다.
     *
     * @param groupBy {@code REGION}(지역별) / {@code PARTNER}(특약점별) / {@code SCHOOL}(학교별, 기본)
     *                — 어느 단위까지 펼칠지. 소계는 항상 지역→거래처 순으로 붙는다.
     */
    public AttendancePeriodResponse period(LocalDate fromDate, LocalDate toDate,
                                           String grade, List<String> grades,
                                           Long partnerId, String groupBy) {
        return period(fromDate, toDate, grade, grades, partnerId, groupBy, null);
    }

    /**
     * 위와 같고 <b>상품년도</b> 조건이 붙는다(프론트 요청 2026-11-10 §1-2 · F-24).
     *
     * <p>레거시 조회화면(20장 빨간 박스)의 '상품년도' 다. <b>도서의 상품년도</b>
     * ({@code products.product_year})이며 거래일자 연도가 아니다 — 2026년에 2025년판을
     * 파는 일이 흔하므로 둘을 섞으면 숫자가 달라진다.
     *
     * <p>★다중선택이다. 빈 목록·null 이면 전체다.
     */
    public AttendancePeriodResponse period(LocalDate fromDate, LocalDate toDate,
                                           String grade, List<String> grades,
                                           Long partnerId, String groupBy,
                                           List<Integer> productYears) {
        List<String> gradeFilter = com.daesung.sales.common.query.MultiSelect.merge(
                blankToNull(grade), grades);
        String unit = normalizeGroupBy(groupBy);
        return periodInternal(fromDate, toDate, gradeFilter, partnerId, unit, productYears);
    }

    /**
     * 조회구분 정규화. 미지정이면 학교까지 펼친다(가장 잘게 — 레거시 기본).
     *
     * <p>‼️모르는 값은 거부한다. 조용히 기본값으로 넘기면 담당자는 지역별로 본다고 믿는데
     * 학교별 목록을 보게 된다 — 행 수가 수십 배 달라지는데 오류가 안 난다.
     */
    private static String normalizeGroupBy(String raw) {
        if (raw == null || raw.isBlank()) {
            return "SCHOOL";
        }
        String v = raw.trim();
        if (List.of("REGION", "PARTNER", "SCHOOL").contains(v)) {
            return v;
        }
        throw new com.daesung.sales.common.exception.BusinessException(
                com.daesung.sales.common.exception.ErrorCode.INVALID_INPUT,
                "알 수 없는 조회구분입니다: " + raw + " (REGION=지역별 / PARTNER=특약점별 / SCHOOL=학교별)");
    }

    private AttendancePeriodResponse periodInternal(LocalDate fromDate, LocalDate toDate,
                                                    List<String> gradeFilter, Long partnerId,
                                                    String unit, List<Integer> productYears) {
        List<String> months = monthsBetween(fromDate, toDate);
        Map<String, Integer> monthIndex = new LinkedHashMap<>();
        for (int i = 0; i < months.size(); i++) {
            monthIndex.put(months.get(i), i);
        }

        // 학교×학년 단위로 월 칸을 채운다(빈 달은 0).
        Map<String, PeriodBucket> byKey = new LinkedHashMap<>();
        for (AttendancePeriodAgg a : saleRepository.attendancePeriod(fromDate, toDate,
                com.daesung.sales.common.query.MultiSelect.isAny(gradeFilter),
                com.daesung.sales.common.query.MultiSelect.orPlaceholder(gradeFilter, "\u0000"),
                partnerId,
                com.daesung.sales.common.query.MultiSelect.isAny(productYears),
                // ‼️빈 목록을 그대로 넘기면 `in ()` 가 돼 HQL 이 터진다 — 자리표를 둔다.
                //   상품년도에 쓰이지 않는 값이어야 한다(연도에 -1 은 없다).
                com.daesung.sales.common.query.MultiSelect.orPlaceholder(productYears, -1))) {
            String region = regionOfPartnerCode(a.getPartnerCode());
            String key = region + "|" + nz(a.getPartnerCode()) + "|" + nz(a.getSchoolCode())
                    + "|" + nz(a.getGrade());
            PeriodBucket b = byKey.computeIfAbsent(key, k -> new PeriodBucket(months.size(),
                    region, a.getPartnerCode(), a.getPartnerName(),
                    a.getSchoolCode(), a.getSchoolName(), a.getGrade()));
            Integer idx = monthIndex.get(String.format("%04d-%02d", a.getYear(), a.getMonth()));
            if (idx == null) {
                continue;   // 기간 밖(있을 수 없지만 방어)
            }
            b.graded[idx] += num(a.getGradedQty());
            b.ungraded[idx] += num(a.getUngradedQty());
        }

        // 지역 → 거래처 → 학교 순으로 묶어 소계·총계를 만든다(레거시 rollup 순서와 같다).
        List<AttendancePeriodResponse.Row> rows = new ArrayList<>();
        PeriodBucket grand = new PeriodBucket(months.size(), null, null, null, null, null, null);
        String curRegion = null;
        String curPartner = null;
        PeriodBucket regionSub = null;
        PeriodBucket partnerSub = null;

        for (PeriodBucket b : sortedBuckets(byKey.values())) {
            if (partnerSub != null && !java.util.Objects.equals(b.partnerCode, curPartner)) {
                if (!"REGION".equals(unit)) {
                    rows.add(partnerSub.toRow("PARTNER_SUBTOTAL"));
                }
                partnerSub = null;
            }
            if (regionSub != null && !java.util.Objects.equals(b.region, curRegion)) {
                rows.add(regionSub.toRow("REGION_SUBTOTAL"));
                regionSub = null;
            }
            if (regionSub == null) {
                curRegion = b.region;
                regionSub = new PeriodBucket(months.size(), b.region, null, null, null, null, null);
            }
            if (partnerSub == null) {
                curPartner = b.partnerCode;
                partnerSub = new PeriodBucket(months.size(), b.region, b.partnerCode, b.partnerName,
                        null, null, null);
            }
            // ★조회구분이 SCHOOL 일 때만 학교 줄을 편다.
            //   지역별·특약점별을 골랐는데 학교 줄이 다 나오면 행이 수십 배가 되고,
            //   담당자는 소계만 보려던 화면에서 원하는 줄을 찾지 못한다.
            if ("SCHOOL".equals(unit)) {
                rows.add(b.toRow("SCHOOL"));
            }
            partnerSub.add(b);
            regionSub.add(b);
            grand.add(b);
        }
        if (partnerSub != null && !"REGION".equals(unit)) {
            rows.add(partnerSub.toRow("PARTNER_SUBTOTAL"));
        }
        if (regionSub != null) {
            rows.add(regionSub.toRow("REGION_SUBTOTAL"));
        }
        rows.add(grand.toRow("TOTAL"));

        return new AttendancePeriodResponse(fromDate, toDate, months, rows);
    }

    /** 지역 → 거래처 → 학교 → 학년 순. rollup은 같은 값이 연속으로 붙어 있어야 성립한다. */
    private static List<PeriodBucket> sortedBuckets(java.util.Collection<PeriodBucket> src) {
        List<PeriodBucket> list = new ArrayList<>(src);
        list.sort(java.util.Comparator
                .comparing((PeriodBucket b) -> nz(b.region))
                .thenComparing(b -> nz(b.partnerCode))
                .thenComparing(b -> nz(b.schoolCode))
                .thenComparing(b -> nz(b.grade)));
        return list;
    }

    /**
     * 조회 기간이 포함하는 월 목록(yyyy-MM). <b>여기가 레거시와 갈리는 지점</b> —
     * 월을 상수로 두지 않으니 해가 바뀌어도, 기간이 해를 넘겨도 손댈 곳이 없다.
     */
    private static List<String> monthsBetween(LocalDate from, LocalDate to) {
        List<String> out = new ArrayList<>();
        java.time.YearMonth cur = java.time.YearMonth.from(from);
        java.time.YearMonth end = java.time.YearMonth.from(to);
        while (!cur.isAfter(end)) {
            out.add(String.format("%04d-%02d", cur.getYear(), cur.getMonthValue()));
            cur = cur.plusMonths(1);
        }
        return out;
    }

    private static long num(Long v) {
        return (v == null) ? 0L : v;
    }

    private static String nz(String v) {
        return (v == null) ? "" : v;
    }

    /** 월 칸 누적 통. 소계·총계도 같은 통을 써서 합산 규칙이 갈리지 않게 한다. */
    private static final class PeriodBucket {
        private final String region;
        private final String partnerCode;
        private final String partnerName;
        private final String schoolCode;
        private final String schoolName;
        private final String grade;
        private final long[] graded;
        private final long[] ungraded;

        private PeriodBucket(int months, String region, String partnerCode, String partnerName,
                             String schoolCode, String schoolName, String grade) {
            this.region = region;
            this.partnerCode = partnerCode;
            this.partnerName = partnerName;
            this.schoolCode = schoolCode;
            this.schoolName = schoolName;
            this.grade = grade;
            this.graded = new long[months];
            this.ungraded = new long[months];
        }

        private void add(PeriodBucket o) {
            for (int i = 0; i < graded.length; i++) {
                graded[i] += o.graded[i];
                ungraded[i] += o.ungraded[i];
            }
        }

        private AttendancePeriodResponse.Row toRow(String rowType) {
            List<Long> g = new ArrayList<>(graded.length);
            List<Long> u = new ArrayList<>(graded.length);
            List<Long> t = new ArrayList<>(graded.length);
            long tg = 0;
            long tu = 0;
            for (int i = 0; i < graded.length; i++) {
                g.add(graded[i]);
                u.add(ungraded[i]);
                t.add(graded[i] + ungraded[i]);
                tg += graded[i];
                tu += ungraded[i];
            }
            return new AttendancePeriodResponse.Row(rowType, region, partnerCode, partnerName,
                    schoolCode, schoolName, grade, g, u, t, tg, tu, tg + tu);
        }
    }

    /**
     * 지역구분. <b>레거시 규칙을 그대로 옮겼다</b>(응시현황.vb:337~339) —
     * <pre>
     *   custCode 앞 1자리가 '2'  → 특약점_ + 앞2자리 + 도시명
     *   custCode &lt; '90001'      → 특약점_ + 앞1자리 + 도시명
     *   그 외                     → 특약점외
     * </pre>
     * 거래처코드가 숫자 5자리라는 전제의 규칙이라, 형식이 다르면 '특약점외'로 떨어진다
     * (실데이터는 '00001'~'90001' 형식이다).
     */
    /**
     * 응시현황 <b>지역비율(보급률)</b> — 레거시 '지역비율' 라디오(F-22·F-23).
     *
     * <p>식은 레거시 그대로다({@code 응시현황.vb:495~590}):
     * <pre>
     *   인원 = SUM(clsNSCnt)            ← 학교 재학생 수 (매출 수량이 아니다)
     *   매출 = SUM(bookCntN)            ← 판매 **부수** (금액 줄은 주석으로 막혀 있다)
     *   비율 = 매출 ÷ 인원 × 100        ← 보급률 %, 인원 0이면 빈 칸
     * </pre>
     *
     * <p>★<b>레거시의 버그는 옮기지 않는다.</b> 레거시 SELECT 는
     * {@code 인원_1학년 AS '인원[2학년]'}·{@code 인원_1학년 AS '인원[3학년]'} 로
     * <b>1학년 값을 2·3학년 칸에 넣는다</b>(:500~502). 같은 쿼리 안쪽은 2·3학년을 제대로
     * 집계하는데 바깥 SELECT 에서만 잘못 복사한 것이라 명백한 오타다 —
     * 우리는 각 학년 값을 쓴다. ‼️레거시 화면과 2·3학년 인원이 다르게 보이는 것은 이 때문이다.
     *
     * <p>★인원은 <b>재학생 자료가 있는 학교만</b> 센다. 자료 없는 학교를 0명으로 세우면
     * 분모만 커져 보급률이 작아진다 — 자료 없음과 0명은 다르다.
     */
    public AttendanceRatioResponse regionRatio(int year,
                                               com.daesung.sales.school.entity.SchoolType schoolType,
                                               String clientCategory) {
        return regionRatio(year, schoolType, clientCategory, null);
    }

    /**
     * 지역비율 + <b>「구분」(상품 type)</b> 조건(프론트 요청 2026-11-20 §1-4).
     *
     * <p>레거시 {@code ComboBox_구분} 은 <b>[전체] · [미분류] + bookData.type 값</b>이다.
     * 그래서 {@code productType} 이 {@code (미분류)} 면 <b>구분이 비어 있는 상품</b>을 고른다 —
     * 연도별({@code attendanceYearly})과 같은 규칙이다. 규칙이 갈리면 같은 콤보를 쓰는
     * 두 화면이 다른 답을 낸다.
     */
    public AttendanceRatioResponse regionRatio(int year,
                                               com.daesung.sales.school.entity.SchoolType schoolType,
                                               String clientCategory, String productType) {
        // ‼️학교/학원 판정은 **isSchool** 이다 — 레거시 응시현황 세 화면 모두
        //   `AllSch.isSchool = 'Y'` / `<> 'Y'` 로 거른다. schoolType 으로 거르면
        //   **실측 106곳이 어긋난다**(isSchool=false 인데 schoolType=SCHOOL 인 행).
        //   파라미터 이름은 프론트가 이미 쓰고 있어 그대로 두고 판정만 레거시에 맞췄다.
        Boolean isSchool = (schoolType == null)
                ? null : schoolType == com.daesung.sales.school.entity.SchoolType.SCHOOL;
        List<SchoolStudentAgg> schools = schoolRepository.studentCounts(isSchool, clientCategory);
        Map<String, long[]> bookBySchool = new java.util.HashMap<>();
        for (AttendanceRatioAgg a : saleRepository.ratioBookCounts(
                LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31),
                blankToNull(productType))) {
            int idx = gradeIndex(a.getGrade());
            if (idx < 0) {
                continue;   // 학년이 없는 도서(공통·자재)는 학년 분해에서 빠진다 — 레거시도 같다
            }
            bookBySchool.computeIfAbsent(a.getSchoolCode(), k -> new long[3])[idx] +=
                    (a.getQty() == null) ? 0L : a.getQty();
        }

        Map<String, RatioAcc> byRegion = new LinkedHashMap<>();
        // ‼️**학교코드가 거래처별로 여러 행인 경우가 있다**(실측 96곳 — `schools` 는 거래처×학교다).
        //   재학생 수는 **학교의 속성**이라 그대로 더하면 그 학교 인원이 두 번 세어져
        //   보급률이 작아진다. 레거시 `schData` 는 학교당 1행이어서 이 문제가 없었다.
        //   → 학교코드당 첫 행만 쓴다(쿼리가 schoolCode, custCode 로 정렬돼 결정적이다).
        java.util.Set<String> seenSchools = new java.util.HashSet<>();
        for (SchoolStudentAgg sc : schools) {
            if (!seenSchools.add(sc.getSchoolCode())) {
                continue;
            }
            String key = regionGroup(sc.getCustCode(), sc.getCity());
            RatioAcc acc = byRegion.computeIfAbsent(key, k -> new RatioAcc(k, sc.getCity()));
            acc.addStudents(nz(sc.getCls1StudentCnt()), nz(sc.getCls2StudentCnt()),
                    nz(sc.getCls3StudentCnt()));
            long[] books = bookBySchool.get(sc.getSchoolCode());
            if (books != null) {
                acc.addBooks(books[0], books[1], books[2]);
            }
        }

        List<RatioAcc> sorted = new ArrayList<>(byRegion.values());
        sorted.sort((a, b) -> a.regionGroup.compareTo(b.regionGroup));
        List<AttendanceRatioResponse.Row> rows = new ArrayList<>();
        RatioAcc total = new RatioAcc(null, null);
        for (RatioAcc a : sorted) {
            rows.add(a.toRow("REGION"));
            total.merge(a);
        }
        rows.add(total.toRow("TOTAL"));
        return new AttendanceRatioResponse(year, rows);
    }

    /** 도서 학년 → 0·1·2. '1'·'2'·'3' 이 아니면 −1(학년 분해 제외). */
    private static int gradeIndex(String grade) {
        if (grade == null) {
            return -1;
        }
        return switch (grade.trim()) {
            case "1" -> 0;
            case "2" -> 1;
            case "3" -> 2;
            default -> -1;
        };
    }

    private static long nz(Integer v) {
        return (v == null) ? 0L : v;
    }

    /** 지역 한 묶음의 인원·부수 누적. */
    private static final class RatioAcc {
        private final String regionGroup;
        private String city;
        private final long[] students = new long[3];
        private final long[] books = new long[3];
        private int schoolCount;

        private RatioAcc(String regionGroup, String city) {
            this.regionGroup = regionGroup;
            this.city = city;
        }

        private void addStudents(long s1, long s2, long s3) {
            students[0] += s1;
            students[1] += s2;
            students[2] += s3;
            schoolCount++;
        }

        private void addBooks(long b1, long b2, long b3) {
            books[0] += b1;
            books[1] += b2;
            books[2] += b3;
        }

        private void merge(RatioAcc o) {
            for (int i = 0; i < 3; i++) {
                students[i] += o.students[i];
                books[i] += o.books[i];
            }
            schoolCount += o.schoolCount;
        }

        /**
         * 지역 표시는 레거시 규칙 그대로 —
         * 합계행은 '합계', {@code 특약점외} 는 '기타', 그 밖은 그 지역의 도시명.
         */
        private String label(String rowType) {
            if ("TOTAL".equals(rowType)) {
                return "합계";
            }
            if ("특약점외".equals(regionGroup)) {
                return "기타";
            }
            return city;
        }

        private AttendanceRatioResponse.Row toRow(String rowType) {
            long st = students[0] + students[1] + students[2];
            long bk = books[0] + books[1] + books[2];
            return new AttendanceRatioResponse.Row(rowType, label(rowType), regionGroup,
                    students[0], students[1], students[2], st,
                    books[0], books[1], books[2], bk,
                    ratio(books[0], students[0]), ratio(books[1], students[1]),
                    ratio(books[2], students[2]), ratio(bk, st),
                    schoolCount);
        }

        /** 보급률 % — 소수 2자리. ‼️인원 0이면 <b>null</b>(레거시도 빈 칸으로 둔다). */
        private static Double ratio(long books, long students) {
            if (students == 0) {
                return null;
            }
            return Math.round((double) books / students * 10_000.0) / 100.0;
        }
    }

    private static String regionGroup(String custCode, String city) {
        String c = (custCode == null) ? "" : custCode;
        String cityPart = (city == null) ? "" : city;
        if (c.startsWith("2") && c.length() >= 2) {
            return "특약점_" + c.substring(0, 2) + cityPart;
        }
        if (!c.isEmpty() && c.compareTo("90001") < 0) {
            return "특약점_" + c.charAt(0) + cityPart;
        }
        return "특약점외";
    }

    /**
     * 응시현황(기간별)의 <b>지역</b> — 레거시는 <b>거래처코드 앞자리</b>로 정한다.
     *
     * <p>근거: {@code 고사별처리인원.vb:257~270} 의 CASE 그대로.
     * <pre>
     *   A10 서울 · A12 광역 · A14 경기 · A16 강원 · A18 충북 · A20 충남
     *   A22 전북 · A24 전남 · A26 경북 · A28 경남 · A30C 제주 · 그 외 '기타'
     * </pre>
     *
     * <p>‼️예전엔 <b>거래처 마스터의 지역 칸</b>({@code partners.region})을 썼다. 그 칸이
     * <b>909곳 중 382곳이 비어</b> 있어 학교 줄 260개의 지역이 빈칸으로 나왔다
     * (프론트 실측 2026-11-10 §2). 레거시는 코드로 정하고 안 맞으면 <b>'기타'</b> 로 묶어
     * <b>빈칸이 없다</b> — 그래서 레거시 규칙으로 통일한다.
     *
     * <p>★마스터 칸을 채우는 쪽(담당자 입력)으로 풀지 않은 이유: 382곳을 사람이 채워야 하고,
     * 그 뒤에도 새 거래처가 생기면 또 빈다. 코드 규칙은 입력이 필요 없다.
     */
    private static String regionOfPartnerCode(String code) {
        String c = (code == null) ? "" : code.trim();
        if (c.length() >= 4 && "A30C".equals(c.substring(0, 4))) {
            return "제주";
        }
        if (c.length() < 3) {
            return "기타";
        }
        return switch (c.substring(0, 3)) {
            case "A10" -> "서울";
            case "A12" -> "광역";
            case "A14" -> "경기";
            case "A16" -> "강원";
            case "A18" -> "충북";
            case "A20" -> "충남";
            case "A22" -> "전북";
            case "A24" -> "전남";
            case "A26" -> "경북";
            case "A28" -> "경남";
            default -> "기타";
        };
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    /** 12개월 누적 통. 소계·총계도 같은 통을 재사용해 합산 규칙이 갈리지 않게 한다. */
    private static final class Bucket {
        private final String regionGroup;
        private final String partnerCode;
        private final String partnerName;
        private final String cityName;
        private final String partnerLoc;
        private String schoolCode;
        private String schoolName;
        private Integer seq;
        private Boolean isSchool;
        private long schoolCount;
        private long studentCnt;
        private final long[] qty = new long[MONTHS];
        private final long[] amount = new long[MONTHS];

        private Bucket(String regionGroup, String partnerCode, String partnerName,
                       String cityName, String partnerLoc) {
            this.regionGroup = regionGroup;
            this.partnerCode = partnerCode;
            this.partnerName = partnerName;
            this.cityName = cityName;
            this.partnerLoc = partnerLoc;
        }

        private void add(Bucket o) {
            for (int i = 0; i < MONTHS; i++) {
                qty[i] += o.qty[i];
                amount[i] += o.amount[i];
            }
            schoolCount += o.schoolCount;
            studentCnt += o.studentCnt;
        }

        private AttendanceResponse.Row toRow(String rowType) {
            List<Long> q = new ArrayList<>(MONTHS);
            List<Long> a = new ArrayList<>(MONTHS);
            long tq = 0;
            long ta = 0;
            for (int i = 0; i < MONTHS; i++) {
                q.add(qty[i]);
                a.add(amount[i]);
                tq += qty[i];
                ta += amount[i];
            }
            return new AttendanceResponse.Row(rowType, regionGroup, partnerCode, partnerName,
                    cityName, partnerLoc, schoolCode, schoolName, seq, isSchool,
                    schoolCount, studentCnt, q, a, tq, ta);
        }
    }
}
