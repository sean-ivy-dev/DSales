package com.daesung.sales.dsre.gateway;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * DSRE 사본이 <b>언제 기준인지</b>. 화면 위쪽의 「DSRE 자료 기준 시각」에 그대로 쓴다
 * (프론트 요청 2026-11-25).
 *
 * <p>‼️<b>왜 필요한가.</b> 배포 서버는 운영 DSRE 에 직접 닿지 못해 <b>사본</b>을 읽는데,
 * 그 사본이 <b>10/26 에서 11/24 까지 한 달 가까이 멈춰</b> 있었고 아무도 몰랐다.
 * 그 사이 10월 물류 작업비가 2,803,640원 적게 나왔고(발주처 10쪽),
 * 매출일괄등록도 DSRE 에서 바꾼 내역이 반영되지 않았다(발주처 3·4쪽).
 * <b>화면에 기준 시각이 보이면 담당자가 바로 안다.</b>
 *
 * <p>기준 시각은 <b>사본 안 자재 수량({@code tbl_logis_cnt})의 최신 등록시각</b>이다.
 * 「사본을 갱신한 시각」이 아니라 <b>자료가 어디까지 들어왔는지</b>를 쓴다 —
 * 갱신을 돌려도 운영 DSRE 가 비어 있으면 자료는 늘지 않으므로,
 * 담당자가 알아야 하는 것은 <b>자료의 끝</b>이다.
 */
@Schema(name = "DsreSnapshot", description = "DSRE 사본 기준 시각")
public record DsreSnapshot(

        @Schema(description = """
                **자료 기준 시각** — 사본 안 자재 수량의 최신 등록시각.
                화면에 「DSRE 자료 기준 : 2026-11-16 10:53」처럼 그대로 쓰면 된다.
                사본이 비어 있으면 `null`.""")
        LocalDateTime dataAsOf,

        @Schema(description = "그 시각이 **며칠 전**인지(오늘 기준). 0이면 오늘 자료다", example = "1")
        long daysBehind,

        @Schema(description = """
                **낡았는지** — `daysBehind` 가 2 이상이면 `true`.
                화면에서 이 값이 참이면 빨간 표시 등으로 눈에 띄게 해 주면 좋다.
                ‼️사본 갱신은 사람이 돌리므로 주말·휴일에는 1~2일 밀릴 수 있다.""")
        boolean stale,

        @Schema(description = "사본에 든 자재 수량 행 수(갱신이 통째로 실패했는지 보는 값)",
                example = "3344124")
        long materialRows,

        @Schema(description = "사본에 든 신청 건수", example = "80617")
        long requestRows
) {
}
