package com.daesung.sales.school;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.dsre.gateway.SchoolRefRow;
import com.daesung.sales.school.dto.SchoolSyncResult;
import com.daesung.sales.school.entity.School;
import com.daesung.sales.school.entity.SchoolSource;
import com.daesung.sales.school.entity.SchoolType;
import com.daesung.sales.school.repository.SchoolRepository;
import com.daesung.sales.school.service.SchoolService;
import com.daesung.sales.support.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 학교관리 DSRE '가져오기' 보존형 동기화 회귀 고정(35p, 발주처 확정 2026-08).
 *
 * <p>이전 방식(전체삭제 후 재수입)에서 실제로 터졌던 사고를 그대로 재현해 막는다 —
 * 동기화 한 번에 <b>수기 입력값</b>(거래처구분·학교학원구분·메모)과 <b>매출프로그램 전용 거래처</b>가
 * 통째로 날아가던 문제. DSRE 연결 없이 {@code merge()}에 원본 행을 직접 넣어 규칙만 검증한다.
 */
class SchoolSyncIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private SchoolService schoolService;

    @Autowired
    private SchoolRepository schoolRepository;

    private static SchoolRefRow row(String custCode, String schoolCode, String schoolName, String city) {
        return new SchoolRefRow(custCode, schoolCode, true, "대성지사", city, "서울관할", schoolName,
                "A10A105", "서울 대성지사");
    }

    private School find(String custCode, String schoolCode) {
        return schoolRepository.findByCustCodeAndSchoolCode(custCode, schoolCode).orElseThrow();
    }

    private static SchoolRefRow rowWithMachul(String custCode, String schoolCode, String machulCode) {
        return new SchoolRefRow(custCode, schoolCode, true, "대성지사", "서울", "서울관할", "대성고",
                machulCode, (machulCode == null) ? null : "서울 대성지사");
    }

    @Test
    @DisplayName("★담당 특약점(매출코드)을 DSRE MACHUL_CD에서 채운다 — 레거시 학교관리.vb:86과 같다")
    void 담당특약점_매출코드_채움() {
        schoolService.merge(List.of(rowWithMachul("S900", "90001", "A10A151")));

        School s = find("S900", "90001");
        assertThat(s.getMockPartnerCode()).isEqualTo("A10A151");
        assertThat(s.getIcPartnerCode()).as("레거시는 같은 MACHUL_CD를 양쪽에 넣는다").isEqualTo("A10A151");
        assertThat(s.getMockPartnerName()).isEqualTo("서울 대성지사");
    }

    @Test
    @DisplayName("★MACHUL_CD가 비어 있으면 덮어쓰지 않는다 — DSRE2에 공란인 거래처가 실재한다")
    void 매출코드_공란이면_유지() {
        schoolService.merge(List.of(rowWithMachul("S901", "90002", "A10A105")));
        assertThat(find("S901", "90002").getMockPartnerCode()).isEqualTo("A10A105");

        // 같은 학교가 MACHUL_CD 없이 다시 들어온다
        schoolService.merge(List.of(rowWithMachul("S901", "90002", null)));

        School after = find("S901", "90002");
        assertThat(after.getMockPartnerCode()).as("빈 값으로 지워지면 안 된다").isEqualTo("A10A105");
        assertThat(after.getIcPartnerCode()).isEqualTo("A10A105");
    }

    @Test
    @DisplayName("동기화는 DSRE 관리 필드만 덮어쓰고 수기 입력값(거래처구분·학교학원구분·메모)은 보존한다")
    void 수기입력값_보존() {
        // 최초 동기화로 유입
        schoolService.merge(List.of(row("S001", "10001", "대성고", "서울")));

        // 담당자가 매출프로그램에서 수기로 채워 넣음 — 동기화 대상이 아닌 필드들
        School s = find("S001", "10001");
        schoolService.update(s.getId(), new com.daesung.sales.school.dto.SchoolUpdateRequest(
                "S001", "대성지사", "서울", "서울관할", "대성고", true,
                SchoolType.HAKWON, "특약점", "자체몰", "담당자 메모",
                // 학년별 학급수·재학생 수(F-22) — 이 테스트는 그 축을 보지 않으므로 비운다.
                // ‼️null 은 "안 고친다"는 뜻이라 동기화 보존 검증에 영향이 없다.
                null, null, null, null, null, null,
                // 관리대상 여부(F-22) — 이 테스트는 그 축을 보지 않으므로 비운다(null = 안 고침)
                null));

        // 두 번째 동기화 — DSRE 쪽 학교명·도시가 바뀐 상태로 다시 들어옴
        SchoolSyncResult result = schoolService.merge(List.of(row("S001", "10001", "대성고등학교", "성남")));

        School after = find("S001", "10001");
        assertThat(after.getSchoolName()).as("DSRE 관리 필드는 갱신").isEqualTo("대성고등학교");
        assertThat(after.getCity()).as("DSRE 관리 필드는 갱신").isEqualTo("성남");
        assertThat(after.getClientCategory()).as("수기 입력값 보존").isEqualTo("특약점");
        assertThat(after.getSchoolType()).as("수기 입력값 보존").isEqualTo(SchoolType.HAKWON);
        assertThat(after.getMemo()).as("수기 입력값 보존").isEqualTo("담당자 메모");
        assertThat(result.added()).isZero();
        assertThat(result.updated()).isEqualTo(1);
    }

    @Test
    @DisplayName("매출프로그램 전용 데이터(DSRE2 미존재)는 동기화가 건드리지 않는다 — 삭제도 미사용도 안 함")
    void 전용데이터_보호() {
        // 문서 예시와 같은 매출프로그램 전용 거래처(DSRE2에 없음)
        long id = createId("/masters/schools", java.util.Map.of(
                "schoolCode", "90001", "custCode", "20005", "custName", "온라인스터디카페",
                "schoolName", "온라인스터디카페", "clientCategory", "자사몰"));

        // 전용 데이터가 전혀 포함되지 않은 DSRE 결과로 동기화
        schoolService.merge(List.of(row("S002", "10002", "한빛고", "부산")));

        School kept = schoolRepository.findById(id).orElseThrow();
        assertThat(kept.getSource()).isEqualTo(SchoolSource.MANUAL);
        assertThat(kept.isActive()).as("전용 데이터는 미사용 처리되면 안 됨").isTrue();
        assertThat(kept.getClientCategory()).isEqualTo("자사몰");
        assertThat(kept.getCustName()).isEqualTo("온라인스터디카페");
    }

    @Test
    @DisplayName("DSRE2에서 사라진 행은 삭제가 아니라 미사용 처리되고, 다시 나타나면 되살아난다")
    void 사라진행_미사용처리_후_복원() {
        schoolService.merge(List.of(row("S003", "10003", "가람고", "대구"), row("S003", "10004", "나람고", "대구")));

        // 10004가 DSRE2에서 빠진 상태로 재동기화
        SchoolSyncResult gone = schoolService.merge(List.of(row("S003", "10003", "가람고", "대구")));
        assertThat(gone.deactivated()).isEqualTo(1);
        assertThat(find("S003", "10004").isActive()).as("삭제가 아니라 미사용").isFalse();
        assertThat(find("S003", "10004").getSchoolName()).as("과거 이력 보존").isEqualTo("나람고");

        // 다시 등장하면 되살아남
        schoolService.merge(List.of(row("S003", "10003", "가람고", "대구"), row("S003", "10004", "나람고", "대구")));
        assertThat(find("S003", "10004").isActive()).as("재등장 시 복원").isTrue();
    }

    @Test
    @DisplayName("매칭키는 거래처코드+학교코드 복합 — 같은 학교코드라도 거래처가 다르면 별개 행")
    void 복합키_매칭() {
        SchoolSyncResult r = schoolService.merge(List.of(
                row("S004", "10005", "공유고", "인천"),
                row("S005", "10005", "공유고", "인천")));

        assertThat(r.added()).as("거래처가 다르면 별개 행으로 생성").isEqualTo(2);
        assertThat(find("S004", "10005").getId()).isNotEqualTo(find("S005", "10005").getId());
    }
}
