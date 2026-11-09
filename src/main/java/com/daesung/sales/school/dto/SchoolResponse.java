package com.daesung.sales.school.dto;

import com.daesung.sales.school.entity.School;
import com.daesung.sales.school.entity.SchoolSource;
import com.daesung.sales.school.entity.SchoolType;

/** 학교 응답(35p 컬럼). */
public record SchoolResponse(
        Long id,
        String schoolCode,
        String custCode,
        String custName,
        String city,
        String region,
        String schoolName,
        boolean isSchool,
        SchoolType schoolType,
        String clientCategory,
        @io.swagger.v3.oas.annotations.media.Schema(description = "판매채널(레거시 schDataDT.type1)")
        String salesChannel,
        String memo,
        /** 출처(DSRE=동기화 유입 / MANUAL=매출프로그램 전용, 동기화 미대상). */
        SchoolSource source,
        /** 사용여부. DSRE2에서 사라지면 false(삭제 아님). */
        boolean active,

        @io.swagger.v3.oas.annotations.media.Schema(description = """
                학년별 **학급수 / 재학생 수**(F-22·F-23). 응시현황의 **'인원'이 재학생 수**다 —
                매출 수량이 아니다. 비어 있으면 그 학교 자료가 아직 없다는 뜻이다(0명이 아니다).
                ★**학급당 평균 인원은 내려주지 않는다** — `재학생 ÷ 학급수` 로 화면에서 구하면 된다.""")
        Integer cls1ClassCnt,
        Integer cls1StudentCnt,
        Integer cls2ClassCnt,
        Integer cls2StudentCnt,
        Integer cls3ClassCnt,
        Integer cls3StudentCnt,

        @io.swagger.v3.oas.annotations.media.Schema(description = """
                관리대상 여부 `Y`/`N`. `N` 이면 **응시현황 집계에서 빠진다**(레거시가 거르는 조건).""")
        String manageYn
) {
    public static SchoolResponse from(School s) {
        return new SchoolResponse(s.getId(), s.getSchoolCode(), s.getCustCode(), s.getCustName(),
                s.getCity(), s.getRegion(), s.getSchoolName(), s.isSchool(), s.getSchoolType(),
                s.getClientCategory(), s.getSalesChannel(), s.getMemo(), s.getSource(), s.isActive(),
                s.getCls1ClassCnt(), s.getCls1StudentCnt(),
                s.getCls2ClassCnt(), s.getCls2StudentCnt(),
                s.getCls3ClassCnt(), s.getCls3StudentCnt(),
                s.getManageYn());
    }
}
