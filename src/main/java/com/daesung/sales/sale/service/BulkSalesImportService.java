package com.daesung.sales.sale.service;

import com.daesung.sales.dsre.gateway.BooklistImportRow;
import com.daesung.sales.common.sequence.SequenceService;
import com.daesung.sales.dsre.gateway.DsreGateway;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.sale.dto.BulkImportResponse;
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
 * 매출일괄등록(DSRE 교재). DSRE2에서 미처리(state='A') 읽어 매출 생성 + write-back(state='T').
 * 근거: 매출일괄등록_교재.vb. daesung.dsre.enabled=true일 때만.
 *
 * ★안전장치(레거시 결함 보완):
 *  - dryRun 미리보기(쓰기 없음)로 대상 확인 후 실행.
 *  - 소스키(req:lst:dtl:구분) UNIQUE 멱등 — write-back 실패해도 재실행 시 스킵(중복 매출 방지).
 *  - 매출 생성(Postgres) 후 write-back(MySQL). 분산트랜잭션 아님 → 멱등으로 방어.
 */
@Service
@ConditionalOnProperty(name = "daesung.dsre.enabled", havingValue = "true")
@RequiredArgsConstructor
public class BulkSalesImportService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final DsreGateway dsreGateway;
    private final SequenceService sequenceService;
    private final EntryNumberService entryNumberService;
    private final SaleRepository saleRepository;
    private final PartnerRepository partnerRepository;
    private final ProductRepository productRepository;
    private final com.daesung.sales.closing.service.PeriodLockService periodLockService;

    /** 구분: M(매출)·J(증정)·B(무상) → 출고유형/회계구분/공급률/수량. */
    private record Kind(String reqGn, ShipmentType shipmentType, SalesCategory category, int rate, int qty) {}

    @Transactional
    public BulkImportResponse importSales(LocalDate fromDate, LocalDate toDate, boolean dryRun) {
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();

        // 월마감 잠금 횡단검사(감사 결함 수정): 실제 적재(dryRun=false)만 — 마감월 매출 유입 차단.
        if (!dryRun) {
            periodLockService.assertNotLocked(to);
        }

        List<BulkImportResponse.Line> lines = new ArrayList<>();
        // 매출일괄등록도 한 번에 수십~수백 줄이 들어간다 — 묶음번호·순번을 붙인다
        // (프론트 실측 2026-10-16 B-2). 묶음번호는 **가져온 날짜**로 뽑는다(원천 거래일자가 섞여 온다).
        String entryNo = entryNumberService.next(LocalDate.now());
        int entrySeq = 0;
        int imported = 0, skipped = 0, unmapped = 0;

        // ★행마다 DB를 치지 않는다 — 더프 쪽(DuffSalesImportService)과 같은 결함이었다.
        //   한 행에 구분 3종(M·J·B)이 붙어 exists 가 행×3 까지 돌았다. 쿼리 4회로 줄인다.
        List<BooklistImportRow> srcRows = dsreGateway.readPendingBooklist(from, to);
        java.util.Set<String> custCodes = new java.util.HashSet<>();
        java.util.Set<String> prodCodes = new java.util.HashSet<>();
        java.util.Set<String> allKeys = new java.util.HashSet<>();
        java.util.Set<Integer> allReqCds = new java.util.HashSet<>();
        for (BooklistImportRow r : srcRows) {
            if (r.custCode() != null) {
                custCodes.add(r.custCode());
            }
            prodCodes.add(r.lstCd() + r.dtlCd());
            allReqCds.add(r.reqCd());
            for (String gn : List.of("M", "J", "B")) {
                allKeys.add(r.reqCd() + ":" + r.lstCd() + ":" + r.dtlCd() + ":" + gn);
            }
        }
        java.util.Map<String, Partner> partnerByCode = new java.util.HashMap<>();
        if (!custCodes.isEmpty()) {
            partnerRepository.findByCodeIn(custCodes).forEach(p -> partnerByCode.put(p.getCode(), p));
        }
        java.util.Map<String, Product> productByCode = new java.util.HashMap<>();
        if (!prodCodes.isEmpty()) {
            productRepository.findByCodeIn(prodCodes).forEach(p -> productByCode.put(p.getCode(), p));
        }
        // 가변 Set — 저장한 줄을 더해 같은 호출 안의 중복을 막는다(예전엔 저장 직후 flush 가 그 일을 했다).
        java.util.Set<String> takenKeys = new java.util.HashSet<>(
                allKeys.isEmpty() ? List.of() : saleRepository.findBulkImportKeysIn(allKeys));
        java.util.Set<Integer> takenReqCds = new java.util.HashSet<>(
                allReqCds.isEmpty() ? List.<Integer>of() : saleRepository.findReqCdsWithSalesIn(allReqCds));

        for (BooklistImportRow row : srcRows) {
            // 거래처(machul_cd)·도서(lst+dtl) → 우리 마스터 매핑
            Partner partner = (row.custCode() == null) ? null : partnerByCode.get(row.custCode());
            String productCode = row.lstCd() + row.dtlCd();
            Product product = productByCode.get(productCode);

            boolean rowWritten = false;
            for (Kind k : List.of(
                    new Kind("M", ShipmentType.NORMAL_SHIP, SalesCategory.SALE, row.saleRate(), row.saleQty()),
                    new Kind("J", ShipmentType.GIFT, SalesCategory.FREE, row.giftRate(), row.giftQty()),
                    new Kind("B", ShipmentType.TEACHER_USE, SalesCategory.FREE, row.freeRate(), row.freeQty()))) {
                if (k.qty() <= 0) {
                    continue; // 수량 0 구분은 스킵
                }
                String sourceKey = row.reqCd() + ":" + row.lstCd() + ":" + row.dtlCd() + ":" + k.reqGn();
                long supply = com.daesung.sales.common.money.Amounts.supplyOf(row.price(), k.rate(), k.qty());

                if (partner == null || product == null) {
                    unmapped++;
                    lines.add(line(sourceKey, k, row, k.qty(), supply, "UNMAPPED", null));
                    continue;
                }
                // ★주문 등록 화면에서 매출까지 함께 세운 신청은 소스키가 없어 아래 검사를 통과한다.
                //   신청번호로 한 번 더 막는다(프론트 확인 질문 2026-10-01).
                //
                // ‼️**잠복 결함(2026-11-09 확인, 지금은 동작을 바꾸지 않음)**: 이 검사가
                //   **같은 행의 뒤 구분까지 막는다.** 한 신청에 M(매출)·J(증정)·B(무상)가 함께
                //   있으면 M 이 등록된 직후 신청번호가 잡혀 **J·B 가 SKIPPED** 된다
                //   (`Sale` 은 IDENTITY 라 save 가 곧 INSERT 이고, 예전 코드도 그 뒤 exists 가
                //   auto-flush 로 방금 넣은 행을 봤다 — 즉 **묶음 조회로 바꿔서 생긴 일이 아니다**).
                //   소스키 검사만으로 중복은 이미 막히므로 이 검사는 **신청번호가 다른 경로로
                //   이미 매출이 된 경우**에만 걸려야 한다. 고치면 등록되는 줄 수가 달라지므로
                //   발주처 확인 후 별건으로 처리한다.
                if (takenReqCds.contains(row.reqCd())) {
                    skipped++;
                    lines.add(line(sourceKey, k, row, k.qty(), supply, "SKIPPED", null));
                    continue;
                }
                if (takenKeys.contains(sourceKey)) {
                    skipped++;
                    lines.add(line(sourceKey, k, row, k.qty(), supply, "SKIPPED", null));
                    rowWritten = true; // 이미 등록된 적 있음 → write-back은 시도
                    continue;
                }
                if (dryRun) {
                    lines.add(line(sourceKey, k, row, k.qty(), supply, "PREVIEW", null));
                    continue;
                }
                // DSRE 작업리스트에 세액 항목이 없다 → 0. 세액은 자동산출하지 않는다(발주처 확정).
                long tax = 0L;
                String salesNo = "I-" + to.format(YYYYMMDD) + "-" + sequenceService.next(SequenceService.SEQ_INVOICE);
                Sale sale = Sale.createBulk(salesNo, to, partner, product,
                        k.shipmentType(), k.category(), row.price(), k.rate(), k.qty(),
                        supply, tax, supply + tax, row.memo(), sourceKey);
                sale.linkOrder(row.reqCd());   // 주문 목록이 "무슨 매출이 됐나"를 역참조한다
                sale.applyEntry(entryNo, ++entrySeq);
                saleRepository.save(sale);
                // 같은 호출 안의 중복 방지(위 '가변 Set' 주석 참고).
                takenKeys.add(sourceKey);
                takenReqCds.add(row.reqCd());
                imported++;
                rowWritten = true;
                lines.add(line(sourceKey, k, row, k.qty(), supply, "IMPORTED", salesNo));
            }

            // write-back: 해당 도서행 처리 후 state='T'(중복방지). dryRun은 안 함.
            if (!dryRun && rowWritten) {
                dsreGateway.markBooklistDone(row.reqCd(), row.lstCd(), row.dtlCd());
            }
        }
        return new BulkImportResponse(dryRun, imported, skipped, unmapped, lines);
    }

    /**
     * ‼️{@code kind} 는 <b>출고유형</b>(NORMAL_SHIP/GIFT/TEACHER_USE)이다 — 회계구분이 아니다.
     * 예전엔 {@link SalesCategory} 를 실어 보내서 <b>증정(J)과 교사용(B)이 둘 다 {@code FREE}</b> 로
     * 뭉개졌고, 화면에서 구분할 길이 소스키 끝글자뿐이었다(프론트 실측 2026-10-22).
     * DTO 설명은 처음부터 GIFT/TEACHER_USE 라고 적혀 있었다 — 문서와 코드가 갈려 있었다.
     */
    private static BulkImportResponse.Line line(String key, Kind kind, BooklistImportRow row,
                                                int qty, long supply, String result, String salesNo) {
        return new BulkImportResponse.Line(key, kind.shipmentType().name(), kind.category().name(),
                row.custName(), row.bookName(), qty, supply, result, salesNo);
    }
}
