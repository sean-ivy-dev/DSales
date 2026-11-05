package com.daesung.sales.warehouse.controller;

import com.daesung.sales.common.dto.PageRequestDto;
import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.warehouse.dto.WarehouseCreateRequest;
import com.daesung.sales.warehouse.dto.WarehouseResponse;
import com.daesung.sales.warehouse.dto.WarehouseUpdateRequest;
import com.daesung.sales.warehouse.service.WarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 기초관리 - 창고 관리. 실제 경로: /api/v1/masters/warehouses. */
@Tag(name = "기초관리 · 창고", description = "창고 마스터 관리 (물류/위탁)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/masters/warehouses")
public class WarehouseController {

    private final WarehouseService warehouseService;
    private final com.daesung.sales.common.excel.ExcelExportUtil excel;

    @Operation(summary = "창고 목록 조회",
            description = "keyword(코드/명 부분일치)로 검색, 페이징·정렬 지원. "
                    + "기본으로 사용 중지한 창고는 제외한다(includeUnused=true로 포함).")
    @GetMapping
    public ApiResponse<PageResponse<WarehouseResponse>> list(
            @Parameter(description = "검색어(창고코드 또는 창고명 부분일치)") @RequestParam(required = false) String keyword,
            @Parameter(description = "사용 중지한 창고도 포함할지. 기본 false(제외) — "
                    + "중지한 창고가 출고 창고 선택지에 계속 뜨던 것을 막는다.")
            @RequestParam(defaultValue = "false") boolean includeUnused,
            @ParameterObject PageRequestDto pageReq) {
        return ApiResponse.success(
                warehouseService.findAll(keyword, includeUnused, pageReq.toPageable()));
    }

    @Operation(summary = "창고 목록 엑셀 다운로드", description = "검색조건 전체를 xlsx로.")
    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<byte[]> listExport(@RequestParam(required = false) String keyword) {
        var cols = java.util.List.of(
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("창고코드", "code"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("창고명", "name"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("유형", "type"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("실물재고여부", "physicalStock"),
                new com.daesung.sales.common.excel.ExcelExportUtil.Col("소속거래처", "ownerClientName"));
        byte[] xlsx = excel.toXlsx("창고목록", cols,
                warehouseService.findAll(keyword, org.springframework.data.domain.PageRequest.of(0, 100000)).getContent());
        return excel.asDownload(xlsx, "창고목록.xlsx");
    }

    @Operation(summary = "창고 상세 조회", description = "id로 단건 조회. 없으면 404")
    @GetMapping("/{id}")
    public ApiResponse<WarehouseResponse> get(@PathVariable Long id) {
        return ApiResponse.success(warehouseService.findById(id));
    }

    @Operation(summary = "창고 등록", description = "창고코드 중복 시 400 반환")
    @PostMapping
    public ApiResponse<WarehouseResponse> create(@Valid @RequestBody WarehouseCreateRequest req) {
        return ApiResponse.success(warehouseService.create(req));
    }

    @Operation(summary = "창고 수정", description = "코드는 불변. 없으면 404")
    @PutMapping("/{id}")
    public ApiResponse<WarehouseResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody WarehouseUpdateRequest req) {
        return ApiResponse.success(warehouseService.update(id, req));
    }

    @Operation(summary = "창고 사용 중지", description = """
            창고를 **지우지 않고 미사용 처리**한다(`useYn=false`). 목록과 선택지에서 빠진다.

            ★물리삭제는 하지 않는다 — 과거 재고 이벤트(`inventory_txn`)가 창고를 가리키고 있어
            지우면 제품수불부가 "어느 창고였는지 모르는" 행을 갖게 된다.
            ‼️**재고가 남아 있으면 400**이다. 물건이 있는 창고를 목록에서 치우면 그 재고를
            아무도 못 찾는다 — 먼저 이고로 비워야 한다.
            되살리려면 수정(PUT)에서 사용여부를 켠다.""")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> discontinue(@PathVariable Long id) {
        warehouseService.discontinue(id);
        return ApiResponse.success(null);
    }
}
