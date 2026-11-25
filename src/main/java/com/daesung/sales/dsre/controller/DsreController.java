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

    @Operation(summary = "DSRE 사본 상태 — 찍은 시각 · 자료 끝",
            description = """
                    사본이 **언제 찍힌 것인지** 내려준다. 화면 위쪽에 「DSRE 자료 기준 : …」 으로
                    쓰면 된다(프론트 요청 2026-11-25).

                    ‼️**왜 필요한가** — 배포 서버는 운영 DSRE 에 직접 닿지 못해 **사본**을 읽는데,
                    그 사본이 **10/26 에서 11/24 까지 멈춰** 있었고 아무도 몰랐다.
                    그 사이 10월 물류 작업비가 2,803,640원 적게 나왔고, 매출일괄등록도
                    DSRE 에서 바꾼 내역이 반영되지 않았다.

                    ### ★★두 시각을 구분해 쓸 것
                    · `refreshedAt` — **사본을 찍은 시각**. 「갱신이 밀렸나」는 **이것**으로 본다
                    · `dataAsOf` — 사본 안 **자료의 끝**. 「자료가 어디까지 들어왔나」

                    ‼️처음엔 `dataAsOf` 로만 밀림을 판정했는데 **오진했다** — 11/24 에 갱신한
                    직후인데 「9일 밀림」으로 나왔다. 그 기간 원본에 자재 등록이 없어 자료 끝이
                    11/16 이었을 뿐이다(실측: 원본도 11/16→11/24 사이 자재 0행).
                    **자재 등록이 드문 주에는 최신 사본도 늘 「밀림」으로 보인다.**

                    · `refreshedDaysAgo` — 찍은 지 며칠(사본 DB 의 현재 기준이라 타임존 영향 없음)
                    · `stale` — 2일 이상이면 참. **찍은 시각을 모르면 참**(「모른다」를 「최신」으로
                      보이지 않게). 참이면 눈에 띄게 표시해 주면 좋다
                    · `materialRows`·`requestRows` — 갱신이 통째로 실패했는지 보는 값

                    ★`refreshedAt` 은 사본 DB 의 **표 생성 시각**이다 — 갱신이 DB 를 통째로
                    지우고 다시 만드는 방식이라 그것이 곧 갱신 시각이고, **누가 갱신했든** 알 수 있다.
                    ‼️사본 DB 서버 시각이라 그 서버가 UTC 면 UTC 로 온다 —
                    「며칠 전」은 `refreshedDaysAgo` 를 쓸 것.""")
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
