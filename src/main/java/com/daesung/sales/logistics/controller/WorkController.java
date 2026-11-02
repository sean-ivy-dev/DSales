package com.daesung.sales.logistics.controller;

import com.daesung.sales.common.query.Keywords;
import com.daesung.sales.common.excel.ExcelExportUtil;
import com.daesung.sales.common.excel.ExcelExportUtil.Col;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.logistics.dto.ReasonRequest;
import com.daesung.sales.logistics.dto.ShippingUpdateRequest;
import com.daesung.sales.logistics.dto.TrackingsUpdateRequest;
import com.daesung.sales.logistics.dto.TrackingUploadResponse;
import com.daesung.sales.logistics.entity.DeliveryType;
import com.daesung.sales.logistics.dto.WorkOrderResponse;
import com.daesung.sales.logistics.dto.WorkResultRow;
import com.daesung.sales.logistics.service.ShipmentService;
import com.daesung.sales.logistics.service.WorkService;
import com.daesung.sales.warehouse.entity.WarehouseType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 물류/작업 — 작업요청서 · 작업결과. 실제 경로: /api/v1/logistics.
 *
 * <p>발송 건은 매출등록 시 자동으로 만들어진다(레거시와 동일). 물류는 여기서
 * <b>작업지시를 출력하고, 박스 수·발송일을 채운다</b>. 작업결과는 그 진행을 모아 보는 조회 화면이다.
 */
@Tag(name = "물류 · 작업요청서/작업결과",
        description = "발송 건별 작업지시(무엇을 몇 개)와 진행 현황(출력·박스·발송일)")
@RestController
@RequestMapping("/logistics")
@RequiredArgsConstructor
public class WorkController {

    private final WorkService workService;
    private final com.daesung.sales.dashboard.service.ActionItemService actionItemService;
    private final ShipmentService shipmentService;
    private final ExcelExportUtil excel;

    @Operation(summary = "작업요청서 조회",
            description = """
                    발송 건과 **그 안에 담을 도서 목록**을 함께 돌려준다(무엇을 몇 개 넣어라).

                    · 발송 건은 **(거래일자 · 거래처 · 학교 · 분류)** 단위다.
                      같은 날 같은 학교로 여러 품목을 등록해도 발송은 한 건이다 —
                      물류는 품목이 아니라 상자 단위로 일한다.
                    · `printed=false`로 **아직 지시가 안 나간 건만** 볼 수 있다(레거시 동일 필터).
                    · 반품은 대상이 아니다(들어오는 물건이라 내보낼 작업이 없다).""")
    @GetMapping("/work-orders")
    public ApiResponse<com.daesung.sales.common.response.PageResponse<WorkOrderResponse>> workOrders(
            @Parameter(description = "거래일자 시작", required = true)
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "거래일자 종료", required = true)
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "분류(매출구분). 미지정=전체") @RequestParam(required = false) String tradeClass,
            @Parameter(description = "거래처 id. 미지정=전체") @RequestParam(required = false) Long partnerId,
            @Parameter(description = "출력여부 true=출력분/false=미출력분/미지정=전체")
            @RequestParam(required = false) Boolean printed,
            @Parameter(description = "확인여부 true=확인분/false=미확인분/미지정=전체. "
                    + "printed=true & acknowledged=false 로 '지시는 나갔는데 아직 확인 안 된 건'을 추린다")
            @RequestParam(required = false) Boolean acknowledged,
            @Parameter(description = "발송구분 COURIER(택배)/FREIGHT(화물). 미지정=전체(미선택 건 포함)")
            @RequestParam(required = false) DeliveryType deliveryType,
            @org.springdoc.core.annotations.ParameterObject
            com.daesung.sales.common.dto.PageRequestDto pageReq) {
        return ApiResponse.success(com.daesung.sales.common.response.PageResponse.of(
                workService.workOrdersPage(fromDate, toDate, tradeClass, partnerId,
                        printed, acknowledged, deliveryType, pageReq.toPageable())));
    }

    @Operation(summary = "미확인 출고요청 건수",
            description = """
                    아직 작업지시가 나가지 않은(미출력) 발송 건수. 좌측 메뉴 배지·대시보드 카드가 쓴다 —
                    화면을 열지 않아도 대기 건이 있는지 보이게 하려는 것이다.
                    판정은 작업요청서 조회의 `printed=false`와 **같다**. 따로 세면 배지에는 3건인데
                    화면을 열면 5건인 상황이 생기고, 그때 담당자는 둘 다 못 믿게 된다.
                    기간을 안 주면 기준일부터 30일 전까지를 센다(무한정 거슬러 세면 몇 년 전
                    미출력분까지 잡혀 숫자가 늘 커져 있고, 그러면 아무도 안 본다).""")
    @GetMapping("/work-orders/new-count")
    public ApiResponse<Integer> newWorkOrderCount(
            @Parameter(description = "기준일(미지정 시 오늘)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return ApiResponse.success(actionItemService.newWorkOrderCount(asOf));
    }

    @Operation(summary = "박스별 송장 저장(여러 개)",
            description = """
                    한 주문에 **박스 수만큼** 송장을 둔다(10/27 명세 §8-3 —
                    "주문은 한 줄이지만 송장번호는 박스 수만큼 여러 개 등록할 수 있게 한다").

                    · **보낸 목록이 그대로 저장된다** — 기존 줄은 사라진다(표를 통째로 저장).
                    · **빈 배열을 보내면 송장을 모두 지운다**(잘못 넣은 것을 되돌리는 길).
                    · 박스 번호는 1부터, 한 발송 안에서 겹치면 400.

                    ‼️기존 단일 필드(`courierName`·`trackingNo`)는 그대로 둔다 —
                    화면·엑셀 양식·일괄 업로드가 아직 쓴다. **1번 박스를 비추며**, 서버가
                    한 곳에서만 갱신하므로 둘이 갈릴 수 없다.""")
    @PutMapping("/work-orders/{id}/trackings")
    public ApiResponse<List<WorkOrderResponse.Tracking>> replaceTrackings(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) TrackingsUpdateRequest req) {
        List<WorkOrderResponse.Tracking> out = new java.util.ArrayList<>();
        for (var t : shipmentService.replaceTrackings(id, req)) {
            out.add(new WorkOrderResponse.Tracking(t.getBoxNo(), t.getCourierName(), t.getTrackingNo()));
        }
        return ApiResponse.success(out);
    }

    @Operation(summary = "작업요청서 출력 처리",
            description = """
                    작업지시를 출력한 것으로 표시한다 → 작업결과의 '출력' ○.

                    **재출력해도 최초 시각을 덮지 않는다** — 이 기록의 의미는
                    "언제 처음 작업지시가 나갔나"다. 이미 출력된 건이면 `false`를 돌려준다.""")
    @PostMapping("/work-orders/{id}/print")
    public ApiResponse<Boolean> markPrinted(@PathVariable Long id) {
        return ApiResponse.success(shipmentService.markPrinted(id));
    }

    @Operation(summary = "작업요청서 출력 되돌리기",
            description = """
                    잘못 출력한 건의 '출력' 표시를 내린다 → 다시 미출력분으로 돌아온다.

                    **사유는 선택이다**(9/27 회의 A-3 — 작업요청서에서 사유 칸 제거).
                    본문 자체를 생략해도 된다.

                    지운 값은 사라지지만 **누가·언제 내렸는지는 상태변경 이력에 남는다**
                    (`GET /audit/status-history`, 대상 SHIPMENT).
                    사유를 안 보내면 이력에 `(사유 미입력)`으로 적힌다 — 빈칸으로 두면
                    "안 적은 것"인지 "기록이 안 된 것"인지 나중에 가릴 수 없다.

                    애초에 출력 전이면 `false`.""")
    @PostMapping("/work-orders/{id}/print/revert")
    public ApiResponse<Boolean> revertPrinted(@PathVariable Long id,
                                              @Valid @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(shipmentService.revertPrinted(id, ReasonRequest.reasonOf(req)));
    }

    @Operation(summary = "작업요청서 삭제",
            description = """
                    발송 건을 **삭제**한다. 근거: 9/27 회의 항목 20 ② "'확인 되돌리기' → '삭제'".

                    ★**확인 표시를 내리는 것이 아니다.** 레거시 `작업요청서.vb:1181` 의 우클릭 '삭제'가
                    `sendData.isDelete = 2` 를 쓴다(주석: `1:발주처에서 삭제 / 2:물류에서 삭제`).
                    발송 건 자체를 없애는 동작이다. 확인만 내리려면 `/acknowledge/revert` 를 쓴다.

                    · **행은 지우지 않는다.** 목록에는 `deleted=true` 로 남고 화면이 취소선으로 표시한다
                      (레거시도 지우지 않고 회색 취소선으로 그린다). 숨기면 '취소된 건'과
                      '원래 없던 건'이 구분되지 않아 담당자가 같은 발송을 다시 만든다.
                    · 삭제된 건은 **출력·확인·발송정보 입력에서 400** — 레거시도 작업 루프에서 건너뛴다.
                    · **사유는 선택**(9/27 A-3). 없으면 이력에 `(사유 미입력)`으로 적힌다.
                    · 이미 삭제된 건이면 `false`.""")
    @DeleteMapping("/work-orders/{id}")
    public ApiResponse<Boolean> deleteWorkOrder(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(shipmentService.delete(id, ReasonRequest.reasonOf(req)));
    }

    @Operation(summary = "작업 확인 표시",
            description = """
                    작업을 확인한 것으로 표시한다 → 작업결과의 '확인' ○. **출력 다음 단계**다.

                    · 재확인해도 최초 시각·처리자를 덮지 않는다(출력과 같은 규칙).
                      이미 확인된 건이면 `false`를 돌려준다.
                    · ⚠️레거시 '완료'(`completed_at`)와는 **다른 축**이다. 그쪽은 레거시에도
                      쓰기 경로가 없어 늘 비어 있고, 거기에 확인을 얹으면 나중에 레거시 데이터를
                      대조할 때 완료인지 확인인지 가릴 수 없다.""")
    @PostMapping("/work-orders/{id}/acknowledge")
    public ApiResponse<Boolean> acknowledge(@PathVariable Long id) {
        return ApiResponse.success(shipmentService.acknowledge(id));
    }

    @Operation(summary = "작업 확인 되돌리기",
            description = "'확인' 표시를 내린다(화면의 **삭제** 버튼). "
                    + "사유는 **선택**이고(9/27 A-3) 이력에는 그대로 남는다.")
    @PostMapping("/work-orders/{id}/acknowledge/revert")
    public ApiResponse<Boolean> revertAcknowledged(@PathVariable Long id,
                                                   @Valid @RequestBody(required = false) ReasonRequest req) {
        return ApiResponse.success(shipmentService.revertAcknowledged(id, ReasonRequest.reasonOf(req)));
    }

    @Operation(summary = "발송정보 입력",
            description = """
                    박스 수·발송일·발송메모(레거시 작업요청서.vb:915와 같은 항목)에
                    **발송구분·수령인·송장**을 함께 기록한다(26p 확정 항목).

                    · **발송구분(택배/화물)**: 정본 26p 「작업 대기 리스트의 '상태변경' 옆에
                      '발송구분(택배/화물)' 필드 추가」. **기본값은 두지 않는다** —
                      "물류가 매번 수동 판단하는지 자동 기본값이 있는지"가 정본에서 미해결이라,
                      고르지 않은 건을 임의로 한쪽에 넣으면 발송된 것처럼 보인다.
                    · **수령인**: 정본 26p 「'택배' 선택 시 담당자 정보가 노출」.
                    · **송장번호·택배사**: 값 기록만 한다. 택배사 API는 부르지 않는다
                      (연동 여부는 물류팀 인터뷰 회신 대기).

                    **지정하지 않은 항목은 건드리지 않는다** — 박스 수만 고치려다
                    발송일이나 수령인이 지워지면 택배가 누구에게 가는지 알 수 없게 된다.

                    ★**발송일(`sentDate`)을 보내면 = 발송완료 처리**다(2026-10-23).
                    이 발송 건에 묶인 신청(`reqCds`)의 **DSRE 진행상태도 발송완료(D)** 로 옮기고,
                    DSRE2 발송처리 화면과 똑같이 **자재 재고 차감·출고내역·발송일**까지 처리한다.
                    · **발송준비중(W)인 신청만** 옮긴다. 다른 상태는 건너뛰고 `dsre[].message` 에 이유를 준다.
                    · 이미 발송완료인 신청은 발송일만 고친다.
                    · 한 신청이 다른 발송 건에도 걸려 있으면, 그 건들이 다 발송된 뒤에 옮긴다.
                    · DSRE 처리가 실패하면 **전체가 실패**한다(우리 발송일도 저장 안 됨).

                    응답 `dsre` = 신청별 결과. 묶인 신청이 없거나(수기 매출) 발송일을 안 보냈으면 빈 목록.""")
    @PutMapping("/work-orders/{id}/shipping")
    public ApiResponse<ShippingResult> updateShipping(@PathVariable Long id,
                                                      @Valid @RequestBody ShippingUpdateRequest req) {
        return ApiResponse.success(new ShippingResult(shipmentService.updateShipping(id, req)));
    }

    /** 발송정보 입력 결과. */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ShippingResult")
    public record ShippingResult(
            @io.swagger.v3.oas.annotations.media.Schema(description = "묶인 신청별 DSRE 진행상태 반영 결과")
            java.util.List<com.daesung.sales.logistics.dto.DsreStateSync> dsre) {
    }

    @Operation(summary = "작업요청서 발송취소(2026-10-12 A-3)",
            description = """
                    그 발송 건의 **발송일을 내린다**(= 발송완료를 되돌린다).

                    ★**왜 따로 있는가** — 10/08에 넣은 발송취소는 `POST /orders/state` 의
                    **D→W 전환**에 딸려 동작한다. 그런데 이 화면에서 직접 [발송완료]를 누른 건은
                    묶인 신청의 DSRE 상태가 D 가 아니라서 그 경로를 탈 수 없었다 —
                    **발송일을 내릴 방법이 아예 없었다**(실측 2026-10-12).

                    · **발송일만 내린다.** 박스 수·송장번호·수령인은 **그대로 둔다** —
                      물건을 싸 둔 사실은 취소되지 않고, 다시 보낼 때 그 값이 필요하다
                      (송장번호는 지우면 복원할 수 없다).
                    · 사유를 받아 상태변경 이력에 남긴다. 안 적으면 `(사유 미입력)`으로 적힌다.
                    · 이미 발송 전이면 `changed=false` — 오류가 아니다(이미 원하는 상태다).

                    ★**묶인 신청의 DSRE 진행상태도 되돌린다**(2026-10-23). 발송완료(D)인 신청만
                    발송준비중(W)으로 내리고, 발송완료 때 뺀 **자재 재고를 돌려놓는다**.
                    그 신청이 걸린 다른 발송 건의 발송일도 함께 내린다. 응답 `dsre` = 신청별 결과.""")
    @DeleteMapping("/work-orders/{id}/shipping")
    public ApiResponse<RevertResult> revertShipping(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) com.daesung.sales.logistics.dto.ReasonRequest req) {
        var r = shipmentService.revertSent(id,
                com.daesung.sales.logistics.dto.ReasonRequest.reasonOf(req));
        return ApiResponse.success(new RevertResult(r.changed(), r.dsre()));
    }

    /** 되돌리기 결과. {@code changed=false} 면 애초에 발송 전이었다는 뜻이다(오류가 아니다). */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ShippingRevertResult")
    public record RevertResult(
            @io.swagger.v3.oas.annotations.media.Schema(description = "실제로 발송일이 내려갔으면 true")
            boolean changed,
            @io.swagger.v3.oas.annotations.media.Schema(description = "묶인 신청별 DSRE 진행상태 반영 결과")
            java.util.List<com.daesung.sales.logistics.dto.DsreStateSync> dsre) {
    }

    @Operation(summary = "송장 등록용 양식 다운로드",
            description = """
                    택배사에 넘길 발송 목록을 xlsx로 내려준다. 택배사가 **송장번호 열만 채워
                    돌려주면** 그대로 업로드(`POST /work-orders/tracking/upload`)하면 된다.

                    맨 앞 **발송건ID**가 대조 키다 — 거래처·학교 이름으로 맞추면
                    같은 날 같은 학교로 두 건이 나갈 때 어느 쪽인지 가릴 수 없다.
                    담당자가 ID를 따로 채울 필요가 없도록 양식에 넣어 둔다.""")
    @GetMapping("/work-orders/tracking/template")
    public ResponseEntity<byte[]> trackingTemplate(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "발송구분. 보통 택배(COURIER)만 뽑는다")
            @RequestParam(required = false) DeliveryType deliveryType,
            @RequestParam(required = false) Long partnerId) {
        List<Col> cols = List.of(
                new Col("발송건ID", "id"), new Col("택배사", "courierName"), new Col("송장번호", "trackingNo"),
                new Col("거래일자", "tradeDate"), new Col("거래처", "partnerName"),
                new Col("학교/학원", "schoolName"), new Col("수령인", "receiverName"),
                new Col("연락처", "receiverPhone"), new Col("Box", "boxCount"),
                new Col("발송구분", "deliveryTypeName"));
        byte[] xlsx = excel.toXlsx("송장등록", cols,
                workService.workOrders(fromDate, toDate, null, partnerId, null, null, deliveryType));
        return excel.asDownload(xlsx, "송장등록양식_" + fromDate + "_" + toDate + ".xlsx");
    }

    @Operation(summary = "송장번호 엑셀 일괄 등록",
            description = """
                    택배사에서 송장번호가 채워져 돌아온 파일을 그대로 올린다.
                    양식 3컬럼(그 뒤 열은 무시): **발송건ID · 택배사 · 송장번호**.

                    · **한 행이 실패해도 나머지는 반영한다.** 택배사 파일에 우리가 모르는 행이
                      섞였다고 전체가 취소되면, 담당자는 어느 줄이 문제인지 모른 채 처음부터 다시 해야 한다.
                    · 행별 결과(UPDATED/ERROR·사유)를 돌려준다.
                    · ⚠️택배사 API를 부르지 않는다 — 연동 여부는 물류팀 인터뷰 회신 대기 항목이다.""")
    @PostMapping(value = "/work-orders/tracking/upload",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TrackingUploadResponse> uploadTracking(
            @Parameter(description = "송장번호가 채워진 xlsx", required = true)
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(shipmentService.uploadTracking(file));
    }

    @Operation(summary = "작업결과 조회",
            description = """
                    발송 건별 진행 현황. 상품군별 수량(교재·IC 등)·박스 수·발송일을 함께 본다.

                    · '출력'·'완료'는 **날짜가 채워졌는지**로 판단한다(레거시에 상태 컬럼이 없다).
                    · **출고창고**: 물류는 본사물류창고만 보이고, 관리자만 전체·본사물류·위탁을 고를 수 있다
                      (발주처 확정 3-2 나). 물류가 다른 값을 보내도 본사물류창고로 강제된다.
                    · **'확인'은 우리가 신설한 축**이다(출력 다음 단계).
                      `printed=true & acknowledged=false` 로 "지시는 나갔는데 아직 확인 안 된 건"을 추린다.
                    · ⚠️'완료'는 레거시에도 값을 넣는 코드가 없어 **항상 false**다.
                      임의로 만들면 화면 의미가 달라져, 발주처 확인 후 붙일 항목으로 남겨 두었다.
                      '확인'과 섞지 말 것 — 축이 다르다.""")
    @GetMapping("/work-results")
    public ApiResponse<com.daesung.sales.common.response.PageResponse<WorkResultRow>> workResults(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String tradeClass,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) Boolean printed,
            @Parameter(description = "확인여부 true=확인분/false=미확인분/미지정=전체")
            @RequestParam(required = false) Boolean acknowledged,
            @Parameter(description = "출고창고 구분 MAIN(본사물류창고)/CONSIGN(위탁창고). 미지정=전체(관리자만)")
            @RequestParam(required = false) WarehouseType warehouseType,
            @Parameter(description = """
                    진행상태 **다중선택** — PREPARING(상품준비중)/READY_TO_SHIP(발송준비중)/
                    SHIPPED(발송완료)/CANCELED(삭제). 미지정=삭제를 뺀 전체.
                    ‼️**삭제는 지정할 때만 나온다** — 평소 목록에 섞이면 처리할 수 없는 건을
                    담당자가 매번 걸러야 한다.
                    예) `statuses=SHIPPED,CANCELED`""")
            @RequestParam(required = false)
            List<com.daesung.sales.dsre.gateway.OrderState> statuses,
            @Parameter(description = "키워드 — 코드·명칭을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword,
            @org.springdoc.core.annotations.ParameterObject
            com.daesung.sales.common.dto.PageRequestDto pageReq) {
        // ‼️전량 반환은 서버를 죽인다 — 2012~2026 전 기간이 **13.1MB · 7.8초**였다
        //   (2026-10-20 실측, 배포 서버 힙 478MB). 작업요청서와 같은 이유다.
        //
        // ★여기서는 **만든 목록을 자른다**(DB 페이징이 아니다). workResults 가 출고창고 필터를
        //   조회 뒤에 거르기 때문이다 — DB에서 끊으면 걸러진 만큼 페이지가 비어 건수가 틀어진다.
        //   JSON 을 13MB 만들던 것이 메모리를 터뜨린 쪽이라, 자르는 것만으로 그 위험은 사라진다.
        //
        // ★목록 생성 자체는 그대로지만 **실측상 문제가 되지 않는다**(2026-10-20, 배포 DB 46,232건) —
        //   당월 0.04초 · 당해 0.70초 · 2년 2.93초 · 전기간(15년) 7.40초.
        //   15년치를 한 번에 부르는 조회는 실무에 없고, 그마저도 메모리는 터지지 않는다.
        //   DB 페이징으로 더 내리려면 출고창고 필터(조회 뒤 판정)를 상관 서브쿼리로 바꿔야 하는데,
        //   같은 모양을 판매채널에서 했다가 0.08초 → 45초가 된 적이 있어 여기서는 하지 않는다.
        List<WorkResultRow> all = Keywords.filter(
                workService.workResults(fromDate, toDate, tradeClass, partnerId, printed,
                        acknowledged, warehouseType, statuses),
                keyword,
                r -> new Object[]{r.partnerCode(), r.partnerName(), r.schoolCode(), r.schoolName(),
                        r.warehouseName()});
        var pageable = pageReq.toPageable();
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return ApiResponse.success(com.daesung.sales.common.response.PageResponse.of(
                new org.springframework.data.domain.PageImpl<>(
                        all.subList(from, to), pageable, all.size())));
    }

    @Operation(summary = "작업결과 엑셀 다운로드")
    @GetMapping("/work-results/export")
    public ResponseEntity<byte[]> workResultsExport(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String tradeClass,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) Boolean printed,
            @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(required = false) WarehouseType warehouseType,
            @Parameter(description = "진행상태 다중선택 — 미지정=삭제 제외 전체")
            @RequestParam(required = false)
            List<com.daesung.sales.dsre.gateway.OrderState> statuses) {
        List<Col> cols = List.of(
                new Col("분류", "tradeClass"), new Col("거래일자", "tradeDate"),
                new Col("거래순번", "tradeSeq"),
                new Col("거래처코드", "partnerCode"), new Col("거래처명", "partnerName"),
                new Col("학교코드", "schoolCode"), new Col("학교명", "schoolName"),
                new Col("출력", "printed"), new Col("확인", "acknowledged"),
                new Col("확인자", "acknowledgedBy"),
                // ★완료·삭제는 진행상태로 통합됐다(발주처 2026-10-23 #17). 진행상태를 앞에 둔다.
                new Col("진행상태", "statusLabel"),
                new Col("발송일", "sentDate"), new Col("출고창고", "warehouseName"),
                new Col("수량", "totalQty"),
                new Col("Box", "boxCount"), new Col("발송메모", "sendMemo"), new Col("비고", "memo"));
        byte[] xlsx = excel.toXlsx("작업결과", cols,
                workService.workResults(fromDate, toDate, tradeClass, partnerId, printed,
                        acknowledged, warehouseType, statuses));
        return excel.asDownload(xlsx, "작업결과.xlsx");
    }

}
