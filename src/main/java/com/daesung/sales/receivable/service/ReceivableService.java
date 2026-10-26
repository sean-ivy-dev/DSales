package com.daesung.sales.receivable.service;

import com.daesung.sales.closing.service.PeriodLockService;
import com.daesung.sales.common.sequence.SequenceService;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.product.entity.MajorCategory;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.receivable.dto.ArLedgerResponse;
import com.daesung.sales.receivable.dto.ArStatusResponse;
import com.daesung.sales.receivable.dto.CarryforwardResult;
import com.daesung.sales.receivable.dto.CollectionRequest;
import com.daesung.sales.receivable.dto.CollectionLedgerResponse;
import com.daesung.sales.receivable.dto.CollectionResponse;
import com.daesung.sales.receivable.dto.DuffLedgerResponse;
import com.daesung.sales.receivable.entity.Collection;
import com.daesung.sales.receivable.entity.CollectionType;
import com.daesung.sales.receivable.entity.ReceivableCarryforward;
import com.daesung.sales.receivable.repository.CollectionRepository;
import com.daesung.sales.receivable.repository.ReceivableCarryforwardRepository;
import com.daesung.sales.sale.entity.Sale;
import com.daesung.sales.sale.repository.SaleRepository;
import com.daesung.sales.salestype.entity.SalesCategory;
import com.daesung.sales.salestype.entity.ShipmentType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채권/수금 도메인. 근거: 레거시 수금등록/외상매출현황조회 + 기획서[마감관리].
 *  - 수금 등록/조회, 이월 스냅샷 생성(idempotent), 미수금 현황(잔액=이월+채권발생−수금, 담보비율).
 * ★ 레거시의 "조회 부수효과 이월생성" 안티패턴 제거 — 이월은 명시 API로만 생성.
 */
@Service
@RequiredArgsConstructor
public class ReceivableService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final CollectionRepository collectionRepository;
    private final SequenceService sequenceService;
    private final ReceivableCarryforwardRepository carryforwardRepository;
    private final SaleRepository saleRepository;
    private final com.daesung.sales.inventory.repository.InventoryTxnRepository inventoryTxnRepository;
    private final PartnerRepository partnerRepository;
    private final PeriodLockService periodLockService;
    private final com.daesung.sales.common.audit.CurrentAuditor currentAuditor;

    /** 수금 등록. 수금번호(C) 채번. 어음정보는 유형=어음일 때만 저장(엔티티에서 처리). */
    @Transactional
    public CollectionResponse registerCollection(CollectionRequest req) {
        return CollectionResponse.from(registerCollectionEntity(req));
    }

    /** 수금 한 줄 저장. 묶음 등록도 이걸 쓴다 — 규칙이 둘이면 경로에 따라 결과가 달라진다. */
    private Collection registerCollectionEntity(CollectionRequest req) {
        periodLockService.assertNotLocked(req.collDate());
        Partner partner = partnerRepository.findById(req.partnerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "거래처가 없습니다. id=" + req.partnerId()));
        String collNo = "C-" + req.collDate().format(YYYYMMDD) + "-" + sequenceService.next(SequenceService.SEQ_COLLECTION);
        return collectionRepository.save(Collection.create(
                collNo, req.collDate(), req.writeDate(), partner, req.collKind(), req.collType(), req.collAmt(),
                req.promissoryNo(), req.promissoryDue(), req.bankName(), req.branchName(), req.memo()));
    }

    /**
     * 수금 <b>묶음 등록</b>(현장 피드백 2026-10-14 B-5) — 여러 줄을 한 전표코드로 묶는다.
     *
     * <p>★줄마다 수금번호(C-)는 <b>그대로 따로</b> 채번한다. 개별 수정·삭제가 그 번호로 되기 때문이다.
     * 전표코드(V-)는 그 위에 얹는 묶음이다.
     *
     * <p>★<b>한 트랜잭션이다.</b> 한 줄이 실패하면 전부 되돌린다 —
     * 같은 입금을 쪼갠 줄들이라 일부만 남으면 합계가 입금액과 안 맞는다.
     * (매출 일괄등록처럼 "되는 것만 넣기"로 하면 안 되는 이유다.)
     */
    @Transactional
    public List<CollectionResponse> registerCollections(
            com.daesung.sales.receivable.dto.CollectionBatchRequest req) {
        // 전표코드는 **첫 줄의 수금일자**로 한 번만 만든다 — 한 입금의 날짜다.
        String voucherNo = "V-" + req.lines().get(0).collDate().format(YYYYMMDD) + "-"
                + sequenceService.next(SequenceService.SEQ_COLLECTION_VOUCHER);

        List<CollectionResponse> out = new ArrayList<>(req.lines().size());
        for (CollectionRequest line : req.lines()) {
            Collection c = registerCollectionEntity(line);
            c.applyVoucher(voucherNo);
            out.add(CollectionResponse.from(c));
        }
        return out;
    }

    /**
     * 조회기준 정규화. 미지정이면 수금일자(COLL).
     *
     * <p>‼️모르는 값은 거부한다. 조용히 기본값으로 넘기면 담당자는 기장일자로 본다고 믿는데
     * 실제로는 수금일자로 잘린 목록을 보게 된다 — 숫자가 달라지는데 오류가 안 난다.
     */
    private static String normalizeBasis(String raw) {
        if (raw == null || raw.isBlank()) {
            return "COLL";
        }
        // ‼️대소문자 변환으로 맞추지 않는다 — 조회 범위를 가르는 값이라
        //   로케일에 따라 다른 문자열이 같아지면 안 된다(정적분석 IMPROPER_UNICODE).
        String v = raw.trim();
        if ("COLL".equals(v) || "coll".equals(v)) {
            return "COLL";
        }
        if ("WRITE".equals(v) || "write".equals(v)) {
            return "WRITE";
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT,
                "알 수 없는 조회기준입니다: " + raw + " (COLL=수금일자 / WRITE=기장일자)");
    }

    /** 수금 목록 조회. */
    @Transactional(readOnly = true)
    public PageResponse<CollectionResponse> searchCollections(LocalDate from, LocalDate to,
                                                              Long partnerId, String collKind,
                                                              CollectionType collType, String keyword,
                                                              String dateBasis, Pageable pageable) {
        String kind = (collKind == null || collKind.isBlank()) ? null : collKind.trim();
        String kw = com.daesung.sales.common.query.Keywords.norm(keyword);
        return PageResponse.of(collectionRepository
                .search(from, to, partnerId, kind, collType, normalizeBasis(dateBasis),
                        (kw == null) ? null : "%" + kw + "%", pageable)
                .map(CollectionResponse::from));
    }

    /**
     * 수금 수정(23p "CRUD 전체"). 수금번호·거래처는 불변 —
     * 거래처를 옮기면 두 거래처의 채권 잔액이 동시에 틀어진다(취소 후 재등록이 맞다).
     *
     * <p><b>바뀐 날짜와 원래 날짜 양쪽</b>의 월마감을 본다. 옮겨 갈 달만 검사하면
     * 마감된 달에서 열린 달로 빼내는 길이 열려, 마감 후에도 그 달 금액을 바꿀 수 있게 된다.
     */
    @Transactional
    public CollectionResponse updateCollection(Long id, CollectionRequest req) {
        Collection c = getCollectionOrThrow(id);
        periodLockService.assertNotLocked(c.getCollDate());     // 원래 귀속 월
        periodLockService.assertNotLocked(req.collDate());      // 옮겨 갈 월
        c.update(req.collDate(), req.writeDate(), req.collKind(), req.collType(), req.collAmt(),
                req.promissoryNo(), req.promissoryDue(), req.bankName(), req.branchName(), req.memo());
        return CollectionResponse.from(c);
    }

    /**
     * 수금 삭제(논리삭제). 레거시는 이 기능이 주석 처리돼 막혀 있었으나(수금관리.vb:356)
     * 정본 23p가 "CRUD 전체 가능 화면"을 요구한다.
     *
     * <p>물리삭제하지 않는 이유: 수금은 돈이 들어온 기록이라 지우면 그만큼 <b>채권 잔액이 늘어난다</b>.
     * 누가 언제 지웠는지 남지 않으면 잔액이 왜 달라졌는지 설명할 수 없다.
     */
    @Transactional
    public void deleteCollection(Long id) {
        Collection c = getCollectionOrThrow(id);
        periodLockService.assertNotLocked(c.getCollDate());
        c.markDeleted(currentAuditor.username());
    }

    private Collection getCollectionOrThrow(Long id) {
        return collectionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "수금이 없습니다. id=" + id));
    }

    /**
     * 수금관리(23p) 조회 — 조회대상기준 3종 + 소계. 근거: 레거시 수금관리.vb:146~227.
     *
     * <p>레거시가 붙이는 소계는 정본이 적은 '일계'보다 촘촘하다 —
     * 날짜 기준은 <b>일계·월계·누계</b> 3단, 거래처 기준은 <b>소계(거래처코드 첫 글자 그룹)·누계</b>다.
     * 그대로 옮긴다.
     *
     * <p>페이징하지 않는다. 소계는 앞뒤 행이 다 있어야 성립해서 잘린 지점의 소계가 틀린 값이 된다.
     */
    @Transactional(readOnly = true)
    public CollectionLedgerResponse collectionLedger(CollectionLedgerResponse.Basis basis,
                                                     LocalDate fromDate, LocalDate toDate,
                                                     Long partnerId, String collKind,
                                                     CollectionType collType) {
        CollectionLedgerResponse.Basis b =
                (basis == null) ? CollectionLedgerResponse.Basis.WRITE_DATE : basis;   // 레거시 기본 선택
        boolean byWrite = (b == CollectionLedgerResponse.Basis.WRITE_DATE);
        boolean byPartner = (b == CollectionLedgerResponse.Basis.PARTNER);
        String kind = (collKind == null || collKind.isBlank()) ? null : collKind.trim();

        List<Collection> src = collectionRepository.ledger(
                fromDate, toDate, partnerId, kind, collType, byWrite, byPartner);

        List<CollectionLedgerResponse.Row> rows = new ArrayList<>();
        long running = 0;
        long dayOrGroup = 0;
        long month = 0;
        String prevDay = null;
        String prevKey = null;

        for (Collection c : src) {
            String day = String.valueOf(byWrite ? c.getWriteDate() : c.getCollDate());
            // 거래처 기준의 그룹 키는 거래처코드 첫 글자다(레거시 custCode.Substring(0,1)).
            String key = byPartner ? groupOf(c.getPartner().getCode()) : day;

            if (prevKey != null && !prevKey.equals(key)) {
                if (byPartner) {
                    rows.add(CollectionLedgerResponse.Row.subtotal("GROUP_SUBTOTAL", "소 계", dayOrGroup));
                    rows.add(CollectionLedgerResponse.Row.subtotal("RUNNING_TOTAL", "누 계", running));
                } else {
                    rows.add(CollectionLedgerResponse.Row.subtotal("DAY_SUBTOTAL", "일 계", dayOrGroup));
                    if (!month(prevDay).equals(month(day))) {   // 달이 바뀌면 월계·누계도(레거시 순서 그대로)
                        rows.add(CollectionLedgerResponse.Row.subtotal("MONTH_SUBTOTAL", "월 계", month));
                        rows.add(CollectionLedgerResponse.Row.subtotal("RUNNING_TOTAL", "누 계", running));
                        month = 0;
                    }
                }
                dayOrGroup = 0;
            }

            rows.add(detailRow(c));
            dayOrGroup += c.getCollAmt();
            month += c.getCollAmt();
            running += c.getCollAmt();
            prevDay = day;
            prevKey = key;
        }

        if (prevKey != null) {
            if (byPartner) {
                rows.add(CollectionLedgerResponse.Row.subtotal("GROUP_SUBTOTAL", "소 계", dayOrGroup));
            } else {
                rows.add(CollectionLedgerResponse.Row.subtotal("DAY_SUBTOTAL", "일 계", dayOrGroup));
                rows.add(CollectionLedgerResponse.Row.subtotal("MONTH_SUBTOTAL", "월 계", month));
            }
            rows.add(CollectionLedgerResponse.Row.subtotal("RUNNING_TOTAL", "누 계", running));
        }

        return new CollectionLedgerResponse(b, b.label(), fromDate, toDate, rows, running);
    }

    /**
     * 외상매출장 '더프모만'(24p). 근거: 레거시 외상매출장조회.vb:603 {@code Refresh_DataGridView_더프모만()}.
     *
     * <p>정본이 "그리드 컬럼 구조 자체가 완전 전환"이라고 못박아, 기본 장부와 <b>다른 응답</b>으로 낸다.
     * 기본 장부는 채권 러닝밸런스를 보는 화면이고 이쪽은 모의고사 매출 세부라 잔액 개념이 없다.
     *
     * <p>소계는 레거시 {@code GROUP BY rollup(schCode, idx), 거래년월} 그대로
     * <b>학교(원) 계 → 월 계</b> 순으로 붙인다.
     */
    @Transactional(readOnly = true)
    public DuffLedgerResponse duffLedger(Long partnerId, LocalDate fromDate, LocalDate toDate) {
        Partner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "거래처가 없습니다. id=" + partnerId));
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();

        return build(partnerId, partner.getCode(), partner.getName(), partner.getDsreCode(), from, to,
                saleRepository.duffLedgerLines(partnerId, from, to));
    }

    /**
     * 더프모 <b>전 거래처</b> — 거래처 하나당 응답 하나.
     *
     * <p>근거: 레거시 거래처 콤보 0번이 "전체 거래처"이고 <b>그걸 골랐을 때만</b>
     * 「전 거래처 메일 일괄발송」·「PDF 일괄저장」이 켜진다(외상매출장조회.vb:97~106·121).
     * 전체 조회는 부수 기능이 아니라 <b>일괄 발송의 전제</b>다 — 화면이
     * "전체 거래처의 더프모는 아직 서버가 지원하지 않습니다"로 막혀 있던 지점이다.
     *
     * <p>★<b>한 번에 훑고 거래처별로 끊는다.</b> 거래처 수만큼 조회를 돌면 요청이
     * 데이터 건수에 비례해 늘고, 그 사이 데이터가 바뀌면 거래처마다 기준 시점이 달라진다.
     *
     * <p>★매출이 <b>있는</b> 거래처만 나온다. 없는 곳까지 넣으면 0원짜리 명세서가
     * 메일·PDF로 나가는 사고가 된다(기본 장부의 {@code onlyReportable}과 같은 결).
     */
    @Transactional(readOnly = true)
    public List<DuffLedgerResponse> duffLedgerAll(LocalDate fromDate, LocalDate toDate) {
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();

        List<DuffLedgerResponse> out = new ArrayList<>();
        List<Object[]> chunk = new ArrayList<>();
        Long curId = null;
        String curCode = null;
        String curName = null;
        String curDsre = null;

        for (Object[] r : saleRepository.duffLedgerLines(null, from, to)) {
            Long pid = ((Number) r[9]).longValue();
            if (curId != null && !curId.equals(pid)) {
                out.add(build(curId, curCode, curName, curDsre, from, to, chunk));
                chunk = new ArrayList<>();
            }
            curId = pid;
            curCode = str(r[10]);
            curName = str(r[11]);
            curDsre = str(r[12]);
            chunk.add(r);
        }
        if (curId != null) {
            out.add(build(curId, curCode, curName, curDsre, from, to, chunk));
        }
        return out;
    }

    /** 거래처 한 곳의 더프모 원장(명세 + 소계 + 요약). 행은 이미 그 거래처 것만 들어온다. */
    private DuffLedgerResponse build(Long partnerId, String partnerCode, String partnerName,
                                     String partnerDsreCode,
                                     LocalDate from, LocalDate to, List<Object[]> lines) {
        List<DuffLedgerResponse.Row> rows = new ArrayList<>();
        // 처리 구분별 합계 — 레거시는 같은 조건으로 SQL을 한 번 더 떠서(rollup) 만들었지만,
        // 명세를 이미 다 읽고 있으므로 여기서 같이 센다. 두 번 조회하면 그 사이에 데이터가 바뀌어
        // 명세와 요약의 합이 어긋날 수 있다.
        Map<String, long[]> byProc = new TreeMap<>();
        long schoolQty = 0;
        long schoolAmt = 0;
        long monthQty = 0;
        long monthAmt = 0;
        long totalQty = 0;
        long totalAmt = 0;
        String prevMonth = null;
        String prevSchool = null;

        for (Object[] r : lines) {
            LocalDate date = toLocalDate(r[0]);
            String school = str(r[1]);
            String month = (date == null) ? "" : String.format("%04d-%02d", date.getYear(), date.getMonthValue());
            long qty = num(r[7]);
            long amt = num(r[8]);

            // 학교가 바뀌면 학교 계, 달이 바뀌면 월 계까지(레거시 rollup 순서 그대로).
            if (prevSchool != null && !prevSchool.equals(school)) {
                rows.add(DuffLedgerResponse.Row.subtotal("SCHOOL_SUBTOTAL", "학교(원) 계",
                        prevSchool, schoolQty, schoolAmt));
                schoolQty = 0;
                schoolAmt = 0;
            }
            if (prevMonth != null && !prevMonth.equals(month)) {
                rows.add(DuffLedgerResponse.Row.subtotal("MONTH_SUBTOTAL", "월 계", null, monthQty, monthAmt));
                monthQty = 0;
                monthAmt = 0;
            }

            String proc = procLabel(str(r[4]));
            rows.add(new DuffLedgerResponse.Row("DETAIL", null, date, school,
                    str(r[2]), str(r[3]), proc,
                    intOrNull(r[5]), intOrNull(r[6]), qty, amt));

            // ‼️TreeMap 은 null 키를 못 받는다. 미지정은 빈 문자열로 모아 두고
            //   낼 때 다시 null 로 돌린다(아래 summary 조립부).
            long[] acc = byProc.computeIfAbsent((proc == null) ? "" : proc, k -> new long[2]);
            acc[0] += qty;
            acc[1] += amt;

            schoolQty += qty;
            schoolAmt += amt;
            monthQty += qty;
            monthAmt += amt;
            totalQty += qty;
            totalAmt += amt;
            prevSchool = school;
            prevMonth = month;
        }

        if (prevSchool != null) {
            rows.add(DuffLedgerResponse.Row.subtotal("SCHOOL_SUBTOTAL", "학교(원) 계",
                    prevSchool, schoolQty, schoolAmt));
            rows.add(DuffLedgerResponse.Row.subtotal("MONTH_SUBTOTAL", "월 계", null, monthQty, monthAmt));
        }

        List<DuffLedgerResponse.SummaryRow> summary = new ArrayList<>();
        byProc.forEach((proc, acc) -> summary.add(new DuffLedgerResponse.SummaryRow(
                proc.isEmpty() ? null : proc, false, acc[0], acc[1])));
        // 총계는 **명세가 있을 때만** 붙인다. 빈 조회에 0원 총계만 찍히면
        // "0원어치 팔렸다"로 읽혀, 조건이 틀렸다는 사실이 묻힌다.
        if (!summary.isEmpty()) {
            summary.add(new DuffLedgerResponse.SummaryRow(null, true, totalQty, totalAmt));
        }

        // ‼️인자 순서는 DTO 선언 순서(code → dsreCode → name)를 그대로 따라야 한다.
        //   셋 다 String 이라 어긋나도 **컴파일이 통과한다** — 실제로 name 과 dsreCode 를
        //   바꿔 넣어 거래처명 자리에 숫자가 나갔다(프론트 실측 2026-10-26, 100곳 중 90곳).
        return new DuffLedgerResponse(partnerId, partnerCode, partnerDsreCode, partnerName, from, to,
                rows, summary, totalQty, totalAmt);
    }

    /**
     * 성적처리 구분 → 화면 표기. <b>입력된 경우에만</b> 값을 낸다 — 미지정이면 null.
     *
     * <p>‼️예전엔 미지정을 '비처리'로 찍었는데, 이관 자료는 그 칸이 **전부 비어 있어**
     * 실제 처리 여부와 무관하게 전건이 '비처리'로 나갔다(프론트 실측 2026-10-26, 959줄 전부).
     * 없는 사실을 지어내는 쪽이라 비운다 — 화면은 값이 없으면 그 칸을 숨긴다.
     *
     * <p>★발주처가 정한 더프모 양식(10/23 명세 8칸·RESULT 샘플 PDF)에 <b>처리 칸이 없다.</b>
     * 레거시도 분류명에 처리/비처리/SOLID 글자가 있을 때만 칸을 뒀고,
     * 2025년부터는 상품코드에 그 구분이 없다.
     *
     * <p>‼️<b>월별매출액명세서(37p)의 "미지정=비처리"는 그대로다.</b> 그 화면은 고객 양식에
     * 성적처리/비처리 칸이 있어 집계 규칙이 맞다. 여기는 <b>표시</b>만 바꾼 것이고,
     * 그쪽은 SQL 에서 직접 버킷을 나눠 이 메서드를 타지 않는다.
     */
    private static String procLabel(String procType) {
        if ("GRADED".equals(procType)) {
            return "처리";
        }
        if ("UNGRADED".equals(procType)) {
            return "비처리";
        }
        return null;   // 미지정 — 지어내지 않는다
    }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) {
            return null;
        }
        return (o instanceof java.sql.Date d) ? d.toLocalDate() : LocalDate.parse(o.toString());
    }

    private static Integer intOrNull(Object o) {
        return (o == null) ? null : ((Number) o).intValue();
    }

    private static String str(Object o) {
        return (o == null) ? null : o.toString();
    }

    /** 거래처 그룹 = 거래처코드 첫 글자(레거시 custCode.Substring(0,1)). 코드가 비면 빈 그룹. */
    private static String groupOf(String partnerCode) {
        return (partnerCode == null || partnerCode.isEmpty()) ? "" : partnerCode.substring(0, 1);
    }

    /** yyyy-MM. 날짜 문자열(yyyy-MM-dd)에서 잘라 쓴다 — 월계 경계 판정용. */
    private static String month(String day) {
        return (day == null || day.length() < 7) ? "" : day.substring(0, 7);
    }

    private static CollectionLedgerResponse.Row detailRow(Collection c) {
        return new CollectionLedgerResponse.Row("DETAIL", null, c.getId(), c.getCollectionNo(),
                c.getVoucherNo(),
                c.getCollDate(), c.getWriteDate(),
                c.getPartner().getCode(), c.getPartner().getName(),
                c.getCollKind(), c.getCollType(), c.getCollType().label(), c.getCollAmt(),
                c.getPromissoryNo(), c.getPromissoryDue(), c.getBankName(), c.getBranchName(), c.getMemo());
    }

    /**
     * 채권 이월 스냅샷 생성(idempotent). fiscalYear 귀속 이월 = 전년말 채권 잔액.
     * 전년말 잔액 = 전전년 이월 + 전년 채권발생 − 전년 수금. 잔액≠0 거래처만 저장. 기존 연도분 삭제 후 재생성.
     */
    @Transactional
    public CarryforwardResult generateCarryforward(int fiscalYear) {
        int prevYear = fiscalYear - 1;
        LocalDate pyStart = LocalDate.of(prevYear, 1, 1);
        LocalDate pyEnd = LocalDate.of(prevYear, 12, 31);

        Map<Long, Long> carry = toAmountMap(carryforwardRepository.sumByYear(prevYear, null));
        Map<Long, Long> gen = toReceivableGenMap(saleRepository.receivableByPartner(pyStart, pyEnd, null));
        Map<Long, Long> coll = toAmountMap(collectionRepository.sumByPartner(pyStart, pyEnd, null));

        Set<Long> ids = new LinkedHashSet<>();
        ids.addAll(carry.keySet());
        ids.addAll(gen.keySet());
        ids.addAll(coll.keySet());

        // ★재생성 전 값을 떠 둔다 — **무엇이 바뀌었는지** 알려주기 위해서다.
        //   ‼️이미 만든 이월을 다시 만들었는데 값이 달라졌다면, 스냅샷을 뜬 뒤에
        //     전년 거래가 더 들어온 것이다. 그냥 덮으면 담당자는 잔액이 왜 변했는지 모른다
        //     (프론트 실측 2026-10-22 ⑧ "이월을 만든 뒤 전년도 매출이 늦게 들어와도 표시가 없다").
        //   재생성 자체는 막지 않는다 — 막으면 늦게 들어온 거래를 영영 반영할 수 없다.
        Map<Long, Long> before = toAmountMap(carryforwardRepository.sumByYear(fiscalYear, null));

        int deleted = carryforwardRepository.deleteByFiscalYear(fiscalYear);
        carryforwardRepository.flush(); // 삭제 확정 후 삽입(유니크 제약 회피)

        LocalDateTime now = LocalDateTime.now();
        int generated = 0;
        long total = 0;
        Map<Long, Long> made = new HashMap<>();
        for (Long pid : ids) {
            long balance = carry.getOrDefault(pid, 0L) + gen.getOrDefault(pid, 0L) - coll.getOrDefault(pid, 0L);
            if (balance == 0) {
                continue;
            }
            Partner partner = partnerRepository.getReferenceById(pid);
            carryforwardRepository.save(ReceivableCarryforward.create(fiscalYear, partner, balance, now));
            generated++;
            total += balance;
            made.put(pid, balance);
        }

        // 바뀐 거래처 수·금액. 사라진 거래처(이전엔 잔액이 있었는데 이번엔 0)도 '바뀐' 것이다.
        Set<Long> compared = new LinkedHashSet<>(before.keySet());
        compared.addAll(made.keySet());
        int changed = 0;
        long changedAmount = 0;
        for (Long pid : compared) {
            long b = before.getOrDefault(pid, 0L);
            long a = made.getOrDefault(pid, 0L);
            if (b != a) {
                changed++;
                changedAmount += a - b;
            }
        }
        return new CarryforwardResult(fiscalYear, deleted, generated, total, changed, changedAmount);
    }

    /**
     * 미수금 현황. 거래처별 잔액 = 이월(전년말 스냅샷) + 기간 채권발생 − 기간 수금 + 담보비율.
     * 기간 미지정 시 올해 1/1~오늘. 이월은 종료일 연도의 carryforward.
     */
    @Transactional(readOnly = true)
    public ArStatusResponse arStatus(LocalDate fromDate, LocalDate toDate, Long partnerId) {
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();

        // ★기초(opening)는 **시작일 전날까지의 잔액**이다 = 그해 이월 + 1/1~시작 전날 거래.
        //   ‼️예전엔 '그해 이월'만 넣어서, 같은 날짜의 잔액이 **시작일을 바꿀 때마다 달라졌다**
        //     (프론트 실측 2026-10-15 A-1: 1/1 시작 75,268,510 vs 7/1 시작 59,096,620 —
        //      차이가 정확히 1~6월 거래 증가분이었다). 같은 파일의 외상매출장(arLedger)은
        //      balanceAsOf 로 맞게 계산하고 있어, **두 화면이 서로 다른 잔액**을 보여주고 있었다.
        //   ★행마다 balanceAsOf 를 부르지 않는다 — 거래처가 수백이면 질의가 그만큼 는다.
        //     기간 **앞쪽 구간**(1/1~시작 전날)을 한 번에 집계해 이월에 더한다.
        //
        // ★★이월의 연도는 **시작일 기준**이다({@code from.getYear()}).
        //   ‼️예전엔 종료일 연도({@code to.getYear()})를 썼다. 같은 해 안에서는 둘이 같아 안 드러났지만,
        //     **해를 걸친 기간**에서는 이월과 앞 구간이 서로 다른 해를 가리켜 **1년치가 이중으로** 더해졌다
        //     (프론트 실측 2026-10-22: 2025-12-30~2026-01-05 조회 시 이월 365.26억,
        //      맞는 값인 12/29 잔액은 369.76억 — 4.50억 차이. 경고도 없이 틀린 값이 나갔다).
        //     2026 이월은 이미 "2025년말 잔액"인데 거기에 2025년 거래를 또 얹고 있었다.
        //   아래 balanceAsOf 와 같은 규칙이다 — 두 화면이 갈리면 둘 다 못 믿는다.
        int openingYear = from.getYear();
        Map<Long, Long> carry = toAmountMap(carryforwardRepository.sumByYear(openingYear, partnerId));
        Map<Long, long[]> sales = toSalesMap(saleRepository.receivableByPartner(from, to, partnerId));
        Map<Long, Long> coll = toAmountMap(collectionRepository.sumByPartner(from, to, partnerId));

        LocalDate yearStart = LocalDate.of(openingYear, 1, 1);
        Map<Long, long[]> priorSales = Map.of();
        Map<Long, Long> priorColl = Map.of();
        if (from.isAfter(yearStart)) {
            LocalDate priorEnd = from.minusDays(1);
            priorSales = toSalesMap(saleRepository.receivableByPartner(yearStart, priorEnd, partnerId));
            priorColl = toAmountMap(collectionRepository.sumByPartner(yearStart, priorEnd, partnerId));
        }
        Map<Long, List<ArStatusResponse.CategoryBreakdown>> byCat =
                toCategoryMap(saleRepository.receivableByPartnerAndCategory(from, to, partnerId));

        // ★매입(입고)도 그 거래처 원장에 들어간다 — 레거시 외상매출현황조회.vb:676 의 공식이
        //   `이월 + 매출 + 반품 + (입고 + 취소) − 수금` 이다. 빼면 매입처가 "수금만 있는"
        //   거래처로 보인다(프론트 실측 2026-10-20 A-1: 25곳이 매출 0 · 수금 130~197억).
        //   ‼️이월에도 같은 기준을 적용한다 — 안 그러면 연도 사이가 이어지지 않는다(A-2).
        Map<Long, long[]> purchase = toQtyAmountMap(
                inventoryTxnRepository.purchaseByPartner(from, to, partnerId));
        // 앞 구간 조건은 priorSales·priorColl 과 **같아야** 한다 — 하나만 어긋나면 기초가 한쪽으로 쏠린다.
        Map<Long, long[]> priorPurchase = from.isAfter(yearStart) ? toQtyAmountMap(
                inventoryTxnRepository.purchaseByPartner(yearStart, from.minusDays(1), partnerId))
                : Map.of();

        Set<Long> ids = new LinkedHashSet<>();
        ids.addAll(carry.keySet());
        ids.addAll(sales.keySet());
        ids.addAll(coll.keySet());
        // ‼️기간 전에만 거래가 있고 조회 기간엔 없는 거래처도 **잔액이 남아 있으면 보여야** 한다.
        //   안 넣으면 "7월부터 조회"했을 때 상반기에만 거래한 미수 거래처가 목록에서 사라진다.
        ids.addAll(priorSales.keySet());
        ids.addAll(priorColl.keySet());
        // 매입만 있는 거래처(매입처)도 원장에 서야 한다 — 빼면 그 거래처가 목록에서 사라진다.
        ids.addAll(purchase.keySet());
        ids.addAll(priorPurchase.keySet());

        Map<Long, Partner> partners = new HashMap<>();
        partnerRepository.findAllById(ids).forEach(p -> partners.put(p.getId(), p));

        // ★거래처를 **매입처/매출처**로 가른다 — 레거시 대성교재 화면이 그렇게 한다
        //   (외상매출현황조회.vb:650 `CASE WHEN LEFT(거래처코드,1)='A' THEN '매입처' ELSE '매출처'`).
        //   ‼️그 'A 접두어' 규칙은 **쓰지 않는다.** DSTxtBook DB 안에서만 성립하던 규칙이라,
        //     두 법인을 한 DB에 합친 우리 데이터에선 안 맞는다 — A 거래처 425곳 중 267곳이
        //     매출처다(2026-10-22 실측). **입고가 있으면 매입처**로 본다(겹치는 곳 3곳뿐).
        //   ‼️예전엔 '사업자번호가 우리와 같으면 제외'로 A000001 한 곳만 걸렀는데,
        //     그건 레거시에 없는 로직이었다. 레거시는 그 거래처도 **매입처로 그냥 집계**한다.
        List<ArStatusResponse.Row> rows = new ArrayList<>();
        long tOpen = 0, tSale = 0, tRet = 0, tTax = 0, tGen = 0, tColl = 0, tBal = 0, tTeacher = 0, tTeacherQty = 0;
        long tPurchase = 0, tPurchaseQty = 0;
        long tSaleQty = 0, tRetQty = 0;
        List<ArStatusResponse.Row> purchaseRows = new ArrayList<>();
        List<ArStatusResponse.Row> salesRows = new ArrayList<>();
        for (Long pid : ids) {
            Partner p = partners.get(pid);
            if (p == null) {
                continue;
            }
            long[] priorPur = priorPurchase.getOrDefault(pid, EMPTY2);
            long opening = carry.getOrDefault(pid, 0L)
                    + priorSales.getOrDefault(pid, new long[8])[0]
                    + priorPur[1]
                    - priorColl.getOrDefault(pid, 0L);
            // [gen, saleAmt, returnAmt, tax, teacherAmt, teacherQty, saleQty, returnQty]
            long[] s = sales.getOrDefault(pid, new long[8]);
            long collected = coll.getOrDefault(pid, 0L);
            long[] pur = purchase.getOrDefault(pid, EMPTY2);
            long purchasedQty = pur[0], purchased = pur[1];
            long balance = opening + s[0] + purchased - collected;

            Long assureAmount = p.getAssureAmount();
            Double ratio = null;
            String level = null;
            if (assureAmount != null && assureAmount > 0) {
                ratio = Math.round((double) balance / assureAmount * 100 * 10) / 10.0;
                level = ratio >= 100 ? "OVER" : ratio >= 70 ? "WARN" : ratio > 50 ? "WATCH" : "NORMAL";
            }
            // 기간 안에 매입(입고)이 있으면 매입처. 기간 밖 매입만 있는 곳도 매입처로 본다
            // (이월에 그 매입이 녹아 있어서, 매출처로 묶으면 소계가 설명되지 않는다).
            boolean isPurchaser = purchased != 0 || purchasedQty != 0 || priorPur[1] != 0;
            String kind = isPurchaser ? PARTNER_KIND_PURCHASE : PARTNER_KIND_SALES;
            ArStatusResponse.Row row = new ArStatusResponse.Row(pid, p.getCode(), p.getName(), p.getBossName(),
                    kind, opening, s[6], s[1], s[7], s[2], s[3], s[0], purchasedQty, purchased,
                    collected, balance, s[4], s[5],
                    byCat.getOrDefault(pid, List.of()),
                    assureAmount, ratio, p.getAssureExpiry(), p.getAssureNote(), level);
            rows.add(row);
            (isPurchaser ? purchaseRows : salesRows).add(row);

            tOpen += opening; tSale += s[1]; tRet += s[2]; tTax += s[3]; tGen += s[0];
            tColl += collected; tBal += balance; tTeacher += s[4]; tTeacherQty += s[5];
            tPurchase += purchased; tPurchaseQty += purchasedQty;
            tSaleQty += s[6]; tRetQty += s[7];
        }
        rows.sort((a, b) -> a.partnerCode().compareTo(b.partnerCode()));
        ArStatusResponse.Row total = new ArStatusResponse.Row(null, "합계", null, null, null,
                tOpen, tSaleQty, tSale, tRetQty, tRet, tTax, tGen, tPurchaseQty, tPurchase,
                tColl, tBal, tTeacher, tTeacherQty,
                sumCategories(byCat.values()),
                null, null, null, null, null);
        // ★이월 스냅샷이 없는 연도는 **없다고 말한다**. 예전엔 조용히 0으로 나가서,
        //   이월이 안 잡힌 채 "잔액이 맞다"고 읽혔다(프론트 실측: 부산지사 2025 미수 3,646만 원 누락).
        //   숫자를 고치는 게 아니라 — 이월을 자동 생성하면 아무도 모르는 사이에 장부가 생긴다 —
        //   **비어 있다는 사실**을 알린다. 만드는 것은 담당자가 [이월생성]으로 명시적으로 한다.
        List<String> warnings = new ArrayList<>();
        if (carry.isEmpty()) {
            // ★경고도 **기초를 읽은 그 해**를 말해야 한다 — 다른 해를 찍으면 담당자가 엉뚱한 해의
            //   이월을 만들고도 값이 그대로라 영문을 모른다.
            warnings.add(openingYear + "년 이월 스냅샷이 없습니다. 이월이 0으로 계산되어 "
                    + "전년 미수금이 잔액에 빠져 있습니다 — 마감관리에서 이월을 먼저 생성하세요.");
        }
        return new ArStatusResponse(from, to, rows, total,
                subtotal("매입처 소계", purchaseRows), subtotal("매출처 소계", salesRows), warnings);
    }

    /**
     * 외상매출장 — <b>전체 거래처</b>(24p). 거래처별 원장을 한 번에 낸다.
     *
     * <p>화면이 "전체 거래처 한 번에 보기는 준비 중"으로 막아 두고 있었다(2026-09-16 번들 실측).
     * 원장은 거래처 하나가 단위라 <b>거래처마다 한 벌씩</b> 담아 낸다 — 한 표로 합치면
     * 러닝밸런스가 남의 거래에 밀려 의미를 잃는다.
     *
     * <p>★대상 거래처는 <b>외상매출현황과 같은 집합</b>이다(이월이 있거나 기간 내 거래가 있는 곳).
     * 다른 기준으로 고르면 두 화면의 거래처 수가 달라져 어느 쪽도 못 믿는다.
     *
     * <p>‼️거래처마다 원장을 만들므로 거래처 수만큼 조회가 돈다. 기간을 넓게 잡으면 느리다 —
     * 화면이 기간을 좁혀 쓰도록 안내하는 편이 낫다.
     */
    @Transactional(readOnly = true)
    public List<ArLedgerResponse> arLedgerAll(LocalDate fromDate, LocalDate toDate) {
        return arLedgerAll(fromDate, toDate, false);
    }

    /**
     * 외상매출장 전체 거래처. {@code onlyReportable=true} 면 <b>기간 내 신고내역이 있는 거래처만</b>.
     *
     * <p>근거: 레거시 외상매출장조회 화면 안내 —
     * "[메일일괄전송]·[PDF일괄저장] … <b>신고대상 체크시 설정된 기간내 신고내역이 있는 거래처에</b>
     * 내역서 일괄 메일 전송". 레거시에서 '신고대상'은 조회 필터가 아니라
     * <b>일괄 전송 대상을 추리는 스위치</b>였다(체크박스가 버튼 활성화만 제어한다).
     *
     * <p>여기서 미리 걸러 주면 화면이 빈 내역서를 만들고 보내는 일이 없다 —
     * 거래가 없는 거래처에 0원짜리 명세서가 메일로 나가면 그건 사고다.
     */
    @Transactional(readOnly = true)
    public List<ArLedgerResponse> arLedgerAll(LocalDate fromDate, LocalDate toDate,
                                              boolean onlyReportable) {
        List<ArLedgerResponse> out = new ArrayList<>();
        for (ArStatusResponse.Row r : arStatus(fromDate, toDate, null).rows()) {
            if (r.partnerId() == null) {
                continue;   // 합계행
            }
            ArLedgerResponse led = arLedger(r.partnerId(), fromDate, toDate);
            // '신고내역이 있는' = 그 기간에 매출·반품·수금 중 하나라도 움직인 거래처.
            // 이월만 있고 기간 거래가 없는 곳은 보낼 내역서가 없다.
            // ‼️**명세행(DETAIL)으로 센다.** 이월·일계 같은 집계행이 생긴 뒤로 lines 는 늘 비지 않는다 —
            //   isEmpty() 로 보면 거래가 없는 거래처에도 0원짜리 명세서가 메일로 나간다.
            if (onlyReportable && led.lines().stream().noneMatch(l -> "DETAIL".equals(l.rowType()))) {
                continue;
            }
            // ★거래처코드 상한도 '신고대상'의 조건이다(화면 담당 전달 2026-10-23 §3③ —
            //   "신고대상 = 기간 내 매출 금액 합 ≠ 0 **인 거래처, 거래처코드 'E110000' 미만**").
            //   레거시 기준이고, 화면 목록이 이미 그 기준으로 맞춰져 있다 —
            //   서버가 더 넓게 주면 **화면에 안 보이던 거래처에 메일·PDF가 나간다.**
            if (onlyReportable && !reportableCode(r.partnerCode())) {
                continue;
            }
            out.add(led);
        }
        return out;
    }

    /**
     * '신고대상' 거래처코드 조건 — <b>'E110000' 미만</b>(레거시 기준, 화면 담당 전달 2026-10-23 §3③).
     *
     * <p>문자열 비교다. 레거시 코드체계가 영문1자 + 숫자라 사전순이 곧 그 체계의 순서다.
     * 'E110000' 이상은 신고 대상이 아닌 계열(내부·기타)로 쓰여 왔다.
     *
     * <p>‼️<b>코드가 없으면 대상이 아니다.</b> 비어 있는 코드를 통과시키면
     * 정체를 모르는 거래처에 명세서가 나간다 — 되돌릴 수 없는 쪽으로 틀리지 않는다.
     */
    private static boolean reportableCode(String partnerCode) {
        return partnerCode != null && !partnerCode.isBlank()
                && partnerCode.trim().compareTo("E110000") < 0;
    }

    /**
     * 외상매출장 전체 — <b>거래처 단위 페이지</b>.
     *
     * <p>‼️<b>전량 반환은 서버를 죽인다.</b> 이관 뒤 2012~2026 전 기간을 부르면
     * <b>응답 127MB · 56.9초</b>였다(2026-10-20 실측). 배포 서버 힙이 478MB 라
     * 이 호출 <b>한 번으로</b> OutOfMemoryError 가 난다 — 10/19 에 실제로 겪은 그것이다.
     *
     * <p>★<b>거래처 단위로 자른다.</b> 한 거래처의 원장(이월·명세·일계·누계)은 통째로 유지된다.
     * 명세 줄 단위로 자르면 누계와 일계가 중간에서 끊겨 숫자가 맞지 않는다.
     *
     * <p>일괄 메일·PDF 는 전 거래처가 필요하므로 {@link #arLedgerAll} 을 그대로 쓴다.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ArLedgerResponse> arLedgerAllPage(
            LocalDate fromDate, LocalDate toDate, boolean onlyReportable,
            org.springframework.data.domain.Pageable pageable) {
        List<Long> partnerIds = new ArrayList<>();
        for (ArStatusResponse.Row r : arStatus(fromDate, toDate, null).rows()) {
            if (r.partnerId() != null) {
                partnerIds.add(r.partnerId());   // 합계행 제외
            }
        }
        // ★원장은 **이 페이지의 거래처만** 만든다. 전체를 만들고 자르면 느린 것은 그대로다.
        //   다만 onlyReportable 은 원장을 만들어 봐야 알 수 있어, 그 경우에만 전량을 훑는다.
        if (onlyReportable) {
            List<ArLedgerResponse> all = arLedgerAll(fromDate, toDate, true);
            int from = (int) Math.min(pageable.getOffset(), all.size());
            int to = Math.min(from + pageable.getPageSize(), all.size());
            return new org.springframework.data.domain.PageImpl<>(
                    all.subList(from, to), pageable, all.size());
        }
        int from = (int) Math.min(pageable.getOffset(), partnerIds.size());
        int to = Math.min(from + pageable.getPageSize(), partnerIds.size());
        List<ArLedgerResponse> rows = new ArrayList<>();
        for (Long id : partnerIds.subList(from, to)) {
            rows.add(arLedger(id, fromDate, toDate));
        }
        return new org.springframework.data.domain.PageImpl<>(rows, pageable, partnerIds.size());
    }

    /**
     * 외상매출장(24p) — <b>도서 단위 명세</b> + 러닝밸런스.
     * 근거: 레거시 외상매출장조회.vb {@code Refresh_DataGridView()} + 정본 24p 데이터 항목.
     *
     * <p>기초이월(시작일 직전 잔액) + 기간 내 매출/교사용/반품/수금 명세 + 일자별 누계.
     * 누계 = 이월 + Σ(채권 증감). 매출·교사용 +, 반품·수금 −.
     *
     * <p>★교사용은 <b>무가 중 공급률이 있는 것</b>만이다(레거시 {@code 무상 AND supRate<>0}).
     * 공급률 0인 무가는 증정이라 채권과 무관하고, 합쳐 두면 교사용 수량이 부풀려진다.
     */
    @Transactional(readOnly = true)
    public ArLedgerResponse arLedger(Long partnerId, LocalDate fromDate, LocalDate toDate) {
        Partner partner = partnerRepository.findById(partnerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "거래처가 없습니다. id=" + partnerId));
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();

        long opening = balanceAsOf(partnerId, from.minusDays(1));

        // 매출/반품 라인 + 수금 라인을 일자순으로 병합(같은 날은 매출 먼저).
        // ★행은 도서 단위다(레거시 UNION 구조). 한 행에는 그 구분의 칸만 찬다 —
        //   매출 행이면 매출수량·금액·세액이 차고 교사용·반품·수금 칸은 비어 있다.
        record Entry(LocalDate date, int ord, ArLedgerResponse.Line line, long amount) {}
        List<Entry> entries = new ArrayList<>();

        for (Sale s : saleRepository.findLedgerLines(partnerId, from, to)) {
            long supply = nz(s.getSupplyAmount());
            long tax = nz(s.getTax());
            long total = nz(s.getTotalAmount());
            SalesCategory cat = s.getSalesCategory();
            boolean isReturn = cat == SalesCategory.RETURN;
            // ‼️교사용은 '무가 중 공급률이 있는 것'만이다(레거시: 무상 AND supRate<>0).
            //   공급률 0인 무가는 증정이라 채권과 무관하다 — 합치면 교사용 수량이 부풀려진다.
            boolean isTeacher = cat == SalesCategory.FREE
                    && s.getSupplyRate() != null && s.getSupplyRate() != 0;
            boolean isSale = cat == SalesCategory.SALE;

            long amount = isReturn ? -total : (isSale || isTeacher ? total : 0L);
            entries.add(new Entry(s.getSalesDate(), 0, ArLedgerResponse.Line.detail(
                    s.getSalesDate(), saleKind(s), s.getSalesNo(),
                    s.getProduct().getCatCode(), s.getProduct().getCatName(),
                    s.getProduct().getCode(), bookLabel(s),
                    s.getSupplyRate(),
                    isSale ? (long) s.getQty() : null, isSale ? supply : null, isSale ? tax : null,
                    isTeacher ? (long) s.getQty() : null, isTeacher ? total : null,
                    isReturn ? -(long) s.getQty() : null, isReturn ? -total : null,
                    null, amount, 0L), amount));
        }

        for (Collection c : collectionRepository.findLedgerLines(partnerId, from, to)) {
            entries.add(new Entry(c.getCollDate(), 1, ArLedgerResponse.Line.detail(
                    c.getCollDate(), "수금", c.getCollectionNo(),
                    null, null, null, collLabel(c.getCollType()), null,
                    null, null, null, null, null, null, null,
                    c.getCollAmt(), -c.getCollAmt(), 0L), -c.getCollAmt()));
        }
        entries.sort(Comparator.comparing(Entry::date).thenComparingInt(Entry::ord));

        List<ArLedgerResponse.Line> lines = new ArrayList<>();
        long running = opening;

        // ★이월 금액 행. 특약점에 나가는 PDF 첫 줄이 이것이다(SALES_*.pdf 실물) —
        //   없으면 받는 쪽이 "왜 잔액이 0에서 시작하지 않나"를 알 수 없다.
        lines.add(ArLedgerResponse.Line.aggregate("OPENING", "이월 금액", null,
                null, null, null, null, null, null, null, null, opening));

        // 일계·월계·누계 누적기.
        // ‼️**누계에는 이월 금액이 들어간다**(프론트 실측 2026-10-12 B-4 — 발주처 인쇄물 기준).
        //   앞서는 기간 매출만 더해 "이월 49,635,790 인데 첫 달 누계가 7,441,200" 이 됐다.
        //   받는 특약점이 보는 것은 "지금까지 얼마가 쌓였나"라 이월을 빼면 숫자가 의미를 잃는다.
        //   → 금액 칸(index 1)만 이월로 시작한다. 수량은 이월 개념이 없다(이월은 금액 잔액이다).
        long[] day = new long[8];
        long[] month = new long[8];
        long[] run = new long[8];
        run[1] = opening;
        LocalDate curDay = null;
        java.time.YearMonth curMonth = null;

        // ★이월 바로 아래에도 누 계를 둔다(프론트 실측 2026-10-12 — 발주처 샘플 SALES_10003 구조).
        //   거래가 시작되기 전의 누계는 곧 이월이다. 이 줄이 없으면 받는 쪽이
        //   "이월이 누계에 반영된 것인가"를 첫 거래가 나올 때까지 알 수 없다.
        lines.add(agg("RUNNING_TOTAL", "누 계", null, run, running));

        for (Entry e : entries) {
            LocalDate d = e.date();
            java.time.YearMonth ym = java.time.YearMonth.from(d);

            // ★인쇄 순서는 샘플 그대로다 —
            //   달 중간 날짜: 일 계 → 누 계
            //   달 마지막 날: 일 계 → 월 계 → 누 계
            //   즉 **날짜가 바뀔 때마다 누 계**가 들어가고, 달이 끝날 때만 그 앞에 월 계가 붙는다.
            if (curDay != null && !curDay.equals(d)) {
                lines.add(agg("DAY_SUBTOTAL", "일 계", curDay, day, running));
                java.util.Arrays.fill(day, 0L);
                if (curMonth != null && !curMonth.equals(ym)) {
                    lines.add(agg("MONTH_SUBTOTAL", "월 계", curDay, month, running));
                    java.util.Arrays.fill(month, 0L);
                }
                lines.add(agg("RUNNING_TOTAL", "누 계", curDay, run, running));
            }
            curDay = d;
            curMonth = ym;

            running += e.amount();
            ArLedgerResponse.Line l = e.line();
            // 누계는 정렬이 끝난 뒤에야 정해지므로 여기서 채운다.
            lines.add(ArLedgerResponse.Line.detail(l.date(), l.kind(), l.refNo(),
                    l.catCode(), l.catName(), l.productCode(), l.productName(), l.supplyRate(),
                    l.saleQty(), l.saleAmount(), l.tax(),
                    l.teacherQty(), l.teacherAmount(), l.returnQty(), l.returnAmount(),
                    l.collectAmount(), l.amount(), running));
            accumulate(day, l);
            accumulate(month, l);
            accumulate(run, l);
        }
        if (curDay != null) {
            lines.add(agg("DAY_SUBTOTAL", "일 계", curDay, day, running));
            lines.add(agg("MONTH_SUBTOTAL", "월 계", curDay, month, running));
            lines.add(agg("RUNNING_TOTAL", "누 계", curDay, run, running));
        }
        return new ArLedgerResponse(partner.getId(), partner.getName(), from, to, opening, running, lines);
    }

    /**
     * 집계 버킷에 명세행을 더한다.
     * 순서: 매출수량·매출액·세액·교사용수량·교사용액·반품수량·<b>반품금액·수금액</b>.
     *
     * <p>★반품금액·수금액도 담는다(프론트 실측 2026-10-12 B-4) — 없으면 인쇄물의
     * 일계·월계 줄을 서버 숫자 그대로 찍을 수 없어 화면이 다시 더해야 한다.
     */
    private static void accumulate(long[] acc, ArLedgerResponse.Line l) {
        acc[0] += nz(l.saleQty());
        acc[1] += nz(l.saleAmount());
        acc[2] += nz(l.tax());
        acc[3] += nz(l.teacherQty());
        acc[4] += nz(l.teacherAmount());
        acc[5] += nz(l.returnQty());
        acc[6] += nz(l.returnAmount());
        acc[7] += nz(l.collectAmount());
    }

    /** 집계행 한 줄. */
    private static ArLedgerResponse.Line agg(String rowType, String label, LocalDate date,
                                             long[] acc, long balance) {
        return ArLedgerResponse.Line.aggregate(rowType, label, date,
                acc[0], acc[1], acc[2], acc[3], acc[4], acc[5], acc[6], acc[7], balance);
    }

    /**
     * 도서명 표기. 레거시 그대로 회차·학교를 붙인다 —
     * {@code 도서명 [3회] <강남대성학원>}. 같은 책이 회차·학교별로 여러 줄 나오므로
     * 이름만으로는 어느 줄인지 가릴 수 없다(외상매출장조회.vb:200~203).
     */
    private static String bookLabel(Sale s) {
        String name = s.getProduct().getName();
        Integer round = s.getBookRound();
        String school = s.getSchoolName();
        StringBuilder sb = new StringBuilder(name == null ? "" : name);
        if (round != null && round != 0) {
            sb.append(" [").append(round).append("회]");
        }
        if (school != null && !school.isBlank()) {
            sb.append(" <").append(school).append(">");
        }
        return sb.toString();
    }

    private static long nz(Long v) {
        return (v == null) ? 0L : v;
    }

    /** 특정 시점까지의 채권 잔액 = 이월(당해 스냅샷) + 당해 1/1~시점 채권발생 − 수금. */
    private long balanceAsOf(Long partnerId, LocalDate asOf) {
        int year = asOf.getYear();
        LocalDate yStart = LocalDate.of(year, 1, 1);
        long carry = firstAmount(carryforwardRepository.sumByYear(year, partnerId));
        long gen = firstAmount(saleRepository.receivableByPartner(yStart, asOf, partnerId));
        long coll = firstAmount(collectionRepository.sumByPartner(yStart, asOf, partnerId));
        return carry + gen - coll;
    }

    private static String saleKind(Sale s) {
        if (s.getSalesCategory() == SalesCategory.RETURN) {
            return "반품";
        }
        if (s.getSalesCategory() == SalesCategory.SALE) {
            return "매출";
        }
        return (s.getShipmentType() == ShipmentType.TEACHER_USE) ? "교사용" : "증정";
    }

    private static String collLabel(CollectionType t) {
        return switch (t) {
            case CASH -> "수금(현금)";
            case PROMISSORY -> "수금(어음)";
            case PREPAY -> "수금(선수금)";
            case REPLACE -> "수금(대체)";
        };
    }

    // ── 매핑 헬퍼 ───────────────────────────────────────────────
    /** [partnerId, amount] 단건(또는 0건) 목록에서 amount만. receivableByPartner의 gen도 index1이라 공용. */
    private static long firstAmount(List<Object[]> rows) {
        return rows.isEmpty() ? 0L : num(rows.get(0)[1]);
    }

    private static long num(Object o) {
        return (o == null) ? 0L : ((Number) o).longValue();
    }

    /** 매입처(기간 내·기간 전 매입이 있는 거래처) / 매출처 구분값. */
    public static final String PARTNER_KIND_PURCHASE = "PURCHASE";
    public static final String PARTNER_KIND_SALES = "SALES";

    /** getOrDefault 기본값 공유 — 루프마다 새 배열을 만들지 않는다. 읽기 전용으로만 쓴다. */
    private static final long[] EMPTY2 = new long[2];

    /**
     * 매입처·매출처 소계행. 레거시 대성교재 화면의 GROUPING 소계와 같은 자리다
     * (외상매출현황조회.vb:650 — 매입처 소계 / 매출처 소계 / 합계 3단).
     * 대상이 없으면 null을 돌려준다 — 0으로 채운 빈 소계행을 그리면 화면에 없는 묶음이 생긴다.
     */
    private static ArStatusResponse.Row subtotal(String label, List<ArStatusResponse.Row> src) {
        if (src.isEmpty()) {
            return null;
        }
        long open = 0, saleQty = 0, sale = 0, retQty = 0, ret = 0, tax = 0, gen = 0;
        long purQty = 0, pur = 0, coll = 0, bal = 0, teacher = 0, teacherQty = 0;
        for (ArStatusResponse.Row r : src) {
            open += r.opening(); saleQty += r.saleQty(); sale += r.saleAmount();
            retQty += r.returnQty(); ret += r.returnAmount(); tax += r.tax(); gen += r.receivableGen();
            purQty += r.purchaseQty(); pur += r.purchaseAmount(); coll += r.collected();
            bal += r.balance(); teacher += r.teacherAmount(); teacherQty += r.teacherQty();
        }
        return new ArStatusResponse.Row(null, label, null, null, null,
                open, saleQty, sale, retQty, ret, tax, gen, purQty, pur, coll, bal, teacher, teacherQty,
                List.of(), null, null, null, null, null);
    }

    /**
     * [partnerId, qty, amount] 목록 → Map&lt;partnerId, [수량, 금액]&gt;.
     * ‼️{@link #toAmountMap}을 쓰면 안 된다 — 그건 r[1]을 금액으로 읽는데 여기선 r[1]이 수량이다.
     */
    private static Map<Long, long[]> toQtyAmountMap(List<Object[]> rows) {
        Map<Long, long[]> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put(num(r[0]), new long[] {num(r[1]), num(r[2])});
        }
        return m;
    }

    /** [partnerId, amount] 목록 → Map. */
    private static Map<Long, Long> toAmountMap(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put(num(r[0]), num(r[1]));
        }
        return m;
    }

    /** receivableByPartner: [partnerId, gen, saleAmt, returnAmt, tax, teacherAmt, teacherQty] → Map<partnerId, long[..]>. */
    /**
     * 상품군(대분류)별 분해를 거래처별로 묶는다.
     *
     * <p>대분류 순서는 {@link MajorCategory} 선언순을 따른다 — 거래처마다 칸 순서가 달라지면
     * 화면이 열마다 다른 상품군을 그린다. 대분류가 없는 상품은 {@code 미분류}로 <b>남긴다</b>
     * (빼면 상품군 합이 전체 매출과 안 맞고, 그러면 어느 쪽이 틀렸는지 알 수 없다).
     */
    private static Map<Long, List<ArStatusResponse.CategoryBreakdown>> toCategoryMap(List<Object[]> rows) {
        Map<Long, List<ArStatusResponse.CategoryBreakdown>> m = new LinkedHashMap<>();
        for (Object[] r : rows) {
            m.computeIfAbsent(num(r[0]), k -> new ArrayList<>()).add(breakdown(
                    str(r[1]), num(r[2]), num(r[3]), num(r[4]), num(r[5])));
        }
        m.values().forEach(list -> list.sort(Comparator.comparingInt(ReceivableService::categoryOrder)));
        return m;
    }

    /** 합계행의 상품군 칸 — 거래처별 분해를 대분류 단위로 합친다. */
    private static List<ArStatusResponse.CategoryBreakdown> sumCategories(
            java.util.Collection<List<ArStatusResponse.CategoryBreakdown>> all) {
        Map<String, long[]> acc = new LinkedHashMap<>();
        Map<String, MajorCategory> codes = new HashMap<>();
        for (List<ArStatusResponse.CategoryBreakdown> list : all) {
            for (ArStatusResponse.CategoryBreakdown c : list) {
                long[] v = acc.computeIfAbsent(c.majorName(), k -> new long[4]);
                v[0] += c.saleQty(); v[1] += c.saleAmount();
                v[2] += c.returnQty(); v[3] += c.returnAmount();
                codes.put(c.majorName(), c.majorCategory());
            }
        }
        List<ArStatusResponse.CategoryBreakdown> out = new ArrayList<>();
        acc.forEach((name, v) -> out.add(new ArStatusResponse.CategoryBreakdown(
                codes.get(name), name, v[0], v[1], v[2], v[3])));
        out.sort(Comparator.comparingInt(ReceivableService::categoryOrder));
        return out;
    }

    private static ArStatusResponse.CategoryBreakdown breakdown(String rawCode, long saleQty,
                                                               long saleAmt, long retQty, long retAmt) {
        MajorCategory code = null;
        if (rawCode != null && !rawCode.isBlank()) {
            try {
                code = MajorCategory.valueOf(rawCode);
            } catch (IllegalArgumentException ignored) {
                code = null;   // 우리가 모르는 값이 DB에 있어도 행을 버리지 않는다(합이 어긋난다)
            }
        }
        String name = (code != null) ? code.label() : "미분류";
        return new ArStatusResponse.CategoryBreakdown(code, name, saleQty, saleAmt, retQty, retAmt);
    }

    /** 미분류는 항상 맨 뒤. 그 외는 enum 선언순. */
    private static int categoryOrder(ArStatusResponse.CategoryBreakdown c) {
        return (c.majorCategory() == null) ? Integer.MAX_VALUE : c.majorCategory().ordinal();
    }

    private static Map<Long, long[]> toSalesMap(List<Object[]> rows) {
        Map<Long, long[]> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put(num(r[0]), new long[]{num(r[1]), num(r[2]), num(r[3]), num(r[4]), num(r[5]),
                    num(r[6]), num(r[7]), num(r[8])});
        }
        return m;
    }

    /** receivableByPartner의 gen(index 1)만 뽑아 Map. */
    private static Map<Long, Long> toReceivableGenMap(List<Object[]> rows) {
        Map<Long, Long> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put(num(r[0]), num(r[1]));
        }
        return m;
    }

}
