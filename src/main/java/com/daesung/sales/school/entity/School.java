package com.daesung.sales.school.entity;

import com.daesung.sales.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학교/학원 마스터(35p). 근거: 요구사항 35p + 레거시 DSLab.schData + DSRE tbl_cust_ref/school_info/hakwon_info.
 *
 * <p><b>식별키 = (거래처코드, 학교코드) 복합</b>. DSRE2 {@code tbl_cust_ref}의 UNIQUE (CUST_CD, MGR_GN, MGR_CD)
 * 및 레거시 {@code schData}(schCode·custCode 별개 컬럼)와 동일 체계다.
 * (V22의 "학교코드=거래처코드 동일값" 전제는 원본과 달라 V26에서 폐기했다.)
 *
 * <p><b>필드는 두 종류로 나뉜다 — 동기화가 이 경계를 넘으면 안 된다.</b>
 * <ul>
 *   <li>DSRE 관리: 거래처명·도시·지역·학교명·학교Y/N → 동기화 시 덮어씀
 *   <li>매출프로그램 전용: 거래처구분·학교/학원구분·메모 → 동기화가 <b>건드리지 않음</b>(수기 입력값 보존)
 * </ul>
 */
@Entity
@Table(name = "schools")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class School extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 학교코드(DSRE MGR_CD). 거래처코드와 함께 동기화 매칭키. */
    @Column(name = "school_code", nullable = false, length = 20)
    private String schoolCode;

    /** 거래처코드(partner.code / DSRE CUST_CD). 매칭키 일부라 NULL 불가(미지정은 빈문자). */
    @Column(name = "cust_code", nullable = false, length = 20)
    private String custCode;

    /** 거래처명(표시용). */
    @Column(name = "cust_name", length = 100)
    private String custName;

    /** 도시. */
    @Column(length = 50)
    private String city;

    /** 지역(관할). */
    @Column(length = 50)
    private String region;

    /** 지역코드(레거시 cityCode). 지역명(city)과 짝 — 검색이 코드로도 들어온다. */
    @Column(name = "city_code", length = 20)
    private String cityCode;

    /** 특약점L — 특약점 소재(레거시 custLoc). 화면은 "특약점L + 특약점N"을 이어 붙여 보여준다. */
    @Column(name = "partner_loc", length = 100)
    private String partnerLoc;

    /**
     * 모의고사 담당 특약점. ★<b>같은 학교라도 상품군에 따라 담당이 다르다</b> —
     * 그래서 거래처 하나(custCode)로는 표현되지 않는다.
     */
    @Column(name = "mock_partner_code", length = 30)
    private String mockPartnerCode;

    @Column(name = "mock_partner_name", length = 100)
    private String mockPartnerName;

    /** IC 담당 특약점. 모의고사와 같은 이유로 따로 든다. */
    @Column(name = "ic_partner_code", length = 30)
    private String icPartnerCode;

    @Column(name = "ic_partner_name", length = 100)
    private String icPartnerName;

    /** 학교/학원명. */
    @Column(name = "school_name", length = 100)
    private String schoolName;

    /** 학교 Y/N. */
    @Column(name = "is_school", nullable = false)
    private boolean isSchool = true;

    /** 학교/학원구분(직접입력). */
    @Enumerated(EnumType.STRING)
    @Column(name = "school_type", nullable = false, length = 10)
    private SchoolType schoolType = SchoolType.SCHOOL;

    /** 거래처구분(특약점/기타학원/B2B 등, 직접입력). */
    @Column(name = "client_category", length = 30)
    private String clientCategory;

    /**
     * 판매채널. 근거: 레거시 {@code 조회.Designer.vb:365}(콤보 라벨 "판매채널") +
     * {@code CommonDB.vb:475} {@code SELECT type1 FROM schDataDT GROUP BY type1}.
     *
     * <p>★<b>학교 단위 값이다</b> — 거래처가 아니라 그 학교가 어느 채널로 들어왔는지다.
     * 레거시 {@code schDataDT} 는 학교코드·type1·type2 세 칸뿐이고, 짝인 type2 는 이미
     * 우리 '학교구분'으로 들어와 있다({@code 조회.vb:1528}).
     *
     * <p>★<b>자유문자열이다.</b> 레거시가 코드표 없이 학교 행의 값을 모아 목록을 만든다 —
     * 고정 enum 으로 바꾸면 채널이 하나 늘 때마다 배포해야 한다.
     */
    @Column(name = "sales_channel", length = 20)
    private String salesChannel;

    @Column(length = 500)
    private String memo;

    /** 출처. MANUAL(매출프로그램 전용)은 DSRE 동기화가 건드리지 않는다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SchoolSource source = SchoolSource.MANUAL;

    /**
     * 학년별 <b>학급수·재학생 수</b>(F-22·F-23). 응시현황의 <b>'인원'이 이 값</b>이다 —
     * 매출 수량이 아니다(레거시 {@code 응시현황.vb:526} 이 {@code cls1SCnt}~{@code cls3SCnt} 를 합한다).
     *
     * <p>★<b>연도 축이 없다.</b> 레거시 {@code schAllData} 에 연도 칸이 없고 그 값을 그대로
     * 집계한다 = 한 벌만 두고 덮어쓰는 구조다. 해마다 바뀌는 값이지만 레거시와 같은 숫자가 먼저다.
     *
     * <p>★<b>학급당 평균 인원은 저장하지 않는다</b> — {@code 재학생 ÷ 학급수} 로 나온다
     * (레거시 {@code clsNRatio} 2,391건 전부 그 식과 일치). 저장하면 둘이 어긋난다.
     */
    /**
     * 관리대상 여부(레거시 {@code schAllData.manageYN}). {@code 'N'} 은 응시현황 집계에서 뺀다.
     *
     * <p>‼️레거시 응시현황이 {@code WHERE manageYN IS NULL OR manageYN='Y'} 로 거른다.
     * 이 칸을 이관하지 않아 <b>관리대상 아닌 학교 433곳(인원 21.9만)이 섞여</b>
     * 지역비율이 레거시보다 20% 컸다(129.2만 ↔ 108.0만, 실측 2026-11-09).
     *
     * <p>★기본 {@code 'Y'} — 레거시가 판단한 적 없는 학교(DSRE 동기화·수기 등록)는 <b>빼지 않는다</b>.
     */
    @Column(name = "manage_yn", nullable = false, length = 1)
    private String manageYn = "Y";

    @Column(name = "cls1_class_cnt")
    private Integer cls1ClassCnt;

    @Column(name = "cls1_student_cnt")
    private Integer cls1StudentCnt;

    @Column(name = "cls2_class_cnt")
    private Integer cls2ClassCnt;

    @Column(name = "cls2_student_cnt")
    private Integer cls2StudentCnt;

    @Column(name = "cls3_class_cnt")
    private Integer cls3ClassCnt;

    @Column(name = "cls3_student_cnt")
    private Integer cls3StudentCnt;

    /** 관리대상 여부를 고친다. null 은 "안 고친다" — Y/N 만 받는다(DTO 가 검증). */
    public void updateManageYn(String manageYn) {
        if (manageYn != null && !manageYn.isBlank()) {
            this.manageYn = manageYn;
        }
    }

    /**
     * 학년별 학급수·재학생 수를 고친다(학교관리 화면·엑셀 업로드).
     *
     * <p>‼️<b>null 은 "안 고친다"</b>는 뜻이다. 0 을 보내면 0 으로 바뀐다 —
     * "값이 없다"와 "0명"은 다르고, 0 으로 깔리면 응시현황 비율이 빈 칸이 되어
     * 자료가 없는 것과 구분되지 않는다.
     */
    public void updateStudentCounts(Integer c1, Integer s1, Integer c2, Integer s2,
                                    Integer c3, Integer s3) {
        if (c1 != null) {
            this.cls1ClassCnt = c1;
        }
        if (s1 != null) {
            this.cls1StudentCnt = s1;
        }
        if (c2 != null) {
            this.cls2ClassCnt = c2;
        }
        if (s2 != null) {
            this.cls2StudentCnt = s2;
        }
        if (c3 != null) {
            this.cls3ClassCnt = c3;
        }
        if (s3 != null) {
            this.cls3StudentCnt = s3;
        }
    }

    /** 사용여부. DSRE2에서 사라진 행은 삭제하지 않고 false(미사용)로 둔다 — 과거 매출 이력 보존. */
    @Column(nullable = false)
    private boolean active = true;

    public static School create(String schoolCode, String custCode, String custName, String city, String region,
                                String schoolName, boolean isSchool, SchoolType schoolType,
                                String clientCategory, String salesChannel, String memo) {
        School s = new School();
        s.schoolCode = schoolCode;
        s.custCode = (custCode == null) ? "" : custCode;
        s.custName = custName;
        s.city = city;
        s.region = region;
        s.schoolName = schoolName;
        s.isSchool = isSchool;
        s.schoolType = (schoolType == null) ? SchoolType.SCHOOL : schoolType;
        s.clientCategory = clientCategory;
        s.salesChannel = salesChannel;
        s.memo = memo;
        return s;
    }

    /** 수정(학교코드 불변). */
    public void update(String custCode, String custName, String city, String region, String schoolName,
                       boolean isSchool, SchoolType schoolType, String clientCategory,
                       String salesChannel, String memo) {
        this.custCode = (custCode == null) ? "" : custCode;
        this.custName = custName;
        this.city = city;
        this.region = region;
        this.schoolName = schoolName;
        this.isSchool = isSchool;
        this.schoolType = (schoolType == null) ? SchoolType.SCHOOL : schoolType;
        this.clientCategory = clientCategory;
        this.salesChannel = salesChannel;
        this.memo = memo;
    }

    /** DSRE에서 온 행으로 표시(신규 동기화 생성 시). */
    public School fromDsre() {
        this.source = SchoolSource.DSRE;
        return this;
    }

    /**
     * 동기화 덮어쓰기 — <b>DSRE 관리 필드만</b>.
     * 거래처구분·학교/학원구분·메모는 매출프로그램 수기 입력값이라 손대지 않는다(발주처 확정 2026-08).
     * 동기화로 다시 나타난 행은 미사용에서 되살린다.
     */
    /**
     * 학교/학원검색(29p) 전용 필드. 담당자 수기 입력 경로다.
     *
     * <p>‼️<b>정정(2026-09-14)</b>: 예전 주석은 "담당 특약점은 레거시 schData에만 있던 축이라
     * DSRE가 주지 않는다"고 적혀 있었는데 <b>사실이 아니다</b>. 레거시
     * {@code 학교관리.vb:86}은 {@code CI.MACHUL_CD}를 두 번 뽑아 {@code mCustCode}·{@code iCustCode}에
     * 넣는다 — DSRE2가 주는 값이다. 그래서 동기화도 이 값을 채운다
     * ({@link #applyDsrePartnerCodes}). 수기 입력은 그대로 두되, 동기화가 빈 값으로 덮지는 않는다.
     */
    public void applySearchFields(String cityCode, String partnerLoc,
                                  String mockPartnerCode, String mockPartnerName,
                                  String icPartnerCode, String icPartnerName) {
        this.cityCode = cityCode;
        this.partnerLoc = partnerLoc;
        this.mockPartnerCode = mockPartnerCode;
        this.mockPartnerName = mockPartnerName;
        this.icPartnerCode = icPartnerCode;
        this.icPartnerName = icPartnerName;
    }

    public void applyDsreFields(String custName, String city, String region, String schoolName, boolean isSchool) {
        this.custName = custName;
        this.city = city;
        this.region = region;
        this.schoolName = schoolName;
        this.isSchool = isSchool;
        this.active = true;
    }

    /**
     * 담당 특약점 코드 — DSRE2 {@code tbl_cust_info.MACHUL_CD}에서 채운다.
     * 근거: 레거시 {@code 학교관리.vb:86} — 같은 MACHUL_CD를 모의고사·IC 양쪽에 넣고,
     * 표시명은 {@code CITY_NM + ' ' + CUST_NM}으로 만든다.
     *
     * <p>‼️<b>빈 값이면 덮어쓰지 않는다.</b> MACHUL_CD는 DSRE2 화면에서 사람이 손으로 치는 칸이라
     * 비어 있는 거래처가 실재한다(2026-09-14 라이브 실측: 17곳). 그걸로 덮으면
     * 담당자가 수기로 넣어 둔 담당 특약점이 동기화 한 번에 사라진다.
     */
    public void applyDsrePartnerCodes(String machulCode, String partnerLabel) {
        if (machulCode == null || machulCode.isBlank()) {
            return;
        }
        this.mockPartnerCode = machulCode;
        this.icPartnerCode = machulCode;
        String label = (partnerLabel == null || partnerLabel.isBlank()) ? null : partnerLabel.trim();
        if (label != null) {
            this.mockPartnerName = label;
            this.icPartnerName = label;
        }
    }

    /** DSRE2에서 사라진 행 처리 — 삭제가 아니라 미사용. */
    public void markUnused() {
        this.active = false;
    }
}
