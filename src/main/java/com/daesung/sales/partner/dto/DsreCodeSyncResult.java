package com.daesung.sales.partner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 거래처 DSRE 코드 동기화 결과. 학교 동기화({@code SchoolSyncResult})와 같은 모양으로 낸다. */
@Schema(description = "거래처 DSRE 코드 동기화 결과")
public record DsreCodeSyncResult(

        @Schema(description = "DSRE2 tbl_cust_info 에서 읽은 행 수", example = "214")
        int fetched,

        @Schema(description = "대표코드를 새로 채운 거래처 수(비어 있던 것)", example = "188")
        int filled,

        @Schema(description = """
                대표코드가 **바뀐** 거래처 수.
                ‼️두 번째 실행부터는 **0 이어야 한다.** 0이 아니면 DSRE 쪽 데이터가
                그 사이 바뀌었다는 뜻이다(대표값은 가장 작은 코드로 결정적으로 정한다).""",
                example = "0")
        int changed,

        @Schema(description = "대표코드가 이미 같아 건드리지 않은 거래처 수", example = "188")
        int unchanged,

        @Schema(description = """
                DSRE 에는 있는데 **우리 거래처 마스터에 없는** 매출코드 수.
                0이 아니면 거래처 마스터가 덜 들어왔다는 뜻이라 확인 대상이다.""", example = "1")
        int unmatched,

        @Schema(description = "새로 추가된 (거래처, DSRE코드) 매핑 수. 재실행하면 0", example = "193")
        int mappingsAdded,

        @Schema(description = """
                ‼️**DSRE 코드를 여럿 가진 거래처**(매출코드 → 코드 목록).

                1:1 이 아니라는 것이 운영에서 드러난 지점이다 —
                한 칸만 두면 동기화가 돌 때마다 값이 바뀌고, 고르지 않은 코드를 쓰는
                학교가 통째로 안 붙는다. 그래서 매핑은 `partner_dsre_code`(1:N)가 갖고
                거래처의 `dsreCode` 는 **가장 작은 코드**를 대표값으로 둔다.

                PDF 파일명이 그 대표값을 쓰므로, 여기 나온 거래처는
                **어느 코드로 파일명을 만들지 확인이 필요하다.**""",
                example = "[\"A30D201 → 90001,99001\"]")
        List<String> multiMappedPartners,

        @Schema(description = """
                DSRE 만료(`END_GUBUN='Y'`)로 **새로 만료 처리한** 거래처 수.

                ‼️그 거래처의 DSRE 지사가 **모두 Y** 일 때만 건다 — 하나라도 사용 중이면
                만료가 아니다(매출코드를 지사 여럿이 공유하는 곳이 있다).
                ‼️만료일은 **동기화가 처음 Y 를 본 날**이다. DSRE 에 만료 날짜 칸이 없어서다.
                이미 만료된 거래처는 날짜를 덮지 않으므로 재실행하면 0 이다.""",
                example = "19")
        int dsreExpired,

        @Schema(description = """
                DSRE 가 `N` 으로 돌아와 **만료를 푼** 거래처 수.

                ★`end_source='DSRE'` 인 행만 푼다 — 담당자가 [거래 종료]한 거래처나
                레거시에서 이관된 만료는 DSRE 와 무관하므로 그대로 둔다.""",
                example = "0")
        int dsreRestored
) {
}
