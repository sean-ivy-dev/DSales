package com.daesung.sales.sale.controller;

import com.daesung.sales.common.dto.PageRequestDto;
import com.daesung.sales.common.query.Keywords;
import com.daesung.sales.common.query.MultiSelect;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.sale.dto.BookInoutResponse;
import com.daesung.sales.sale.dto.CategorySalesResponse;
import com.daesung.sales.sale.dto.MonthlyStatementResponse;
import com.daesung.sales.sale.dto.NetSalesResponse;
import com.daesung.sales.sale.dto.ReturnInboundRequest;
import com.daesung.sales.sale.dto.ReturnableResponse;
import com.daesung.sales.sale.dto.RoundWorkStatusRow;
import com.daesung.sales.sale.dto.AttendancePeriodResponse;
import com.daesung.sales.sale.dto.AttendanceResponse;
import com.daesung.sales.sale.dto.SaleResponse;
import com.daesung.sales.sale.dto.GroupTotalResponse;
import com.daesung.sales.sale.dto.SalesEntryRequest;
import com.daesung.sales.sale.dto.SalesEntryResponse;
import com.daesung.sales.sale.dto.SalesStatementResponse;
import com.daesung.sales.sale.dto.StatementKind;
import com.daesung.sales.sale.entity.SalesType;
import com.daesung.sales.sale.dto.SalesUploadResponse;
import com.daesung.sales.sale.dto.SalesSummaryResponse;
import com.daesung.sales.sale.dto.TransactionStatementResponse;
import com.daesung.sales.sale.dto.UnclassifiedSalesResponse;
import com.daesung.sales.sale.dto.YoyComparisonResponse;
import com.daesung.sales.sale.service.SaleReportService;
import com.daesung.sales.sale.service.SaleService;
import com.daesung.sales.sale.service.SalesUploadService;
import com.daesung.sales.salestype.entity.SalesCategory;
import com.daesung.sales.salestype.entity.ShipmentType;
import com.daesung.sales.salestype.entity.TradeClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import com.daesung.sales.common.excel.ExcelExportUtil;
import com.daesung.sales.common.excel.ExcelExportUtil.Col;
import com.daesung.sales.common.excel.ExcelExportUtil.Heading;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.daesung.sales.logistics.dto.ReasonRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 매출관리. 실제 경로: /api/v1/sales. */
@Tag(name = "매출관리 · 매출", description = "매출 등록/취소/조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/sales")
public class SaleController {

    /**
     * 매출 목록 정렬 별칭 — <b>화면 컬럼명 → 엔티티 속성 경로</b>.
     *
     * <p>★화면이 보이는 이름으로 정렬을 건다. '학년'은 화면에선 {@code grade}지만
     * {@code Sale}엔 그 속성이 없고 {@code product.grade}에 있다. 별칭이 없으면
     * {@code GET /sales?sort=grade,asc} 가 <b>500</b>이었다(실측 2026-09-18).
     *
     * <p>‼️여기 없는 항목은 <b>400</b>이다. 무엇으로 정렬할 수 있는지 화면은 알 수 없으므로
     * 오류 메시지가 목록을 함께 준다. 늘릴 때는 이 표에만 추가하면 된다.
     */
    private static final java.util.Map<String, String> SALE_SORTS = java.util.Map.ofEntries(
            java.util.Map.entry("id", "id"),
            java.util.Map.entry("salesNo", "salesNo"),
            java.util.Map.entry("salesDate", "salesDate"),
            java.util.Map.entry("salesType", "salesType"),
            java.util.Map.entry("shipmentType", "shipmentType"),
            java.util.Map.entry("salesCategory", "salesCategory"),
            java.util.Map.entry("unitPrice", "unitPrice"),
            java.util.Map.entry("supplyRate", "supplyRate"),
            java.util.Map.entry("qty", "qty"),
            java.util.Map.entry("supplyAmount", "supplyAmount"),
            java.util.Map.entry("tax", "tax"),
            java.util.Map.entry("totalAmount", "totalAmount"),
            java.util.Map.entry("discountAmount", "discountAmount"),
            java.util.Map.entry("schoolCode", "schoolCode"),
            java.util.Map.entry("schoolName", "schoolName"),
            java.util.Map.entry("bookRound", "bookRound"),
            // 프론트가 정렬을 껐던 둘 — 표에 없어 400이었다(2026-09-18 지적).
            java.util.Map.entry("canceled", "canceled"),
            java.util.Map.entry("memo", "memo"),
            // ↓ 연관 엔티티 — 화면 이름과 경로가 다른 것들
            java.util.Map.entry("partnerCode", "partner.code"),
            java.util.Map.entry("partnerName", "partner.name"),
            java.util.Map.entry("productCode", "product.code"),
            java.util.Map.entry("productName", "product.name"),
            java.util.Map.entry("catCode", "product.catCode"),
            java.util.Map.entry("catName", "product.catName"),
            java.util.Map.entry("grade", "product.grade"),
            java.util.Map.entry("warehouseName", "warehouse.name"));

    private final SaleService saleService;
    private final SaleReportService saleReportService;
    private final com.daesung.sales.sale.service.AttendanceService attendanceService;
    private final SalesUploadService salesUploadService;
    private final com.daesung.sales.sale.service.SalesMailService salesMailService;
    private final ExcelExportUtil excel;

    /**
     * 엑셀 한 장에 담는 최대 행수. 조회 화면의 페이지 크기(200)와 무관하게 전량을 담기 위한 상한이다.
     * ★무제한으로 두지 않는 이유: 기간을 안 주고 내려받으면 전 기간이 메모리에 한 번에 올라온다.
     *   넘치면 잘라 내는 것이 아니라 <b>기간을 좁히라고 알려주는</b> 편이 낫다(조용한 누락 방지).
     */
    private static final int EXPORT_MAX = 50_000;

    @Operation(summary = "통합 매출 조회(7p 출고/반품조회 · 12p 통합매출조회)",
            description = """
                    기간·거래처와 **표준 구분값 축**으로 조회한다. 기본은 취소건 제외.

                    발주처 확정 4축(2026-08-05)과 파라미터 대응 —
                    | 축 | 값 | 파라미터 |
                    |---|---|---|
                    | 거래분류(5) | 매출/무상/반품/입고/폐기 | `tradeClass` |
                    | 구분(상세)(3) | 매출/무상/반품 | `salesCategory` |
                    | 출고유형(6) | 정상출고/위탁출고/증정용/교사용/반품/취소 | `shipmentType` |
                    | 창고(2) | 물류창고/위탁창고 | 응답 `warehouseName` |

                    · 매출 원장의 거래분류는 **구분(상세)에서 파생**된다 — 따로 저장하지 않아 둘이 어긋날 수 없다.

                    ### 다중선택(체크박스) — 2026-08-31 발주처 요구
                    네 축 모두 **복수 파라미터**를 받는다. 값을 콤마로 잇거나 파라미터를 반복하면 된다
                    (`tradeClasses=FREE,RETURN` 또는 `tradeClasses=FREE&tradeClasses=RETURN`).

                    · **단수 파라미터는 그대로 살아 있고**, 복수와 같이 오면 **합집합**이다.
                      단수는 '원소가 하나인 다중선택'으로 본다.
                    · `tradeClasses`에 **입고·폐기가 섞이면 그 값만 무시**한다 — 재고 원장의
                      거래라 매출에는 없다. '무상+입고'를 고르면 무상은 정상 조회된다.
                      단 **입고·폐기만** 골랐다면 남는 조건이 없어 **빈 결과**다.
                    · `tradeClasses`와 `salesCategories`를 같이 주면 **교집합**이다(별개 축이라 둘 다 만족).""")
    @GetMapping
    public ApiResponse<PageResponse<SaleResponse>> list(
            @Parameter(description = "시작일(yyyy-MM-dd). `fromDate`로 보내도 된다")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "종료일(yyyy-MM-dd). `toDate`로 보내도 된다")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "시작일 별칭 — 다른 조회 API가 쓰는 이름")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일 별칭 — 다른 조회 API가 쓰는 이름")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "구분(상세) — 회계구분 SALE/FREE/RETURN (단건. 다중은 salesCategories)")
            @RequestParam(required = false) SalesCategory salesCategory,
            @Parameter(description = "구분(상세) **다중선택** — 예: SALE,RETURN")
            @RequestParam(required = false) List<SalesCategory> salesCategories,
            @Parameter(description = "거래분류 SALES(매출)/FREE(무상)/RETURN(반품)/INBOUND(입고)/DISPOSE(폐기). "
                    + "입고·폐기는 재고 원장 거래라 매출 조회에서는 무시된다 (단건. 다중은 tradeClasses)")
            @RequestParam(required = false) TradeClass tradeClass,
            @Parameter(description = "거래분류 **다중선택** — 예: FREE,RETURN (화면3 체크박스)")
            @RequestParam(required = false) List<TradeClass> tradeClasses,
            @Parameter(description = "출고유형(6종) (단건. 다중은 shipmentTypes)")
            @RequestParam(required = false) ShipmentType shipmentType,
            @Parameter(description = "출고유형 **다중선택** — 예: NORMAL_SHIP,GIFT")
            @RequestParam(required = false) List<ShipmentType> shipmentTypes,
            @Parameter(description = "거래처 id (단건. 다중은 partnerIds)")
            @RequestParam(required = false) Long partnerId,
            @Parameter(description = "거래처 id **다중선택** — 좌측 트리뷰 체크박스용")
            @RequestParam(required = false) List<Long> partnerIds,
            @Parameter(description = "출고 창고 id (단건. 다중은 warehouseIds)")
            @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "출고 창고 id **다중선택**")
            @RequestParam(required = false) List<Long> warehouseIds,
            @Parameter(description = "학교코드 **다중선택** — 9/27 회의 13-② '학교별 중복조회'. "
                    + "한 거래처가 여러 학교에 나가므로 거래처만으로는 좁혀지지 않는다")
            @RequestParam(required = false) List<String> schoolCodes,
            @Parameter(description = """
                    쿠폰 포함 카운트(9/27 회의 13-⑤). **기본 false** — 레거시 체크박스 기본값과 같다.
                    false 면 도서명에 '쿠폰'이 든 상품의 **수량만** 0으로 본다(금액은 그대로).
                    ‼️레거시와 같은 규칙이라 판별이 도서명 문자열이다.""")
            @RequestParam(required = false, defaultValue = "false") boolean couponCount,
            @Parameter(description = "세부구분 **다중선택**(상품의 매출구분 코드). 예: `divisions=모의고사,교재`")
            @RequestParam(required = false) List<String> divisions,
            @Parameter(description = "도서(상품) id **다중선택**")
            @RequestParam(required = false) List<Long> productIds,
            @Parameter(description = """
                    거래처×학교 **조합** 선택. `거래처id`(그 거래처 전체) 또는 `거래처id:학교코드`.
                    예: `partnerSchools=12&partnerSchools=13:S001` → 12는 전체, 13은 S001만.
                    ★거래처목록·학교목록을 따로 거는 것으로는 "A는 전체 + B는 그 학교만"을
                    표현할 수 없어(학교 조건이 A에도 걸린다) 별도 축으로 받는다.""")
            @RequestParam(required = false) List<String> partnerSchools,
            @Parameter(description = """
                    판매채널 **다중선택**(레거시 조회화면 콤보). 값은 **학교 마스터**에 있다
                    (레거시 `schDataDT.type1`). 선택 가능한 목록은
                    `GET /masters/schools/sales-channels` 로 받는다 — 코드표가 아니라
                    등록된 값을 모아 만든다(레거시 `CommonDB.vb:475` 와 같은 방식).

                    ★`미분류` 를 넣으면 **판매채널이 비어 있는 학교**의 매출을 뜻한다
                    (레거시 `< 미분류 >`, `조회.vb:1205`). 다른 채널명과 함께 보낼 수 있다.""")
            @RequestParam(required = false) List<String> salesChannels,
            @Parameter(description = """
                    거래순번 — 그 거래처의 그 날 **몇 번째 주문**인가(10/27 명세 §3-6).
                    `partnerId`·기간과 함께 주면 그 주문 한 건만 추린다(프론트 B-2③).""")
            @RequestParam(required = false) Integer tradeSeq,
            @Parameter(description = "키워드 — 거래처명·도서명·도서코드·매출번호·학교명을 한 번에 훑는다(부분일치)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = """
                    **출고/반품 조회** 전용 — 실제로 물건이 움직인 건만 본다(10/27 §3-3·4-10·4-12).
                    `true` 면 셋을 뺀다: **매출만(배송X) · 창고 없는 매출 · 특강**.
                    미지정(기본 false)이면 통합 매출 조회 — 지금까지와 똑같이 전부 나온다.

                    ‼️이 규칙이 생기기 전(2026-10-29 이전) 등록분은 「매출만」 체크가 저장되지
                    않아 걸러지지 않는다. 그때는 작업요청서까지 만들어져 있어 어느 기준으로도
                    가릴 수 없다 — 소급해 채우면 없던 의도를 만드는 것이라 그대로 둔다.""")
            @RequestParam(defaultValue = "false") boolean shippedOnly,
            @Parameter(description = "취소건 포함 여부(기본 false)") @RequestParam(defaultValue = "false") boolean includeCanceled,
            @ParameterObject PageRequestDto pageReq) {
        return ApiResponse.success(saleService.search(either(startDate, fromDate), either(endDate, toDate),
                MultiSelect.merge(salesCategory, salesCategories),
                MultiSelect.merge(tradeClass, tradeClasses),
                MultiSelect.merge(shipmentType, shipmentTypes),
                MultiSelect.merge(partnerId, partnerIds),
                MultiSelect.merge(warehouseId, warehouseIds),
                schoolCodes, divisions, productIds, partnerSchools, salesChannels, keyword,
                tradeSeq, shippedOnly, couponCount,
                includeCanceled, pageReq.toPageable(SALE_SORTS)));
    }

    @Operation(summary = "통합 매출 조회 엑셀 다운로드(12p)",
            description = """
                    조회(`GET /sales`)와 **같은 필터**를 받아 그 결과를 엑셀로 내린다.

                    ★필터를 똑같이 받는 이유: 다운로드만 조건이 빠지면 **화면에 없던 건이 파일에 실린다.**
                    담당자는 화면을 믿고 파일을 그대로 보고서에 붙인다.

                    ★페이징은 적용하지 않는다 — 파일은 **조건에 맞는 전량**이다.
                    화면 2페이지를 보다 내려받았는데 101~200행만 나오면 자료가 빠진 줄 모른다.

                    ‼️브라우저에서 `<a href>`로 이 주소를 열면 **인증 헤더가 실리지 않아 401**이다.
                    fetch로 토큰을 붙여 받은 뒤 파일로 저장해야 한다(개발팀 점검 2026-09-09 P0-5).""")
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportSales(
            @Parameter(description = "시작일(yyyy-MM-dd). `fromDate`로 보내도 된다")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "종료일(yyyy-MM-dd). `toDate`로 보내도 된다")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "시작일 별칭 — 다른 조회 API가 쓰는 이름")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일 별칭 — 다른 조회 API가 쓰는 이름")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) SalesCategory salesCategory,
            @RequestParam(required = false) List<SalesCategory> salesCategories,
            @RequestParam(required = false) TradeClass tradeClass,
            @RequestParam(required = false) List<TradeClass> tradeClasses,
            @RequestParam(required = false) ShipmentType shipmentType,
            @RequestParam(required = false) List<ShipmentType> shipmentTypes,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) List<Long> partnerIds,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) List<Long> warehouseIds,
            @Parameter(description = "학교코드 **다중선택**(조회와 같은 축)")
            @RequestParam(required = false) List<String> schoolCodes,
            @Parameter(description = "쿠폰 포함 카운트(기본 false — 조회와 같은 기본값)")
            @RequestParam(required = false, defaultValue = "false") boolean couponCount,
            @Parameter(description = "세부구분 **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<String> divisions,
            @Parameter(description = "도서 id **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<Long> productIds,
            @Parameter(description = "거래처×학교 조합(조회와 동일)")
            @RequestParam(required = false) List<String> partnerSchools,
            @Parameter(description = "판매채널 **다중선택**(조회와 동일). '미분류' 가능")
            @RequestParam(required = false) List<String> salesChannels,
            @RequestParam(required = false) String keyword,
            @Parameter(description = "거래순번(조회와 동일) — 그 거래처의 그 날 몇 번째 주문인가")
            @RequestParam(required = false) Integer tradeSeq,
            @Parameter(description = "출고/반품 조회 전용(조회와 동일) — 매출만·창고없음·특강을 뺀다")
            @RequestParam(defaultValue = "false") boolean shippedOnly,
            @RequestParam(defaultValue = "false") boolean includeCanceled) {
        List<Col> cols = List.of(
                new Col("등록번호", "entryNo"), new Col("순번", "entrySeq"),
                new Col("거래순번", "tradeSeq"),
                new Col("매출번호", "salesNo"), new Col("매출일자", "salesDate"),
                new Col("거래처코드", "partnerCode"), new Col("도시명", "partnerCityName"),
                new Col("거래처명1", "partnerName1"), new Col("거래처명2", "partnerName"),
                new Col("거래처구분", "clientCategory"), new Col("지역", "region"),
                new Col("학교코드", "schoolCode"), new Col("학교/학원명", "schoolName"),
                new Col("대분류", "majorCategoryName"), new Col("세부구분", "salesDivisionName"),
                new Col("분류코드", "catCode"), new Col("분류명", "catName"),
                new Col("도서코드", "productCode"), new Col("도서명", "productName"),
                new Col("학년", "grade"), new Col("회차", "bookRound"),
                new Col("거래분류", "tradeClassName"), new Col("출고유형", "shipmentTypeName"),
                new Col("창고", "warehouseName"),
                new Col("정가", "unitPrice"), new Col("공급률(%)", "supplyRate"),
                new Col("할인액", "discountAmount"), new Col("수량", "qty"),
                new Col("공급가액", "supplyAmount"), new Col("세액", "tax"),
                new Col("총금액", "totalAmount"),
                new Col("판매채널", "salesChannel"),
                new Col("취소", "canceled"), new Col("메모", "memo"));

        // ★페이징 없이 전량. PageRequestDto 상한(200)에 걸리지 않도록 서비스에 직접 큰 페이지를 준다.
        var page = saleService.search(either(startDate, fromDate), either(endDate, toDate),
                MultiSelect.merge(salesCategory, salesCategories),
                MultiSelect.merge(tradeClass, tradeClasses),
                MultiSelect.merge(shipmentType, shipmentTypes),
                MultiSelect.merge(partnerId, partnerIds),
                MultiSelect.merge(warehouseId, warehouseIds),
                schoolCodes, divisions, productIds, partnerSchools, salesChannels, keyword,
                tradeSeq, shippedOnly, couponCount,
                includeCanceled,
                org.springframework.data.domain.PageRequest.of(0, EXPORT_MAX));
        // ★고른 거래처 수를 머리말에 남긴다(2026-09-28 화면 정리안).
        //   몇 곳만 골라 뽑은 파일과 전량 파일이 겉으로 구분되지 않으면,
        //   그대로 보고서에 붙었을 때 빠진 거래처가 있다는 사실이 드러나지 않는다.
        List<Long> chosen = MultiSelect.merge(partnerId, partnerIds);
        String extra = MultiSelect.isAny(chosen) ? null : ("거래처 " + chosen.size() + "곳");
        byte[] xlsx = excel.toXlsx("통합매출조회", cols, page.getContent(),
                Heading.period("통합 매출 조회", either(startDate, fromDate), either(endDate, toDate), extra));
        return excel.asDownload(xlsx, "통합매출조회.xlsx");
    }

    @Operation(summary = "묶음 합계(소계용)",
            description = """
                    통합 매출 조회의 **소계 행**을 붙이는 데 쓴다(프론트 B-3).
                    **조건은 `GET /sales` 와 완전히 같다** — 서버가 같은 WHERE 절을 공유하므로
                    목록과 소계가 다른 집합을 셀 수 없다.

                    · `groupBy=PARTNER`(기본) — 거래처별. 키=거래처 id, 이름=거래처명
                    · `groupBy=DATE` — 거래일자별. 키·이름 모두 날짜

                    지금까지는 화면이 전량을 받아 더했다 — 1~10월 27,187건에 **약 4분**이 걸려
                    1만 건이 넘으면 소계를 못 붙였다. DB 가 세면 한 번이면 끝난다.""")
    @GetMapping("/group-totals")
    public ApiResponse<List<GroupTotalResponse>> groupTotals(
            @Parameter(description = "묶는 단위 — PARTNER(기본) 또는 DATE", example = "PARTNER")
            @RequestParam(defaultValue = "PARTNER") String groupBy,
            @Parameter(description = """
                    쿠폰 포함 카운트. **목록(`/sales`)과 같은 기본값(false)** 이다 —
                    도서명에 '쿠폰'이 든 상품의 **수량만** 0으로 본다.
                    ‼️목록과 다른 값을 주면 소계가 목록 합과 어긋난다.""")
            @RequestParam(required = false, defaultValue = "false") boolean couponCount,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) List<SalesCategory> salesCategories,
            @RequestParam(required = false) List<TradeClass> tradeClasses,
            @RequestParam(required = false) List<ShipmentType> shipmentTypes,
            @RequestParam(required = false) List<Long> partnerIds,
            @RequestParam(required = false) List<Long> warehouseIds,
            @RequestParam(required = false) List<String> schoolCodes,
            @RequestParam(required = false) List<String> divisions,
            @RequestParam(required = false) List<Long> productIds,
            @RequestParam(required = false) List<String> partnerSchools,
            @RequestParam(required = false) List<String> salesChannels,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer tradeSeq,
            @Parameter(description = "출고/반품 조회 전용 — 매출만·창고없음·특강을 뺀다")
            @RequestParam(defaultValue = "false") boolean shippedOnly,
            @RequestParam(defaultValue = "false") boolean includeCanceled) {
        return ApiResponse.success(saleService.groupTotals(groupBy, fromDate, toDate,
                salesCategories, tradeClasses, shipmentTypes, partnerIds, warehouseIds,
                schoolCodes, divisions, productIds, partnerSchools, salesChannels,
                keyword, tradeSeq, shippedOnly, includeCanceled, couponCount));
    }

    @Operation(summary = "수기 매출 등록(일반)",
            description = "품목별 금액(정가×공급률/100×수량)·세액 산출 + 매출번호(I) 채번. "
                    + "출고유형→회계구분 자동. 위탁출고(CONSIGN_SHIP)는 불가(위탁정산 별도).")
    @PostMapping("/entries")
    public ApiResponse<SalesEntryResponse> createEntries(@Valid @RequestBody SalesEntryRequest req) {
        return ApiResponse.success(saleService.createEntries(req));
    }

    @Operation(summary = "주문 통째 수정(거래순번으로 불러와 고치기)",
            description = """
                    한 번의 등록(`entryNo`)에 속한 줄을 **본문으로 통째 교체**한다.
                    근거: 10/27 명세 §3-6 "이 순번으로 **들어가 수정할 수 있게** 한다.
                    레거시 출고 화면의 '거래순번'과 같다(PPT 3)".

                    · 본문은 `POST /sales/entries` 와 **같다**.
                    · **등록번호·거래순번은 그대로** 둔다 — 바뀌면 화면이 방금 고친 주문을 다시 못 찾는다.
                    · 재고·작업요청서도 함께 맞춰진다(지우고 다시 넣는 방식이라 등록과 결과가 같다).

                    **거부**
                    · 마감된 달 — 원래 날짜와 바꾼 뒤 날짜를 **둘 다** 본다(한쪽만 보면 열린 달로 옮겨 빠져나간다).
                    · **발송완료된 주문** — 물건이 나간 뒤라 줄을 바꾸면 나간 것과 장부가 갈린다.
                      반품 또는 매출 취소로 처리한다.""")
    @PutMapping("/entries/{entryNo}")
    public ApiResponse<SalesEntryResponse> replaceEntry(
            @Parameter(description = "등록 묶음번호(E-yyyyMMdd-n)", example = "E-20261027-55")
            @PathVariable String entryNo,
            @Valid @RequestBody SalesEntryRequest req) {
        return ApiResponse.success(saleService.replaceEntry(entryNo, req));
    }

    @Operation(summary = "주문 통째 삭제(거래순번으로 불러와 지우기)",
            description = """
                    한 번의 등록(`entryNo`)에 속한 줄을 **전부** 지운다.
                    재고는 되돌아가고, **발송 전이면 묶여 있던 작업요청서도 함께 걷힌다**
                    (이미 나간 건은 그대로 둔다 — 나간 사실은 매출을 지운다고 없어지지 않는다).

                    논리삭제다 — 목록에서 빠지고 이력에 남는다. 마감된 달은 거부한다.""")
    @DeleteMapping("/entries/{entryNo}")
    public ApiResponse<Integer> deleteEntry(
            @Parameter(description = "등록 묶음번호(E-yyyyMMdd-n)", example = "E-20261027-55")
            @PathVariable String entryNo,
            @Valid @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(saleService.deleteEntry(entryNo, ReasonRequest.reasonOf(req)));
    }

    @Operation(summary = "매출 엑셀 업로드(표준양식 일괄등록)",
            description = "표준양식 엑셀(12컬럼: 거래일자·거래처코드·학교코드·분류코드·도서코드·회차·정가·공급률·수량·금액·구분·메모)을 "
                    + "매출 원장으로 일괄 등록. 상품=분류+도서 조합, 정가·공급률 미입력 시 도서·거래처단가 자동조회, 공급률 0.75는 75로 정규화. "
                    + "정상출고/증정용/교사용만 허용(위탁·취소·반품은 거부). 재고는 미반영(레거시 매출가져오기와 동일). 행별 결과 반환.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SalesUploadResponse> upload(
            @Parameter(description = "표준양식 xlsx 파일", required = true) @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(salesUploadService.upload(file));
    }

    @Operation(summary = "반품 가능내역 조회(교재식 반품)",
            description = "거래처(옵션 도서)의 도서×정가×공급률별 반품가능수량(누적 판매출고 − 기반품, >0만). "
                    + "반품입고 시 이 목록에서 라인을 골라 그 범위 내에서만 반품하며, 정가·공급률은 원 출고건을 그대로 사용.")
    @GetMapping("/returnable")
    public ApiResponse<ReturnableResponse> returnable(
            @Parameter(description = "거래처 id", required = true) @RequestParam Long partnerId,
            @Parameter(description = "도서(상품) id 필터(옵션)") @RequestParam(required = false) Long productId) {
        return ApiResponse.success(saleService.returnable(partnerId, productId));
    }

    @Operation(summary = "반품입고(29p·28p 물류 진입점, 교재식)",
            description = """
                    물류가 반품 물량을 입고하면 한 트랜잭션으로 매출 반품(RETURN) 라인 자동 생성
                    + 물류창고 재고 +복구. 반품 가능내역은 `GET /sales/returnable`로 먼저 조회한다.

                    ### ★초과해도 막지 않는다 — 경고로 알린다
                    발주처 화면검토(2026-08-31) 화면28 원문 —
                    "출고내역보다 반품 등록 내역이 더 많이 입력되는 경우 **경고 알림(alert)**을 넣어주시기 바랍니다."

                    경고를 요구했지 차단을 요구하지 않았고, 같은 회신에서 재고 음수·초과정산 차단도
                    함께 걷어냈다. 현장에서는 컷오버 전 출고분이나 다른 경로로 나간 물건이 반품으로 들어온다 —
                    막으면 **실제로 들어온 물건을 장부에 못 적는다**.

                    · 초과분은 응답 `warnings[]`에 담긴다(코드 `RETURN_EXCEEDS`,
                      반품가능수량·요청수량·초과수량·표시문구 포함). **`warnings`가 비어 있지 않으면 화면은 alert을 띄울 것.**
                    · 서버 로그에도 남는다. 조용히 통과시키면 담당자는 자기가 초과 입력한 줄 모른다.
                    · ⚠️종전에는 409 RETURN_EXCEEDS로 거부했다(2026-09-02 변경).

                    ### 공급률
                    원 출고건 값을 **표시**만 하고 **수정 가능**하다(발주처 확정 2026-08-04·08-05).
                    그래서 범위 판정은 **도서 단위 합계**로만 한다 — 공급률을 판정 키에 넣으면
                    담당자가 값을 바꾸는 순간 원 출고건을 못 찾아 거부되어 사실상 잠금이 된다.

                    재고관리 상품만 재고 복구(모의고사 등 인원기반은 이벤트 없음). 원본 출고번호(선택) 역추적 링크.""")
    @PostMapping("/return-inbound")
    public ApiResponse<SalesEntryResponse> returnInbound(@Valid @RequestBody ReturnInboundRequest req) {
        return ApiResponse.success(saleService.returnInbound(req));
    }

    @Operation(summary = "매출 삭제(마감 前)",
            description = """
                    **잘못 입력한 매출을 없던 것으로** 만든다. 발주처 확정(2026-08-14 [4]) —
                    "마감 확정 前 → 등록담당자가 우클릭 삭제 가능(레거시 수준)".

                    ★**취소와 다른 축이다.**
                    · **취소**: 있었던 거래를 되돌림 → 역분개가 장부에 **남는다**(그게 사실이다).
                    · **삭제**: 애초에 잘못 친 것 → 장부에 **남으면 안 된다**(없던 거래다).
                    합치면 마감 후 정당한 반품 취소와 오입력이 섞여, 세무 소명 때 가릴 수 없다.

                    · 재고는 **원 이벤트를 무효화**해 되돌린다(취소처럼 반대 이벤트를 새로 남기지 않는다).
                    · **사유는 선택**(9/27 회의 A-3, 항목 12 — 통합매출 취소·삭제 사유 제거).
                      그래도 지운 내용(거래처·도서·수량·금액)은 상태변경 이력에 남는다
                      — 삭제 후에는 거기에만 남는다. 사유를 안 보내면 `(사유 미입력)`으로 적힌다.
                    · **마감된 달은 400**(PERIOD_LOCKED) — 그때는 취소로 간다.
                    · **위탁정산 매출은 400** — 정산 행위의 결과물이지 오입력이 아니다.
                      취소로 가야 위탁 미결원장이 함께 되돌아간다.

                    ⚠️행은 DB에 남는다(논리삭제). 담당자가 보는 결과는 레거시와 같다 —
                    목록에서 사라진다. 복구 가능성만 더 얻는 것이다.""")
    // ‼️경로변수를 숫자로 제한한다. 안 그러면 이 매핑이 `/sales/summary`·`/sales/statement` 같은
    //   조회 경로까지 삼켜, "메서드만 틀린" 요청이 405가 아니라 400(숫자 변환 실패)이 된다.
    @DeleteMapping("/{id:\\d+}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                    @Valid @RequestBody(required = false) com.daesung.sales.logistics.dto.ReasonRequest req) {
        saleService.delete(id, com.daesung.sales.logistics.dto.ReasonRequest.reasonOf(req));
        return ApiResponse.success(null);
    }

    @Operation(summary = "매출 수정(오입력 정정)",
            description = """
                    등록한 매출의 **수량·정가·공급률·세액·할인액·메모·회차·성적처리·포장구분**을 고친다.
                    근거: 9/27 회의 항목 12 「통합매출 우클릭 [수정]」(항목 7·9도 같은 요구).

                    · **보내지 않은 항목은 유지**된다. 수량만 고치려다 메모가 지워지지 않게.
                    · 금액(공급가액·총금액)은 **항상 다시 계산**된다 — 수량만 바꿔도 따라간다.
                    · **세액은 자동산출하지 않는다**(재무팀 확정). 보내면 그 값, 안 보내면 기존 값.
                      면세 상품에 0이 아닌 세액을 보내면 400이다.
                    · **수량을 바꾸면 재고도 차액만큼 보정**된다. 원 재고이벤트는 고치지 않고
                      같은 매출번호로 차액 이벤트를 남긴다 — 그래야 과거 수불부를 다시 뽑아도
                      당시 숫자가 그대로 나온다.

                    ★**매출번호는 바뀌지 않는다.** 거래명세서·작업요청서·DSRE 신청번호·반품
                    원출고번호가 그 번호에 매달려 있다. (폐기 수정은 반대로 새 전표번호가 나간다 —
                    폐기는 그런 연결이 없고, 제자리 수정이면 폐기 목록과 요약이 어긋난다.)

                    **바꿀 수 없는 것** — 거래일자 · 거래처 · 도서 · 출고유형 · 창고 · 학교.
                    그 여섯이 바뀌면 사실상 다른 거래이고 발송 건을 묶는 키가 달라져 이미 나간
                    작업지시와 어긋난다. 그 경우는 **삭제 후 다시 등록**해야 한다.

                    **400인 경우** — 마감된 달(PERIOD_LOCKED) · 이미 취소된 매출 ·
                    위탁정산 매출(취소 후 재정산) · 바꿀 항목이 하나도 없음.""")
    @PatchMapping("/{id:\\d+}")
    public ApiResponse<SaleResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody com.daesung.sales.sale.dto.SaleUpdateRequest req) {
        return ApiResponse.success(saleService.update(id, req));
    }

    @Operation(summary = "매출 취소(논리 취소)",
            description = "원 매출을 삭제하지 않고 취소 표시 + 원출고 재고 역분개(복구). 이미 취소된 건은 400. "
                    + "위탁정산 매출은 연결 재고이벤트가 없어 재고 불변.")
    @PostMapping("/{id}/cancel")
    public ApiResponse<SaleResponse> cancel(
            @PathVariable Long id,
            @Parameter(description = """
                    취소 사유(선택). 상태변경 이력에 남는다.
                    ‼️입고·폐기 취소와 **같은 자리·같은 이름**이다 — 예전엔 매출만 받지 않아
                    화면이 이 동작에서만 사유칸을 못 띄웠다.""")
            @RequestParam(required = false) String reason) {
        return ApiResponse.success(saleService.cancel(id, reason));
    }

    @Operation(summary = "매출액정리 엑셀 다운로드",
            description = "분류·도서별 매출/반품/교사용/순매출 수량·금액. 조회와 같은 필터를 받는다.")
    @GetMapping("/summary/export")
    public ResponseEntity<byte[]> summaryExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String summaryKind,
            @RequestParam(required = false) String salesType,
            @Parameter(description = "거래분류 **다중선택**(SALES/FREE/RETURN). 조회와 같은 축")
            @RequestParam(required = false) List<TradeClass> tradeClasses,
            @RequestParam(required = false) String keyword) {
        List<Col> cols = List.of(
                new Col("분류코드", "catCode"), new Col("분류명", "catName"),
                new Col("상품코드", "productCode"), new Col("상품명", "productName"),
                new Col("매출수량", "saleQty"), new Col("매출액", "saleAmount"),
                new Col("증정수량", "freeQty"), new Col("증정액", "freeAmount"),
                new Col("교사용수량", "teacherQty"), new Col("교사용액", "teacherAmount"),
                new Col("반품수량", "returnQty"), new Col("반품액", "returnAmount"),
                new Col("순매출수량", "netQty"), new Col("순매출액", "netAmount"),
                new Col("세액", "tax"), new Col("합계", "total"));
        SalesSummaryResponse r = saleReportService.summary(fromDate, toDate, partnerId,
                summaryKind, salesType, tradeClasses);
        byte[] xlsx = excel.toXlsx("매출액정리", cols,
                Keywords.filter(r.rows(), keyword,
                        x -> new Object[]{x.productCode(), x.productName()}),
                Heading.period("매출액정리", r.fromDate(), r.toDate()));
        return excel.asDownload(xlsx, "매출액정리.xlsx");
    }

    @Operation(summary = "순매출 집계 조회",
            description = "기간·거래처로 상품별 매출/증정/교사용/반품 버킷 + 순매출(매출−반품) 집계 + 합계행. "
                    + "취소건 제외. 기간 미지정 시 올해 1/1~오늘.")
    @GetMapping("/summary")
    public ApiResponse<SalesSummaryResponse> summary(
            @Parameter(description = "시작일(yyyy-MM-dd, 미지정 시 올해 1/1)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd, 미지정 시 오늘)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "거래처 id 필터") @RequestParam(required = false) Long partnerId,
            @Parameter(description = "구분 — 매출/반품/교사용/증정용. 미지정=전체. ‼️모르는 값은 400")
            @RequestParam(required = false) String summaryKind,
            @Parameter(description = "매출유형 — 일반매출/위탁매출. 미지정=전체. ‼️모르는 값은 400")
            @RequestParam(required = false) String salesType,
            @Parameter(description = """
                    거래분류 **다중선택**(SALES/FREE/RETURN). 조회 화면과 같은 축이다(2026-10-08).
                    `summaryKind` 는 한 번에 하나뿐이라 "무상+반품만" 같은 조합을 표현할 수 없었다.""")
            @RequestParam(required = false) List<TradeClass> tradeClasses,
            @Parameter(description = "키워드 — 코드·명칭을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword) {
        SalesSummaryResponse r = saleReportService.summary(fromDate, toDate, partnerId,
                summaryKind, salesType, tradeClasses);
        // ‼️키워드는 **상세 행만** 거른다. 소계·합계는 거르기 전 기준이라 그대로 둔다 —
        //   거른 행에 맞춰 소계를 다시 만들면 "검색 결과의 소계"가 되어 신고·대사에 쓸 수 없다.
        //   화면이 키워드를 쓰는 순간은 "그 도서를 찾는" 때지 "그 범위로 집계하는" 때가 아니다.
        return ApiResponse.success(new SalesSummaryResponse(r.fromDate(), r.toDate(),
                Keywords.filter(r.rows(), keyword,
                        x -> new Object[]{x.productCode(), x.productName(),
                                x.catCode(), x.catName()}),
                r.total(), r.categorySubtotals()));
    }

    @Operation(summary = "콘텐츠구분 순매출 조회",
            description = "상품별 순매출(매출−반품). 외부콘텐츠(EXTERNAL)는 매입원가(입고 unit_cost 평균)로 "
                    + "매입액·이익·이익률까지 산출. contentType=SELF(자체교재)/EXTERNAL(외부콘텐츠)/미지정(전체).")
    @GetMapping("/net-summary")
    public ApiResponse<NetSalesResponse> netSummary(
            @Parameter(description = "시작일(yyyy-MM-dd)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "콘텐츠구분(SELF/EXTERNAL)") @RequestParam(required = false) String contentType,
            @Parameter(description = "거래처 id **다중선택**(2026-10-08)")
            @RequestParam(required = false) List<Long> partnerIds,
            @Parameter(description = "세부구분 **다중선택**")
            @RequestParam(required = false) List<String> divisions,
            @Parameter(description = "분류코드 **다중선택**")
            @RequestParam(required = false) List<String> catCodes,
            @Parameter(description = "도서 id **다중선택**")
            @RequestParam(required = false) List<Long> productIds,
            @Parameter(description = """
                    조회구분 — `PRODUCT`(도서별, 기본) / `PARTNER`(거래처별).
                    근거: 레거시 순매출조회 오른쪽 위 「조회구분 ○거래처별 ○분류별」
                    (현장 피드백 2026-10-14 B-2).

                    ★금액 칸은 둘이 같다 — 같은 화면의 두 보기라 칸이 달라지면 비교가 안 된다.
                    ‼️`PARTNER` 는 **매입·이익 칸을 비운다.** 매입단가는 상품 속성이라
                      거래처로 묶으면 서로 다른 단가가 섞인다 — 섞인 값으로 이익을 내면
                      틀린 숫자가 그럴듯하게 나온다.""")
            @RequestParam(required = false, defaultValue = "PRODUCT") String groupBy,
            @Parameter(description = "키워드 — 코드·명칭을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword) {
        // 대소문자 변환 없이 값을 그대로 본다 — equalsIgnoreCase 는 로케일에 따라
        // 결과가 달라진다(정적분석 IMPROPER_UNICODE, 터키어 i→İ).
        boolean byPartner = "PARTNER".equals(groupBy) || "partner".equals(groupBy);
        NetSalesResponse r = byPartner
                ? saleReportService.netSalesByPartner(fromDate, toDate, contentType,
                        partnerIds, divisions, catCodes, productIds)
                : saleReportService.netSales(fromDate, toDate, contentType,
                        partnerIds, divisions, catCodes, productIds);
        return ApiResponse.success(new NetSalesResponse(r.fromDate(), r.toDate(), r.contentType(),
                Keywords.filter(r.rows(), keyword,
                        x -> new Object[]{x.productCode(), x.productName(), x.catCode(), x.catName()}),
                r.total()));
    }

    @Operation(summary = "콘텐츠구분 순매출 엑셀 다운로드",
            description = "드라이브 순매출조회 형식(상품별 매출·반품·순매출 + 외부콘텐츠 매입·이익·이익률).")
    @GetMapping("/net-summary/export")
    public ResponseEntity<byte[]> netSummaryExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String contentType,
            @Parameter(description = "거래처 id **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<Long> partnerIds,
            @Parameter(description = "세부구분 **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<String> divisions,
            @Parameter(description = "분류코드 **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<String> catCodes,
            @Parameter(description = "도서 id **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<Long> productIds) {
        List<Col> cols = List.of(
                new Col("분류코드", "catCode"), new Col("분류명", "catName"),
                new Col("거래처코드", "partnerCode"), new Col("거래처명", "partnerName"),
                new Col("상품코드", "productCode"), new Col("상품명", "productName"), new Col("콘텐츠구분", "contentType"),
                new Col("매출수량", "saleQty"), new Col("매출 공급가액", "saleAmount"),
                new Col("교사용수량", "freeQty"), new Col("교사용 공급가액", "freeAmount"),
                new Col("반품수량", "returnQty"), new Col("반품률(%)", "returnRate"), new Col("반품액", "returnAmount"),
                new Col("순매출수량", "netQty"), new Col("순매출 공급가액", "netAmount"),
                new Col("순매출 세액", "netTax"), new Col("순매출 총금액", "netTotal"),
                new Col("매입 입고수량", "inboundQty"),
                new Col("매입반품수량", "purchaseReturnQty"),
                new Col("매입반품액", "purchaseReturnAmount"),
                new Col("매입단가", "purchaseUnitCost"), new Col("매입액", "purchaseAmount"),
                new Col("이익", "profit"), new Col("이익률(%)", "marginPct"));
        byte[] xlsx = excel.toXlsx("순매출조회", cols,
                saleReportService.netSales(fromDate, toDate, contentType,
                        partnerIds, divisions, catCodes, productIds).rows(),
                Heading.period("순매출조회", fromDate, toDate));
        return excel.asDownload(xlsx, "순매출조회.xlsx");
    }

    @Operation(summary = "매출액명세서",
            description = "대분류→분류(catCode)→도서 3계층으로 rollup한 매출 명세. 소계·총계 포함. "
                    + "★대분류는 상품의 세부구분에서 파생된다(모의고사·교재·기타고사·특강·기타). "
                    + "세부구분이 없는 상품은 '미분류'로 모인다 — 집계에서 빼지 않는다. 금액=공급가, 세액, 합계=금액+세액. 취소건 제외. "
                    + "category=SALE(매출)/FREE(무가)/RETURN(반품)/미지정(전체).")
    @GetMapping("/statement")
    public ApiResponse<SalesStatementResponse> statement(
            @Parameter(description = "시작일(yyyy-MM-dd)", required = true) @RequestParam(name = "fromDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일(yyyy-MM-dd)", required = true) @RequestParam(name = "toDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = """
                    구분 SALE(매출)/RETURN(반품)/TEACHER_USE(교사용)/GIFT(증정용). 미지정=전체.
                    ‼️표준 '구분(상세)'(3종)와 값이 다르다 — 무상을 교사용·증정용으로 쪼갠 축이다(정본 15p).""")
            @RequestParam(required = false) StatementKind kind,
            @Parameter(description = "매출유형 NORMAL_SALES(일반매출)/CONSIGN_SALES(위탁매출). 미지정=전체")
            @RequestParam(required = false) SalesType salesType,
            @Parameter(description = "거래처 id. 미지정=전체")
            @RequestParam(required = false) Long partnerId,
            @Parameter(description = """
                    거래분류 **다중선택**(매출·무상·반품·입고·대체·폐기).
                    근거: 발주처 1차회신 #8 + 프론트 실측 2026-10-12 B-1 —
                    10/08 회신에 적어 드린 `tradeClasses` 가 `/sales/summary` 에만 붙어 있었다.
                    매출액정리 화면이 쓰는 것은 이쪽이다.
                    ‼️입고·대체·폐기는 매출 원장에 없는 분류라 그것만 고르면 빈 결과다.""")
            @RequestParam(required = false) List<TradeClass> tradeClasses,
            @Parameter(description = "키워드 — 분류코드·분류명·도서코드·도서명을 함께 훑는다(부분일치). "
                    + "‼️소계·분류계는 걸러진 결과로 다시 계산된다(상세만 걸러내면 합이 안 맞는다)")
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(saleReportService.statement(
                from, to, partnerId, kind, salesType, tradeClasses, keyword));
    }

    @Operation(summary = "매출액명세서 엑셀 다운로드", description = "드라이브 '매출액정리' 형식(분류/도서별 수량·금액·세액·합계).")
    @GetMapping("/statement/export")
    public ResponseEntity<byte[]> statementExport(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) StatementKind kind,
            @RequestParam(required = false) SalesType salesType,
            @RequestParam(required = false) List<TradeClass> tradeClasses,
            @RequestParam(required = false) String keyword) {
        List<Col> cols = List.of(
                new Col("구분", "rowType"), new Col("대분류", "majorName"),
                new Col("분류코드", "catCode"), new Col("분류명", "catName"),
                new Col("도서코드", "bookCode"), new Col("도서명", "bookName"), new Col("수량", "qty"),
                new Col("금액", "amount"), new Col("세액", "tax"), new Col("합계", "total"));
        byte[] xlsx = excel.toXlsx("매출액명세서", cols,
                saleReportService.statement(from, to, partnerId, kind, salesType, tradeClasses, keyword).rows(),
                Heading.period("매출액명세서", from, to),
                com.daesung.sales.common.excel.ExcelExportUtil.AGG_BY_ROW_TYPE);
        return excel.asDownload(xlsx, "매출액명세서_" + from + "_" + to + ".xlsx");
    }

    @Operation(summary = "월별매출액명세서(37p)",
            description = "구분(대분류)×상품별 성적처리/비처리 인원·금액 + 계 + 과세매출액 + 부가세. "
                    + "★대분류는 상품의 세부구분에서 파생(모의고사·교재·기타고사·특강·기타). "
                    + "대분류 소계·총계 포함. 매출(SALE)만 집계(무상·반품 제외), 취소 제외. "
                    + "인원=수량(모의고사=응시인원), 성적처리=매출등록 proc_type(GRADED, 미지정=비처리). "
                    + "year·month 미지정 시 이번 달.")
    @GetMapping("/monthly-statement")
    public ApiResponse<MonthlyStatementResponse> monthlyStatement(
            @Parameter(description = "연도(미지정 시 올해)", example = "2026") @RequestParam(required = false) Integer year,
            @Parameter(description = "월 1~12(미지정 시 이번 달)", example = "6") @RequestParam(required = false) Integer month) {
        LocalDate now = LocalDate.now();
        int y = (year != null) ? year : now.getYear();
        int m = (month != null) ? month : now.getMonthValue();
        return ApiResponse.success(saleReportService.monthlyStatement(y, m));
    }

    @Operation(summary = "월별매출액명세서(37p) 엑셀 다운로드",
            description = "드라이브 '연구소 월별매출액명세서' 형식(구분×성적처리/비처리 인원·금액+과세·부가세).")
    @GetMapping("/monthly-statement/export")
    public ResponseEntity<byte[]> monthlyStatementExport(
            @RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month) {
        LocalDate now = LocalDate.now();
        int y = (year != null) ? year : now.getYear();
        int m = (month != null) ? month : now.getMonthValue();
        List<Col> cols = List.of(
                new Col("구분", "rowType"), new Col("대분류", "majorName"), new Col("분류명", "catName"),
                new Col("도서코드", "bookCode"), new Col("도서명", "bookName"),
                new Col("성적처리인원", "gradedQty"), new Col("성적처리금액", "gradedAmount"),
                new Col("비처리인원", "ungradedQty"), new Col("비처리금액", "ungradedAmount"),
                new Col("계인원", "totalQty"), new Col("계금액", "totalAmount"),
                new Col("과세매출액", "taxableAmount"), new Col("부가세", "vat"));
        byte[] xlsx = excel.toXlsx("월별매출액명세서", cols, saleReportService.monthlyStatement(y, m).rows(),
                new Heading("월별매출액명세서", "조회기준 : " + y + "년 " + m + "월"),
                com.daesung.sales.common.excel.ExcelExportUtil.AGG_BY_ROW_TYPE);
        return excel.asDownload(xlsx, "월별매출액명세서_" + y + "-" + String.format("%02d", m) + ".xlsx");
    }

    @Operation(summary = "거래명세서",
            description = "거래처×기간의 거래명세서 데이터. 공급자(자사)·공급받는자(거래처 세무정보) + "
                    + "유가(공급가액>0)/무가(교사용·증정) 라인 분리 + 합계(공급가액·세액·합계). 취소 제외. "
                    + "category 미지정=매출(SALE)+무가(FREE), category=RETURN이면 반품명세서.")
    @GetMapping("/transaction-statement")
    public ApiResponse<TransactionStatementResponse> transactionStatement(
            @Parameter(description = "거래처 id", required = true) @RequestParam Long partnerId,
            @Parameter(description = "시작일(yyyy-MM-dd)", required = true) @RequestParam(name = "fromDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일(yyyy-MM-dd)", required = true) @RequestParam(name = "toDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = """
                    학교/학원코드 — **그 학교 건만** 싣는다. 실물 양식의 「학교(원)」 칸은 값이 하나라
                    명세서는 (거래처 × 학교) 단위로 발행된다.
                    ‼️안 주면 기간의 모든 학교가 한 장에 섞이고 이름은 첫 학교만 찍힌다 —
                    발행 대상은 `GET /sales/transaction-statement/schools` 로 먼저 뽑을 것.""")
            @RequestParam(required = false) String schoolCode,
            @Parameter(description = "회계구분(미지정=매출+무가, RETURN=반품명세서)") @RequestParam(required = false)
            SalesCategory category) {
        return ApiResponse.success(
                saleReportService.transactionStatement(partnerId, from, to, category, schoolCode));
    }

    @Operation(summary = "거래명세서 발행 대상 학교 목록",
            description = """
                    그 거래처·기간에 **명세서를 몇 장 발행해야 하는지**. 한 장에 한 학교다.

                    실물 양식의 「학교(원)」 칸은 값이 하나라, 학교가 여럿이면 여러 장이다.
                    이 목록 없이 발행하면 학교가 섞인 한 장이 나오고 **첫 학교 이름만** 찍혀,
                    나머지 학교 물량이 남의 명세서에 실린다.""")
    @GetMapping("/transaction-statement/schools")
    public ApiResponse<List<com.daesung.sales.sale.repository.SaleRepository.StatementSchool>>
            transactionStatementSchools(
            @Parameter(description = "거래처 id", required = true) @RequestParam Long partnerId,
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) SalesCategory category) {
        return ApiResponse.success(saleReportService.statementSchools(partnerId, from, to, category));
    }

    @Operation(summary = "거래명세서 엑셀 다운로드", description = "유가+무가 라인 통합(품명·정가·공급률·단가·수량·금액·세액).")
    @GetMapping("/transaction-statement/export")
    public ResponseEntity<byte[]> transactionStatementExport(
            @RequestParam Long partnerId,
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) SalesCategory category) {
        var d = saleReportService.transactionStatement(partnerId, from, to, category);
        var all = new java.util.ArrayList<>(d.pricedLines());
        all.addAll(d.freeLines());
        List<Col> cols = List.of(
                new Col("순번", "seq"), new Col("품명", "bookLabel"), new Col("도서코드", "bookCode"),
                new Col("수량", "qty"), new Col("정가", "listPrice"), new Col("공급률", "supplyRate"),
                new Col("단가", "unitSupplyPrice"), new Col("공급가액", "supplyAmount"), new Col("세액", "tax"),
                new Col("구분", "category"), new Col("비고", "memo"));
        byte[] xlsx = excel.toXlsx("거래명세서", cols, all);
        return excel.asDownload(xlsx, "거래명세서_" + from + "_" + to + ".xlsx");
    }

    @Operation(summary = "과목별매출현황",
            description = """
                    거래처×분류(catCode)×도서 단위 현황. 매출·반품·순매출(매출−반품)·교사용의
                    **수량과 금액** + 반품률(%). 취소 제외. 거래처·분류 옵션 필터.

                    ‼️<b>화면은 제외됐지만 이 엔드포인트는 지우지 않는다.</b>
                    발주처가 과목별매출현황 화면을 신규 순매출조회로 대체했으나,
                    **처리내역서**(레거시 `처리내역서.vb`, 인쇄물 제목은 「거래처별 순매출 조회」)가
                    같은 축을 거래처로 rollup 해 쓴다 — 화면 담당이 명시적으로 존치를 요청했다(2026-08-11).
                    금액 컬럼(`saleAmount`·`returnAmount`·`netAmount`)이 그 요청으로 추가된 것이다.""")
    @GetMapping("/category-summary")
    public ApiResponse<CategorySalesResponse> categorySummary(
            @Parameter(description = "시작일(yyyy-MM-dd)", required = true) @RequestParam(name = "fromDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일(yyyy-MM-dd)", required = true) @RequestParam(name = "toDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "거래처 id 필터(미지정=전체)") @RequestParam(required = false) Long partnerId,
            @Parameter(description = "분류코드 필터(미지정=전체)") @RequestParam(required = false) String catCode) {
        return ApiResponse.success(saleReportService.categorySales(from, to, partnerId, catCode));
    }

    @Operation(summary = "도서입출고현황",
            description = "도서별 매입+매출 이중장부 종합. 매입측(입고·취소·취소율·실매입) + "
                    + "매출측(출고·반품·반품률·실판매) + 정본 재고(종료일 기준) + 매출총이익(실판매−실매입). "
                    + "취소=매입취소(INBOUND 역분개), 반품=매출반품. 취소건 제외. 분류·상품 옵션 필터.")
    @GetMapping("/book-inout")
    public ApiResponse<BookInoutResponse> bookInout(
            @Parameter(description = "시작일(yyyy-MM-dd)", required = true) @RequestParam(name = "fromDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일(yyyy-MM-dd)", required = true) @RequestParam(name = "toDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "분류코드 필터(미지정=전체)") @RequestParam(required = false) String catCode,
            @Parameter(description = "상품 id 필터(미지정=전체)") @RequestParam(required = false) Long productId) {
        return ApiResponse.success(saleReportService.bookInout(from, to, catCode, productId));
    }

    @Operation(summary = "미분류 매출 상품 목록(이관 검증)",
            description = """
                    대분류가 안 붙은 매출을 **상품 단위로, 매출 큰 순서로** 돌려준다.
                    근거: 10/27 검수 피드백 §7-C 하단 5번 — "데이터 이관 때 매핑되지 않는
                    누락 건은 반드시 목록으로 뽑아 확인한다".

                    · 대분류는 `매출 → 상품.세부구분 → 세부구분 마스터.대분류` 로 이어진다.
                      이 사슬이 끊긴 매출이 외상매출현황·매출액정리에서 '미분류'로 모인다.
                    · 끊긴 이유를 함께 준다 — `EMPTY`(상품에 세부구분이 비어 있다) /
                      `UNKNOWN`(값은 있는데 세부구분 마스터에 없다). **고치는 방법이 다르다.**
                    · 미분류 **비율(%)**도 함께 낸다. 목록 길이만으로는 심각도를 알 수 없다 —
                      한 번도 안 팔린 상품 100개보다 많이 팔린 상품 1개가 장부를 더 흔든다.""")
    @GetMapping("/unclassified")
    public ApiResponse<UnclassifiedSalesResponse> unclassified(
            @Parameter(description = "시작일(yyyy-MM-dd)", required = true) @RequestParam(name = "fromDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "종료일(yyyy-MM-dd)", required = true) @RequestParam(name = "toDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(saleReportService.unclassifiedSales(from, to));
    }

    @Operation(summary = "미분류 매출 상품 목록 엑셀",
            description = "위 목록을 그대로 내려받는다. 담당자가 도서관리에서 하나씩 채워야 하는 작업 목록이다.")
    @GetMapping("/unclassified/export")
    public ResponseEntity<byte[]> unclassifiedExport(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var r = saleReportService.unclassifiedSales(from, to);
        byte[] xlsx = excel.toXlsx("미분류매출", List.of(
                        new Col("도서코드", "productCode"), new Col("도서명", "productName"),
                        new Col("분류코드", "catCode"), new Col("분류명", "catName"),
                        new Col("세부구분(현재값)", "salesDivision"), new Col("사유", "reason"),
                        new Col("매출수량", "saleQty"), new Col("매출액", "saleAmount"),
                        new Col("마지막매출일", "lastSalesDate")),
                r.rows(),
                Heading.period("미분류 매출 상품 목록", from, to,
                        "미분류 " + String.format("%,d", r.amount()) + "원 / 전체 "
                                + String.format("%,d", r.totalAmount()) + "원 (" + r.ratio() + "%)"));
        return excel.asDownload(xlsx, "미분류매출.xlsx");
    }

    @Operation(summary = "거래처별 매출대비표(전년 동기간)",
            description = "당해 기간 매출을 전년 동기간([from−1년, to−1년])과 비교. 수량·금액 + 증감 + 비율(%, 당해÷전년×100). "
                    + "groupBy=PARTNER(거래처)/CATEGORY(거래처×분류)/BOOK(거래처×도서). SALE만, 취소 제외. 거래처·분류 옵션 필터.")
    @GetMapping("/yoy-comparison")
    public ApiResponse<YoyComparisonResponse> yoyComparison(
            @Parameter(description = "당해 시작일(yyyy-MM-dd)", required = true) @RequestParam(name = "fromDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "당해 종료일(yyyy-MM-dd)", required = true) @RequestParam(name = "toDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "집계 단위(PARTNER/CATEGORY/BOOK)") @RequestParam(defaultValue = "PARTNER")
            YoyComparisonResponse.GroupBy groupBy,
            @Parameter(description = "거래처 id 필터(미지정=전체)") @RequestParam(required = false) Long partnerId,
            @Parameter(description = "분류코드 필터(미지정=전체)") @RequestParam(required = false) String catCode,
            @Parameter(description = "키워드 — 코드·명칭을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword) {
        YoyComparisonResponse r = saleReportService.yoyComparison(from, to, groupBy, partnerId, catCode);
        return ApiResponse.success(new YoyComparisonResponse(r.from(), r.to(), r.prevFrom(), r.prevTo(),
                r.groupBy(),
                Keywords.filter(r.rows(), keyword,
                        x -> new Object[]{x.partnerCode(), x.partnerName(), x.catCode(), x.catName(),
                                x.bookCode(), x.bookName()})));
    }

    @Operation(summary = "과목별매출현황 엑셀 다운로드", description = "거래처×분류×도서 매출/반품/순매출/교사용 수량+반품률.")
    @GetMapping("/category-summary/export")
    public ResponseEntity<byte[]> categorySummaryExport(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long partnerId, @RequestParam(required = false) String catCode) {
        List<Col> cols = List.of(
                new Col("거래처코드", "partnerCode"), new Col("거래처명", "partnerName"),
                new Col("분류코드", "catCode"), new Col("분류명", "catName"),
                new Col("도서코드", "bookCode"), new Col("도서명", "bookName"),
                new Col("매출수량", "saleQty"), new Col("매출금액", "saleAmount"),
                new Col("반품수량", "returnQty"), new Col("반품금액", "returnAmount"),
                new Col("순매출수량", "netQty"), new Col("순매출금액", "netAmount"),
                new Col("교사용수량", "teacherQty"), new Col("교사용금액", "teacherAmount"),
                new Col("반품률(%)", "returnRate"));
        byte[] xlsx = excel.toXlsx("과목별매출현황", cols, saleReportService.categorySales(from, to, partnerId, catCode).rows());
        return excel.asDownload(xlsx, "과목별매출현황_" + from + "_" + to + ".xlsx");
    }

    @Operation(summary = "도서입출고현황 엑셀 다운로드", description = "도서별 매입+매출 이중장부 + 정본재고 + 매출총이익.")
    @GetMapping("/book-inout/export")
    public ResponseEntity<byte[]> bookInoutExport(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String catCode, @RequestParam(required = false) Long productId) {
        List<Col> cols = List.of(
                new Col("도서코드", "bookCode"), new Col("도서명", "bookName"),
                new Col("분류코드", "catCode"), new Col("분류명", "catName"), new Col("정가", "listPrice"),
                new Col("입고수량", "inboundQty"), new Col("입고금액", "inboundAmount"),
                new Col("취소수량", "cancelQty"), new Col("취소금액", "cancelAmount"), new Col("취소율(%)", "cancelRate"),
                new Col("실매입수량", "netPurchaseQty"), new Col("실매입금액", "netPurchaseAmount"),
                new Col("출고수량", "outboundQty"), new Col("출고금액", "outboundAmount"),
                new Col("반품수량", "returnQty"), new Col("반품금액", "returnAmount"), new Col("반품률(%)", "returnRate"),
                new Col("실판매수량", "netSalesQty"), new Col("실판매금액", "netSalesAmount"),
                new Col("재고", "stockQty"), new Col("매출총이익", "grossMargin"));
        byte[] xlsx = excel.toXlsx("도서입출고현황", cols, saleReportService.bookInout(from, to, catCode, productId).rows());
        return excel.asDownload(xlsx, "도서입출고현황_" + from + "_" + to + ".xlsx");
    }

    @Operation(summary = "거래처별 매출대비표 엑셀 다운로드", description = "당해 vs 전년 동기간 수량·금액 증감·비율.")
    @GetMapping("/yoy-comparison/export")
    public ResponseEntity<byte[]> yoyComparisonExport(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "PARTNER") YoyComparisonResponse.GroupBy groupBy,
            @RequestParam(required = false) Long partnerId, @RequestParam(required = false) String catCode) {
        List<Col> cols = List.of(
                new Col("거래처코드", "partnerCode"), new Col("거래처명", "partnerName"),
                new Col("분류코드", "catCode"), new Col("분류명", "catName"),
                new Col("도서코드", "bookCode"), new Col("도서명", "bookName"),
                new Col("당해수량", "curQty"), new Col("당해금액", "curAmount"),
                new Col("전년수량", "prevQty"), new Col("전년금액", "prevAmount"),
                new Col("증감수량", "diffQty"), new Col("증감금액", "diffAmount"),
                new Col("수량비율(%)", "qtyRatioPct"), new Col("금액비율(%)", "amountRatioPct"));
        byte[] xlsx = excel.toXlsx("매출대비표", cols,
                saleReportService.yoyComparison(from, to, groupBy, partnerId, catCode).rows());
        return excel.asDownload(xlsx, "매출대비표_" + from + "_" + to + ".xlsx");
    }

    @Operation(summary = "회차별 포장유형 물량(구 IC회차별작업현황) — ⚠️화면30이 아니다",
            description = """
                    분류×도서×**회차**를 행으로, 포장구분(개별1/개별2/반별) 수량을 열로 펼쳐 보여준다.
                    레거시 IC회차별작업현황 화면 재현 — 회차가 없는 건(0)은 제외되고, 취소 건도 빠진다.
                    catCode를 주면 해당 분류만 조회한다.

                    ### ⚠️화면30(회차별작업현황)은 이제 이 API가 아니다
                    발주처가 화면검토(2026-08-31)에서 "이 화면이 세트 조립·해체를 추적하는 화면인지,
                    아니면 회차별 포장유형별 물량을 집계하는 화면인지" 확인을 요청했고,
                    **실측 결과 후자(이 API)였다.** 그래서 요청대로 화면30은
                    **`GET /stock/bom-work-status`(세트 조립·해체 현황)** 으로 재구성했다.

                    이 API는 **레거시 재현으로서 그대로 남겨 둔다** — 포장유형별 물량은 물류에서
                    여전히 쓰는 숫자이고, 지운다고 조립·해체 현황이 더 정확해지지 않는다.""")
    @GetMapping("/round-work-status")
    public ApiResponse<List<RoundWorkStatusRow>> roundWorkStatus(
            @Parameter(description = "시작일(yyyy-MM-dd)") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd)") @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "분류코드(선택)") @RequestParam(required = false) String catCode,
            @Parameter(description = "키워드 — 코드·명칭을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(Keywords.filter(
                saleReportService.roundWorkStatus(fromDate, toDate, catCode), keyword,
                r -> new Object[]{r.catCode(), r.catName(), r.productCode(), r.productName()}));
    }

    @Operation(summary = "응시현황(연도별)",
            description = """
                    거래처별 **월별 수량·매출 크로스탭**(1월[수량]·1월[매출] … 12월 + 합계).

                    ⚠️이름과 달리 '시험 신청·응시율' 표가 아니다 — 레거시 응시현황.vb 실물이
                    이 구성이고, 신청·응시율 같은 항목은 레거시 어디에도 없다.

                    · 행은 **거래처 → 지역구분 소계 → 총계** 3단이다(레거시 rollup과 같다).
                    · 반품은 음수로 반영되고 취소는 제외된다(레거시가 반품을 음수로 저장해
                      SUM만으로 순수량이 나오던 것과 값이 같아진다).
                    · 학년·상품구분 필터는 레거시와 같은 축이다(도서의 학년/상품구분).

                    발주처 회신(2-2): 레거시 화면이 "2022년까지만 조회 가능"으로 막혀 있고
                    전산담당자가 부재라, 매출 데이터로 다시 만들어 달라는 요청이었다.""")
    @GetMapping("/attendance-yearly")
    public ApiResponse<AttendanceResponse> attendanceYearly(
            @Parameter(description = "조회 연도", example = "2026") @RequestParam int year,
            @Parameter(description = "학년 필터(도서 학년). 미지정=전체", example = "3")
            @RequestParam(required = false) String grade,
            @Parameter(description = "상품구분 필터. 미지정=전체", example = "교재")
            @RequestParam(required = false) String productType,
            @Parameter(description = "거래처 검색(코드·명 부분일치). 미지정=전체", example = "대성")
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(attendanceService.yearly(year, grade, productType, keyword));
    }

    @Operation(summary = "응시현황(연도별) 엑셀 다운로드")
    @GetMapping("/attendance-yearly/export")
    public org.springframework.http.ResponseEntity<byte[]> attendanceYearlyExport(
            @RequestParam int year,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) String keyword) {
        var cols = new java.util.ArrayList<com.daesung.sales.common.excel.ExcelExportUtil.Col>();
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("구분", "rowType"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("지역구분", "regionGroup"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("특약점코드", "partnerCode"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("특약점명", "partnerName"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("지역", "cityName"));
        for (int m = 1; m <= 12; m++) {
            cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col(
                    m + "월[수량]", "monthlyQty[" + (m - 1) + "]"));
            cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col(
                    m + "월[매출]", "monthlyAmount[" + (m - 1) + "]"));
        }
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("합계[수량]", "totalQty"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("합계[매출]", "totalAmount"));
        byte[] xlsx = excel.toXlsx("응시현황", cols, attendanceService.yearly(year, grade, productType, keyword).rows(),
                new com.daesung.sales.common.excel.ExcelExportUtil.Heading("응시현황(연도별)", "조회기준 : " + year + "년"),
                com.daesung.sales.common.excel.ExcelExportUtil.AGG_BY_ROW_TYPE);
        return excel.asDownload(xlsx, "응시현황_연도별.xlsx");
    }

    @Operation(summary = "응시현황 — 지역비율(보급률) 조회",
            description = """
                    레거시 응시현황 **'지역비율'** 라디오(F-22·F-23). 지역별로
                    **인원 · 매출 · 비율**을 1·2·3학년·계로 낸다.

                    ‼️**세 칸의 뜻이 이름과 다르다**(레거시 SQL 로 확정) —
                    · **인원 = 학교의 재학생 수**(학교관리의 학년별 재학생 칸). **매출 수량이 아니다.**
                    · **매출 = 판매 부수**(`sum(qty)`). **금액이 아니다** —
                      레거시에서 금액(`totalAmt`) 줄은 **주석으로 막혀** 있다.
                    · **비율 = 부수 ÷ 재학생 × 100 = 보급률(%)**. 재학생이 0이면 **null**(빈 칸).

                    ★**재학생 자료가 있는 학교만** 집계한다. 자료 없는 학교를 0명으로 세우면
                    분모만 커져 비율이 작아진다 — 자료 없음과 0명은 다르다.
                    모집단을 확인할 수 있게 `schoolCount`(집계에 든 학교 수)를 함께 준다.

                    ★학년은 **도서의 학년**이다(레거시도 `bookData.grade`). 학교 학년이 아니다.
                    학년이 없는 도서(공통·자재)는 학년 분해에서 빠진다.""")
    @GetMapping("/attendance-ratio")
    public ApiResponse<com.daesung.sales.sale.dto.AttendanceRatioResponse> attendanceRatio(
            @Parameter(description = "조회 연도", example = "2026") @RequestParam int year,
            @Parameter(description = "학교/학원 구분(SCHOOL/HAKWON). 미지정=전체")
            @RequestParam(required = false) com.daesung.sales.school.entity.SchoolType schoolType,
            @Parameter(description = "거래처구분(특약점·기타학원 등). 미지정=전체", example = "특약점")
            @RequestParam(required = false) String clientCategory) {
        return ApiResponse.success(
                attendanceService.regionRatio(year, schoolType, clientCategory));
    }

    @Operation(summary = "응시현황 — 지역비율 엑셀 다운로드",
            description = "조회와 같은 표. 레거시 컬럼 순서(지역 · 인원 4 · 매출 4 · 비율 4) 그대로.")
    @GetMapping("/attendance-ratio/export")
    public org.springframework.http.ResponseEntity<byte[]> attendanceRatioExport(
            @RequestParam int year,
            @RequestParam(required = false) com.daesung.sales.school.entity.SchoolType schoolType,
            @RequestParam(required = false) String clientCategory) {
        List<com.daesung.sales.common.excel.ExcelExportUtil.Col> cols = List.of(
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("지역", "region"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("인원[1학년]", "studentCnt1"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("인원[2학년]", "studentCnt2"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("인원[3학년]", "studentCnt3"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("인원[계]", "studentCntTotal"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("매출[1학년]", "bookCnt1"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("매출[2학년]", "bookCnt2"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("매출[3학년]", "bookCnt3"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("매출[계]", "bookCntTotal"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("비율[1학년]", "ratio1"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("비율[2학년]", "ratio2"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("비율[3학년]", "ratio3"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("비율[계]", "ratioTotal"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("학교수", "schoolCount"));
        byte[] xlsx = excel.toXlsx("응시현황(지역비율)", cols,
                attendanceService.regionRatio(year, schoolType, clientCategory).rows(),
                new com.daesung.sales.common.excel.ExcelExportUtil.Heading(
                        "응시현황(지역비율)", "조회기준 : " + year + "년"),
                com.daesung.sales.common.excel.ExcelExportUtil.AGG_BY_ROW_TYPE);
        return excel.asDownload(xlsx, "응시현황_지역비율.xlsx");
    }

    @Operation(summary = "회차별작업현황 엑셀 다운로드",
            description = "분류·도서·회차별 포장유형(개별1/개별2/반별) 물량.")
    @GetMapping("/round-work-status/export")
    public ResponseEntity<byte[]> roundWorkStatusExport(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String catCode,
            @RequestParam(required = false) String keyword) {
        List<Col> cols = List.of(
                new Col("분류코드", "catCode"), new Col("분류명", "catName"),
                new Col("도서코드", "productCode"), new Col("도서명", "productName"),
                new Col("회차", "bookRound"),
                new Col("개별1", "individual1"), new Col("개별2", "individual2"),
                new Col("반별", "classBundle"));
        byte[] xlsx = excel.toXlsx("회차별작업현황", cols,
                Keywords.filter(saleReportService.roundWorkStatus(fromDate, toDate, catCode), keyword,
                        x -> new Object[]{x.catCode(), x.catName(), x.productCode(), x.productName()}),
                Heading.period("회차별작업현황", fromDate, toDate), com.daesung.sales.common.excel.ExcelExportUtil.AGG_BY_ROW_TYPE);
        return excel.asDownload(xlsx, "회차별작업현황.xlsx");
    }

    @Operation(summary = "응시현황(기간별) 18p",
            description = """
                    모의고사 응시(처리)현황을 **기간별**로 본다.
                    행은 지역 · 특약점 · 학교 · 학년, 열은 **조회 기간의 각 월** × 처리/비처리/계.
                    소계는 학교 → 특약점 → 지역 → 총계 순으로 붙는다.

                    · **모의고사만** 집계한다. 판별은 상품의 **대분류**로 한다.
                    · 처리/비처리는 매출등록의 성적처리 구분(`procType`)이다(미지정=비처리).
                    · 인원 = 매출 수량. 매출(SALE)만 — 증정·반품은 응시가 아니다.
                    · 월 컬럼 개수는 **조회 기간에 따라 달라진다**. 응답 `months`(yyyy-MM)가
                      `monthlyGraded`/`monthlyUngraded`/`monthlyTotal` 배열의 순서를 알려준다.

                    ⚠️레거시(`고사별처리인원.vb`)는 이 화면이 **"2022년까지만 조회 가능"** 으로 막혀 있었다.
                    정본에는 '2022년 이후 데이터 미유입'으로 적혀 있으나 실측 결과 원인은 데이터가 아니라
                    **코드**였다 — 모의고사 판별을 연도가 박힌 분류코드(`catCode in ('M22A','M22B')`)로 하고
                    영역·월을 도서명 문자열로 긁어서, 연도마다 SQL을 복붙해야 했고 2022년에 멈춘 것이다.
                    여기서는 연도에 의존하는 부분을 두지 않아 해가 바뀌어도 손댈 곳이 없다.""")
    @GetMapping("/attendance-period")
    public ApiResponse<AttendancePeriodResponse> attendancePeriod(
            @Parameter(description = "시작일(yyyy-MM-dd)", required = true) @RequestParam(name = "fromDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd)", required = true) @RequestParam(name = "toDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "학년 필터(도서 학년). 미지정=전체", example = "고3")
            @RequestParam(required = false) String grade,
            @Parameter(description = "거래처(특약점) 필터. 미지정=전체")
            @RequestParam(required = false) Long partnerId,
            @Parameter(description = """
                    학년 **다중선택** — 예: `1,2,3`. 레거시 조회화면의 학년 체크박스에 대응한다.
                    단건 `grade`와 함께 주면 합집합이다.""")
            @RequestParam(required = false) List<String> grades,
            @Parameter(description = """
                    조회구분 — `REGION`(지역별) / `PARTNER`(특약점별) / `SCHOOL`(학교별, 기본).
                    레거시 라디오 버튼에 대응한다. 고른 단위까지만 펼치고 아래는 접는다.
                    ‼️모르는 값은 400 — 조용히 기본값으로 주면 행 수가 수십 배 달라지는데 오류가 안 난다.""")
            @RequestParam(required = false) String groupBy,
            @Parameter(description = "키워드 — 코드·명칭을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword) {
        assertWithinTwelveMonths(fromDate, toDate);
        AttendancePeriodResponse r = attendanceService.period(fromDate, toDate, grade, grades,
                partnerId, groupBy);
        return ApiResponse.success(new AttendancePeriodResponse(r.fromDate(), r.toDate(), r.months(),
                Keywords.filter(r.rows(), keyword,
                        x -> new Object[]{x.region(), x.partnerCode(), x.partnerName(),
                                x.schoolCode(), x.schoolName()})));
    }

    @Operation(summary = "응시현황(기간별) 엑셀 다운로드",
            description = "월 컬럼은 조회 기간에서 만들어지므로 기간에 따라 열 개수가 달라진다.")
    @GetMapping("/attendance-period/export")
    public org.springframework.http.ResponseEntity<byte[]> attendancePeriodExport(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) Long partnerId) {
        AttendancePeriodResponse res = attendanceService.period(fromDate, toDate, grade, partnerId);
        var cols = new java.util.ArrayList<com.daesung.sales.common.excel.ExcelExportUtil.Col>();
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("구분", "rowType"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("지역", "region"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("특약점코드", "partnerCode"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("특약점명", "partnerName"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("학교코드", "schoolCode"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("학교명", "schoolName"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("학년", "grade"));
        // 월 컬럼은 응답이 알려준 목록으로 만든다 — 상수로 두면 레거시와 같은 하드코딩이 된다.
        for (int i = 0; i < res.months().size(); i++) {
            String m = res.months().get(i);
            cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col(
                    m + "[처리]", "monthlyGraded[" + i + "]"));
            cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col(
                    m + "[비처리]", "monthlyUngraded[" + i + "]"));
            cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col(
                    m + "[계]", "monthlyTotal[" + i + "]"));
        }
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("합계[처리]", "gradedTotal"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("합계[비처리]", "ungradedTotal"));
        cols.add(new com.daesung.sales.common.excel.ExcelExportUtil.Col("합계[계]", "total"));
        byte[] xlsx = excel.toXlsx("응시현황(기간별)", cols, res.rows(),
                com.daesung.sales.common.excel.ExcelExportUtil.Heading.period("응시현황(기간별)", fromDate, toDate),
                com.daesung.sales.common.excel.ExcelExportUtil.AGG_BY_ROW_TYPE);
        return excel.asDownload(xlsx, "응시현황_기간별_" + fromDate + "_" + toDate + ".xlsx");
    }

    /**
     * 날짜 파라미터 별칭 합치기.
     *
     * <p>★{@code /sales}는 {@code startDate/endDate}, 나머지 조회 API는 {@code fromDate/toDate}를
     * 쓰고 있었다. 이름을 한쪽으로 바꾸면 이미 붙어 있는 화면이 조용히 전체기간 조회가 된다
     * (파라미터가 안 맞으면 400이 아니라 <b>null이 되어 필터가 사라진다</b>).
     * 그래서 바꾸지 않고 <b>둘 다 받는다.</b> 둘 다 오면 원래 이름을 쓴다.
     */
    private static LocalDate either(LocalDate primary, LocalDate alias) {
        return (primary != null) ? primary : alias;
    }

    @Operation(summary = "거래상세내역서 이메일 전송(12p)",
            description = """
                    조회 결과를 **거래처별 엑셀**로 만들어 거래처 이메일로 보낸다.
                    근거: 9/27 회의 A-4(항목 13) — "통합매출조회 … 이메일 버튼 없음 → 추가
                    (기존 매출프로그램에 있는 기능)". 레거시 `조회.vb:3781` 과 같은 동작이다.

                    · **거래처마다 따로 보낸다.** 한 파일에 전 거래처를 담으면 각 거래처가
                      남의 매출을 본다 — 레거시가 거래처별로 나눈 이유가 그것이다.
                    · 수신은 거래처 마스터의 **이메일1·이메일2**. 1이 비고 2만 있으면 2로 보낸다(레거시 동일).
                    · 제목 `{거래처명} 거래상세내역서(2026.06.01-2026.06.30)`.
                    · **한 건이 실패해도 나머지는 보낸다.** 거래처별 결과(SENT/FAILED/NO_EMAIL)를 돌려준다 —
                      레거시는 건수만 세고 상세를 로그 파일에 적어, 실패한 거래처를 찾으려면 그 파일을 열어야 했다.
                    · `NO_EMAIL` 은 실패가 아니다. 거래처관리에서 주소를 채워야 하는 건이다.
                    · ★**조회(`GET /sales`)와 같은 필터를 받는다**(2026-10-01). 구분·출고유형·창고·학교·
                      쿠폰 포함 카운트까지 그대로다. 화면에서 걸러 본 것과 다른 내용이 나가면 안 된다 —
                      메일은 되돌릴 수 없다.
                    · ‼️메일 설정이 없으면 400 — 조용히 실패하면 담당자는 보냈다고 믿는다.""")
    @PostMapping("/email")
    public ApiResponse<com.daesung.sales.common.mail.MailSendResponse> email(
            @Parameter(description = "시작일", required = true) @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일", required = true) @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "회계구분 **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<SalesCategory> salesCategories,
            @Parameter(description = "거래분류 **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<TradeClass> tradeClasses,
            @Parameter(description = "출고유형 **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<ShipmentType> shipmentTypes,
            @Parameter(description = "거래처 id **다중선택**. 비우면 조회 결과에 나온 거래처 전부(일괄 발송)")
            @RequestParam(required = false) List<Long> partnerIds,
            @Parameter(description = "창고 id **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<Long> warehouseIds,
            @Parameter(description = "학교코드 **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<String> schoolCodes,
            @Parameter(description = "세부구분 **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<String> divisions,
            @Parameter(description = "도서 id **다중선택**(조회와 동일)")
            @RequestParam(required = false) List<Long> productIds,
            @Parameter(description = "거래처×학교 조합(조회와 동일)")
            @RequestParam(required = false) List<String> partnerSchools,
            @Parameter(description = "판매채널 **다중선택**(조회와 동일). '미분류' 가능")
            @RequestParam(required = false) List<String> salesChannels,
            @Parameter(description = "키워드(조회와 같은 축)") @RequestParam(required = false) String keyword,
            @Parameter(description = "쿠폰 포함 카운트(조회와 동일, 기본 false)")
            @RequestParam(required = false, defaultValue = "false") boolean couponCount) {
        return ApiResponse.success(salesMailService.sendStatements(fromDate, toDate,
                salesCategories, tradeClasses, shipmentTypes, partnerIds, warehouseIds,
                schoolCodes, divisions, productIds, partnerSchools, salesChannels,
                keyword, couponCount));
    }

    /**
     * 응시현황(기간별)은 <b>12개월까지</b>만 받는다.
     *
     * <p>★화면이 월 칸을 12개까지만 그린다 — 더 넓게 조회하면 같은 달이 겹쳐 읽을 수가 없다
     * (프론트 확인 2026-10-21, 화면에서도 같은 상한을 건다).
     *
     * <p>‼️여기서 막는 이유는 따로 있다. 이 응답은 <b>페이징할 수 없다</b> —
     * 행에 학교 줄과 소계 줄이 섞여 있어(SCHOOL·PARTNER_SUBTOTAL·REGION_SUBTOTAL·TOTAL)
     * 줄 단위로 자르면 묶음이 쪽 사이에서 끊기고 총계가 마지막 쪽에만 남는다.
     * 그래서 <b>기간으로 막는 것이 유일한 안전장치</b>다 —
     * 전 기간(15년) 조회가 7.2MB 였다(2026-10-20 실측).
     */
    private static void assertWithinTwelveMonths(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return;
        }
        if (to.isBefore(from)) {
            throw new com.daesung.sales.common.exception.BusinessException(com.daesung.sales.common.exception.ErrorCode.INVALID_INPUT,
                    "종료일이 시작일보다 빠릅니다.");
        }
        if (from.plusMonths(12).minusDays(1).isBefore(to)) {
            throw new com.daesung.sales.common.exception.BusinessException(com.daesung.sales.common.exception.ErrorCode.INVALID_INPUT,
                    "응시현황은 한 번에 12개월까지 조회할 수 있습니다. "
                            + "기간을 나눠서 조회해 주세요(요청: " + from + " ~ " + to + ").");
        }
    }
}
