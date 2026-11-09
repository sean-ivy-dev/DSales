package com.daesung.sales.logistics.controller;

import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.logistics.dto.ProductLogisRateRequest;
import com.daesung.sales.logistics.dto.ProductLogisRateResponse;
import com.daesung.sales.logistics.dto.ProductLogisRateUpdateRequest;
import com.daesung.sales.logistics.service.ProductLogisRateService;
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
 * 물류비용등록(36p) — <b>매출프로그램 상품</b>의 물류 단가. 실제 경로: /api/v1/logistics-costs/products.
 *
 * <p>화면의 두 버튼 중 <b>"신규등록"</b>이 여기다. 다른 하나인 <b>"DSRE 상품 가져오기"</b>
 * (예전 이름도 "신규등록")는 DSRE 시행 단가를 다루는 {@code /logistics-costs/rates} 쪽이다.
 *
 * <p>★<b>DSRE가 꺼져 있어도 동작한다.</b> 우리 DB의 단가라 DSRE 연동과 무관하다 —
 * {@code /logistics-costs/rates}가 {@code daesung.dsre.enabled}에 묶여 있는 것과 다르다.
 */
@Tag(name = "기초관리 · 물류비용등록(매출프로그램 상품)",
        description = "상품 + 작업구분 선택 → 단가 자동 적용. DSRE 시행 단가와 별개 축")
@RestController
@RequiredArgsConstructor
@RequestMapping("/logistics-costs/products")
public class ProductLogisRateController {

    private final ProductLogisRateService service;

    @Operation(summary = "상품별 물류단가 목록",
            description = """
                    매출프로그램 상품에 등록된 물류 단가. 상품코드 순으로 낸다 —
                    등록순으로 두면 같은 분류의 상품이 목록 여기저기 흩어져 단가를 비교할 수 없다.

                    · `overridden=true`인 행은 **담당자가 개별 수정한 행**이라 일괄반영이 건너뛴다.
                    · `priceVisible=false`인 행은 **DSRE 병행 상품**이라 여기 단가가 안 쓰일 수 있다.""")
    @GetMapping
    public ApiResponse<List<ProductLogisRateResponse>> list(
            @Parameter(description = "작업구분(PACKTYPE). 미지정=전체") @RequestParam(required = false)
            Integer packType,
            @Parameter(description = "키워드 — 상품코드·상품명을 함께 훑는다(부분일치)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = """
                    상품 연도(미지정=전체). **분류코드 2~5번째**에서 뽑은 값이다
                    (레거시 `substring(catCode,2,4)`). 좌측 연도 트리와 같은 축이다.""")
            @RequestParam(required = false) Integer productYear) {
        return ApiResponse.success(service.search(packType, keyword, productYear));
    }

    @Operation(summary = "좌측 상품 트리(연도 > 분류 > 상품) — 33p",
            description = """
                    물류비용등록 좌측 트리 데이터. 정본이 "트리뷰 상품 선택에 **연도 구분** 추가"를
                    요구하는데 서버가 이 데이터를 준 적이 없었다(F-38).

                    · 연도 축은 **분류코드 2~5번째**다(레거시 `substring(catCode,2,4) desc`).
                      ‼️상품 마스터의 `상품년도` 필드는 **운영 14,811개 중 3개만 채워져 있어** 쓰지 않는다 —
                      그 필드로 묶으면 전 상품이 '미지정' 한 묶음이 된다. 분류코드에서는 100% 뽑힌다.
                    · **3단**인 이유: 상품이 14,811개다(연도 21 → 분류 889 → 상품).
                    · `withRateOnly=true` → 단가가 등록된 상품만. 기본은 전체다 —
                      트리에서 골라 **새로 등록**하는 화면이라 미등록 상품이 보여야 한다.
                    · 사용 중지(`useYn=false`) 상품은 제외한다.
                    · 정렬: 연도 내림차순 → 분류코드 → 상품코드.""")
    @GetMapping("/tree")
    public ApiResponse<java.util.List<com.daesung.sales.logistics.dto.ProductTreeNode>> tree(
            @Parameter(description = "단가 등록분만 보기. 기본 false=전체")
            @RequestParam(required = false, defaultValue = "false") boolean withRateOnly,
            @Parameter(description = "키워드 — 상품코드·상품명·분류코드·분류명(부분일치)")
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(service.tree(withRateOnly, keyword));
    }

    @Operation(summary = "물류단가 신규등록",
            description = """
                    매출프로그램 상품을 불러와 **작업구분을 선택하면 단가가 자동 적용된다**
                    (발주처 회신 §1-10). 요청에 단가를 넣지 않는다 —
                    고른 작업구분의 기준단가가 그대로 복사된다.

                    ★**복사이지 참조가 아니다.** 참조로 두면 기준단가를 고치는 순간 과거 출고의
                    작업비까지 따라 바뀐다(§1-1 "저장된 출고 작업비는 단가 변경에 소급되지 않아야 함").
                    기준단가를 바꾼 뒤 기존 행에도 밀어 넣으려면 작업구분 일괄반영을 쓴다.

                    · 상품 하나에 단가는 **하나**다. 이미 있으면 400 — 수정으로 바꾼다.
                    · DSRE 병행(단가노출 N) 상품이어도 **막지 않고 경고**한다(`warnings`).
                      구분값 필드 구조가 발주처 확인 대기라, 지금 막으면 방향이 뒤집힐 때
                      등록해 둔 데이터가 통째로 묶인다.""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductLogisRateResponse.Result> create(
            @Valid @RequestBody ProductLogisRateRequest req) {
        return ApiResponse.success(service.create(req));
    }

    @Operation(summary = "물류단가 개별 수정",
            description = """
                    **지정한 항목만** 바꾼다 — 라벨만 고치려다 기본작업비가 0으로 덮이면
                    그 상품 물류비가 통째로 틀어진다.

                    ★이 경로로 고친 행은 **예외로 표시**되어 이후 작업구분 일괄반영이 건너뛴다.
                    담당자가 일부러 넣은 값이 일괄반영 한 번에 조용히 덮이면
                    그 상품이 잘못된 단가로 청구된다.

                    · **작업구분만** 바꾸면 새 기준단가로 다시 채우고 **예외 표시를 푼다** —
                      작업구분을 바꿨다는 건 그 기준단가를 쓰겠다는 뜻이다.
                    · 작업구분과 단가를 **같이** 주면 단가 입력값이 이긴다(예외로 남는다).""")
    @PutMapping("/{id}")
    public ApiResponse<ProductLogisRateResponse.Result> update(
            @PathVariable Long id, @Valid @RequestBody ProductLogisRateUpdateRequest req) {
        return ApiResponse.success(service.update(id, req));
    }

    @Operation(summary = "물류단가 삭제(논리삭제)",
            description = "행은 남고 삭제자·시각이 기록된다. 같은 상품으로 다시 등록할 수 있다.")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success(null);
    }
}
