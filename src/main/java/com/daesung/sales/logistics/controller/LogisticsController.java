package com.daesung.sales.logistics.controller;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.logistics.dto.ReturnCostDetailResponse;
import com.daesung.sales.dsre.gateway.DsreGateway;
import com.daesung.sales.dsre.gateway.LogisCostRate;
import com.daesung.sales.logistics.dto.LogisCostBulkRequest;
import com.daesung.sales.logistics.dto.LogisCostBulkResult;
import com.daesung.sales.logistics.service.LogisCostBulkService;
import com.daesung.sales.dsre.gateway.LogisMode;
import com.daesung.sales.logistics.dto.LogisCostDetailResponse;
import com.daesung.sales.dsre.gateway.OutboundLogisCost;
import com.daesung.sales.dsre.gateway.PeriodLogisCost;
import com.daesung.sales.logistics.dto.LogisCostUpsertRequest;
import com.daesung.sales.logistics.dto.ReturnRateRequest;
import com.daesung.sales.logistics.dto.ReturnRateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 물류/작업 · 물류비. DSRE2 의존(단가·수량·인원이 전부 DSRE2에 있음) → daesung.dsre.enabled=true일 때만.
 * 신청(REQ) 단위 출고금액 + 기간 출고·회수 집계 + 물류단가 관리(DSRE2 write-back).
 */
@Tag(name = "물류/작업 · 물류비", description = "DSRE2 기반 출고 물류비 계산(자재금액+인원비)")
@RestController
@RequestMapping("/logistics-costs")
@ConditionalOnProperty(name = "daesung.dsre.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LogisticsController {

    private final DsreGateway dsreGateway;
    private final com.daesung.sales.logistics.service.LogisCostDetailService logisCostDetailService;
    private final com.daesung.sales.common.excel.ExcelExportUtil excel;
    private final com.daesung.sales.logistics.service.WorkTypeService workTypeService;
    private final LogisCostBulkService logisCostBulkService;

    @Operation(summary = "출고 물류비 계산(신청 단위)",
            description = "DSRE2에서 자재수량×단가 집계 + 인원함수 호출로 출고 물류비 산출. "
                    + "자재금액(시험지/OMR/단행본·책자/라벨) + 인원비(인원×(BASIC+TRADE)).")
    @GetMapping("/outbound")
    public ApiResponse<OutboundLogisCost> outbound(
            @Parameter(description = "신청번호(REQ_CD)", example = "2001") @RequestParam int reqCd) {
        return ApiResponse.success(dsreGateway.calcOutbound(reqCd));
    }

    @Operation(summary = "기간 출고 물류비 집계",
            description = "신청일(REQ_DATE) 기간의 출고 물류비를 총계로 집계. 자재금액(시험지/OMR/ETC/라벨) + 인원비. "
                    + "mode=ALL(전체)/NORMAL(지사신청 APPLY_GN='S')/ACCIDENT(사고처리 APPLY_GN='A'). "
                    + "includeCancel=false(기본)면 취소(STATE='C') 제외.")
    @GetMapping("/outbound/period")
    public ApiResponse<PeriodLogisCost> outboundPeriod(
            @Parameter(description = "시작일", example = "2024-01-01")
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일", example = "2024-12-31")
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "구분(전체/일반/사고)", example = "ALL")
            @RequestParam(defaultValue = "ALL") LogisMode mode,
            @Parameter(description = "취소분 포함 여부", example = "false")
            @RequestParam(defaultValue = "false") boolean includeCancel) {
        return ApiResponse.success(dsreGateway.calcOutboundPeriod(from, to, mode, includeCancel));
    }

    @Operation(summary = "좌측 '시행 선택' 트리(연도 > 상품 > 시행) — 27p",
            description = """
                    물류 작업비 계산 좌측 트리 데이터. 정본이 "트리뷰 상품 선택에 **연도 구분** 추가
                    (29장 레거시: 2026년/2025년… 트리)"를 요구하는데, 필터(`dtlCds`)만 있고
                    **트리 데이터를 주는 경로가 없었다**(F-32).

                    · 연도 축은 DSRE2 상품 년도(`tbl_product_info.YEAR`)다. 복제본 실측 **2019~2026**,
                      상품 212 · 시행 2,576건.
                    · ‼️`GET /orders/exams` 로는 못 쓴다 — 그쪽은 **주문 등록용**이라
                      `판매종료일 >= 오늘` 로 **끝난 시행을 버린다**. 여기는 과거 연도를 보는 화면이라
                      판매종료 무관으로 전부 낸다(조건을 공유하면 2019~2025년이 통째로 사라진다).
                    · 잎의 `dtlCd` 를 그대로 `/logistics-costs/outbound/detail` 의 `dtlCds` 에 넘기면 된다.
                    · 정렬: 연도 내림차순 → 상품코드 → 학년 → 시행코드.""")
    @GetMapping("/exam-tree")
    public ApiResponse<java.util.List<com.daesung.sales.dsre.gateway.ExamTreeNode>> examTree(
            @Parameter(description = "그 연도만. 미지정=전 연도", example = "2026")
            @RequestParam(required = false) Integer year) {
        return ApiResponse.success(dsreGateway.examTree(year));
    }

    @Operation(summary = "출고 물류비 명세(28p 그리드)",
            description = """
                    화면에 뿌릴 **행 목록 + 소계**. 총계만 주던 `/outbound/period`와 짝이다.

                    정본 28p 데이터 항목이 처음부터 행 단위였다 —
                    접수일자·상품명·학년·시행코드/명·거래처코드/명·자재합·시험지합/금액·
                    OMR합/금액·기타비/금액·인원·기본작업비·출고비·금액합계.

                    · **grain**으로 묶는 축을 고른다(레거시 '상세보기' 체크박스):
                      `REQUEST`=신청번호까지 펼침 / `PARTNER`=거래처로 묶음(기본).
                      두 모드가 **같은 쿼리 하나**를 접어 만든다 — 쿼리를 나누면 계산식이 두 벌이 된다.
                    · 소계는 레거시 ROLLUP 그대로 **시행 계 → 학년 계 → 상품 계 → 총 계**.
                    · ⚠️인원은 신청 단위로 1회 산정된다. 거래처 축에서는 그 거래처의
                      여러 신청 인원이 합쳐진다(의도된 동작).""")
    @GetMapping("/outbound/detail")
    public ApiResponse<LogisCostDetailResponse> outboundDetail(
            @Parameter(description = "시작일", example = "2026-01-01") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일", example = "2026-12-31") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "구분(전체/일반/사고)", example = "ALL")
            @RequestParam(required = false, defaultValue = "ALL") LogisMode mode,
            @Parameter(description = "시행코드(DTL_CD) 다중 선택. 미지정=전체 — 좌측 연도·시행 트리가 쓴다")
            @RequestParam(required = false) java.util.List<Integer> dtlCds,
            @Parameter(description = "발송 후 취소 포함 여부")
            @RequestParam(required = false, defaultValue = "false") boolean includeCancel,
            @Parameter(description = "묶는 축 REQUEST(신청)/PARTNER(거래처, 기본)")
            @RequestParam(required = false) LogisCostDetailResponse.Grain grain) {
        return ApiResponse.success(
                logisCostDetailService.outboundDetail(fromDate, toDate, mode, dtlCds, includeCancel, grain));
    }

    @Operation(summary = "출고 물류비 명세 엑셀 다운로드")
    @GetMapping("/outbound/detail/export")
    public org.springframework.http.ResponseEntity<byte[]> outboundDetailExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false, defaultValue = "ALL") LogisMode mode,
            @Parameter(description = "시행코드(DTL_CD) 다중 선택. 미지정=전체 — 좌측 연도·시행 트리가 쓴다")
            @RequestParam(required = false) java.util.List<Integer> dtlCds,
            @RequestParam(required = false, defaultValue = "false") boolean includeCancel,
            @RequestParam(required = false) LogisCostDetailResponse.Grain grain) {
        var cols = java.util.List.of(
                // ★★F-35 — **레거시 18개 구성 그대로**다(`물류비계산_20260901-20260930.xlsx` 6행 헤더).
                //   발주처 원문: "별도 요청 컬럼이 아니면 **레거시 18개 구성대로**".
                //   뺀 것: 구분·소계명(우리가 만든 칸) · 신청번호 · 상품코드 · 지역 · 도시명 · 학교명.
                //   · 상품코드·지역·도시명은 레거시도 **조회는 하고 그리드에서만 숨긴다**(`물류비계산2.vb:318~320`).
                //   · 학교명은 레거시 **상세보기 전용**이라 그리드에 없다.
                //   · 집계행 라벨(소 계·누 계·합 계·총 계)은 **접수일자 칸**에 들어간다 → `dateOrLabel`.
                //   ‼️**응답(JSON)에서는 빼지 않았다** — 화면이 상세보기에 쓴다. 엑셀만 18개다.
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("접수일자", "dateOrLabel"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("상품명", "productName"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("학년", "grade"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("시행코드", "dtlCd"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("시행명", "detailName"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("거래처코드", "partnerCode"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("거래처명", "partnerName"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("자재합", "materialQty"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("시험지합", "paperQty"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("시험지금액", "paperAmount"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("OMR합", "omrQty"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("OMR금액", "omrAmount"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("기타합", "etcQty"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("기타금액", "etcAmount"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("인원", "inwon"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("기본작업비", "basicAmount"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("출고비", "tradeAmount"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("금액합계", "totalAmount"));

        byte[] xlsx = excel.toXlsx("물류작업비", cols,
                logisCostDetailService.outboundDetail(fromDate, toDate, mode, dtlCds, includeCancel, grain).rows(),
                com.daesung.sales.common.excel.ExcelExportUtil.Heading.period(
                        "물류비계산", fromDate, toDate),
                com.daesung.sales.common.excel.ExcelExportUtil.AGG_BY_ROW_TYPE);
        return excel.asDownload(xlsx, "물류작업비_" + fromDate + "_" + toDate + ".xlsx");
    }

    @Operation(summary = "기간 회수 물류비 집계",
            description = "회수일(REG_DATE) 기간의 회수 물류비를 총계로 집계(자재금액만, 인원비 없음). "
                    + "mode=ALL(전체)/NORMAL(반품 tbl_wol_dtl_b)/ACCIDENT(사고 tbl_wol_dtl). "
                    + "회수 단가는 tbl_logis_cost DTL_CD=0 기준.")
    @GetMapping("/return")
    public ApiResponse<PeriodLogisCost> returnPeriod(
            @Parameter(description = "시작일", example = "2024-01-01")
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일", example = "2024-12-31")
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "구분(전체/반품/사고)", example = "ALL")
            @RequestParam(defaultValue = "ALL") LogisMode mode) {
        return ApiResponse.success(dsreGateway.calcReturnPeriod(from, to, mode));
    }

    @Operation(summary = "회수 물류비 명세(행 + 소계)",
            description = """
                    회수 작업비를 **행 단위**로 본다. 출고는 상세가 있는데 회수는 기간 총계뿐이었다(B-4).

                    · 행마다 회수일자·자재·구분(사고/반품)·수량·적용단가·금액이 나온다.
                    · **총계와 같은 단가 규칙**이다 — 자재구분으로 단가를 고르고(OMR→OMR /
                      단행본·책자→단행본 / 라벨→라벨 / 그 외→시험지), 회수단가는
                      `tbl_logis_cost` 특수행(DTL_CD=0)의 최신값을 쓴다.
                      그래서 **행 합 = `GET /logistics-costs/return` 총계**가 성립한다.
                      (따로 계산하면 두 화면 숫자가 갈리고, 그때 담당자는 둘 다 안 믿는다.)
                    · 소계는 응답의 `byType`(자재구분별)·`byMode`(사고/반품별)로 함께 준다.""")
    @GetMapping("/return/detail")
    public ApiResponse<ReturnCostDetailResponse> returnDetail(
            @Parameter(description = "시작일", required = true)
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일", required = true)
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "구분(전체/반품/사고)", example = "ALL")
            @RequestParam(defaultValue = "ALL") LogisMode mode) {
        return ApiResponse.success(
                ReturnCostDetailResponse.of(from, to, mode, dsreGateway.returnDetail(from, to, mode)));
    }

    // ── 물류단가 관리(기초관리 · DSRE2 tbl_logis_cost write-back) — 근거: 레거시 물류비용등록.vb ──


    @Operation(summary = "물류단가 목록",
            description = """
                    시행코드별 단가에 **상품명·시행명·변경일**을 함께 돌려준다
                    (정본 구분값정리 10.물류비용등록 목록 컬럼).

                    코드만 보여주면 값이 같은 행이 여러 개라 담당자가 어느 줄을 고쳐야 할지 알 수 없다.
                    이 단가가 물류 작업비 계산의 유일한 소스라, 잘못된 줄을 고치면 정산 금액이 틀어진다.

                    dtl_cd=0은 회수단가 특수행이라 상품명·시행명이 비어 있다.""")
    @GetMapping("/rates")
    public ApiResponse<List<LogisCostRate>> listRates() {
        return ApiResponse.success(dsreGateway.listLogisCosts());
    }

    @Operation(summary = "물류단가 일괄 수정",
            description = """
                    체크한 여러 시행의 단가를 한 번에 바꾼다(정본 "선택 항목 일괄 수정").

                    · **입력한 항목만 반영**하고 비운 칸은 기존 값을 유지한다 —
                      빈 칸이 0으로 덮이면 그 시행의 물류비가 통째로 0원이 된다.
                    · ⚠️**작업구분이 다른 행을 함께 선택하면 거부**한다(400).
                      작업구분마다 단가 구성이 달라(반별봉투는 기본작업비·출고비가 0) 한 번에 덮으면 틀어진다.
                      레거시엔 이 검증이 없었고, 정본이 신규로 요구한 항목이다.
                    · 한 건이라도 검증에 걸리면 **아무것도 바꾸지 않는다** —
                      중간까지 반영된 채 실패하면 어디까지 바뀌었는지 알 수 없다.""")
    @PutMapping("/rates")
    public ApiResponse<LogisCostBulkResult> bulkUpdateRates(
            @Valid @RequestBody LogisCostBulkRequest req) {
        workTypeService.assertRegisteredPackType(req.packtype());
        return ApiResponse.success(logisCostBulkService.bulkUpdate(req));
    }

    @Operation(summary = "물류단가 등록/수정(개별)",
            description = """
                    시행코드(dtl_cd)별 단가 upsert(있으면 수정, 없으면 등록). DSRE2에 직접 write-back.

                    ★**이 경로로 고친 행은 '예외'로 등록된다** — 이후 작업구분 기준단가
                    일괄 반영이 그 행을 건너뛴다(36p: "예외 처리된 항목에는 영향을 주지 않아야").
                    담당자가 일부러 다른 값을 넣은 행이 일괄적용 한 번에 조용히 덮이면
                    그 상품이 잘못된 단가로 청구된다.

                    예외를 풀려면 `DELETE /masters/work-types/overrides/{dtlCd}`.""")
    @PutMapping("/rates/{dtlCd}")
    public ApiResponse<Void> upsertRate(
            @Parameter(description = "시행코드(DTL_CD)", example = "10") @PathVariable int dtlCd,
            @Valid @RequestBody LogisCostUpsertRequest req) {
        workTypeService.assertRegisteredPackType(req.packtype());
        dsreGateway.upsertLogisCost(dtlCd, req.paper(), req.omr(), req.etc(), req.label(),
                req.basic(), req.trade(), req.packtype(), req.bSpare());
        workTypeService.markOverride(dtlCd);
        return ApiResponse.success(null);
    }

    @Operation(summary = "★빈 시행 일괄 추가(DSRE 상품 가져오기)",
            description = """
                    단가가 **아직 없는 사용 중 시행**(`tbl_product_dtl.USE_YN='Y'`)을
                    기본값으로 한 번에 추가한다. 응답은 추가한 수 — 화면은 레거시처럼
                    "n개의 항목 추가 완료" 로 쓰면 된다.

                    기본값(레거시 `물류비용등록.vb:563` 그대로):
                    시험지 50 · OMR 50 · 단행본 50 · 라벨 0 · 기본작업비 0 · 출고비 0 ·
                    작업구분 1 · 여분 Y.

                    ★**예외로 치지 않는다** — `PUT /rates/{dtlCd}` 와 다른 점이다.
                    그 경로는 행마다 '예외' 표시를 남겨 이후 작업구분 기준단가 일괄 반영이
                    그 행을 건너뛴다. 빈 칸을 채우려고 한 것이 전부 예외가 되면
                    기준단가가 영원히 안 먹는다(프론트 요청 2026-11-10 §1-3).

                    ★**여러 번 눌러도 안전하다** — 이미 단가가 있는 시행은 건너뛰므로
                    두 번째부터는 0개 추가다.""")
    @PostMapping("/rates/init-missing")
    public ApiResponse<com.daesung.sales.logistics.dto.LogisCostInitResult> initMissingRates() {
        return ApiResponse.success(new com.daesung.sales.logistics.dto.LogisCostInitResult(
                dsreGateway.initMissingLogisCosts()));
    }

    @Operation(summary = "물류단가 삭제",
            description = "시행코드(dtl_cd)의 단가 삭제. 없으면 404.")
    @DeleteMapping("/rates/{dtlCd}")
    public ApiResponse<Void> deleteRate(
            @Parameter(description = "시행코드(DTL_CD)", example = "10") @PathVariable int dtlCd) {
        int deleted = dsreGateway.deleteLogisCost(dtlCd);
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "물류단가가 없습니다. dtl_cd=" + dtlCd);
        }
        return ApiResponse.success(null);
    }

    @Operation(summary = "회수단가 조회",
            description = """
                    회수 단가(dtl_cd=0 특수행)의 현재값.

                    ★**저장은 되는데 읽을 수가 없었다** — PUT만 있고 GET이 없어
                    화면의 회수단가 입력창이 빈 칸으로 열렸다. 지금 값을 모르고 덮어쓰면
                    바꾸려던 항목 말고 다른 항목까지 0으로 밀린다.

                    아직 회수단가 행이 만들어지지 않았으면 0으로 준다(오류가 아니다 —
                    "아직 등록 전"과 "조회 실패"는 화면에서 다르게 다뤄야 한다).""")
    @GetMapping("/rates/return")
    public ApiResponse<ReturnRateResponse> returnRate() {
        return ApiResponse.success(dsreGateway.findLogisCost(0)
                .map(r -> new ReturnRateResponse(r.paper(), r.omr(), r.etc(), r.changedAt()))
                .orElseGet(() -> new ReturnRateResponse(0, 0, 0, null)));
    }

    @Operation(summary = "회수단가 수정",
            description = "회수 단가(dtl_cd=0 특수행)의 PAPER/OMR/ETC 수정. 없으면 생성. 근거: 레거시 회수단가 수정.")
    @PutMapping("/rates/return")
    public ApiResponse<Void> updateReturnRate(@Valid @RequestBody ReturnRateRequest req) {
        dsreGateway.updateReturnRate(req.paper(), req.omr(), req.etc());
        return ApiResponse.success(null);
    }
}
