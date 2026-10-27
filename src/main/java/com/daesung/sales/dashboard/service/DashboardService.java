package com.daesung.sales.dashboard.service;

import com.daesung.sales.dashboard.dto.DashboardOverviewResponse;
import com.daesung.sales.product.entity.MajorCategory;
import com.daesung.sales.dashboard.dto.DashboardResponse;
import com.daesung.sales.dashboard.dto.TargetRequest;
import com.daesung.sales.dashboard.dto.TargetResponse;
import com.daesung.sales.dashboard.entity.DashboardSnapshot;
import com.daesung.sales.dashboard.entity.SalesTarget;
import com.daesung.sales.dashboard.entity.TargetEntryType;
import com.daesung.sales.dashboard.entity.TargetScope;
import com.daesung.sales.dashboard.repository.DashboardSnapshotRepository;
import com.daesung.sales.dashboard.repository.SalesTargetRepository;
import com.daesung.sales.sale.repository.SaleRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매출 대시보드. 목표 등록(upsert) + 목표 대비 실적·달성률·전년비 집계. */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final SalesTargetRepository targetRepository;
    private final SaleRepository saleRepository;
    private final DashboardSnapshotRepository snapshotRepository;
    private final com.daesung.sales.inventory.repository.InventoryRepository inventoryRepository;

    /** 매출목표 등록/수정(같은 연·월·대상·종류면 금액 갱신). */
    @Transactional
    public void upsertTarget(TargetRequest req) {
        TargetScope scope = req.scopeOrDefault();
        TargetEntryType type = req.entryTypeOrDefault();
        String scopeKey = (scope == TargetScope.DIVISION) ? req.scopeKey() : null;
        Long productId = (scope == TargetScope.PRODUCT) ? req.productId() : null;

        targetRepository.find(req.year(), req.month(), scope, scopeKey, productId, type)
                .ifPresentOrElse(
                        t -> t.updateAmount(req.targetAmount()),
                        () -> targetRepository.save(SalesTarget.create(
                                req.year(), req.month(), scope, scopeKey, productId,
                                type, req.targetAmount())));
    }

    /** 연도의 목표 목록(관리 화면). */
    @Transactional(readOnly = true)
    public List<TargetResponse> listTargets(int year) {
        return targetRepository.findByFiscalYearOrderByScopeAscScopeKeyAscMonthAsc(year).stream()
                .map(TargetResponse::from).toList();
    }

    /**
     * 목표 대비 실적 대시보드(월별 + 연간). 실적=순매출(매출−반품), 전년 동기 대비.
     *
     * <p>월 셀에는 당월값과 <b>누적값(1월~해당월)</b>을 함께 담는다(요구 20p 매출 상세 대시보드).
     * 연간 목표만 등록된 경우 월 목표가 0이므로 누적목표도 0이다 —
     * 연간 금액을 12로 나눠 뿌리지 않는다는 원칙과 같은 이유다.
     *
     * <p>연간 목표만 등록된 경우(발주처는 연 1회 연간으로 준다) 월 셀 목표는 비고
     * <b>연간 요약에만</b> 그 값이 잡힌다 — 연간 금액을 12로 나눠 월에 뿌리면 있지도 않은
     * 월 목표를 만들어내는 셈이라, 달성률이 실제와 다르게 보인다.
     *
     * <p>전년 실적은 우리 매출에서 계산하되, 그 연도 매출이 아예 없으면(이관하지 않아 비어 있는
     * 과거연도) 저장된 ACTUAL 값으로 채운다.
     */
    @Transactional(readOnly = true)
    public DashboardResponse salesDashboard(int year, Integer compareYear,
                                            TargetScope scope, String scopeKey, Long productId) {
        // 비교연도는 고를 수 있다(정본 20p 데이터 항목 "기준/비교연도"). 미지정이면 전년.
        int cmpYear = (compareYear != null) ? compareYear : year - 1;
        // scope 미지정이면 넘어온 값에서 추론한다 — 기존처럼 productId만 넘기던 호출이
        // 조용히 전사 대시보드로 바뀌면 안 된다.
        TargetScope sc = scope;
        if (sc == null) {
            sc = (productId != null) ? TargetScope.PRODUCT
                    : (scopeKey != null && !scopeKey.isBlank()) ? TargetScope.DIVISION : TargetScope.COMPANY;
        }
        String key = (sc == TargetScope.DIVISION) ? scopeKey : null;
        Long pid = (sc == TargetScope.PRODUCT) ? productId : null;

        Map<Integer, Long> monthTargets = new HashMap<>();
        Long annualTarget = null;
        for (SalesTarget t : targetRepository.findByYearAndScope(year, sc, key, pid)) {
            if (t.getEntryType() != TargetEntryType.TARGET) {
                continue;
            }
            if (t.isAnnual()) {
                annualTarget = t.getTargetAmount();
            } else {
                monthTargets.put(t.getMonth(), t.getTargetAmount());
            }
        }

        Map<Integer, Long> actual = monthlyMap(year, sc, pid);
        Map<Integer, Long> prev = monthlyMap(cmpYear, sc, pid);

        List<DashboardResponse.MonthCell> months = new ArrayList<>();
        long tTarget = 0;
        long tActual = 0;
        long tPrev = 0;
        for (int m = 1; m <= 12; m++) {
            long target = monthTargets.getOrDefault(m, 0L);
            long act = actual.getOrDefault(m, 0L);
            long pv = prev.getOrDefault(m, 0L);
            // 누적은 1월부터 해당 월까지의 합이다. 합계 변수를 먼저 더한 뒤 담아야
            // '해당 월 포함' 누적이 된다(먼저 담으면 전월까지가 되어 한 달씩 밀린다).
            tTarget += target;
            tActual += act;
            tPrev += pv;
            // 정본 20p "미도래 월은 실적 컬럼 공백 처리" — 0으로 채우면 차트가 연말까지
            // 바닥으로 곤두박질친 것처럼 보인다. 단 미래 달이라도 실적이 기록돼 있으면 그 값을 준다
            // (있는 숫자를 숨기지는 않는다).
            // 정본 20p "미도래 월은 실적 컬럼 공백 처리" — 0으로 채우면 막대차트가
            // 연말까지 바닥으로 곤두박질친 것처럼 보인다. 미래 달이라도 실적이 기록돼 있으면 그 값을 준다.
            // 'ㅤ누적은 비우지 않는다 — 누적은 지금까지의 합이라 미래 칸에서도 유지되는 게 맞고,
            //   비우면 선그래프가 끊긴다. 달성률·성장률은 당월 값이 없으면 계산이 성립하지 않아 함께 비운다.
            boolean pending = isFutureMonth(year, m) && act == 0;
            months.add(new DashboardResponse.MonthCell(m, target, tTarget,
                    pending ? null : act, tActual,
                    pending ? null : pct(act, target), pv,
                    pending ? null : growth(act, pv)));
        }

        // 연간 목표가 등록돼 있으면 월 합계보다 그 값이 우선이다(월별을 안 넣고 연간만 넣는 운영).
        long yearTarget = (annualTarget != null) ? annualTarget : tTarget;
        // 전년 매출이 통째로 비어 있으면 저장해둔 확정 실적으로 대체.
        long yearPrev = (tPrev > 0) ? tPrev : storedActual(cmpYear, sc, key, pid);

        DashboardResponse.YearSummary summary = new DashboardResponse.YearSummary(
                yearTarget, tActual, pct(tActual, yearTarget), yearPrev, growth(tActual, yearPrev));
        return new DashboardResponse(year, cmpYear, sc, key, pid, computedAt(year, sc), months, summary);
    }

    /**
     * 기본 통계 대시보드(19p). KPI + 제품별 목표대비 + 월 누적트렌드 + 거래처 비중 + 순매출 TOP5.
     *
     * <p>★순매출은 20p와 <b>같은 원천</b>을 쓴다(정본 19p가 "원천데이터 공유"를 조건으로 달았다).
     * 월별 트렌드는 20p가 쓰는 {@link #monthlyMap}을 그대로 재사용하고,
     * 축별 집계는 같은 식으로 만든 단일 쿼리 하나만 쓴다. 식이 갈리면 두 화면 숫자가 달라진다.
     *
     * @param month 기준 월(1~12). 당월 KPI와 누적 범위를 정한다.
     */
    @Transactional(readOnly = true)
    public DashboardOverviewResponse overview(int year, int month) {
        return overview(year, month, null);
    }

    /**
     * 분기 단위 조회 포함(발주처 질의 2026-10-26 ③). quarter(1~4)를 주면 KPI의
     * "당월" 자리가 <b>그 분기 3개월 합</b>으로 바뀐다 — 필드명은 그대로 두되(응답 스키마를
     * 깨지 않으려고) 응답의 {@code quarter}에 조회 단위를 실어 화면이 "월/분기 중 뭘 보고
     * 있는지" 구분할 수 있게 한다.
     *
     * <p>★<b>quarter가 있으면 month는 분기의 마지막 달로 강제된다</b>(1분기→3, 2분기→6 …).
     * 누적(1월~기준월)·트렌드는 원래 로직 그대로 — 분기든 월이든 "그 달까지 누적"은 같은 의미다.
     * 바뀌는 건 "당월" 한 칸이 1개월치냐 3개월치냐 뿐이다.
     *
     * @param quarter 1~4. null이면 기존과 동일(단일 월).
     */
    @Transactional(readOnly = true)
    public DashboardOverviewResponse overview(int year, int month, Integer quarter) {
        // 분기 모드면 그 분기의 시작월로 "당월" 합산 범위를 넓힌다. 끝은 항상 조회월(=분기 마지막달)이다.
        int periodStartMonth = (quarter != null) ? quarter * 3 - 2 : month;

        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate monthStart = LocalDate.of(year, periodStartMonth, 1);
        LocalDate monthEnd = LocalDate.of(year, month, 1);
        monthEnd = monthEnd.withDayOfMonth(monthEnd.lengthOfMonth());

        // 월별 트렌드 — 20p가 쓰는 그 맵을 그대로 쓴다(스냅샷/실시간 폴백까지 공유).
        Map<Integer, Long> monthly = monthlyMap(year, TargetScope.COMPANY, null);
        List<DashboardOverviewResponse.TrendPoint> trend = new ArrayList<>();
        long running = 0;
        long cumulativeToMonth = 0;
        for (int m = 1; m <= 12; m++) {
            long v = monthly.getOrDefault(m, 0L);
            running += v;
            trend.add(new DashboardOverviewResponse.TrendPoint(m, v, running));
            if (m <= month) {
                cumulativeToMonth = running;
            }
        }
        // 분기면 시작월~조회월 합, 월이면 그 한 달뿐 — periodStartMonth==month일 때 결과가 같다.
        long monthNet = 0;
        for (int m = periodStartMonth; m <= month; m++) {
            monthNet += monthly.getOrDefault(m, 0L);
        }

        // 누적(1월~기준월)을 한 번만 읽고 축별로 접는다 — 순매출 식이 한 벌뿐이라는 뜻이다.
        List<Object[]> breakdown = saleRepository.netSalesBreakdown(yearStart, monthEnd);
        List<DashboardOverviewResponse.Share> products = fold(breakdown, 0, 1);
        List<DashboardOverviewResponse.Share> partners = fold(breakdown, 2, 3);
        List<DashboardOverviewResponse.Share> majors = fold(breakdown, 4, 4);

        // 점유율 1위 상품군 — 이름을 코드에 박지 않는다(정본 예시는 '더프리미엄'이지만
        // 그 상품군이 1위가 아니게 된 순간 카드가 거짓말을 한다).
        DashboardOverviewResponse.Share top = majors.stream()
                .filter(x -> x.netSales() > 0).findFirst().orElse(null);

        // 특약점 당월(분기) 순매출 — 거래처구분 축에서 뽑는다. 범위는 위 monthNet과 같은 기간.
        long dealerMonth = saleRepository.netSalesBreakdown(monthStart, monthEnd).stream()
                .filter(r -> DEALER.equals(str(r[5])))
                .mapToLong(r -> num(r[6])).sum();

        DashboardOverviewResponse.Kpi kpi = new DashboardOverviewResponse.Kpi(
                monthNet, cumulativeToMonth,
                (top == null) ? 0L : top.netSales(),
                (top == null) ? null : majorLabel(top.name()),
                (top == null) ? null : top.sharePct(),
                dealerMonth);

        // 출고유형·지역은 같은 breakdown에서 접는다(축마다 쿼리를 새로 파지 않는다).
        List<DashboardOverviewResponse.Share> shipTypes = fold(breakdown, 7, 7).stream()
                .map(x -> new DashboardOverviewResponse.Share(
                        x.key(), shipTypeLabel(x.key()), x.netSales(), x.sharePct()))
                .toList();
        List<DashboardOverviewResponse.Share> regions = fold(breakdown, 8, 8);
        // 세부구분(구 매출구분) 축 — 발주처 회신 2026-08-20 "집계는 대분류, 세부구분으로 드릴다운".
        // 제품별 차트를 이 값으로 묶어 달라는 요청(2026-09-17 피드백 화면11-3).
        List<DashboardOverviewResponse.Share> divisions = fold(breakdown, 9, 9);

        return new DashboardOverviewResponse(year, month, quarter, kpi,
                productTargets(year, products), trend,
                partners, products.stream().limit(5).toList(),
                shipTypes, regions, warehouseStocks(), divisions);
    }

    /** 거래처구분 '특약점'. 정본 19p KPI "특약점 당월매출". */
    private static final String DEALER = "특약점";

    /** 제품별 목표대비 — 목표가 등록된 상품만. 목표가 없는 상품까지 0으로 깔면 차트가 의미를 잃는다. */
    private List<DashboardOverviewResponse.ProductTarget> productTargets(
            int year, List<DashboardOverviewResponse.Share> productActuals) {
        Map<String, Long> actualById = new HashMap<>();
        Map<String, String> nameById = new HashMap<>();
        for (DashboardOverviewResponse.Share p : productActuals) {
            actualById.put(p.key(), p.netSales());
            nameById.put(p.key(), p.name());
        }

        Map<Long, long[]> byProduct = new LinkedHashMap<>();   // [월목표합, 연간목표]
        for (SalesTarget t : targetRepository.findByFiscalYearAndScope(year, TargetScope.PRODUCT)) {
            if (t.getEntryType() != TargetEntryType.TARGET || t.getProductId() == null) {
                continue;
            }
            long[] acc = byProduct.computeIfAbsent(t.getProductId(), k -> new long[2]);
            if (t.isAnnual()) {
                acc[1] = t.getTargetAmount();
            } else {
                acc[0] += t.getTargetAmount();
            }
        }

        List<DashboardOverviewResponse.ProductTarget> out = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : byProduct.entrySet()) {
            String key = String.valueOf(e.getKey());
            // 연간 목표가 있으면 그 값이 우선(20p 요약과 같은 규칙).
            long target = (e.getValue()[1] > 0) ? e.getValue()[1] : e.getValue()[0];
            long actual = actualById.getOrDefault(key, 0L);
            out.add(new DashboardOverviewResponse.ProductTarget(
                    e.getKey(), nameById.get(key), target, actual, pct(actual, target)));
        }
        return out;
    }

    /**
     * 잘게 집계된 행을 한 축으로 접고 비중을 매긴다. 큰 순으로 정렬한다(도넛·TOP5가 같은 순서를 쓴다).
     *
     * <p>비중의 분모는 <b>양수 합</b>이다 — 반품이 많아 순매출이 음수인 항목까지 분모에 넣으면
     * 전체가 줄어 나머지 비중이 부풀려진다. 전체가 0이면 비중은 null(0%로 두면 "비중 없음"으로 오해된다).
     *
     * @param keyIdx  키로 쓸 컬럼 위치
     * @param nameIdx 표시명으로 쓸 컬럼 위치(대분류처럼 키가 곧 이름이면 같은 값)
     */
    private static List<DashboardOverviewResponse.Share> fold(List<Object[]> rows, int keyIdx, int nameIdx) {
        Map<String, long[]> sums = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Object[] r : rows) {
            String key = str(r[keyIdx]);
            if (key == null || key.isEmpty()) {
                continue;   // 대분류·거래처구분이 비어 있는 행은 축에 올릴 이름이 없다
            }
            sums.computeIfAbsent(key, k -> new long[1])[0] += num(r[6]);
            names.putIfAbsent(key, str(r[nameIdx]));
        }
        long total = sums.values().stream().mapToLong(v -> v[0]).filter(v -> v > 0).sum();
        List<DashboardOverviewResponse.Share> out = new ArrayList<>();
        sums.forEach((k, v) -> {
            Double share = (total == 0) ? null : Math.round((double) v[0] / total * 100 * 10) / 10.0;
            out.add(new DashboardOverviewResponse.Share(k, names.get(k), v[0], share));
        });
        out.sort((a, b) -> Long.compare(b.netSales(), a.netSales()));
        return out;
    }

    /** 대분류 코드 → 한글명. 매핑에 없으면 원값 그대로(미분류 등). */
    private static String majorLabel(String code) {
        for (MajorCategory c : MajorCategory.values()) {
            if (c.name().equals(code)) {
                return c.label();
            }
        }
        return code;
    }

    private static long num(Object o) {
        return (o == null) ? 0L : ((Number) o).longValue();
    }

    private static String str(Object o) {
        return (o == null) ? null : o.toString();
    }

    /** 아직 오지 않은 달인가(오늘 기준). 이번 달은 진행 중이라 '도래'로 본다. */
    private static boolean isFutureMonth(int year, int month) {
        LocalDate now = LocalDate.now();
        return year > now.getYear() || (year == now.getYear() && month > now.getMonthValue());
    }

    /** 저장된 확정 실적(ACTUAL) 합. 이관하지 않은 과거연도의 전년비를 살리는 용도. */
    private long storedActual(int year, TargetScope scope, String scopeKey, Long productId) {
        return targetRepository.findByYearAndScope(year, scope, scopeKey, productId).stream()
                .filter(t -> t.getEntryType() == TargetEntryType.ACTUAL)
                .mapToLong(SalesTarget::getTargetAmount)
                .sum();
    }

    /**
     * 월별 순매출. <b>스냅샷이 있으면 그것을, 없으면 실시간 계산</b>한다.
     *
     * <p>발주처 확정(3-2 아)대로 새벽 배치가 미리 계산해 두지만, 배치가 안 돌았거나 실패한 날
     * 화면이 통째로 비면 "느린 것"보다 나쁘다. 그래서 폴백을 둔다.
     * 대신 {@link #computedAt}로 언제 기준 숫자인지 화면에 드러낸다.
     */
    private Map<Integer, Long> monthlyMap(int year, TargetScope scope, Long productId) {
        if (scope == TargetScope.COMPANY) {
            List<DashboardSnapshot> snaps =
                    snapshotRepository.findByYearAndScope(year, TargetScope.COMPANY, null);
            if (!snaps.isEmpty()) {
                Map<Integer, Long> map = new HashMap<>();
                snaps.forEach(s -> map.put(s.getMonth(), s.getNetSales()));
                return map;
            }
        }
        return realtimeMonthlyMap(year, productId);
    }

    private Map<Integer, Long> realtimeMonthlyMap(int year, Long productId) {
        Map<Integer, Long> map = new HashMap<>();
        for (Object[] r : saleRepository.monthlyNetSales(year, productId)) {
            map.put(((Number) r[0]).intValue(), ((Number) r[1]).longValue());
        }
        return map;
    }

    /** 스냅샷 기준시각(가장 최근). 스냅샷을 안 썼으면 null — 화면이 "실시간"임을 알 수 있다. */
    private LocalDateTime computedAt(int year, TargetScope scope) {
        if (scope != TargetScope.COMPANY) {
            return null;
        }
        return snapshotRepository.findByYearAndScope(year, TargetScope.COMPANY, null).stream()
                .map(DashboardSnapshot::getComputedAt)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    /** 달성률 = 실적/목표 ×100(소수1). 목표 0이면 null. */
    private static Double pct(long actual, long target) {
        return (target == 0) ? null : Math.round((double) actual / target * 100 * 10) / 10.0;
    }

    /** 전년비 성장률 = (실적−전년)/전년 ×100(소수1). 전년 0이면 null. */
    private static Double growth(long actual, long prev) {
        return (prev == 0) ? null : Math.round((double) (actual - prev) / prev * 100 * 10) / 10.0;
    }

    /** 출고유형 enum 이름 → 한글 표기. 화면이 라벨을 다시 만들지 않게 서버가 붙여 준다. */
    private static String shipTypeLabel(String code) {
        return switch (code) {
            case "NORMAL_SHIP" -> "정상출고";
            case "CONSIGN_SHIP" -> "위탁출고";
            case "GIFT" -> "증정용";
            case "TEACHER_USE" -> "교사용";
            case "RETURN" -> "반품";
            case "CANCEL" -> "취소";
            default -> code;
        };
    }

    /** 창고별 재고 수량. 사용 중인 창고만(쓰지 않는 창고가 도넛에 남으면 물건이 있는 줄 안다). */
    private List<DashboardOverviewResponse.WarehouseStock> warehouseStocks() {
        List<DashboardOverviewResponse.WarehouseStock> out = new ArrayList<>();
        for (Object[] r : inventoryRepository.stockQtyByWarehouse()) {
            out.add(new DashboardOverviewResponse.WarehouseStock(
                    num(r[0]), str(r[1]), String.valueOf(r[2]), num(r[3])));
        }
        return out;
    }
}
