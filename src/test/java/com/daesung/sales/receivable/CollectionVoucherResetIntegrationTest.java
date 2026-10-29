package com.daesung.sales.receivable;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 수금 전표코드 — 수금일자 안에서만 1부터 센다. 근거: 10/27 검수 피드백 §6-1
 * "지금은 'V-20261026-2'처럼 날짜와 순번을 붙인다. 기존 매출프로그램처럼 해당 수금일자
 * 안에서 1, 2, 3… 순번만 표시한다".
 *
 * <p>★포맷("V-날짜-N")은 그대로 유지했다 — 원문이 "순번만 표시"라고 콕 집은 건 레거시
 * 드롭다운(PPT 11, "1~5")의 화면 표시 방식이고, 전표코드 체계 자체를 "V-" 없이 숫자만으로
 * 바꾸라는 근거까진 못 찾았다. 그래서 "날짜 안에서 1부터 리셋"만 고치고 포맷은 안 건드렸다
 * — 0번 원칙("지적된 항목만 고친다")에 맞춘 보수적 선택.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("수금 전표코드 날짜별 리셋(10/27 피드백 §6-1)")
class CollectionVoucherResetIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CV" + (System.nanoTime() % 1_000_000L);

    /**
     * ‼️날짜 자체가 전표코드 키다 — 연도를 고정하면 테스트 워커 JVM 재사용 시
     * "이미 그 날짜에 등록된 전표가 있어 순번이 1부터 시작 안 함"으로 깨진다(테스트 격리 규율).
     * 매 실행마다 바뀌는 값으로 연도를 잡아 날짜 자체를 고유하게 만든다.
     */
    private static final int YEAR = 2100 + (int) (System.nanoTime() % 50);

    private Long partner;

    @BeforeAll
    void seed() {
        token();
        partner = createId("/masters/clients", Map.of("code", "CV" + SFX, "name", "수금거래처", "type", "NORMAL"));
    }

    private String registerAndGetVoucher(String date) {
        JsonNode r = post("/closing/collections/batch", Map.of(
                "lines", List.of(Map.of("collDate", date, "partnerId", partner,
                        "collType", "CASH", "collAmt", 10000))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r).get(0).path("voucherNo").asText();
    }

    @Test
    @DisplayName("★같은 날짜에 묶음을 여러 번 등록하면 순번이 1,2,3…으로 쌓인다")
    void 같은날짜_순번_누적() {
        String date = YEAR + "-07-01";
        String tag = "V-" + YEAR + "0701-";

        String v1 = registerAndGetVoucher(date);
        String v2 = registerAndGetVoucher(date);
        String v3 = registerAndGetVoucher(date);

        assertThat(v1).isEqualTo(tag + "1");
        assertThat(v2).isEqualTo(tag + "2");
        assertThat(v3).isEqualTo(tag + "3");
    }

    @Test
    @DisplayName("★다른 날짜면 그 전 날짜에서 몇 번을 했든 1부터 다시 시작한다")
    void 날짜바뀌면_리셋() {
        // 같은 클래스 안 다른 테스트와 날짜가 겹치지 않도록 이 테스트 전용 날짜를 쓴다.
        String dayA = YEAR + "-08-10";
        String dayB = YEAR + "-08-11";

        registerAndGetVoucher(dayA);
        registerAndGetVoucher(dayA);
        String v3OnA = registerAndGetVoucher(dayA);
        assertThat(v3OnA).isEqualTo("V-" + YEAR + "0810-3");

        // ‼️예전엔 전역 누적이라 여기서 4가 나왔다 — 날짜가 바뀌었으니 1이어야 한다.
        String v1OnB = registerAndGetVoucher(dayB);
        assertThat(v1OnB).as("날짜가 바뀌면 리셋돼야: %s", v1OnB).isEqualTo("V-" + YEAR + "0811-1");
    }
}
