package com.daesung.sales.school.dto;

import com.daesung.sales.school.entity.SchoolType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 학교 수정 요청(35p). 학교코드는 불변이라 제외. */
public record SchoolUpdateRequest(

        @Schema(description = "거래처코드(partner.code 매핑)", example = "A0003") String custCode,
        @Schema(description = "거래처명", example = "진주 이룸도서") String custName,
        @Schema(description = "도시", example = "진주") String city,
        @Schema(description = "지역(관할)", example = "경남") String region,
        @Schema(description = "학교/학원명", example = "진주고등학교") String schoolName,
        @Schema(description = "학교 Y/N(기본 true)", example = "true") Boolean isSchool,
        @Schema(description = "학교/학원구분(SCHOOL/HAKWON)", example = "SCHOOL") SchoolType schoolType,
        @Schema(description = "거래처구분(특약점/기타학원/B2B 등)", example = "특약점") String clientCategory,

        @Schema(description = """
                판매채널(레거시 schDataDT.type1). 자유문자열 — 코드표를 두지 않는다.
                조회화면의 판매채널 목록은 등록된 값들을 모아 만든다
                (`GET /masters/schools/sales-channels`).""",
                example = "자체몰")
        @Size(max = 20) String salesChannel,
        @Schema(description = "메모") String memo,

        @Schema(description = """
                학년별 **학급수 / 재학생 수**(F-22·F-23). 응시현황 '인원'·보급률의 원천이다.
                ‼️**보내지 않은 칸은 그대로 둔다**(null = 안 고침). 0 을 보내면 0 이 된다 —
                "자료 없음"과 "0명"은 다르고, 0 이 깔리면 보급률이 빈 칸이 되어 구분되지 않는다.""",
                example = "5")
        @jakarta.validation.constraints.PositiveOrZero Integer cls1ClassCnt,
        @jakarta.validation.constraints.PositiveOrZero Integer cls1StudentCnt,
        @jakarta.validation.constraints.PositiveOrZero Integer cls2ClassCnt,
        @jakarta.validation.constraints.PositiveOrZero Integer cls2StudentCnt,
        @jakarta.validation.constraints.PositiveOrZero Integer cls3ClassCnt,
        @jakarta.validation.constraints.PositiveOrZero Integer cls3StudentCnt,

        @Schema(description = """
                관리대상 여부 `Y`/`N`. 미지정이면 그대로 둔다.
                ‼️`N` 이면 **응시현황 집계에서 빠진다**(레거시가 거르는 조건).""",
                example = "Y", allowableValues = {"Y", "N"})
        @jakarta.validation.constraints.Pattern(regexp = "[YN]") String manageYn
) {
}
