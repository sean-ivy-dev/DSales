package com.daesung.sales.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 도서 Y/N 항목 <b>일괄 변경</b> 요청. 근거: 발주처 요청(자료요청서 1-3 회신) —
 * "Y/N 값들은 추후 변경될 수 있습니다. 각 열(Web게시여부·면세여부·수불부노출 등)을
 * 일괄로 처리할 수 있는 기능(전체선택 등)이 필요합니다."
 *
 * <p><b>지정하지 않은 플래그는 건드리지 않는다</b>(null = 그대로). 한 요청에서 여러 플래그를
 * 동시에 바꿀 수도 있고, 하나만 바꿀 수도 있다.
 */
@Schema(name = "ProductFlagBulkRequest", description = "도서 Y/N 항목 일괄 변경")
public record ProductFlagBulkRequest(

        @Schema(description = """
                분류코드. 이걸 주면 **그 분류의 도서 전체**가 대상이 된다(productIds는 무시).
                근거: 발주처 구조보완요청안(2026-08-31) — "수불부/단가 노출 Y/N 값은
                DSRE 병행 운영/매출프로그램 단독 운영 여부에 따라 결정되는 값으로,
                **분류명(콘텐츠) 단위로 설정**됩니다."
                ★저장은 도서 단위 그대로다(단일 소스 유지). 분류는 **거는 단위**다 —
                분류별 설정을 따로 저장하면 도서 값과 어긋났을 때 어느 쪽이 이기는지가
                또 하나의 규칙이 된다.""", example = "S2026A02")
        String catCode,

        @Schema(description = """
                대상 도서 id 목록(화면에서 선택한 행). **catCode를 주면 무시된다.**
                둘 중 하나는 있어야 한다 — 없으면 서비스가 거부한다.""")
        @Size(max = 1000, message = "한 번에 1000건까지 처리할 수 있습니다.")
        List<Long> productIds,

        @Schema(description = "Web게시여부 — 미지정이면 건드리지 않음", example = "true")
        Boolean webVisible,

        @Schema(description = "지정 거래처 전용(화이트리스트) — 미지정이면 건드리지 않음. "
                + "true면 '열기'로 지정한 거래처에만 노출(11/04 회신 §1)", example = "false")
        Boolean restrictedVisible,

        @Schema(description = "면세여부 — 미지정이면 건드리지 않음", example = "true")
        Boolean taxFree,

        @Schema(description = "수불부노출 — 미지정이면 건드리지 않음", example = "true")
        Boolean ledgerVisible,

        @Schema(description = "단가노출 — 미지정이면 건드리지 않음. 수불부노출과 별개 축", example = "true")
        Boolean priceVisible,

        @Schema(description = "사용여부 — 미지정이면 건드리지 않음", example = "true")
        Boolean useYn,

        @Schema(description = "재고관리 여부 — 미지정이면 건드리지 않음. "
                + "끄면 매출 시 재고가 차감되지 않으니 주의", example = "true")
        Boolean stockManaged
) {
    /** 바꿀 플래그가 하나도 없으면 무의미한 요청이다(전 건을 훑고 아무것도 안 한다). */
    public boolean hasNoFlag() {
        return webVisible == null && taxFree == null && ledgerVisible == null && priceVisible == null
                && useYn == null && stockManaged == null && restrictedVisible == null;
    }
}
