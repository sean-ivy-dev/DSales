package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * [도서관리] '거래처별 노출' — <b>분류 단위 일괄 적용</b>. 근거: 11/04 클라이언트 회신 §2
 * "[도서관리] '거래처별 단가·노출 매핑' 탭에 추가: ... 분류명 체크 → 하위 도서 일괄 선택 ·
 * 거래처 체크박스 다중 선택 → [열기]/[닫기] 일괄 적용".
 *
 * <p>★<b>{@link ProductVisibilityBulkRequest}(도서 1권 × 거래처 여러 곳)로는 이 요구를
 * 못 채운다</b> — "분류명 체크"는 도서 여러 권을 한 번에 고르는 축이다. 이 DTO가
 * {@code catCode}×{@code partnerIds}(또는 {@code productIds}×{@code partnerIds})
 * 전체 조합을 한 호출로 처리한다.
 *
 * <p>대상 결정 규칙은 {@link ProductFlagBulkRequest}와 같다 — {@code catCode}를 주면
 * 그 분류 도서 전체(productIds는 무시), 아니면 productIds. 둘 다 없으면 서비스가 거부한다.
 */
public record ProductVisibilityCategoryBulkRequest(
        @Schema(description = "분류코드. 주면 그 분류의 도서 전체가 대상(productIds는 무시)", example = "A2092A")
        String catCode,

        @Schema(description = "대상 도서 id 목록(화면에서 개별 선택한 경우). catCode를 주면 무시된다.")
        @Size(max = 1000, message = "한 번에 1000권까지 처리할 수 있습니다.")
        List<Long> productIds,

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
    public boolean hasNoTarget() {
        return (catCode == null || catCode.isBlank()) && (productIds == null || productIds.isEmpty());
    }
}
