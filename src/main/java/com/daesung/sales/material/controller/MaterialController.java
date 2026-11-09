package com.daesung.sales.material.controller;

import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.material.dto.MaterialBomRequest;
import com.daesung.sales.material.dto.MaterialBomResponse;
import com.daesung.sales.material.dto.MaterialRequest;
import com.daesung.sales.material.dto.MaterialResponse;
import com.daesung.sales.material.service.MaterialBomService;
import com.daesung.sales.material.service.MaterialService;
import com.daesung.sales.product.entity.MaterialType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 기초관리 · 자재 마스터 + 세트 자재 매칭(33p 도서관리 세트구성 탭).
 * 근거: 발주처 「도서관리·제품수불부현황 데이터 구조 보완 요청안」(2026-08-31).
 */
@Tag(name = "기초관리 · 자재",
        description = "자재 마스터(33p) + 세트·회차 ↔ 자재 매칭. 범용 자재는 여러 세트에 중복 매칭된다")
@RestController
@RequestMapping("/masters")
@RequiredArgsConstructor
public class MaterialController {

    private final MaterialService materialService;
    private final MaterialBomService materialBomService;

    @Operation(summary = "자재 목록(33p)",
            description = """
                    자재구분·사용여부·검색어(자재코드/자재명)로 좁힌다.

                    ★자재는 **상품이 아니다** — 팔지 않으므로 정가·공급률이 없다.
                    별도 목록으로 관리하고 세트의 구성회차에 **선택 매칭**한다.""")
    @GetMapping("/materials")
    public ApiResponse<List<MaterialResponse>> list(
            @Parameter(description = "자재구분 필터") @RequestParam(required = false) MaterialType materialType,
            @Parameter(description = "사용여부 필터") @RequestParam(required = false) Boolean useYn,
            @Parameter(description = "검색어(자재코드·자재명)") @RequestParam(required = false) String keyword) {
        return ApiResponse.success(materialService.search(materialType, useYn, keyword));
    }

    @Operation(summary = "자재 등록",
            description = """
                    자재구분을 정확히 선택할 것 — **물류 작업비 단가를 고르는 키**다(발주처 강조).

                    ★**자재코드는 보내지 않는다** — 서버가 `100001`부터 자동으로 매긴다
                    (10/27 피드백 §11-3 확정, PPT23 "자재코드는 자동으로 생성 필요").
                    화면에서 입력란을 없애고, 등록 응답의 `code`를 보여 주면 된다.
                    옛 화면이 `code`를 보내도 오류는 나지 않는다(무시된다).""")
    @PostMapping("/materials")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MaterialResponse> create(@Valid @RequestBody MaterialRequest req) {
        return ApiResponse.success(materialService.create(req));
    }

    @Operation(summary = "자재 수정", description = "자재코드는 바뀌지 않는다(매칭이 참조하는 식별자).")
    @PutMapping("/materials/{id}")
    public ApiResponse<MaterialResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody MaterialRequest req) {
        return ApiResponse.success(materialService.update(id, req));
    }

    @Operation(summary = "자재 삭제(논리)",
            description = """
                    ★**세트 구성에 매칭돼 있으면 거부**한다. 지우면 그 세트의 소요수량이
                    조용히 사라져 물류 작업비가 줄어든다.
                    더 안 쓸 자재는 `useYn=false`로 두면 목록에서만 빠지고 기존 매칭은 남는다.""")
    @DeleteMapping("/materials/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        materialService.delete(id, materialBomService.matchedCount(id));
        return ApiResponse.success(null);
    }

    // ── 세트 ↔ 자재 매칭 ────────────────────────────────────────────────────────

    @Operation(summary = "자재 매칭 전체 조회(세트 가로지름)",
            description = """
                    **전 세트**의 자재 매칭을 한 줄씩 낸다. 세트 하나씩 보는
                    `GET /masters/products/{setProductId}/materials` 로는 "자재가 어디에 몇 개
                    걸려 있나"를 볼 수 없어 신설했다(상품이 14,811개라 순회가 불가능하다).

                    · `commonOnly=true` → **공통(회차 미지정)** 매칭만.
                      F-37 에서 발주처가 "지금 「공통(회차 0)」으로 연결된 건을 새 구조로
                      어떻게 옮길지" 회신을 요구한 그 대상이다.
                    · 정렬: 세트코드 → 회차(공통 먼저) → 자재코드.""")
    @GetMapping("/material-boms")
    public ApiResponse<java.util.List<com.daesung.sales.material.dto.MaterialBomFlatRow>> allBoms(
            @Parameter(description = "공통(회차 미지정) 매칭만 보기. 기본 false=전체")
            @RequestParam(required = false, defaultValue = "false") boolean commonOnly) {
        return ApiResponse.success(materialBomService.listAll(commonOnly));
    }

    @Operation(summary = "세트 자재 매칭 조회(33p 구성회차별 매칭 결과)",
            description = "공통(회차 미지정) 먼저, 그다음 회차순.")
    @GetMapping("/products/{setProductId}/materials")
    public ApiResponse<MaterialBomResponse> boms(@PathVariable Long setProductId) {
        return ApiResponse.success(materialBomService.list(setProductId));
    }

    @Operation(summary = "세트 재고 + 조립 가능 수량",
            description = """
                    ★★**의미가 정정됐다(F-37, 발주처 확정 2026-11-09).** 숫자 **두 개**를 함께 낸다.

                    | 필드 | 뜻 |
                    |---|---|
                    | `setStock` | **지금 조립돼 있는 세트 재고.** 조립 +, 해체 −, 세트 출고 − |
                    | `assemblableSets` | **자재로 더 만들 수 있는 세트 수** = 회차별 가능수량의 **최소값** |

                    ‼️10/22 기준 "세트 재고 = 구성 회차별 시험지 재고의 최소값"은 **폐기됐다.**
                    새 기준은 "재고는 **자재 낱장이 기준 원장**, SET 재고는 **조립·해체로 증감**"이다.
                    옛 `availableSets` 가 계산한 값은 후자(조립 가능 수량)였다 — 계산이 아니라
                    **그것을 '세트 재고'라고 부른 것**이 틀렸다. 화면 안내문도 함께 고쳐야 한다.

                    · 최소값인 이유: 세트는 회차 시험지를 모아 만드는 것이라 **가장 모자란 회차**가
                      만들 수 있는 수를 정한다(100·120·105·111 → 100).
                    · **시험지만** 본다 — OMR·라벨까지 넣으면 라벨이 모자라 세트가 0으로 보인다.
                    · 회차별 줄의 `standaloneQty` 가 **회차 단독 가능 부수**다(회차당 소요로 나눈 값).
                      공통 반복형은 세트당÷회차수, 1회형(쿠폰)은 0이다.
                    · 음수 재고는 음수로 낸다 — 0으로 올리면 모자란 사실이 묻힌다.
                    · 구성이 없으면 `assemblableSets` 는 **null**(0은 "못 만든다", null 은 "구성이 없다").""")
    @GetMapping("/products/{setProductId}/set-stock")
    public ApiResponse<com.daesung.sales.material.dto.SetStockResponse> setStock(
            @PathVariable Long setProductId) {
        return ApiResponse.success(materialBomService.setStock(setProductId));
    }

    @Operation(summary = "세트 자재 매칭 추가",
            description = """
                    회차를 비우면 **공통**(세트 전체에 붙는 범용 자재)이다.
                    같은 (세트·회차·자재) 조합이 이미 있으면 **수량을 갱신**한다.

                    ★소요수량은 세트 내 회차 반복까지 반영한 **최종 수량**이다 —
                    4회차 구성의 OMR은 4, 세트에 한 번뿐인 쿠폰은 1.
                    서버가 회차 수를 곱하지 않는다.""")
    @PostMapping("/products/{setProductId}/materials")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MaterialBomResponse> addBom(@PathVariable Long setProductId,
                                                   @Valid @RequestBody MaterialBomRequest req) {
        return ApiResponse.success(materialBomService.add(setProductId, req));
    }

    @Operation(summary = "세트 자재 매칭 해제")
    @DeleteMapping("/products/{setProductId}/materials/{bomId}")
    public ApiResponse<MaterialBomResponse> removeBom(@PathVariable Long setProductId,
                                                      @PathVariable Long bomId) {
        return ApiResponse.success(materialBomService.remove(setProductId, bomId));
    }
}
