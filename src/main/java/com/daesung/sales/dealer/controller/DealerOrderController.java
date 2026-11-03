package com.daesung.sales.dealer.controller;

import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.dealer.dto.DealerOrderRequest;
import com.daesung.sales.dealer.dto.DealerShippedOrderRow;
import com.daesung.sales.document.dto.PartnerDocumentDtos;
import com.daesung.sales.logistics.entity.DeliveryType;
import com.daesung.sales.sale.dto.TransactionStatementResponse;
import com.daesung.sales.dealer.service.DealerOrderService;
import com.daesung.sales.sale.dto.SalesEntryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final com.daesung.sales.dealer.service.DealerStatementService dealerStatementService;
    private final com.daesung.sales.dealer.service.DealerDocumentService dealerDocumentService;

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

    @Operation(summary = "★신청 화면 노출 도서 목록", description = """
            그 거래처에 **노출되는 도서만** 내려준다. 근거: 10/26 §3 + 특약점팀 전달
            2026-11-03 요청 1번.

            **노출 조건** — 넷을 모두 만족해야 한다.
            ① 도서 Web게시 = Y
            ② 거래처별 매핑(거래처 × 도서 **대분류**, [거래처별 단가·물류비용등록])의 Web게시 = Y
            ③ 그 매핑의 사용여부 = Y (삭제된 매핑은 없는 것으로 본다)
            ④ **매핑이 없으면 비노출** — 신규 거래처는 매핑을 깔기 전까지 아무것도 안 보인다

            · 대분류는 도서의 **세부구분**을 거쳐 정해진다(세부구분 → 대분류). 세부구분이
              비었거나 마스터에 없는 도서는 대분류를 알 수 없어 ④에 걸려 **비노출**이다.
            · **캐시가 없다** — 도서관리에서 바꾸면 다음 호출에 바로 반영된다(§3).
            · 공급률·정가·Web게시 값은 **담지 않는다** — §1 "매출 금액은 매출프로그램 기준으로
              계산한다", §3 "Web게시여부 값은 사이트에 보여주지 않는다".
            · `POST /dealer-site/orders`·`PUT …/{orderNo}` 도 **같은 판정**을 쓴다 —
              노출되지 않는 도서를 담아 보내면 `PRODUCT_NOT_VISIBLE` 로 거부한다.

            ⚠️**아직 반영하지 않은 조건이 하나 있습니다** — 전달 주신 ②"거래처 × **도서**
            예외(열기/닫기)"는 **도서 단위** 축인데, 우리 쪽은 그 표를 V42에서 의도적으로
            없애고 **거래처 × 대분류** 한 축으로 통일했습니다(정본 34p 기준. 두 축을 같이 두면
            "무엇이 먼저냐" 규칙이 또 생겨, 같은 도서가 화면마다 달라지는 레거시 문제가
            돌아옵니다). 도서 단위 예외가 실제로 필요하면 알려 주세요 — 축을 되살리는
            작업이라 확인 후 진행하겠습니다.""")
    @GetMapping("/products")
    public ApiResponse<java.util.List<com.daesung.sales.dealer.dto.DealerProductRow>> products(
            @Parameter(description = "주문 거래처 코드(DSRE 매출코드)", example = "A10A195")
            @RequestParam String partnerCode) {
        return ApiResponse.success(dealerOrderService.visibleProducts(partnerCode));
    }

    @Operation(summary = "담당자 정보 미리채우기용 조회", description = """
            신청 화면이 담당자 3칸을 **미리 채울** 때 쓴다. 근거: 10/26 §4 "신청 화면에
            들어가면 거래처관리에 저장된 값을 미리 채운다" + 특약점팀 전달 2026-11-03 요청 3번.

            · 출처는 **접수 때 비워 보내면 채우는 값과 같다**(거래처관리 대표자명·연락처1·
              이메일1) — 화면에 보인 값과 저장되는 값이 어긋나지 않게 한 곳에서 가져온다.
            · 값이 없으면 **빈 값**(`null`)으로 준다.
            · 사용자가 고친 값은 **그 주문에만** 저장된다 — 거래처관리 원본은 UPDATE하지 않는다(§4).""")
    @GetMapping("/contact")
    public ApiResponse<com.daesung.sales.dealer.dto.DealerContactResponse> contact(
            @Parameter(description = "주문 거래처 코드(DSRE 매출코드)", example = "A10A195")
            @RequestParam String partnerCode) {
        return ApiResponse.success(dealerOrderService.contact(partnerCode));
    }

    @Operation(summary = "★내 주문 목록(조회·수정·삭제 가능 여부 포함)", description = """
            그 거래처가 넣은 주문 목록. 10/26 §2-1 "'신청완료'를 '접수완료'로" —
            진행상태 라벨은 이미 정본 6단계 명칭 그대로다(`progressStateName`).

            · 접수완료(`progressState=RECEIVED`)인 주문만 **수정·삭제**가 됩니다(§2-3).
              G~D 는 이 목록으로 **조회만** 됩니다 — 사이트 쪽에서 그 상태면 수정·삭제
              버튼을 잠그고, 눌렀을 때는 아래 두 API 가 돌려주는 문구를 그대로 보여주면
              §2-4 "주문 건의 수정 및 취소를 원하시는 경우 담당자에게 확인 바랍니다"가 된다.
            · 응답 모양은 백오피스 주문조회(`GET /orders`)와 같은 `SalesOrderRow` 다.""")
    @GetMapping("/orders")
    public ApiResponse<com.daesung.sales.common.response.PageResponse<
            com.daesung.sales.order.dto.SalesOrderRow>> myOrders(
            @Parameter(description = "주문 거래처 코드", example = "A10A195")
            @RequestParam String partnerCode,
            @Parameter(description = "주문일 시작. 미지정 시 올해 1월 1일")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "주문일 종료. 미지정 시 오늘")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "진행상태(미지정=전체)")
            @RequestParam(required = false) com.daesung.sales.dsre.gateway.OrderState state,
            @Parameter(description = "페이지(0부터)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기(최대 200)") @RequestParam(defaultValue = "50") int size) {
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();
        return ApiResponse.success(com.daesung.sales.common.response.PageResponse.of(
                dealerOrderService.list(partnerCode, from, to, state, page, size)));
    }

    @Operation(summary = "★내 주문 수정 — 접수완료일 때만", description = """
            근거: 10/26 §2-3 "**A(접수완료)에서만** 수정·삭제할 수 있다. G~C는 조회만 된다."

            · 본문은 `POST /orders` 와 **같다**. 줄을 통째로 바꾼다(등록번호·거래순번 유지).
            · 접수완료가 아니거나 **본인 거래처 주문이 아니면 같은 문구**로 거부한다 —
              "주문 건의 수정 및 취소를 원하시는 경우 담당자에게 확인 바랍니다"(§2-4).
              남의 거래처 주문인지 여부를 알려주지 않는다(존재 자체를 숨긴다).""")
    @org.springframework.web.bind.annotation.PutMapping("/orders/{orderNo}")
    public ApiResponse<SalesEntryResponse> edit(
            @Parameter(description = "주문번호", example = "E-20261102-7")
            @PathVariable String orderNo,
            @Valid @RequestBody DealerOrderRequest req) {
        return ApiResponse.success(dealerOrderService.edit(orderNo, req));
    }

    @Operation(summary = "★내 주문 삭제 — 접수완료일 때만", description = """
            근거: 10/26 §2-2 "'취소' 버튼 — 화면 예시의 '취소'는 실제로 **삭제** 동작이다"·
            §2-3·§2-4(위 수정과 같은 규칙).

            줄 전부와, 발송 전이면 묶인 작업요청서까지 함께 지운다(백오피스 삭제와 같은 로직).
            논리삭제라 이력에 남는다.""")
    @org.springframework.web.bind.annotation.DeleteMapping("/orders/{orderNo}")
    public ApiResponse<Integer> delete(
            @Parameter(description = "주문번호", example = "E-20261102-7")
            @PathVariable String orderNo,
            @Parameter(description = "주문 거래처 코드", example = "A10A195")
            @RequestParam String partnerCode) {
        return ApiResponse.success(dealerOrderService.delete(orderNo, partnerCode));
    }

    @Operation(summary = "발송완료 주문 목록(송장 조회·거래명세서 공통)", description = """
            그 거래처의 **발송완료** 주문을 내린다. 송장 메뉴(10/26 §4)와 거래명세서 메뉴(§5)가
            **같은 목록**을 본다 — 둘 다 "내 발송완료 주문"이다.

            · §4 표시 조건이 **택배 AND 발송완료**이므로, 송장 메뉴는 `deliveryType=COURIER` 로
              걸러 부른다. 화물 건은 `trackings` 가 빈 목록이다.
            · §5 표시 대상이 **발송완료부터**다 — "발송준비중까지는 내부에서 수정될 수 있으므로
              표시하지 않는다".
            · 송장번호를 눌러 택배사 조회 페이지를 새 창으로 여는 것은 **사이트 몫**이다
              (§4 "택배사 API는 연동하지 않는다").
            · 송장 입력은 매출 [작업요청서 > 송장번호 입력]에서만 한다 —
              §4 "**사이트에는 업로드 기능을 두지 않는다**".

            한 번에 최대 500건이다. 그 위는 기간을 좁혀야 한다.""")
    @GetMapping("/orders/shipped")
    public ApiResponse<List<DealerShippedOrderRow>> shippedOrders(
            @Parameter(description = "주문 거래처 코드", example = "A10A195")
            @RequestParam String partnerCode,
            @Parameter(description = "주문일 시작. 미지정 시 올해 1월 1일")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "주문일 종료. 미지정 시 오늘")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "배송구분 필터. 송장 메뉴는 COURIER 로 부른다")
            @RequestParam(required = false) DeliveryType deliveryType) {
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();
        return ApiResponse.success(
                dealerStatementService.shippedOrders(partnerCode, from, to, deliveryType));
    }

    @Operation(summary = "거래명세서(조회·출력 전용)", description = """
            발송완료된 주문 하나의 거래명세서를 내린다. **양식은 매출프로그램 거래명세서와
            같다** — §5 "기존 양식과 출력 로직을 재사용".

            · **발송완료가 아니면 거부**한다(§5 "발송준비중까지는 표시하지 않는다").
            · **본인 거래처 주문이 아니면 "없음"** 으로 답한다 — 존재 자체를 숨긴다
              (§5 "URL이나 파라미터로 받은 거래처코드는 믿지 않는다 / Phase 0 order IDOR과 같은 유형").
            · 명세서는 (거래처 × **학교**) 단위로 발행된다. 한 주문에 학교가 섞여 있으면
              특정할 수 없어 거부한다 — 섞은 채로 뽑으면 「학교(원)」 칸에 첫 학교만 찍히고
              나머지가 그 학교 몫처럼 보인다.""")
    @GetMapping("/orders/{orderNo}/statement")
    public ApiResponse<TransactionStatementResponse> statement(
            @Parameter(description = "주문번호", example = "E-20261102-7")
            @PathVariable String orderNo,
            @Parameter(description = "주문 거래처 코드", example = "A10A195")
            @RequestParam String partnerCode) {
        return ApiResponse.success(dealerStatementService.statement(partnerCode, orderNo));
    }

    @Operation(summary = "내 PDF 보관함 목록(최근 12개월)", description = """
            그 거래처의 매출(SALES)·성적(RESULT) PDF 목록을 **기준월** 순으로 내린다.

            근거: 10/26 §7 — "보관·열람: 사이트에서 **월을 골라** 지난달 PDF를 볼 수 있다.
            범위는 **최근 12개월**이다. 이보다 오래된 파일은 지우지 않는다."
            §10-3 — "사이트 서버가 매출 API를 서버 간 호출 … 대상은 노출 도서, 거래명세서,
            송장, **PDF**다."

            · 기본 범위가 **최근 12개월**이다. `fromMonth`·`toMonth`(yyyy-MM)로 좁힐 수 있다.
            · 오래된 파일은 지우지 않으므로, 범위를 넓게 주면 그 이전 것도 나온다.
            · 내용(PDF 바이트)은 목록에 담지 않는다 — 수백 개를 함께 읽으면 앱이 넘어간다.
              파일은 아래 다운로드로 받는다.

            ‼️**DSRE 로 올린 기존 PDF 는 이 목록에 없다.** 그 파일들은 사이트 서버의 공개 폴더
            (`webapps/DOC/PDF`)에 있고 우리에게 바이트가 없어, 목록에 끼우면 다운로드가 깨진다.
            §7 "기존 PDF도 계속 보이게 한다"는 **사이트가 자기 폴더 파일을 함께 보여주는 것**으로
            충족된다(§7 "같은 표를 쓸지 이관할지는 개발에서 판단한다" — 이관하지 않기로 판단했다).""")
    @GetMapping("/documents")
    public ApiResponse<List<PartnerDocumentDtos.Row>> documents(
            @Parameter(description = "거래처 코드", example = "A10A195")
            @RequestParam String partnerCode,
            @Parameter(description = "종류 SALES/RESULT. 미지정=전체") @RequestParam(required = false)
            String docType,
            @Parameter(description = "기준월 시작(yyyy-MM). 미지정 시 11개월 전")
            @RequestParam(required = false) String fromMonth,
            @Parameter(description = "기준월 종료(yyyy-MM). 미지정 시 이번 달")
            @RequestParam(required = false) String toMonth) {
        return ApiResponse.success(
                dealerDocumentService.documents(partnerCode, docType, fromMonth, toMonth));
    }

    @Operation(summary = "내 PDF 다운로드(인증)", description = """
            근거: 10/26 §7 '열람 권한' — "서버가 **로그인 거래처 본인 파일인지 확인한 뒤**
            내려준다. 현행 공개 폴더 직접 링크 방식은 **파일명만 알면 남의 PDF 가 열릴 수 있으니**,
            확인 후 인증 다운로드로 바꾼다."

            · 본인 파일이 아니면 **"없음"과 같은 답**이다 — "있지만 당신 것이 아니다"는 그 자체로
              남의 파일 존재를 알려준다(Phase 0 order IDOR 과 같은 유형).
            · 거래처는 **파일 id 가 아니라 `partnerCode` 로** 판정한다. id 만 믿으면 번호를 바꿔
              남의 PDF 를 받는다.""")
    @GetMapping("/documents/{id}")
    public ResponseEntity<byte[]> downloadDocument(
            @Parameter(description = "문서 id") @PathVariable Long id,
            @Parameter(description = "거래처 코드", example = "A10A195")
            @RequestParam String partnerCode) {
        return dealerDocumentService.download(partnerCode, id);
    }
}
