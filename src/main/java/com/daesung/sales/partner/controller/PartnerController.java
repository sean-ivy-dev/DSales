package com.daesung.sales.partner.controller;

import com.daesung.sales.common.dto.PageRequestDto;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.common.excel.ExcelExportUtil;
import com.daesung.sales.common.excel.ExcelExportUtil.Col;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.partner.dto.CollateralExpiryResponse;
import com.daesung.sales.partner.dto.PartnerCreateRequest;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import com.daesung.sales.partner.dto.PartnerResponse;
import com.daesung.sales.partner.dto.PartnerUpdateRequest;
import com.daesung.sales.partner.service.PartnerService;
import com.daesung.sales.partner.service.PartnerUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import java.time.LocalDate;
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

/** 기초관리 - 거래처 관리. 실제 경로: /api/v1/masters/clients. */
@Tag(name = "기초관리 · 거래처", description = "거래처 마스터 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/masters/clients")
public class PartnerController {

    private final PartnerService partnerService;
    private final PartnerUploadService partnerUploadService;
    private final com.daesung.sales.product.service.PartnerProductVisibilityService partnerProductVisibilityService;
    private final ExcelExportUtil excel;
    /**
     * DSRE 코드 동기화. ‼️<b>선택 주입</b>이다 — DSRE 연동이 꺼져 있으면 빈이 없다.
     * 필수로 두면 연동을 끈 환경에서 <b>거래처 화면 전체</b>가 못 뜬다.
     */
    private final org.springframework.beans.factory.ObjectProvider<
            com.daesung.sales.partner.service.DsrePartnerCodeSyncService> dsreCodeSyncProvider;

    @Operation(summary = "거래처 목록 조회",
            description = """
                    keyword(코드/명 부분일치)로 검색, 페이징·정렬 지원.
                    **기본은 거래중인 거래처만** 보여준다(만료일이 없는 건).
                    includeExpired=true면 만료된 거래처까지 포함 — 화면의 '만료된 거래처 포함' 체크박스.""")
    @GetMapping
    public ApiResponse<PageResponse<PartnerResponse>> list(
            @Parameter(description = "검색어(거래처코드 또는 거래처명 부분일치)") @RequestParam(required = false) String keyword,
            @Parameter(description = "만료된 거래처 포함(기본 false)")
            @RequestParam(required = false, defaultValue = "false") boolean includeExpired,
            @ParameterObject PageRequestDto pageReq) {
        return ApiResponse.success(partnerService.findAll(keyword, includeExpired, pageReq.toPageable()));
    }

    @Operation(summary = "거래처코드 일괄 조회",
            description = """
                    거래처**코드 여러 개**를 한 번에 id 로 바꿔 준다.
                    근거: 2026-09-28 화면 정리안 — 조회화면 좌측 거래처 목록에서
                    "**거래처코드 붙여넣기로 일괄 선택**".

                    ★이게 없으면 화면이 코드 하나마다 검색을 한 번씩 돌려야 한다
                    (50개를 붙여넣으면 50번). 조회 조건은 id 로만 받으므로 변환이 꼭 필요하다.

                    · 만료된 거래처도 포함해 돌려준다 — 과거 자료를 뽑을 때 붙여넣는 코드에
                      만료 거래처가 섞여 있는데, 조용히 빠지면 담당자는 그 거래처 매출이
                      없는 줄 안다. 만료 여부는 `endDate` 로 화면에서 구분한다.
                    · **없는 코드는 그냥 빠진다.** 어느 코드가 빠졌는지는 화면이 요청한 목록과
                      대조하면 알 수 있다(우리가 빈 껍데기를 만들어 주면 그게 실재하는 거래처처럼 보인다).""")
    @GetMapping("/by-codes")
    public ApiResponse<List<PartnerResponse>> byCodes(
            @Parameter(description = "거래처코드 목록 — 예: `codes=A001,A002,A003`", required = true)
            @RequestParam List<String> codes) {
        return ApiResponse.success(partnerService.findByCodes(codes));
    }

    @Operation(summary = "거래처 목록 엑셀 다운로드", description = "30p 거래처관리 컬럼 전체. 검색조건 그대로.")
    @GetMapping("/export")
    public ResponseEntity<byte[]> listExport(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean includeExpired) {
        List<Col> cols = List.of(
                new Col("거래처코드", "code"), new Col("거래처명", "name"), new Col("신고명", "name1"),
                new Col("사업자번호", "bizNo"), new Col("업종", "bizStatus"), new Col("업태", "bizItem"),
                new Col("사업자명", "bossName"), new Col("사업자주민번호", "bossId"),
                new Col("연락처1", "tel1"), new Col("연락처2", "tel2"),
                new Col("휴대폰번호", "cellPhone"), new Col("팩스번호", "fax"),
                new Col("이메일1", "email1"), new Col("이메일2", "email2"),
                new Col("우편번호", "zip"), new Col("기본주소", "addr1"), new Col("상세주소", "addr2"),
                new Col("등록일", "startDate"), new Col("만료일", "endDate"),
                new Col("도시명", "cityName"), new Col("지역", "region"), new Col("관할지역", "zone2"),
                new Col("거래처구분", "clientCategory"),
                new Col("담보금액", "assureAmount"), new Col("담보만기", "assureExpiry"),
                new Col("담보내용", "assureNote"));
        byte[] xlsx = excel.toXlsx("거래처목록", cols,
                partnerService.findAll(keyword, includeExpired, PageRequest.of(0, 100000)).getContent());
        return excel.asDownload(xlsx, "거래처목록.xlsx");
    }

    @Operation(summary = "담보만기 알림 엑셀 다운로드", description = "만기 임박/만료 거래처(남은일수·상태).")
    @GetMapping("/collateral-expiry/export")
    public ResponseEntity<byte[]> collateralExpiryExport(
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate asOf,
            @RequestParam(defaultValue = "30") int withinDays) {
        List<Col> cols = List.of(
                new Col("거래처코드", "code"), new Col("거래처명", "name"),
                new Col("담보만기일", "assureExpiry"), new Col("담보금액", "assureAmount"),
                new Col("남은일수", "daysUntilExpiry"), new Col("상태", "status"));
        byte[] xlsx = excel.toXlsx("담보만기", cols, partnerService.collateralExpiry(asOf, withinDays).rows(),
                com.daesung.sales.common.excel.ExcelExportUtil.Heading.asOf("담보 만기 임박", asOf));
        return excel.asDownload(xlsx, "담보만기알림.xlsx");
    }

    @Operation(summary = "담보 만기 알림",
            description = "기준일(asOf, 미지정=오늘) 대비 담보 만기일이 withinDays(기본 30) 이내이거나 이미 만료된 "
                    + "거래처 목록. 만기일 오름차순 + 남은 일수 + 상태(EXPIRED/IMMINENT). 만기 1개월 전 팝업용.")
    @GetMapping("/collateral-expiry")
    public ApiResponse<CollateralExpiryResponse> collateralExpiry(
            @Parameter(description = "기준일(yyyy-MM-dd, 미지정 시 오늘)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @Parameter(description = "임박 판정 일수(기본 30)") @RequestParam(defaultValue = "30") int withinDays) {
        return ApiResponse.success(partnerService.collateralExpiry(asOf, withinDays));
    }

    @Operation(summary = "거래처 상세 조회", description = "id로 단건 조회. 없으면 404")
    @GetMapping("/{id}")
    public ApiResponse<PartnerResponse> get(@PathVariable Long id) {
        return ApiResponse.success(partnerService.findById(id));
    }

    @Operation(summary = "거래처 사용 중지", description = """
            거래처를 **지우지 않고 만료 처리**한다(만료일=오늘). 기본 조회에서 빠지고
            `includeExpired=true` 로만 보인다.

            ★물리삭제는 하지 않는다 — 과거 매출·재고·채권이 전부 `partner_id` 로 물려 있어
            (REFERENCES 9곳·컬럼 20곳) 지우면 그 장부가 끊긴다.
            ‼️이미 만료된 거래처는 날짜를 덮어쓰지 않는다 — 언제부터 안 쓰게 됐는지가 기록이다.
            되살리려면 수정(PUT)에서 만료일을 비운다.""")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> discontinue(@PathVariable Long id) {
        partnerService.discontinue(id);
        return ApiResponse.success(null);
    }

    @Operation(summary = "거래처 등록", description = "거래처코드 중복 시 400 반환")
    @PostMapping
    public ApiResponse<PartnerResponse> create(@Valid @RequestBody PartnerCreateRequest req) {
        return ApiResponse.success(partnerService.create(req));
    }

    @Operation(summary = "거래처 엑셀 업로드",
            description = """
                    거래처를 **여러 건 한 번에** 등록·수정한다. 초기 적재용.

                    - **양식 = 거래처 목록 다운로드 파일 그대로**(`GET /masters/clients/export`).
                      헤더 **이름**으로 읽으므로 열 순서가 달라도, 메모 열이 붙어 있어도 된다.
                    - **거래처코드가 키** — 있으면 고치고 없으면 만든다.
                    - **빈 칸은 그대로**(기존 값 유지). 고칠 열만 채워 올리면 된다.
                    - **한 행이 틀려도 나머지는 들어간다.** 행별 결과·사유를 돌려준다.
                      단 거래처코드 열이 없는 파일은 양식이 틀린 것이라 파일 전체를 거부한다.
                    - 등록 화면과 달리 **사업자번호 형식은 검증하지 않는다** — 초기 적재 원본에는
                      빈 값·옛 표기가 섞여 있고, 그 행을 거부하면 한 건도 못 올린다.""")
    @PostMapping(value = "/upload", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<com.daesung.sales.product.dto.MasterUploadResponse> upload(
            @io.swagger.v3.oas.annotations.Parameter(description = "거래처 엑셀(.xlsx)")
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success(partnerUploadService.upload(file));
    }

    @Operation(summary = "거래처 수정", description = "코드는 불변. 없으면 404")
    @PutMapping("/{id}")
    public ApiResponse<PartnerResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody PartnerUpdateRequest req) {
        return ApiResponse.success(partnerService.update(id, req));
    }

    @Operation(summary = "코드값 목록(거래처 등록 화면 선택용)",
            description = """
                    거래처 등록·수정 화면의 **셀렉트 채우기**용. 한 번에 네 축을 준다.

                    | 축 | 성격 | 값 |
                    |---|---|---|
                    | `clientCategories` | **고정** | 특약점·기타학원·B2B·대성·자사몰 (정본 확정) |
                    | `regions` | 열림 | 쓰이는 값 + 건수. 새 값 입력 가능 |
                    | `zones` | 열림 | 관할지역. region과 **별개 축**이다(정본 4탭에 둘 다 있음) |

                    ### 고정과 열림을 왜 나누나
                    거래처구분은 정본이 5값으로 못 박았다 — 서버가 그 밖의 값을 **400으로 거부**한다.
                    지역은 정해진 목록이 없어 막을 수 없다. 대신 쓰이는 값을 보여줘 고르게 하고,
                    새 지역은 그대로 입력할 수 있게 둔다(분류코드와 같은 방식).

                    ### 읽는 법
                    `usedCount`가 1인 값이 비슷한 이름 옆에 있으면 **오타를 의심할 자리**다 —
                    `경남`과 `경상남도`가 섞이면 필터에서 한쪽이 통째로 빠진다.""")
    @GetMapping("/codes")
    public ApiResponse<java.util.Map<String, Object>> codes() {
        return ApiResponse.success(java.util.Map.of(
                "clientCategories", com.daesung.sales.common.code.MasterCodes.CLIENT_CATEGORY,
                "regions", partnerService.usedRegions(),
                "zones", partnerService.usedZones()));
    }

    @Operation(summary = "DSRE 거래처코드 동기화",
            description = """
                    운영 DSRE2 `tbl_cust_info` 를 읽어 거래처마다 **DSRE 코드**(`CUST_CD`)를 채운다.
                    매칭키는 `MACHUL_CD` = 우리 거래처코드다.

                    ### 왜 필요한가
                    우리 거래처코드는 DSLab 매출코드(`A10A195` 꼴)인데,
                    **학교 마스터와 발주처 양식은 DSRE 코드(`10003` 꼴)를 쓴다.**
                    두 체계가 달라 `schools.cust_code = partners.code` 조인이 한 건도 안 붙었고,
                    그래서 학교/학원검색의 거래처명1·지역명이 빈 칸이었다.
                    PDF 파일명(`RESULT_10003_강남 하람도서_…`)도 같은 이유로 못 만들었다.

                    ### 규율
                    · DSRE 는 **읽기만** 한다. 쓰는 것은 우리 `partners.dsre_code` 하나다.
                    · DSRE 에서 사라진 거래처의 코드도 **지우지 않는다** — 지우면 그 거래처의
                      학교가 다시 빈 칸이 되는데 사라진 이유를 우리가 모른다.
                    · 같은 DSRE 코드를 여럿이 쓰면 **막지 않고 목록으로 보고**한다
                      (`duplicatedDsreCodes`). 막으면 동기화 전체가 실패한다.

                    여러 번 돌려도 안전하다(같은 값이면 `unchanged`).
                    DSRE 연동(`daesung.dsre.enabled=true`) 필요.""")
    @PostMapping("/sync-dsre-code")
    public ApiResponse<com.daesung.sales.partner.dto.DsreCodeSyncResult> syncDsreCode() {
        var svc = dsreCodeSyncProvider.getIfAvailable();
        if (svc == null) {
            throw new com.daesung.sales.common.exception.BusinessException(
                    com.daesung.sales.common.exception.ErrorCode.INVALID_INPUT,
                    "DSRE 연동이 꺼져 있습니다(daesung.dsre.enabled). 서버 설정을 확인하세요.");
        }
        return ApiResponse.success(svc.sync());
    }

    @Operation(summary = "★도서별 노출(거래처×도서 예외)", description = """
            [거래처관리] 거래처 상세 → '도서별 노출' 화면. 근거: 10/31 클라이언트 회신 §1-4.

            목록은 **분류코드→도서코드 순**이다(화면에서 분류명으로 묶어 트리로 그린다).
            각 줄의 `visible`은 노출 판단 순서(①도서Web게시 ②이 거래처의 예외 ③거래처×대분류
            매핑 ④매핑없음)를 **전부 적용한 현재 값**이다 — 체크박스에 그대로 쓰면 된다.
            `exception=true`면 그 값이 예외로 저장된 것이다(화면의 '예외' 배지).
            `productWebVisible=false`면 체크박스를 비활성화한다(①이 예외보다 우선이라
            체크해도 안 바뀐다).""")
    @GetMapping("/{partnerId}/product-visibility")
    public ApiResponse<PageResponse<com.daesung.sales.product.dto.PartnerProductVisibilityRow>> productVisibility(
            @PathVariable Long partnerId,
            @Parameter(description = "도서코드·도서명 부분일치") @RequestParam(required = false) String keyword,
            @ParameterObject PageRequestDto page) {
        return ApiResponse.success(PageResponse.of(
                partnerProductVisibilityService.list(partnerId, keyword, page.toPageable())));
    }

    @Operation(summary = "도서별 노출 — 체크박스 변경", description = """
            열기/닫기를 저장한다. **③④(거래처×대분류 매핑)로 계산한 기본값과 같아지면
            예외 행을 지운다**(10/31 §1-4) — 예외는 "기본값과 다른 값"일 때만 의미가 있다.
            변경 이력(누가·언제·열기/닫기)이 남는다.""")
    @PutMapping("/{partnerId}/product-visibility/{productId}")
    public ApiResponse<Void> setProductVisibility(
            @PathVariable Long partnerId, @PathVariable Long productId,
            @RequestBody com.daesung.sales.product.dto.ProductVisibilityRequest req) {
        partnerProductVisibilityService.set(partnerId, productId, req.visible(), req.reason());
        return ApiResponse.success(null);
    }

    @Operation(summary = "도서별 노출 — 기본값으로 되돌리기",
            description = "예외 행을 지운다. 그 뒤로는 거래처×대분류 매핑(③④)을 그대로 따른다.")
    @DeleteMapping("/{partnerId}/product-visibility/{productId}")
    public ApiResponse<Void> resetProductVisibility(
            @PathVariable Long partnerId, @PathVariable Long productId,
            @RequestParam(required = false) String reason) {
        partnerProductVisibilityService.reset(partnerId, productId, reason);
        return ApiResponse.success(null);
    }
}
