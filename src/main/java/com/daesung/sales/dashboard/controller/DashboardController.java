package com.daesung.sales.dashboard.controller;

import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.dashboard.dto.DashboardOverviewResponse;
import com.daesung.sales.dashboard.dto.DashboardResponse;
import com.daesung.sales.dashboard.dto.TargetRequest;
import com.daesung.sales.dashboard.dto.TargetResponse;
import com.daesung.sales.dashboard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 매출관리 · 대시보드. 목표 대비 실적. 목표 입력은 재무/관리자(예산 권한). */
@Tag(name = "매출관리 · 대시보드", description = "매출목표 등록 + 목표 대비 실적·달성률·전년비")
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final com.daesung.sales.dashboard.service.ActionItemService actionItemService;

    @Operation(summary = "매출목표 등록/수정",
            description = """
                    연·월(비우면 연간)·대상(전사/사업부문/상품) 금액 upsert.

                    ★**지금은 관리자만 됩니다.** 권한표(V52)에 `재무 × 대시보드 = 조회`로 들어 있어
                    재무 계정은 403 입니다 — 2026-08-18 확정 권한표(매출관리 × 재무 = 조회)를 따른 것입니다.

                    ‼️예전 설명에 "예산 입력 권한(재무/관리자)만"이라고 적혀 있었는데 **실제 동작과 달랐습니다**
                    (프론트 실측 지적 2026-10-01). 동작이 맞고 설명이 틀렸던 것이라 설명을 고쳤습니다.

                    재무에게 열지는 발주처 정책 변경 사항입니다. 권한은 코드가 아니라 DB 표라
                    관리자 화면에서 바꿀 수 있고, 배포는 필요 없습니다.""")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @PostMapping("/targets")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> setTarget(@Valid @RequestBody TargetRequest req) {
        dashboardService.upsertTarget(req);
        return ApiResponse.success(null);
    }

    @Operation(summary = "매출 대시보드(목표 대비 실적)",
            description = """
                    연도별 월 목표/실적(순매출)·달성률·전년 동월 대비 성장률 + 연간 합계.

                    · 대상은 scope로 고른다 — COMPANY(전사, 기본) / DIVISION(사업부문) / PRODUCT(상품).
                    · **연간 목표만 등록된 경우 월 셀 목표는 0이고 연간 요약에만 잡힌다.**
                      연간 금액을 12로 나눠 뿌리면 있지도 않은 월 목표를 만들어내기 때문이다.
                    · **비교 연도를 고를 수 있다**(정본 20p "기준/비교연도"). 미지정이면 전년이다.
                      2026을 2024와 견주는 식으로 임의 연도 비교가 된다.
                    · 비교연도 실적은 우리 매출에서 계산하되, 그 해 매출이 통째로 없으면
                      저장된 확정 실적(entryType=ACTUAL)으로 채운다.
                    · **미도래 월은 당월 실적이 비어 나온다**(0으로 채우면 차트가 바닥으로 떨어진 것처럼 보인다).
                      누적은 비우지 않는다.""")
    @GetMapping("/sales")
    public ApiResponse<DashboardResponse> sales(
            @Parameter(description = "기준 연도", example = "2026") @RequestParam int year,
            @Parameter(description = "비교 연도(미지정 시 전년). 예: 2026을 2024와 견주려면 2024",
                    example = "2025")
            @RequestParam(required = false) Integer compareYear,
            @Parameter(description = "대상 축(미지정=COMPANY 전사)")
            @RequestParam(required = false) com.daesung.sales.dashboard.entity.TargetScope scope,
            @Parameter(description = "사업부문명(scope=DIVISION)", example = "더프리미엄")
            @RequestParam(required = false) String scopeKey,
            @Parameter(description = "상품 id(scope=PRODUCT)") @RequestParam(required = false) Long productId) {
        return ApiResponse.success(
                dashboardService.salesDashboard(year, compareYear, scope, scopeKey, productId));
    }

    @Operation(summary = "기본 통계 대시보드(19p)",
            description = """
                    KPI 카드 + 제품별 목표대비 + 월별 누적트렌드 + 거래처별 비중 + 순매출 TOP5.

                    · **20p와 원천이 같다.** 정본 19p가 "20페이지 매출상세대시보드와 원천데이터 공유"를
                      조건으로 달았으므로 순매출 식(매출 공급가 − 반품 공급가, 취소 제외)을 한 곳에서 쓴다.
                      월별 트렌드는 20p가 쓰는 계산을 그대로 재사용한다(스냅샷/실시간 폴백까지 동일).
                    · **점유율 1위 상품군**은 계산해서 낸다. 정본 예시가 '더프리미엄'이지만
                      이름을 코드에 박지 않는다 — 그 상품군이 1위가 아니게 된 순간 카드가 거짓말을 한다.
                    · 제품별 목표대비는 **목표가 등록된 상품만** 나온다.
                      목표 없는 상품까지 0으로 깔면 차트가 의미를 잃는다.
                    · 누적은 1월~기준월이다.""")
    @GetMapping("/overview")
    public ApiResponse<DashboardOverviewResponse> overview(
            @Parameter(description = "기준 연도(미지정 시 올해)", example = "2026")
            @RequestParam(required = false) Integer year,
            @Parameter(description = "기준 월 1~12(미지정 시 이번 달). **quarter를 주면 무시된다**")
            @RequestParam(required = false) Integer month,
            @Parameter(description = """
                    분기 단위 조회(1~4). 주면 **월 대신 분기 3개월 합**으로 KPI를 본다
                    (발주처 질의 2026-10-26 ③). month는 분기의 마지막 달로 자동 치환된다.""",
                    example = "3")
            @RequestParam(required = false) Integer quarter) {
        if (quarter != null && (quarter < 1 || quarter > 4)) {
            throw new com.daesung.sales.common.exception.BusinessException(
                    com.daesung.sales.common.exception.ErrorCode.INVALID_INPUT,
                    "분기는 1~4만 가능합니다: " + quarter);
        }
        java.time.LocalDate now = java.time.LocalDate.now();
        int y = (year != null) ? year : now.getYear();
        int m = (quarter != null) ? quarter * 3 : (month != null) ? month : now.getMonthValue();
        return ApiResponse.success(dashboardService.overview(y, m, quarter));
    }

    @Operation(summary = "매출목표 목록",
            description = "연도의 등록된 목표·실적 전체(전사/사업부문/상품, 월별·연간). 관리 화면용.")
    @GetMapping("/targets")
    public ApiResponse<java.util.List<TargetResponse>> targets(
            @Parameter(description = "연도", example = "2026") @RequestParam int year) {
        return ApiResponse.success(dashboardService.listTargets(year));
    }

    @Operation(summary = "매출목표 삭제",
            description = """
                    잘못 넣은 목표 한 줄을 지운다.

                    ‼️**지우는 길이 없었다.** 금액을 0으로 덮을 수는 있지만 목록에 **0짜리 줄이 남아**,
                    담당자는 "목표가 0인 달"과 "목표를 안 넣은 달"을 구분할 수 없다
                    (대시보드가 그 둘을 다르게 보여준다 — 0은 미달, 미입력은 빈칸).

                    등록과 **같은 권한**이다(ADMIN·FINANCE).""")
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE')")
    @DeleteMapping("/targets/{id}")
    public ApiResponse<Void> deleteTarget(
            @Parameter(description = "매출목표 id(목록 응답의 id)") @PathVariable Long id) {
        dashboardService.deleteTarget(id);
        return ApiResponse.success(null);
    }

    @Operation(summary = "할 일 목록",
            description = """
                    지금 손대야 할 것을 한 번에 센다 — 위탁 정산 대기 · 미확인 출고요청 · 담보 만기 임박.
                    ★**0건인 항목은 내려보내지 않는다.** '0건' 카드가 늘 떠 있으면 담당자가 매번
                    숫자를 읽어 할 일이 없음을 확인해야 한다 — 목록에 있다는 것 자체가 신호여야 한다.
                    각 숫자는 해당 화면이 쓰는 것과 **같은 판정**이라 카드와 화면이 갈리지 않는다.""")
    @GetMapping("/actions")
    public ApiResponse<java.util.List<com.daesung.sales.dashboard.dto.ActionItemResponse>> actions() {
        return ApiResponse.success(actionItemService.actionItems());
    }
}
