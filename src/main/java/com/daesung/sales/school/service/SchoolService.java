package com.daesung.sales.school.service;

import com.daesung.sales.audit.entity.StatusEntityType;
import com.daesung.sales.audit.service.StatusHistoryService;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.response.PageResponse;
import com.daesung.sales.dsre.gateway.DsreGateway;
import com.daesung.sales.dsre.gateway.SchoolRefRow;
import com.daesung.sales.school.dto.SchoolCreateRequest;
import com.daesung.sales.school.dto.SchoolResponse;
import com.daesung.sales.school.dto.SchoolSyncResult;
import com.daesung.sales.school.dto.SchoolUpdateRequest;
import com.daesung.sales.school.entity.School;
import com.daesung.sales.school.entity.SchoolType;
import com.daesung.sales.school.entity.SchoolSource;
import com.daesung.sales.school.repository.SchoolRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학교/학원 마스터(35p) + DSRE '가져오기'(보존형 동기화).
 *
 * <p>★<b>학교는 조회할 때 자동으로 맞추지 않는다</b> — [가져오기] 버튼으로만 돈다.
 * 거래처는 74건짜리 한 표라 화면을 열 때마다 읽어도 되지만, 학교는 지사×학교 매핑이라
 * 규모가 다르고 <b>수기로 넣은 값(학교/학원구분·거래처구분·메모)이 섞여 있다</b>.
 * 언제 덮어쓸지는 담당자가 고르는 편이 맞다 — 조회할 때마다 조용히 바뀌면
 * 자기가 고친 값이 언제 사라졌는지 알 수 없다.
 * (2026-09-11 결정. 거래처는 {@code PartnerService.findAll} 에서 자동 갱신한다.)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SchoolService {

    private final SchoolRepository schoolRepository;
    private final com.daesung.sales.partner.repository.PartnerRepository partnerRepository;
    /** 거래처 ↔ DSRE 코드 매핑(1:N). 학교의 cust_code 가 DSRE 코드라 이 표를 타야 붙는다. */
    private final com.daesung.sales.partner.repository.PartnerDsreCodeRepository
            partnerDsreCodeRepository;
    private final StatusHistoryService statusHistoryService;

    /** DSRE 연동은 daesung.dsre.enabled=true일 때만 빈이 존재 → 없을 수 있으므로 Provider로 주입. */
    private final ObjectProvider<DsreGateway> dsreGateway;

    public PageResponse<SchoolResponse> findAll(String keyword, Pageable pageable) {
        return findAll(keyword, null, pageable);
    }

    /**
     * 목록 — 키워드 + 학교/학원 구분.
     *
     * <p>키워드는 학교코드·학교명에 더해 <b>거래처코드·거래처명까지</b> 훑는다.
     * 엑셀엔 거래처코드 컬럼을 내려주면서 그 값으로는 검색이 안 되던 것을 맞춘 것이다.
     */
    public PageResponse<SchoolResponse> findAll(String keyword, SchoolType schoolType, Pageable pageable) {
        String kw = com.daesung.sales.common.query.Keywords.norm(keyword);
        Page<School> page = schoolRepository.search(
                (kw == null) ? null : "%" + kw + "%", schoolType, pageable);
        return PageResponse.of(page.map(SchoolResponse::from));
    }

    public SchoolResponse findById(Long id) {
        return SchoolResponse.from(getOrThrow(id));
    }

    /**
     * 학교/학원검색(29p). 레거시 {@code 학교검색.vb}와 같은 필터 4종·같은 컬럼.
     *
     * <p>★특약점명 검색은 <b>대표·모의고사·IC 세 축</b>을 다 뒤진다 —
     * 같은 학교라도 상품군에 따라 담당 특약점이 달라, 대표만 보면 못 찾는다.
     */
    @Transactional(readOnly = true)
    public java.util.List<com.daesung.sales.school.dto.SchoolSearchRow> search(
            String schoolCode, String schoolName, String region, String partnerName) {
        var rows = schoolRepository.search(nullIfBlank(schoolCode), nullIfBlank(schoolName),
                nullIfBlank(region), nullIfBlank(partnerName));

        // ★거래처명1(상호)·거래처명2(풀네임)는 **거래처 마스터에만** 있다(발주처 #18 확정 컬럼).
        //   학교 마스터엔 거래처명이 한 칸뿐이라 둘로 나눌 수 없다.
        //   거래처코드를 모아 **한 번에** 읽는다 — 행마다 조회하면 전국 학교 목록에서 N+1이 된다.
        java.util.Set<String> codes = new java.util.HashSet<>();
        rows.forEach(r -> {
            if (r.getCustCode() != null && !r.getCustCode().isBlank()) {
                codes.add(r.getCustCode());
            }
        });
        // ‼️**DSRE 코드로 찾는다.** 학교의 cust_code 는 DSRE 거래처코드('10003' 꼴)이고
        //   우리 partners.code 는 DSLab 매출코드('A10A195' 꼴)라 **체계가 다르다** —
        //   예전엔 code 로 찾아서 한 건도 안 붙었고, 거래처명1·지역명이 통째로 빈 칸이었다
        //   (프론트 실측 2026-10-23 §6③). 값은 POST /masters/clients/sync-dsre-code 가 채운다.
        //
        //   ★code 로도 한 번 더 찾는다 — 수기로 등록한 학교는 우리 거래처코드를 적었을 수 있다.
        //     DSRE 코드가 먼저고, 못 찾을 때만 우리 코드를 본다.
        java.util.Map<String, com.daesung.sales.partner.entity.Partner> partners =
                new java.util.HashMap<>();
        if (!codes.isEmpty()) {
            // ① DSRE 코드 — **매핑표(1:N)를 탄다.** 거래처 한 곳이 DSRE 코드를 여럿 갖는 일이
            //    실재한다(운영 214행 중 5곳, 한 곳은 다섯 개). 거래처의 대표코드 한 칸만 보면
            //    나머지 코드를 쓰는 학교가 통째로 안 붙는다 — 실측 145곳이 그랬다.
            var maps = partnerDsreCodeRepository.findByDsreCodeIn(codes);
            java.util.Set<Long> ids = new java.util.HashSet<>();
            maps.forEach(m -> ids.add(m.getPartnerId()));
            if (!ids.isEmpty()) {
                java.util.Map<Long, com.daesung.sales.partner.entity.Partner> byId =
                        new java.util.HashMap<>();
                partnerRepository.findAllById(ids).forEach(p -> byId.put(p.getId(), p));
                // ‼️**코드마다** 담는다. 거래처당 하나만 담으면 그 거래처의 다른 코드를 쓰는
                //   학교가 그대로 빈 칸으로 남는다 — 1:N 으로 고친 의미가 없어진다.
                maps.forEach(m -> {
                    var p = byId.get(m.getPartnerId());
                    if (p != null) {
                        partners.putIfAbsent(m.getDsreCode(), p);
                    }
                });
            }
            // ② 우리 거래처코드 — 수기로 등록한 학교는 이쪽을 적었을 수 있다.
            partnerRepository.findByCodeIn(codes)
                    .forEach(p -> partners.putIfAbsent(p.getCode(), p));
        }
        return rows.stream()
                .map(r -> com.daesung.sales.school.dto.SchoolSearchRow.from(
                        r, partners.get(r.getCustCode())))
                .toList();
    }

    /**
     * 빈 문자열은 조건에서 뺀다. 레거시는 {@code like '%%'}로 넘겼는데,
     * 그러면 그 컬럼이 NULL인 행이 통째로 빠진다(SQL에서 {@code NULL like '%%'}는 참이 아니다).
     */
    private static String nullIfBlank(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    @Transactional
    public SchoolResponse create(SchoolCreateRequest req) {
        String custCode = (req.custCode() == null) ? "" : req.custCode();
        schoolRepository.findByCustCodeAndSchoolCode(custCode, req.schoolCode()).ifPresent(s -> {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이미 존재하는 거래처×학교 조합: " + custCode + "/" + req.schoolCode());
        });
        School school = School.create(req.schoolCode(), req.custCode(), req.custName(), req.city(), req.region(),
                req.schoolName(), req.isSchool() == null || req.isSchool(), req.schoolType(),
                req.clientCategory(), req.salesChannel(), req.memo());
        // 학교/학원검색(29p) 축 — DSRE가 주지 않는 값이라 등록 시 함께 받는다.
        school.applySearchFields(req.cityCode(), req.partnerLoc(),
                req.mockPartnerCode(), req.mockPartnerName(),
                req.icPartnerCode(), req.icPartnerName());
        // 학년별 학급수·재학생 수(F-22). 안 보내면 비워 둔다 —
        // 응시현황이 "자료 있는 학교만" 세므로 0 으로 깔면 보급률이 틀어진다.
        school.updateStudentCounts(req.cls1ClassCnt(), req.cls1StudentCnt(),
                req.cls2ClassCnt(), req.cls2StudentCnt(),
                req.cls3ClassCnt(), req.cls3StudentCnt());
        school.updateManageYn(req.manageYn());
        return SchoolResponse.from(schoolRepository.save(school));
    }

    /**
     * 등록된 판매채널 목록(조회화면 콤보). 레거시 {@code CommonDB.vb:475} 와 같은 방식 —
     * 코드표 없이 학교 행에 적힌 값을 모은다.
     */
    @Transactional(readOnly = true)
    public java.util.List<String> salesChannels() {
        return schoolRepository.findSalesChannels();
    }

    @Transactional
    public SchoolResponse update(Long id, SchoolUpdateRequest req) {
        School school = getOrThrow(id);
        school.update(req.custCode(), req.custName(), req.city(), req.region(), req.schoolName(),
                req.isSchool() == null || req.isSchool(), req.schoolType(), req.clientCategory(),
                req.salesChannel(), req.memo());
        // 학년별 학급수·재학생 수(F-22·F-23). 안 보낸 칸은 그대로 둔다 —
        // 학교 수정은 다른 칸을 비워 보내는 경우가 많아, 여기서 null 을 0 으로 깔면
        // 응시현황 보급률이 조용히 빈 칸이 된다.
        school.updateStudentCounts(req.cls1ClassCnt(), req.cls1StudentCnt(),
                req.cls2ClassCnt(), req.cls2StudentCnt(),
                req.cls3ClassCnt(), req.cls3StudentCnt());
        school.updateManageYn(req.manageYn());
        return SchoolResponse.from(school);
    }

    // ── DSRE 가져오기(보존형 동기화) ──────────────────────────────────────

    /**
     * DSRE2 지사↔학교 매핑을 읽어 병합. DSRE 연동이 꺼져 있으면 오류.
     */
    @Transactional
    public SchoolSyncResult syncFromDsre() {
        DsreGateway gateway = dsreGateway.getIfAvailable();
        if (gateway == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "DSRE 연동이 비활성 상태입니다(daesung.dsre.enabled=false).");
        }
        return merge(gateway.readSchoolRefs());
    }

    /**
     * 보존형 병합(발주처 확정 2026-08, 35p) — <b>전체삭제 후 재수입 금지</b>.
     *
     * <p>이전 방식은 동기화 한 번에 수기 입력값(거래처구분·학교/학원구분·메모)과
     * 매출프로그램 전용 거래처가 통째로 날아갔다. 그래서 규칙이 넷이다.
     * <ol>
     *   <li>매칭키 (거래처코드, 학교코드)가 있으면 → <b>DSRE 관리 필드만</b> 덮어쓰고 수기 필드는 유지
     *   <li>없으면 → 신규 추가(source=DSRE)
     *   <li>DSRE2에서 사라진 DSRE 행 → 삭제하지 않고 미사용(active=false). 과거 매출 이력과 끊기면 안 됨
     *   <li>매출프로그램 전용(MANUAL) 행 → 동기화 대상에서 제외. 미사용 처리도 하지 않음
     * </ol>
     *
     * <p>{@code merge}를 별도 공개 메서드로 둔 이유: DSRE 연결 없이도 이 병합 규칙을 테스트로 고정하기 위함.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public SchoolSyncResult merge(List<SchoolRefRow> rows) {
        int added = 0;
        int updated = 0;
        int missingPartnerInfo = 0;
        Set<String> incoming = new HashSet<>();

        for (SchoolRefRow row : rows) {
            String custCode = (row.custCode() == null) ? "" : row.custCode();
            incoming.add(key(custCode, row.schoolCode()));
            if (row.custName() == null) {
                missingPartnerInfo++;   // DSRE2 고아 매핑 — 버리지 않고 건수만 드러낸다
            }

            School school = schoolRepository.findByCustCodeAndSchoolCode(custCode, row.schoolCode()).orElse(null);
            if (school == null) {
                School created = School.create(row.schoolCode(), custCode, row.custName(), row.city(), row.region(),
                        // 판매채널은 DSRE가 주지 않는 수기 필드다 — 동기화로 덮지 않는다.
                        row.schoolName(), row.school(), null, null, null, null).fromDsre();
                // 담당 특약점(매출코드)도 DSRE2가 준다 — 레거시 학교관리.vb:86과 같다.
                created.applyDsrePartnerCodes(row.machulCode(), row.partnerLabel());
                schoolRepository.save(created);
                added++;
            } else {
                // 수기 필드(거래처구분·학교/학원구분·메모)는 건드리지 않는다.
                school.applyDsreFields(row.custName(), row.city(), row.region(), row.schoolName(), row.school());
                school.applyDsrePartnerCodes(row.machulCode(), row.partnerLabel());
                updated++;
            }
        }

        // DSRE2에서 사라진 행 = 미사용. MANUAL 행은 애초에 조회 대상이 아니라 안전하다.
        int deactivated = 0;
        for (School s : schoolRepository.findBySource(SchoolSource.DSRE)) {
            if (s.isActive() && !incoming.contains(key(s.getCustCode(), s.getSchoolCode()))) {
                s.markUnused();
                statusHistoryService.record(StatusEntityType.SCHOOL, s.getId(), "active",
                        true, false, "DSRE 동기화: 원본에서 사라져 미사용 처리");
                deactivated++;
            }
        }
        int keptManual = schoolRepository.findBySource(SchoolSource.MANUAL).size();

        return new SchoolSyncResult(rows.size(), added, updated, deactivated, keptManual, missingPartnerInfo);
    }

    private static String key(String custCode, String schoolCode) {
        return custCode + " " + schoolCode;
    }

    private School getOrThrow(Long id) {
        return schoolRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학교가 없습니다. id=" + id));
    }
}
