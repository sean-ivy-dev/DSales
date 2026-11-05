package com.daesung.sales.sale.service;

import com.daesung.sales.closing.config.SupplierProperties;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.inventory.repository.InventoryTxnRepository;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.sale.dto.BookInoutResponse;
import com.daesung.sales.sale.dto.BookSalesAgg;
import com.daesung.sales.sale.dto.CategorySalesAgg;
import com.daesung.sales.sale.dto.CategorySalesResponse;
import com.daesung.sales.sale.dto.RoundWorkStatusRow;
import com.daesung.sales.sale.dto.MonthlyStatementResponse;
import com.daesung.sales.sale.dto.NetSalesResponse;
import com.daesung.sales.sale.dto.PartnerProductSalesAgg;
import com.daesung.sales.product.entity.MajorCategory;
import com.daesung.sales.sale.dto.SalesStatementAgg;
import com.daesung.sales.sale.dto.StatementKind;
import com.daesung.sales.sale.entity.SalesType;
import com.daesung.sales.salestype.entity.ShipmentType;
import com.daesung.sales.sale.dto.SalesStatementResponse;
import com.daesung.sales.sale.dto.SalesStatementRow;
import com.daesung.sales.sale.dto.SalesSummaryResponse;
import com.daesung.sales.sale.dto.SalesSummaryRow;
import com.daesung.sales.sale.dto.TransactionStatementResponse;
import com.daesung.sales.sale.dto.UnclassifiedSalesResponse;
import com.daesung.sales.sale.dto.YoyComparisonResponse;
import com.daesung.sales.sale.entity.Sale;
import com.daesung.sales.sale.repository.SaleRepository;
import com.daesung.sales.salestype.entity.SalesCategory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매출 리포트 집계 8종(레거시 RDLC 재현). 전부 읽기전용 — {@link SaleService}(쓰기 도메인)에서 분리.
 * 순매출/명세서/월별명세서/거래명세서/도서입출고/매출대비표/과목별매출. 이중장부(sales+inventory_txn) 조회.
 */
@Service
@RequiredArgsConstructor
public class SaleReportService {

    /**
     * 매출액명세서 행 순서. rollup은 같은 대분류가 <b>연속으로 붙어 있어야</b> 성립한다.
     * 대분류는 분류코드가 아니라 세부구분에서 오므로, 서로 다른 분류코드가 같은 대분류에
     * 속해 흩어져 있을 수 있다. 정렬하지 않으면 같은 대분류의 '분류 계' 행이 여러 번 찍힌다.
     *
     * <p>순서의 정본은 enum 선언 순서(모의고사·교재·기타고사·특강·기타)다 —
     * SQL로 정렬하면 enum 이름 알파벳순이 되어 화면 순서와 어긋난다. 미분류는 맨 뒤.
     */
    private static final java.util.Comparator<SalesStatementAgg> STATEMENT_ORDER =
            java.util.Comparator
                    .comparingInt((SalesStatementAgg a) -> (a.getMajorCategory() == null)
                            ? Integer.MAX_VALUE : a.getMajorCategory().ordinal())
                    .thenComparing(a -> blank(a.getCatCode()))
                    .thenComparing(a -> blank(a.getBookCode()));

    private final SaleRepository saleRepository;
    private final InventoryTxnRepository inventoryTxnRepository;
    private final PartnerRepository partnerRepository;
    private final SupplierProperties supplier;

    /**
     * 순매출 집계(상품별 + 합계행). 취소 제외. 매출/증정/교사용/반품 버킷 + 순매출(매출−반품).
     * 기간 미지정 시 올해 1/1~오늘.
     */
    @Transactional(readOnly = true)
    public SalesSummaryResponse summary(LocalDate fromDate, LocalDate toDate, Long partnerId,
                                        String summaryKind, String salesType,
                                        java.util.List<com.daesung.sales.salestype.entity.TradeClass> tradeClasses) {
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();
        String kind = normalizeKind(summaryKind);
        String type = normalizeSalesType(salesType);

        List<SalesSummaryRow> rows = new ArrayList<>();
        long tSaleQ = 0, tSaleA = 0, tFreeQ = 0, tFreeA = 0, tTchQ = 0, tTchA = 0,
                tRetQ = 0, tRetA = 0, tTax = 0, tTotal = 0;
        // 거래분류(다중) → 회계구분으로 바꿔 넘긴다. 입고·폐기는 매출 원장에 없어 떨어진다.
        java.util.List<String> categories = new ArrayList<>();
        if (!com.daesung.sales.common.query.MultiSelect.isAny(tradeClasses)) {
            for (com.daesung.sales.salestype.entity.TradeClass tc : tradeClasses) {
                var mapped = tc.toSalesCategory();
                if (mapped != null && !categories.contains(mapped.name())) {
                    categories.add(mapped.name());
                }
            }
            if (categories.isEmpty()) {
                // 입고·폐기만 골랐다 — 매출 집계로는 한 건도 안 나오는 것이 맞다.
                return new SalesSummaryResponse(from, to, List.of(),
                        new SalesSummaryRow(null, null, null, null, "합계", kind, type,
                                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List.of());
            }
        }
        boolean anyCategory = categories.isEmpty();
        for (Object[] r : saleRepository.salesSummary(anyCategory,
                anyCategory ? List.of("\u0000") : categories,
                from, to, partnerId, categoryOf(kind),
                kindShipment(kind), typeShipment(type))) {
            // ‼️인덱스는 SELECT 순서다 — 0 상품id / 1 코드 / 2 명 / 3 분류코드 / 4 분류명 / 5~ 버킷.
            //   분류 두 칸을 앞에 넣으면서 여기를 안 옮겨 집계가 통째로 0이 됐다(테스트가 잡았다).
            long saleQty = num(r[5]), saleAmt = num(r[6]), freeQty = num(r[7]), freeAmt = num(r[8]),
                    tchQty = num(r[9]), tchAmt = num(r[10]), retQty = num(r[11]), retAmt = num(r[12]),
                    tax = num(r[13]), total = num(r[14]);
            rows.add(new SalesSummaryRow(
                    num(r[0]), (String) r[3], (String) r[4], (String) r[1], (String) r[2], kind, type,
                    saleQty, saleAmt, freeQty, freeAmt, tchQty, tchAmt, retQty, retAmt,
                    saleQty - retQty, saleAmt - retAmt, tax, total));
            tSaleQ += saleQty; tSaleA += saleAmt; tFreeQ += freeQty; tFreeA += freeAmt;
            tTchQ += tchQty; tTchA += tchAmt; tRetQ += retQty; tRetA += retAmt; tTax += tax; tTotal += total;
        }
        SalesSummaryRow total = new SalesSummaryRow(null, null, null, "합계", null, kind, type,
                tSaleQ, tSaleA, tFreeQ, tFreeA, tTchQ, tTchA, tRetQ, tRetA,
                tSaleQ - tRetQ, tSaleA - tRetA, tTax, tTotal);
        return new SalesSummaryResponse(from, to, rows, total, subtotalsByCategory(rows, kind, type));
    }

    /**
     * 분류별 소계(발주처 1차회신 2026-10-02 #8 — "분류명에 따른 소계, 분류코드에 따른 분류 계, 총계").
     *
     * <p>★<b>묶는 키는 분류코드다.</b> 회신 문장은 소계(분류명)와 분류계(분류코드)를 따로 적었지만,
     * 우리 마스터에서 분류코드와 분류명은 1:1이라 두 축이 같은 묶음을 만든다. 둘을 따로 내면
     * 같은 숫자가 두 줄로 나와 담당자가 "뭐가 다른가"를 되묻게 된다 —
     * 코드로 묶고 이름을 함께 실어, 화면이 어느 쪽으로 보여주든 한 숫자를 쓰게 한다.
     * ‼️이름이 같고 코드가 다른 분류가 생기면 <b>코드 기준이 맞다</b>(코드가 식별자다).
     *
     * <p>소계행은 상품 자리를 비운다 — 채우면 특정 도서의 숫자로 읽힌다(합계행과 같은 규칙).
     */
    private static List<SalesSummaryRow> subtotalsByCategory(List<SalesSummaryRow> rows,
                                                             String kind, String type) {
        record Acc(long[] v, String name) {
        }
        java.util.Map<String, Acc> byCat = new java.util.LinkedHashMap<>();
        for (SalesSummaryRow r : rows) {
            String code = (r.catCode() == null || r.catCode().isBlank()) ? "(미분류)" : r.catCode();
            Acc a = byCat.computeIfAbsent(code, k -> new Acc(new long[10], r.catName()));
            long[] v = a.v();
            v[0] += r.saleQty();     v[1] += r.saleAmount();
            v[2] += r.freeQty();     v[3] += r.freeAmount();
            v[4] += r.teacherQty();  v[5] += r.teacherAmount();
            v[6] += r.returnQty();   v[7] += r.returnAmount();
            v[8] += r.tax();         v[9] += r.total();
        }
        List<SalesSummaryRow> out = new ArrayList<>(byCat.size());
        for (var e : byCat.entrySet()) {
            long[] v = e.getValue().v();
            out.add(new SalesSummaryRow(null, e.getKey(), e.getValue().name(), null, "소계",
                    kind, type,
                    v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7],
                    v[0] - v[6], v[1] - v[7], v[8], v[9]));
        }
        return out;
    }

    /**
     * 구분(매출/반품/교사용/증정용) 정규화.
     *
     * <p>‼️모르는 값은 <b>거부한다.</b> 조용히 전체로 넘기면 담당자는 걸러진 목록을 보고 있다고
     * 믿는데 실제로는 전부가 나온다 — 화면에 오류도 안 뜬다.
     */
    private static String normalizeKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (List.of("매출", "반품", "교사용", "증정용").contains(v)) {
            return v;
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT,
                "알 수 없는 구분입니다: " + raw + " (매출/반품/교사용/증정용)");
    }

    /** 매출유형(일반매출/위탁매출) 정규화. 모르는 값은 거부. */
    private static String normalizeSalesType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        if (List.of("일반매출", "위탁매출").contains(v)) {
            return v;
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT,
                "알 수 없는 매출유형입니다: " + raw + " (일반매출/위탁매출)");
    }

    /** 구분 → 회계구분. 교사용·증정용은 둘 다 FREE라 출고유형으로 더 좁힌다. */
    private static String categoryOf(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "매출" -> "SALE";
            case "반품" -> "RETURN";
            default -> "FREE";
        };
    }

    /**
     * 구분 → 출고유형. 교사용·증정용은 구분이 곧 출고유형이다(둘 다 회계구분은 FREE라 더 좁혀야 한다).
     *
     * <p>★{@link #typeShipment}와 <b>따로</b> 건다. 하나로 합치면 '교사용 + 위탁매출' 같은
     * 모순 조합에서 한쪽이 조용히 무시돼, 담당자는 걸러진 줄 알고 교사용을 본다.
     * 따로 걸면 서로 충돌해 빈 결과가 되고, 그게 사실에 맞다.
     */
    private static String kindShipment(String kind) {
        if ("교사용".equals(kind)) {
            return "TEACHER_USE";
        }
        return "증정용".equals(kind) ? "GIFT" : null;
    }

    /** 매출유형 → 출고유형. 일반/위탁은 매출 안에서의 갈래다. */
    private static String typeShipment(String salesType) {
        if (salesType == null) {
            return null;
        }
        return "일반매출".equals(salesType) ? "NORMAL_SHIP" : "CONSIGN_SHIP";
    }

    /**
     * 콘텐츠구분 순매출. SELF=매출−반품, EXTERNAL=매출−매입=이익(매입원가=매입입고 unit_cost 가중평균).
     * 기간 미지정 시 올해 1/1~오늘. contentType: null/전체, SELF, EXTERNAL.
     *
     * <p>정본 16p 데이터 항목을 다 싣는다 — 상품분류, 교사용(증정용포함) 수량,
     * 순매출 세액·총금액, 외부콘텐츠 매입 입고수량.
     *
     * <p>★<b>교사용은 순매출에서 빼지 않는다</b>. 정본 16p 설명문은 "매출−교사용−반품=순매출"이라
     * 적혀 있지만, 교사용은 회계구분 FREE라 애초에 매출액(SALE)에 들어 있지 않다 —
     * 한 번 더 빼면 무상으로 나간 만큼 순매출이 <b>음수 쪽으로</b> 밀린다.
     * 레거시도 교사용을 빼지 않는다(제품수불부 {@code 순매출수량=매출+유가+반품}).
     * 그래서 교사용은 <b>따로 보여주기만</b> 하고 순매출 식에서는 건드리지 않는다.
     */
    @Transactional(readOnly = true)
    public NetSalesResponse netSales(LocalDate fromDate, LocalDate toDate, String contentType) {
        return netSales(fromDate, toDate, contentType, null, null, null, null);
    }

    /** 조회 조건(거래처·세부구분·분류·도서)까지 받는 순매출(2026-10-08). */
    /**
     * 순매출 — <b>거래처별</b>(현장 피드백 2026-10-14 B-2, 레거시 「조회구분 ○거래처별」).
     *
     * <p>★금액 칸은 도서별과 같다. 같은 화면의 두 보기라 칸이 달라지면 비교가 안 된다.
     * <p>‼️매입·이익 칸은 <b>비운다</b> — 매입단가는 상품 속성이라 거래처로 묶으면
     * 서로 다른 단가가 섞인다. 섞인 값으로 이익을 내면 틀린 숫자가 그럴듯하게 나온다.
     */
    @Transactional(readOnly = true)
    public NetSalesResponse netSalesByPartner(LocalDate fromDate, LocalDate toDate, String contentType,
                                              java.util.List<Long> partnerIds,
                                              java.util.List<String> divisions,
                                              java.util.List<String> catCodes,
                                              java.util.List<Long> productIds) {
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();
        String filter = (contentType == null || contentType.isBlank())
                ? null : contentType.trim().toUpperCase(Locale.ROOT);

        List<NetSalesResponse.Row> rows = new ArrayList<>();
        long tSaleQ = 0, tSaleA = 0, tSaleTax = 0, tFreeQ = 0, tFreeA = 0;
        long tRetQ = 0, tRetA = 0, tRetTax = 0;
        for (Object[] r : saleRepository.netSalesByPartner(
                com.daesung.sales.common.query.MultiSelect.isAny(partnerIds),
                com.daesung.sales.common.query.MultiSelect.orPlaceholder(partnerIds, 0L),
                com.daesung.sales.common.query.MultiSelect.isAny(divisions),
                com.daesung.sales.common.query.MultiSelect.orPlaceholder(divisions, "\u0000"),
                com.daesung.sales.common.query.MultiSelect.isAny(catCodes),
                com.daesung.sales.common.query.MultiSelect.orPlaceholder(catCodes, "\u0000"),
                com.daesung.sales.common.query.MultiSelect.isAny(productIds),
                com.daesung.sales.common.query.MultiSelect.orPlaceholder(productIds, 0L),
                from, to, filter)) {
            long saleQty = num(r[3]), saleAmt = num(r[4]), saleTax = num(r[5]);
            long freeQty = num(r[6]), freeAmt = num(r[7]);
            long retQty = num(r[8]), retAmt = num(r[9]), retTax = num(r[10]);
            long netQty = saleQty - retQty, netAmt = saleAmt - retAmt, netTax = saleTax - retTax;

            // 거래처 행에서는 상품 축(분류·콘텐츠구분)과 매입·이익을 비운다 — 섞인 값이다.
            rows.add(new NetSalesResponse.Row(null, null, null, null, null,
                    num(r[0]), (String) r[1], (String) r[2],
                    null, null,
                    saleQty, saleAmt, freeQty, freeAmt, freeQty, freeAmt,
                    retQty, returnRate(retQty, saleQty), retAmt,
                    netQty, netAmt, netTax, netAmt + netTax,
                    null, null, null, null, null, null, null));
            tSaleQ += saleQty; tSaleA += saleAmt; tSaleTax += saleTax;
            tFreeQ += freeQty; tFreeA += freeAmt;
            tRetQ += retQty; tRetA += retAmt; tRetTax += retTax;
        }
        long tNetAmt = tSaleA - tRetA;
        long tNetTax = tSaleTax - tRetTax;
        NetSalesResponse.Row total = new NetSalesResponse.Row(null, null, null, null, null,
                null, null, "합계", null, null,
                tSaleQ, tSaleA, tFreeQ, tFreeA, tFreeQ, tFreeA,
                tRetQ, returnRate(tRetQ, tSaleQ), tRetA,
                tSaleQ - tRetQ, tNetAmt, tNetTax, tNetAmt + tNetTax,
                null, null, null, null, null, null, null);
        return new NetSalesResponse(from, to, filter, rows, total);
    }

    @Transactional(readOnly = true)
    public NetSalesResponse netSales(LocalDate fromDate, LocalDate toDate, String contentType,
                                     java.util.List<Long> partnerIds,
                                     java.util.List<String> divisions,
                                     java.util.List<String> catCodes,
                                     java.util.List<Long> productIds) {
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();
        String filter = (contentType == null || contentType.isBlank())
                ? null : contentType.trim().toUpperCase(Locale.ROOT);

        // 상품별 매입원가(입고 가중평균 — 기간 무관)와 매입 입고수량(기간 내)
        Map<Long, Long> avgCost = new HashMap<>();
        for (Object[] r : inventoryTxnRepository.avgInboundCostByProduct()) {
            avgCost.put(num(r[0]), num(r[1]));
        }
        Map<Long, Long> inbound = new HashMap<>();
        for (Object[] r : inventoryTxnRepository.purchaseInboundByProduct(from, to)) {
            inbound.put(num(r[0]), num(r[1]));
        }
        // 매입반품(기간) — 수량·금액. 쿼리가 부호를 뒤집어 양수로 준다.
        Map<Long, long[]> purchaseReturn = new HashMap<>();
        for (Object[] r : inventoryTxnRepository.purchaseReturnByProduct(from, to)) {
            purchaseReturn.put(num(r[0]), new long[]{num(r[1]), num(r[2])});
        }

        List<NetSalesResponse.Row> rows = new ArrayList<>();
        long tSaleQ = 0, tSaleA = 0, tSaleTax = 0, tFreeQ = 0, tFreeA = 0;
        long tRetQ = 0, tRetA = 0, tRetTax = 0, tPurch = 0, tInQ = 0;
        long tPrQ = 0, tPrA = 0;   // 매입반품 수량·금액
        boolean anyExternal = false;
        for (Object[] r : saleRepository.netSalesByProduct(
                com.daesung.sales.common.query.MultiSelect.isAny(partnerIds),
                com.daesung.sales.common.query.MultiSelect.orPlaceholder(partnerIds, 0L),
                com.daesung.sales.common.query.MultiSelect.isAny(divisions),
                com.daesung.sales.common.query.MultiSelect.orPlaceholder(divisions, "\u0000"),
                com.daesung.sales.common.query.MultiSelect.isAny(catCodes),
                com.daesung.sales.common.query.MultiSelect.orPlaceholder(catCodes, "\u0000"),
                com.daesung.sales.common.query.MultiSelect.isAny(productIds),
                com.daesung.sales.common.query.MultiSelect.orPlaceholder(productIds, 0L),
                from, to, filter)) {
            long pid = num(r[0]);
            String ct = (String) r[3];
            long saleQty = num(r[6]), saleAmt = num(r[7]), saleTax = num(r[8]);
            long freeQty = num(r[9]), freeAmt = num(r[10]);
            long retQty = num(r[11]), retAmt = num(r[12]), retTax = num(r[13]);
            long netQty = saleQty - retQty;
            long netAmt = saleAmt - retAmt;
            long netTax = saleTax - retTax;

            Long inQty = null, unitCost = null, purchase = null, profit = null;
            Long prQty = null, prAmt = null;
            Double margin = null;
            if ("EXTERNAL".equals(ct)) {
                anyExternal = true;
                inQty = inbound.getOrDefault(pid, 0L);
                long[] pr = purchaseReturn.getOrDefault(pid, new long[]{0L, 0L});
                prQty = pr[0];
                prAmt = pr[1];
                tPrQ += pr[0];
                tPrA += pr[1];
                unitCost = avgCost.getOrDefault(pid, 0L);
                // ‼️매입액에서 매입반품을 빼지 않는다 — 매입액은 "판 물건의 원가"(단가×순매출수량)이고
                //   매입반품은 재고 흐름이다. 빼면 반품한 달의 이익률이 실제와 달라진다.
                purchase = unitCost * netQty;
                profit = netAmt - purchase;
                margin = (netAmt != 0) ? Math.round((double) profit / netAmt * 100 * 10) / 10.0 : null;
                tPurch += purchase;
                tInQ += inQty;
            }
            rows.add(new NetSalesResponse.Row(pid, (String) r[4], (String) r[5], (String) r[1], (String) r[2],
                    null, null, null,
                    ct, contentLabel(ct),
                    saleQty, saleAmt, freeQty, freeAmt, freeQty, freeAmt,
                    retQty, returnRate(retQty, saleQty), retAmt,
                    netQty, netAmt, netTax, netAmt + netTax,
                    inQty, prQty, prAmt, unitCost, purchase, profit, margin));
            tSaleQ += saleQty; tSaleA += saleAmt; tSaleTax += saleTax;
            tFreeQ += freeQty; tFreeA += freeAmt;
            tRetQ += retQty; tRetA += retAmt; tRetTax += retTax;
        }

        long tNetAmt = tSaleA - tRetA;
        long tNetTax = tSaleTax - tRetTax;
        Long totalPurchase = anyExternal ? tPurch : null;
        Long totalProfit = anyExternal ? (tNetAmt - tPurch) : null;
        Double totalMargin = (anyExternal && tNetAmt != 0) ? Math.round((double) totalProfit / tNetAmt * 100 * 10) / 10.0 : null;
        NetSalesResponse.Row total = new NetSalesResponse.Row(null, null, null, "합계", null,
                null, null, null, null, null,
                tSaleQ, tSaleA, tFreeQ, tFreeA, tFreeQ, tFreeA,
                tRetQ, returnRate(tRetQ, tSaleQ), tRetA,
                tSaleQ - tRetQ, tNetAmt, tNetTax, tNetAmt + tNetTax,
                anyExternal ? tInQ : null,
                anyExternal ? tPrQ : null, anyExternal ? tPrA : null,
                null, totalPurchase, totalProfit, totalMargin);
        return new NetSalesResponse(from, to, filter, rows, total);
    }

    /**
     * 콘텐츠구분 표기. 발주처 화면검토(2026-08-31)로 '외부콘텐츠' → <b>'매입 교재'</b>가 됐다.
     * 코드값(EXTERNAL)은 그대로 두고 라벨만 바꾼다 — 저장 데이터·API 계약을 건드릴 일이 아니다.
     */
    private static String contentLabel(String code) {
        if (code == null) {
            return null;
        }
        try {
            return com.daesung.sales.product.entity.ContentType.valueOf(code).label();
        } catch (IllegalArgumentException e) {
            return code;   // 알 수 없는 값은 그대로 — 숨기면 왜 비었는지 못 찾는다
        }
    }

    /** 반품률 %(반품/매출). 매출이 0이면 나눌 수 없어 null — 0%로 두면 "반품 없음"으로 오해된다. */
    private static Double returnRate(long returnQty, long saleQty) {
        return (saleQty == 0) ? null : Math.round((double) returnQty / saleQty * 100 * 10) / 10.0;
    }

    private static long num(Object o) {
        return (o == null) ? 0L : ((Number) o).longValue();
    }

    /**
     * 미분류 매출 — 상품별 목록(10/27 피드백 §7-C 하단 5번 "이관 때 매핑되지 않는 누락 건은
     * 반드시 목록으로 뽑아 확인한다").
     *
     * <p>대분류는 {@code 매출 → 상품.세부구분 → 세부구분 마스터.대분류} 로 이어지는데,
     * 이 사슬이 끊긴 매출은 상품군 칸 어디에도 안 들어가고 '미분류'로 남는다.
     * 그 끊긴 자리를 <b>상품 단위로, 매출 큰 순서로</b> 돌려준다 —
     * 담당자가 도서관리에서 하나씩 채워야 하는 작업 목록이다.
     *
     * <p>비율을 함께 내는 이유: 목록 길이만으로는 심각도를 알 수 없다. 한 번도 안 팔린
     * 상품 100개보다 많이 팔린 상품 1개가 장부를 더 많이 흔든다.
     */
    @Transactional(readOnly = true)
    public UnclassifiedSalesResponse unclassifiedSales(LocalDate from, LocalDate to) {
        List<UnclassifiedSalesResponse.Row> rows = new ArrayList<>();
        long unclassified = 0;
        for (Object[] r : saleRepository.unclassifiedProducts(from, to)) {
            long amount = num(r[8]);
            unclassified += amount;
            rows.add(new UnclassifiedSalesResponse.Row(
                    (r[0] == null) ? null : ((Number) r[0]).longValue(),
                    (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    (String) r[5], (String) r[6],
                    num(r[7]), amount,
                    (r[9] == null) ? null : ((java.sql.Date) r[9]).toLocalDate()));
        }
        long total = saleRepository.sumSaleAmount(from, to);
        double ratio = (total == 0) ? 0.0
                : Math.round((double) unclassified / total * 100 * 10) / 10.0;
        return new UnclassifiedSalesResponse(from, to, total, unclassified, ratio, rows);
    }

    /**
     * 매출액명세서. 근거: 레거시 매출액명세서.vb rollup(대분류, catCode, bookCode).
     * flat 도서집계를 상세→소계(분류)→분류계(대분류)→총계 순 계층으로 조립.
     * 합계=금액+세액(레거시 totalAmt2 미사용 규칙).
     *
     * <p>필터는 정본 15p 그대로다 — <b>구분</b>(전체/매출/반품/교사용/증정용)과
     * <b>매출유형</b>(일반매출/위탁매출). 둘 다 null이면 전체다.
     *
     * <p>★대분류는 세부구분 마스터에서 파생된 값이다(발주처 회신 2026-08-20).
     * 예전엔 {@code left(catCode,1)}로 분류코드 첫 글자를 그대로 대분류로 썼는데,
     * 화면에 'H'·'M' 같은 글자가 나왔고 분류코드 체계가 아직 없는 신규 데이터에서는 값 자체가 무의미했다.
     */
    @Transactional(readOnly = true)
    public SalesStatementResponse statement(LocalDate from, LocalDate to, Long partnerId,
                                           StatementKind kind, SalesType salesType, String keyword) {
        return statement(from, to, partnerId, kind, salesType, null, keyword);
    }

    /**
     * 매출액명세서. {@code tradeClasses}(거래분류 다중선택)까지 받는다.
     *
     * <p>근거: 프론트 실측 2026-10-12 B-1 — 10/08 회신에 "매출액정리 거래분류 다중 →
     * `tradeClasses=`" 라고 적었는데 실제로는 {@code /sales/summary} 에만 붙어 있었다.
     * <b>매출액정리 화면이 쓰는 것은 이쪽</b>이라 여기에 없으면 회신대로 동작하지 않는다.
     *
     * <p>입고·폐기는 매출 원장에 없는 분류라 떨어진다 — 그것만 고르면 빈 결과가 맞다.
     */
    @Transactional(readOnly = true)
    public SalesStatementResponse statement(LocalDate from, LocalDate to, Long partnerId,
                                           StatementKind kind, SalesType salesType,
                                           java.util.List<com.daesung.sales.salestype.entity.TradeClass> tradeClasses,
                                           String keyword) {
        // 구분은 회계구분·출고유형이 섞인 축이다(정본 15p) — 교사용·증정용은 둘 다 무상이라
        // 회계구분만으로는 갈라지지 않는다. 어느 쪽으로 거를지는 enum이 안다.
        SalesCategory category = (kind == null) ? null : kind.category();
        ShipmentType shipmentType = (kind == null) ? null : kind.shipmentType();
        // 거래분류(다중) → 회계구분. 입고·폐기는 매출 원장에 없어 떨어진다.
        java.util.List<SalesCategory> tradeCategories = new ArrayList<>();
        boolean anyTradeClass = com.daesung.sales.common.query.MultiSelect.isAny(tradeClasses);
        if (!anyTradeClass) {
            for (com.daesung.sales.salestype.entity.TradeClass tc : tradeClasses) {
                SalesCategory mapped = tc.toSalesCategory();
                if (mapped != null && !tradeCategories.contains(mapped)) {
                    tradeCategories.add(mapped);
                }
            }
            if (tradeCategories.isEmpty()) {
                // 입고·폐기만 골랐다 — 매출 명세서로는 한 건도 안 나오는 것이 맞다.
                return new SalesStatementResponse(from, to,
                        (kind == null) ? null : kind.name(),
                        (kind == null) ? null : kind.label(),
                        (salesType == null) ? null : salesType.name(), List.of());
            }
        }
        List<SalesStatementAgg> aggs = new ArrayList<>(
                saleRepository.statementAgg(from, to, partnerId, category, shipmentType, salesType,
                        anyTradeClass,
                        com.daesung.sales.common.query.MultiSelect.orPlaceholder(
                                tradeCategories, SalesCategory.SALE)));
        // ‼️키워드는 **롤업을 만들기 전에** 건다. 만들고 나서 상세행만 걸러내면
        //   소계·분류계·총계는 걸러지기 전 값으로 남아, 화면에서 상세를 다 더해도 소계와 안 맞는다.
        //   ‼️Keywords.filter 는 불변 리스트를 돌려준다 — 바로 아래 sort 가 터지므로
        //     정렬을 먼저 하고 그 결과를 걸러 새 리스트로 받는다.
        aggs.sort(STATEMENT_ORDER);
        aggs = com.daesung.sales.common.query.Keywords.filter(aggs, keyword,
                a -> new Object[]{a.getCatCode(), a.getCatName(), a.getBookCode(), a.getBookName()});
        // ‼️rollup은 같은 대분류가 연속으로 붙어 있어야 성립한다. 대분류는 분류코드가 아니라
        //   세부구분에서 오므로, 서로 다른 분류코드가 같은 대분류에 속해 흩어져 있을 수 있다.
        //   정렬하지 않으면 같은 대분류의 '분류 계' 행이 여러 번 찍힌다.
        //   순서의 정본은 enum 선언 순서(모의고사·교재·기타고사·특강·기타) — SQL로 정렬하면
        //   enum 이름 알파벳순이 되어 화면 순서와 어긋난다. 미분류는 맨 뒤.
        List<SalesStatementRow> rows = new ArrayList<>();

        long gQty = 0, gAmt = 0, gTax = 0;                 // 총계
        MajorCategory curMajor = null;
        boolean majorOpen = false;
        long mQty = 0, mAmt = 0, mTax = 0;                 // 대분류계
        String curCat = null, curCatName = null;
        boolean catOpen = false;
        long cQty = 0, cAmt = 0, cTax = 0;                 // 분류 소계

        for (SalesStatementAgg a : aggs) {
            MajorCategory major = a.getMajorCategory();
            String cat = a.getCatCode();

            if (catOpen && !java.util.Objects.equals(cat, curCat)) {
                rows.add(SalesStatementRow.catSubtotal(curMajor, curCat, curCatName, cQty, cAmt, cTax));
                catOpen = false;
            }
            if (majorOpen && !java.util.Objects.equals(major, curMajor)) {
                rows.add(SalesStatementRow.majorTotal(curMajor, mQty, mAmt, mTax));
                majorOpen = false;
            }
            if (!majorOpen) {
                curMajor = major;
                mQty = mAmt = mTax = 0;
                majorOpen = true;
            }
            if (!catOpen) {
                curCat = cat;
                curCatName = a.getCatName();
                cQty = cAmt = cTax = 0;
                catOpen = true;
            }

            rows.add(SalesStatementRow.detail(major, cat, a.getCatName(),
                    a.getBookCode(), a.getBookName(), a.getQty(), a.getAmount(), a.getTax()));

            cQty += a.getQty(); cAmt += a.getAmount(); cTax += a.getTax();
            mQty += a.getQty(); mAmt += a.getAmount(); mTax += a.getTax();
            gQty += a.getQty(); gAmt += a.getAmount(); gTax += a.getTax();
        }
        if (catOpen) {
            rows.add(SalesStatementRow.catSubtotal(curMajor, curCat, curCatName, cQty, cAmt, cTax));
        }
        if (majorOpen) {
            rows.add(SalesStatementRow.majorTotal(curMajor, mQty, mAmt, mTax));
        }
        rows.add(SalesStatementRow.grandTotal(gQty, gAmt, gTax));

        return new SalesStatementResponse(from, to,
                (kind == null) ? null : kind.name(),
                (kind == null) ? null : kind.label(),
                (salesType == null) ? null : salesType.name(), rows);
    }

    /** 정렬용 null 방어. 분류·도서 코드가 비어도 순서가 뒤집히지 않게 빈 문자열로 맞춘다. */
    private static String blank(String v) {
        return (v == null) ? "" : v;
    }

    /**
     * 대분류 정렬 순서 — enum 선언 순서가 정본(모의고사·교재·기타고사·특강·기타). 미지정은 맨 뒤.
     * SQL에 넣지 않는 이유: enum 이름 알파벳순(ETC, ETC_EXAM, MOCK_EXAM…)이 되어 화면 순서와 어긋난다.
     */
    private static int majorOrder(String majorName) {
        for (MajorCategory c : MajorCategory.values()) {
            if (c.name().equals(majorName)) {
                return c.ordinal();
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 월별매출액명세서(37p): 대분류×상품 성적처리/비처리 인원·금액 + 대분류계 + 총계 + 과세매출·부가세.
     * 매출(SALE)만. 인원=수량(모의고사=응시인원), 성적처리=proc_type GRADED(그 외=비처리).
     */
    @Transactional(readOnly = true)
    public MonthlyStatementResponse monthlyStatement(int year, int month) {
        List<MonthlyStatementResponse.Row> rows = new ArrayList<>();
        // 누계: [gradedQty, gradedAmt, ungradedQty, ungradedAmt, taxableAmt, vat]
        long[] major = new long[6];
        long[] grand = new long[6];
        String curMajor = null;
        boolean majorOpen = false;

        // 대분류는 세부구분에서 오므로 서로 다른 분류코드가 같은 대분류에 흩어질 수 있다.
        // 정렬하지 않으면 같은 대분류의 소계 행이 여러 번 찍힌다(순서 정본 = enum 선언 순서).
        List<Object[]> aggs = new ArrayList<>(saleRepository.monthlyStatementAgg(year, month));
        aggs.sort(java.util.Comparator
                .comparingInt((Object[] r) -> majorOrder((r[0] == null) ? null : r[0].toString()))
                .thenComparing(r -> blank((String) r[1]))
                .thenComparing(r -> blank((String) r[4])));

        // 분류 계(발주처 검수 F-21) — 대분류 안에서 분류가 바뀔 때 끊는다.
        // ★대분류 소계보다 **먼저** 찍어야 한다(분류 계들 → 대분류 계 순).
        long[] cat = new long[6];
        String curCat = null;
        String curCatName = null;
        boolean catOpen = false;

        for (Object[] r : aggs) {
            String m = (r[0] == null) ? null : r[0].toString();
            String catCode = (String) r[1];
            String catName = (String) r[2];
            long[] v = {num(r[6]), num(r[7]), num(r[8]), num(r[9]), num(r[10]), num(r[11])};

            boolean majorChanged = majorOpen && !java.util.Objects.equals(m, curMajor);
            boolean catChanged = catOpen
                    && (majorChanged || !java.util.Objects.equals(catCode, curCat));
            if (catChanged) {
                rows.add(catRow(curMajor, curCatName, cat));
                cat = new long[6];
                catOpen = false;
            }
            if (majorChanged) {
                rows.add(majorRow(MonthlyStatementResponse.Row.RowType.MAJOR_SUBTOTAL, curMajor, major));
                major = new long[6];
                majorOpen = false;
            }
            if (!majorOpen) {
                curMajor = m;
                majorOpen = true;
            }
            if (!catOpen) {
                curCat = catCode;
                curCatName = catName;
                catOpen = true;
            }
            rows.add(new MonthlyStatementResponse.Row(
                    MonthlyStatementResponse.Row.RowType.DETAIL, m, majorLabel(m),
                    catName, (String) r[4], (String) r[5],
                    v[0], v[1], v[2], v[3], v[0] + v[2], v[1] + v[3], v[4], v[5]));
            for (int i = 0; i < 6; i++) {
                cat[i] += v[i];
                major[i] += v[i];
                grand[i] += v[i];
            }
        }
        if (catOpen) {
            rows.add(catRow(curMajor, curCatName, cat));
        }
        if (majorOpen) {
            rows.add(majorRow(MonthlyStatementResponse.Row.RowType.MAJOR_SUBTOTAL, curMajor, major));
        }
        rows.add(majorRow(MonthlyStatementResponse.Row.RowType.GRAND_TOTAL, null, grand));
        return new MonthlyStatementResponse(year, month, rows);
    }

    /**
     * 분류 계 행(F-21). 대분류명은 남기고 <b>분류명 칸에 분류명</b>을 넣는다 —
     * 도서코드·도서명은 비운다(그 자리에 값이 있으면 상세 행으로 읽힌다).
     */
    private MonthlyStatementResponse.Row catRow(String majorCode, String catName, long[] v) {
        return new MonthlyStatementResponse.Row(
                MonthlyStatementResponse.Row.RowType.CAT_SUBTOTAL, majorCode, majorLabel(majorCode),
                catName, null, null,
                v[0], v[1], v[2], v[3], v[0] + v[2], v[1] + v[3], v[4], v[5]);
    }

    /** 집계배열 v=[gradedQty,gradedAmt,ungradedQty,ungradedAmt,taxableAmt,vat] → 소계/총계 행. */
    private MonthlyStatementResponse.Row majorRow(MonthlyStatementResponse.Row.RowType type,
                                                  String majorCode, long[] v) {
        // 총계 행은 대분류가 없으므로 이름도 비운다 — '미분류'로 찍으면 대분류 하나처럼 보인다.
        boolean grand = (type == MonthlyStatementResponse.Row.RowType.GRAND_TOTAL);
        return new MonthlyStatementResponse.Row(type, majorCode, grand ? null : majorLabel(majorCode),
                null, null, null,
                v[0], v[1], v[2], v[3], v[0] + v[2], v[1] + v[3], v[4], v[5]);
    }

    /** 대분류 코드 → 화면 표기명. 미지정은 '미분류'(집계에서 빼지 않는다). */
    private static String majorLabel(String majorName) {
        for (MajorCategory c : MajorCategory.values()) {
            if (c.name().equals(majorName)) {
                return c.label();
            }
        }
        return SalesStatementRow.UNCLASSIFIED;
    }

    /**
     * 거래명세서. 근거: 레거시 UC_TabPages_ETC.vb 거래명세서 데이터셋(공급자/공급받는자 + 유가/무가 라인).
     * 공급자=자사(SupplierProperties, 단일법인 가정), 공급받는자=거래처. 취소 제외.
     * category=null이면 매출(SALE)+무가(FREE), 지정 시 해당 구분만(예: RETURN=반품명세서).
     * 유가(공급가액≠0)/무가(=0) 분리, 합계=공급가액+세액.
     */
    @Transactional(readOnly = true)
    /** 거래명세서 발행 대상 학교 목록(거래처 × 기간). 한 장에 한 학교다. */
    public List<SaleRepository.StatementSchool> statementSchools(Long partnerId, LocalDate from,
                                                                 LocalDate to, SalesCategory category) {
        java.util.Collection<SalesCategory> cats = (category != null)
                ? List.of(category)
                : List.of(SalesCategory.SALE, SalesCategory.FREE);
        return saleRepository.statementSchools(partnerId, from, to, cats);
    }

    public TransactionStatementResponse transactionStatement(Long partnerId, LocalDate from, LocalDate to,
                                                             SalesCategory category) {
        return transactionStatement(partnerId, from, to, category, null);
    }

    /**
     * 거래명세서. {@code schoolCode} 를 주면 <b>그 학교 건만</b> 싣는다.
     *
     * <p>★실물 양식의 「학교(원)」 칸은 값이 <b>하나</b>다 — 명세서는 (거래처 × 학교) 단위로
     * 발행된다. 학교를 안 주면 기간의 모든 학교가 한 장에 섞이고 이름은 첫 학교만 찍힌다.
     * 발행 대상은 {@code /sales/transaction-statement/schools} 로 먼저 뽑는다.
     */
    public TransactionStatementResponse transactionStatement(Long partnerId, LocalDate from, LocalDate to,
                                                             SalesCategory category, String schoolCode) {
        Partner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "거래처가 없습니다. id=" + partnerId));
        java.util.Collection<SalesCategory> cats = (category != null)
                ? List.of(category)
                : List.of(SalesCategory.SALE, SalesCategory.FREE);
        List<Sale> sales = saleRepository.statementLines(partnerId, from, to, cats);
        if (schoolCode != null && !schoolCode.isBlank()) {
            String want = schoolCode.trim();
            sales = sales.stream().filter(x -> want.equals(x.getSchoolCode())).toList();
        }

        List<TransactionStatementResponse.Line> priced = new ArrayList<>();
        List<TransactionStatementResponse.Line> free = new ArrayList<>();
        long pQty = 0, pSupply = 0, pTax = 0, fQty = 0;
        int pSeq = 0, fSeq = 0;
        // 실물 양식 거래정보 블록의 "학교(원)" 칸. 한 명세서는 보통 한 학교 건이라 첫 값을 대표로 쓴다.
        String schoolName = null;

        for (Sale s : sales) {
            long supply = s.getSupplyAmount() == null ? 0 : s.getSupplyAmount();
            long tax = s.getTax() == null ? 0 : s.getTax();
            Product p = s.getProduct();
            String label = (p.getCatName() == null || p.getCatName().isEmpty())
                    ? p.getName() : p.getCatName() + " / " + p.getName();
            long unit = (s.getQty() != 0) ? supply / s.getQty() : 0;
            String cat = s.getSalesCategory().name();
            if (schoolName == null && s.getSchoolName() != null && !s.getSchoolName().isBlank()) {
                schoolName = s.getSchoolName();
            }

            if (supply != 0) {
                priced.add(new TransactionStatementResponse.Line(++pSeq, label, p.getCode(), s.getQty(),
                        s.getUnitPrice(), s.getSupplyRate(), unit, supply, tax, cat, s.getMemo()));
                pQty += s.getQty();
                pSupply += supply;
                pTax += tax;
            } else {
                free.add(new TransactionStatementResponse.Line(++fSeq, label, p.getCode(), s.getQty(),
                        s.getUnitPrice(), s.getSupplyRate(), 0, 0, 0, cat, s.getMemo()));
                fQty += s.getQty();
            }
        }

        // 실물 양식 상단 "23 권" = 유가+무가 전체 수량(2026-08-05 거래명세서 실물 대조).
        TransactionStatementResponse.Totals totals = new TransactionStatementResponse.Totals(
                pQty, pSupply, pTax, pSupply + pTax, fQty, pQty + fQty);
        TransactionStatementResponse.Party provider = new TransactionStatementResponse.Party(
                null, supplier.name(), supplier.bizNo(), supplier.bossName(),
                supplier.addr(), supplier.bizStatus(), supplier.bizItem(), supplier.tel());
        TransactionStatementResponse.Party receiver = new TransactionStatementResponse.Party(
                partner.getCode(), partner.getName(), partner.getBizNo(), partner.getBossName(),
                joinAddr(partner.getAddr1(), partner.getAddr2()), partner.getBizStatus(),
                partner.getBizItem(), null);   // 실물 양식은 공급자 전화만 표기

        return new TransactionStatementResponse(from, to, category == null ? null : category.name(),
                provider, receiver, schoolName, priced, free, totals);
    }

    private static String joinAddr(String a1, String a2) {
        if (a1 == null || a1.isEmpty()) {
            return a2;
        }
        return (a2 == null || a2.isEmpty()) ? a1 : a1 + " " + a2;
    }

    /**
     * 도서입출고현황. 근거: 레거시 도서입출고현황.vb(매입+매출 이중장부 종합).
     * 매입측(입고/취소, inventory_txn 원가) + 매출측(출고/반품, sales 공급가) + 정본 재고(종료일 기준)를 상품키로 병합.
     * 취소=매입취소(INBOUND 역분개, 현재 미모델링이면 0), 매출총이익=실판매−실매입.
     * 매입 거래처≠매출 거래처(이중장부)라 거래처 그룹은 제외(도서 단위). catCode·productId 옵션 필터.
     */
    @Transactional(readOnly = true)
    public BookInoutResponse bookInout(LocalDate from, LocalDate to, String catCode, Long productId) {
        // 매출측(상품별 출고/반품) → productId 맵
        Map<Long, BookSalesAgg> salesByProduct = new HashMap<>();
        for (BookSalesAgg a : saleRepository.bookSalesAgg(from, to)) {
            salesByProduct.put(a.getProductId(), a);
        }

        List<BookInoutResponse.Row> rows = new ArrayList<>();
        for (Object[] r : inventoryTxnRepository.bookPurchaseAgg(from, to, catCode, productId)) {
            long pid = ((Number) r[0]).longValue();
            long inQty = num(r[6]);
            long inAmt = num(r[7]);
            long cancQty = num(r[8]);
            long cancAmt = num(r[9]);
            long stockQty = num(r[10]);

            BookSalesAgg s = salesByProduct.get(pid);
            long outQty = (s == null) ? 0 : s.getOutQty();
            long outAmt = (s == null) ? 0 : s.getOutAmt();
            long retQty = (s == null) ? 0 : s.getRetQty();
            long retAmt = (s == null) ? 0 : s.getRetAmt();

            // 움직임·재고 모두 없으면 스킵
            if (inQty == 0 && cancQty == 0 && outQty == 0 && retQty == 0 && stockQty == 0) {
                continue;
            }

            Double cancelRate = (inQty == 0) ? null : round2(cancQty * 100.0 / inQty);
            Double returnRate = (outQty == 0) ? null : round2(retQty * 100.0 / outQty);
            long netPurQty = inQty - cancQty;
            long netPurAmt = inAmt - cancAmt;
            long netSalesQty = outQty - retQty;
            long netSalesAmt = outAmt - retAmt;

            rows.add(new BookInoutResponse.Row(
                    pid, (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    (r[5] == null) ? null : ((Number) r[5]).intValue(),
                    inQty, inAmt, cancQty, cancAmt, cancelRate, netPurQty, netPurAmt,
                    outQty, outAmt, retQty, retAmt, returnRate, netSalesQty, netSalesAmt,
                    stockQty, netSalesAmt - netPurAmt));
        }
        return new BookInoutResponse(from, to, catCode, rows);
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 거래처별 매출대비표(당해 vs 전년 동기간). 근거: 레거시 거래처별_매출대비표.vb.
     * 전년은 salesDate 기준 [from−1년, to−1년](우리 catCode엔 연도 미인코딩 → 날짜 비교). SALE만.
     * groupBy=PARTNER(거래처)/CATEGORY(거래처×분류)/BOOK(거래처×도서). 비율=당해÷전년×100.
     */
    @Transactional(readOnly = true)
    public YoyComparisonResponse yoyComparison(LocalDate from, LocalDate to,
                                               YoyComparisonResponse.GroupBy groupBy,
                                               Long partnerId, String catCode) {
        LocalDate prevFrom = from.minusYears(1);
        LocalDate prevTo = to.minusYears(1);
        List<PartnerProductSalesAgg> curList = saleRepository.salesByPartnerProduct(from, to, partnerId, catCode);
        List<PartnerProductSalesAgg> prevList = saleRepository.salesByPartnerProduct(prevFrom, prevTo, partnerId, catCode);

        Map<String, PartnerProductSalesAgg> labels = new HashMap<>();
        Map<String, long[]> cur = accumulate(curList, groupBy, labels);
        Map<String, long[]> prev = accumulate(prevList, groupBy, labels);

        List<YoyComparisonResponse.Row> rows = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>(cur.keySet());
        keys.addAll(prev.keySet());
        for (String k : keys) {
            long[] c = cur.getOrDefault(k, new long[2]);
            long[] p = prev.getOrDefault(k, new long[2]);
            PartnerProductSalesAgg lb = labels.get(k);
            boolean withCat = groupBy != YoyComparisonResponse.GroupBy.PARTNER;
            boolean withBook = groupBy == YoyComparisonResponse.GroupBy.BOOK;
            rows.add(new YoyComparisonResponse.Row(
                    lb.getPartnerId(), lb.getPartnerCode(), lb.getPartnerName(),
                    withCat ? lb.getCatCode() : null, withCat ? lb.getCatName() : null,
                    withBook ? lb.getBookCode() : null, withBook ? lb.getBookName() : null,
                    c[0], c[1], p[0], p[1],
                    c[0] - p[0], c[1] - p[1],
                    p[0] == 0 ? null : round2(c[0] * 100.0 / p[0]),
                    p[1] == 0 ? null : round2(c[1] * 100.0 / p[1])));
        }
        rows.sort(java.util.Comparator
                .comparing(YoyComparisonResponse.Row::partnerCode, java.util.Comparator.nullsLast(String::compareTo))
                .thenComparing(r -> r.catCode(), java.util.Comparator.nullsLast(String::compareTo))
                .thenComparing(r -> r.bookCode(), java.util.Comparator.nullsLast(String::compareTo)));

        return new YoyComparisonResponse(from, to, prevFrom, prevTo, groupBy, rows);
    }

    /** groupBy 수준으로 (qty,amount) 누적 + 라벨(첫 등장) 수집. */
    private static Map<String, long[]> accumulate(List<PartnerProductSalesAgg> list,
                                                  YoyComparisonResponse.GroupBy groupBy,
                                                  Map<String, PartnerProductSalesAgg> labels) {
        Map<String, long[]> map = new HashMap<>();
        for (PartnerProductSalesAgg a : list) {
            String key = switch (groupBy) {
                case PARTNER -> "P" + a.getPartnerId();
                case CATEGORY -> "P" + a.getPartnerId() + "|C" + (a.getCatCode() == null ? "" : a.getCatCode());
                case BOOK -> "P" + a.getPartnerId() + "|B" + a.getProductId();
            };
            long[] acc = map.computeIfAbsent(key, x -> new long[2]);
            acc[0] += a.getQty();
            acc[1] += a.getAmount();
            labels.putIfAbsent(key, a);
        }
        return map;
    }

    /**
     * 과목별매출현황. 근거: 레거시 과목별매출현황.vb(거래처×분류×도서 수량·반품률).
     * 순매출수량=매출−반품, 반품률(%)=반품÷매출×100(매출0이면 null, 소수 2자리). 취소 제외.
     */
    @Transactional(readOnly = true)
    public CategorySalesResponse categorySales(LocalDate from, LocalDate to, Long partnerId, String catCode) {
        List<CategorySalesAgg> aggs = saleRepository.categorySales(from, to, partnerId, catCode);
        List<CategorySalesResponse.Row> rows = new ArrayList<>(aggs.size());
        for (CategorySalesAgg a : aggs) {
            long sale = a.getSaleQty();
            long ret = a.getReturnQty();
            Double rate = (sale == 0) ? null : Math.round(ret * 100.0 / sale * 100.0) / 100.0;
            rows.add(new CategorySalesResponse.Row(
                    a.getPartnerId(), a.getPartnerCode(), a.getPartnerName(),
                    a.getCatCode(), a.getCatName(), a.getBookCode(), a.getBookName(),
                    sale, ret, sale - ret, a.getTeacherQty(), rate,
                    a.getSaleAmount(), a.getReturnAmount(),
                    a.getSaleAmount() - a.getReturnAmount(), a.getTeacherAmount()));
        }
        return new CategorySalesResponse(from, to, partnerId, catCode, rows);
    }

    /**
     * 회차별 작업현황(구 IC회차별작업현황, 물류). 근거: 레거시 IC회차별작업현황.vb.
     * 분류×도서×회차 단위로 포장구분(개별1/개별2/반별) 수량을 펼쳐 보여준다.
     * 레거시와 동일하게 회차 없는 건(bookReqSeq=0)은 제외한다.
     */
    @Transactional(readOnly = true)
    public List<RoundWorkStatusRow> roundWorkStatus(LocalDate from, LocalDate to, String catCode) {
        List<Object[]> raw = saleRepository.roundWorkStatus(from, to,
                (catCode == null || catCode.isBlank()) ? null : catCode);
        List<RoundWorkStatusRow> rows = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            rows.add(new RoundWorkStatusRow(
                    (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                    (r[4] == null) ? null : ((Number) r[4]).intValue(),
                    ((Number) r[5]).longValue(), ((Number) r[6]).longValue(),
                    ((Number) r[7]).longValue(), ((Number) r[8]).longValue()));
        }
        return rows;
    }
}
