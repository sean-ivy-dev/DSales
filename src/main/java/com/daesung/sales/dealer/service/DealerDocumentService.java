package com.daesung.sales.dealer.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.document.dto.PartnerDocumentDtos;
import com.daesung.sales.document.entity.PartnerDocument;
import com.daesung.sales.document.service.PartnerDocumentService;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 특약점 사이트용 <b>PDF 보관함</b>(10/26 §7 · §10-3).
 *
 * <p>§7 — "보관·열람: 사이트에서 <b>월을 골라</b> 지난달 PDF를 볼 수 있다. 범위는
 * <b>최근 12개월</b>이다. … 열람 권한: 서버가 <b>로그인 거래처 본인 파일인지 확인한 뒤</b>
 * 내려준다. 현행 공개 폴더 직접 링크 방식은 <b>파일명만 알면 남의 PDF가 열릴 수 있으니</b>,
 * 확인 후 인증 다운로드로 바꾼다."
 * §10-3 — "사이트 서버가 매출 API를 서버 간 호출 … 대상은 노출 도서, 거래명세서, 송장, <b>PDF</b>."
 *
 * <p>★<b>방향이 뒤집힌 지점</b>: 현행 DSRE 는 파일을 사이트 서버에 <b>SFTP 로 밀어넣었다</b>.
 * 신규는 우리가 보관하고 사이트가 받아간다 — §7 의 "서버가 본인 파일인지 <b>확인한 뒤</b>
 * 내려준다"를 밀어넣기 방식으로는 지킬 수 없기 때문이다(파일이 남의 서버 공개 폴더에 있으면
 * 우리가 확인할 자리가 없다).
 *
 * <p>업로드·보관·교체는 12단계에서 이미 만들었다({@link PartnerDocumentService}). 여기는
 * <b>사이트가 받아갈 경로</b>만 더한다 — 그걸 안 붙여서 서비스 메서드
 * {@code findForPartner} 가 아무도 안 부르는 상태로 남아 있었다.
 */
@Service
@RequiredArgsConstructor
public class DealerDocumentService {

    /** 사이트 기본 열람 범위. §7 "범위는 최근 12개월이다". */
    private static final int DEFAULT_MONTHS = 12;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final PartnerRepository partnerRepository;
    private final PartnerDocumentService partnerDocumentService;

    /**
     * 그 거래처의 보관함 목록. 기간을 안 주면 <b>최근 12개월</b>이다.
     *
     * <p>‼️거래처를 <b>반드시 조건으로 건다</b> — 비우면 전 거래처 목록이 나간다.
     */
    @Transactional(readOnly = true)
    public List<PartnerDocumentDtos.Row> documents(String partnerCode, String docType,
                                                   String fromMonth, String toMonth) {
        Partner partner = requirePartner(partnerCode);
        LocalDate today = LocalDate.now();
        String from = (fromMonth == null || fromMonth.isBlank())
                ? today.minusMonths(DEFAULT_MONTHS - 1L).format(MONTH) : fromMonth;
        String to = (toMonth == null || toMonth.isBlank()) ? today.format(MONTH) : toMonth;

        // ★거래처는 **우리 id 로** 건다. 파일명에서 읽은 DSRE 코드로 걸면, 매핑을 못 찾아
        //   거래처가 비어 있는 보관분까지 코드가 같다는 이유로 섞여 나갈 수 있다.
        return partnerDocumentService.search(blankToNull(docType), partner.getId(), null, from, to);
    }

    /**
     * 다운로드. <b>본인 파일이 아니면 "없음"과 같은 답</b>이다(§7 열람 권한).
     *
     * <p>판정은 파일 id 가 아니라 <b>거래처</b>로 한다 — id 만 믿으면 번호를 바꿔 남의 PDF 를
     * 받는다(Phase 0 order IDOR 과 같은 유형).
     */
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> download(String partnerCode, Long id) {
        Partner partner = requirePartner(partnerCode);
        PartnerDocument doc = partnerDocumentService.findForPartner(id, partner.getId());

        // 파일명은 한글이 들어가므로 UTF-8 로 인코딩해 내린다(다른 다운로드와 같은 규칙).
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(doc.getFileName(), StandardCharsets.UTF_8).build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(cd);
        return new ResponseEntity<>(doc.getContent(), headers, org.springframework.http.HttpStatus.OK);
    }

    /** ‼️{@link ErrorCode#PARTNER_NOT_LINKED} 다 — 특약점팀 전달(2026-11-03 확인 a)로 발견된
     * 누락. {@link com.daesung.sales.dealer.service.DealerStatementService#requirePartner} 와
     * 같은 사유. */
    private Partner requirePartner(String partnerCode) {
        return partnerRepository.findByCode(partnerCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARTNER_NOT_LINKED,
                        "매출 거래처 연결이 되어 있지 않습니다: " + partnerCode));
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }
}
