package com.daesung.sales.dsre.gateway;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * DSRE 사본이 <b>언제 찍힌 것인지</b>. 화면 위쪽의 「DSRE 자료 기준 시각」에 쓴다
 * (프론트 요청 2026-11-25).
 *
 * <p>‼️<b>왜 필요한가.</b> 배포 서버는 운영 DSRE 에 직접 닿지 못해 <b>사본</b>을 읽는데,
 * 그 사본이 <b>10/26 에서 11/24 까지 한 달 가까이 멈춰</b> 있었고 아무도 몰랐다.
 * 그 사이 10월 물류 작업비가 2,803,640원 적게 나왔고(발주처 10쪽), 매출일괄등록도
 * DSRE 에서 바꾼 내역이 반영되지 않았다(발주처 3·4쪽).
 *
 * <h3>★★두 시각을 구분해 내린다 — 섞으면 오진한다</h3>
 * <pre>
 *   refreshedAt  사본을 **찍은** 시각   ← 「갱신이 밀렸나」는 이것으로 본다
 *   dataAsOf     사본 안 **자료의 끝**  ← 「자료가 어디까지 들어왔나」
 * </pre>
 * ‼️처음엔 {@code dataAsOf} 로만 밀림을 판정했는데 <b>오진했다</b> —
 * 11/24 에 갱신한 직후인데 「9일 밀림」으로 나왔다. 그 기간 원본에 자재 등록이
 * 없어서 자료 끝이 11/16 이었을 뿐이다(실측: 원본도 11/16 → 11/24 사이 자재 0행).
 * <b>자재 등록이 드문 주에는 최신 사본도 늘 「밀림」으로 보인다.</b>
 */
@Schema(name = "DsreSnapshot", description = "DSRE 사본 — 찍은 시각 · 자료 끝")
public record DsreSnapshot(

        @Schema(description = """
                ★**사본을 찍은 시각**(사본 DB 의 표 생성 시각). 갱신이 밀렸는지는 이 값으로 본다.
                화면에 「DSRE 자료 기준 : 2026-11-24 10:52」처럼 쓰면 된다.
                ‼️**사본 DB 서버의 시각**이다 — 그 서버가 UTC 로 돌면 UTC 로 온다.
                며칠 전인지는 `refreshedDaysAgo` 를 쓰면 타임존과 무관하게 맞다.""")
        LocalDateTime refreshedAt,

        @Schema(description = """
                사본을 찍은 지 **며칠 됐는지**(사본 DB 의 현재 시각 기준이라 타임존 영향이 없다).
                0이면 오늘 찍은 것이다. 알 수 없으면 −1.""", example = "1")
        long refreshedDaysAgo,

        @Schema(description = """
                **갱신이 밀렸는지** — `refreshedDaysAgo` 가 2 이상이면 `true`.
                참이면 빨간 표시 등으로 눈에 띄게 해 주면 좋다.
                ‼️사본 갱신은 **사람이 돌린다** — 주말·휴일에는 1~2일 밀릴 수 있다.""")
        boolean stale,

        @Schema(description = """
                사본 안 **자료의 끝**(자재 수량의 최신 등록시각).
                ‼️**갱신 지연과 다르다** — 원본에 자재 등록이 없는 기간이면 최신 사본도
                며칠 전으로 나온다. 밀림 판정에는 쓰지 말 것.""")
        LocalDateTime dataAsOf,

        @Schema(description = "사본에 든 자재 수량 행 수(갱신이 통째로 실패했는지 보는 값)",
                example = "3344124")
        long materialRows,

        @Schema(description = "사본에 든 신청 건수", example = "80617")
        long requestRows
) {
}
