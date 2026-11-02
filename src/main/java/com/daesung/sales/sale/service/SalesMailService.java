package com.daesung.sales.sale.service;

import com.daesung.sales.common.excel.ExcelExportUtil;
import com.daesung.sales.common.excel.ExcelExportUtil.Col;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.mail.MailAttachment;
import com.daesung.sales.common.mail.MailSendResponse;
import com.daesung.sales.common.mail.MailService;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.sale.dto.SaleResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래상세내역서 메일 발송(9/27 회의 A-4, 항목 13·17).
 *
 * <p>레거시 {@code 조회.vb:3781}·{@code 외상매출장조회.vb:1434} 를 옮긴 것이다. 그쪽 동작은 —
 * <ol>
 *   <li>조회 결과를 <b>거래처별로</b> 나눠 엑셀을 만들고</li>
 *   <li>거래처 마스터의 <b>이메일1·이메일2</b>로 보내고</li>
 *   <li>제목은 {@code {거래처명} 거래상세내역서(시작-종료)}</li>
 *   <li>성공·실패를 로그 파일에 적는다</li>
 * </ol>
 *
 * <p>★<b>거래처마다 따로 보낸다.</b> 한 파일에 전 거래처를 담아 보내면
 * 각 거래처가 남의 매출을 본다 — 레거시가 거래처별로 나눈 이유가 그것이다.
 *
 * <p>★<b>한 건이 실패해도 나머지는 보낸다.</b> 그리고 어느 거래처가 왜 실패했는지 돌려준다.
 * 레거시는 건수만 세고 상세는 파일 로그에 적어, 실패한 거래처를 찾으려면 그 파일을 열어야 했다.
 */
@Service
@RequiredArgsConstructor
public class SalesMailService {

    private static final DateTimeFormatter DOT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    /** 메일 첨부는 페이지가 아니라 전량이다 — 화면 1페이지만 보내면 받는 쪽이 자료가 빠진 줄 모른다. */
    private static final int MAX_ROWS = 20_000;

    private final SaleService saleService;
    private final PartnerRepository partnerRepository;
    private final MailService mailService;
    private final ExcelExportUtil excel;

    /** 첨부 엑셀 컬럼. 화면 다운로드와 같은 축이어야 담당자가 같은 파일로 인식한다. */
    private static final List<Col> COLS = List.of(
            new Col("매출번호", "salesNo"), new Col("매출일자", "salesDate"),
            new Col("거래처코드", "partnerCode"), new Col("거래처명", "partnerName"),
            new Col("학교코드", "schoolCode"), new Col("학교/학원명", "schoolName"),
            new Col("분류코드", "catCode"), new Col("분류명", "catName"),
            new Col("도서코드", "productCode"), new Col("도서명", "productName"),
            new Col("회차", "bookRound"), new Col("정가", "unitPrice"),
            new Col("공급률", "supplyRate"), new Col("수량", "qty"),
            new Col("공급가액", "supplyAmount"), new Col("세액", "tax"),
            new Col("총금액", "totalAmount"), new Col("메모", "memo"));

    /**
     * 보낼 수 있는 상태인지 먼저 확인한다.
     *
     * <p>★<b>조회보다 먼저 부른다.</b> 메일이 꺼져 있는데 "보낼 자료가 없습니다"가 뜨면
     * 담당자는 조건을 계속 바꿔 본다 — 고쳐야 할 곳은 서버 설정이다.
     */
    public void assertUsable() {
        if (!mailService.usable()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "메일 발송이 꺼져 있습니다. 서버 설정(daesung.mail.enabled·spring.mail.*)을 확인하세요.");
        }
    }

    /**
     * 조회 조건 그대로의 매출을 거래처별 엑셀로 만들어 메일로 보낸다.
     *
     * <p>★<b>조회({@code GET /sales})와 같은 필터를 받는다</b>(프론트 실측 지적 2026-10-01).
     * 예전엔 기간·거래처·키워드만 받아서, 화면에서 구분·출고유형·창고·학교로 걸러 본 것과
     * <b>다른 내용이 거래처에 나갔다</b>. 메일은 되돌릴 수 없어 조회와 어긋나면 안 된다.
     *
     * <p>‼️<b>조회에 축을 더할 때 여기도 같이 더해야 한다</b>(프론트 실측 2026-10-12 B-3).
     * 10/08에 세부구분·도서·거래처×학교·판매채널을 조회에 넣으면서 이쪽을 빠뜨려,
     * 화면에서 그 조건으로 좁혀 놓고 보내면 <b>화면에 없던 매출까지 메일로 나갔다</b>.
     * 같은 사고를 막으려면 두 곳이 같은 파라미터를 받는지부터 본다.
     *
     * @param partnerIds 보낼 거래처. 비우면 <b>조회 결과에 나온 거래처 전부</b>(레거시 일괄 발송과 같다)
     */
    @Transactional(readOnly = true)
    public MailSendResponse sendStatements(
            LocalDate fromDate, LocalDate toDate,
            List<com.daesung.sales.salestype.entity.SalesCategory> salesCategories,
            List<com.daesung.sales.salestype.entity.TradeClass> tradeClasses,
            List<com.daesung.sales.salestype.entity.ShipmentType> shipmentTypes,
            List<Long> partnerIds, List<Long> warehouseIds, List<String> schoolCodes,
            List<String> divisions, List<Long> productIds, List<String> partnerSchools,
            List<String> salesChannels,
            String keyword, boolean couponCount) {
        if (fromDate == null || toDate == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "기간을 지정하세요. 기간 없이 보내면 몇 년치가 첨부됩니다.");
        }
        assertUsable();

        List<SaleResponse> rows = saleService.search(fromDate, toDate,
                        salesCategories, tradeClasses, shipmentTypes,
                        partnerIds, warehouseIds, schoolCodes,
                        divisions, productIds, partnerSchools, salesChannels,
                        keyword, null, false, couponCount, false,   // 일괄메일: 주문·출고 필터 없음
                        PageRequest.of(0, MAX_ROWS))
                .getContent();
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "보낼 매출이 없습니다. 조건을 확인하세요.");
        }

        // 거래처별로 나눈다 — 한 파일에 다 담으면 각 거래처가 남의 매출을 본다.
        Map<Long, List<SaleResponse>> byPartner = new LinkedHashMap<>();
        for (SaleResponse r : rows) {
            byPartner.computeIfAbsent(r.partnerId(), k -> new ArrayList<>()).add(r);
        }

        String period = fromDate.format(DOT) + "-" + toDate.format(DOT);
        List<MailSendResponse.Line> lines = new ArrayList<>();
        int sent = 0;
        int failed = 0;
        int skipped = 0;

        for (Map.Entry<Long, List<SaleResponse>> e : byPartner.entrySet()) {
            Partner p = partnerRepository.findById(e.getKey()).orElse(null);
            String name = (p != null) ? p.getName() : e.getValue().get(0).partnerName();
            String code = (p != null) ? p.getCode() : e.getValue().get(0).partnerCode();

            // 레거시와 같은 보정: 이메일1이 비고 2만 있으면 2를 주소로 올린다(외상매출장조회.vb:1425).
            String to1 = (p == null) ? null : p.getEmail1();
            String to2 = (p == null) ? null : p.getEmail2();
            if (to1 == null || to1.isBlank()) {
                to1 = to2;
                to2 = null;
            }

            String subject = name + " 거래상세내역서(" + period + ")";
            byte[] xlsx = excel.toXlsx("거래상세내역서", COLS, e.getValue());
            MailService.MailResult r = mailService.send(to1, to2, subject,
                    new MailAttachment("거래상세내역서_" + name + "_" + period + ".xlsx", xlsx));

            String result;
            if (r.sent()) {
                result = "SENT";
                sent++;
            } else if (r.to() == null) {
                result = "NO_EMAIL";
                skipped++;
            } else {
                result = "FAILED";
                failed++;
            }
            lines.add(new MailSendResponse.Line(e.getKey(), code, name, r.to(), result, r.message()));
        }
        return new MailSendResponse(sent, failed, skipped, lines);
    }
}
