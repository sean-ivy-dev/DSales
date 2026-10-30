package com.daesung.sales.document.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 특약점 배포용 PDF 보관함 DTO 모음. */
public final class PartnerDocumentDtos {

    private PartnerDocumentDtos() {
    }

    /**
     * 업로드 결과. <b>파일 하나가 실패해도 나머지는 올라간다</b>(10/26 명세 §7 —
     * "형식이 맞지 않거나 거래처코드가 없으면 업로드 전 목록에 오류로 표시하고 그 파일만 뺀다").
     */
    @Schema(name = "PartnerDocumentUploadResponse")
    public record UploadResponse(
            @Schema(description = "새로 올라간 파일 수") int created,
            @Schema(description = "기존 것을 교체한 파일 수(같은 거래처·종류·기준월)") int replaced,
            @Schema(description = "형식 오류 등으로 뺀 파일 수") int failed,
            @Schema(description = "파일별 결과") List<UploadLine> lines) {
    }

    @Schema(name = "PartnerDocumentUploadLine")
    public record UploadLine(
            @Schema(description = "원본 파일명") String fileName,
            @Schema(description = "`CREATED` / `REPLACED` / `ERROR`") String result,
            @Schema(description = "종류(SALES/RESULT)") String docType,
            @Schema(description = "파일명에서 읽은 DSRE 거래처코드") String partnerCode,
            @Schema(description = "기준월(yyyy-MM) — 기간 종료일의 월") String baseMonth,
            @Schema(description = """
                    경고·오류 문구. ‼️거래처를 못 찾아도 **파일은 받는다**(경고만) —
                    업로드를 막으면 담당자가 그 달 전체를 못 올린다. 매핑이 채워지면
                    같은 파일을 다시 올려 이어 붙일 수 있다.""")
            String message) {
    }

    /** 보관함 목록 한 줄. 내용(PDF)은 빼고 메타만 — 목록에서 수백 개를 읽으면 앱이 넘어간다. */
    @Schema(name = "PartnerDocumentRow")
    public record Row(
            Long id,
            @Schema(description = "종류(SALES/RESULT)") String docType,
            @Schema(description = "우리 거래처 id. 매핑을 못 찾았으면 null") Long partnerId,
            @Schema(description = "DSRE 거래처코드") String partnerCode,
            @Schema(description = "파일명에서 읽은 거래처명") String partnerName,
            @Schema(description = "기준월(yyyy-MM)") String baseMonth,
            LocalDate periodFrom,
            LocalDate periodTo,
            String fileName,
            @Schema(description = "바이트") int fileSize,
            @Schema(description = "올린 시각") LocalDateTime uploadedAt) {
    }
}
