package com.daesung.sales.school.repository;

import com.daesung.sales.school.entity.School;
import com.daesung.sales.school.entity.SchoolSource;
import java.util.List;
import java.util.Optional;
import com.daesung.sales.school.entity.SchoolType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SchoolRepository extends JpaRepository<School, Long> {
    Optional<School> findBySchoolCode(String schoolCode);

    /**
     * 등록된 <b>판매채널 목록</b>. 근거: 레거시 {@code CommonDB.vb:475}
     * {@code SELECT type1 FROM schDataDT GROUP BY type1} — 코드표가 아니라
     * <b>학교 행에 적힌 값을 모아</b> 조회화면 콤보를 만든다. 우리도 같게 둔다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select distinct s.salesChannel from School s
             where s.salesChannel is not null and s.salesChannel <> ''
             order by s.salesChannel
            """)
    java.util.List<String> findSalesChannels();

    /** 매출 목록이 학교/학원 구분을 붙일 때 쓴다 — 코드를 모아 한 번에(N+1 방지). */
    List<School> findBySchoolCodeIn(java.util.Collection<String> schoolCodes);

    /**
     * 학교/학원검색(29p). 근거: 레거시 {@code 학교검색.vb} 조회 SQL을 그대로 옮겼다.
     *
     * <p>★<b>특약점명은 세 축을 다 뒤진다</b> — 레거시 원문:
     * {@code (custName like ? or mCustName like ? or iCustName like ?)}.
     * 같은 학교라도 상품군에 따라 담당 특약점이 달라, 대표 특약점만 보면 못 찾는다.
     *
     * <p>필터 4종(학교코드·학교명·지역·특약점명)은 전부 부분일치다(레거시 {@code like '%..%'}).
     * 빈 값은 조건에서 빠진다 — 레거시는 빈 문자열을 넣어 {@code like '%%'}가 되게 했는데,
     * 그러면 NULL인 행이 통째로 빠진다(SQL에서 NULL like '%%'는 참이 아니다).
     */
    @Query("""
            select s from School s
             where (:schoolCode is null or s.schoolCode like %:schoolCode%)
               and (:schoolName is null or s.schoolName like %:schoolName%)
               and (:region is null or s.cityCode like %:region% or s.city like %:region%
                    or s.region like %:region%)
               and (:partnerName is null
                    or s.custName like %:partnerName%
                    or s.mockPartnerName like %:partnerName%
                    or s.icPartnerName like %:partnerName%)
             order by s.schoolCode
            """)
    List<School> search(@Param("schoolCode") String schoolCode,
                        @Param("schoolName") String schoolName,
                        @Param("region") String region,
                        @Param("partnerName") String partnerName);

    /** 동기화 매칭키 조회 — (거래처코드, 학교코드) 복합. */
    Optional<School> findByCustCodeAndSchoolCode(String custCode, String schoolCode);

    /** 출처별 조회. 동기화는 DSRE 행만 대상으로 하고 MANUAL 행은 손대지 않는다. */
    java.util.List<School> findBySource(SchoolSource source);

    /**
     * 목록 조회 — 키워드 + 학교/학원 구분.
     *
     * <p>★키워드가 <b>거래처코드·거래처명까지</b> 훑는다. 예전엔 학교코드·학교명만 봐서
     * 화면의 거래처코드 컬럼으로 검색하면 0건이었다(실측 2026-09-18: `00001` → 0건).
     * 엑셀엔 그 컬럼을 내려주면서 검색은 안 되는 건 앞뒤가 안 맞는다.
     *
     * <p>{@code schoolType} 은 미지정이면 전체다.
     */
    @Query("""
            select s from School s
             where (:kw is null
                    or lower(s.schoolCode) like :kw or lower(s.schoolName) like :kw
                    or lower(s.custCode) like :kw or lower(s.custName) like :kw)
               and (:schoolType is null or s.schoolType = :schoolType)
            """)
    Page<School> search(@Param("kw") String kw,
                        @Param("schoolType") SchoolType schoolType,
                        Pageable pageable);

    Page<School> findBySchoolCodeContainingIgnoreCaseOrSchoolNameContainingIgnoreCase(
            String schoolCode, String schoolName, Pageable pageable);

    /**
     * 응시현황 '지역비율'의 <b>인원</b> 원자료 — 학교별 거래처·도시·학년 재학생 수(F-22).
     *
     * <p>★<b>재학생 수가 있는 학교만</b> 낸다. 레거시는 {@code schAllData} 에 있는 학교만
     * 집계하는데(그 표가 재학생 자료 자체다), 우리는 한 표에 섞여 있어 조건으로 가른다.
     * 자료가 없는 학교를 0명으로 세우면 <b>보급률 분모가 커져 비율이 작아진다</b> —
     * 자료 없음과 0명은 다르다.
     *
     * <p>{@code schoolType} 미지정이면 전체다(레거시 「학원」 체크박스에 대응).
     *
     * <p>‼️<b>{@code manageYn = 'Y'} 가 레거시가 거르는 조건</b>이다
     * ({@code 응시현황.vb}: {@code WHERE manageYN IS NULL OR manageYN='Y'}).
     * 이게 없으면 관리대상 아닌 학교 <b>433곳·인원 21.9만</b>이 섞여, 분모만 커져
     * 보급률이 작아진다(실측 2026-11-09: 인원 129.2만 ↔ 레거시 108.0만).
     *
     * <p>★<b>정렬을 고정한다</b> — 같은 학교코드가 거래처별로 여러 행인 경우가 있어
     * (실측 96곳) 서비스가 <b>첫 행만</b> 쓴다. 정렬이 흔들리면 그 학교의 지역구분이
     * 조회마다 달라진다.
     *
     * <p>⚠️HQL 은 {@code --} 주석을 못 읽는다 — 쿼리 안에 설명을 넣으면
     * {@code Bad HQL grammar} 로 <b>앱이 기동하지 못한다</b>(실제로 그랬다).
     */
    @Query("""
            select s.schoolCode as schoolCode, s.custCode as custCode, s.city as city,
                   s.cls1StudentCnt as cls1StudentCnt, s.cls2StudentCnt as cls2StudentCnt,
                   s.cls3StudentCnt as cls3StudentCnt
              from School s
             where s.active = true
               and s.manageYn = 'Y'
               and (s.cls1StudentCnt is not null or s.cls2StudentCnt is not null
                    or s.cls3StudentCnt is not null)
               and (:isSchool is null or s.isSchool = :isSchool)
               and (:clientCategory is null or s.clientCategory = :clientCategory)
             order by s.schoolCode, s.custCode
            """)
    List<com.daesung.sales.sale.dto.SchoolStudentAgg> studentCounts(
            @Param("isSchool") Boolean isSchool,
            @Param("clientCategory") String clientCategory);

    /**
     * 응시현황(연도별) 학교 목록 — <b>재학생 수가 없는 학교도 포함</b>한다.
     *
     * <p>{@link #studentCounts} 와 달리 재학생 칸 조건이 없다. 연도별 화면은
     * <b>학교 수</b>를 함께 세는데(레거시 {@code COUNT(ISNULL(cls{n}GCnt,0))}),
     * 인원이 비어 있는 학교도 한 곳으로 세어야 하기 때문이다.
     *
     * <p>★학교/학원 판정은 <b>{@code isSchool}</b> 이다. 레거시 세 화면이 모두
     * {@code AllSch.isSchool = 'Y'} / {@code <> 'Y'} 로 거른다 —
     * {@code schoolType} 으로 거르면 <b>실측 106곳이 어긋난다</b>
     * (isSchool=false 인데 schoolType=SCHOOL 인 행들).
     *
     * <p>★{@code custCode} 는 레거시 {@code ComboBox_CustList} 의 거래처 조건이다
     * (전체면 null).
     */
    @Query("""
            select s.schoolCode as schoolCode, s.schoolName as schoolName,
                   s.custCode as custCode, s.custName as custName,
                   s.city as city, s.partnerLoc as partnerLoc, s.isSchool as isSchool,
                   s.cls1StudentCnt as cls1StudentCnt, s.cls2StudentCnt as cls2StudentCnt,
                   s.cls3StudentCnt as cls3StudentCnt
              from School s
             where s.active = true
               and s.manageYn = 'Y'
               and (:isSchool is null or s.isSchool = :isSchool)
               and (:custCode is null or s.custCode = :custCode)
             order by s.custCode, s.schoolCode
            """)
    List<com.daesung.sales.sale.dto.SchoolAttendanceAgg> attendanceSchools(
            @Param("isSchool") Boolean isSchool,
            @Param("custCode") String custCode);
}
