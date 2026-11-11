package com.daesung.sales.partner.repository;

import com.daesung.sales.partner.entity.Partner;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PartnerRepository extends JpaRepository<Partner, Long> {
    Optional<Partner> findByCode(String code);

    /**
     * 거래처코드 여러 개를 한 번에(2026-09-28 화면 정리안 — "거래처코드 붙여넣기로 일괄 선택").
     * 없는 코드는 그냥 빠진다.
     */
    java.util.List<Partner> findByCodeIn(java.util.Collection<String> codes);

    /**
     * ‼️<b>이 메서드로 학교를 조인하지 말 것.</b> {@code partners.dsre_code} 는
     * PDF 파일명처럼 하나만 써야 하는 자리의 <b>대표값</b>일 뿐이다.
     *
     * <p>거래처 하나가 DSRE 코드를 <b>여럿</b> 갖는 일이 실재한다(운영 214행 중 5곳,
     * 한 곳은 다섯 개). 대표값으로 찾으면 나머지 코드를 쓰는 학교가 통째로 안 붙는다 —
     * 실측 145곳이 그랬다. 조인은 {@code PartnerDsreCodeRepository}(1:N)를 쓴다.
     */
    java.util.List<Partner> findByDsreCodeIn(java.util.Collection<String> dsreCodes);

    Page<Partner> findByCodeContainingIgnoreCaseOrNameContainingIgnoreCase(
            String code, String name, Pageable pageable);

    /** 담보 만기일이 기준일(threshold) 이하인 거래처(만료+임박). 만기일 오름차순. null 만기일은 자동 제외. */
    List<Partner> findByAssureExpiryLessThanEqualOrderByAssureExpiryAsc(LocalDate threshold);

    /**
     * 거래처 검색. 키워드(코드·거래처명 부분일치) + 만료 포함 여부.
     * includeExpired=false면 거래중(end_date IS NULL)만 — 레거시 거래처관리 기본 동작.
     *
     * <p>‼️<b>이름의 공백을 무시해서도 찾는다</b>(발주처 2026-11-11 승인).
     * 레거시가 개인 거래처를 {@code '조 병 준'} 처럼 <b>글자 사이에 공백을 넣어</b> 저장해
     * 뒀고 그대로 이관됐다 — <b>75곳</b>이 그 모양이다. 담당자가 "조병준"으로 검색하면
     * 한 건도 안 나와서 "거래처가 없다"고 판단하게 된다(실제로 그렇게 오진한 적이 있다).
     * 그래서 <b>이름 데이터는 그대로 두고 검색만</b> 공백을 지워 한 번 더 비교한다.
     * 키워드 쪽 공백도 함께 지우므로 "조 병 준"으로 검색해도 찾는다.
     *
     * <p>⚠️{@code replace()} 는 인덱스를 타지 못하지만, 바로 위의 {@code like '%...%'} 가
     * 이미 전체 스캔이라 비용이 늘지 않는다(거래처는 1천 행 미만).
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from Partner p
             where (:keyword is null
                    or lower(p.code) like lower(concat('%', :keyword, '%'))
                    or lower(p.name) like lower(concat('%', :keyword, '%'))
                    or replace(lower(p.name), ' ', '')
                       like replace(lower(concat('%', :keyword, '%')), ' ', ''))
               and (:includeExpired = true or p.endDate is null)
             order by p.code
            """)
    org.springframework.data.domain.Page<Partner> search(
            @org.springframework.data.repository.query.Param("keyword") String keyword,
            @org.springframework.data.repository.query.Param("includeExpired") boolean includeExpired,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 위와 같은 검색에 <b>거래처구분</b> 필터를 더한 것. [도서관리] '거래처별 노출' 탭이
     * "거래처구분으로 좁힌 뒤 여러 거래처를 체크"하는 조작을 쓴다(정본 1탭 도서관리).
     *
     * <p>거래처구분은 자유 문자열이라 <b>정확일치</b>로 본다 — 거래처별 단가 매핑
     * ({@code PartnerSupplyRateRepository.search})과 같은 규칙을 쓴다. 한쪽만 부분일치로
     * 두면 같은 화면의 두 탭이 다른 거래처 집합을 보여준다.
     */
    @org.springframework.data.jpa.repository.Query("""
            select p from Partner p
             where (:keyword is null
                    or lower(p.code) like lower(concat('%', :keyword, '%'))
                    or lower(p.name) like lower(concat('%', :keyword, '%'))
                    or replace(lower(p.name), ' ', '')
                       like replace(lower(concat('%', :keyword, '%')), ' ', ''))
               and (:clientCategory is null or p.clientCategory = :clientCategory)
               and (:includeExpired = true or p.endDate is null)
             order by p.code
            """)
    org.springframework.data.domain.Page<Partner> searchByCategory(
            @org.springframework.data.repository.query.Param("keyword") String keyword,
            @org.springframework.data.repository.query.Param("clientCategory") String clientCategory,
            @org.springframework.data.repository.query.Param("includeExpired") boolean includeExpired,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 쓰이고 있는 지역·관할지역 목록(거래처 등록 화면 선택용).
     *
     * <p>★분류코드와 같은 이유다 — 지역은 조회 필터({@code ?region=})로 쓰이는데 자유 입력이라
     * {@code 경남}과 {@code 경상남도}가 섞이면 한쪽이 필터에서 통째로 빠진다.
     * 값이 정해져 있지 않아 enum으로 못 박을 수 없으니, <b>쓰이는 값을 보여줘 고르게</b> 한다.
     *
     * <p>{@code kind}로 축을 가른다 — 지역(region)과 관할지역(zone2)은 <b>별개 축</b>이다
     * (정본 4탭 거래처관리에 둘 다 있다). 한 목록에 섞으면 어느 칸에 넣을 값인지 알 수 없다.
     */
    @Query("""
            select p.region as value, count(p) as usedCount
            from Partner p
            where p.region is not null and p.region <> ''
            group by p.region
            order by count(p) desc, p.region asc
            """)
    List<CodeUsage> findUsedRegions();

    @Query("""
            select p.zone2 as value, count(p) as usedCount
            from Partner p
            where p.zone2 is not null and p.zone2 <> ''
            group by p.zone2
            order by count(p) desc, p.zone2 asc
            """)
    List<CodeUsage> findUsedZones();

    /** 위 두 조회의 투영. 건수가 1인 값은 오타를 의심할 자리다. */
    interface CodeUsage {
        String getValue();

        long getUsedCount();
    }

}
