package com.daesung.sales.sale.controller;

import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.dsre.gateway.DuffChargeMode;
import com.daesung.sales.sale.dto.DuffImportResponse;
import com.daesung.sales.sale.service.DuffSalesImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매출관리 · 매출일괄등록(14p, 더프). DSRE2 의존 → daesung.dsre.enabled=true일 때만.
 *
 * <p>{@code /sales/bulk}(교재)와 <b>다른 화면</b>이다 — 더프는 응시인원 기반 종량제라
 * 부수 기반과 계산이 다르다(정본 14p).
 */
@Tag(name = "매출관리 · 매출일괄등록(더프)",
        description = "DSRE2 신청내역을 응시인원 기반으로 매출 일괄 등록(14p). 소스키 멱등")
@RestController
@RequestMapping("/sales/duff")
@ConditionalOnProperty(name = "daesung.dsre.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DuffSalesController {

    private final DuffSalesImportService duffSalesImportService;

    private static final String MODE_DESC = """
            처리구분 모드. **정가(단가) 자체가 달라진다** — tbl_product_amt가 처리/비처리별로
            단가를 따로 갖고 있어서, 단순한 조회 필터가 아니다.
            ALL(모두·기본, 신청건의 처리구분을 따름) / PROCESSED(처리) / UNPROCESSED(비처리)""";

    private static final String COMPLETE_DESC =
            "true면 발송완료(state='D') 건만. 레거시 「발송완료만」 체크박스와 같다";

    @Operation(summary = "매출일괄등록(더프) 미리보기(dryRun)",
            description = """
                    DSRE2 신청내역을 조회만 — 매출 생성 없음. 정본 14p 항목(처리순번·신청일자·매출코드·
                    거래처·학교·학년·처리구분·청구구분·신청/처리/비처리 인원·단가·총금액)을 그대로 낸다.
                    청구인원은 우선순위 **등록(청구구분 R) → 조회모드 → 처리구분 N이면 비처리 → 처리**로 정해지며,
                    어느 근거로 골랐는지 chargeBasis에 실린다.

                    ★**`productId` 를 같이 보내면 그 도서로 미리 계산해 보여 준다**(등록과 같은 규칙).
                    비우면 DSRE 과목코드로 도서를 찾는데, **과목코드와 도서코드는 체계가 달라
                    대개 전부 `UNMAPPED_PRODUCT` 로 나온다** — 그게 고장이 아니라
                    "아직 도서를 고르지 않은 상태"다(레거시도 담당자가 콤보에서 고른다).
                    ‼️등록할 때와 **같은 `productId` 를 넣어** 미리보기해야 금액·인원이 실제와 같다.""")
    @GetMapping("/preview")
    public ApiResponse<DuffImportResponse> preview(
            @Parameter(description = "신청일 시작(yyyy-MM-dd)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "신청일 종료(yyyy-MM-dd)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = MODE_DESC) @RequestParam(required = false) DuffChargeMode mode,
            @Parameter(description = COMPLETE_DESC)
            @RequestParam(required = false, defaultValue = "false") boolean onlyComplete,
            @Parameter(description = """
                    담당자가 고른 도서 id — **목록 전체에 적용해** 미리 계산한다.
                    등록(`POST /sales/duff/import`)의 `productId` 와 같은 값이다.""")
            @RequestParam(required = false) Long productId) {
        return ApiResponse.success(duffSalesImportService
                .importSales(fromDate, toDate, mode, onlyComplete, productId, true));
    }

    @Operation(summary = "매출일괄등록(더프) 실행 — 레거시 방식(고르고 고쳐서 등록)",
            description = """
                    신청내역을 매출로 생성. 소스키(모드:신청:거래처:학교:과목) 멱등이라
                    재실행해도 이미 등록분은 스킵된다.
                    인원 0 이하 건은 등록하지 않는다(정본 14p).

                    ★**마감 검사는 줄마다 실제 거래일**로 한다. 거래일자를 지정하면 그 날,
                    비우면 줄마다 **신청일**이라 한 호출 안에서도 달이 섞인다.
                    마감된 달에 걸리는 줄은 **그 줄만** `SKIPPED` 로 빠지고 사유가 실린다 —
                    오래된 신청 한 줄 때문에 이번 달 것까지 통째로 막히면 쓸 수 없다.
                    (거래일자를 통일해 보낸 경우에는 시작 전에 한 번에 막는다.)

                    ★<b>본문으로 받는다</b>(발주처 §1 "매출일괄등록은 기존 그대로 유지" + 레거시 매출가져오기.vb).
                    · `sourceKeys` — 미리보기에서 **체크한 줄만** 등록(비우면 기간 전체)
                    · `productId` — 담당자가 고른 도서를 **체크한 줄 전부에** 적용(레거시 아래 콤보박스)
                    · `overrides[]` — 줄별 인원·정가·공급률·할인액·총금액 수정(레거시 '금액수정가능')
                    · `salesDate` — 비우면 **신청일 기준**, 주면 그 날로 통일
                    · `memo` — 등록되는 모든 줄에 붙는 공통 메모(원천 메모 뒤에 덧붙는다)

                    ‼️미리보기와 **같은 기간·모드**를 함께 보내야 한다 — 범위가 다르면 그 소스키가
                    목록에 없어 조용히 빠진다. 본문을 통째로 비우면 예전처럼 기간 전체를 등록한다.""")
    @PostMapping("/import")
    public ApiResponse<DuffImportResponse> importSales(
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            com.daesung.sales.sale.dto.DuffImportRequest body) {
        com.daesung.sales.sale.dto.DuffImportRequest req = (body != null) ? body
                : new com.daesung.sales.sale.dto.DuffImportRequest(
                        null, null, null, null, null, null, null, null,
                        null, null, null, null);
        return ApiResponse.success(duffSalesImportService.importSales(req, false));
    }
}
