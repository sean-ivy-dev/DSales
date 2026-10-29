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
 * 더프모 외상매출장 레거시 양식 — 10/27 검수 피드백 §7-B.
 *
 * <p>정답지는 발주처가 보낸 실물 PDF({@code RESULT_10003_강남 하람도서_20260801-20260831.pdf})다.
 * 그 인쇄물의 줄 순서가 <b>날짜순도 학교명 가나다순도 아니어서</b> 기준이 무엇인지 레거시
 * 원본에서 확인했다 — {@code 외상매출장조회.vb:672}의
 * {@code ORDER BY 거래년월, isnull(schCode,99999), …} 즉 <b>학교코드순</b>이다.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>정렬이 <b>학교코드</b>순이다(학교명 가나다순이 아니다)</li>
 *   <li>학교코드가 없는 건은 뒤로 간다(레거시 {@code isnull(schCode,99999)})</li>
 *   <li>제목·하단 헤더가 일반 외상매출장(§7-A)과 같다 — 공급자명·입금계좌</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("더프모 외상매출장 레거시 양식(10/27 피드백 §7-B)")
class DuffLedgerLegacyFormatIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DL" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2087;
    private static final String DAY = YEAR + "-09-10";

    private Long partner;
    private Long wh;
    private Long mockSet;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "DLS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "DLP" + SFX, "name", "더프양식거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses", Map.of("code", "DLW" + SFX, "name", "물류", "type", "MAIN"));
        // ★더프 판별 = 분류코드 M + A/B/C 계열(레거시 외상매출장조회.vb:668)
        mockSet = createId("/masters/products", Map.of("code", "DLK" + SFX, "name", "모의고사세트",
                "contentType", "SELF", "price", 10000, "supplyRate", 100,
                "grade", "고3", "catCode", "M" + YEAR + "A01", "catName", "더프 고3",
                "salesDivision", "모의고사", "stockManaged", false));

        // ★학교명 가나다 순서와 학교코드 순서를 **일부러 반대로** 깐다.
        //   이름순이면 가나다고 → 하하하고, 코드순이면 하하하고(S100) → 가나다고(S900).
        sell("S900" + SFX, "가나다고", 10);
        sell("S100" + SFX, "하하하고", 20);
        // 학교코드가 없는 건 — 레거시 isnull(schCode,99999)처럼 맨 뒤로 가야 한다.
        sell(null, "코드없는고", 30);
    }

    private void sell(String schoolCode, String schoolName, int qty) {
        Map<String, Object> item = new java.util.HashMap<>();
        item.put("productId", mockSet);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 100);
        item.put("qty", qty);
        item.put("schoolName", schoolName);
        if (schoolCode != null) {
            item.put("schoolCode", schoolCode);
        }
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DAY, "partnerId", partner, "warehouseId", wh, "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출등록: %s", r).isTrue();
    }

    private JsonNode ledger() {
        return data(get("/closing/ar-ledger/duff?partnerId=" + partner
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
    }

    /** 명세행의 학교명을 나온 순서대로. */
    private List<String> schoolOrder() {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode r : ledger().path("rows")) {
            if ("DETAIL".equals(r.path("rowType").asText())) {
                out.add(r.path("schoolName").asText());
            }
        }
        return out;
    }

    @Test
    @DisplayName("★정렬은 학교코드순이다 — 학교명 가나다순이 아니다")
    void 학교코드순_정렬() {
        List<String> order = schoolOrder();

        // 코드순(S100 → S900)이면 하하하고가 먼저. 이름순이면 가나다고가 먼저다.
        assertThat(order).as("학교코드순이어야: %s", order)
                .containsSubsequence("하하하고", "가나다고");
    }

    @Test
    @DisplayName("★학교코드가 없는 건은 맨 뒤로 간다(레거시 isnull(schCode,99999))")
    void 코드없으면_뒤로() {
        List<String> order = schoolOrder();

        assertThat(order).as("%s", order).contains("코드없는고");
        assertThat(order.indexOf("코드없는고"))
                .as("코드 없는 건이 뒤에 와야: %s", order)
                .isGreaterThan(order.indexOf("가나다고"));
    }

    @Test
    @DisplayName("★제목·하단 헤더 — 공급자명·입금계좌가 일반 외상매출장과 같게 실린다")
    void 헤더_인쇄정보() {
        JsonNode h = ledger();

        assertThat(h.path("partnerName").asText()).isEqualTo("더프양식거래처");
        assertThat(h.path("partnerCode").asText()).isEqualTo("DLP" + SFX);
        assertThat(h.path("supplierName").asText()).as("하단 왼쪽 회사명").isNotEmpty();
        assertThat(h.path("bankAccount").asText()).as("하단 오른쪽 입금계좌").contains("은행");
    }
}
