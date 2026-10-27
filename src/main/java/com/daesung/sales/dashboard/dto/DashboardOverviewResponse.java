package com.daesung.sales.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 기본 통계 대시보드(19p). 근거: 정본 19p 핵심 요구사항 —
 * "당월/누적 순매출액, 더프리미엄 누적매출(점유율1위), 특약점 당월매출 등 KPI 카드 +
 * 제품별 목표대비 차트 + 월별누적트렌드 + 거래처별비중 도넛 + 순매출TOP5".
 *
 * <p>★<b>20p와 원천이 같다</b>. 정본이 "20페이지 매출상세대시보드와 원천데이터 공유 확인"을
 * 조건으로 달았으므로, 순매출 식({@code SALE 공급가 − RETURN 공급가}, 취소 제외)을 20p와
 * 한 곳에서 공유한다. 식이 갈리면 같은 달인데 두 화면 숫자가 다르게 나온다.
 */
@Schema(name = "DashboardOverviewResponse", description = "기본 통계 대시보드(19p)")
public record DashboardOverviewResponse(
        @Schema(description = "기준 연도") int year,
        @Schema(description = "기준 월. quarter로 조회했으면 그 분기의 마지막 달(1분기→3 …)") int month,
        @Schema(description = """
                조회 단위(1~4분기). **월별로 조회했으면 null**(발주처 질의 2026-10-26 ③).
                값이 있으면 `kpi`의 "당월" 자리가 **그 분기 3개월 합**이라는 뜻이다 —
                필드명(monthNetSales 등)은 그대로지만 의미가 분기로 넓어진다.""")
        Integer quarter,
        @Schema(description = "KPI 카드") Kpi kpi,
        @Schema(description = "제품별 목표대비(당해 누적, 목표 등록분만)") List<ProductTarget> productTargets,
        @Schema(description = "월별 누적 트렌드(1~12월)") List<TrendPoint> monthlyTrend,
        @Schema(description = "거래처별 비중(당해 누적, 비중 큰 순)") List<Share> partnerShares,
        @Schema(description = "순매출 TOP5(당해 누적)") List<Share> topProducts,

        @Schema(description = """
                출고유형별 순매출(19p 상세 대시보드). 정상출고·위탁출고·증정용·교사용·반품·취소.
                ★키는 enum 이름, `name`은 한글 표기다 — 화면이 라벨을 다시 만들지 않아도 된다.""")
        List<Share> shipTypeShares,

        @Schema(description = """
                지역별 순매출(19p). 축은 **거래처의 지역**이다(매출에는 지역 컬럼이 없다).
                ‼️지역이 비어 있는 거래처의 매출은 이 축에서 빠진다 — 합이 총매출과 다를 수 있다.""")
        List<Share> regionShares,

        @Schema(description = "창고별 재고 수량(19p '창고별 재고 수량 · 물류/위탁'). 사용 중인 창고만.")
        List<WarehouseStock> warehouseStocks,

        @Schema(description = """
                **세부구분별** 순매출(구 매출구분). 제품별 차트를 이 축으로 묶는다
                (2026-09-17 피드백 화면11-3 — "제품별 목표달성 차트를 세부구분 필드 값 기준 그룹화").
                ★대분류(5종 고정)와 다른 축이다 — 대분류로 합산하고 세부구분으로 드릴다운한다
                (발주처 회신 2026-08-20).""")
        List<Share> divisionShares
) {
    @Schema(name = "DashboardKpi")
    public record Kpi(
            @Schema(description = "당월 순매출액") long monthNetSales,
            @Schema(description = "누적 순매출액(1월~기준월)") long cumulativeNetSales,
            @Schema(description = """
                    점유율 1위 상품군(대분류)의 누적 순매출. 정본 예시는 '더프리미엄'이지만
                    특정 이름을 코드에 박지 않는다 — **점유율 1위를 계산해서** 낸다.
                    이름을 박으면 그 상품군이 1위가 아니게 된 순간 카드가 거짓말을 한다.""")
            long topCategoryNetSales,
            @Schema(description = "점유율 1위 상품군 명칭(모의고사·교재 …). 매출이 없으면 null")
            String topCategoryName,
            @Schema(description = "점유율 1위 상품군의 비중 %") Double topCategorySharePct,
            @Schema(description = "특약점 당월 순매출(거래처구분='특약점')") long dealerMonthNetSales
    ) {
    }

    @Schema(name = "DashboardProductTarget")
    public record ProductTarget(
            @Schema(description = "상품 id") Long productId,
            @Schema(description = "상품명") String productName,
            @Schema(description = "목표(연간 목표가 있으면 그 값, 없으면 월 목표 합)") long target,
            @Schema(description = "누적 실적(순매출)") long actual,
            @Schema(description = "달성률 %. 목표 0이면 null") Double achievementPct
    ) {
    }

    @Schema(name = "DashboardTrendPoint")
    public record TrendPoint(
            @Schema(description = "월(1~12)") int month,
            @Schema(description = "당월 순매출") long netSales,
            @Schema(description = "누적 순매출") long cumulative
    ) {
    }

    @Schema(name = "DashboardShare")
    public record Share(
            @Schema(description = "키(거래처 id 또는 상품 id)") String key,
            @Schema(description = "표시명") String name,
            @Schema(description = "순매출") long netSales,
            @Schema(description = "비중 %(전체 대비). 전체가 0이면 null") Double sharePct
    ) {
    }

    @Schema(name = "DashboardWarehouseStock", description = "창고별 재고 수량")
    public record WarehouseStock(
            @Schema(description = "창고 id") Long warehouseId,
            @Schema(description = "창고명") String name,
            @Schema(description = "창고구분 MAIN(물류)/CONSIGN(위탁)") String type,
            @Schema(description = """
                    재고 수량(부). ★금액이 아니라 **수량**이다 — 그래서 Share를 쓰지 않는다.
                    같은 그릇에 담으면 언젠가 금액으로 더해진다.""")
            long qty
    ) {
    }
}
