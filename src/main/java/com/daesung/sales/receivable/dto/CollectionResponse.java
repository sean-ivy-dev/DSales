package com.daesung.sales.receivable.dto;

import com.daesung.sales.receivable.entity.Collection;
import com.daesung.sales.receivable.entity.CollectionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 수금 조회 응답.
 *
 * <p>구분이 두 축이다(정본 23p) — 수금구분(명목)·입금구분(형태).
 * 합치면 "어음으로 받은 도서대금"을 표현할 수 없다.
 */
public record CollectionResponse(
        @Schema(description = "수금 id") Long id,
        @Schema(description = "수금번호") String collectionNo,
        @Schema(description = """
                전표코드 — **한 번에 등록한 수금 묶음**(현장 피드백 2026-10-14 B-5).
                한 번 들어온 돈을 여러 줄로 쪼개 적을 때 그 줄들이 공유한다.
                ‼️한 건씩 등록한 수금은 null 이다(묶음이 없다).""")
        String voucherNo,

        @io.swagger.v3.oas.annotations.media.Schema(description = """
                수금순번 — 그 **거래처**의 그 **수금일자** 안에서 몇 번째인가(1부터).
                등록 창의 순번 드롭다운이 이 값이고, `partnerId`+`collDate`+`collSeq` 로
                그 수금을 불러온다(레거시 `amtData.collSeq`).
                ‼️`voucherNo`(한 번에 올린 **묶음**)와 다른 축이다.
                이관분은 null 이다 — 소급해 채우지 않았다.""")
        Integer collSeq,
        @Schema(description = "수금일자") LocalDate collDate,
        @Schema(description = "기장일자(회계 기표일)") LocalDate writeDate,
        @Schema(description = "거래처 id") Long partnerId,
        @Schema(description = "거래처명") String partnerName,
        @Schema(description = "수금구분(무슨 명목). 기본 '도서대금'") String collKind,
        @Schema(description = "입금구분 코드(어떤 형태)") CollectionType collType,
        @Schema(description = "입금구분 명칭(현금/어음/선수금/대체)") String collTypeName,
        @Schema(description = "수금액") long collAmt,
        @Schema(description = "어음번호") String promissoryNo,
        @Schema(description = "어음 만기") LocalDate promissoryDue,
        @Schema(description = "은행명") String bankName,
        @Schema(description = "지점명") String branchName,
        @Schema(description = "비고") String memo
) {
    public static CollectionResponse from(Collection c) {
        return new CollectionResponse(
                c.getId(), c.getCollectionNo(), c.getVoucherNo(), c.getCollSeq(),
                c.getCollDate(), c.getWriteDate(),
                c.getPartner().getId(), c.getPartner().getName(),
                c.getCollKind(), c.getCollType(), c.getCollType().label(), c.getCollAmt(),
                c.getPromissoryNo(), c.getPromissoryDue(), c.getBankName(), c.getBranchName(), c.getMemo());
    }
}
