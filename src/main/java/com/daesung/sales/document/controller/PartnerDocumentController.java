package com.daesung.sales.document.controller;

import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.document.dto.PartnerDocumentDtos;
import com.daesung.sales.document.entity.PartnerDocument;
import com.daesung.sales.document.service.PartnerDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 특약점 배포용 PDF 보관함. 실제 경로: {@code /api/v1/closing/partner-documents}.
 *
 * <p>근거: 10/26 특약점 명세 §7 「특약점 PDF — 매출프로그램에서 직접 업로드」 +
 * 10/27 피드백 §7-8(버튼 이름을 [특약점 업로드]로, 그 자리에서 업로드).
 *
 * <p>★<b>왜 {@code /closing} 아래인가</b> — 외상매출장(마감관리)에서 뽑은 PDF를 올리는
 * 기능이고, 권한 표(V52)가 {@code /closing/**} 를 마감 화면으로 잡고 있다. 다른 접두어를
 * 쓰면 화면에 안 붙은 쓰기 경로가 되어 **권한 표의 deny-by-default 에 막힌다**.
 */
@Tag(name = "마감관리 · 특약점 PDF", description = "외상매출장 PDF 업로드/조회/다운로드(특약점 배포용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/closing/partner-documents")
public class PartnerDocumentController {

    private final PartnerDocumentService service;

    @Operation(summary = "특약점 PDF 업로드(다건)",
            description = """
                    외상매출장 PDF를 올려 둔다. **파일명이 곧 메타데이터**다 —
                    `{종류}_{거래처코드}_{거래처명}_{yyyyMMdd}-{yyyyMMdd}.pdf`
                    (예: `SALES_10003_강남 하람도서_20260101-20260831.pdf`).
                    종류는 `SALES`(일반)·`RESULT`(더프모) 둘뿐이다.

                    · **기준월 = 기간 종료일의 월.** 같은 거래처·종류·기준월을 다시 올리면 **교체**한다.
                    · 파일 하나가 틀려도 **나머지는 올라간다** — 월마감 뒤 수백 개를 한 번에
                      올리는 작업이라, 하나 때문에 전부 되돌리면 어느 것이 문제인지 모른 채
                      처음부터 다시 해야 한다.
                    · 거래처코드로 거래처를 **못 찾아도 파일은 받는다**(경고만). 막으면 담당자가
                      그 달 전체를 못 올린다. 매핑이 채워진 뒤 같은 파일을 다시 올리면 이어 붙는다.""")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<PartnerDocumentDtos.UploadResponse> upload(
            @Parameter(description = "PDF 파일들(다건)") @RequestParam("files") List<MultipartFile> files) {
        return ApiResponse.success(service.upload(files));
    }

    @Operation(summary = "특약점 PDF 목록",
            description = """
                    보관된 PDF의 **메타만** 돌려준다(내용 제외 — 목록에서 수백 개를 읽으면
                    그것만으로 앱이 넘어간다). 조건은 전부 선택이다.

                    사이트의 '최근 12개월'은 `fromMonth`·`toMonth` 로 그대로 만들 수 있다.""")
    @GetMapping
    public ApiResponse<List<PartnerDocumentDtos.Row>> list(
            @Parameter(description = "종류(SALES/RESULT). 미지정=전체") @RequestParam(required = false) String docType,
            @Parameter(description = "우리 거래처 id") @RequestParam(required = false) Long partnerId,
            @Parameter(description = "DSRE 거래처코드(파일명에 들어 있는 값)")
            @RequestParam(required = false) String partnerCode,
            @Parameter(description = "기준월 시작(yyyy-MM)") @RequestParam(required = false) String fromMonth,
            @Parameter(description = "기준월 종료(yyyy-MM)") @RequestParam(required = false) String toMonth) {
        return ApiResponse.success(service.search(docType, partnerId, partnerCode, fromMonth, toMonth));
    }

    @Operation(summary = "특약점 PDF 다운로드(백오피스)",
            description = """
                    ‼️**이 경로는 사업부 백오피스용이다.** 특약점 사이트에 그대로 열어 주면 안 된다 —
                    명세 §7이 "서버가 로그인 거래처 본인 파일인지 확인한 뒤 내려준다"를 요구한다.
                    사이트용 경로는 거래처를 **서버가 정해** 내려주는 형태여야 한다
                    (`PartnerDocumentService.findForPartner`). 파일 id만 믿으면 번호를 바꿔
                    남의 PDF를 받을 수 있다 — Phase 0 order IDOR 과 같은 유형이다.""")
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        PartnerDocument d = service.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(d.getFileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(d.getContent());
    }

    @Operation(summary = "특약점 PDF 삭제(관리자)", description = """
            보관함에서 **영구 삭제**한다(프론트 I-5).

            요청 원문: "거래처를 잘못 골라 올린 파일은 지울 길이 없어 특약점 사이트
            (최근 12개월)에 그대로 보입니다. **관리자만 지울 수 있는 삭제**를 부탁드립니다."

            · ‼️**논리삭제가 아니다.** 파일 내용이 이 표에 들어 있어, 행만 숨기면 바이트가
              DB 에 남는다 — 잘못 올린 자료를 '안 보이게'만 하는 것으로는 부족하다.
            · 같은 거래처·종류·기준월은 **다시 올리면 교체**된다. 삭제는 거래처를 잘못 골라
              올린 경우처럼 **교체로 덮을 수 없을 때** 쓴다.
            · 응답에 지운 파일의 정보를 담아 돌려준다 — 무엇을 지웠는지 기록으로 남도록.""")
    @DeleteMapping("/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PartnerDocumentDtos.Row> delete(
            @Parameter(description = "문서 id") @PathVariable Long id) {
        return ApiResponse.success(service.delete(id));
    }
}
