package com.daesung.sales.partner.dto;

import com.daesung.sales.common.mask.Masks;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.entity.PartnerType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 거래처 응답 DTO. 30p 거래처관리 화면 컬럼 전체.
 * name=거래처명2(풀네임), name1=신고명, cityName=도시명, region=지역(zone1), zone2=관할지역.
 */
public record PartnerResponse(
        Long id,
        @Schema(description = "거래처코드") String code,
        @Schema(description = "거래처명(풀네임)") String name,
        @Schema(description = "도시명") String cityName,
        @Schema(description = "신고명(상호만)") String name1,
        @Schema(description = "지역") String region,
        @Schema(description = """
                DSRE 거래처코드('10003' 꼴). `code`(DSLab 매출코드 'A10A195' 꼴)와 **다른 체계**다.
                학교 마스터 조인·PDF 파일명이 이 값을 쓴다.
                비어 있으면 `POST /masters/clients/sync-dsre-code` 로 채운다.""")
        String dsreCode,
        @Schema(description = "관할지역") String zone2,
        @Schema(description = "거래처구분") String clientCategory,
        PartnerType type,
        @Schema(description = "사업자번호") String bizNo,
        @Schema(description = "사업자명(대표자)") String bossName,
        @Schema(description = "사업자주민번호 — **마스킹되어 나간다**(800101-1******). 원본은 조회 경로로 제공하지 않는다")
        String bossId,
        @Schema(description = "업종") String bizStatus,
        @Schema(description = "업태") String bizItem,
        @Schema(description = "연락처1") String tel1,
        @Schema(description = "연락처2") String tel2,
        @Schema(description = "휴대폰번호") String cellPhone,
        @Schema(description = "팩스번호") String fax,
        @Schema(description = "이메일1") String email1,
        @Schema(description = "이메일2") String email2,
        @Schema(description = "우편번호") String zip,
        @Schema(description = "기본주소") String addr1,
        @Schema(description = "상세주소") String addr2,
        @Schema(description = "등록일(거래 시작)") LocalDate startDate,
        @Schema(description = "만료일 — 값이 있으면 만료 거래처") LocalDate endDate,
        @Schema(description = "만료 여부") boolean expired,
        @Schema(description = "담보금액") Long assureAmount,
        @Schema(description = "담보만기") LocalDate assureExpiry,
        @Schema(description = "담보내용") String assureNote,
        @Schema(description = """
                수정 버전 — **수정 요청에 그대로 되돌려 보내면** 다른 사람이 먼저 고친 것을
                덮어쓰지 않는다(어긋나면 409). 안 보내면 예전처럼 그냥 덮어쓴다.""")
        Long version,

        @Schema(description = """
                ★**마지막으로 고친 시각.** 거래처 변경은 상태변경 이력의 대상이 아니라
                (`StatusEntityType` 에 거래처가 없다) <b>언제 바뀌었는지 알 길이 이것뿐이다</b>.

                ‼️그래서 「담보 자료가 언제 운영에 반영됐나」에 답할 수 없었다(2026-11-26).
                값은 전부터 DB 에 있었는데 응답에 담지 않았다.""")
        java.time.LocalDateTime updatedAt,

        @Schema(description = "마지막으로 고친 사람(로그인 사용자명). 위 `updatedAt` 과 짝이다")
        String updatedBy
) {
    /**
     * 엔티티 → 응답. 사업자주민번호는 <b>여기서 마스킹</b>한다(DB-73).
     *
     * <p>이 한 곳을 거쳐 JSON 응답과 거래처 엑셀이 모두 나가므로, 화면이 아니라 여기서 막아야
     * 엑셀 다운로드·API 직접 호출로 원본이 새지 않는다.
     */
    public static PartnerResponse from(Partner p) {
        return new PartnerResponse(p.getId(), p.getCode(), p.getName(), p.getCityName(), p.getName1(),
                p.getRegion(), p.getDsreCode(), p.getZone2(), p.getClientCategory(), p.getType(),
                p.getBizNo(), p.getBossName(), Masks.residentNo(p.getBossId()), p.getBizStatus(), p.getBizItem(),
                p.getTel1(), p.getTel2(), p.getCellPhone(), p.getFax(), p.getEmail1(), p.getEmail2(),
                p.getZip(), p.getAddr1(), p.getAddr2(),
                p.getStartDate(), p.getEndDate(), p.isExpired(),
                p.getAssureAmount(), p.getAssureExpiry(), p.getAssureNote(),
                p.getVersion(), p.getUpdatedAt(), p.getUpdatedBy());
    }
}
