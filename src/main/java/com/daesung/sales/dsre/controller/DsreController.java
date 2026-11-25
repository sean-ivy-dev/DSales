package com.daesung.sales.dsre.controller;

import com.daesung.sales.common.response.ApiResponse;
import com.daesung.sales.dsre.gateway.DsreGateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** DSRE2 연동 진단(1단계 검증용). daesung.dsre.enabled=true일 때만. 물류비 등은 게이트웨이를 내부 사용. */
@Tag(name = "DSRE2 연동", description = "DSRE2(기존 라이브 유지) 게이트웨이 진단")
@RestController
@RequestMapping("/dsre")
@ConditionalOnProperty(name = "daesung.dsre.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DsreController {

    private final DsreGateway dsreGateway;

    @Operation(summary = "DSRE 자료 기준 시각",
            description = """
                    사본이 **언제 기준인지** 내려준다. 화면 위쪽에 「DSRE 자료 기준 : …」 으로
                    그대로 쓰면 된다(프론트 요청 2026-11-25).

                    ‼️**왜 필요한가** — 배포 서버는 운영 DSRE 에 직접 닿지 못해 **사본**을 읽는데,
                    그 사본이 **10/26 에서 11/24 까지 멈춰** 있었고 아무도 몰랐다.
                    그 사이 10월 물류 작업비가 2,803,640원 적게 나왔고, 매출일괄등록도
                    DSRE 에서 바꾼 내역이 반영되지 않았다.

                    · `dataAsOf` — 사본 안 **자재 수량의 최신 등록시각**. 화면에 쓸 값
                    · `daysBehind` — 며칠 전 자료인지(0이면 오늘). 사본이 비면 −1
                    · `stale` — 2일 이상 밀렸으면 참. 참이면 눈에 띄게 표시해 주면 좋다
                    · `materialRows`·`requestRows` — 갱신이 통째로 실패했는지 보는 값

                    ★`dataAsOf` 는 **갱신한 시각이 아니라 자료의 끝**이다. 갱신을 돌려도
                    운영 DSRE 가 비어 있으면 자료는 늘지 않으므로, 담당자가 알아야 하는 것은
                    자료가 어디까지 들어왔는지다.""")
    @GetMapping("/snapshot")
    public ApiResponse<com.daesung.sales.dsre.gateway.DsreSnapshot> snapshot() {
        return ApiResponse.success(dsreGateway.snapshot());
    }

    @Operation(summary = "신청인원 조회(FUNC_REQINWON_GET)",
            description = "DSRE2 저장함수를 호출해 신청 REQ_CD의 인원을 산출. 물류비 인원기준 계산의 기반.")
    @GetMapping("/req-inwon")
    public ApiResponse<Integer> reqInwon(
            @Parameter(description = "신청번호(REQ_CD)", example = "1001") @RequestParam int reqCd) {
        return ApiResponse.success(dsreGateway.reqInwon(reqCd));
    }
}
