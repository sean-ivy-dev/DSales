package com.daesung.sales.logistics.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 물류 작업비 명세(28p) — 행 + 소계. 근거: 레거시 물류비계산2.vb 그리드
 * ({@code GROUP BY … WITH ROLLUP}) + 정본 28p 주요 데이터 항목.
 *
 * <p>★레거시는 화면 체크박스({@code CheckBox_ViewDetail})로 <b>묶는 축이 갈린다</b>:
 * <pre>
 *   상세 ON  : 상품 · 학년 · 시행 · <b>신청</b>
 *   상세 OFF : 상품 · 학년 · 시행 · <b>거래처</b>
 * </pre>
 * 우리는 가장 잘게(신청·거래처 둘 다) 한 번 받아 두고 여기서 접는다 —
 * 모드마다 쿼리를 따로 두면 물류비 계산식이 두 벌로 갈라진다.
 *
 * <p>소계는 레거시 ROLLUP 그대로 <b>시행 → 학년 → 상품 → 총계</b> 순으로 붙는다.
 */
@Schema(name = "LogisCostDetailResponse", description = "물류 작업비 명세(28p)")
public record LogisCostDetailResponse(
        @Schema(description = "조회 시작일") LocalDate fromDate,
        @Schema(description = "조회 종료일") LocalDate toDate,
        @Schema(description = "구분 ALL/NORMAL/ACCIDENT") String mode,
        @Schema(description = "묶는 축 REQUEST(신청)/PARTNER(거래처)") Grain grain,
        @Schema(description = "명세 + 소계") List<Row> rows,
        @Schema(description = "총 금액합계") long totalAmount,

        @Schema(description = """
                ‼️**작업비가 안 잡혀 목록에서 빠진 단독 상품** — 건수·사유별 건수·표본.

                0원 행을 끼워 넣지 않는 대신 여기로 알린다 — 0원 줄은 담당자가
                "작업비 0원 확정"으로 읽어서, 등록이 덜 된 것과 구분되지 않는다.

                ★전건을 나열하지 않는다. 단가 마스터가 비어 있으면 한 달에 수백 개가 걸려
                경고가 아니라 소음이 된다(운영 2026-09: 400여 개). 화면은 사유별 한 줄
                ("단가 미등록 341개")로 띄우고, 표본은 무엇이 걸렸는지 감을 잡는 용도다.

                `total = 0`이면 빠진 상품이 없다는 뜻이다.""")
        MissingRate missingRate
) {
    /** 표본으로 싣는 최대 건수. */
    public static final int MISSING_SAMPLE_LIMIT = 10;

    /** 작업비가 0원이라 행에서 빠진 이유. 사유마다 담당자가 열어야 할 화면이 다르다. */
    @Schema(name = "LogisCostMissingReason")
    public enum MissingReason {
        NO_RATE("단가 미등록", "물류비용등록 > 상품별 단가에서 작업구분 선택"),
        NO_MATERIAL("자재 구성 없음", "도서관리 > 세트구성 탭에서 자재 매칭"),
        ZERO_RATE("작업구분 단가가 0", "물류비용등록 > 작업구분 관리에서 단가 입력");

        private final String label;
        private final String action;

        MissingReason(String label, String action) {
            this.label = label;
            this.action = action;
        }

        public String label() {
            return label;
        }

        public String action() {
            return action;
        }
    }

    /** 빠진 상품 한 건. */
    @Schema(name = "LogisCostMissingProduct")
    public record MissingProduct(
            @Schema(description = "상품코드") String productCode,
            @Schema(description = "상품명") String productName,
            @Schema(description = "사유") MissingReason reason
    ) {
    }

    @Schema(name = "LogisCostMissingReasonCount")
    public record ReasonCount(
            @Schema(description = "사유 코드") MissingReason reason,
            @Schema(description = "사유 표시명", example = "단가 미등록") String label,
            @Schema(description = "담당자가 할 일", example = "물류비용등록 > 상품별 단가에서 작업구분 선택")
            String action,
            @Schema(description = "상품 수") int count
    ) {
    }

    @Schema(name = "LogisCostMissingRate")
    public record MissingRate(
            @Schema(description = "빠진 상품 수(전체)") int total,
            @Schema(description = "사유별 상품 수. 건수 많은 순") List<ReasonCount> reasons,
            @Schema(description = "표본(최대 10건, 상품코드 순)") List<MissingProduct> samples
    ) {
        public static final MissingRate NONE = new MissingRate(0, List.of(), List.of());

        public static MissingRate of(List<MissingProduct> all) {
            if (all.isEmpty()) {
                return NONE;
            }
            java.util.Map<MissingReason, Integer> byReason = new java.util.EnumMap<>(MissingReason.class);
            for (MissingProduct m : all) {
                byReason.merge(m.reason(), 1, Integer::sum);
            }
            List<ReasonCount> reasons = byReason.entrySet().stream()
                    .sorted(java.util.Map.Entry.<MissingReason, Integer>comparingByValue().reversed())
                    .map(e -> new ReasonCount(e.getKey(), e.getKey().label(), e.getKey().action(),
                            e.getValue()))
                    .toList();
            // ★사유가 적은 쪽(예: 자재 구성 없음 1건)이 수백 건에 묻히지 않게,
            //   사유마다 먼저 한 건씩 담고 남는 자리를 상품코드 순으로 채운다.
            List<MissingProduct> sorted = all.stream()
                    .sorted(java.util.Comparator.comparing(MissingProduct::productCode))
                    .toList();
            java.util.LinkedHashSet<MissingProduct> picked = new java.util.LinkedHashSet<>();
            for (ReasonCount rc : reasons) {
                sorted.stream().filter(m -> m.reason() == rc.reason()).findFirst().ifPresent(picked::add);
            }
            for (MissingProduct m : sorted) {
                if (picked.size() >= MISSING_SAMPLE_LIMIT) {
                    break;
                }
                picked.add(m);
            }
            return new MissingRate(all.size(), reasons, List.copyOf(picked));
        }
    }

    /** 마지막 묶음 축. 레거시 '상세보기' 체크박스에 대응한다. */
    @Schema(name = "LogisCostGrain")
    public enum Grain {
        /** 상세 ON — 신청번호까지 펼친다. */
        REQUEST,
        /** 상세 OFF — 거래처로 묶는다(한 거래처의 여러 신청이 한 줄). */
        PARTNER
    }

    @Schema(name = "LogisCostDetailRowView")
    public record Row(
            @Schema(description = "행 종류", allowableValues = {"DETAIL", "DTL_SUBTOTAL",
                    "GRADE_SUBTOTAL", "PRODUCT_SUBTOTAL", "TOTAL"})
            String rowType,
            @Schema(description = "소계 표시명. 명세행은 null") String label,
            @Schema(description = "접수일자(명세행)") LocalDate reqDate,

            @Schema(description = """
                    ★**엑셀 첫 칸 전용 파생값**(F-35). 명세행이면 접수일자(`2026.09.05`),
                    집계행이면 `소 계`·`누 계`·`합 계`·`총 계`.

                    ‼️레거시 실파일이 그 모양이다 — 컬럼이 **18개뿐이고 '구분'·'소계명' 칸이 없으며**,
                    집계행은 **접수일자 자리에 라벨**을 넣는다(`물류비계산_20260901-20260930.xlsx` 6행 헤더 +
                    165행 `['소 계','더 프리미엄',1,3701,…]`). 발주처 F-35 가 "레거시 18개 구성대로"다.

                    화면은 `rowType`·`label` 을 그대로 쓰면 된다 — 이 칸은 엑셀에서만 쓴다.""")
            String dateOrLabel,
            @Schema(description = "신청번호(grain=REQUEST일 때)") Integer reqCd,
            @Schema(description = "상품코드") String productCode,
            @Schema(description = "상품명") String productName,
            @Schema(description = "학년") String grade,
            @Schema(description = "시행코드") Integer dtlCd,
            @Schema(description = "시행명") String detailName,
            @Schema(description = "거래처코드") String partnerCode,
            @Schema(description = "거래처명") String partnerName,
            @Schema(description = "지역(거래처 관할명). 레거시 '특약지역명'") String region,
            @Schema(description = "도시명. 레거시 '도시지역명'") String cityName,
            @Schema(description = "학교·학원명. 못 찾으면 null") String schoolName,

            @Schema(description = "자재 합") long materialQty,
            @Schema(description = "시험지 합") long paperQty,
            @Schema(description = "시험지 금액") long paperAmount,
            @Schema(description = "OMR 합") long omrQty,
            @Schema(description = "OMR 금액") long omrAmount,
            @Schema(description = "기타 합") long etcQty,
            @Schema(description = "기타 금액") long etcAmount,
            @Schema(description = "인원") int inwon,
            @Schema(description = "기본작업비") long basicAmount,
            @Schema(description = "출고비") long tradeAmount,
            @Schema(description = "금액합계") long totalAmount
    ) {
    }
}
