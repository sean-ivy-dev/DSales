package com.daesung.sales.order.controller;

import com.daesung.sales.common.excel.ExcelExportUtil.Col;
import com.daesung.sales.common.excel.ExcelExportUtil;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.order.dto.SalesOrderRow;
import com.daesung.sales.order.service.SalesOrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
            @Parameter(description = "페이지(0부터)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기(최대 500)") @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(PageResponse.of(
                salesOrderQueryService.orders(from(fromDate), to(toDate), partnerId, partnerCode,
                        keyword, state, PageRequest.of(Math.max(0, page),
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
            @RequestParam(required = false) OrderState state) {
        List<Col> cols = List.of(
                new Col("주문번호", "orderNo"), new Col("거래순번", "tradeSeq"),
                new Col("주문일자", "orderDate"),
                new Col("거래처코드", "partnerCode"), new Col("거래처명", "partnerName"),
                new Col("학교/학원코드", "schoolCode"), new Col("학교/학원명", "schoolName"),
                new Col("분류", "tradeClass"),
                new Col("진행상태", "progressStateName"),
                new Col("발송구분", "deliveryTypeName"),
                new Col("총수량", "totalQty"), new Col("총금액", "totalAmount"));
        // ★엑셀은 한 번에 다 필요하다 — 상한(500)까지 받는다. 그 위는 기간을 좁혀야 한다
        //   (전량을 열면 배포 서버 힙이 터진다 — 작업요청서에서 실제로 겪었다).
        var rows = salesOrderQueryService.orders(from(fromDate), to(toDate), partnerId, partnerCode,
                keyword, state, PageRequest.of(0, 500)).getContent();
        return excel.asDownload(excel.toXlsx("주문조회", cols, rows), "주문조회.xlsx");
    }

    private static LocalDate from(LocalDate v) {
        return (v != null) ? v : LocalDate.now().withDayOfYear(1);
    }

    private static LocalDate to(LocalDate v) {
        return (v != null) ? v : LocalDate.now();
    }
}
