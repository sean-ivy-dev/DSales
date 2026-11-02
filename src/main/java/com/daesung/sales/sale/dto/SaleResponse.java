package com.daesung.sales.sale.dto;

import com.daesung.sales.product.entity.MajorCategory;
import com.daesung.sales.product.entity.SalesDivision;
import com.daesung.sales.sale.entity.Sale;
import io.swagger.v3.oas.annotations.media.Schema;
import com.daesung.sales.sale.entity.SalesType;
import com.daesung.sales.salestype.entity.SalesCategory;
import com.daesung.sales.salestype.entity.ShipmentType;
import com.daesung.sales.salestype.entity.TradeClass;
import java.time.LocalDate;

/** 매출 상세/조회 응답 DTO. 통합매출조회(12p) 컬럼 전부 노출. */
public record SaleResponse(
        Long id,
        String salesNo,
        LocalDate salesDate,
        Long partnerId,
        String partnerCode,       // 거래처코드
        String partnerName,       // 거래처명2(합쳐진 풀네임)
        String partnerCityName,   // 도시명
        String partnerName1,      // 거래처명1(상호만)
        String region,            // 지역(12p)
        String clientCategory,    // 거래처구분(12p)
        String schoolCode,        // 학교코드(12p 학교구분명)
        String schoolName,        // 학교/학원명

        @Schema(description = """
                학교/학원 구분(SCHOOL·HAKWON). **학교 마스터에서 붙인다** —
                매출 원장엔 코드·이름만 있고 구분이 없었다(2026-09-18 지적).
                ‼️마스터에 없는 학교코드면 null 이다. 조인을 느슨하게 둬 매출이 사라지지 않게 한다.""")
        String schoolType,
        @Schema(description = "학교/학원 구분명(학교·학원)") String schoolTypeName,

        String grade,             // 학년(12p, 상품 마스터 속성)
        String catCode,           // 분류코드(12p). ‼️대분류와 다른 축이다 — 아래 majorCategory 참고
        String catName,           // 분류명
        @Schema(description = """
                세부구분(구 '매출구분'). 발주처 회신 2026-08-20으로 명칭이 바뀌었다 —
                7p·12p에서 '대분류' 컬럼으로 표기하던 값이 실제로는 이것이다.""")
        String salesDivision,
        @Schema(description = "세부구분 명칭") String salesDivisionName,
        @Schema(description = "대분류 코드(집계 기준). 세부구분 마스터에서 파생, 미지정은 null")
        MajorCategory majorCategory,
        @Schema(description = "대분류 명칭(모의고사·교재 …)") String majorCategoryName,
        Long productId,
        String productCode,       // 도서코드
        String productName,       // 도서명
        Integer bookRound,        // 회차(12p)
        SalesType salesType,
        ShipmentType shipmentType,
        @Schema(description = """
                출고유형 명칭(정상출고/위탁출고/증정용/교사용/반품/취소).
                ★코드(enum)와 한글을 <b>둘 다</b> 준다 — 화면마다 라벨표를 만들면 언젠가 갈린다.
                실제로 엑셀에 `NORMAL_SHIP`이 그대로 찍히고 있었다(2026-09-17 지적).""")
        String shipmentTypeName,
        @Schema(description = """
                거래분류 코드(표준 4축 중 첫째). 매출 원장에서는 SALES/FREE/RETURN만 나온다 —
                INBOUND(입고)·DISPOSE(폐기)는 재고 원장의 거래다.
                **구분(상세)에서 파생**되며 따로 저장하지 않는다(두 값이 어긋날 여지를 두지 않는다).""")
        TradeClass tradeClass,
        @Schema(description = "거래분류 명칭(매출/무상/반품)") String tradeClassName,
        @Schema(description = "구분(상세) — 회계구분 SALE/FREE/RETURN") SalesCategory salesCategory,
        Integer unitPrice,
        Integer supplyRate,
        @Schema(description = """
                적용된 권당 할인액(34p). 값이 있으면 공급가액 = (정가−할인액)×수량이고
                공급률은 금액에 쓰이지 않았다는 뜻이다.""")
        Integer discountAmount,
        int qty,
        Long supplyAmount,
        Long tax,
        Long totalAmount,
        boolean canceled,
        @Schema(description = "출고 창고 id") Long warehouseId,
        @Schema(description = "출고 창고명(7p 재고위치). 이 컬럼 도입 전 매출은 비어 있다") String warehouseName,
        @Schema(description = """
                판매채널(레거시 `schDataDT.type1`). **학교 마스터에서 붙인다** — 매출 원장엔 없다.
                학교코드가 없거나 마스터에 그 학교가 없으면 null.""")
        String salesChannel,
        @Schema(description = """
                등록 묶음번호(E-yyyyMMdd-n) — **한 번의 등록**을 가리킨다(현장 피드백 2026-10-14 B-4).
                매출번호는 줄마다 따로 채번돼 묶음을 표현하지 못한다.
                ‼️이 컬럼 도입 전 매출은 null 이다 — 소급해 채우지 않았다(그때는 묶음이 기록되지 않았고,
                  날짜·거래처로 추측해 묶으면 없던 사실을 만드는 것이다).""")
        String entryNo,
        @Schema(description = "그 묶음 안의 등록 순번(1부터). 담당자가 친 순서다") Integer entrySeq,
        @Schema(description = """
                거래순번 — 그 거래처의 그 날 **몇 번째 주문**인가(10/27 명세 §3-6, 1부터).
                `entrySeq`(묶음 **안**의 줄 번호)와 다르다. 이쪽은 **묶음 자체**의 번호라,
                한 번 등록하면 그 안의 모든 줄이 같은 값을 갖는다.
                조회 조건 `tradeSeq`(거래처·거래일자와 함께)가 이 값을 본다.
                ‼️이 컬럼 도입 전 매출은 null 이다 — 소급해 채우지 않았다.""")
        Integer tradeSeq,
        @Schema(description = """
                「매출만(배송X)」 체크 여부 — 담당자가 등록할 때 고른 값.
                주문을 불러와 고칠 때 **체크 상태를 그대로 되살리는** 값이다(프론트 B-4④).
                ‼️"발송이 없다"와 다르다 — 특강·창고없음 건도 작업요청서를 만들지 않는다.
                이 컬럼 도입 전 매출은 null(= 체크 안 함).""")
        Boolean onlySales,
        @Schema(description = """
                공급가액을 담당자가 **직접 입력**했나(B-3).
                true 면 계산값이 아니라 손으로 맞춘 금액이다.""")
        boolean amountOverridden,
        String memo
) {
    /**
     * @param division 이 매출 상품의 세부구분 마스터(null=미지정). 대분류는 여기서 파생된다 —
     *                 매출에 대분류를 복사해두지 않는 이유는 매핑이 바뀌면 과거 조회가 어긋나기 때문이다.
     */
    public static SaleResponse from(Sale s, SalesDivision division) {
        return from(s, division, null);
    }

    /**
     * @param schoolType 학교 마스터에서 찾은 구분(없으면 null). 매출 원장엔 없는 값이라 밖에서 넣는다.
     */
    public static SaleResponse from(Sale s, SalesDivision division,
                                    com.daesung.sales.school.entity.SchoolType schoolType) {
        return from(s, division, schoolType, null, true);
    }

    /**
     * 조회용 — 학교명 보완과 쿠폰 카운트를 함께 적용한다(9/27 회의 항목 13-③·⑤).
     *
     * @param schoolNameFallback 매출에 학교명이 비어 있을 때 쓸 마스터 이름(없으면 null).
     *        ★<b>덮어쓰지 않는다.</b> 매출에 적힌 이름은 등록 당시의 것이고 지금 마스터와
     *        다를 수 있다(통폐합·개명). 비어 있을 때만 채운다.
     * @param couponCount 쿠폰 포함 카운트. {@code false}(화면 기본)면 도서명에 <b>쿠폰</b>이
     *        든 상품의 <b>수량만</b> 0으로 본다 — 레거시 {@code 조회.vb:1335}
     *        {@code iif(CHARINDEX('쿠폰', bookName) > 0, 0, bookCnt)} 그대로다.
     *        ‼️<b>금액은 건드리지 않는다.</b> 쿠폰도 팔린 것은 맞고, '부수'로 세지 않을 뿐이다.
     *        ‼️판별이 <b>도서명 문자열</b>이라 이름이 바뀌면 조용히 빗나간다
     *        (더프원장 시행월의 {@code ##ERROR} 와 같은 함정). 상품 속성으로 옮길지는 확인 대상.
     */
    public static SaleResponse from(Sale s, SalesDivision division,
                                    com.daesung.sales.school.entity.SchoolType schoolType,
                                    String schoolNameFallback, boolean couponCount) {
        return from(s, division, schoolType, schoolNameFallback, null, couponCount);
    }

    /** 판매채널까지 붙인 조회용(2026-10-02 발주처 회신 — 레거시 조회화면 콤보). */
    public static SaleResponse from(Sale s, SalesDivision division,
                                    com.daesung.sales.school.entity.SchoolType schoolType,
                                    String schoolNameFallback, String salesChannel,
                                    boolean couponCount) {
        MajorCategory major = (division == null) ? null : division.getMajorCategory();
        String schoolName = (s.getSchoolName() != null && !s.getSchoolName().isBlank())
                ? s.getSchoolName() : schoolNameFallback;
        String productName = s.getProduct().getName();
        int qty = (!couponCount && productName != null && productName.contains("쿠폰"))
                ? 0 : s.getQty();
        // ★반품은 수량·금액을 음수로 내려준다(10/27 피드백 §3-2) — 서버는 합계를 직접
        //   계산하지 않고 화면이 행을 그대로 더하므로, 음수로 줘야 합산 시 자동으로 상쇄된다.
        //   저장(Sale)은 항상 양수+salesCategory 구분 방식을 그대로 유지한다 — 이 변환은
        //   응답(조회) 전용이고, 재고·재무 계산 등 다른 로직은 전혀 건드리지 않는다.
        boolean isReturn = s.getSalesCategory() == SalesCategory.RETURN;
        if (isReturn) {
            qty = -qty;
        }
        Long supplyAmount = isReturn ? negate(s.getSupplyAmount()) : s.getSupplyAmount();
        Long tax = isReturn ? negate(s.getTax()) : s.getTax();
        Long totalAmount = isReturn ? negate(s.getTotalAmount()) : s.getTotalAmount();
        return new SaleResponse(
                s.getId(), s.getSalesNo(), s.getSalesDate(),
                s.getPartner().getId(), s.getPartner().getCode(), s.getPartner().getName(),
                s.getPartner().getCityName(), s.getPartner().getName1(),
                s.getPartner().getRegion(), s.getPartner().getClientCategory(),
                s.getSchoolCode(), schoolName,
                (schoolType == null) ? null : schoolType.name(),
                (schoolType == null) ? null : schoolType.label(),
                s.getProduct().getGrade(),
                s.getProduct().getCatCode(), s.getProduct().getCatName(),
                s.getProduct().getSalesDivision(),
                (division == null) ? null : division.getName(),
                major, (major == null) ? null : major.label(),
                s.getProduct().getId(), s.getProduct().getCode(), productName, s.getBookRound(),
                s.getSalesType(), s.getShipmentType(),
                ShipmentType.labelOf(s.getShipmentType()),
                TradeClass.of(s.getSalesCategory()),
                (s.getSalesCategory() == null) ? null : TradeClass.of(s.getSalesCategory()).label(),
                s.getSalesCategory(),
                s.getUnitPrice(), s.getSupplyRate(), s.getDiscountAmount(), qty,
                supplyAmount, tax, totalAmount,
                s.isCanceled(),
                (s.getWarehouse() == null) ? null : s.getWarehouse().getId(),
                (s.getWarehouse() == null) ? null : s.getWarehouse().getName(),
                salesChannel,
                s.getEntryNo(), s.getEntrySeq(), s.getTradeSeq(), s.getOnlySales(),
                s.isAmountOverridden(),
                s.getMemo());
    }

    private static Long negate(Long v) {
        return (v == null) ? null : -v;
    }
}
