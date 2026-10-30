package com.daesung.sales.document.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.document.dto.PartnerDocumentDtos;
import com.daesung.sales.document.entity.PartnerDocument;
import com.daesung.sales.document.repository.PartnerDocumentRepository;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.entity.PartnerDsreCode;
import com.daesung.sales.partner.repository.PartnerDsreCodeRepository;
import com.daesung.sales.partner.repository.PartnerRepository;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 특약점 배포용 PDF 보관함. 근거: 10/26 특약점 명세 §7 + 10/27 피드백 §7-8.
 *
 * <p>매출프로그램에서 거래처별 외상매출장 PDF를 올려 두면, 특약점이 사이트에서 자기 것만
 * 내려받는다. <b>파일명이 곧 메타데이터</b>라 {@link DocumentFileName} 이 그것을 읽는다.
 *
 * <p>★<b>이 서비스가 파일 저장을 혼자 안다.</b> 지금은 DB LONGBLOB 이고(도커라 로컬 파일은
 * 재배포 때 사라지고, 외부 스토리지 설정이 아직 없다), S3 가 생기면 여기만 고치면 된다.
 */
@Service
@RequiredArgsConstructor
public class PartnerDocumentService {

    /** 한 번에 올릴 수 있는 파일 크기 상한(20MB). 외상매출장 PDF는 보통 150~400KB다. */
    private static final int MAX_BYTES = 20 * 1024 * 1024;

    private final PartnerDocumentRepository repository;
    private final PartnerRepository partnerRepository;
    private final PartnerDsreCodeRepository dsreCodeRepository;

    /**
     * 다건 일괄 업로드(명세 §7 — "다건 일괄 업로드와 단건 재업로드를 모두 지원").
     *
     * <p>‼️<b>파일 하나가 틀려도 나머지는 올린다.</b> 월마감 뒤 수백 개를 한 번에 올리는
     * 작업이라, 하나 때문에 전부 되돌리면 담당자가 어느 것이 문제인지 모른 채 다시 시작해야 한다.
     */
    @Transactional
    public PartnerDocumentDtos.UploadResponse upload(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "올릴 파일이 없습니다");
        }
        List<PartnerDocumentDtos.UploadLine> lines = new ArrayList<>();
        int created = 0;
        int replaced = 0;
        int failed = 0;

        for (MultipartFile file : files) {
            String fileName = file.getOriginalFilename();
            DocumentFileName.Parsed p = DocumentFileName.parse(fileName);
            if (!p.ok()) {
                failed++;
                lines.add(new PartnerDocumentDtos.UploadLine(
                        fileName, "ERROR", null, null, null, p.error()));
                continue;
            }
            if (file.isEmpty()) {
                failed++;
                lines.add(new PartnerDocumentDtos.UploadLine(
                        fileName, "ERROR", p.docType(), p.partnerCode(), p.baseMonth(), "빈 파일입니다"));
                continue;
            }
            if (file.getSize() > MAX_BYTES) {
                failed++;
                lines.add(new PartnerDocumentDtos.UploadLine(
                        fileName, "ERROR", p.docType(), p.partnerCode(), p.baseMonth(),
                        "파일이 너무 큽니다(최대 20MB)"));
                continue;
            }

            byte[] content;
            try {
                content = file.getBytes();
            } catch (IOException e) {
                failed++;
                lines.add(new PartnerDocumentDtos.UploadLine(
                        fileName, "ERROR", p.docType(), p.partnerCode(), p.baseMonth(),
                        "파일을 읽을 수 없습니다: " + e.getMessage()));
                continue;
            }

            Partner partner = findPartner(p.partnerCode()).orElse(null);
            // ★거래처를 못 찾아도 받는다 — 코드만 맞춰 두면 매핑이 채워질 때 이어 붙는다.
            String warn = (partner == null)
                    ? "거래처코드 " + p.partnerCode() + " 에 연결된 거래처를 못 찾았습니다 — 파일은 보관했습니다"
                    : null;

            Optional<PartnerDocument> existing = repository
                    .findByDocTypeAndPartnerCodeAndBaseMonth(p.docType(), p.partnerCode(), p.baseMonth());
            if (existing.isPresent()) {
                existing.get().replaceWith(partner, p.partnerName(), p.from(), p.to(), fileName, content);
                replaced++;
                lines.add(new PartnerDocumentDtos.UploadLine(fileName, "REPLACED",
                        p.docType(), p.partnerCode(), p.baseMonth(), warn));
            } else {
                repository.save(PartnerDocument.of(p.docType(), partner, p.partnerCode(),
                        p.partnerName(), p.baseMonth(), p.from(), p.to(), fileName, content));
                created++;
                lines.add(new PartnerDocumentDtos.UploadLine(fileName, "CREATED",
                        p.docType(), p.partnerCode(), p.baseMonth(), warn));
            }
        }
        return new PartnerDocumentDtos.UploadResponse(created, replaced, failed, lines);
    }

    /**
     * 파일명의 <b>DSRE 거래처코드</b>로 우리 거래처를 찾는다. 매핑표(V100)가 1:N 이라
     * 여럿이면 첫 번째를 쓴다 — 어느 쪽이든 같은 특약점이다.
     *
     * <p>‼️우리 매출코드(A10A195)로도 한 번 더 찾아본다. 레거시 파일명은 DSRE 코드지만,
     * 우리 화면에서 새로 뽑은 파일은 매출코드로 나갈 수 있어서다.
     */
    private Optional<Partner> findPartner(String code) {
        List<PartnerDsreCode> mapped = dsreCodeRepository.findByDsreCodeIn(List.of(code));
        if (!mapped.isEmpty()) {
            return partnerRepository.findById(mapped.get(0).getPartnerId());
        }
        return partnerRepository.findByCode(code);
    }

    /** 보관함 목록(메타만). 기준월 범위는 사이트의 '최근 12개월'을 그대로 쓸 수 있게 열어 둔다. */
    @Transactional(readOnly = true)
    public List<PartnerDocumentDtos.Row> search(String docType, Long partnerId, String partnerCode,
                                                String fromMonth, String toMonth) {
        List<PartnerDocumentDtos.Row> rows = new ArrayList<>();
        for (Object[] r : repository.search(docType, partnerId, partnerCode, fromMonth, toMonth)) {
            rows.add(new PartnerDocumentDtos.Row(
                    num(r[0]), (String) r[1], (r[2] == null) ? null : num(r[2]),
                    (String) r[3], (String) r[4], (String) r[5],
                    date(r[6]), date(r[7]), (String) r[8],
                    (r[9] == null) ? 0 : ((Number) r[9]).intValue(),
                    (r[10] == null) ? null : ((java.sql.Timestamp) r[10]).toLocalDateTime()));
        }
        return rows;
    }

    /**
     * 다운로드. 백오피스(사업부)는 전부 받을 수 있다.
     *
     * <p>★<b>특약점 사이트에 열어 줄 때는 여기 그대로 쓰면 안 된다</b> — 명세 §7이
     * "서버가 로그인 거래처 본인 파일인지 확인한 뒤 내려준다"를 명시했다. 사이트용 경로는
     * {@link #findForPartner} 로 <b>거래처를 서버가 정해</b> 내려줘야 한다(파일 id만 믿으면
     * 번호를 바꿔 남의 PDF를 받는다 — Phase 0 order IDOR 과 같은 유형이다).
     */
    @Transactional(readOnly = true)
    public PartnerDocument download(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "문서를 찾을 수 없습니다: " + id));
    }

    /**
     * <b>특약점 본인 파일인지 확인하고</b> 내려준다. 사이트가 쓸 경로다(명세 §7 '열람 권한').
     * 거래처는 <b>호출자가 넘긴 값이 아니라 서버가 아는 로그인 거래처</b>여야 한다.
     */
    @Transactional(readOnly = true)
    public PartnerDocument findForPartner(Long id, Long partnerId) {
        PartnerDocument d = download(id);
        boolean mine = (d.getPartner() != null && d.getPartner().getId().equals(partnerId));
        if (!mine) {
            // ‼️"없음"으로 답한다 — "있지만 당신 것이 아니다"는 그 자체로 남의 파일 존재를 알려 준다.
            throw new BusinessException(ErrorCode.NOT_FOUND, "문서를 찾을 수 없습니다: " + id);
        }
        return d;
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }

    private static LocalDate date(Object o) {
        return (o == null) ? null : ((java.sql.Date) o).toLocalDate();
    }
}
