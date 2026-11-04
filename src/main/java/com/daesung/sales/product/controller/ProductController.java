package com.daesung.sales.product.controller;

import com.daesung.sales.common.dto.PageRequestDto;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.product.dto.BomRegisterRequest;
import com.daesung.sales.product.dto.BomResponse;
import com.daesung.sales.product.dto.ProductFlagBulkRequest;
import com.daesung.sales.product.dto.ProductFlagBulkResult;
import com.daesung.sales.product.dto.ProductCreateRequest;
import com.daesung.sales.product.dto.ProductResponse;
import com.daesung.sales.product.dto.ProductSlimResponse;
import com.daesung.sales.product.dto.ProductUpdateRequest;
import com.daesung.sales.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 기초관리 - 상품(도서) 관리. 실제 경로: /api/v1/masters/products. */
@Tag(name = "기초관리 · 상품", description = "도서/상품 마스터 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/masters/products")
public class ProductController {

    private final com.daesung.sales.product.service.ProductUploadService uploadService;

    private final ProductService productService;
    private final com.daesung.sales.product.service.PartnerProductVisibilityService partnerProductVisibilityService;
    private final com.daesung.sales.common.excel.ExcelExportUtil excel;

    @Operation(summary = "상품 목록 조회", description = "keyword(코드/명 부분일치)로 검색, 페이징·정렬 지원")
    @GetMapping
    public ApiResponse<PageResponse<?>> list(
            @Parameter(description = "검색어(상품코드 또는 상품명 부분일치)") @RequestParam(required = false) String keyword,
            @Parameter(description = """
                    수불부노출 필터 — 제품수불부(11p) 집계 대상만/제외만 보기.
                    ★단가노출과 **별개 축**이다(발주처 2026-08-21 E-7로 분리).""")
            @RequestParam(required = false) Boolean ledgerVisible,
            @Parameter(description = """
                    단가노출 필터 — 거래처별 단가를 매기는 도서만/제외만 보기.
                    수불부엔 안 나와도 단가는 매기는 상품이 있어 수불부노출과 따로 둔다.""")
            @RequestParam(required = false) Boolean priceVisible,
            @Parameter(description = """
                    **이 시각 이후 등록·수정된 도서만**(yyyy-MM-dd'T'HH:mm:ss).
                    프론트 요청 2026-10-20 B-1 — 이관 뒤 14,808권(약 5.4MB)이라 전량을 30분마다
                    다시 받고 있었다. 지난번 받은 응답의 `updatedAt` 중 **가장 큰 값**을 그대로 넣으면
                    그 뒤 바뀐 것만 온다. 사용중지(useYn=false)도 수정이라 여기 잡힌다.

                    ‼️**삭제(논리삭제)는 이걸로 알 수 없다** — 목록에서 아예 빠지기 때문이다.
                      삭제분까지 따라가야 하면 별도 경로가 필요하다(협의 중).
                    ‼️한 번도 안 고친 도서는 `updatedAt` 이 비어 있어, 서버는 **등록시각**으로 본다.
                      그래서 새로 등록된 도서도 빠짐없이 잡힌다.""")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime updatedSince,
            @Parameter(description = """
                    `slim`(소문자 그대로) 을 주면 **화면이 쓰는 칸만** 담아 가볍게 보낸다(프론트 요청 2026-10-20 B-2).
                    담는 칸: id·code·name·catCode·catName·majorCategoryName·set·salesDivision·
                    useYn·price·taxFree·supplyRate·contentType·grade·priceVisible·stockManaged·updatedAt.
                    ★기본(미지정)은 **전체 칸 그대로**다 — 다른 화면이 쓰는 칸을 말없이 빼면 그쪽이 깨진다.""",
                    schema = @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"slim"}))
            @RequestParam(required = false) String view,
            @ParameterObject PageRequestDto pageReq) {
        var page = productService.findAll(
                keyword, ledgerVisible, priceVisible, updatedSince, pageReq.toPageable());
        // ‼️대소문자를 접지 않고 **정확히** 비교한다. 접으면 정적분석(IMPROPER_UNICODE)에 걸리고,
        //   로케일을 명시해도 마찬가지다 — 규칙이 '대소문자 변환 후 분기' 자체를 본다.
        //   값은 우리가 정하는 것이라(화면이 보내는 고정 문자열) 접을 이유가 없다.
        if ("slim".equals(view)) {
            return ApiResponse.success(page.map(ProductSlimResponse::from));
        }
        return ApiResponse.success(page);
    }

    @Operation(summary = "도서 목록 엑셀 다운로드", description = "검색조건 전체를 xlsx로(도서관리 기본정보).")
    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<byte[]> listExport(@RequestParam(required = false) String keyword) {
        var cols = java.util.List.of(
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("도서코드", "code"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("도서명", "name"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("콘텐츠구분", "contentType"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("분류코드", "catCode"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("분류명", "catName"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("정가", "price"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("면세", "taxFree"),
                // 발주처 회신 2026-08-20: '매출구분' → '세부구분'으로 명칭 변경. 집계 기준인 대분류를 앞에 둔다.
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("대분류", "majorCategoryName"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("세부구분", "salesDivision"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("수불부노출", "ledgerVisible"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("단가노출", "priceVisible"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("재고관리", "stockManaged"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("사용여부", "useYn"));
        byte[] xlsx = excel.toXlsx("도서목록", cols,
                productService.findAll(keyword, null, null,
                        org.springframework.data.domain.PageRequest.of(0, 100000)).getContent());
        return excel.asDownload(xlsx, "도서목록.xlsx");
    }

    @Operation(summary = "상품 상세 조회", description = "id로 단건 조회. 없으면 404")
    @GetMapping("/{id}")
    public ApiResponse<ProductResponse> get(@PathVariable Long id) {
        return ApiResponse.success(productService.findById(id));
    }

    @Operation(summary = "상품 등록", description = "상품코드 중복 시 400 반환")
    @PostMapping
    public ApiResponse<ProductResponse> create(@Valid @RequestBody ProductCreateRequest req) {
        return ApiResponse.success(productService.create(req));
    }

    @Operation(summary = "상품 수정", description = "코드는 불변. 없으면 404")
    @PutMapping("/{id}")
    public ApiResponse<ProductResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody ProductUpdateRequest req) {
        return ApiResponse.success(productService.update(id, req));
    }

    @Operation(summary = "상품 삭제(비활성화)", description = "물리삭제가 아니라 useYn=false 논리삭제")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        productService.deactivate(id);
        return ApiResponse.success();
    }

    @Operation(summary = "BOM 구성 조회", description = "완제품(세트)의 구성품·비율")
    @GetMapping("/{id}/bom")
    public ApiResponse<BomResponse> getBom(@PathVariable Long id) {
        return ApiResponse.success(productService.getBom(id));
    }

    @Operation(summary = "BOM 구성 등록", description = "완제품의 구성품·비율 등록(기존 구성 대체). 완제품은 세트로 표시됨")
    @PutMapping("/{id}/bom")
    public ApiResponse<BomResponse> registerBom(@PathVariable Long id,
                                                @Valid @RequestBody BomRegisterRequest req) {
        return ApiResponse.success(productService.registerBom(id, req));
    }

    // ── 거래처별 단가(34p)는 별도 컨트롤러다 ──────────────────────────────
    //   화면은 도서관리 3번째 탭이지만 데이터에 도서 차원이 없다 —
    //   정본 34p가 "거래처별로 상품군(대분류)마다"라고 못박고 있다.
    //   경로: /masters/partner-supply-rates (PartnerSupplyRateController).

    @Operation(summary = "도서 Y/N 항목 일괄 변경",
            description = """
                    화면에서 고른 여러 도서의 Y/N 항목을 한 번에 바꾼다
                    (발주처 요청: "각 열을 일괄로 처리할 수 있는 기능(전체선택 등)").

                    · **지정한 항목만** 바뀐다. 안 보낸 항목은 그대로 둔다.
                    · 요청 건수와 **실제로 바뀐 건수를 나눠** 돌려준다 —
                      이미 같은 값이면 안 바뀌므로 "몇 건 적용"만으로는 무슨 일이 났는지 모른다.
                    · 건별로 **변경이력에 남는다**. 잘못 눌렀을 때 되짚을 수 있어야 한다.
                    · ⚠️재고관리를 끄면 그 도서는 매출을 넣어도 재고가 차감되지 않는다.""")
    @PutMapping("/flags")
    public ApiResponse<ProductFlagBulkResult> bulkUpdateFlags(
            @Valid @RequestBody ProductFlagBulkRequest req) {
        return ApiResponse.success(productService.bulkUpdateFlags(req));
    }

    @Operation(summary = "★거래처별 노출 — 거래처 목록(도서 1권 기준)", description = """
            [도서관리] 도서 상세 → '거래처별 노출' 탭. 근거: **11/04 클라이언트 회신 §2**
            (10/31 판의 [거래처관리] 거래처 축을 대체한다 — 축이 뒤집혔다).

            한 도서를 놓고 **거래처별 상태**를 본다.
            · `open` = 이 거래처에 '열기'로 지정돼 있는지 = **체크박스 상태**
            · `visible` = 노출 판단 순서를 전부 적용한 **실제 노출 결과**
              (① 도서 Web게시 N → 비노출 / ② 지정 거래처 전용 → 열기 있는 곳만
               / ③ 열기 있음 → 노출 / ④ 거래처×대분류 매핑)
            · `productWebVisible=false` 면 무엇을 지정해도 비노출이다(①이 최우선) → 화면은 경고를 띄운다
            · `productRestrictedVisible=true` 면 열기 없는 거래처는 매핑과 무관하게 비노출이다

            ‼️**'닫기'는 없다.** 지정은 열기 하나뿐이고 닫는 것은 지정을 지우는 것이다
            (11/04 §1 — 예전 '닫기' 예외는 '지정 거래처 전용' 스위치로 대체됐다).""")
    @GetMapping("/{productId}/partner-visibility")
    public ApiResponse<PageResponse<
            com.daesung.sales.product.dto.ProductPartnerVisibilityRow>> partnerVisibility(
            @PathVariable Long productId,
            @Parameter(description = "거래처코드·거래처명 부분일치") @RequestParam(required = false) String keyword,
            @Parameter(description = "거래처구분(정확일치) — 거래처별 단가 탭과 같은 필터") @RequestParam(required = false) String clientCategory,
            @Parameter(description = "거래 종료된 거래처도 포함할지(기본 false)") @RequestParam(defaultValue = "false") boolean includeExpired,
            @ParameterObject PageRequestDto page) {
        return ApiResponse.success(PageResponse.of(
                partnerProductVisibilityService.list(productId, keyword, clientCategory,
                        includeExpired, page.toPageable())));
    }

    @Operation(summary = "★거래처별 노출 — 선택 거래처 일괄 적용", description = """
            체크한 거래처들에 '열기'를 **한 번에** 걸거나 푼다(정본 1탭 도서관리
            "거래처구분 필터로 좁힌 뒤 여러 거래처를 체크해 '선택 거래처 일괄 적용'").

            · `open=true` → 열기 지정 / `open=false` → 지정 해제
            · 이미 그 상태인 거래처는 `unchanged` 로 센다(이력을 부풀리지 않는다)
            · 건별로 **변경 이력**이 남는다(누가·언제·열기/해제·사유)

            ‼️해제는 '강제 비노출'이 **아니다**. 해제 뒤 노출은 도서가 '지정 거래처 전용'이면
            비노출, 아니면 거래처×대분류 매핑을 따른다.""")
    @PutMapping("/{productId}/partner-visibility")
    public ApiResponse<com.daesung.sales.product.dto.ProductVisibilityBulkResult> bulkPartnerVisibility(
            @PathVariable Long productId,
            @Valid @RequestBody com.daesung.sales.product.dto.ProductVisibilityBulkRequest req) {
        return ApiResponse.success(partnerProductVisibilityService.bulkApply(productId, req));
    }

    @Operation(summary = "★★거래처별 노출 — 분류 단위 일괄 적용", description = """
            [도서관리] '거래처별 노출' 탭의 **"분류명 체크 → 하위 도서 일괄 선택"** 조작.
            근거: 11/04 클라이언트 회신 §2.

            `catCode`를 주면 그 분류의 **도서 전체**가 대상이다(`productIds`는 무시).
            분류 없이 화면에서 도서를 개별 체크했다면 `productIds`를 준다.
            거기에 체크한 **거래처 여러 곳**(`partnerIds`)을 곱해 전부에 같은 열기/해제를
            건다 — 도서 10권 × 거래처 5곳을 체크하면 50개 조합을 한 번에 처리한다.

            ‼️위 단건 API({@code PUT /{productId}/partner-visibility})와 **같은 규칙**을
            쓴다 — 도서마다 그 API를 호출해 합산할 뿐이라 결과(열기/닫기/이미그상태)가
            두 경로 사이에서 어긋나지 않는다.""")
    @PutMapping("/partner-visibility/bulk")
    public ApiResponse<com.daesung.sales.product.dto.ProductVisibilityCategoryBulkResult> bulkPartnerVisibilityByCategory(
            @Valid @RequestBody com.daesung.sales.product.dto.ProductVisibilityCategoryBulkRequest req) {
        return ApiResponse.success(partnerProductVisibilityService.bulkApplyByCategory(req));
    }


    private static final String UPLOAD_COMMON = """

            ### 양식 = **목록 다운로드 파일 그대로**
            별도 양식을 만들지 않았다. `/export`로 받은 파일에서 **값만 고쳐 다시 올리면 된다**.
            · **헤더 이름으로 읽는다** — 열 순서를 바꾸거나 메모 열을 끼워 넣어도 된다.
              위치로 읽으면 열 하나만 밀려도 정가 자리에 수량이 조용히 들어간다.
            · 모르는 열은 무시한다. **필수 열이 없으면 파일 전체를 거부**한다 —
              양식이 틀린 파일을 행 단위 오류로 흘리면 수백 줄의 같은 오류를 보고서야 알게 된다.
            · Y/N 칸은 `Y·예·O·1` / `N·아니오·X·0`을 받는다. 알 수 없는 값은 **오류**다
              ("Yes"를 조용히 false로 읽으면 끈 적 없는 항목이 꺼진다).

            ### 결과
            행마다 `CREATED / UPDATED / ERROR` + 엑셀 행번호를 돌려준다.
            **한 행이 틀려도 나머지는 들어간다** — 수백 줄에서 한 줄 오타로 전부 되돌리면
            담당자는 고칠 곳을 못 찾은 채 처음부터 다시 해야 한다.
            ‼️`created`/`updated` 숫자를 볼 것. 고치려고 올렸는데 전부 신규면 **코드가 안 맞은 것**이다.""";

    @Operation(summary = "도서 기본정보 엑셀 업로드(33p 탭1)",
            description = "도서 마스터를 일괄 등록·수정한다. **도서코드가 키** — 있으면 고치고 없으면 만든다.\n\n"
                    + "★**빈 칸은 '지우기'가 아니라 '그대로'다.** 담당자는 고칠 열만 채워 올린다. "
                    + "빈 칸을 지움으로 처리하면 정가만 고치려던 사람이 분류·공급률을 통째로 날린다."
                    + UPLOAD_COMMON)
    @PostMapping(value = "/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<com.daesung.sales.product.dto.MasterUploadResponse> uploadProducts(
            @io.swagger.v3.oas.annotations.Parameter(description = "도서목록 xlsx", required = true)
            @org.springframework.web.bind.annotation.RequestPart("file")
            org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success(uploadService.uploadProducts(file));
    }

    @Operation(summary = "세트구성(BOM) 엑셀 업로드(33p 탭2)",
            description = "세트별 구성품을 일괄 등록한다. 컬럼: **세트도서코드·구성도서코드·소요수량** "
                    + "(선택: 구성회차·시행예정일·분리포장·자재구분).\n\n"
                    + "★한 세트의 구성품이 **여러 행**으로 오고, 등록은 **세트 단위 전량 교체**다. "
                    + "그래서 **한 세트의 구성품 중 하나라도 틀리면 그 세트는 통째로 건너뛴다** — "
                    + "일부만 넣으면 구성품이 빠진 반쪽 BOM이 만들어지고, 그 세트로 조립하면 자재가 안 빠진다. "
                    + "다른 세트는 영향받지 않는다."
                    + UPLOAD_COMMON)
    @PostMapping(value = "/bom/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<com.daesung.sales.product.dto.MasterUploadResponse> uploadBom(
            @io.swagger.v3.oas.annotations.Parameter(description = "세트구성 xlsx", required = true)
            @org.springframework.web.bind.annotation.RequestPart("file")
            org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success(uploadService.uploadBom(file));
    }


    @Operation(summary = "분류코드 선택 목록(32p 도서관리)",
            description = """
                    도서 등록·수정 화면의 **분류코드 콤보박스**에 쓸 목록.
                    이미 쓰이고 있는 `분류코드 + 분류명` 조합을 사용 건수와 함께 준다.

                    ### ★분류 마스터가 아니다
                    정본(1탭 32p)은 분류코드·분류명을 **도서 기본정보의 입력 항목**으로 둔다 —
                    분류를 따로 등록·관리하는 화면이 없다. 레거시도 도서 테이블에 직접 넣었다.
                    그래서 이 목록은 **실제 사용 중인 값을 모아 보여주는 것**이고,
                    새 분류는 여전히 직접 입력할 수 있다.

                    ### 왜 필요한가
                    형식 검증(`[영문1][연도4][영문·숫자1~3]`)은 **오타를 못 잡는다.**
                    `K202601`을 `K202602`로 잘못 치면 형식은 통과하고, 리포트에 없던 분류가
                    한 줄 더 생겨 매출이 조용히 쪼개진다. 고르게 하면 그 경로가 줄어든다.

                    ### 읽는 법
                    · 같은 코드에 분류명이 갈려 있으면 **여러 줄로 나온다** — 숨기지 않는다.
                      건수 1짜리가 비슷한 이름 옆에 있으면 오타를 의심할 자리다.
                    · 사용여부가 꺼진 도서의 분류도 포함한다. 과거 매출이 그 분류를 가리켜
                      리포트에는 계속 나오는데, 목록에서 빼면 담당자가 "없는 분류"로 알고 또 만든다.""")
    @GetMapping("/categories")
    public ApiResponse<java.util.List<com.daesung.sales.product.dto.ProductCategoryRow>> categories() {
        return ApiResponse.success(productService.usedCategories());
    }

}
