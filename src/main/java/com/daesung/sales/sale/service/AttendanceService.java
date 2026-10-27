package com.daesung.sales.sale.service;

import com.daesung.sales.sale.dto.AttendanceAgg;
import com.daesung.sales.sale.dto.AttendancePeriodAgg;
import com.daesung.sales.sale.dto.AttendancePeriodResponse;
import com.daesung.sales.sale.dto.AttendanceResponse;
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

    public AttendanceResponse yearly(int year, String grade, String productType) {
        return yearly(year, grade, productType, null);
    }

    /**
     * 응시현황(연도별) — <b>거래처 검색(keyword)</b> 포함.
     * 근거: 발주처 질의 2026-10-26 ②-① "거래처 검색(keyword)" 요청.
     * 거래처코드·거래처명 부분일치다(통합매출조회 등 다른 화면의 keyword 검색과 같은 규칙).
     */
    public AttendanceResponse yearly(int year, String grade, String productType, String keyword) {
        // 거래처별로 12개월 칸을 채운다(빈 달은 0).
        Map<String, Bucket> byPartner = new LinkedHashMap<>();
        for (AttendanceAgg a : saleRepository.attendanceYearly(year, blankToNull(grade),
                blankToNull(productType), blankToNull(keyword))) {
            Bucket b = byPartner.computeIfAbsent(a.getPartnerCode(),
                    k -> new Bucket(regionGroup(a.getPartnerCode(), a.getCityName()),
                            a.getPartnerCode(), a.getPartnerName(), a.getCityName()));
            int idx = a.getMonth() - 1;
            b.qty[idx] += (a.getQty() == null) ? 0 : a.getQty();
            b.amount[idx] += (a.getAmount() == null) ? 0 : a.getAmount();
        }

        // 지역구분 → 거래처 순서로 묶어 소계·총계를 만든다(레거시 rollup 순서와 같다).
        Map<String, List<Bucket>> byRegion = new LinkedHashMap<>();
        byPartner.values().stream()
                .sorted((x, y) -> {
                    int c = x.regionGroup.compareTo(y.regionGroup);
                    return (c != 0) ? c : x.partnerCode.compareTo(y.partnerCode);
                })
                .forEach(b -> byRegion.computeIfAbsent(b.regionGroup, k -> new ArrayList<>()).add(b));

        List<AttendanceResponse.Row> rows = new ArrayList<>();
        Bucket grand = new Bucket("", null, null, null);
        for (Map.Entry<String, List<Bucket>> e : byRegion.entrySet()) {
            Bucket sub = new Bucket(e.getKey(), null, null, null);
            for (Bucket b : e.getValue()) {
                rows.add(b.toRow("PARTNER"));
                sub.add(b);
                grand.add(b);
            }
            rows.add(sub.toRow("REGION_SUBTOTAL"));
        }
        rows.add(grand.toRow("TOTAL"));
        return new AttendanceResponse(year, rows);
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
        return period(fromDate, toDate, grade, null, partnerId, null);
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
        List<String> gradeFilter = com.daesung.sales.common.query.MultiSelect.merge(
                blankToNull(grade), grades);
        String unit = normalizeGroupBy(groupBy);
        return periodInternal(fromDate, toDate, gradeFilter, partnerId, unit);
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
                                                    String unit) {
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
                partnerId)) {
            String region = nz(a.getRegion());
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

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    /** 12개월 누적 통. 소계·총계도 같은 통을 재사용해 합산 규칙이 갈리지 않게 한다. */
    private static final class Bucket {
        private final String regionGroup;
        private final String partnerCode;
        private final String partnerName;
        private final String cityName;
        private final long[] qty = new long[MONTHS];
        private final long[] amount = new long[MONTHS];

        private Bucket(String regionGroup, String partnerCode, String partnerName, String cityName) {
            this.regionGroup = regionGroup;
            this.partnerCode = partnerCode;
            this.partnerName = partnerName;
            this.cityName = cityName;
        }

        private void add(Bucket o) {
            for (int i = 0; i < MONTHS; i++) {
                qty[i] += o.qty[i];
                amount[i] += o.amount[i];
            }
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
                    cityName, q, a, tq, ta);
        }
    }
}
