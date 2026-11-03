package com.daesung.sales.order.controller;

import com.daesung.sales.common.excel.ExcelExportUtil.Col;
import com.daesung.sales.common.excel.ExcelExportUtil;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.order.dto.OrderProgressChangeRequest;
import com.daesung.sales.order.dto.SalesOrderRow;
import com.daesung.sales.order.service.SalesOrderQueryService;
import com.daesung.sales.order.service.SalesOrderStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 조회(통합) — <b>우리 주문</b>.
 *
 * <p>★<b>{@link OrderController} 와 갈라 둔 이유</b>: 그쪽은
 * {@code @ConditionalOnProperty(daesung.dsre.enabled)} 뒤에 있다(DSRE2 를 읽는 경로들이라
 * 그게 맞다). 그런데 주문조회는 10/27 명세 §1 로 <b>DSRE 와 무관</b>해졌다 —
 * 같은 컨트롤러에 두면 DSRE 를 끈 환경에서 주문조회가 통째로 사라진다.
 */
@Tag(name = "주문 조회(통합)", description = """
        **새 특약점 주문 + 매출에서 등록한 주문**을 본다(10/27 명세 §1·§2-1·§2-2).
        옛 DSRE 신청 목록은 `GET /orders/dsre` 에 남아 있다(§2-3 "숨김, 삭제하지 않음").""")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class SalesOrderController {

    private final SalesOrderQueryService salesOrderQueryService;
    private final ExcelExportUtil excel;
    private final SalesOrderStateService salesOrderStateService;

    @Operation(summary = "★주문 조회(통합) — 우리 주문", description = """
            **새 특약점 주문 + 매출에서 등록한 주문**을 조회한다.

            근거: 10/27 명세 §1 — "조회 대상 … 지금은 DSRE 주문(신청번호·지사코드)을 읽어 온다.
            이를 **새 특약점 주문 + 매출에서 수기 등록한 주문**으로 바꾼다" ·
            §2-1 "수기 주문과 온라인(특약점) 주문을 **함께** 보여준다" ·
            §2-2 "등록한 주문이 [주문 조회]에 나오지 않는다(버그) — **등록 즉시** 보여야 한다".

            ### 바뀐 점
            예전 이 목록은 DSRE2 신청(`tbl_request_info`)을 읽었다. 그래서 매출등록·특약점
            주문이 들어와도 **한 줄도 안 늘었다**(§2-2 버그의 정체). 옛 DSRE 목록은
            `GET /orders/dsre` 로 남겨 뒀다.

            ### 한 줄 = 한 주문
            주문이 학교·분류 때문에 발송 여러 건으로 갈려도 **한 줄**이다(§3-6 "거래순번 …
            한 주문이 발송 여러 건으로 갈려도 번호는 하나"). 갈린 발송 id 는 `shipmentIds` 다.
            · 진행상태는 **가장 덜 진행된** 발송의 것이다 — 세 상자 중 둘만 나갔으면
              아직 발송완료가 아니다.
            · 학교·분류·발송구분이 주문 안에서 섞이면 **비워서** 준다(아무거나 고르면
              담당자가 그 값으로 주문을 특정했다고 믿는다). 줄을 보면 된다.
            · 삭제된 주문도 **목록에 남긴다** — 레거시처럼 표시만 한다.

            기간을 생략하면 **올해 1/1~오늘**이다(다른 조회와 같은 규칙).""")
    @GetMapping
    public ApiResponse<PageResponse<SalesOrderRow>> orders(
            @Parameter(description = "주문(거래)일 시작. 미지정 시 올해 1월 1일")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "주문(거래)일 종료. 미지정 시 오늘")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "거래처 id(미지정=전체)") @RequestParam(required = false) Long partnerId,
            @Parameter(description = "거래처코드(미지정=전체). `partnerId` 와 함께 주면 교집합")
            @RequestParam(required = false) String partnerCode,
            @Parameter(description = "키워드 — 거래처명·학교명·주문번호를 함께 훑는다")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "진행상태(미지정=전체)") @RequestParam(required = false) OrderState state,
            @Parameter(description = """
                    접수경로(미지정=전체) — `ONLINE`(특약점 사이트) / `MANUAL`(수기).
                    ‼️`MANUAL` 은 **값이 비어 있는 옛 주문까지** 포함한다(그 전 주문은 전부 수기).""")
            @RequestParam(required = false) com.daesung.sales.sale.entity.OrderChannel channel,
            @Parameter(description = "페이지(0부터)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기(최대 500)") @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(PageResponse.of(
                salesOrderQueryService.orders(from(fromDate), to(toDate), partnerId, partnerCode,
                        keyword, state, channel, PageRequest.of(Math.max(0, page),
                                Math.min(Math.max(1, size), 500)))));
    }

    @Operation(summary = "주문 조회(통합) 엑셀", description = """
            화면 목록과 **같은 조건·같은 줄**을 내린다.
            ‼️옛 DSRE 신청 목록의 엑셀은 `GET /orders/dsre/export` 다.""")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String partnerCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OrderState state,
            @RequestParam(required = false) com.daesung.sales.sale.entity.OrderChannel channel) {
        List<Col> cols = List.of(
                new Col("주문번호", "orderNo"), new Col("거래순번", "tradeSeq"),
                new Col("주문일자", "orderDate"),
                new Col("거래처코드", "partnerCode"), new Col("거래처명", "partnerName"),
                new Col("학교/학원코드", "schoolCode"), new Col("학교/학원명", "schoolName"),
                new Col("분류", "tradeClass"),
                new Col("진행상태", "progressStateName"),
                new Col("접수경로", "channelName"),
                new Col("발송구분", "deliveryTypeName"),
                new Col("총수량", "totalQty"), new Col("총금액", "totalAmount"));
        // ★엑셀은 한 번에 다 필요하다 — 상한(500)까지 받는다. 그 위는 기간을 좁혀야 한다
        //   (전량을 열면 배포 서버 힙이 터진다 — 작업요청서에서 실제로 겪었다).
        var rows = salesOrderQueryService.orders(from(fromDate), to(toDate), partnerId, partnerCode,
                keyword, state, channel, PageRequest.of(0, 500)).getContent();
        return excel.asDownload(excel.toXlsx("주문조회", cols, rows), "주문조회.xlsx");
    }

    @Operation(summary = "★선택 건 상태변경(접수완료·상품검수·상품준비중)", description = """
            주문조회에서 체크한 주문들의 **접수 단계**를 한 번에 바꾼다.

            근거: 10/27 명세 §2-8 — "선택지를 **접수완료 / 상품검수 / 상품준비중**만 남긴다.
            발송준비중·발송완료는 물류 영역이므로 **[작업요청서]에서만** 처리한다."

            ### 전이 축이 둘이다
            | 축 | 전이 | 어디서 |
            |---|---|---|
            | 접수 | 접수완료 ↔ 상품검수 ↔ 상품준비중 | **이 API** |
            | 물류 | 상품준비중 → 발송준비중 → 발송완료 | 작업요청서(`POST /orders/state`) |

            섞으면 접수완료가 곧바로 발송완료가 되는 주문이 생긴다.
            · 세 상태 **사이는 자유롭게** 오간다 — 발주처가 상품검수를 "사용빈도는 거의
              없으나 일단 포함"이라 했으니 접수완료→상품준비중 바로 가기가 평상시 흐름이다.
            · `READY_TO_SHIP`·`SHIPPED`·`CANCELED` 를 목표로 주면 **거부**한다(400).
            · ‼️**이미 출력·발송된 주문은 건별로 거부**한다. 적어도 화면이 안 바뀌기 때문이다
              (진행상태는 출력·발송을 먼저 본다) — 조용한 무동작은 담당자가 바뀐 줄 알게 만든다.

            ### 실패 처리
            **한 건이 안 된다고 전체를 실패시키지 않는다.** 건별 `changed` 와 사유(`message`)를
            준다 — `skipped` 가 0이 아니면 확인할 것.

            ### 이력
            바꾼 건은 상태변경 이력(`status_history`)에 **발송 건마다** 남는다.
            주문이 발송 여럿으로 갈렸을 때 어느 것이 바뀌었는지 따라갈 수 있어야 한다.""")
    @PostMapping("/progress-state")
    public ApiResponse<SalesOrderStateService.BulkResult> changeProgressState(
            @Valid @RequestBody OrderProgressChangeRequest req) {
        return ApiResponse.success(
                salesOrderStateService.change(req.orderNos(), req.toState(), req.reason()));
    }

    @Operation(summary = "★주문 삭제 — 접수완료 상태일 때만", description = """
            근거: 10/27 명세 §2-9 — "주문 삭제 … **접수완료 상태일 때만** 가능하게 한다."

            ‼️**`DELETE /sales/entries/{entryNo}` 와 다른 경로다.** 그건 매출등록 화면의
            "거래순번으로 불러와 지우기"(§3-6)가 쓰는 경로이고, 그 화면의 주문은 접수 단계를
            건너뛰고 **상품준비중에서 시작**한다(수기 등록 설계) — 거기에 이 제한을 걸면
            매출등록의 기존 삭제가 깨진다. §2-9(이 화면)와 §3-6(매출등록 화면)은 정본에서
            서로 다른 섹션의 서로 다른 화면 기능이라 경로를 갈랐다.

            · 현재 상태가 **접수완료가 아니면 거부**한다(상품검수·상품준비중·발송준비중·
              발송완료·삭제 전부). 상품검수·상품준비중으로 되돌리려면 먼저
              `POST /orders/progress-state` 로 접수완료로 돌린 뒤 지운다.
            · 등록번호 없는 옛 주문(`#발송id`)은 지울 수 없다 — 반품 또는 매출 취소로 처리한다.
            · 실제 삭제는 매출등록과 같은 로직(`SaleService.deleteEntry`)을 그대로 쓴다 —
              재고 역분개·이력·빈 작업요청서 정리가 똑같이 적용된다.

            ‼️경로가 **`entries`를 거친다**(`/orders/entries/{orderNo}`, `/orders/{orderNo}`가
            아니다) — `DELETE /orders/{reqCd}`(DSRE 모의고사 신청, OrderController)와 베이스가
            같은 한 세그먼트라 **겹쳐서 라이브에서 실제로 500(Ambiguous handler methods)이
            났다**(2026-11-03 전수 호출로 발견). reqCd에 숫자 제약(`:[0-9]+`)을 걸어도
            Spring의 PathPattern 특이성 비교는 "제약 있는 변수 1개 세그먼트"와 "제약 없는
            변수 1개 세그먼트"를 같게 보고 여전히 터진다(`OrderPathCollisionTest`로 직접
            재현·확인) — 그래서 변수 제약이 아니라 **리터럴 세그먼트로 패턴 자체를 다르게**
            만들어 갈랐다.""")
    @org.springframework.web.bind.annotation.DeleteMapping("/entries/{orderNo}")
    public ApiResponse<Integer> delete(
            @Parameter(description = "주문번호(`E-…`)", example = "E-20261027-55")
            @org.springframework.web.bind.annotation.PathVariable String orderNo,
            @Valid @RequestBody(required = false)
            com.daesung.sales.logistics.dto.ReasonRequest req) {
        return ApiResponse.success(salesOrderStateService.delete(orderNo,
                com.daesung.sales.logistics.dto.ReasonRequest.reasonOf(req)));
    }

    private static LocalDate from(LocalDate v) {
        return (v != null) ? v : LocalDate.now().withDayOfYear(1);
    }

    private static LocalDate to(LocalDate v) {
        return (v != null) ? v : LocalDate.now();
    }
}
