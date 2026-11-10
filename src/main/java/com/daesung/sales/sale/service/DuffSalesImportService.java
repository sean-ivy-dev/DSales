package com.daesung.sales.sale.service;

import com.daesung.sales.closing.service.PeriodLockService;
import com.daesung.sales.common.money.Amounts;
import com.daesung.sales.common.sequence.SequenceService;
import com.daesung.sales.dsre.gateway.DsreGateway;
import com.daesung.sales.dsre.gateway.DuffChargeMode;
import com.daesung.sales.dsre.gateway.DuffSalesRow;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.sale.dto.DuffImportResponse;
import com.daesung.sales.sale.entity.Sale;
import com.daesung.sales.sale.repository.SaleRepository;
import com.daesung.sales.salestype.entity.SalesCategory;
import com.daesung.sales.salestype.entity.ShipmentType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매출일괄등록(14p, 더프). DSRE2 신청내역을 <b>응시인원 기반</b>으로 읽어 매출을 만든다.
 * 근거: 레거시 {@code 매출가져오기.vb} + 정본 14p.
 *
 * <p>★교재({@link BulkSalesImportService})와 별개다. 교재는 {@code tbf_booklist_cnt}의
 * <b>부수</b>를 읽고, 더프는 {@code tbl_request_info}의 <b>인원</b>을 읽는다 —
 * 정본 14p 원문 "더프는 응시인원 기반 종량제 과금(타 상품군의 SET수량 기반과 계산방식 상이)".
 *
 * <h3>청구인원을 고르는 규칙</h3>
 * 정본 14p 원문: "처리구분(Y처리인원/N신청인원)·청구구분(T처리+비처리/S처리만/R등록)에 따라
 * 단가·청구인원 결정, <b>우선순위: 등록→처리비처리→신청(기본값)</b>".
 * 레거시 CASE({@code 매출가져오기.vb:425})가 그 우선순위의 실물이다 —
 * <ol>
 *   <li>청구구분 R → <b>등록인원</b></li>
 *   <li>조회 모드가 처리/비처리로 좁혀져 있으면 → 그 인원</li>
 *   <li>처리구분 N → <b>비처리인원</b></li>
 *   <li>그 외 → <b>처리인원</b></li>
 * </ol>
 *
 * <p>⚠️레거시 화면 안내문은 "처리구분 : Y - 처리인원 / <b>N - 신청인원</b>"이라고 적혀 있지만
 * ({@code 매출가져오기.Designer.vb:470}), 실제 CASE는 N일 때 <b>비처리인원</b>을 쓴다.
 * 안내문이 아니라 코드를 따랐다 — 실제로 청구된 금액이 그쪽이기 때문이다.
 *
 * <h3>레거시와 다르게 한 것</h3>
 * <ul>
 *   <li><b>멱등</b>: 소스키(모드:신청:거래처:학교:과목) UNIQUE로 재실행 시 중복 매출을 막는다.
 *       레거시는 같은 기간을 두 번 등록하면 매출이 두 벌 생긴다.</li>
 *   <li><b>미리보기</b>(dryRun): 쓰기 없이 대상·금액을 먼저 확인한다.</li>
 *   <li><b>월마감 검사</b>: 마감된 달로 매출이 흘러 들어가지 못하게 막는다.</li>
 *   <li><b>write-back 없음</b>: 레거시도 하지 않는다(교재와 달리 state를 건드리지 않는다).
 *       중복 방지는 위 소스키가 맡는다.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "daesung.dsre.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DuffSalesImportService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final DsreGateway dsreGateway;
    private final SequenceService sequenceService;
    private final EntryNumberService entryNumberService;
    private final SaleRepository saleRepository;
    private final PartnerRepository partnerRepository;
    private final ProductRepository productRepository;
    private final PeriodLockService periodLockService;
    private final com.daesung.sales.school.repository.SchoolRepository schoolRepository;

    /** 청구인원 판정 결과 — 몇 명을, 무슨 근거로. */
    record Charge(int inwon, String basis) {}

    /**
     * 기존 쿼리파라미터 경로(미리보기 등). 줄 선택·값 수정 없이 기간 전체를 돈다.
     *
     * <p>★{@code productId} 는 <b>미리보기에도 받아야 한다.</b> 등록(본문 경로)은 담당자가 고른
     * 도서를 줄 전부에 적용하는데, 미리보기에 그 칸이 없으면 <b>무엇을 골라도 목록이 늘
     * "상품이 마스터에 없습니다"로 보인다</b> — DSRE 과목코드와 우리 도서코드는 체계가 달라
     * 자동 매칭이 원래 비는 쪽이다. 그래서 고르기 전과 고른 뒤를 구분할 수 없었고,
     * 실제로 "일괄등록이 전건 실패한다"는 오진이 나왔다(2026-11-09).
     */
    @Transactional
    public DuffImportResponse importSales(LocalDate fromDate, LocalDate toDate,
                                          DuffChargeMode mode, boolean onlyComplete, boolean dryRun) {
        return importSales(fromDate, toDate, mode, onlyComplete, null, dryRun);
    }

    /** 위와 같되 담당자가 고른 도서를 함께 받는다(미리보기에서 결과를 미리 보려면 필요하다). */
    @Transactional
    public DuffImportResponse importSales(LocalDate fromDate, LocalDate toDate,
                                          DuffChargeMode mode, boolean onlyComplete,
                                          Long productId, boolean dryRun) {
        return importSales(new com.daesung.sales.sale.dto.DuffImportRequest(
                fromDate, toDate, mode, onlyComplete, null, productId, null, null,
                null, null, null, null), dryRun);
    }

    /**
     * 매출일괄등록 — <b>레거시 방식</b>(목록을 불러와 사람이 고르고 고쳐서 등록).
     *
     * <p>근거: 발주처 §1 "매출일괄등록은 기존 그대로 유지" + 레거시 {@code 매출가져오기.vb}.
     * 담당자가 ①줄을 체크하고 ②<b>분류·도서를 골라</b> 체크한 줄 전부에 적용하고
     * ③값을 고쳐 등록한다. 예전엔 기간만 받아 전부 자동 매핑해 넣었다 —
     * 그래서 '상품 없음'으로 빠지는 줄을 살릴 길이 없었다.
     */
    @Transactional
    public DuffImportResponse importSales(com.daesung.sales.sale.dto.DuffImportRequest req,
                                          boolean dryRun) {
        LocalDate fromDate = req.fromDate();
        LocalDate toDate = req.toDate();
        boolean onlyComplete = req.onlyCompleteOrFalse();
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();
        DuffChargeMode m = (req.mode() != null) ? req.mode() : DuffChargeMode.ALL;

        // 체크한 줄만 등록 — 비우면 기간 전체(예전 동작).
        java.util.Set<String> picked = req.hasSelection()
                ? new java.util.HashSet<>(req.sourceKeys())   // [] 면 빈 Set — 아무 줄도 안 고른 것이다
                : null;
        // 줄별 수정값은 소스키로 찾는다.
        java.util.Map<String, com.daesung.sales.sale.dto.DuffImportRequest.Override> fixes =
                new java.util.HashMap<>();
        if (req.overrides() != null) {
            req.overrides().stream()
                    .filter(o -> o != null && o.sourceKey() != null)
                    .forEach(o -> fixes.put(o.sourceKey(), o));
        }
        // 담당자가 고른 상품(레거시의 아래 콤보박스). 체크한 줄 **전부에** 적용된다.
        Product chosen = (req.productId() == null) ? null
                : productRepository.findById(req.productId()).orElseThrow(() ->
                        new com.daesung.sales.common.exception.BusinessException(
                                com.daesung.sales.common.exception.ErrorCode.NOT_FOUND,
                                "상품이 없습니다. id=" + req.productId()));

        // ★**신규임의등록**(레거시 CheckBox_Custom) — 체크한 줄의 매출코드·학교 대신
        //   담당자가 고른 거래처·학교로 등록한다. 거래처 매핑이 안 되던 줄을 살리는 경로다.
        Partner customPartner = (req.partnerId() == null) ? null
                : partnerRepository.findById(req.partnerId()).orElseThrow(() ->
                        new com.daesung.sales.common.exception.BusinessException(
                                com.daesung.sales.common.exception.ErrorCode.NOT_FOUND,
                                "거래처가 없습니다. id=" + req.partnerId()));
        // ‼️partnerId 없이 schoolCode 만 오면 **그 칸은 무시된다**(줄의 원래 학교를 쓴다).
        //   조용히 무시하지 않고 거부한다 — 담당자는 학교를 바꿨다고 믿고 있다.
        if (customPartner == null && req.schoolCode() != null && !req.schoolCode().isBlank()) {
            throw new com.daesung.sales.common.exception.BusinessException(
                    com.daesung.sales.common.exception.ErrorCode.INVALID_INPUT,
                    "학교(schoolCode)만으로는 바꿀 수 없습니다. 신규임의등록은 거래처(partnerId)와 "
                            + "함께 보냅니다. 학교 없이 그 특약점으로 넣으려면 schoolCode 를 비우세요.");
        }
        String customSchoolCode = (customPartner == null) ? null : blankToNullStr(req.schoolCode());
        String customSchoolName = (customSchoolCode == null) ? null
                : schoolRepository.findBySchoolCode(customSchoolCode)
                        .map(com.daesung.sales.school.entity.School::getSchoolName).orElse(null);

        // ★★마감 검사는 **줄마다 실제 거래일**로 한다(프론트 실측 2026-10-23 §4①).
        //
        //   ‼️예전엔 미리보기 끝 날짜(to) 하나로만 봤다. 그래서 둘 다 틀렸다 —
        //     ① 거래일이 마감된 달이어도 끝 날짜만 열려 있으면 **들어갔다**
        //     ② 지난달이 마감됐으면 거래일을 오늘로 지정해도 **막혔다**
        //   거래일은 req.salesDate() 로 통일되거나, 비우면 줄마다 신청일(reqDate)이라
        //   한 호출 안에서도 달이 섞인다.
        //
        //   ★거래일을 통일해 보낸 경우에는 **미리 한 번** 본다. 수백 줄을 다 돌고 나서
        //     전부 PERIOD_LOCKED 로 떨어뜨리면 담당자는 뭘 잘못했는지 알기 어렵다.
        //   미리보기(dryRun)는 마감된 달도 들여다볼 수 있어야 하므로 검사하지 않는다.
        if (!dryRun && req.salesDate() != null) {
            periodLockService.assertNotLocked(req.salesDate());
        }

        List<DuffImportResponse.Line> lines = new ArrayList<>();
        // 매출일괄등록도 한 번에 수십~수백 줄이 들어간다 — 묶음번호·순번을 붙인다
        // (프론트 실측 2026-10-16 B-2). 묶음번호는 **가져온 날짜**로 뽑는다(원천 거래일자가 섞여 온다).
        String entryNo = entryNumberService.next(LocalDate.now());
        int entrySeq = 0;
        int imported = 0, skipped = 0, unmapped = 0, zeroInwon = 0, zeroAmount = 0;
        long totalSum = 0;

        // ★★행마다 DB를 치지 않는다 — 먼저 **한 번에** 읽어 둔다.
        //
        //   ‼️예전엔 행마다 ①거래처 ②상품 ③소스키 ④신청번호를 각각 조회했다.
        //     2,549행이면 **1만 번 왕복**이고, 그중 ③④는 미리보기(dryRun)에서도 돌았다
        //     (dryRun 분기가 그 뒤에 있다). 2026-11-09 운영 실측 —
        //     1일 173행 5.2초 / 7일 1,109행 30.4초 / 31일 2,549행 **67.8초** → 게이트웨이 504.
        //     행당 26ms 로 정확히 선형이었고, DSRE 쿼리 자체는 복제본 단독 실행 0.67초,
        //     저장함수 1,000행 0.11초로 **무죄**였다. 병목은 전부 이쪽이었다.
        //   → 쿼리 4회로 줄인다. 판정 결과는 그대로다(조건을 단건 메서드와 같게 맞췄다).
        List<DuffSalesRow> srcRows = dsreGateway.readDuffSales(from, to, onlyComplete, m);

        java.util.Set<String> partnerCodes = new java.util.HashSet<>();
        java.util.Set<String> productCodes = new java.util.HashSet<>();
        java.util.Set<String> sourceKeys = new java.util.HashSet<>();
        java.util.Set<Integer> reqCds = new java.util.HashSet<>();
        for (DuffSalesRow r : srcRows) {
            if (r.machulCode() != null) {
                partnerCodes.add(r.machulCode());
            }
            if (r.dtlCode() != null) {
                productCodes.add(r.dtlCode());
            }
            sourceKeys.add(sourceKeyOf(m, r));
            reqCds.add(r.reqCd());
        }
        java.util.Map<String, Partner> partnerByCode = new java.util.HashMap<>();
        if (!partnerCodes.isEmpty()) {
            partnerRepository.findByCodeIn(partnerCodes)
                    .forEach(p -> partnerByCode.put(p.getCode(), p));
        }
        java.util.Map<String, Product> productByCode = new java.util.HashMap<>();
        if (!productCodes.isEmpty()) {
            productRepository.findByCodeIn(productCodes)
                    .forEach(p -> productByCode.put(p.getCode(), p));
        }
        // ★가변 Set 이어야 한다 — 저장한 줄을 여기에 더해 같은 호출 안의 중복을 막는다(아래 참고).
        java.util.Set<String> takenKeys = new java.util.HashSet<>(
                sourceKeys.isEmpty() ? List.of() : saleRepository.findBulkImportKeysIn(sourceKeys));
        java.util.Set<Integer> takenReqCds = new java.util.HashSet<>(
                reqCds.isEmpty() ? List.<Integer>of() : saleRepository.findReqCdsWithSalesIn(reqCds));

        for (DuffSalesRow row : srcRows) {
            if (!m.accepts(row.procType(), row.chargeType())) {
                continue;   // 이 모드에서는 안 보이는 행(레거시 ifCondition)
            }

            Charge charge = charge(row, m);   // fix 로 덮일 수 있다(아래)

            // 소스키에 모드를 넣는 이유: 같은 신청을 '처리'로 한 번, '비처리'로 한 번
            // 청구하는 계약(청구구분 T)이 실재한다. 모드를 빼면 두 번째가 중복으로 막힌다.
            String sourceKey = sourceKeyOf(m, row);

            // ★체크한 줄만 — 비우면 전체(예전 동작). 미리보기와 **같은 기간·모드**여야 키가 맞는다.
            if (picked != null && !picked.contains(sourceKey)) {
                continue;
            }
            com.daesung.sales.sale.dto.DuffImportRequest.Override fix = fixes.get(sourceKey);
            if (fix != null && fix.inwon() != null) {
                // 담당자가 고친 인원 — 근거를 MANUAL 로 남긴다. 어느 값으로 청구했는지
                // 나중에 되짚을 수 있어야 한다(chargeBasis 가 그 용도다).
                charge = new Charge(fix.inwon(), "MANUAL");
            }

            // 신규임의등록이 있으면 그것이 이긴다(레거시 CheckBox_Custom 과 같다).
            Partner partner = (customPartner != null) ? customPartner
                    : (row.machulCode() == null) ? null : partnerByCode.get(row.machulCode());
            // 담당자가 고른 상품이 있으면 **그걸 쓴다**. 과목코드 매칭은 그다음이다 —
            // DSRE 과목(시행일×영역)과 우리 도서는 1:1이 아니라 자동 매칭이 늘 비는 쪽이다.
            Product product = (chosen != null) ? chosen
                    : (row.dtlCode() == null ? null : productByCode.get(row.dtlCode()));

            // 정가·공급률은 DSRE 값이 우선이고, 없을 때만 도서 마스터로 채운다.
            // ★조용히 0원 매출을 만들지 않기 위해서다 — DSRE의 tbl_product_amt·tbl_school_ref가
            //   비어 있는 신청이 실재한다(복제본 7월 실측: 공급률 미매칭 다수).
            //   레거시는 그대로 0원을 넣지만, 0원 매출은 나중에 "매출 누락"으로만 드러난다.
            //   우리 매출등록(13p)이 이미 쓰는 폴백 규칙과 같게 맞췄다.
            int price = (row.price() > 0) ? row.price() : priceOf(product);
            int rate = (row.supplyRate() > 0) ? row.supplyRate() : rateOf(product);
            // 담당자가 고친 값이 **이긴다**(레거시 '금액수정가능'). 안 보낸 칸은 그대로 둔다.
            if (fix != null) {
                if (fix.unitPrice() != null) {
                    price = fix.unitPrice();
                }
                if (fix.supplyRate() != null) {
                    rate = fix.supplyRate();
                }
            }

            // ‼️총금액은 인원을 넣어 <b>한 번에</b> 계산한다. 단가를 먼저 버림하고 인원을 곱하면
            //   버림이 인원 수만큼 누적된다({@link Amounts} 주석의 999·33·3 사례와 같은 함정).
            //   unit은 화면 표시용이라 따로 낸다 — 총금액을 unit에서 되짚지 말 것.
            Integer discount = (fix != null && fix.discount() != null)
                    ? fix.discount() : discountOrNull(row);
            long total = Amounts.supplyOf(price, rate, charge.inwon(), discount);
            if (fix != null && fix.totalAmount() != null) {
                // ‼️총금액을 직접 준 경우 **계산으로 덮지 않는다** —
                //   담당자가 끝수를 맞춘 금액이 반올림으로 되돌아가면 안 된다.
                total = fix.totalAmount();
            }
            long unit = Amounts.supplyOf(price, rate, 1, discount);
            DuffSalesRow shown = withAmounts(row, price, rate);

            // 인원이 0 이하면 등록하지 않는다(정본 14p "인원 0 미만 건은 매출 등록 제외").
            if (charge.inwon() <= 0) {
                zeroInwon++;
                lines.add(line(shown, charge, unit, total, "ZERO_INWON", null, null, sourceKey));
                continue;
            }
            if (partner == null || product == null) {
                // ‼️예전엔 둘을 합쳐 "UNMAPPED" 하나로만 알려줬다. 그래서 화면은 전부
                //   "거래처 매핑 실패"로 읽었고, 실제 원인(상품 미등록)을 아무도 몰랐다
                //   — 2026-09-18 에 "매핑 키를 machulCode 로 바꿔 달라"는 요청이 온 것이
                //   그 오해다(우리는 이미 machulCode 로 찾고 있었다).
                //   무엇이 없는지 갈라서 알려준다. 담당자가 채울 마스터가 달라진다.
                unmapped++;
                String why = (partner == null && product == null)
                        ? "거래처(매출코드 " + row.machulCode() + ")와 상품(과목코드 "
                                + row.dtlCode() + ")이 모두 마스터에 없습니다"
                        : (partner == null)
                                ? "거래처가 마스터에 없습니다 — 매출코드 " + row.machulCode()
                                : "상품이 마스터에 없습니다 — 과목코드 " + row.dtlCode();
                String code = (partner == null && product == null) ? "UNMAPPED"
                        : (partner == null) ? "UNMAPPED_PARTNER" : "UNMAPPED_PRODUCT";
                lines.add(line(shown, charge, unit, total, code, why, null, sourceKey));
                continue;
            }
            // 폴백까지 쓰고도 금액이 0이면 등록하지 않는다. 단가가 어디에도 없다는 뜻이라
            // 담당자가 마스터를 채워야 할 건이다 — 0원으로 넣어두면 그 사실이 묻힌다.
            if (total <= 0) {
                zeroAmount++;
                lines.add(line(shown, charge, unit, total, "ZERO_AMOUNT", null, null, sourceKey));
                continue;
            }
            if (takenKeys.contains(sourceKey)) {
                skipped++;
                lines.add(line(shown, charge, unit, total, "SKIPPED", null, null, sourceKey));
                continue;
            }
            // ★주문 등록 화면에서 매출까지 함께 세운 신청은 소스키가 없어 위 검사를 통과한다.
            //   그대로 두면 같은 신청이 두 번 매출이 된다(프론트 확인 질문 2026-10-01).
            //   두 경로가 공유하는 값은 신청번호뿐이라 그것으로 막는다.
            if (takenReqCds.contains(row.reqCd())) {
                skipped++;
                lines.add(line(shown, charge, unit, total, "SKIPPED",
                        "이미 매출이 등록된 신청입니다(주문 등록에서 함께 세운 건 포함).", null, sourceKey));
                continue;
            }
            if (dryRun) {
                totalSum += total;
                lines.add(line(shown, charge, unit, total, "PREVIEW", null, null, sourceKey));
                continue;
            }

            LocalDate tradeDate = tradeDateOf(req.salesDate(), row.reqDate(), to);

            // ★그 줄이 귀속될 달이 마감됐으면 **그 줄만** 건너뛴다.
            //   전체를 실패시키지 않는 이유: 신청일이 섞여 들어오는 일괄 작업이라,
            //   오래된 신청 한 줄 때문에 이번 달 것까지 통째로 막히면 쓸 수가 없다.
            //   조용히 빼지도 않는다 — 사유를 실어 보내 화면이 그대로 보여 준다.
            if (periodLockService.isLocked(tradeDate)) {
                skipped++;
                lines.add(line(shown, charge, unit, total, "SKIPPED",
                        "거래일(" + tradeDate + ")이 마감된 달입니다. 마감을 해제하거나 "
                                + "거래일자를 열린 달로 지정하세요.", null, sourceKey));
                continue;
            }

            // 세액은 자동산출하지 않는다(발주처 확정 2026-08-06) — 기본 0 이고,
            // 담당자가 줄별 '세금'을 체크한 줄에만 총금액÷10 이 붙는다(레거시 줄별 세금 칸).
            long tax = taxOf((fix == null) ? null : fix.tax(), total, product,
                    "소스키=" + sourceKey);
            String salesNo = "I-" + to.format(YYYYMMDD) + "-"
                    + sequenceService.next(SequenceService.SEQ_INVOICE);
            Sale sale = Sale.createBulk(salesNo, tradeDate,
                    partner, product, ShipmentType.NORMAL_SHIP, SalesCategory.SALE,
                    price, rate, charge.inwon(),
                    total, tax, total + tax,
                    // 공통 메모를 주면 원천 메모 뒤에 붙인다 — 원천을 지우면 되짚을 수 없다.
                    joinMemo(row.memo(), (fix == null) ? null : fix.memo(), req.memo()), sourceKey);
            sale.applyUploadDetail(row.schoolCode(), row.schoolName(), null);
            sale.linkOrder(row.reqCd());   // 주문 목록이 "무슨 매출이 됐나"를 역참조한다
            sale.applyDiscount(discount);
            sale.applyEntry(entryNo, ++entrySeq);
            saleRepository.save(sale);
            // ‼️**같은 호출 안의 중복**도 막아야 한다. 예전엔 행마다 exists 를 쳐서
            //   저장 직후 조회가 자동 flush 로 방금 넣은 행을 보았다. 묶음 조회로 바꾸면
            //   그 효과가 사라지므로, 여기서 직접 넣어 준다 —
            //   안 하면 한 기간에 같은 소스키·신청번호가 두 줄 들어온 경우 **둘 다 등록된다**.
            takenKeys.add(sourceKey);
            takenReqCds.add(row.reqCd());

            imported++;
            totalSum += total;
            lines.add(line(shown, charge, unit, total, "IMPORTED", null, salesNo, sourceKey));
        }

        // ★**수기 줄**(레거시 '항목 추가') — 원천에 없는 줄을 **같은 묶음**으로 함께 등록한다.
        //   따로 [매출 등록] 화면으로 보내면 다른 묶음번호가 되어 "몇 번째 줄"로 찾는 경로가 끊긴다.
        //   ‼️소스키가 없으므로 중복 검사·write-back 대상이 아니다(원천에 되돌릴 것이 없다).
        if (req.manualLines() != null && !req.manualLines().isEmpty()) {
            if (chosen == null) {
                throw new com.daesung.sales.common.exception.BusinessException(
                        com.daesung.sales.common.exception.ErrorCode.INVALID_INPUT,
                        "수기 줄을 넣으려면 상품(productId)을 함께 골라야 합니다. "
                                + "레거시도 고른 분류·도서를 모든 줄에 적용합니다.");
            }
            int manualSeq = 0;
            for (com.daesung.sales.sale.dto.DuffImportRequest.ManualLine ml : req.manualLines()) {
                manualSeq++;
                if (ml == null) {
                    continue;
                }
                String where = "수기 " + manualSeq + "번째 줄:";
                if (ml.partnerId() == null) {
                    throw new com.daesung.sales.common.exception.BusinessException(
                            com.daesung.sales.common.exception.ErrorCode.INVALID_INPUT,
                            where + " 거래처(partnerId)는 비울 수 없습니다.");
                }
                int inwon = (ml.inwon() == null) ? 0 : ml.inwon();
                if (inwon <= 0) {
                    // 레거시도 '인원이 있는 경우만 등록함' — 0명은 조용히 건너뛴다.
                    zeroInwon++;
                    continue;
                }
                Partner mp = partnerRepository.findById(ml.partnerId()).orElseThrow(() ->
                        new com.daesung.sales.common.exception.BusinessException(
                                com.daesung.sales.common.exception.ErrorCode.NOT_FOUND,
                                where + " 거래처가 없습니다. id=" + ml.partnerId()));
                int mPrice = (ml.unitPrice() != null) ? ml.unitPrice()
                        : (chosen.getPrice() == null ? 0 : chosen.getPrice());
                int mRate = (ml.supplyRate() != null) ? ml.supplyRate()
                        : (chosen.getSupplyRate() == null ? 0 : chosen.getSupplyRate());
                Integer mDiscount = ml.discount();
                long mTotal = (ml.totalAmount() != null) ? ml.totalAmount()
                        : Amounts.supplyOf(mPrice, mRate, inwon, mDiscount);
                LocalDate mDate = (req.salesDate() != null) ? req.salesDate() : to;
                if (!dryRun) {
                    periodLockService.assertNotLocked(mDate);
                }
                long mTax = taxOf(ml.tax(), mTotal, chosen, where);
                String mSchoolCode = blankToNullStr(ml.schoolCode());
                String mSchoolName = (mSchoolCode == null) ? null
                        : schoolRepository.findBySchoolCode(mSchoolCode)
                                .map(com.daesung.sales.school.entity.School::getSchoolName)
                                .orElse(null);
                long mUnit = Amounts.supplyOf(mPrice, mRate, 1, mDiscount);
                if (dryRun) {
                    lines.add(manualLine(mp, mSchoolCode, mSchoolName, chosen, inwon,
                            mPrice, mRate, mDiscount, mUnit, mTotal, "PREVIEW", null, manualSeq));
                    continue;
                }
                String mSalesNo = "I-" + to.format(YYYYMMDD) + "-"
                        + sequenceService.next(SequenceService.SEQ_INVOICE);
                Sale ms = Sale.createBulk(mSalesNo, mDate, mp, chosen,
                        ShipmentType.NORMAL_SHIP, SalesCategory.SALE,
                        mPrice, mRate, inwon, mTotal, mTax, mTotal + mTax,
                        joinMemo(null, ml.memo(), req.memo()), null);
                ms.applyUploadDetail(mSchoolCode, mSchoolName, null);
                ms.applyDiscount(mDiscount);
                ms.applyEntry(entryNo, ++entrySeq);
                saleRepository.save(ms);
                imported++;
                totalSum += mTotal;
                lines.add(manualLine(mp, mSchoolCode, mSchoolName, chosen, inwon,
                        mPrice, mRate, mDiscount, mUnit, mTotal, "IMPORTED", mSalesNo, manualSeq));
            }
        }

        return new DuffImportResponse(dryRun, m.name(), imported, skipped, unmapped, zeroInwon,
                zeroAmount, totalSum, lines);
    }

    /**
     * 수기 줄의 결과 줄. 원천 값이 없으니 신청번호·인원 칸은 0 이고,
     * {@code sourceKey} 는 {@code MANUAL:n} 으로 둔다 — 화면이 "몇 번째 수기 줄"을 가리킬 수 있어야 한다.
     */
    private static DuffImportResponse.Line manualLine(Partner p, String schoolCode, String schoolName,
                                                      Product product, int inwon, int price, int rate,
                                                      Integer discount, long unit, long total,
                                                      String result, String salesNo, int seq) {
        return new DuffImportResponse.Line(
                "MANUAL:" + seq,
                0, null, null, p.getCode(), p.getName(),
                schoolCode, schoolName, product.getGrade(),
                product.getCatCode(), product.getCatName(), product.getCode(), product.getName(),
                null, null, 0,
                0, 0, 0, 0,
                inwon, "MANUAL",
                price, rate, (discount == null) ? 0 : discount, unit, total,
                result, null, salesNo);
    }

    /**
     * 청구인원 판정. 레거시 {@code 매출가져오기.vb:425} CASE와 같은 순서다.
     * 순서가 곧 우선순위라 위에서 걸리면 아래는 보지 않는다.
     */
    static Charge charge(DuffSalesRow row, DuffChargeMode mode) {
        if ("R".equals(row.chargeType())) {
            return new Charge(row.regInwon(), "REGISTERED");
        }
        if (mode == DuffChargeMode.PROCESSED) {
            return new Charge(row.procInwon(), "PROCESSED");
        }
        if (mode == DuffChargeMode.UNPROCESSED) {
            return new Charge(row.unprocInwon(), "UNPROCESSED");
        }
        if ("N".equals(row.procType())) {
            return new Charge(row.unprocInwon(), "UNPROCESSED");
        }
        return new Charge(row.procInwon(), "PROCESSED");
    }

    /** 도서 마스터 정가(없으면 0). DSRE에 정가가 없을 때만 쓰는 폴백. */
    private static int priceOf(Product product) {
        return (product == null || product.getPrice() == null) ? 0 : product.getPrice();
    }

    /** 도서 마스터 공급률(없으면 0). DSRE에 공급률이 없을 때만 쓰는 폴백. */
    /**
     * 멱등 소스키. <b>한 곳에만 둔다</b> — 묶음 조회(루프 전)와 줄 판정(루프 안)이
     * 같은 값을 써야 한다. 두 군데서 조립하면 한쪽만 고쳐져 멱등이 조용히 깨진다.
     *
     * <p>모드를 넣는 이유: 같은 신청을 '처리'로 한 번, '비처리'로 한 번 청구하는
     * 계약(청구구분 T)이 실재한다. 모드를 빼면 두 번째가 중복으로 막힌다.
     */
    private static String sourceKeyOf(DuffChargeMode mode, DuffSalesRow row) {
        return "DUFF:" + mode.name() + ":" + row.reqCd() + ":" + row.custCode()
                + ":" + row.schoolCode() + ":" + row.dtlCode();
    }

    private static int rateOf(Product product) {
        return (product == null || product.getSupplyRate() == null) ? 0 : product.getSupplyRate();
    }

    /**
     * 화면에 보여줄 정가·공급률을 폴백 적용 후 값으로 바꾼 사본.
     * 계산에 쓴 값과 화면에 찍히는 값이 다르면 담당자가 금액을 검산할 수 없다.
     */
    private static DuffSalesRow withAmounts(DuffSalesRow r, int price, int rate) {
        return new DuffSalesRow(r.reqCd(), r.reqDate(), r.machulCode(), r.custCode(),
                r.custName(), r.custFullName(), r.cityName(), r.schoolCode(), r.schoolName(),
                r.grade(), r.prodCode(), r.prodName(), r.dtlCode(), r.dtlName(), r.subjectCnt(),
                r.procType(), r.chargeType(), r.reqInwon(), r.procInwon(), r.unprocInwon(),
                r.regInwon(), price, rate, r.discount(), r.memo());
    }

    /**
     * 할인액. 0이면 null로 바꿔 넘긴다 — {@link Amounts}는 "할인액이 있으면 공급률 대신"이라는
     * 규칙이라 0을 그대로 주면 "할인 0원 적용"과 "할인 없음"이 구분되지 않는다.
     */
    private static Integer discountOrNull(DuffSalesRow row) {
        return (row.discount() > 0) ? row.discount() : null;
    }

    /**
     * 그 줄의 <b>거래일자</b> — 매출이 귀속될 날이고, <b>마감 검사의 기준</b>이다.
     *
     * <ul>
     *   <li>담당자가 거래일자를 지정했으면 그 날로 통일한다.</li>
     *   <li>비우면 <b>신청일 기준</b>(레거시 '신청일기준' 체크박스). 줄마다 다르다.</li>
     *   <li>신청일도 없으면 조회 끝 날짜로 떨어진다.</li>
     * </ul>
     *
     * <p>‼️<b>별도 메서드로 둔 이유</b>: 마감 검사가 예전엔 조회 끝 날짜(to) 하나만 봐서,
     * 거래일이 마감된 달이어도 끝 날짜만 열려 있으면 들어갔다(프론트 실측 2026-10-23 §4①).
     * DSRE 연동은 CI에서 꺼져 있어 이 경로를 통합테스트로 못 짚는다 —
     * 규칙만이라도 순수 함수로 꺼내 단위테스트로 고정한다.
     */
    static LocalDate tradeDateOf(LocalDate specified, LocalDate reqDate, LocalDate to) {
        if (specified != null) {
            return specified;
        }
        return (reqDate != null) ? reqDate : to;
    }

    private static String blankToNullStr(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    /**
     * 줄별 '세금' 체크 → 세액. 레거시 {@code CLng(총금액 / 10)} 그대로(버림).
     *
     * <p>★세액은 자동산출하지 않는다는 발주처 확정(2026-08-06)과 어긋나지 않는다 —
     * 담당자가 <b>체크한 줄에만</b> 붙는 수동 입력이다. 미체크는 0 이다.
     *
     * <p>‼️<b>면세 상품에 체크하면 거부</b>한다. 조용히 0 으로 무시하면 담당자는
     * 세액이 붙었다고 믿는다(매출등록 쪽과 같은 규율).
     */
    static long taxOf(Boolean checked, long total, Product product, String where) {
        if (!Boolean.TRUE.equals(checked)) {
            return 0L;
        }
        if (product != null && product.isTaxFree()) {
            throw new com.daesung.sales.common.exception.BusinessException(
                    com.daesung.sales.common.exception.ErrorCode.INVALID_INPUT,
                    "면세 상품에는 세액을 넣을 수 없습니다. " + where
                            + " 상품=" + product.getCode() + " / 세금 체크를 해제하세요.");
        }
        return total / 10L;
    }

    private static DuffImportResponse.Line line(DuffSalesRow r, Charge c, long unit, long total,
                                                String result, String reason, String salesNo,
                                                String sourceKey) {
        return new DuffImportResponse.Line(
                sourceKey,
                r.reqCd(), r.reqDate(), r.machulCode(), r.custCode(), r.custName(),
                r.schoolCode(), r.schoolName(), r.grade(),
                r.prodCode(), r.prodName(), r.dtlCode(), r.dtlName(),
                r.procType(), r.chargeType(), r.subjectCnt(),
                r.reqInwon(), r.procInwon(), r.unprocInwon(), r.regInwon(),
                c.inwon(), c.basis(),
                r.price(), r.supplyRate(), r.discount(), unit, total, result, reason, salesNo);
    }

    /** 원천 메모 + 담당자 공통 메모. 원천을 지우지 않는다 — 지우면 어느 신청이었는지 되짚을 수 없다. */
    /**
     * 저장할 메모 — {@code 원천(DSRE) / 줄별 / 공통} 순으로 이어 붙인다.
     *
     * <p>★<b>원천을 지우지 않는다.</b> 그 줄이 왜 그렇게 들어왔는지 되짚는 유일한 단서다.
     * <p>줄별 메모는 프론트 실측 2026-10-23 §4② — "레거시는 공통 메모와 별개로
     * 줄마다 메모 칸이 있었다".
     */
    static String joinMemo(String rowMemo, String lineMemo, String common) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{rowMemo, lineMemo, common}) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(part.trim());
            }
        }
        return (sb.length() == 0) ? null : sb.toString();
    }
}
