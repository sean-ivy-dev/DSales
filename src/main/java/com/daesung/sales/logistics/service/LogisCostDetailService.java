package com.daesung.sales.logistics.service;

import com.daesung.sales.common.audit.CurrentAuditor;
import com.daesung.sales.dsre.gateway.DsreGateway;
import com.daesung.sales.dsre.gateway.LogisCostDetailRow;
import com.daesung.sales.dsre.gateway.LogisMode;
import com.daesung.sales.logistics.dto.LogisCostDetailResponse;
import com.daesung.sales.logistics.entity.LogisCostSnapshot;
import com.daesung.sales.logistics.repository.LogisCostManualRepository;
import com.daesung.sales.logistics.repository.LogisCostSnapshotRepository;
import com.daesung.sales.logistics.dto.LogisCostDetailResponse.Grain;
import com.daesung.sales.logistics.dto.LogisCostDetailResponse.Row;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 물류 작업비 명세(28p) 조립 — 행 + 소계.
 *
 * <p>게이트웨이가 가장 잘게(상품×학년×시행×신청×거래처) 낸 것을 여기서 <b>접는다</b>.
 * 레거시가 체크박스로 신청/거래처 축을 갈아 끼우던 것을 {@link Grain}으로 받는다.
 * 쿼리를 모드마다 두지 않는 이유는 물류비 계산식이 두 벌로 갈라지지 않게 하기 위함이다.
 */
@Service
// DSRE2 단가·자재를 직접 읽는 서비스라 연동이 꺼지면 뜰 수 없다(LogisticsController와 같은 조건).
// 조건을 안 걸면 게이트웨이 빈이 없어 컨텍스트 전체가 깨진다.
@ConditionalOnProperty(name = "daesung.dsre.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LogisCostDetailService {

    private final DsreGateway dsreGateway;
    private final LogisCostSnapshotRepository snapshotRepository;
    private final StandaloneLogisCostService standaloneLogisCostService;
    private final LogisCostManualRepository manualRepository;
    private final CurrentAuditor currentAuditor;

    public LogisCostDetailResponse outboundDetail(LocalDate from, LocalDate to, LogisMode mode,
                                                  java.util.Collection<Integer> dtlCds,
                                                  boolean includeCancel, Grain grain) {
        Grain g = (grain == null) ? Grain.PARTNER : grain;   // 레거시 기본은 '상세보기' 꺼짐

        // 원천: 마감월이면 굳혀 둔 값, 아니면 실시간 계산.
        // 필터·접기는 그 뒤로 <b>완전히 같은 코드</b>를 탄다 — 두 경로가 갈리면
        // 마감 전후로 같은 달 숫자가 달라진다.
        List<LogisCostDetailRow> raw = new ArrayList<>(loadRows(from, to, dtlCds));

        // ★단독 상품(수불부노출 Y)을 더한다 — 발주처 확정 2026-10-23 #10.
        //   '인원' 자리에 **매출프로그램 출고수량**이 들어가고, 계산식·단가는 기존 그대로다.
        //   ‼️**이중계산 방지** — 병행(N) 상품은 위 loadRows 가 DSRE 신청 기준으로 이미 가져온다.
        //     standaloneShippedQty 가 `ledgerVisible = true` 만 세므로 두 집합은 겹치지 않는다.
        //     한 상품이 양쪽에 다 잡히면 금액이 두 배가 된다(발주처가 콕 집어 경고한 지점).
        //   ‼️시행코드(dtlCd) 필터가 걸린 조회에는 더하지 않는다 — 그 필터는 DSRE 시행 축이고
        //     단독 상품에는 시행이 없다. 넣으면 "시행을 골랐는데 상관없는 행이 섞이는" 화면이 된다.
        if (dtlCds == null || dtlCds.isEmpty()) {
            raw.addAll(standaloneLogisCostService.rows(from, to));
        }

        List<LogisCostDetailRow> src = raw.stream().filter(r -> r.matches(mode, includeCancel)).toList();

        // 1) 요청한 축으로 접는다. 신청 축이면 원본 그대로, 거래처 축이면 신청을 합친다.
        Map<String, Acc> folded = new LinkedHashMap<>();
        for (LogisCostDetailRow r : src) {
            String key = r.productCode() + "|" + nz(r.grade()) + "|" + r.dtlCd() + "|"
                    + (g == Grain.REQUEST ? r.reqCd() : nz(r.partnerCode()));
            folded.computeIfAbsent(key, k -> new Acc(r, g)).add(r);
        }

        // 2) 소계를 붙인다(레거시 ROLLUP 순서: 시행 → 학년 → 상품 → 총계).
        List<Row> rows = new ArrayList<>();
        Acc dtlSub = null;
        Acc gradeSub = null;
        Acc prodSub = null;
        Acc grand = Acc.empty();
        String curProd = null;
        String curGrade = null;
        Integer curDtl = null;

        for (Acc a : folded.values()) {
            if (dtlSub != null && !Objects.equals(curDtl, a.dtlCd)) {
                rows.add(dtlSub.toRow("DTL_SUBTOTAL", "시행 계"));
                dtlSub = null;
            }
            if (gradeSub != null && !Objects.equals(curGrade, nz(a.grade))) {
                rows.add(gradeSub.toRow("GRADE_SUBTOTAL", "학년 계"));
                gradeSub = null;
            }
            if (prodSub != null && !Objects.equals(curProd, a.productCode)) {
                rows.add(prodSub.toRow("PRODUCT_SUBTOTAL", "상품 계"));
                prodSub = null;
            }
            if (prodSub == null) {
                curProd = a.productCode;
                prodSub = Acc.empty();
            }
            if (gradeSub == null) {
                curGrade = nz(a.grade);
                gradeSub = Acc.empty();
            }
            if (dtlSub == null) {
                curDtl = a.dtlCd;
                dtlSub = Acc.empty();
            }
            rows.add(a.toRow("DETAIL", null));
            dtlSub.merge(a);
            gradeSub.merge(a);
            prodSub.merge(a);
            grand.merge(a);
        }
        if (dtlSub != null) {
            rows.add(dtlSub.toRow("DTL_SUBTOTAL", "시행 계"));
            rows.add(gradeSub.toRow("GRADE_SUBTOTAL", "학년 계"));
            rows.add(prodSub.toRow("PRODUCT_SUBTOTAL", "상품 계"));
        }
        rows.add(grand.toRow("TOTAL", "총 계"));

        // ★빠진 단독 상품을 사유와 함께 싣는다. 0원 행을 안 내보내는 대신 이걸로 알린다 —
        //   아무 설명 없이 사라지면 담당자는 **빠진 줄이 있다는 것조차 모른다**.
        //   ‼️시행코드 필터가 걸린 조회에는 단독 상품을 안 더하므로(위 참고) 목록도 비운다.
        LogisCostDetailResponse.MissingRate missing = (dtlCds == null || dtlCds.isEmpty())
                ? LogisCostDetailResponse.MissingRate.of(standaloneLogisCostService.missingProducts(from, to))
                : LogisCostDetailResponse.MissingRate.NONE;
        return new LogisCostDetailResponse(from, to, mode.name(), g, rows, grand.totalAmount,
                missing);
    }

    /**
     * 원천 선택 — 조회 기간이 <b>전부 굳어 있는 달</b>이면 저장분, 아니면 실시간.
     *
     * <p>기간이 여러 달에 걸치면 달마다 갈린다. 굳은 달은 저장분, 열린 달은 실시간으로 섞어 낸다 —
     * 그래야 "6월은 확정, 7월은 아직"인 기간 조회가 맞는 숫자를 낸다.
     */
    private List<LogisCostDetailRow> loadRows(LocalDate from, LocalDate to,
                                              java.util.Collection<Integer> dtlCds) {
        List<LogisCostDetailRow> out = new ArrayList<>();
        java.time.YearMonth cur = java.time.YearMonth.from(from);
        java.time.YearMonth end = java.time.YearMonth.from(to);
        while (!cur.isAfter(end)) {
            List<LogisCostSnapshot> snap =
                    snapshotRepository.findByPeriodYearAndPeriodMonth(cur.getYear(), cur.getMonthValue());
            if (!snap.isEmpty()) {
                snap.forEach(x -> out.add(x.toRow()));
            } else {
                // 조회 기간의 양끝은 달 전체가 아닐 수 있어 요청 범위로 잘라 준다.
                LocalDate mf = maxDate(cur.atDay(1), from);
                LocalDate mt = minDate(cur.atEndOfMonth(), to);
                out.addAll(dsreGateway.outboundDetail(mf, mt, dtlCds));
            }
            cur = cur.plusMonths(1);
        }
        // ★마감된 달은 스냅샷에서 오므로 DSRE 쿼리 조건이 안 걸린다.
        //   여기서 한 번 더 거른다 — 안 그러면 **열린 달만 필터가 먹고 마감된 달은 다 나오는**
        //   화면이 된다(같은 조건인데 달마다 결과가 다른 것이 가장 나쁜 종류다).
        if (dtlCds != null && !dtlCds.isEmpty()) {
            out.removeIf(r -> !dtlCds.contains(r.dtlCd()));
        }
        // 수기 등록분(28p 에디팅)을 합친다. 자동계산분과 같은 타입이라 이후 필터·접기·소계가
        // 그대로 잡힌다 — 합산 코드를 따로 두지 않는다("소계/누계/합계/총계 정상 반영" 요구).
        // 스냅샷에 넣지 않는 이유: 수기 행은 우리 DB에 있고, 마감월은 수정이 막혀 있어
        // 그 잠금이 곧 불변성 보장이다(두 벌로 복사해 두면 원본과 어긋날 여지가 생긴다).
        manualRepository.findByReqDateBetween(from, to).forEach(m -> out.add(m.toRow()));
        return out;
    }

    /**
     * 그 달 물류작업비를 굳힌다(월마감 시 호출). 근거: 발주처 회신 "과거 데이터 고정".
     *
     * <p>이미 굳어 있으면 <b>다시 굳히지 않는다</b> — 재마감으로 값이 바뀌면 고정의 뜻이 없다.
     * 다시 계산하려면 마감을 풀어야 하고, 그때 저장분이 지워진다.
     *
     * @return 저장한 행 수
     */
    @Transactional
    public int freeze(int year, int month) {
        if (snapshotRepository.existsByPeriodYearAndPeriodMonth(year, month)) {
            return 0;
        }
        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        List<LogisCostDetailRow> rows = dsreGateway.outboundDetail(ym.atDay(1), ym.atEndOfMonth());
        String actor = currentAuditor.username();
        rows.forEach(r -> snapshotRepository.save(LogisCostSnapshot.of(year, month, r, actor)));
        return rows.size();
    }

    /** 마감 해제 시 저장분을 지운다 — 다시 마감하면 그 시점 단가로 새로 굳는다. */
    @Transactional
    public void unfreeze(int year, int month) {
        snapshotRepository.deleteByPeriodYearAndPeriodMonth(year, month);
    }

    private static LocalDate maxDate(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate minDate(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static String nz(String v) {
        return (v == null) ? "" : v;
    }

    /**
     * 누적 통. 명세행과 소계행이 같은 통을 써서 합산 규칙이 갈리지 않게 한다.
     *
     * <p>⚠️인원은 <b>신청 단위 값</b>이라, 같은 신청이 여러 자재 행으로 쪼개져 오면 중복된다.
     * 게이트웨이가 이미 신청을 GROUP BY에 넣어 한 번만 내주므로 여기서는 그냥 더한다 —
     * 거래처 축으로 접을 때 여러 신청의 인원이 합쳐지는 것은 의도한 동작이다.
     */
    private static final class Acc {
        private LocalDate reqDate;
        private Integer reqCd;
        private String productCode;
        private String productName;
        private String grade;
        private Integer dtlCd;
        private String detailName;
        private String partnerCode;
        private String partnerName;
        private String region;
        private String cityName;
        private String schoolName;
        private long materialQty;
        private long paperQty;
        private long paperAmount;
        private long omrQty;
        private long omrAmount;
        private long etcQty;
        private long etcAmount;
        private int inwon;
        private long basicAmount;
        private long tradeAmount;
        private long totalAmount;

        private Acc(LogisCostDetailRow r, Grain g) {
            this.reqDate = r.reqDate();
            this.reqCd = (g == Grain.REQUEST) ? r.reqCd() : null;
            this.productCode = r.productCode();
            this.productName = r.productName();
            this.grade = r.grade();
            this.dtlCd = r.dtlCd();
            this.detailName = r.detailName();
            this.partnerCode = r.partnerCode();
            this.partnerName = r.partnerName();
            this.region = r.region();
            this.cityName = r.cityName();
            this.schoolName = r.schoolName();
        }

        private Acc() {
        }

        private static Acc empty() {
            return new Acc();
        }

        private void add(LogisCostDetailRow r) {
            materialQty += r.materialQty();
            paperQty += r.paperQty();
            paperAmount += r.paperAmount();
            omrQty += r.omrQty();
            omrAmount += r.omrAmount();
            etcQty += r.etcQty();
            etcAmount += r.etcAmount();
            inwon += r.inwon();
            basicAmount += r.basicAmount();
            tradeAmount += r.tradeAmount();
            totalAmount += r.totalAmount();
            // 접수일자는 **가장 늦은 것**으로 — 거래처 축에서 여러 신청이 합쳐질 때 기준이 필요하다.
            //
            // ‼️레거시와 같은 방향이어야 한다(발주처 검수 F-34). 레거시는
            //   `ELSE DATE_FORMAT(max(REQ_DATE), '%Y.%m.%d')`(물류비계산2.vb:421)로 **max**를 쓴다.
            //   우리가 min(isBefore)을 쓰던 동안 2026-09 대조에서 138행 중 56행이 어긋났고
            //   **56행 전부 우리 쪽이 더 일렀다**(늦은 건 0) — 한 거래처가 한 시행에 신청을
            //   여러 번 낸 행만, 전부 같은 방향으로 어긋난 것이 그 증거다.
            if (reqDate == null || (r.reqDate() != null && r.reqDate().isAfter(reqDate))) {
                reqDate = r.reqDate();
            }
        }

        private void merge(Acc o) {
            materialQty += o.materialQty;
            paperQty += o.paperQty;
            paperAmount += o.paperAmount;
            omrQty += o.omrQty;
            omrAmount += o.omrAmount;
            etcQty += o.etcQty;
            etcAmount += o.etcAmount;
            inwon += o.inwon;
            basicAmount += o.basicAmount;
            tradeAmount += o.tradeAmount;
            totalAmount += o.totalAmount;
        }

        private Row toRow(String rowType, String label) {
            boolean detail = "DETAIL".equals(rowType);
            return new Row(rowType, label,
                    detail ? reqDate : null, detail ? reqCd : null,
                    detail ? productCode : null, detail ? productName : null,
                    detail ? grade : null, detail ? dtlCd : null, detail ? detailName : null,
                    detail ? partnerCode : null, detail ? partnerName : null,
                    detail ? region : null, detail ? cityName : null, detail ? schoolName : null,
                    materialQty, paperQty, paperAmount, omrQty, omrAmount, etcQty, etcAmount,
                    inwon, basicAmount, tradeAmount, totalAmount);
        }
    }
}
