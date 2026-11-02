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
 * 외상매출현황 — 거래처코드 첫 글자가 바뀔 때마다 "소 계" 행이 낀다(10/27 피드백 §7-C
 * "소계 구간도 레거시와 동일하게 — 레거시 6구간, 테스트 2구간").
 *
 * <p>근거: 레거시 실DB(DSLab.bak·DSTxtBook.bak 복원) 재현 — 9월 기준 연구소 단독 174명은
 * 6구간(A·#·D·E·G·H), 법인 통합(273명, 라이브 "전체 273건"과 일치) 기준은 대성교재 소속
 * 대형서점(B)이 더해져 7구간. "6"은 레거시가 두 법인을 분리 운영하던 시절 연구소 화면
 * 단독 숫자이고, 우리는 법인 통합이 확정이라 그대로 둔다 — 구간 수를 6으로 고정하지 않는다
 * (몇 개가 나오든 "첫 글자가 바뀌면 소계" 규칙 자체가 맞는지만 고정한다).
 *
 * <p>'A'는 "A30C" 기준으로 한 번 더 쪼갠다(레거시 "인터넷 서점 별도 계산" 특례,
 * {@code 외상매출현황조회.vb:343-347}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출현황 소계 구간(10/27 피드백 §7-C)")
class ArStatusSubtotalGroupIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SG" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2091;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long wh;
    private Long book;
    // 거래처코드 첫 4자리를 고정해 그룹 경계를 직접 통제한다 — SFX는 뒤에 붙어 유일성만 보장.
    private String codeALow;   // "A10X..." → A30C보다 사전식으로 앞 → "A" 그룹
    private String codeAHigh;  // "A99X..." → A30C보다 뒤 → "#" 그룹(인터넷서점 특례)
    private String codeD;      // "D"로 시작 → "D" 그룹

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "SGS" + SFX, "name", "인쇄", "type", "NORMAL"));
        wh = createId("/masters/warehouses", Map.of("code", "SGW" + SFX, "name", "물류", "type", "MAIN"));
        book = createId("/masters/products", Map.of("code", "SGB" + SFX, "name", "소계도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));

        codeALow = "A10X" + SFX;
        codeAHigh = "A99X" + SFX;
        codeD = "D1" + SFX;

        createId("/masters/clients", Map.of("code", codeALow, "name", "A그룹거래처", "type", "NORMAL"));
        createId("/masters/clients", Map.of("code", codeAHigh, "name", "샵그룹거래처(인터넷서점)", "type", "NORMAL"));
        createId("/masters/clients", Map.of("code", codeD, "name", "D그룹거래처", "type", "NORMAL"));

        sell(codeALow);
        sell(codeAHigh);
        sell(codeD);
    }

    private void sell(String partnerCode) {
        Long partnerId = findPartnerId(partnerCode);
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-03-10", "partnerId", partnerId, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 75, "qty", 5))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private Long findPartnerId(String code) {
        for (JsonNode p : data(get("/masters/clients?keyword=" + code)).path("content")) {
            if (code.equals(p.path("code").asText())) {
                return p.path("id").asLong();
            }
        }
        throw new AssertionError("거래처 못 찾음: " + code);
    }

    private JsonNode rows() {
        return data(get("/closing/ar-status" + RANGE)).path("rows");
    }

    /** 내 테스트 데이터(코드 ALow/AHigh/D)가 등장하는 구간만 추려, 순서대로 라벨을 뽑는다. */
    private List<String> myRegionLabels() {
        List<String> out = new java.util.ArrayList<>();
        boolean started = false;
        for (JsonNode r : rows()) {
            String code = r.path("partnerCode").asText();
            boolean isSubtotal = "소 계".equals(code);
            boolean isMine = codeALow.equals(code) || codeAHigh.equals(code) || codeD.equals(code);
            if (isMine) {
                started = true;
            }
            if (started) {
                out.add(isSubtotal ? "[소계]" : code);
            }
            // D그룹 소계까지 담았으면 그만(그 뒤는 내 데이터와 무관한 다른 그룹).
            if (started && isSubtotal && !out.isEmpty() && codeD.equals(lastNonSubtotal(out))) {
                break;
            }
        }
        return out;
    }

    private String lastNonSubtotal(List<String> labels) {
        for (int i = labels.size() - 1; i >= 0; i--) {
            if (!"[소계]".equals(labels.get(i))) {
                return labels.get(i);
            }
        }
        return null;
    }

    @Test
    @DisplayName("★거래처코드 첫 글자가 바뀌면 그 사이에 '소 계' 행이 낀다")
    void 첫글자_바뀌면_소계() {
        List<String> labels = myRegionLabels();

        // A(낮은 코드) → 소계 → #(A30C 이상, 인터넷서점 특례) → 소계 → D → 소계
        assertThat(labels).as("구간 순서: %s", labels)
                .containsSubsequence(codeALow, "[소계]", codeAHigh, "[소계]", codeD, "[소계]");
    }

    @Test
    @DisplayName("같은 'A' 접두어라도 A30C 기준으로 다른 그룹이 된다 — 둘 사이에 소계가 껴야 한다")
    void A와_샵은_다른_그룹() {
        List<String> labels = myRegionLabels();
        int idxLow = labels.indexOf(codeALow);
        int idxHigh = labels.indexOf(codeAHigh);

        assertThat(idxLow).as("A10X 코드가 먼저 나와야: %s", labels).isLessThan(idxHigh);
        // 둘 사이 어딘가에 소계가 있어야 한다(바로 다음이 아니어도 되지만, 다른 그룹인 이상 꼭 낀다).
        boolean hasSubtotalBetween = labels.subList(idxLow + 1, idxHigh).contains("[소계]");
        assertThat(hasSubtotalBetween).as("A10X와 A99X 사이에 소계가 껴야: %s", labels).isTrue();
    }

    @Test
    @DisplayName("키워드 검색 시엔 소계 행이 안 섞여 나온다 — 필터링된 부분합이 전체 소계처럼 보이면 안 된다")
    void 검색시엔_소계없음() {
        JsonNode r = data(get("/closing/ar-status" + RANGE + "&keyword=" + codeD)).path("rows");
        for (JsonNode row : r) {
            assertThat(row.path("partnerCode").asText()).as("검색 결과에 소계 행 없어야").isNotEqualTo("소 계");
        }
    }
}
