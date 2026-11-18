package com.daesung.sales.sale.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 매출일괄등록(14p, 더프) 결과. 근거: 레거시 {@code 매출가져오기.vb} + 정본 14p 데이터 항목.
 *
 * <p>정본 14p 항목을 그대로 싣는다 — {@code 처리순번, 신청일자, 매출코드, 거래처코드/명,
 * 학교코드/명, 학년, 처리구분, 청구구분, 신청/처리/비처리 인원, 단가, 총금액}.
 *
 * <p>★더프는 <b>응시인원 기반 종량제</b>다(정본 14p). 교재·기타고사의 부수 기반과 계산이 다르므로
 * {@code /sales/bulk}(교재)와 별개 경로다.
 */
public record DuffImportResponse(
        @Schema(description = "미리보기 여부(true=쓰기 안 함)") boolean dryRun,
        @Schema(description = "처리구분 모드(ALL/PROCESSED/UNPROCESSED)") String mode,
        @Schema(description = "생성된 매출 라인 수") int importedCount,
        @Schema(description = "이미 등록되어 스킵된 라인 수(멱등)") int skippedCount,
        @Schema(description = """
                매핑 실패로 제외된 라인 수(거래처·상품 합계).
                ★어느 쪽이 없는지는 라인의 `result`(UNMAPPED_PARTNER / UNMAPPED_PRODUCT)와
                `reason`을 봐야 한다 — 합계만 보면 "거래처가 안 맞는다"로 읽힌다.""")
        int unmappedCount,
        @Schema(description = "청구인원 0 이하로 제외된 라인 수") int zeroInwonCount,
        @Schema(description = "단가가 없어 금액 0으로 제외된 라인 수(DSRE·도서 마스터 양쪽 다 비어 있음)")
        int zeroAmountCount,
        @Schema(description = "총금액 합계(등록 대상 기준)") long totalAmount,
        @Schema(description = "라인 상세") List<Line> lines
) {
    @Schema(name = "DuffImportLine")
    public record Line(
            @Schema(description = """
                    이 줄의 **소스키**. 등록 요청의 `sourceKeys`·`overrides[].sourceKey` 에
                    그대로 넣는다 — 체크한 줄만 등록하거나 값을 고칠 때 쓰는 키다.""")
            String sourceKey,
            @Schema(description = "처리순번(신청번호)") int reqCd,
            @Schema(description = "신청일자") LocalDate reqDate,
            @Schema(description = "매출코드(거래처의 매출용 코드)") String machulCode,
            @Schema(description = "거래처코드") String partnerCode,
            @Schema(description = "거래처명") String partnerName,
            @Schema(description = "학교/학원 코드") String schoolCode,
            @Schema(description = "학교/학원명") String schoolName,
            @Schema(description = "학년") String grade,
            @Schema(description = "분류코드") String catCode,
            @Schema(description = "분류명") String catName,
            @Schema(description = "과목코드") String subjectCode,
            @Schema(description = "과목명") String subjectName,
            @Schema(description = "처리구분(신청건의 proc_yn2). 실값은 '1'·'5'·'N' — 숫자는 회차다. "
                    + "판정은 'N'이냐 아니냐만 본다(N=비처리, 그 외=처리)", example = "1")
            String procType,
            @Schema(description = "청구구분(T=처리+비처리 / S=처리 / R=등록)") String chargeType,
            @Schema(description = "신청과목수 — 레거시 목록의 컬럼(프론트 요청 2026-10-22 3-2 ⑥)")
            int subjectCnt,
            @Schema(description = "신청인원") int reqInwon,
            @Schema(description = "처리인원") int procInwon,
            @Schema(description = "비처리인원") int unprocInwon,
            @Schema(description = "등록인원") int regInwon,
            @Schema(description = "청구인원(이 건에 실제로 청구한 인원 = 수량)") int chargedInwon,
            @Schema(description = "청구인원을 무엇으로 골랐는지(REGISTERED/PROCESSED/UNPROCESSED)")
            String chargeBasis,
            @Schema(description = "정가(DSRE 값 우선, 없으면 도서 마스터)") int price,
            @Schema(description = "공급률 %(DSRE 값 우선, 없으면 도서 마스터)") int supplyRate,
            @Schema(description = "할인액(권당). 0보다 크면 공급률 대신 이 값으로 단가가 정해진다") int discount,
            @Schema(description = "단가(할인액>0이면 정가−할인액, 아니면 정가×공급률/100)") long unitAmount,
            @Schema(description = "총금액(단가×청구인원)") long totalAmount,
            @Schema(description = """
                    처리 결과 — IMPORTED / SKIPPED / **UNMAPPED_PARTNER** / **UNMAPPED_PRODUCT** /
                    UNMAPPED(둘 다 없음) / ZERO_INWON / ZERO_AMOUNT / **ENTRY_DATE_TOO_OLD** / PREVIEW.

                    ★`ENTRY_DATE_TOO_OLD` = 그 줄의 거래일이 **입력 최소일 이전**이다
                    (「신청일 기준」으로 실행하면 줄마다 거래일이 신청일이라 줄별로 갈린다).
                    ‼️SKIPPED 로 합치지 않는다 — 화면이 SKIPPED 를 「이미 등록됨」으로 세어
                    요약 숫자가 틀어진다(프론트 요청 2026-11-18).

                    ★거래처·상품을 **갈라서** 알려준다. 예전엔 UNMAPPED 하나로 합쳐 두어
                    화면이 전부 "거래처 매핑 실패"로 읽었고, 실제 원인(상품 미등록)이 묻혔다.
                    채워야 할 마스터가 달라지므로 구분이 필요하다.""")
            String result,

            @Schema(description = "왜 그 결과가 나왔는지. UNMAPPED 계열에서 무엇이 없는지 알려준다")
            String reason,

            @Schema(description = "생성 매출번호(등록 시)") String salesNo
    ) {
    }
}
