package com.daesung.sales.dealer.controller;

import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.dealer.dto.DealerOrderRequest;
import com.daesung.sales.dealer.service.DealerOrderService;
import com.daesung.sales.sale.dto.SalesEntryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 특약점 사이트 서버가 부르는 경로. 사람이 쓰는 화면이 아니다.
 *
 * <p>인증은 {@code X-Dealer-Site-Key} 헤더 하나다
 * ({@link com.daesung.sales.dealer.config.DealerSiteKeyFilter}) —
 * 10/26 명세 §10-3 "사이트 서버가 매출 API를 서버 간 호출"의 구현이다.
 */
@Tag(name = "특약점 사이트 연동", description = """
        특약점 사이트(order.dsdo.co.kr) **서버**가 호출하는 경로.
        `X-Dealer-Site-Key` 헤더가 필요하고, 없으면 401 이다.
        ‼️브라우저에서 직접 부르지 말 것 — 키가 노출되면 남의 거래처로 주문을 넣을 수 있다.""")
@RestController
@RequestMapping("/dealer-site")
@RequiredArgsConstructor
public class DealerOrderController {

    private final DealerOrderService dealerOrderService;

    @Operation(summary = "새 주문 접수(특약점 → 매출 주문)", description = """
            특약점 사이트 **[새 주문] 탭**(과목별·모의고사·교재)에서 들어온 주문을
            **매출 주문으로 저장**한다. 근거: 10/27 명세 §1
            "새 탭 주문은 매출 주문으로 저장" — DSRE2 `tbl_request_info` 에는 담지 않는다.

            **받는 것은 거래처·도서·수량뿐이다.**
            - 정가·공급률·금액을 받지 않는다 — 10/26 §1 "공급률은 신청 화면에 표시하지도,
              반영하지도 않는다. 매출 금액은 매출프로그램 기준으로 계산한다".
              단가는 서버가 거래처별 단가 → 도서 마스터 순으로 정한다.
            - 창고·발송구분도 받지 않는다 — §2-10 "특약점 사이트에서는 고르지 않는다".
              창고는 실물재고 물류창고, 발송구분은 화물이 기본이다.

            등록되는 것은 **매출등록과 똑같은 흐름**이다(재고 차감·작업요청서·거래순번·전표번호).
            다른 점은 진행상태가 **접수완료**에서 시작하는 것뿐이다 —
            10/26 §2-3 "A(접수완료)에서만 수정·삭제할 수 있다".

            응답은 매출등록과 같은 형식이다(`entryNo`·`tradeSeq`·줄별 매출번호·금액·경고).
            `entryNo` 로 주문 전체를 다시 조회·수정·삭제할 수 있다.""")
    @PostMapping("/orders")
    public ApiResponse<SalesEntryResponse> accept(@Valid @RequestBody DealerOrderRequest req) {
        return ApiResponse.success(dealerOrderService.accept(req));
    }
}
