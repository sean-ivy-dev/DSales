package com.daesung.sales.sale.dto;

import com.daesung.sales.logistics.entity.DeliveryType;
import com.daesung.sales.sale.entity.ProcType;
import com.daesung.sales.salestype.entity.ShipmentType;
import com.daesung.sales.sale.entity.PackType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.List;

/** 수기 매출 등록 요청(일반 매출). 위탁출고 매출은 별도(위탁정산)로 처리. */
public record SalesEntryRequest(

        @Schema(description = "매출일자", example = "2026-06-22", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalDate salesDate,

        @Schema(description = "거래처 id", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long partnerId,

        @Schema(description = """
                출고 물류창고 id(정상출고=−차감 / 반품=+복구).

                <p>★<b>비워 둘 수 있다 — '미출고 매출'이다.</b> 문항사용료·학원매출처럼
                **실제 출고가 일어나지 않는 매출**은 창고가 없다(정본 13p 필요기능).
                이 경우 재고를 건드리지 않고 매출만 선다.
                ‼️거래명세서·물류 작업으로도 연계되지 않는다 — 내보낼 물건이 없기 때문이다.

                <p>‼️<b>반품·정상출고는 창고가 필요하다.</b> 재고가 움직이는 거래라
                어느 창고인지 없으면 어디서 빼고 어디로 넣을지 알 수 없다 → 미지정 시 400.""",
                example = "1")
        Long warehouseId,

        @Schema(description = """
                「매출만(배송X)」 — 매출은 세우고 <b>작업요청서(발송 건)만 만들지 않는다</b>.

                <p>근거: 레거시 {@code UC_TabPages.vb} 의 같은 이름 체크박스
                ({@code CheckBox_OnlySales}, Designer.vb:113 "매출만(배송X)"). 그 체크는
                {@code UC_TabPages.vb:749} 한 곳에만 쓰이고, 하는 일은 <b>sendData INSERT 를 건너뛰는 것</b>이다
                — 매출 기록과 재고는 평소와 똑같다.

                <p>‼️<b>창고를 비우는 것과 다른 축이다.</b> 혼동이 있어 바로잡는다(2026-10-08 [C]17) —
                <ul>
                  <li><b>창고 없음</b> = 미출고 매출(문항사용료·학원매출). <b>재고가 안 움직인다.</b></li>
                  <li><b>배송X</b> = 물건은 나가므로 <b>재고는 그대로 빠지고</b>, 물류 작업지시만 안 만든다
                      (이미 배송정보가 있거나 거래처가 직접 가져가는 건).</li>
                </ul>
                그래서 재고관리 상품을 창고 없이 보내면 그대로 400이다 — 재고를 조용히 안 건드리고
                매출만 서면 장부가 갈라진다. 그 경우 필요한 것은 창고를 비우는 게 아니라 이 값이다.""",
                example = "false")
        Boolean onlySales,


        @Schema(description = """
                발송구분(COURIER=택배/FREIGHT=화물). 신규 주문(간단 폼)의 「택배 발송」 체크 대응
                (발주처 질의 2026-10-26 ①, 정본 16p·26p).

                <p>이 주문이 묶이는 발송 건(거래일자·거래처·학교·분류 단위, {@link #onlySales}
                주석 참고)에 <b>비어 있을 때만</b> 채운다. 이미 값이 있으면 그대로 둔다 —
                한 발송 건에 여러 주문이 묶일 수 있어, 나중 주문이 앞 주문의 값이나
                물류가 손으로 고친 값을 덮으면 택배가 엉뚱한 사람에게 간다(DSRE 신청 경로
                {@code autoFillDelivery}와 같은 정책).

                <p>창고가 없거나(미출고 매출) 「매출만(배송X)」이면 발송 건 자체가 안 생겨
                이 값도 적용되지 않는다.""",
                example = "COURIER")
        DeliveryType deliveryType,

        @Schema(description = "수령인(담당자명) — 「택배 발송」 체크 시 함께 받는다. 발송구분과 같은 규칙으로 비어 있을 때만 채운다.",
                example = "홍길동")
        String receiverName,

        @Schema(description = "수령인 연락처", example = "010-0000-0000")
        String receiverPhone,

        @Schema(description = """
                접수경로 — `ONLINE`(특약점 사이트) / `MANUAL`(수기). **안 보내면 수기**다.
                근거: 10/27 §2-1 "수기 주문과 온라인(특약점) 주문을 **함께** 보여주고".
                ‼️특약점 접수(`POST /dealer-site/orders`)는 서버가 ONLINE 으로 박으므로
                이 칸으로 바꿀 수 없다 — 사이트에서 온 주문을 수기로 위장할 수 없어야 한다.""",
                example = "MANUAL")
        com.daesung.sales.sale.entity.OrderChannel channel,

        @Schema(description = "매출 품목 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Valid List<Item> items
) {
    /** 매출 품목. 금액=정가×공급률/100×수량(부수기준), 세액=면세면 0 아니면 공급가액의 10%. */
    @Schema(name = "SalesEntryItem")
    public record Item(

            @Schema(description = "상품 id", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Long productId,

            @Schema(description = "출고유형(CONSIGN_SHIP=위탁출고는 이 API 불가)", example = "NORMAL_SHIP",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull ShipmentType shipmentType,

            @Schema(description = "정가(원). 미입력 시 도서 마스터 정가 자동적용", example = "20000")
            @Positive Integer unitPrice,

            @Schema(description = "공급률(%). 미입력 시 거래처별 단가 매핑에서 자동조회(둘 다 없으면 오류)", example = "75")
            @PositiveOrZero Integer supplyRate,

            @Schema(description = """
                    권당 할인액(선택). 미입력 시 거래처×대분류 매핑(34p)에서 자동조회.
                    **값이 있으면 공급률 대신 이 값으로 금액이 계산된다** — 공급가액 = (정가−할인액)×수량.
                    둘 다 곱하면 이중 할인이 되기 때문이다(레거시 매출가져오기.vb:425).""",
                    example = "0")
            @PositiveOrZero Integer discountAmount,

            @Schema(description = "수량", example = "100", requiredMode = Schema.RequiredMode.REQUIRED)
            @Positive int qty,

            @Schema(description = "세액(선택). 자동산출하지 않으며 미입력 시 0. 면세 상품에는 입력 불가",
                    example = "0")
            @PositiveOrZero Integer tax,

            @Schema(description = """
                    공급가액 **직접 입력**(선택). 근거: 현장 피드백 2026-10-14 —
                    "정가는 기본, 자동 계산은 되는데 **수동으로 수정해서 등록**할 수 있도록".

                    · 비우면 지금처럼 `정가 × 공급률 × 수량`(할인액 반영)으로 서버가 계산한다.
                    · 값을 주면 **그 값을 그대로** 저장하고, 총금액 = 이 값 + 세액이 된다.
                    · 응답·조회에 `amountOverridden=true` 가 붙는다 — 화면이 「손으로 고친 금액」으로
                      구분해 보여줄 수 있다.
                    ‼️정가·공급률은 **입력한 그대로 남는다**(금액에 맞춰 역산하지 않는다).
                      역산하면 담당자가 적어 둔 조건이 사라져 나중에 왜 그 금액인지 알 수 없다.""",
                    example = "95000")
            @PositiveOrZero Long supplyAmount,

            @Schema(description = "성적처리 구분(37p 월별매출액명세서 인원 집계축): GRADED(성적처리)/UNGRADED(비처리). 미지정 시 비처리로 집계",
                    example = "GRADED")
            ProcType procType,

            @Schema(description = "학교/학원 코드(12p 세부 거래단위, 선택)", example = "A0003")
            String schoolCode,

            @Schema(description = "학교/학원명(선택)", example = "진주고등학교")
            String schoolName,

            @Schema(description = "세트 상품 회차(선택)", example = "4")
            Integer round,

            @Schema(description = "포장구분(물류 회차별 작업현황 집계축, 선택): "
                    + "INDIVIDUAL_1(개별1·개별봉투)/INDIVIDUAL_2(개별2·개별봉투SET)/CLASS_BUNDLE(반별)",
                    example = "INDIVIDUAL_1")
            PackType packType,

            @Schema(description = "비고", example = "6월 정상 매출")
            String memo
    ) {
    }
}
