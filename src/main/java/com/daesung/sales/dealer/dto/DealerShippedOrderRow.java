package com.daesung.sales.dealer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 특약점 사이트용 <b>발송완료 주문</b> 한 줄 — 송장 조회(10/26 §4)와 거래명세서(§5)의 공통 목록.
 *
 * <p>§4 — "표시 조건: 배송구분 <b>택배</b> AND 진행상태 <b>발송완료(D)</b> /
 * 데이터: 매출 {@code shipment.tracking_no} · {@code courier_name}(V44) /
 * 입력 위치: 매출 [작업요청서 &gt; 송장번호 입력] … <b>사이트에는 업로드 기능을 두지 않는다</b>."
 * §5 — "<b>조회·출력 전용</b>이다. 표시 대상: 매출에서 <b>발송완료(D)</b> 처리된 건부터
 * 보여준다. 발송준비중까지는 내부에서 수정될 수 있으므로 표시하지 않는다."
 *
 * <p>★두 메뉴가 <b>같은 목록</b>을 본다 — 둘 다 "내 발송완료 주문"이고, 송장 메뉴는 거기서
 * 택배 건만 고르면 된다({@code deliveryType=COURIER} 로 걸러 달라고 할 수 있다).
 * 목록을 둘로 만들면 조건이 갈라져 한쪽에만 뜨는 주문이 생긴다.
 */
@Schema(name = "DealerShippedOrderRow", description = "특약점 사이트용 발송완료 주문")
public record DealerShippedOrderRow(

        @Schema(description = "주문번호. 거래명세서를 뽑을 때 이 값을 쓴다", example = "E-20261102-7")
        String orderNo,

        @Schema(description = "주문(거래)일자", example = "2026-11-02") LocalDate orderDate,

        @Schema(description = "학교/학원명. 주문 안에 여러 학교가 섞이면 비운다") String schoolName,

        @Schema(description = "배송구분 코드(COURIER=택배 / FREIGHT=화물)") String deliveryType,
        @Schema(description = "배송구분 명칭") String deliveryTypeName,

        @Schema(description = "총 수량") long totalQty,
        @Schema(description = "총 금액") long totalAmount,

        @Schema(description = """
                송장 — 박스 수만큼 있다(V106). **화물 건은 빈 목록**이다.
                ‼️송장번호를 누르면 택배사 조회 페이지를 새 창으로 여는 것은 사이트 몫이다 —
                §4 "택배사 API는 연동하지 않는다".""")
        List<Tracking> trackings
) {
    @Schema(name = "DealerTracking")
    public record Tracking(
            @Schema(description = "박스 번호(1부터)", example = "1") int boxNo,
            @Schema(description = "택배사", example = "CJ대한통운") String courierName,
            @Schema(description = "송장번호", example = "123456789012") String trackingNo
    ) { }
}
