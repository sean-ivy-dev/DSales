package com.daesung.sales.product.controller;

import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.dealer.dto.DealerStockCutoverSettingResponse;
import com.daesung.sales.dealer.dto.DealerStockCutoverSettingUpdateRequest;
import com.daesung.sales.dealer.service.DealerStockService;
import com.daesung.sales.product.dto.PartnerProductStockSeedBulkRequest;
import com.daesung.sales.product.dto.PartnerProductStockSeedResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [관리자] 특약점 재고현황 이관 베이스라인 시딩. 근거: 11/05 회신 §4.
 *
 * <p>★<b>{@code /dealer-site/stock}과 다른 경로다.</b> 그쪽은 특약점 사이트가 쓰는 경로라
 * {@code X-Dealer-Site-Key}로 인증하고 노출 판정을 건다. 여기는 <b>우리 쪽이 운영 DB에
 * 과거 데이터를 이관</b>할 때만 쓰는 관리자 전용 경로라 일반 JWT 인증을 쓰고, 노출 판정을
 * 보지 않는다(이관되는 값은 지금 노출 설정과 무관한 과거 사실이다).
 */
@Tag(name = "재고현황 · 이관", description = "특약점 재고현황 베이스라인 시딩(관리자 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/masters/partner-product-stock")
public class PartnerProductStockController {

    private final DealerStockService dealerStockService;

    @Operation(summary = "재고현황 이관 베이스라인 일괄 시딩",
            description = "DSRE 등 레거시 매입·반품·재고 누적값을 partner_product_stock 이관 "
                    + "시작값으로 일괄 적용한다. 노출 판정을 보지 않는다(관리자 전용).")
    @PostMapping("/seed-baseline")
    public ApiResponse<PartnerProductStockSeedResult> seedBaseline(
            @Valid @RequestBody PartnerProductStockSeedBulkRequest req) {
        return ApiResponse.success(dealerStockService.seedBaselineBulk(req.items()));
    }

    @Operation(summary = "재고현황 매입·반품 자동집계 전환일 조회",
            description = "DB 설정값(V117) — null이면 아직 전환 전(지금처럼 수기입력).")
    @GetMapping("/cutover-date")
    public ApiResponse<DealerStockCutoverSettingResponse> cutoverDate() {
        return ApiResponse.success(dealerStockService.getCutoverSetting());
    }

    @Operation(summary = "재고현황 매입·반품 자동집계 전환일 변경",
            description = "즉시 적용된다 — 배포·재시작 불필요(11/05 §4). cutoverDate를 null로 "
                    + "보내면 전환을 끄고 현행 수기입력으로 되돌린다.")
    @PutMapping("/cutover-date")
    public ApiResponse<DealerStockCutoverSettingResponse> updateCutoverDate(
            @RequestBody DealerStockCutoverSettingUpdateRequest req) {
        return ApiResponse.success(dealerStockService.updateCutoverSetting(req.cutoverDate()));
    }
}
