package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * [도서관리] '거래처별 노출' 탭 한 줄 — <b>도서 한 권에 대한 거래처별 상태</b>.
 * 근거: 11/04 클라이언트 회신 §2(설정 위치를 거래처 축 → 도서 축으로 옮김).
 *
 * <p>★예전 {@code PartnerProductVisibilityRow}(거래처 하나 × 도서 수천 권)와 축이 반대다.
 * 도서 한 권을 지정 거래처에만 열려면 거래처 축으로는 도서를 몇 천 번 찾아야 했다.
 */
@Schema(name = "ProductPartnerVisibilityRow",
        description = "도서 1권 × 거래처 — 계산된 현재 노출값 + '열기' 지정 여부")
public record ProductPartnerVisibilityRow(
        @Schema(description = "거래처 id") Long partnerId,
        @Schema(description = "거래처코드") String partnerCode,
        @Schema(description = "거래처명") String partnerName,
        @Schema(description = "거래처구분(특약점·학원 …) — 목록 필터 축") String clientCategory,
        @Schema(description = """
                노출 판단 순서(11/04 §1 ①②③④)를 **그대로 적용한 결과**.
                판정은 서버의 단일 소스(`ProductRepository.isDealerVisible`)가 계산한다 —
                화면이 규칙을 다시 구현하면 신청 화면과 어긋난다.""")
        boolean visible,
        @Schema(description = """
                이 거래처에 '열기'로 지정돼 있는지 — 화면의 체크박스 상태.
                ‼️'닫기' 상태는 없다(11/04 §1). 닫는 것은 이 지정을 지우는 것이고,
                그러면 노출은 ②(지정 거래처 전용)·④(대분류 매핑)에서 떨어진다.""")
        boolean open,
        @Schema(description = "도서 자체 Web게시 — N이면 무엇을 지정해도 비노출이다(①이 최우선). 화면은 경고로 표시")
        boolean productWebVisible,
        @Schema(description = "도서가 '지정 거래처 전용'인지(②). true면 열기 없는 거래처는 대분류 매핑과 무관하게 비노출")
        boolean productRestrictedVisible
) {
}
