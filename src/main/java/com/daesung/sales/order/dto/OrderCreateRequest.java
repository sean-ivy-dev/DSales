package com.daesung.sales.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 신규 주문 등록. 근거: 레거시 특약점 사이트 {@code Application_SQL.xml:266~299} —
 * 같은 3단 구조(주문 → 반 → 과목수량)를 그대로 옮긴다.
 *
 * <p>★<b>주문 하나에 반이 여럿, 반 하나에 과목 수량이 여럿</b>이다.
 * 반을 평평하게 펴서 받으면 "3학년 1반의 국어 30부"와 "2반의 국어 30부"를 구분할 수 없다.
 */
@Schema(name = "OrderCreateRequest", description = "신규 주문 등록(주문 → 반 → 과목수량)")
public record OrderCreateRequest(

        @Schema(description = "시행코드(DTL_CD). `GET /orders/exams`에서 고른다",
                example = "78331", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Integer dtlCd,

        @Schema(description = "지사(거래처)코드 — DSRE2 CUST_CD", example = "70501",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 5) String custCode,

        @Schema(description = "학교/학원코드 — DSRE2 MGR_CD", example = "10001")
        @Size(max = 5) String schoolCode,

        @Schema(description = "성적처리 구분 Y/N. 미지정 시 시행 기본값", example = "N")
        @Pattern(regexp = "[YN]") String procYn,

        @Schema(description = "본사 성적처리 구분 Y/N. 미지정 시 N", example = "N")
        @Pattern(regexp = "[YN]") String procYn2,

        @Schema(description = "성적처리 예상일(yyyyMMdd)", example = "20261015")
        @Pattern(regexp = "\\d{8}") String procDate,

        @Schema(description = "담당자명") @Size(max = 64) String teacher,
        @Schema(description = "담당자 연락처") @Size(max = 64) String tel,
        @Schema(description = "담당자 이메일") @Size(max = 64) String email,
        @Schema(description = "우편번호") @Size(max = 7) String zipCode,
        @Schema(description = "주소") @Size(max = 256) String address,
        @Schema(description = "비고") @Size(max = 256) String memo,

        @Schema(description = """
                배송구분 — **H=화물(지사) / T=택배(학교·학원)**. 미지정 시 H.
                ‼️레거시 컬럼 주석 그대로다(`LGS_GN`). 물류 작업요청서의 발송구분과는 별개 축이다.""",
                example = "H")
        @Pattern(regexp = "[HT]") String deliveryGubun,

        @Schema(description = """
                매출 직접입력(선택). 근거: 9/27 회의 항목 2 —
                "신규등록에서 매출직접입력의 정가·공급률·수량 가능해야됨. **매출등록도 그대로 유지**".

                주면 주문 등록 직후 <b>매출까지 함께</b> 세운다. 안 주면 주문만 등록한다
                (기존 동작 그대로 — 매출은 매출등록 화면에서 따로 친다).""")
        @Valid SaleLine sale,

        @Schema(description = "반 목록. 최소 1개", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Size(max = 200) @Valid List<ClassLine> classes
) {

    /** 반 1개. 간편신청이면 인문·자연·통합 인원을, 과목신청이면 과목별 수량을 채운다. */
    @Schema(name = "OrderClassLine", description = "반별 신청")
    public record ClassLine(

            @Schema(description = "반 이름", example = "3학년 1반",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 32) String className,

            @Schema(description = """
                    신청방식 — **S=간편신청(인원만) / N=과목신청(과목별 수량)**.
                    미지정 시 과목 수량이 있으면 N, 없으면 S로 본다.
                    ‼️시행이 간편신청을 받는지는 `GET /orders/exams`의 `easyYn`이 알려준다.""",
                    example = "S")
            @Pattern(regexp = "[SN]") String applyType,

            @Schema(description = "간편신청 — 인문 인원") @PositiveOrZero Integer humanities,
            @Schema(description = "간편신청 — 자연 인원") @PositiveOrZero Integer science,
            @Schema(description = "간편신청 — 통합 인원") @PositiveOrZero Integer combined,

            @Schema(description = "과목신청 — 과목별 수량. `GET /orders/exams/{dtlCd}/subjects`에서 고른다")
            @Size(max = 100) @Valid List<SubjectQty> subjects
    ) {
    }

    /** 과목 1개의 신청 수량. */
    @Schema(name = "OrderSubjectQty", description = "과목별 신청 수량")
    public record SubjectQty(
            @Schema(description = "과목코드(RES_CD)", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Integer resCd,
            @Schema(description = "신청 수량", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull @Positive Integer qty
    ) {
    }

    /**
     * 주문과 함께 세울 매출(9/27 항목 2).
     *
     * <p>★<b>거래처는 id 로 받는다.</b> 주문의 {@code custCode} 는 DSRE2 지사코드(CUST_CD)이고
     * 우리 거래처코드는 <b>매출코드(MACHUL_CD)</b>라 서로 다른 값이다 — 자동으로 유추하면
     * 엉뚱한 거래처에 매출이 붙는다. 화면이 고른 거래처를 그대로 보낸다.
     *
     * <p>상품은 비워도 된다. 비우면 <b>시행코드(dtlCd)와 같은 도서코드</b>를 찾는다 —
     * 매출일괄등록(더프)이 쓰는 매핑과 같은 규칙이다.
     */
    @Schema(name = "OrderSaleLine", description = "주문과 함께 세울 매출(선택)")
    public record SaleLine(

            @Schema(description = "거래처 id — 우리 마스터의 id. DSRE 지사코드와 다른 값이다",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Long partnerId,

            @Schema(description = "상품 id. 미지정이면 시행코드(dtlCd)와 같은 도서코드를 찾는다")
            Long productId,

            @Schema(description = "매출일자. 미지정이면 오늘")
            java.time.LocalDate salesDate,

            @Schema(description = "출고 창고 id. 미지정이면 재고를 건드리지 않는 '미출고 매출'이 된다")
            Long warehouseId,

            @Schema(description = "정가(원). 미지정이면 도서 마스터 정가", example = "12000")
            @PositiveOrZero Integer unitPrice,

            @Schema(description = "공급률(%). 미지정이면 거래처별 단가 매핑에서 자동조회", example = "70")
            @PositiveOrZero Integer supplyRate,

            @Schema(description = """
                    수량. **미지정이면 이 주문의 총 신청 수량**을 쓴다 —
                    신규등록 화면에서 반별 인원을 이미 친 뒤라 같은 숫자를 두 번 치게 하지 않는다.

                    ★**0 을 명시적으로 보내면 0 으로 등록된다**(F-17) — 매출만 있고 보낼 물건이
                    없는 상품이 있다. 그때는 재고·작업지시를 만들지 않고 응답 `warnings` 에 담는다.
                    (미지정 = 반별 인원 합, 0 = 진짜 0. 둘은 다르다.)""",
                    example = "120")
            @PositiveOrZero Integer qty,

            @Schema(description = "세액(선택). 자동산출하지 않는다 — 미입력이면 0")
            @PositiveOrZero Integer tax,

            @Schema(description = "비고") @Size(max = 500) String memo
    ) {
    }
}
