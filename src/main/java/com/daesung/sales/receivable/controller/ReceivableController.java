package com.daesung.sales.receivable.controller;

import com.daesung.sales.common.query.Keywords;
import com.daesung.sales.common.dto.PageRequestDto;
import com.daesung.sales.common.excel.ExcelExportUtil;
import com.daesung.sales.common.excel.ExcelExportUtil.Col;
import com.daesung.sales.common.excel.ExcelExportUtil.Heading;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.receivable.dto.ArLedgerResponse;
import com.daesung.sales.product.entity.MajorCategory;
import com.daesung.sales.receivable.dto.ArStatusResponse;
import com.daesung.sales.receivable.dto.CarryforwardResult;
import com.daesung.sales.receivable.dto.CollectionRequest;
import com.daesung.sales.receivable.dto.CollectionLedgerResponse;
import com.daesung.sales.receivable.dto.CollectionResponse;
import com.daesung.sales.receivable.dto.DuffLedgerResponse;
import com.daesung.sales.receivable.entity.CollectionType;
import com.daesung.sales.receivable.service.ReceivableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 마감관리 · 채권/수금. 실제 경로: /api/v1/closing. 스펙 §마감관리(collections/ar-status) 대응. */
@Tag(name = "마감관리 · 채권/수금", description = "수금 등록/조회 · 이월 생성(idempotent) · 미수금 현황")
@RestController
@RequiredArgsConstructor
@RequestMapping("/closing")
public class ReceivableController {

    private final ReceivableService receivableService;
    private final com.daesung.sales.sale.service.SalesMailService salesMailService;
    private final ExcelExportUtil excel;

    @Operation(summary = "수금 등록",
            description = """
                    수금번호(C) 채번. 채권 잔액에서 차감된다.

                    · **수금구분**(명목) 미입력 시 `도서대금` — 레거시가 화면에서 고정하던 값이다.
                    · **입금구분**=어음일 때만 어음정보(번호/만기/은행/지점)를 저장한다.""")
    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CollectionResponse> registerCollection(@Valid @RequestBody CollectionRequest req) {
        return ApiResponse.success(receivableService.registerCollection(req));
    }

    @Operation(summary = "수금 목록 조회",
            description = """
                    기간·거래처 + **구분 2축**으로 수금 내역을 조회한다(정본 23p 데이터 항목).

                    · **수금구분**(`collKind`) = 무슨 명목으로 받은 돈인가. 기본 `도서대금`.
                    · **입금구분**(`collType`) = 어떤 형태로 들어왔는가(현금/어음/선수금/대체).

                    두 축을 하나로 합치면 "**어음으로 받은 도서대금**"을 표현할 수 없다.""")
    @GetMapping("/collections")
    public ApiResponse<PageResponse<CollectionResponse>> listCollections(
            @Parameter(description = "시작일(yyyy-MM-dd)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "거래처 id") @RequestParam(required = false) Long partnerId,
            @Parameter(description = "수금구분(명목). 미지정=전체", example = "도서대금")
            @RequestParam(required = false) String collKind,
            @Parameter(description = "입금구분(형태) CASH/PROMISSORY/PREPAY/REPLACE. 미지정=전체")
            @RequestParam(required = false) CollectionType collType,
            @Parameter(description = "키워드 — 거래처명·수금번호·어음번호·은행명을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = """
                    수금순번 — 그 거래처의 그 수금일자 안에서 몇 번째인가(1부터).
                    `partnerId`·기간과 함께 주면 **그 수금 한 건**만 추린다(프론트 B-7②).
                    등록 창의 순번 드롭다운에서 고른 값으로 수금을 불러올 때 쓴다.""")
            @RequestParam(required = false) Integer collSeq,
            @Parameter(description = """
                    조회기준 — `COLL`(수금일자, 기본) / `WRITE`(기장일자).
                    돈이 들어온 날과 장부에 올린 날이 달라 재무팀이 둘을 나눠 본다(정본 23p).
                    ‼️모르는 값은 400 — 조용히 기본값으로 넘기면 다른 기준으로 자른 목록을 보게 된다.""")
            @RequestParam(required = false) String dateBasis,
            @ParameterObject PageRequestDto pageReq) {
        return ApiResponse.success(receivableService.searchCollections(
                fromDate, toDate, partnerId, collKind, collType, keyword, collSeq, dateBasis,
                pageReq.toPageable()));
    }

    @Operation(summary = "수금 수정",
            description = """
                    수금 내용을 고친다(23p "CRUD 전체 가능 화면").
                    **수금번호·거래처는 바꿀 수 없다** — 거래처를 옮기면 두 거래처의 채권 잔액이
                    동시에 틀어진다. 그건 수정이 아니라 취소 후 재등록이다.

                    · **원래 달과 옮겨 갈 달 양쪽**의 월마감을 본다. 옮겨 갈 달만 검사하면
                      마감된 달에서 열린 달로 금액을 빼내는 길이 열린다.
                    · 입금구분을 어음이 아닌 값으로 바꾸면 어음 정보를 지운다 —
                      남겨두면 "현금인데 어음번호가 붙은" 유령 어음이 생긴다.""")
    @PutMapping("/collections/{id}")
    public ApiResponse<CollectionResponse> updateCollection(@PathVariable Long id,
                                                            @Valid @RequestBody CollectionRequest req) {
        return ApiResponse.success(receivableService.updateCollection(id, req));
    }

    @Operation(summary = "수금 삭제(논리삭제)",
            description = """
                    수금을 지운다. **행은 남고 삭제자·시각이 기록된다.**

                    수금은 돈이 들어온 기록이라 지우면 그만큼 **채권 잔액이 늘어난다**.
                    누가 언제 지웠는지 남지 않으면 잔액이 왜 달라졌는지 설명할 수 없다.
                    삭제분은 미수금현황·외상매출장·이월 스냅샷 계산에서 모두 빠진다.

                    ⚠️레거시는 이 기능이 주석 처리돼 막혀 있었으나 정본 23p가 "CRUD 전체"를 요구한다.
                    마감된 달의 수금은 지울 수 없다(PERIOD_LOCKED).""")
    @DeleteMapping("/collections/{id}")
    public ApiResponse<Void> deleteCollection(@PathVariable Long id) {
        receivableService.deleteCollection(id);
        return ApiResponse.success(null);
    }

    @Operation(summary = "수금 묶음 등록(2026-10-14 B-5)",
            description = """
                    여러 줄을 **한 전표코드로 묶어** 등록한다.
                    근거: 현장 피드백 — "수금 여러 건 등록할 때 전표코드로 한 건 생성할 때 묶일 수 있도록".

                    ★한 번 들어온 돈을 여러 줄로 쪼개 적는 경우를 위한 것이다
                    (거래처·항목별). 지금까지는 그 줄들이 "같은 입금"이라는 사실이 어디에도 없었다.

                    · 줄마다 **수금번호(C-)는 그대로 따로** 생긴다 — 개별 수정·삭제가 그 번호로 된다.
                      전표코드(V-)는 그 위에 얹는 묶음이다.
                    · 전표코드는 **첫 줄의 수금일자**로 만든다.
                    · ★**한 트랜잭션이다.** 한 줄이라도 실패하면 전부 되돌린다 —
                      같은 입금을 쪼갠 줄들이라 일부만 남으면 합계가 입금액과 안 맞는다.
                    · 마감된 달이 한 줄이라도 있으면 전체가 400(PERIOD_LOCKED).

                    ‼️거래처를 섞어도 받는다. 한 입금을 여러 거래처로 나눠 적는 경우가 실제로
                      있는지 확인 중이라 미리 막지 않았다 — 막아야 하면 그때 넣는 편이 낫다.""")
    @PostMapping("/collections/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<CollectionResponse>> registerCollections(
            @Valid @RequestBody com.daesung.sales.receivable.dto.CollectionBatchRequest req) {
        return ApiResponse.success(receivableService.registerCollections(req));
    }

    @Operation(summary = "수금 조회(소계 포함) 23p",
            description = """
                    수금관리 화면의 본 조회. **조회대상기준 3종**과 **소계**가 붙는다.

                    · `WRITE_DATE`(기장일자) — **기본값**. 기장일자로 걸러 날짜순.
                      ⚠️기장일자가 비어 있는 건(아직 기표 전)은 이 기준에서 빠진다.
                    · `COLLECT_DATE`(수금일자) — 수금일자로 걸러 날짜순.
                    · `PARTNER`(거래처) — 날짜는 수금일자로 걸러 **거래처순**으로 묶는다.

                    소계는 레거시 실물 그대로다 — 날짜 기준은 **일 계·월 계·누 계** 3단,
                    거래처 기준은 **소 계(거래처코드 첫 글자 그룹)·누 계**.
                    정본은 '일계'만 적었지만 레거시는 더 촘촘하다.

                    **페이징하지 않는다** — 소계는 앞뒤 행이 다 있어야 성립해서,
                    페이지를 자르면 잘린 지점의 소계가 틀린 값이 된다.
                    등록·수정용 그리드는 `GET /closing/collections`(페이징)를 쓴다.""")
    @GetMapping("/collections/ledger")
    public ApiResponse<CollectionLedgerResponse> collectionLedger(
            @Parameter(description = "조회대상기준(미지정 시 기장일자)")
            @RequestParam(required = false) CollectionLedgerResponse.Basis basis,
            @Parameter(description = "시작일", required = true) @RequestParam(name = "fromDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일", required = true) @RequestParam(name = "toDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long partnerId,
            @Parameter(description = "수금구분(명목)") @RequestParam(required = false) String collKind,
            @Parameter(description = "입금구분(형태)") @RequestParam(required = false) CollectionType collType) {
        return ApiResponse.success(receivableService.collectionLedger(
                basis, fromDate, toDate, partnerId, collKind, collType));
    }

    @Operation(summary = "수금 조회 엑셀 다운로드(소계 포함)")
    @GetMapping("/collections/ledger/export")
    public ResponseEntity<byte[]> collectionLedgerExport(
            @RequestParam(required = false) CollectionLedgerResponse.Basis basis,
            @RequestParam(name = "fromDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(name = "toDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long partnerId,
            @RequestParam(required = false) String collKind,
            @RequestParam(required = false) CollectionType collType) {
        List<Col> cols = List.of(
                new Col("구분", "rowType"), new Col("소계명", "label"),
                new Col("전표코드", "voucherNo"), new Col("수금번호", "collectionNo"),
                new Col("수금일자", "collDate"), new Col("기장일자", "writeDate"),
                new Col("거래처코드", "partnerCode"), new Col("거래처명", "partnerName"),
                new Col("수금구분", "collKind"), new Col("입금구분", "collTypeName"),
                new Col("금액", "collAmt"), new Col("어음번호", "promissoryNo"),
                new Col("만기일자", "promissoryDue"), new Col("은행명", "bankName"),
                new Col("지점명", "branchName"), new Col("비고", "memo"));
        byte[] xlsx = excel.toXlsx("수금관리", cols, receivableService.collectionLedger(
                basis, fromDate, toDate, partnerId, collKind, collType).rows());
        return excel.asDownload(xlsx, "수금관리_" + fromDate + "_" + toDate + ".xlsx");
    }

    @Operation(summary = "채권 이월 스냅샷 생성(idempotent)",
            description = "해당 연도 이월(=전년말 채권 잔액)을 계산해 저장. 기존 연도분 삭제 후 재생성. "
                    + "레거시의 '조회 시 자동생성' 부수효과를 제거한 명시 API.")
    @PostMapping("/carryforward")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CarryforwardResult> generateCarryforward(
            @Parameter(description = "이월 귀속 연도", example = "2026") @RequestParam int fiscalYear) {
        return ApiResponse.success(receivableService.generateCarryforward(fiscalYear));
    }

    @Operation(summary = "미수금(외상매출) 현황 조회",
            description = "거래처별 잔액 = 이월 + 기간 채권발생(매출+세액−반품) − 수금. 담보비율/경고등급 포함. "
                    + "기간 미지정 시 올해 1/1~오늘.")
    @GetMapping("/ar-status")
    public ApiResponse<ArStatusResponse> arStatus(
            @Parameter(description = "시작일(yyyy-MM-dd, 미지정 시 올해 1/1)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd, 미지정 시 오늘)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "거래처 id 필터") @RequestParam(required = false) Long partnerId,
            @Parameter(description = "키워드 — 코드·명칭을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword) {
        // ★매입처 제외(10/27 피드백 §7-C) — 이 화면 **목록**에선 '매입처' 행을 아예 빼고,
        //   합계 잔액에도 넣지 않는다. 법인 분리(리브커넥스·대성교재) 예정이라
        //   이 법인 장부에 남의 잔액이 섞이면 안 된다.
        //   ★단 **거래처를 하나 지정해 들어온 조회는 그대로 답한다**(partnerId != null).
        //     지정한 거래처가 매입처면 빈 화면이 돌아오는데, 그러면 담당자는 필터가
        //     고장 난 줄 안다 — "목록에서 빼라"와 "특정 거래처를 물었을 때 숨겨라"는 다르다.
        //     ⚠️이 경계는 발주처 확인 대상(피드백 문구는 목록 기준으로만 말한다).
        ArStatusResponse r = receivableService.arStatus(fromDate, toDate, partnerId, partnerId == null);
        // ‼️합계·소계는 그대로 둔다 — 걸러진 것만의 합으로 바꾸면 "전체 잔액"이 아니게 된다.
        return ApiResponse.success(new ArStatusResponse(r.fromDate(), r.toDate(),
                Keywords.filter(r.rows(), keyword,
                        x -> new Object[]{x.partnerCode(), x.partnerName()}),
                r.total(), r.purchaseSubtotal(), r.salesSubtotal(), r.warnings()));
    }

    @Operation(summary = "미수금현황 엑셀 다운로드", description = "거래처별 이월·매출·반품·채권발생·수금·잔액·담보비율.")
    @GetMapping("/ar-status/export")
    public ResponseEntity<byte[]> arStatusExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long partnerId) {
        // 매입처 제외는 조회와 **같은 기준**이다 — 화면과 엑셀의 합계가 갈리면 둘 다 못 믿는다.
        List<ArStatusResponse.Row> rows =
                receivableService.arStatus(fromDate, toDate, partnerId, partnerId == null).rows();
        byte[] xlsx = excel.toXlsx("미수금현황", arStatusCols(), arStatusRows(rows),
                Heading.period("외상매출현황조회", fromDate, toDate));
        return excel.asDownload(xlsx, "미수금현황.xlsx");
    }

    @Operation(summary = "미수금현황 — 담보보기 엑셀 다운로드",
            description = """
                    외상매출현황조회의 '담보보기' 모드 전용 양식(10/27 피드백 §7-C, PPT 16).
                    위 /ar-status/export(매출상세 모드)와 **컬럼 구성이 다르다** — 상품군별
                    수량·금액 분해가 빠지고 **담보만기·담보내용**이 대신 들어간다. 레거시가
                    담보비율·담보만기 칸에 색을 칠해 구분하듯, 여기 `담보등급`이 그 판정값이다.""")
    @GetMapping("/ar-status/export/collateral")
    public ResponseEntity<byte[]> arStatusCollateralExport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long partnerId) {
        List<ArStatusResponse.Row> rows =
                receivableService.arStatus(fromDate, toDate, partnerId, partnerId == null).rows();
        // ★매출상세(arStatusRows)와 달리 **수동 평탄화가 필요 없다** — 담보보기 컬럼은 전부
        //   Row의 단순 필드(리스트인 byCategory는 안 씀)라 record를 그대로 Jackson이 펼친다.
        byte[] xlsx = excel.toXlsx("미수금현황(담보보기)", arStatusCollateralCols(), rows,
                Heading.period("외상매출현황조회(담보보기)", fromDate, toDate));
        return excel.asDownload(xlsx, "미수금현황_담보보기.xlsx");
    }

    @Operation(summary = "외상매출장 조회(24p, 거래처 상세)",
            description = "단일 거래처의 기초이월 + 기간 내 매출/교사용/반품/수금 **도서 단위 명세** + 일자별 누계. "
                    + "기간 미지정 시 올해 1/1~오늘.")
    /**
     * 외상매출현황 엑셀 컬럼. 근거: 재무팀 실파일 {@code 외상매출현황조회_20260630.xlsx}(24칸) —
     * 거래처명·사장명·이월·매출수량/금액·세액 뒤에 <b>상품군별 수량·금액</b>이 붙고,
     * 교사용·반품(상품군별 포함)·입금액·잔액으로 끝난다.
     *
     * <p>★<b>상품군 칸을 데이터에서 만들지 않는다.</b> 그 달에 안 팔린 상품군이 통째로 빠지면
     * 달마다 컬럼 수가 달라져 재무팀이 파일을 겹쳐 볼 수 없다. 대분류 전체를 고정으로 깐다.
     *
     * <p>‼️실파일은 상품군 4칸(교재·모의고사·기타·특강)인데 우리 대분류는 5종이다
     * (기타고사가 더 있다). <b>합치지 않고 5칸을 그대로 낸다</b> —
     * 기타고사를 기타에 섞으면 어느 쪽 매출인지 파일에서 되찾을 수 없다.
     * 4칸으로 맞출지는 발주처 확인 대상이다.
     */
    private static List<Col> arStatusCols() {
        List<Col> cols = new ArrayList<>(List.of(
                new Col("거래처코드", "partnerCode"), new Col("거래처명", "partnerName"),
                new Col("사장명", "bossName"), new Col("이월", "opening"),
                new Col("매출수량", "saleQty"), new Col("매출액", "saleAmount"), new Col("세액", "tax")));
        for (MajorCategory c : arCategories()) {
            cols.add(new Col("수량(" + c.label() + ")", "saleQty_" + c.name()));
            cols.add(new Col("매출(" + c.label() + ")", "saleAmount_" + c.name()));
        }
        cols.add(new Col("교사용수량", "teacherQty"));
        cols.add(new Col("교사용(증정포함)", "teacherAmount"));
        cols.add(new Col("반품수량", "returnQty"));
        for (MajorCategory c : arReturnCategories()) {
            cols.add(new Col("반품수량(" + c.label() + ")", "returnQty_" + c.name()));
        }
        cols.add(new Col("반품액", "returnAmount"));
        for (MajorCategory c : arReturnCategories()) {
            cols.add(new Col("반품금액(" + c.label() + ")", "returnAmount_" + c.name()));
        }
        cols.addAll(List.of(
                new Col("채권발생", "receivableGen"),
                // 매입(입고) — 레거시 대성교재 화면의 `입고수량`·`입고금액` 칸.
                // 잔액 공식에 들어가므로 칸으로도 보여야 한다. 안 보이면 매입처 잔액을 설명할 수 없다.
                new Col("구분", "partnerKind"),
                new Col("입고수량", "purchaseQty"), new Col("입고금액", "purchaseAmount"),
                new Col("수금", "collected"), new Col("잔액", "balance"),
                new Col("담보금액", "assureAmount"), new Col("담보비율", "assureRatio"),
                new Col("담보등급", "assureLevel")));
        return cols;
    }

    /**
     * 외상매출현황 '담보보기' 전용 컬럼. 근거: 10/27 피드백 §7-C·PPT 16 —
     * <pre>거래처명/사장명/이월금액/매출수량/매출금액/세액/교사용수량·금액/반품수량·금액/
     * 입금액/잔액/담보금액/담보비율/담보만기/담보내용</pre>
     *
     * <p>★<b>{@link #arStatusCols()}(매출상세)와 교집합이 아니다.</b> 상품군별 분해(교재·
     * 모의고사·기타·특강 수량·금액, 반품 상품군별)가 전부 빠지고, 대신 레거시 원본 14칸 중
     * {@code arStatusCols()}에 빠져 있던 <b>담보만기·담보내용</b>이 들어간다 — 이 둘은
     * 지금까지 응답 DTO엔 있었지만 어느 엑셀에도 안 나가고 있었다.
     *
     * <p>담보등급은 레거시 원문엔 없지만, 레거시가 담보비율·만기 칸에 색을 칠해 구분하는 것과
     * 같은 역할(화면이 색을 입힐 판정값)이라 매출상세 export처럼 덤으로 둔다.
     */
    private static List<Col> arStatusCollateralCols() {
        return List.of(
                new Col("거래처코드", "partnerCode"), new Col("거래처명", "partnerName"),
                new Col("사장명", "bossName"), new Col("이월", "opening"),
                new Col("매출수량", "saleQty"), new Col("매출액", "saleAmount"), new Col("세액", "tax"),
                new Col("교사용수량", "teacherQty"), new Col("교사용(증정포함)", "teacherAmount"),
                new Col("반품수량", "returnQty"), new Col("반품액", "returnAmount"),
                new Col("수금", "collected"), new Col("잔액", "balance"),
                new Col("담보금액", "assureAmount"), new Col("담보비율", "assureRatio"),
                new Col("담보만기", "assureExpiry"), new Col("담보내용", "assureNote"),
                new Col("담보등급", "assureLevel"));
    }

    /**
     * 엑셀에 낼 <b>매출</b> 상품군 — 4칸 고정(교재·모의고사·기타·특강).
     * 근거: 10/27 피드백 §7-C + 재무팀 실파일 24칸.
     *
     * <p>‼️예전엔 {@code MajorCategory.values()} 중 visible 전부(5칸)를 깔아
     * 기타고사 칸이 따로 나갔고, 대분류를 못 붙인 매출은 어느 칸에도 안 들어갔다.
     * 지금은 서비스가 기타고사·미분류·IC를 <b>기타로 합쳐</b> 4칸으로만 내려준다.
     */
    private static List<MajorCategory> arCategories() {
        return List.of(MajorCategory.TEXTBOOK, MajorCategory.MOCK_EXAM,
                MajorCategory.ETC, MajorCategory.SPECIAL_LECTURE);
    }

    /**
     * 엑셀에 낼 <b>반품</b> 상품군 — 2칸(교재·기타).
     * 근거: 10/27 피드백 §7-C "반품은 교재·기타 2분류" — 실파일에도 반품 칸은
     * `반품수량(교재)·반품수량(기타)` 둘뿐이고 모의고사·특강 반품 칸이 없다.
     */
    private static List<MajorCategory> arReturnCategories() {
        return List.of(MajorCategory.TEXTBOOK, MajorCategory.ETC);
    }

    /**
     * 행을 평탄화한다 — 상품군 분해가 리스트라 엑셀 컬럼(field 한 개)으로는 못 꺼낸다.
     * 대분류를 못 붙인 매출(미분류)은 <b>컬럼이 없어 상품군 칸에는 안 나온다</b>.
     * 다만 전체 매출수량·매출액에는 들어 있으므로, 상품군 합이 전체보다 작으면 미분류가 있다는 뜻이다.
     */
    private static List<Map<String, Object>> arStatusRows(List<ArStatusResponse.Row> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ArStatusResponse.Row r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partnerCode", r.partnerCode());
            m.put("partnerName", r.partnerName());
            m.put("bossName", r.bossName());
            m.put("opening", r.opening());
            m.put("saleQty", r.saleQty());
            m.put("saleAmount", r.saleAmount());
            m.put("tax", r.tax());
            m.put("teacherQty", r.teacherQty());
            m.put("teacherAmount", r.teacherAmount());
            m.put("returnQty", r.returnQty());
            m.put("returnAmount", r.returnAmount());
            m.put("receivableGen", r.receivableGen());
            m.put("collected", r.collected());
            m.put("balance", r.balance());
            m.put("assureAmount", r.assureAmount());
            m.put("assureRatio", r.assureRatio());
            m.put("assureLevel", r.assureLevel());
            for (MajorCategory c : arCategories()) {
                m.put("saleQty_" + c.name(), 0L);
                m.put("saleAmount_" + c.name(), 0L);
                m.put("returnQty_" + c.name(), 0L);
                m.put("returnAmount_" + c.name(), 0L);
            }
            for (ArStatusResponse.CategoryBreakdown c : r.byCategory()) {
                if (c.majorCategory() == null) {
                    continue;
                }
                String k = c.majorCategory().name();
                m.put("saleQty_" + k, c.saleQty());
                m.put("saleAmount_" + k, c.saleAmount());
                m.put("returnQty_" + k, c.returnQty());
                m.put("returnAmount_" + k, c.returnAmount());
            }
            out.add(m);
        }
        return out;
    }

    @GetMapping("/ar-ledger")
    public ApiResponse<ArLedgerResponse> arLedger(
            @Parameter(description = "거래처 id", example = "1") @RequestParam Long partnerId,
            @Parameter(description = "시작일(yyyy-MM-dd, 미지정 시 올해 1/1)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd, 미지정 시 오늘)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "키워드 — 코드·명칭을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword) {
        ArLedgerResponse r = receivableService.arLedger(partnerId, fromDate, toDate);
        // ‼️이월·마감(opening/closing)은 그대로 둔다. 걸러진 줄만으로 다시 계산하면
        //   러닝밸런스가 원장과 어긋나 "검색했더니 잔액이 달라졌다"가 된다.
        // ‼️키워드를 주면 **집계행(이월·일계·월계·누계)은 뺀다.** 그 숫자는 원장 전체 기준이라
        //   걸러진 명세 옆에 두면 "이 검색 결과의 합"으로 읽힌다 — 실제로는 전혀 다른 수다.
        //   키워드가 없으면 그대로 다 내려간다(PDF 인쇄물과 같은 모양).
        boolean searching = keyword != null && !keyword.isBlank();
        var lines = searching
                ? r.lines().stream().filter(l -> "DETAIL".equals(l.rowType())).toList()
                : r.lines();
        return ApiResponse.success(new ArLedgerResponse(r.partnerId(), r.partnerCode(), r.partnerName(),
                r.fromDate(), r.toDate(), r.supplierName(), r.bankAccount(),
                r.opening(), r.closing(),
                Keywords.filter(lines, keyword,
                        x -> new Object[]{x.refNo(), x.catCode(), x.catName(),
                                x.productCode(), x.productName()})));
    }

    @Operation(summary = "외상매출장 — 전체 거래처(24p)",
            description = """
                    거래처별 원장을 한 번에 낸다. **거래처마다 한 벌씩** 담긴다 —
                    한 표로 합치면 러닝밸런스가 남의 거래에 밀려 의미를 잃는다.

                    · 대상 거래처는 **외상매출현황과 같은 집합**이다(이월이 있거나 기간 내 거래가 있는 곳).
                    · 조회는 **거래처 수와 무관하게 한 벌**만 돈다(10/30 D-3 — 예전에는
                      거래처마다 단건 조회를 다시 돌려 100곳 8.5초·200곳 19.9초였다).""")
    @GetMapping("/ar-ledger/all")
    public ApiResponse<com.daesung.sales.common.response.PageResponse<ArLedgerResponse>> arLedgerAll(
            @Parameter(description = "시작일(yyyy-MM-dd, 미지정 시 올해 1/1)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(yyyy-MM-dd, 미지정 시 오늘)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "키워드 — 거래처명으로 좁힌다(부분일치)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = """
                    **신고대상만** — 기간 내 거래(매출·반품·수금)가 있는 거래처만 내려준다.

                    레거시에서 '신고대상'은 조회 필터가 아니라 **일괄 메일·PDF 대상을 추리는 스위치**였다
                    (화면 안내: "신고대상 체크시 설정된 기간내 신고내역이 있는 거래처에 내역서 일괄 메일 전송").
                    여기서 걸러 주면 **거래가 없는 곳에 0원짜리 명세서가 나가는 사고**를 막는다.""")
            @RequestParam(required = false, defaultValue = "false") boolean onlyReportable,
            @org.springdoc.core.annotations.ParameterObject
            com.daesung.sales.common.dto.PageRequestDto pageReq) {
        // ‼️키워드가 오면 **먼저 거르고** 잘라야 한다 — 자른 뒤 거르면 페이지마다 건수가 들쭉날쭉하고
        //   총건수도 틀린다. 키워드는 거래처명 부분일치라 원장을 다 만들어야 알 수 있어,
        //   이때만 전량을 훑는다(키워드를 주는 조회는 결과가 작다).
        var p = pageReq.toPageable();
        if (keyword != null && !keyword.isBlank()) {
            var filtered = Keywords.filter(
                    receivableService.arLedgerAll(fromDate, toDate, onlyReportable), keyword,
                    x -> new Object[]{x.partnerName()});
            int from = (int) Math.min(p.getOffset(), filtered.size());
            int to = Math.min(from + p.getPageSize(), filtered.size());
            return ApiResponse.success(com.daesung.sales.common.response.PageResponse.of(
                    new org.springframework.data.domain.PageImpl<>(
                            filtered.subList(from, to), p, filtered.size())));
        }
        return ApiResponse.success(com.daesung.sales.common.response.PageResponse.of(
                receivableService.arLedgerAllPage(fromDate, toDate, onlyReportable, p)));
    }

    @Operation(summary = "외상매출장 '더프모만'(24p)",
            description = """
                    모의고사 매출을 **학교(원)·시행월·학년·처리** 단위로 본다.
                    정본 24p가 "'더프모만' 체크 시 그리드 컬럼 구조 자체가 완전 전환"이라 해서
                    기본 외상매출장과 **다른 응답**으로 낸다 —
                    한 응답에 두 스키마를 우겨넣으면 절반이 늘 비어 있는 DTO가 된다.

                    ```
                    [기본]   일자 · 구분 · 전표번호 · 적요 · 금액 · 잔액   ← GET /closing/ar-ledger
                    [더프모] 일자 · 학교(원)명 · 시행월 · 학년 · 처리 · 정가 · 공급률 · 수량 · 금액
                    ```
                    기본 장부는 **채권 러닝밸런스**를 보는 화면이고 이쪽은 모의고사 매출 세부라 잔액이 없다.

                    · 소계는 레거시 그대로 **학교(원) 계 → 월 계** 순으로 붙는다.
                    · 모의고사 판별은 **대분류**로 한다. 레거시는 `catCode LIKE 'M%A%'`처럼
                      코드 패턴으로 걸렀는데, 그러면 코드체계가 바뀔 때마다 깨진다.
                    · **시행월**은 2단 경로다 — ① BOM 시행예정일, ② 없으면 도서명 맨 앞 'N월'
                      (연도는 분류코드에서). 둘 다 없으면 비운다. 자세한 근거는 응답 필드 설명 참고.
                    · **전 거래처**는 `GET /closing/ar-ledger/duff/all` — PDF 일괄저장의 전제다.""")
    @GetMapping("/ar-ledger/duff")
    public ApiResponse<DuffLedgerResponse> duffLedger(
            @Parameter(description = "거래처 id", required = true) @RequestParam Long partnerId,
            @Parameter(description = "시작일(미지정 시 올해 1/1)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(미지정 시 오늘)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ApiResponse.success(receivableService.duffLedger(partnerId, fromDate, toDate));
    }

    @Operation(summary = "외상매출장 '더프모만' 엑셀 다운로드")
    @GetMapping("/ar-ledger/duff/export")
    public ResponseEntity<byte[]> duffLedgerExport(
            @RequestParam Long partnerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        List<Col> cols = List.of(
                new Col("구분", "rowType"), new Col("소계명", "label"),
                new Col("일자", "date"), new Col("학교(원)명", "schoolName"),
                new Col("시행월", "examMonth"), new Col("학년", "grade"),
                new Col("처리", "procType"), new Col("정가", "unitPrice"),
                new Col("공급률", "supplyRate"), new Col("수량", "qty"), new Col("금액", "amount"));
        byte[] xlsx = excel.toXlsx("외상매출장_더프모", cols,
                receivableService.duffLedger(partnerId, fromDate, toDate).rows());
        return excel.asDownload(xlsx, "외상매출장_더프모.xlsx");
    }

    @Operation(summary = "외상매출장 '더프모만' — 전 거래처",
            description = """
                    더프모 매출이 **있는 거래처 전부**를 거래처당 원장 하나로 내려준다.

                    ★근거: 레거시 거래처 콤보 0번이 "전체 거래처"이고, **그걸 골랐을 때만**
                    「전 거래처 메일 일괄발송」·「PDF 일괄저장」이 켜진다
                    (외상매출장조회.vb:97~106·121). 전체 조회는 부수 기능이 아니라
                    **일괄 발송의 전제**다 — 화면이 "전체 거래처의 더프모는 아직 서버가
                    지원하지 않습니다"로 막혀 있던 지점이다.

                    ★**매출이 있는 거래처만** 나온다. 없는 곳까지 넣으면 0원짜리 명세서가
                    메일·PDF로 나가는 사고가 된다.

                    ★각 원장에 `partnerDsreCode`가 실려 있다 —
                    발주처 지정 파일명 `RESULT_{DSRE 거래처코드}_{거래처명}_{yyyyMMdd}-{yyyyMMdd}.pdf`
                    (예: `RESULT_10003_강남 하람도서_20260801-20260831.pdf`)를 만들 때 쓴다.

                    ‼️`partnerCode`(우리 마스터 코드, `A10A195` 꼴)와 **다른 값**이다.
                    그걸 쓰면 `RESULT_A10A195_…` 가 된다.
                    비어 있으면 `POST /masters/clients/sync-dsre-code` 를 먼저 돌려야 한다.""")
    @GetMapping("/ar-ledger/duff/all")
    public ApiResponse<List<DuffLedgerResponse>> duffLedgerAll(
            @Parameter(description = "시작일(미지정 시 올해 1/1)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일(미지정 시 오늘)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "키워드 — 거래처코드·거래처명을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(Keywords.filter(
                receivableService.duffLedgerAll(fromDate, toDate), keyword,
                x -> new Object[]{x.partnerCode(), x.partnerName()}));
    }

    @Operation(summary = "외상매출장 '더프모만' 요약표 엑셀 다운로드",
            description = """
                    처리 구분별 수량·금액 + 총계. 레거시 `외상매출장조회.vb:778` 의
                    `GROUP BY rollup(처리)` 그리드(`DataGridView_요약`)다.

                    ★RESULT_ PDF 는 명세와 요약을 **같이** 찍는다(`ExportPDF_요약추가`, 1684행).
                    PDF 생성 자체는 화면(프론트 인쇄 엔진)이 하므로 서버는 두 데이터를 댄다.""")
    @GetMapping("/ar-ledger/duff/summary/export")
    public ResponseEntity<byte[]> duffLedgerSummaryExport(
            @RequestParam Long partnerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        List<Col> cols = List.of(
                new Col("처리", "procType"), new Col("총계", "total"),
                new Col("수량", "qty"), new Col("금액", "amount"));
        byte[] xlsx = excel.toXlsx("더프모_요약", cols,
                receivableService.duffLedger(partnerId, fromDate, toDate).summary());
        return excel.asDownload(xlsx, "외상매출장_더프모_요약.xlsx");
    }

    @Operation(summary = "외상매출장 엑셀 다운로드", description = "단일 거래처 일자별 매출/반품/수금 명세 + 러닝밸런스.")
    @GetMapping("/ar-ledger/export")
    public ResponseEntity<byte[]> arLedgerExport(
            @RequestParam Long partnerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        // ★레거시 13칸 그대로(10/27 피드백 §7-A + 실물 PDF `SALES_*.pdf`).
        //   ‼️집계·구분·전표번호·분류코드·도서코드는 뺐다 — 피드백이 "제외: 구분, 거래처코드,
        //   거래처명"이라 했고, 나머지도 레거시 인쇄물에 없는 칸이다. 특히 '구분'은 7단계에서
        //   DTO 필드가 사라져 **빈 칸으로 나가고 있었다**.
        List<Col> cols = List.of(
                new Col("일자", "date"), new Col("분류명", "catName"), new Col("도서명", "productName"),
                new Col("공급(%)", "supplyRate"),
                new Col("매출수량", "saleQty"), new Col("매출금액", "saleAmount"), new Col("세액", "tax"),
                new Col("교사용수량", "teacherQty"), new Col("교사용금액", "teacherAmount"),
                new Col("반품수량", "returnQty"), new Col("반품금액", "returnAmount"),
                new Col("수금액", "collectAmount"), new Col("잔액", "balance"));
        var led = receivableService.arLedger(partnerId, fromDate, toDate);
        byte[] xlsx = excel.toXlsx("외상매출장", cols, arLedgerRows(led.lines()),
                // 제목은 "외상매출장(거래처명)"(§7-A). 거래처코드는 조회기준 줄에 붙인다 —
                // 인쇄물 제목 줄이 `10003 ─ 강남 하람도서` 꼴이라 둘 다 필요하다.
                Heading.period("외상매출장(" + led.partnerName() + ")", fromDate, toDate,
                        "거래처코드 : " + led.partnerCode()),
                m -> !"DETAIL".equals(m.get("rowType")));
        return excel.asDownload(xlsx, "외상매출장.xlsx");
    }

    /**
     * 외상매출장 엑셀 행 — <b>집계행의 라벨을 첫 칸(일자)에 넣는다</b>.
     * 근거: 10/27 피드백 §7-B "월 계는 **첫 번째 열(일자 칸)**에 표시한다. 지금은 두 번째 열에
     * 나온다" + 실물 PDF({@code SALES_*.pdf})가 '일계·월계·누계'를 맨 왼쪽에 찍는다.
     *
     * <p>{@code rowType}은 컬럼으로 내보내지 않지만 맵에 담는다 — 엑셀 엔진이 집계행에
     * 색을 입힐 때 그 값으로 판정한다(§7-6).
     */
    private static List<Map<String, Object>> arLedgerRows(List<ArLedgerResponse.Line> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ArLedgerResponse.Line l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rowType", l.rowType());
            m.put("date", (l.label() != null) ? l.label()
                    : (l.date() == null ? null : l.date().toString()));
            m.put("catName", l.catName());
            m.put("productName", l.productName());
            m.put("supplyRate", l.supplyRate());
            m.put("saleQty", l.saleQty());
            m.put("saleAmount", l.saleAmount());
            m.put("tax", l.tax());
            m.put("teacherQty", l.teacherQty());
            m.put("teacherAmount", l.teacherAmount());
            m.put("returnQty", l.returnQty());
            m.put("returnAmount", l.returnAmount());
            m.put("collectAmount", l.collectAmount());
            m.put("balance", l.balance());
            out.add(m);
        }
        return out;
    }

    @Operation(summary = "외상매출장 이메일 전송(20p)",
            description = """
                    근거: 9/27 회의 A-4(항목 17) — "외상매출장조회에도 이메일 전송 누락".
                    레거시 `외상매출장조회.vb:1434` 와 같은 동작이다.

                    ★첨부·제목·수신 규칙은 통합매출조회(`POST /sales/email`)와 **같다** —
                    레거시도 두 화면이 같은 `SendMail` 을 같은 인자로 부르고, 첨부도 같은
                    「거래상세내역서」다(`외상매출장조회.vb:1434`).

                    거래처마다 따로 보내고, 한 건이 실패해도 나머지는 보낸다.

                    ★**거래처를 안 고르면 외상매출장에 실제로 잡힌 거래처로 좁힌다**(2026-10-01).
                    `onlyReportable` 도 그 판정에 함께 쓰인다 — 화면에서 걸러 본 것과 다른 곳에
                    메일이 나가면 되돌릴 수 없다.""")
    @PostMapping("/ar-ledger/email")
    public ApiResponse<com.daesung.sales.common.mail.MailSendResponse> arLedgerEmail(
            @Parameter(description = "시작일", required = true) @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "종료일", required = true) @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @Parameter(description = "거래처 id **다중선택**. 비우면 외상매출장에 나온 거래처 전부")
            @RequestParam(required = false) java.util.List<Long> partnerIds,
            @Parameter(description = """
                    신고대상만(조회 화면의 그 체크박스, 2026-10-01 추가).
                    켜면 **신고대상 거래처에만** 보낸다 — 화면에서 걸러 본 것과 다른 곳에
                    메일이 나가지 않게 하려는 것이다.""")
            @RequestParam(required = false, defaultValue = "false") boolean onlyReportable,
            @Parameter(description = "키워드") @RequestParam(required = false) String keyword) {
        // ‼️메일 설정부터 본다. 꺼져 있는데 "거래처가 없습니다"가 뜨면 담당자는 조건만 계속 바꾼다.
        salesMailService.assertUsable();
        // ★거래처를 안 고르면 **외상매출장에 실제로 잡힌 거래처**로 좁힌다.
        //   예전엔 매출 조회 결과 전체로 보내서, 화면에 안 보이던 거래처에도 메일이 나갈 수 있었다.
        java.util.List<Long> targets = partnerIds;
        if (targets == null || targets.isEmpty()) {
            targets = receivableService.arLedgerAll(fromDate, toDate, onlyReportable).stream()
                    .map(com.daesung.sales.receivable.dto.ArLedgerResponse::partnerId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            if (targets.isEmpty()) {
                throw new com.daesung.sales.common.exception.BusinessException(
                        com.daesung.sales.common.exception.ErrorCode.NOT_FOUND,
                        "외상매출장에 잡히는 거래처가 없습니다. 조건을 확인하세요.");
            }
        }
        return ApiResponse.success(salesMailService.sendStatements(fromDate, toDate,
                null, null, null, targets, null, null,
                null, null, null, null, keyword, false));
    }
}
