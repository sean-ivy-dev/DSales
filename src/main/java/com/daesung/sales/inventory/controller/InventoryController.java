package com.daesung.sales.inventory.controller;

import org.springframework.web.bind.annotation.PathVariable;
import com.daesung.sales.common.excel.ExcelExportUtil;
import com.daesung.sales.common.excel.ExcelExportUtil.Col;
import com.daesung.sales.common.excel.ExcelExportUtil.Heading;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.inventory.dto.BomWorkRequest;
import com.daesung.sales.inventory.dto.BomWorkResponse;
import com.daesung.sales.inventory.dto.InboundRequest;
import com.daesung.sales.inventory.dto.InboundResponse;
import com.daesung.sales.inventory.dto.StockLedgerRow;
import com.daesung.sales.inventory.dto.StockRecordRow;
import com.daesung.sales.inventory.dto.StockSettlementRow;
import com.daesung.sales.inventory.dto.TransferRequest;
import com.daesung.sales.inventory.dto.TransferResponse;
import com.daesung.sales.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.daesung.sales.common.query.MultiSelect;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 주문/출고관리 - 재고(입고/이고/BOM). 실제 경로: /api/v1/stock. */
@Tag(name = "주문/출고관리 · 재고", description = "재고 엔진(로직 A): 입고/이고/BOM")
@RestController
@RequiredArgsConstructor
@RequestMapping("/stock")
public class InventoryController {

    private final InventoryService inventoryService;
    private final com.daesung.sales.common.audit.CurrentAuditor currentAuditor;
    private final com.daesung.sales.inventory.service.MaterialLedgerService materialLedgerService;
    private final com.daesung.sales.inventory.service.MaterialStockService materialStockService;
    private final ExcelExportUtil excel;

    @Operation(summary = "전표 삭제(마감 前)",
            description = """
                    **잘못 입력한 전표를 없던 것으로** 만든다(발주처 2026-08-14 [4] "마감 확정 前 삭제 가능").

                    ★취소와 다른 축이다 — 취소는 "되돌렸다"를 반대 이벤트로 장부에 남기고,
                    삭제는 애초에 없던 일로 만든다(원 이벤트를 무효화하고 잔량만 되돌린다).

                    · **사유 필수.** 지운 품목·수량은 상태변경 이력에 남는다.
                    · **이미 취소된 전표는 400** — 되돌린 기록이 장부에 선 뒤라 오입력이 아니다.
                    · **마감된 달은 400**(PERIOD_LOCKED).

                    ⚠️{@code inventory_txn} 행은 남는다(논리삭제). 재고의 유일 진실이라
                    물리삭제하면 수불부·채권이 파생되는 원장을 찢는 것과 같다.""")
    @DeleteMapping("/inbound/{refNo}")
    public ApiResponse<Void> deleteVoucher(
            @Parameter(description = "전표번호", required = true) @PathVariable String refNo,
            @Valid @RequestBody com.daesung.sales.logistics.dto.RevertRequest req) {
        inventoryService.deleteVoucher(refNo, req.reason(), currentAuditor.username());
        return ApiResponse.success(null);
    }

    @Operation(summary = "일반 입고 등록",
            description = "인쇄소 등 → 물류창고 입고. 재고이벤트(INBOUND) 기록 + 재고 잔량 가산을 한 트랜잭션으로 처리")
    @PostMapping("/inbound")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InboundResponse> inbound(@Valid @RequestBody InboundRequest req) {
        return ApiResponse.success(inventoryService.inbound(req));
    }

    @Operation(summary = "단순 이고(창고 이동)",
            description = "출발창고 −qty(음수재고 방지) / 도착창고 +qty. 매출 미발생. 재고이벤트 2다리를 한 트랜잭션으로.")
    @PostMapping("/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransferResponse> transfer(@Valid @RequestBody TransferRequest req) {
        return ApiResponse.success(inventoryService.transfer(req));
    }

    @Operation(summary = "세트 조립/해체(BOM)",
            description = "조립=완제품+/구성품−, 해체=반대. 구성품·비율은 상품 BOM 마스터에서 읽음. 음수재고 방지, 한 트랜잭션.")
    @PostMapping("/bom")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BomWorkResponse> bom(@Valid @RequestBody BomWorkRequest req) {
        return ApiResponse.success(inventoryService.bom(req));
    }

    @Operation(summary = "입고/대체 내역 조회(8p·9p)",
            description = """
                    등록된 **입고·단순이고·세트조립/해체**를 최근순으로. 기간·도서·창고·구분으로 좁힌다.

                    ★**등록만 되고 조회가 없었다.** 전부 재고를 움직이는 작업인데 되짚어 볼
                    방법이 없으면, 재고가 안 맞을 때 언제 무엇이 들어오고 옮겨졌는지 알 수 없다.

                    수불부(`/stock/ledger`)는 **합계**를 주고 이건 **낱건**을 준다 —
                    합계가 이상할 때 그 안을 들여다보는 용도라 둘 다 필요하다.

                    ★수량 부호는 **그대로** 준다. 이고는 출발(−)·도착(+) 두 줄이고
                    세트작업도 완제품(+)·구성품(−)으로 갈린다 — 방향이 곧 정보다.

                    출고·폐기·반품·실사는 제외한다(각자 화면이 따로 있다).

                    도서·창고·작업구분 모두 **다중선택**이다(좌측 트리뷰 체크박스, 2026-08-31 공통 요구).
                    단수 파라미터도 그대로 살아 있고, 복수와 같이 오면 합집합이다.""")
    @GetMapping("/records")
    public ApiResponse<List<StockRecordRow>> records(
            @Parameter(description = "처리일 시작(yyyy-MM-dd). 미지정=전체") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "처리일 종료(yyyy-MM-dd). 미지정=전체") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "도서(상품) id 필터(단건)") @RequestParam(required = false) Long productId,
            @Parameter(description = "도서(상품) id **다중선택**") @RequestParam(required = false)
            List<Long> productIds,
            @Parameter(description = "창고 id 필터(단건)") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "창고 id **다중선택**") @RequestParam(required = false)
            List<Long> warehouseIds,
            @Parameter(description = "작업구분 INBOUND(입고)/TRANSFER(단순이고)/"
                    + "BOM_ASSEMBLE(세트조립)/BOM_DISASSEMBLE(세트해체). 미지정=전체 (단건)")
            @RequestParam(required = false) com.daesung.sales.inventory.entity.TxnType kind,
            @Parameter(description = "작업구분 **다중선택** — 예: INBOUND,TRANSFER")
            @RequestParam(required = false) List<com.daesung.sales.inventory.entity.TxnType> kinds) {
        return ApiResponse.success(inventoryService.stockRecords(fromDate, toDate,
                MultiSelect.merge(productId, productIds),
                MultiSelect.merge(warehouseId, warehouseIds),
                MultiSelect.merge(kind, kinds)));
    }

    @Operation(summary = "제품수불부 조회",
            description = "이월+입고+이고+BOM+폐기(+출고) = 현재재고 단일 공식 집계. "
                    + "이벤트합계(closing)와 캐시(inventory.qty) 대사(reconciled) 포함. "
                    + "기간 미지정 시 올해 1/1~오늘.")
    @GetMapping("/ledger")
    public ApiResponse<com.daesung.sales.common.response.PageResponse<StockLedgerRow>> ledger(
            @Parameter(description = "시작일(yyyy-MM-dd, 미지정 시 올해 1/1)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd, 미지정 시 오늘)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "상품 id 필터") @RequestParam(required = false) Long productId,
            @Parameter(description = "창고 id 필터") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = """
                    창고구분 MAIN(물류창고)/CONSIGN(위탁창고). 미지정=전체.
                    발주처 요청(2026-08-21): 전체 합산만 보면 **위탁 미결잔여가 실제로 어느 창고에
                    남아 있는지** 알 수 없다.""")
            @RequestParam(required = false) com.daesung.sales.warehouse.entity.WarehouseType warehouseType,
            @Parameter(description = "분류코드 **다중선택**(2026-10-08). 예: `catCodes=M2026A01,H2026A01`")
            @RequestParam(required = false) List<String> catCodes,
            @Parameter(description = "키워드 — 도서코드·도서명·창고명을 한 번에 훑는다(부분일치)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = """
                    정렬 — `필드,방향` (예: `closing,desc`). 방향 생략 시 오름차순.
                    필드: productCode · productName · warehouseName · opening · inbound ·
                    sale · netSaleQty · salesReturn · dispose · closing.
                    ‼️모르는 필드는 400 — 조용히 기본 순서로 주면 정렬이 먹은 줄 알고 위에서부터 판단한다.""")
            @RequestParam(required = false) String sort,
            @Parameter(description = "페이지(0부터)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "100") int size) {
        // ★집계 후 자른다. SQL에 LIMIT을 걸면 페이지마다 이월·마감이 달라진다
        //   (수불부는 상품×창고로 묶은 뒤라야 이월이 정확하다). 총건수는 전체를 준다.
        return ApiResponse.success(com.daesung.sales.common.response.PageResponse.ofList(
                inventoryService.stockLedger(fromDate, toDate, productId, warehouseId,
                        warehouseType, keyword, sort, catCodes),
                page, size));
    }

    @Operation(summary = "제품수불부 엑셀 다운로드", description = "이월/입고/이고/조립해체/폐기/매출/무상/교사용/반품/조정/현재재고.")
    @GetMapping("/ledger/export")
    public ResponseEntity<byte[]> ledgerExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long productId, @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) com.daesung.sales.warehouse.entity.WarehouseType warehouseType) {
        List<Col> cols = List.of(
                new Col("도서코드", "productCode"), new Col("도서명", "productName"), new Col("창고", "warehouseName"),
                new Col("이월", "opening"), new Col("입고", "inbound"), new Col("이고", "transfer"),
                new Col("조립해체", "bom"), new Col("폐기", "dispose"), new Col("매출", "sale"),
                new Col("무상", "free"), new Col("교사용", "teacher"), new Col("반품", "salesReturn"),
                new Col("조정", "adjust"), new Col("순매출수량", "netSaleQty"),
                new Col("현재재고", "closing"));
        byte[] xlsx = excel.toXlsx("제품수불부", cols,
                inventoryService.stockLedger(fromDate, toDate, productId, warehouseId, warehouseType),
                Heading.period("제품수불부현황", fromDate, toDate));
        return excel.asDownload(xlsx, "제품수불부.xlsx");
    }

    @Operation(summary = "제품수불부 자재 상세(11p 2단계)",
            description = """
                    요약(세트·회차)에서 한 행을 골랐을 때 **구성 자재가 몇 장 들어갔는지**.

                    ★**자재 재고가 아니다.** 발주처 원문 — "자재 자체의 입고·이월을 반영한
                    재고 잔량이 아니라 해당 세트 내 **소요량** 기준".
                    자재를 얼마나 들여왔는지가 아니라, 세트가 팔린 만큼 얼마나 쓰였는지를 낸다.

                    합계 = **세트 출고분 + 회차 단독 출고분**
                    (각각 그 상품의 출고수량 × 세트당 소요수량)

                    출고수량 = 매출 + 무상 + 교사용 + 폐기 − 반품 ± 세트조립/해체.
                    ‼️**창고이동은 빠진다** — 창고만 옮긴 것은 소비가 아니다(발주처 확정).

                    회차를 지정하면 그 회차 전용 자재 + 공통 자재만 나온다.

                    ### 공통 자재의 '회차 단독 출고분'
                    구조보완요청안 각주가 공통 자재를 **두 종류**로 나눠 놓았다 —
                    "회차마다 반복 사용되는 자재(OMR, **4회차 기준 4**)는 회차 수만큼 반영한 값을,
                    세트 전체에 한 번만 필요한 자재(쿠폰)는 **1**로 고정한 값을 입력".
                    숫자만으로는 `4`가 "4회차×1"인지 "세트당 4개 고정"인지 구분되지 않아,
                    자재 매칭에 **회차반복(`perRound`)** 플래그를 둔다(V61).

                    · **반복형** — 회차당 소요 = 세트당수량 ÷ 회차수(OMR 4 ÷ 4회차 = 1).
                      회차만 단독으로 팔려도 그만큼 나간다.
                    · **1회형**(기본) — 0. 세트를 사야 붙는 자재라 회차만 사면 나가지 않는다.

                    ‼️회차 수는 **필터 전 세트 전체 기준**으로 센다. 1회만 골랐다고 회차수가 1이 되면
                    OMR이 4장으로 뻥튀기된다.""")
    @GetMapping("/ledger/materials")
    public ApiResponse<com.daesung.sales.inventory.dto.MaterialLedgerResponse> materials(
            @Parameter(description = "세트 상품 id", required = true) @RequestParam Long setProductId,
            @Parameter(description = "회차 상품 id(미지정 시 세트 전체 자재)")
            @RequestParam(required = false) Long roundProductId,
            @Parameter(description = "시작일(yyyy-MM-dd). 미지정=전체") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd). 미지정=전체") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ApiResponse.success(
                materialLedgerService.materials(setProductId, roundProductId, fromDate, toDate));
    }

    @Operation(summary = "제품수불부 결산내역(연초~기준일 누적)",
            description = """
                    기준일자 연도 1월 1일부터 기준일까지의 수불 전체내역. 분류 소계·총계 포함.
                    레거시 제품수불부 「결산내역」 체크박스와 같은 뷰다 —
                    화면 안내문 원문 "&lt;결산내역&gt; 체크시 기준일자 연도 1월1일부터 기준일자 까지의
                    제품수불 전체내역을 보여줍니다".
                    ★시작일은 받지 않는다(연초 고정). 분류 필터도 없다(레거시는 결산 시 분류 콤보를 잠근다).
                    창고는 합산하되 창고구분 필터는 남긴다.""")
    @GetMapping("/ledger/settlement")
    public ApiResponse<List<StockSettlementRow>> settlement(
            @Parameter(description = "기준일(yyyy-MM-dd, 미지정 시 오늘). 시작일은 이 날짜의 연도 1/1로 고정")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate,
            @Parameter(description = "상품 id 필터") @RequestParam(required = false) Long productId,
            @Parameter(description = "창고구분 MAIN/CONSIGN. 미지정=전체")
            @RequestParam(required = false) com.daesung.sales.warehouse.entity.WarehouseType warehouseType) {
        return ApiResponse.success(inventoryService.stockSettlement(baseDate, productId, warehouseType));
    }

    @Operation(summary = "제품수불부 결산내역 엑셀 다운로드",
            description = "연초~기준일 누적 + 분류 소계·총계.")
    @GetMapping("/ledger/settlement/export")
    public ResponseEntity<byte[]> settlementExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) com.daesung.sales.warehouse.entity.WarehouseType warehouseType) {
        List<Col> cols = List.of(
                new Col("구분", "rowType"), new Col("분류코드", "catCode"), new Col("분류명", "catName"),
                new Col("도서코드", "productCode"), new Col("도서명", "productName"),
                new Col("이월", "opening"), new Col("입고", "inbound"), new Col("이고", "transfer"),
                new Col("조립해체", "bom"), new Col("폐기", "dispose"), new Col("매출", "sale"),
                new Col("무상", "free"), new Col("교사용", "teacher"), new Col("반품", "salesReturn"),
                new Col("조정", "adjust"), new Col("재고", "closing"));
        byte[] xlsx = excel.toXlsx("제품수불부결산", cols,
                inventoryService.stockSettlement(baseDate, productId, warehouseType),
                Heading.asOf("제품수불부 결산내역", baseDate),
                com.daesung.sales.common.excel.ExcelExportUtil.AGG_BY_ROW_TYPE);
        return excel.asDownload(xlsx, "제품수불부_결산내역.xlsx");
    }

    private static final String BOM_WORK_DESC = """
            세트를 **조립·해체한 결과**를 세트별로 집계한다.
            근거: 발주처 화면검토(2026-08-31) 화면30 — "화면 27(물류 작업비 계산)의 비용 산출과는
            **연동되지 않도록 분리**해, 세트 조립·해체 작업을 진행한 **현황(결과)만** 보여주는 조회 화면".

            ### ★비용 칸이 없다
            발주처가 요구한 '분리'는 화면을 나누는 것이 아니라 **이 화면이 비용을 말하지 않는 것**이다.
            금액 칸이 하나라도 있으면 화면27과 값이 갈리는 순간 어느 쪽이 맞는지 다투게 된다.
            작업비는 화면27(물류작업비) 소관이다.

            ### 무엇을 세나
            · 작업 건수 = **완제품 이벤트 수**(작업 1회에 정확히 하나 생긴다)
            · 조립 수량 / 해체 수량 / **순증**(조립 − 해체). 순증이 음수면 그 기간에 푼 쪽이 많다.
            · `components` = 그 기간 조립·해체로 움직인 **구성품 합계**
              ⚠️**어느 세트 작업에 쓰였는지는 나누지 않는다.** 원장에 완제품↔구성품 링크가 없다.
              지금 BOM 비율로 역산하면 그럴듯한 숫자가 나오지만, 작업한 뒤 BOM이 바뀌었으면
              실제와 다른 값을 사실처럼 보여주게 된다. 모르는 것은 모른다고 둔다.

            ### ⚠️이전 구현을 대체한다
            종전 화면30은 **회차별 포장유형(개별1/개별2/반별) 물량 집계**였다. 레거시
            `IC회차별작업현황.vb`를 그대로 옮긴 것이라 레거시로서는 맞았지만, 발주처가
            "후자라면 조립·해체 현황으로 구성해 달라"고 확인 요청했고 실측 결과 후자였다.
            포장유형 집계가 필요하면 `GET /sales/round-work-status`가 그대로 남아 있다.""";

    @Operation(summary = "세트 조립·해체 현황(30p)", description = BOM_WORK_DESC)
    @GetMapping("/bom-work-status")
    public ApiResponse<com.daesung.sales.inventory.dto.BomWorkStatusResponse> bomWorkStatus(
            @Parameter(description = "작업일 시작(yyyy-MM-dd). 미지정=전체") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "작업일 종료(yyyy-MM-dd). 미지정=전체") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "창고 id 필터(단건)") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "창고 id **다중선택**") @RequestParam(required = false)
            List<Long> warehouseIds,
            @Parameter(description = "분류코드 필터") @RequestParam(required = false) String catCode) {
        return ApiResponse.success(inventoryService.bomWorkStatus(fromDate, toDate,
                MultiSelect.merge(warehouseId, warehouseIds), catCode));
    }

    @Operation(summary = "세트 조립·해체 현황 엑셀 다운로드(30p)",
            description = "세트별 조립·해체 수량. 작업비는 담지 않는다(화면27 소관).")
    @GetMapping("/bom-work-status/export")
    public ResponseEntity<byte[]> bomWorkStatusExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) List<Long> warehouseIds,
            @RequestParam(required = false) String catCode) {
        List<Col> cols = List.of(
                new Col("분류코드", "catCode"), new Col("분류명", "catName"),
                new Col("도서코드", "productCode"), new Col("도서명", "productName"),
                new Col("조립건수", "assembleCount"), new Col("조립수량", "assembleQty"),
                new Col("해체건수", "disassembleCount"), new Col("해체수량", "disassembleQty"),
                new Col("순증", "netQty"), new Col("최근작업일", "lastWorkedAt"));
        byte[] xlsx = excel.toXlsx("세트조립해체현황", cols,
                inventoryService.bomWorkStatus(fromDate, toDate,
                        MultiSelect.merge(warehouseId, warehouseIds), catCode).rows(),
                Heading.period("세트 조립·해체 현황", fromDate, toDate));
        return excel.asDownload(xlsx, "세트_조립해체현황.xlsx");
    }
    @Operation(summary = "재고 전표 취소(전표 종류 무관)",
            description = """
                    입고·**이고·세트작업**·폐기 어느 전표든 전표번호로 취소한다(역분개).

                    ★예전엔 `/stock/inbound/{refNo}/cancel` 하나뿐이고 설명이 "입고 전표"라,
                    화면이 입고 행에만 되돌리기 버튼을 열고 있었다(추측으로 쏘지 않기 위해).
                    이고(TR-)·세트작업(BW-)에는 애초에 **전표번호가 안 붙어** 가리킬 수도 없었다.
                    번호를 붙였고, 이 경로가 종류를 가리지 않는다.

                    · 취소는 **반대 이벤트를 새로 남긴다** — "되돌렸다"가 장부에 남는 방식이다.
                      오입력을 없던 일로 만들려면 삭제(`DELETE`)를 쓴다.
                    · 마감된 달은 400(PERIOD_LOCKED). 이미 취소된 전표도 400.
                    · ⚠️번호가 붙기 전(2026-09-18 이전)에 만들어진 이고·세트작업 전표는
                      `refNo`가 비어 있어 되돌릴 수 없다. 소급 부여는 하지 않았다 —
                      어떤 행들이 한 전표였는지 되살릴 근거가 없어, 묶는 순간
                      되돌리기가 남의 작업까지 끌고 간다.""")
    @PostMapping("/vouchers/{refNo}/cancel")
    public ApiResponse<com.daesung.sales.inventory.dto.VoucherCancelResponse> cancelAnyVoucher(
            @Parameter(description = "전표번호(IN-·TR-·BW-·P-)", required = true)
            @PathVariable String refNo,
            @Parameter(description = "취소 사유") @RequestParam(required = false) String reason) {
        return ApiResponse.success(inventoryService.cancelVoucher(refNo, reason));
    }

    @Operation(summary = "재고 전표 삭제(마감 前, 전표 종류 무관)",
            description = """
                    잘못 입력한 전표를 **없던 것으로** 만든다. 입고·이고·세트작업·폐기 공통이다.

                    취소와 다른 축이다 — 취소는 되돌린 기록을 장부에 남기고,
                    삭제는 원 이벤트를 무효화한다. **사유 필수**이고 지운 내용은 이력에 남는다.""")
    @DeleteMapping("/vouchers/{refNo}")
    public ApiResponse<Void> deleteAnyVoucher(
            @Parameter(description = "전표번호", required = true) @PathVariable String refNo,
            @Valid @RequestBody com.daesung.sales.logistics.dto.RevertRequest req) {
        inventoryService.deleteVoucher(refNo, req.reason(), currentAuditor.username());
        return ApiResponse.success(null);
    }

    @Operation(summary = "입고 취소(역분개)",
            description = """
                    입고 전표를 통째로 되돌린다. **물리 삭제가 아니다** —
                    반대 부호 이벤트를 새로 적어 상쇄하고 취소 이력을 남긴다.

                    · 같은 전표를 두 번 취소하면 재고가 반대로 밀린다 → **두 번째는 400**.
                    · **마감된 달은 막는다**(PERIOD_LOCKED).
                    · ‼️입고는 예전에 전표번호가 없었다. 지금은 등록 응답의 `inboundNo`(IN-…)를 쓴다.""")
    @PostMapping("/inbound/{refNo}/cancel")
    public ApiResponse<com.daesung.sales.inventory.dto.VoucherCancelResponse> cancelInbound(
            @Parameter(description = "입고 전표번호(IN-…)", required = true) @PathVariable String refNo,
            @Parameter(description = "취소 사유") @RequestParam(required = false) String reason) {
        return ApiResponse.success(inventoryService.cancelVoucher(refNo, reason));
    }

    @Operation(summary = "제품수불부 — 세트의 회차별 현황(요약 → 상세 2단계)",
            description = """
                    수불부 요약 행(세트)에서 **회차로 내려가는** 단계다.

                    ```
                    요약   세트          GET /stock/ledger
                    중간   └ 회차        **이 API**
                    상세     └ 자재      GET /stock/ledger/materials
                    ```

                    · 회차 행은 **수불부와 같은 집계**다 — 여기서 다시 계산하지 않는다.
                    · **거래가 없는 회차도 0으로 담는다.** 빼면 "이 회차는 왜 없지"가 된다.""")
    @GetMapping("/ledger/rounds")
    public ApiResponse<com.daesung.sales.inventory.dto.SetRoundLedgerResponse> ledgerRounds(
            @Parameter(description = "세트 상품 id", required = true) @RequestParam Long setProductId,
            @Parameter(description = "시작일(yyyy-MM-dd)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "창고구분 MAIN/CONSIGN. 미지정=전체")
            @RequestParam(required = false) com.daesung.sales.warehouse.entity.WarehouseType warehouseType) {
        return ApiResponse.success(
                inventoryService.setRounds(setProductId, fromDate, toDate, warehouseType));
    }


    // ── 자재 재고(V78) ──────────────────────────────────────────────────────────
    // 근거: 9/27 회의 A-1 — "dsre는 매출만 변경, 매출에서는 수불·자재 다 관리" ·
    //       항목 6 "자재별로 입고 가능하게" · 항목 22 "입고/대체등록에 자재 등록 내역이 보이게".

    @Operation(summary = "자재 입고",
            description = """
                    자재(시험지·해설지·OMR·라벨·단행본)를 창고로 입고한다.

                    ★도서 입고(`POST /stock/inbound`)와 **요청 모양이 같다** — `productId` 자리에
                    `materialId` 가 올 뿐이다. 같은 화면에서 품목 종류만 바꿔 쓰는 동작이라 일부러 맞췄다.

                    · 재고이벤트(INBOUND) 기록과 잔량 가산을 **한 트랜잭션**으로 처리한다.
                    · 전표번호는 도서 입고와 같은 `IN-` 채번을 쓴다.
                    · 잔량 갱신은 원자적 UPDATE — 같은 자재를 동시에 입고해도 유실되지 않는다.""")
    @PostMapping("/materials/inbound")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<com.daesung.sales.inventory.dto.MaterialInboundResponse> materialInbound(
            @Valid @RequestBody com.daesung.sales.inventory.dto.MaterialInboundRequest req) {
        return ApiResponse.success(materialStockService.inbound(req));
    }

    @Operation(summary = "자재 재고 현황",
            description = """
                    자재별 **현재 잔량**을 창고 단위로 낸다.

                    ‼️제품수불부의 '자재 상세'(`GET /stock/ledger/materials`)와 **다른 것**이다.
                    그쪽은 "세트가 팔리면서 자재가 몇 장 **쓰였나**"(소요량)이고,
                    여기는 "그래서 지금 창고에 몇 장 **남았나**"(잔량)다.
                    9/27 회의 전까지는 앞의 것만 있었다.

                    잔량이 0인 자재도 낸다 — "취급한 적 없는 자재"와 "다 쓴 자재"는 다르다.""")
    @GetMapping("/materials")
    public ApiResponse<List<com.daesung.sales.inventory.dto.MaterialStockRow>> materialStock(
            @Parameter(description = "자재 id 필터(미지정=전체)") @RequestParam(required = false) Long materialId,
            @Parameter(description = "창고 id 필터(미지정=전체)") @RequestParam(required = false) Long warehouseId,
            @Parameter(description = "자재구분 필터(시험지/해설지/OMR/라벨/단행본/기타 — 한글·코드명 모두 가능)")
            @RequestParam(required = false) com.daesung.sales.product.entity.MaterialType materialType) {
        return ApiResponse.success(materialStockService.stock(materialId, warehouseId, materialType));
    }

    @Operation(summary = "자재 재고 현황 엑셀 다운로드")
    @GetMapping("/materials/export")
    public ResponseEntity<byte[]> materialStockExport(
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) com.daesung.sales.product.entity.MaterialType materialType) {
        List<Col> cols = List.of(
                new Col("자재코드", "materialCode"), new Col("자재명", "materialName"),
                new Col("자재구분", "materialType"), new Col("창고", "warehouseName"),
                new Col("현재잔량", "qty"));
        byte[] xlsx = excel.toXlsx("자재재고현황", cols,
                materialStockService.stock(materialId, warehouseId, materialType));
        return excel.asDownload(xlsx, "자재재고현황.xlsx");
    }

    @Operation(summary = "자재 이고(창고 이동)",
            description = """
                    자재를 창고 사이로 옮긴다. 출발 −수량 · 도착 +수량을 **한 트랜잭션**으로 처리하고
                    전표번호(`TR-`)를 붙인다 — 번호가 있어야 나중에 되돌릴 수 있다.

                    도서 이고(`POST /stock/transfer`)와 요청 모양이 같다(`productId` → `materialId`).""")
    @PostMapping("/materials/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<com.daesung.sales.inventory.dto.MaterialTransferResponse> materialTransfer(
            @Valid @RequestBody com.daesung.sales.inventory.dto.MaterialTransferRequest req) {
        return ApiResponse.success(materialStockService.transfer(req));
    }

    @Operation(summary = "자재 입출고 등록(출고·회수·폐기·파손)",
            description = """
                    DSRE2 자재입출고관리(`FM_LOGI_MatInOut`)의 구분을 그대로 옮긴 등록 API 다.
                    근거: 9/27 회의 항목 22 "dsre 자재입출고관리 참고".

                    | 구분 | 잔량 |
                    |---|---|
                    | `OUTBOUND` 출고 — 시행·작업으로 나감(**자재가 줄어드는 주 경로**) | − |
                    | `RECOVER_ACCIDENT` 회수(사고처리용) · `RECOVER_RETURN` 회수(반품) | + |
                    | `DISPOSE` 폐기 · `DAMAGE` 파손 | − |

                    · **수량은 언제나 양수로 보낸다.** 나가는 구분이면 서버가 음수로 기록한다.
                    · 구분은 한글로도 받는다(화면이 목록에서 고른 값을 그대로 되보내므로).
                    · ‼️**폐기와 파손은 잔량 효과가 같아 이 구분으로만 갈린다** — 뭉치면 되돌릴 수 없다.
                    · ‼️**사유 칸이 없다**(9/27 A-3, 항목 9). 비고는 남아 있다.
                    · `INBOUND`(입고)는 거래처·단가가 필요해 전용 API(`/stock/materials/inbound`)를 쓴다.""")
    @PostMapping("/materials/io")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<com.daesung.sales.inventory.dto.MaterialIoResponse> materialIo(
            @Valid @RequestBody com.daesung.sales.inventory.dto.MaterialIoRequest req) {
        return ApiResponse.success(materialStockService.io(req));
    }

    @Operation(summary = "자재 입출고 수정(10/06 요청 2-3)",
            description = """
                    자재 입출고 전표의 품목·수량·비고를 고친다.

                    ★**삭제 + 재등록이다(한 트랜잭션). 새 전표번호가 나간다** — 폐기 수정과 같은 방식이고
                    이유도 같다: 자재 내역 화면이 재고 이벤트를 직접 읽어서, 제자리에서 고치면
                    한 전표가 두 줄로 보이거나 상세와 합계가 어긋난다.
                    취소+재등록도 안 된다 — 역분개가 **취소한 달**로 잡혀 원 수량이 기간 합계에 남는다.

                    ‼️**입고(IN-) 전표는 못 고친다.** 입고는 거래처·입고단가가 붙어 있어 축이 다르다
                    (`POST /stock/materials/inbound`). 섞으면 그 값들이 조용히 사라진다.

                    · 마감된 달은 400(PERIOD_LOCKED) · 이미 취소된 전표도 400.
                    · 어느 전표가 어느 전표로 바뀌었는지 상태변경 이력에 남는다.""")
    @org.springframework.web.bind.annotation.PatchMapping("/materials/io/{refNo}")
    public ApiResponse<com.daesung.sales.inventory.dto.MaterialIoResponse> reviseMaterialIo(
            @Parameter(description = "수정할 전표번호", required = true) @PathVariable String refNo,
            @Valid @RequestBody com.daesung.sales.inventory.dto.MaterialIoRequest req) {
        return ApiResponse.success(
                materialStockService.reviseIo(refNo, req, currentAuditor.username()));
    }

    @Operation(summary = "자재 거래 내역",
            description = """
                    자재의 **입고·이고·폐기·실사** 내역을 최근순으로 낸다.
                    근거: 9/27 회의 항목 22 — "도서관리-자재관리, 입고/대체등록에 자재 등록한 내역이 보이게".

                    도서 내역(`GET /stock/records`)과 **서로 섞이지 않는다** — 같은 원장에 있지만
                    이쪽은 자재 행만, 저쪽은 도서 행만 낸다.

                    자재·창고·작업구분은 **다중선택**이다(예: `kinds=INBOUND,DISPOSE`).""")
    @GetMapping("/materials/records")
    public ApiResponse<List<com.daesung.sales.inventory.dto.MaterialRecordRow>> materialRecords(
            @Parameter(description = "시작일") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "자재 id **다중선택**") @RequestParam(required = false) List<Long> materialIds,
            @Parameter(description = "창고 id **다중선택**") @RequestParam(required = false) List<Long> warehouseIds,
            @Parameter(description = "작업구분 **다중선택**(INBOUND/TRANSFER/DISPOSE/ADJUST). 미지정=전체")
            @RequestParam(required = false) List<com.daesung.sales.inventory.entity.TxnType> kinds) {
        return ApiResponse.success(
                materialStockService.records(fromDate, toDate, materialIds, warehouseIds, kinds));
    }

    @Operation(summary = "자재 거래 내역 엑셀 다운로드")
    @GetMapping("/materials/records/export")
    public ResponseEntity<byte[]> materialRecordsExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) List<Long> materialIds,
            @RequestParam(required = false) List<Long> warehouseIds,
            @RequestParam(required = false) List<com.daesung.sales.inventory.entity.TxnType> kinds) {
        List<Col> cols = List.of(
                new Col("처리일자", "date"), new Col("작업구분", "kind"),
                new Col("창고", "warehouse"), new Col("자재코드", "materialCode"),
                new Col("자재명", "materialName"), new Col("자재구분", "materialType"),
                new Col("증감수량", "qtyDelta"), new Col("입고단가", "unitCost"),
                new Col("전표번호", "refNo"), new Col("비고", "memo"));
        byte[] xlsx = excel.toXlsx("자재거래내역", cols,
                materialStockService.records(fromDate, toDate, materialIds, warehouseIds, kinds),
                Heading.period("자재거래내역", fromDate, toDate));
        return excel.asDownload(xlsx, "자재거래내역.xlsx");
    }
}
