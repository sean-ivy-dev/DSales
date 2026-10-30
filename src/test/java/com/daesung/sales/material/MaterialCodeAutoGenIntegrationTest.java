package com.daesung.sales.material;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 자재코드 자동 채번(10/27 검수 피드백 §11-3).
 *
 * <p>원문: "자재 등록에서 <b>자재코드를 직접 입력하지 않고 자동으로 끌어와 채운다</b>(확정).
 * 지금은 직접 입력이고 '한번 정하면 바꿀 수 없습니다'라고 안내한다. 어디서 끌어올지는 개발이
 * 정한다. <b>기존 매출프로그램과 DSRE의 자재코드 체계를 확인해 같은 규칙으로 맞춘다.</b>"
 *
 * <p>★<b>규칙 = DSRE와 같은 순수 숫자 순번 + 10만 대역.</b> DSRE 운영덤프(2026-10-26) 실측 —
 * {@code tbl_materials_info.MAT_CD}가 {@code int auto_increment}(1~12,334, 10,991건)라
 * "같은 규칙"이 곧 "그냥 순번"이다. 1부터 쓰면 DSRE 자재와 번호가 겹치므로
 * {@code 100001}부터 쓴다(DSRE 연 증가 평균 ~1,500건 → 10만까지 30~58년).
 *
 * <p>‼️<b>절대값을 기대하지 않는다.</b> 통합테스트는 컨테이너 DB를 클래스 간 공유하고
 * 워커 JVM도 재사용되므로, 다른 테스트가 자재를 만들면 번호가 달라진다. 그래서
 * "10만 이상"·"직전 코드 +1"처럼 <b>관계</b>로만 검증한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("자재코드 자동 채번(§11-3)")
class MaterialCodeAutoGenIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-MC" + (System.nanoTime() % 1_000_000L);

    @BeforeAll
    void seed() {
        token();
    }

    @Test
    @DisplayName("★코드를 안 보내도 등록된다 — 10만 번대 숫자를 서버가 매긴다")
    void 서버가_매긴다() {
        JsonNode r = post("/masters/materials", Map.of(
                "name", "자동채번 시험지" + SFX, "materialType", "EXAM_PAPER"));

        assertThat(r.path("success").asBoolean()).as("코드는 필수값이 아니다: %s", r).isTrue();
        String code = data(r).path("code").asText();
        assertThat(code).as("숫자만으로 이뤄진다(DSRE MAT_CD와 같은 모양)").matches("\\d+");
        assertThat(Long.parseLong(code))
                .as("10만 이상 — DSRE 대역(1~12,334)과 겹치지 않는다")
                .isGreaterThanOrEqualTo(100_001L);
    }

    @Test
    @DisplayName("연속 등록하면 1씩 올라간다")
    void 순번은_1씩() {
        long first = Long.parseLong(data(post("/masters/materials", Map.of(
                "name", "연속A" + SFX, "materialType", "OMR"))).path("code").asText());
        long second = Long.parseLong(data(post("/masters/materials", Map.of(
                "name", "연속B" + SFX, "materialType", "OMR"))).path("code").asText());

        assertThat(second).isEqualTo(first + 1);
    }

    @Test
    @DisplayName("★자재구분이 달라도 채번을 공유한다 — DSRE와 동일(구분은 코드가 아니라 컬럼)")
    void 구분별로_갈리지_않는다() {
        long paper = Long.parseLong(data(post("/masters/materials", Map.of(
                "name", "공유채번 시험지" + SFX, "materialType", "EXAM_PAPER"))).path("code").asText());
        long label = Long.parseLong(data(post("/masters/materials", Map.of(
                "name", "공유채번 라벨" + SFX, "materialType", "LABEL"))).path("code").asText());

        // 구분별로 1부터 리셋한다면 두 값이 같거나 역전된다. 하나의 순번을 공유해야 +1이다.
        assertThat(label).as("구분이 달라도 다음 번호를 받는다").isEqualTo(paper + 1);
    }

    @Test
    @DisplayName("보낸 코드는 무시된다 — 화면이 옛 값을 보내도 400이 아니다")
    void 보낸코드_무시() {
        // ‼️필드를 아예 거부하면 code를 아직 보내는 현행 화면이 통째로 죽는다.
        // "받아서 버린다"가 안전한 전환이다.
        Map<String, Object> req = new HashMap<>();
        req.put("code", "담당자가적은코드" + SFX);
        req.put("name", "보낸코드무시" + SFX);
        req.put("materialType", "ETC");

        JsonNode r = post("/masters/materials", req);

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("code").asText())
                .as("보낸 값이 아니라 서버 채번값이 들어간다")
                .isNotEqualTo("담당자가적은코드" + SFX)
                .matches("\\d+");
    }

    @Test
    @DisplayName("생성된 코드로 목록에서 다시 찾을 수 있다 — BOM 매칭이 쓰는 식별자")
    void 생성코드로_조회() {
        String code = data(post("/masters/materials", Map.of(
                "name", "조회검증 단행본" + SFX, "materialType", "BOOK"))).path("code").asText();

        boolean found = false;
        for (JsonNode n : data(get("/masters/materials?keyword=조회검증 단행본" + SFX))) {
            if (code.equals(n.path("code").asText())) {
                found = true;
            }
        }
        assertThat(found).as("채번된 코드 %s 가 목록에 그대로 보인다", code).isTrue();
    }
}
