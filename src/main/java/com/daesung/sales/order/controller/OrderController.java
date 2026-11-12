package com.daesung.sales.order.controller;

import com.daesung.sales.common.excel.ExcelExportUtil;
import com.daesung.sales.common.excel.ExcelExportUtil.Col;
import com.daesung.sales.dsre.gateway.BranchRow;
import com.daesung.sales.dsre.gateway.BranchSchoolRow;
import com.daesung.sales.dsre.gateway.OrderDetailRow;
import com.daesung.sales.dsre.gateway.ExamRow;
import com.daesung.sales.dsre.gateway.SubjectRow;
import com.daesung.sales.order.dto.OrderCreateRequest;
import com.daesung.sales.order.dto.OrderCreateResponse;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.dsre.gateway.DsreGateway;
import com.daesung.sales.dsre.gateway.DsreOrderRow;
import com.daesung.sales.dsre.gateway.LogisMode;
import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.order.dto.OrderStateChangeRequest;
import com.daesung.sales.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 조회 · 진행상태. 실제 경로: /api/v1/orders.
 *
 * <p><b>상태 원본은 DSRE2 {@code tbl_request_info.STATE}다.</b> 우리는 복사본을 두지 않고 그 값을
 * 직접 읽고 쓴다 — 그래서 동기화 로직이 없고, 우리가 바꾸면 DSRE2 데스크톱에 즉시 보인다.
 *
 * <p>발주처 확정(2026-08-11) "DSRE는 그대로 사용"에 따라 전이는 대부분 DSRE2 데스크톱 소관이다.
 * <b>우리 몫은 물류 담당자가 하는 뒤쪽 세 전이</b>다(자료요청서 3-2(가).진행상태) —
 * 거래명세서 출력(S→W, 자동) · <b>되돌리기(W→S, 수동)</b> · <b>발송완료(W→D, 다건 일괄)</b> ·
 * <b>발송취소(D→W, 9/27 회의)</b>.
 * 명세서 출력이 원래부터 매출프로그램 기능이었고(레거시 리포트 21종, DSRE2엔 명세서 화면 없음),
 * 되돌리기·발송처리도 같은 물류 담당자가 같은 화면에서 하는 일이라 함께 둔다.
 * 앞쪽(접수·검수·취소)은 order 사이트와 DSRE2 소관이라 건드리지 않는다.
 *
 * <p>DSRE 연동이 꺼져 있으면(daesung.dsre.enabled=false) 이 화면 자체가 뜨지 않는다.
 */
@Tag(name = "주문 · 진행상태",
        description = "DSRE2 주문·진행상태 조회 + 물류 담당자 몫 전이(명세서 발급·되돌리기·발송완료). "
                + "접수·검수·취소는 order 사이트와 DSRE2 소관.")
@RestController
@RequestMapping("/orders")
@ConditionalOnProperty(name = "daesung.dsre.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OrderController {

    private final DsreGateway dsreGateway;
    private final OrderService orderService;
    private final ExcelExportUtil excel;

    @Operation(summary = "지사 목록",
            description = """
                    주문 등록에서 고를 **지사**. 원천은 DSRE2 거래처 정보다.

                    ★**우리 거래처 마스터로 대신할 수 없습니다.** 우리 `partners.code` 는
                    **매출코드**(예: `A26D153`)이고, 주문이 요구하는 것은 DSRE2 **지사코드**
                    (예: `70501`)입니다. 같은 거래처를 가리키지만 값이 다릅니다 —
                    더프 매핑에서도 같은 혼동이 있었습니다.
                    그래서 `machulCode` 를 함께 내려, 우리 마스터와 이어 볼 수 있게 했습니다.

                    · 예전엔 이 경로가 없어 화면이 주문 목록을 전부 훑어 지사를 모았습니다(수십 초).

                    ★**만료 지사**는 `expired` 로 내려갑니다(DSRE `END_GUBUN='Y'`).
                    ‼️**지사명 앞의 `[만료]` 표시로 가리지 마세요** — 기준이 아닙니다.
                    특약점팀 실측(2026-11-12): Y 인데 표시 없는 곳 10곳 ·
                    표시는 있는데 N 인 곳 1곳(99001)이라 11곳이 틀리게 나옵니다.
                    목록에서 아예 빼려면 `excludeExpired=true` 를 쓰세요.""")
    @GetMapping("/branches")
    public ApiResponse<List<BranchRow>> branches(
            @Parameter(description = "지사명·풀네임·매출코드·지사코드 부분일치. 미지정=전체")
            @RequestParam(required = false) String keyword,
            @Parameter(description = """
                    만료 지사(`END_GUBUN='Y'`)를 **빼고** 줍니다. 기본 false(전부 줍니다).
                    매출일괄등록 특약점 드롭다운처럼 "고르는 목록"에서 씁니다
                    (발주처 2026-11-12 요청 3번 "만료된 특약점은 목록에 나오지 않게").""")
            @RequestParam(defaultValue = "false") boolean excludeExpired) {
        List<BranchRow> rows = dsreGateway.listBranches(keyword);
        if (excludeExpired) {
            rows = rows.stream().filter(r -> !r.expired()).toList();
        }
        return ApiResponse.success(rows);
    }

    @Operation(summary = "지사별 학교/학원 목록",
            description = """
                    그 지사가 담당하는 학교·학원만. 주문 등록의 학교코드(MGR_CD) 후보입니다.
                    전국 학교 2,400여 곳을 다 보여주면 담당자가 고를 수 없습니다.""")
    @GetMapping("/branches/{custCode}/schools")
    public ApiResponse<List<BranchSchoolRow>> branchSchools(
            @Parameter(description = "지사코드(CUST_CD)", required = true) @PathVariable String custCode) {
        return ApiResponse.success(dsreGateway.listBranchSchools(custCode));
    }

    @Operation(summary = "주문 상세(반 · 과목수량)",
            description = """
                    주문 하나의 **반과 과목 수량**. 등록은 3단인데 조회가 마스터만 있어
                    화면이 상세 칸을 비워 두고 있었습니다.

                    · 간편신청 반은 인문·자연·통합 인원이 차고 `subjects` 가 비어 있습니다.
                    · 반별 `totalQty` 는 **목록의 총수량과 같은 방식**으로 셉니다
                      (두 화면 숫자가 갈리면 아무도 안 믿습니다).""")
    @GetMapping("/{reqCd}/detail")
    public ApiResponse<List<OrderDetailRow>> detail(
            @Parameter(description = "신청번호(REQ_CD)", required = true) @PathVariable int reqCd) {
        return ApiResponse.success(dsreGateway.findOrderDetail(reqCd));
    }

    @Operation(summary = "주문 삭제(접수완료 건만)",
            description = """
                    잘못 넣은 주문을 지운다. 발주처 확정(2026-08-14 [4]) —
                    "주문(접수완료) — **상품검수로 넘어가기 전까지** 영업·관리자가 삭제 가능".

                    · **접수완료(A)에서만** 됩니다. 상품검수 이후는 물류가 이미 움직인 건이라
                      그때 지우면 작업지시와 실물이 어긋납니다 → 400.
                    · ⚠️**물리삭제가 아닙니다.** DSRE2가 그렇게 정의합니다 —
                      `STATE = 'C' : 삭제`. 행을 지우면 반·과목 수량까지 사라져
                      "무엇이 있었는지"가 남지 않고, order 사이트·DSRE2 데스크톱이
                      같은 행을 보고 있어 우리가 지울 자리가 아닙니다.
                    · 조회와 변경 사이에 DSRE2 쪽이 먼저 옮겼으면 `changed=false`로 돌려줍니다
                      (성공으로 보고하면 담당자가 속습니다).
                    · 누가·언제·왜 지웠는지는 상태변경 이력에 남습니다.

                    ‼️이 경로가 한때 `DELETE /orders/{orderNo}`(주문조회, SalesOrderController)와
                    겹쳐서 **둘 다 라이브에서 500(Ambiguous handler methods)이 났었다**
                    (2026-11-03 전수 호출로 발견) — 베이스가 둘 다 `/orders`라 변수명만 다를 뿐
                    패턴이 같았다. ⚠️이 메서드의 `:[0-9]+` 제약은 **그 충돌을 풀지 못한다**
                    (Spring의 PathPattern 특이성 비교가 "제약 있는 변수"와 "제약 없는 변수"를
                    같게 본다 — `OrderPathCollisionTest`로 직접 재현·확인) — 실제로 가른 것은
                    SalesOrderController 쪽 경로를 `/orders/entries/{orderNo}`로 바꿔 **리터럴
                    세그먼트 자체를 다르게** 만든 것이다. 이 숫자 제약은 그와 별개로 남겨 둔다 —
                    REQ_CD가 항상 순수 숫자라는 입력 모양을 명시하는 용도로는 여전히 유효하다.""")
    @DeleteMapping("/{reqCd:[0-9]+}")
    public ApiResponse<OrderService.ItemResult> delete(
            @Parameter(description = "신청번호(REQ_CD)", required = true) @PathVariable int reqCd,
            @Parameter(description = "삭제 사유") @RequestParam(required = false) String reason) {
        return ApiResponse.success(orderService.delete(reqCd, reason));
    }

    @Operation(summary = "신청 가능 시행 목록",
            description = """
                    주문 등록 1단계 — **무엇을 신청할지** 고른다. 원천은 DSRE2 상품상세(시행)다.

                    · **판매중인 것만** 내려준다(사용·판매 여부 Y + 판매종료일이 오늘 이후).
                      끝난 시행으로는 신청할 수 없다.
                    · `easyYn`이 **간편신청 가능 여부**다. 시행마다 정해져 있으니
                      화면은 이 값을 보고 입력칸(인원 3칸 / 과목별 수량)을 고른다.
                    · `dtlCd`는 물류단가가 키로 쓰는 그 **시행코드**와 같은 값이다.""")
    @GetMapping("/exams")
    public ApiResponse<List<ExamRow>> exams(
            @Parameter(description = "시행명·상품명 부분일치. 미지정=전체")
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(dsreGateway.listExams(keyword));
    }

    @Operation(summary = "시행별 과목 목록",
            description = """
                    주문 등록 2단계(과목신청일 때만) — 그 시행에 **어떤 과목이 있는지**.

                    신청 시 계산처리 대상(`DISP_GN='Y'`)만 내려준다 — 레거시 신청 화면과 같은 조건이다.
                    간편신청(`easyYn='Y'`)으로 넣을 거라면 부를 필요가 없다.""")
    @GetMapping("/exams/{dtlCd}/subjects")
    public ApiResponse<List<SubjectRow>> subjects(
            @Parameter(description = "시행코드(DTL_CD)", required = true) @PathVariable int dtlCd) {
        return ApiResponse.success(dsreGateway.listSubjects(dtlCd));
    }

    @Operation(summary = "신규 주문 등록",
            description = """
                    주문을 새로 만든다. 구조는 **주문 → 반 → 과목수량 3단**이고,
                    레거시 특약점 사이트의 신청과 같은 모양이다.

                    · **테이블 셋을 한 트랜잭션으로** 넣는다. 중간에 실패하면 반·수량이 빠진
                      반쪽 주문이 남아, 물류가 무엇을 보낼지 알 수 없게 된다.
                    · **등록 직후 상태는 항상 `A`(접수완료)**다. 이후 전이(상품검수→발송완료)는
                      기존 상태변경 경로를 탄다.
                    · 반마다 **간편신청(인원 3칸)** 또는 **과목신청(과목별 수량)** 중 하나로 채운다.
                      `applyType`을 안 주면 과목 수량 유무로 판정한다.
                    · ‼️간편인데 인원이 0이거나 과목신청인데 수량이 없으면 **400**이다.
                      조용히 통과시키면 물류가 무엇을 몇 개 보낼지 모르는 주문이 선다.

                    ⚠️이 등록은 **발주처 DB(DSRE2)에 직접 기록**된다.""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderCreateResponse> create(@Valid @RequestBody OrderCreateRequest req) {
        return ApiResponse.success(orderService.create(req));
    }

    @Operation(summary = "거래명세서 발급 처리 → 발송준비중 전환",
            description = """
                    물류담당자가 거래명세서를 출력 처리한 시점에 호출한다.
                    진행상태를 **상품준비중(S) → 발송준비중(W)** 으로 바꾼다(발주처 확정 3-2(가) 5번).

                    · 상태 원본은 DSRE2라 여기서 바꾸면 DSRE2 데스크톱에도 즉시 반영된다(복사본 없음).
                    · **상품준비중일 때만** 바뀐다. 이미 발송완료 등이면 되돌리지 않고
                      `changed=false`로 응답한다 — 명세서 재출력은 실무에서 흔해 오류로 막지 않는다.
                    · 되돌리기(발송준비중 → 상품준비중)와 발송완료는 `POST /orders/state`로 한다.
                    · DSRE2엔 이력 테이블이 없어, 우리가 바꾼 건은 상태변경 이력에 남긴다.""")
    @PostMapping("/{reqCd}/issue-statement")
    public ApiResponse<OrderService.IssueResult> issueStatement(
            @Parameter(description = "신청번호(REQ_CD)", example = "78331") @PathVariable int reqCd) {
        return ApiResponse.success(orderService.issueStatement(reqCd));
    }

    @Operation(summary = "진행상태 다건 일괄 전환(되돌리기 · 발송완료 · 발송취소)",
            description = """
                    체크한 주문들의 진행상태를 한 번에 바꾼다.
                    근거: 자료요청서 3-2(가).진행상태 — 발송준비중 "**되돌릴 때는 수동 전환**",
                    발송완료 "물류가 발송 처리(**체크박스 다건 일괄** 포함)".

                    ### 바꿀 수 있는 전이
                    | 현재 | → | 목표 | 쓰임 |
                    |---|---|---|---|
                    | 상품준비중(S) | → | 발송준비중(W) | 거래명세서 출력(자동 전환의 수동판) |
                    | 발송준비중(W) | → | 상품준비중(S) | **되돌리기(한 칸)** |
                    | 발송준비중(W) | → | 발송완료(D) | **발송 처리** |
                    | 발송완료(D) | → | 발송준비중(W) | **발송취소**(9/27 회의 항목 20 ④) |

                    · 표에 **없는 전이는 거부**한다. 아무 상태로나 뛰게 열면 접수완료가 곧바로
                      발송완료가 되는 주문이 생기고, 검수·준비 단계가 있으나 마나가 된다.
                    · 되돌리기는 언제나 **한 칸**이다 — D 에서 S 나 접수완료로 바로 뛰지는 못한다.
                    · ‼️**발송취소(D→W)는 실물이 이미 나간 뒤의 되돌리기**라 오조작 위험이 크다.
                      9/27 회의에서 "발송완료·발송취소 버튼 필요"로 요구돼 열었다.
                      반드시 이력에 남고, `reason`을 함께 보내는 것을 권한다.
                    · 접수·검수·취소는 order 사이트와 DSRE2 데스크톱 소관이라 여기서 못 바꾼다.

                    ### 발송취소는 작업요청서의 발송 기록까지 내린다 (2026-10-08)
                    예전에는 **DSRE 상태만** 되돌려서, 작업요청서 화면이 계속 발송완료로 보였다
                    — 그 화면의 '발송완료'는 DSRE 상태가 아니라 **우리 발송일**로 판정하기 때문이다.
                    이제 D→W 전환이 그 신청으로 세운 발송 건의 **발송일을 함께 내린다**.

                    · 박스 수·송장번호·수령인은 **남긴다** — 물건을 싸 둔 사실은 취소되지 않고,
                      다시 보낼 때 그 값이 필요하다(송장번호는 지우면 복원할 수 없다).
                    · 한 발송 건에 신청이 여럿이면 그 상자의 발송이 함께 내려간다(한 번에 싸서 보낸 건이다).
                      ‼️**남은 신청의 DSRE 상태는 D 로 남는다** — 우리가 임의로 바꾸지 않는다.
                      작업요청서 행의 `reqCds` 를 전부 보내 함께 취소하면 된다.

                    ### 실패 처리
                    · **한 건이 안 된다고 전체를 실패시키지 않는다.** 100건을 체크했는데
                      이미 발송완료된 1건 때문에 전부 다시 고르는 것은 실무에서 못 쓴다.
                      건별로 `changed`와 넘어간 `message`를 준다 — `skipped`가 0이 아니면 확인할 것.
                    · 조회와 변경 사이에 DSRE2 데스크톱이 먼저 옮겼으면 **성공으로 치지 않는다**
                      (조건부 UPDATE). "그 사이 바뀌었다"고 알려준다.

                    ### 이력
                    바꾼 건은 상태변경 이력(`status_history`)에 남는다. DSRE2는 제자리 UPDATE라
                    이전 값이 사라져서, 우리가 안 남기면 **되돌린 사실 자체가 어디에도 없다**.
                    `reason`은 선택이지만 되돌리기는 적어 두는 편이 좋다(감사에서 묻는 것은 "왜"다).""")
    @PostMapping("/state")
    public ApiResponse<OrderService.BulkStateResult> changeState(
            @Valid @RequestBody OrderStateChangeRequest req) {
        return ApiResponse.success(orderService.changeState(req.reqCds(), req.toState(), req.reason()));
    }

    @Operation(summary = "DSRE 신청 주문 조회(옛 목록)",
            description = """
                    ‼️**주문조회 화면의 목록은 더 이상 이 경로가 아니다.** 10/27 명세 §1 로
                    조회 대상이 **우리 주문**(새 특약점 주문 + 매출 등록 주문)으로 바뀌었다 →
                    `GET /orders` 를 쓸 것. 이 경로는 DSRE 신청을 보던 옛 목록이고,
                    §2-3 "[DSRE 신청 등록] 버튼은 **숨김 처리한다(삭제하지 않음)**.
                    나중에 다시 쓰게 되면 별도 페이지로 만든다"에 따라 **남겨 둔 것**이다.
                    기존 모의고사 탭(DSRE 연동 유지)이 이 축을 쓴다.

                    신청일 기간으로 DSRE2 주문을 조회한다. 진행상태는 DSRE2 원본값을 한글명과 함께 돌려준다.

                    · **기간을 생략하면 올해 1/1~오늘**이다(다른 조회 API와 같은 규칙).
                    · 응답은 다른 목록 API와 같은 **페이지 형태**(`content`/`totalElements`)다.
                      ⚠️단 DSRE2 조회는 저장함수로 인원을 계산하며 한 번에 결과를 만들어
                      SQL 페이징이 없다. 전량을 받아 서버에서 자르므로 **총건수는 항상 전체**다.

                    · 상태값: A 접수완료 / G 상품검수 / S 상품준비중 / W 발송준비중 / D 발송완료 / C 삭제
                    · ⚠️ 취소분은 **본사·물류가 삭제한 건만** C로 조회된다.
                      특약점(지사)이 삭제한 건은 원본 행이 지워져 조회되지 않는다.
                    · 인원은 DSRE2 저장함수(FUNC_REQINWON_GET) 산출값이다.""")
    @GetMapping("/dsre")
    public ApiResponse<com.daesung.sales.common.response.PageResponse<DsreOrderRow>> dsreOrders(
            @Parameter(description = "신청일 시작(yyyy-MM-dd). 미지정 시 올해 1월 1일")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "신청일 종료(yyyy-MM-dd). 미지정 시 오늘")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "페이지(0부터)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기") @RequestParam(defaultValue = "100") int size,
            @Parameter(description = "진행상태(미지정=전체)") @RequestParam(required = false) OrderState state,
            @Parameter(description = "거래처코드(미지정=전체)") @RequestParam(required = false) String custCode,
            @Parameter(description = """
                    거래처코드 **다중 선택**(좌측 트리). 쉼표로 여러 개.
                    단건 `custCode` 와 함께 주면 **교집합**이다.""")
            @RequestParam(required = false) java.util.List<String> custCodes,
            @Parameter(description = "구분 ALL/NORMAL(정상)/ACCIDENT(사고). 미지정=전체")
            @RequestParam(required = false, defaultValue = "ALL") LogisMode mode,
            @Parameter(description = """
                    도서(상품코드) **다중 선택**. 쉼표로 여러 개. 미지정=전체.
                    ⚠️DSRE2 상품코드라 우리 도서코드와 다른 체계다(주문 응답의 상품명 축).""")
            @RequestParam(required = false) java.util.List<String> prodCodes,
            @Parameter(description = "시행(DTL_CD) **다중 선택**. 쉼표로 여러 개. 미지정=전체")
            @RequestParam(required = false) java.util.List<Integer> dtlCds,
            @Parameter(description = "상품 연도(미지정=전체)") @RequestParam(required = false) Integer productYear) {
        // ★날짜를 필수로 두었더니 화면이 못 불렀다(파라미터를 안 실어 400).
        //   다른 조회 API는 이미 기간을 생략하면 올해로 잡는다 — /orders만 예외였다.
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();
        // ★페이징을 SQL로 내린다. 예전엔 전량을 받아 여기서 잘라, 페이지 크기와 무관하게
        //   매번 2초가 걸렸다(실측 2026-09-18) — 행마다 저장함수로 인원을 계산하기 때문이다.
        //   ‼️예전 주석은 "LIMIT을 걸면 페이지마다 인원·합계가 달라진다"였는데 사실이 아니다.
        //     인원·예상금액은 **행별** 값이고 누계가 없다. 잘라도 남은 행의 값은 그대로다.
        int p = Math.max(0, page);
        int sz = Math.min(Math.max(1, size), 500);
        return ApiResponse.success(com.daesung.sales.common.response.PageResponse.of(
                orderService.withSalesNos(
                        dsreGateway.findOrders(from, to, state, custCode, custCodes, mode,
                                prodCodes, dtlCds, productYear, p * sz, sz)),
                p, sz, dsreGateway.countOrders(from, to, state, custCode, custCodes, mode,
                        prodCodes, dtlCds, productYear)));
    }

    @Operation(summary = "DSRE 신청 주문 엑셀 다운로드(옛 목록)",
            description = "‼️우리 주문 목록의 엑셀은 `GET /orders/export` 다. 이 경로는 옛 DSRE 목록용이다.")
    @GetMapping("/dsre/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) OrderState state,
            @RequestParam(required = false) String custCode,
            @RequestParam(required = false) java.util.List<String> custCodes,
            @RequestParam(required = false, defaultValue = "ALL") LogisMode mode,
            @RequestParam(required = false) java.util.List<String> prodCodes,
            @RequestParam(required = false) java.util.List<Integer> dtlCds,
            @RequestParam(required = false) Integer productYear) {
        List<Col> cols = List.of(
                new Col("신청번호", "reqCd"), new Col("신청일", "reqDate"),
                new Col("진행상태", "stateName"),
                new Col("거래처코드", "custCode"), new Col("거래처명", "custName"),
                new Col("도시", "cityName"),
                new Col("학교/학원코드", "mgrCode"), new Col("학교/학원명", "mgrName"),
                new Col("상품명", "productName"), new Col("시행명", "detailName"),
                new Col("학년", "grade"), new Col("인원", "inwon"), new Col("반수", "classCount"),
                new Col("총수량", "totalQty"), new Col("품목건수", "itemCount"),
                new Col("예상금액", "estimatedAmount"),
                new Col("성적처리", "procYn"), new Col("담당선생님", "teacher"),
                new Col("연락처", "tel"), new Col("이메일", "email"),
                new Col("발송구분", "deliveryGubun"),
                new Col("주소", "address"), new Col("비고", "memo"));
        byte[] xlsx = excel.toXlsx("주문진행상태", cols,
                orderService.withSalesNos(
                        dsreGateway.findOrders(fromDate, toDate, state, custCode, custCodes, mode,
                                prodCodes, dtlCds, productYear, 0, Integer.MAX_VALUE)));
        return excel.asDownload(xlsx, "주문_진행상태.xlsx");
    }
}
