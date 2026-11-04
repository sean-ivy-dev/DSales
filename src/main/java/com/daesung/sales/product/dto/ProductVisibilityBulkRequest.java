package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * [도서관리] '거래처별 노출' — <b>선택 거래처 일괄 적용</b>.
 * 근거: 11/04 회신 §2 + 정본 1탭 도서관리 "거래처구분 필터로 좁힌 뒤 여러 거래처를 체크해
 * '선택 거래처 일괄 적용'"(거래처별 단가·노출 매핑 탭이 이미 쓰는 패턴).
 */
public record ProductVisibilityBulkRequest(
        @Schema(description = "대상 거래처 id 목록(화면에서 체크한 행)")
        @NotEmpty(message = "거래처를 한 곳 이상 선택하세요.")
        @Size(max = 1000, message = "한 번에 1000곳까지 처리할 수 있습니다.")
        List<Long> partnerIds,

        @Schema(description = """
                true=열기 지정 / false=열기 해제.
                ‼️해제는 '강제 비노출'이 아니라 **지정을 지우는 것**이다 — 그 뒤 노출은
                도서가 '지정 거래처 전용'이면 비노출, 아니면 거래처x대분류 매핑을 따른다.""",
                example = "true")
        boolean open,

        @Schema(description = "변경 사유(선택) — 변경 이력에 남는다") String reason
) {
}
