package com.daesung.sales.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 프론트 회신(2026-09-17) ⑧⑨⑩⑪ 회귀 고정.
 *
 * <pre>
 *   ⑧ 수금 조회기준 — 기간을 기장일자로도 자를 수 있어야 한다
 *   ⑨ 수불부 정렬 — sort= 를 바꿔도 순서가 같았다
 *   ⑩ 엑셀 3경로 — 매출액정리·회차별작업현황·학교/학원검색
 *   ⑪ 마감 해제 사유 — 해제 memo 가 마감 memo 로 덮였다
 * </pre>
 *
 * <p>★네 건의 공통점: <b>조용히 무시하던 것들</b>이다. 파라미터를 받고도 안 쓰거나(⑨),
 * 한 칸을 둘이 돌려쓰거나(⑪) 하면 화면엔 오류가 안 뜬다 — 담당자는 먹은 줄 알고 그 화면을 믿는다.
 * 그래서 "받는다"가 아니라 <b>"실제로 달라진다"</b>를 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("프론트 회신 ⑧⑨⑩⑪(2026-09-17)")
class FrontFeedbackIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-FF" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2092;

    private Long partner;
    private Long wh;
    private String bookCode;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "FFS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "FFP" + SFX, "name", "피드백거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "FFW" + SFX, "name", "피드백창고", "type", "MAIN"));

        bookCode = "FFB" + SFX;
        Map<String, Object> b = new HashMap<>();
        b.put("code", bookCode);
        b.put("name", "피드백도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "F" + YEAR + "A");
        b.put("catName", "피드백분류");
        b.put("salesDivision", "교재");
        Long book = createId("/masters/products", b);

        // ★정렬을 검증하려면 행이 둘 이상이어야 한다 — 한 종이면 순서가 바뀔 수가 없다.
        Map<String, Object> b2 = new HashMap<>(b);
        b2.put("code", bookCode + "B");
        b2.put("salesDivision", "교재");
        b2.put("name", "피드백도서2");
        Long book2 = createId("/masters/products", b2);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500),
                        Map.of("productId", book2, "unitCost", 3000, "qty", 100))));
        post("/sales/entries", Map.of("salesDate", YEAR + "-02-10", "partnerId", partner,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 70, "qty", 30))));

        // ★수금일자와 기장일자를 **다른 달**로 둔다 — 같으면 기준을 바꿔도 결과가 같아
        //   테스트가 통과하면서 아무것도 검증하지 못한다.
        JsonNode c = post("/closing/collections", Map.of(
                "collDate", YEAR + "-03-15", "writeDate", YEAR + "-05-20",
                "partnerId", partner, "collKind", "도서대금", "collType", "CASH",
                "collAmt", 100000));
        assertThat(c.path("success").asBoolean()).as("수금 등록: %s", c).isTrue();
    }

    // ── ⑧ 수금 조회기준 ─────────────────────────────────────────────

    private long collCount(String query) {
        return data(get("/closing/collections" + query + "&partnerId=" + partner + "&size=100"))
                .path("totalElements").asLong();
    }

    @Test
    @DisplayName("★수금 조회기준 — 3월(수금일)과 5월(기장일)이 기준에 따라 갈린다")
    void 수금_조회기준() {
        String march = "?fromDate=" + YEAR + "-03-01&toDate=" + YEAR + "-03-31";
        String may = "?fromDate=" + YEAR + "-05-01&toDate=" + YEAR + "-05-31";

        assertThat(collCount(march + "&dateBasis=COLL")).as("수금일 기준 3월").isEqualTo(1);
        assertThat(collCount(march + "&dateBasis=WRITE")).as("기장일 기준 3월엔 없다").isZero();

        assertThat(collCount(may + "&dateBasis=WRITE")).as("기장일 기준 5월").isEqualTo(1);
        assertThat(collCount(may + "&dateBasis=COLL")).as("수금일 기준 5월엔 없다").isZero();

        assertThat(collCount(march)).as("미지정이면 수금일자(기본)").isEqualTo(1);
    }

    @Test
    @DisplayName("모르는 조회기준은 400 — 조용히 기본값으로 넘기면 다른 목록을 보게 된다")
    void 수금_기준_오류() {
        JsonNode r = get("/closing/collections?dateBasis=기장&partnerId=" + partner);

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("조회기준", "WRITE");
    }

    // ── ⑨ 수불부 정렬 ───────────────────────────────────────────────

    @Test
    @DisplayName("★수불부 정렬 — sort 를 바꾸면 실제로 순서가 바뀐다")
    void 수불부_정렬() {
        String base = "/stock/ledger?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31&size=200";

        String mine = "&warehouseId=" + wh;
        JsonNode asc = data(get(base + mine + "&sort=closing,asc")).path("content");
        JsonNode desc = data(get(base + mine + "&sort=closing,desc")).path("content");

        assertThat(asc).hasSizeGreaterThan(1);
        // 오름차순은 앞이 더 작거나 같고, 내림차순은 정반대여야 한다.
        assertThat(asc.get(0).path("closing").asLong())
                .isLessThanOrEqualTo(asc.get(asc.size() - 1).path("closing").asLong());
        assertThat(desc.get(0).path("closing").asLong())
                .isEqualTo(asc.get(asc.size() - 1).path("closing").asLong());
    }

    @Test
    @DisplayName("모르는 정렬 필드는 400 — 기본 순서로 돌려주면 정렬이 먹은 줄 안다")
    void 수불부_정렬_오류() {
        JsonNode r = get("/stock/ledger?sort=없는필드,desc");

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("정렬", "closing");
    }

    // ── ⑩ 엑셀 3경로 ────────────────────────────────────────────────

    @Test
    @DisplayName("★엑셀 3경로 — 매출액정리·회차별작업현황·학교/학원검색")
    void 엑셀_3경로() {
        String range = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

        assertThat(downloadSize("/sales/summary/export" + range)).isPositive();
        assertThat(downloadSize("/sales/round-work-status/export" + range)).isPositive();
        assertThat(downloadSize("/masters/schools/search/export")).isPositive();
    }

    private int downloadSize(String path) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setBearerAuth(token());
        byte[] body = rest.exchange("/api/v1" + path, org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(h), byte[].class).getBody();
        return (body == null) ? 0 : body.length;
    }

    // ── ⑪ 마감 해제 사유 ────────────────────────────────────────────

    @Test
    @DisplayName("★마감 해제 사유가 마감 사유를 덮지 않는다")
    void 마감_해제사유() {
        JsonNode locked = post("/closing/periods/lock",
                Map.of("year", YEAR, "month", 7, "memo", "7월 정기마감"));
        assertThat(locked.path("success").asBoolean()).as("마감: %s", locked).isTrue();
        JsonNode unlocked = post("/closing/periods/unlock",
                Map.of("year", YEAR, "month", 7, "memo", "세금계산서 정정"));

        assertThat(unlocked.path("success").asBoolean()).as("%s", unlocked).isTrue();
        JsonNode d = data(unlocked);
        assertThat(d.path("memo").asText()).as("마감 사유는 그대로").isEqualTo("7월 정기마감");
        assertThat(d.path("unlockMemo").asText()).as("해제 사유는 따로").isEqualTo("세금계산서 정정");
        assertThat(d.path("unlockedBy").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("★다시 마감해도 해제 기록은 남는다 — 한 번 열렸다는 사실이 감사 대상이다")
    void 재마감해도_해제기록_유지() {
        post("/closing/periods/lock", Map.of("year", YEAR, "month", 8, "memo", "8월 마감"));
        post("/closing/periods/unlock", Map.of("year", YEAR, "month", 8, "memo", "누락분 추가"));
        JsonNode again = post("/closing/periods/lock",
                Map.of("year", YEAR, "month", 8, "memo", "8월 재마감"));

        JsonNode d = data(again);
        assertThat(d.path("memo").asText()).as("재마감 응답: %s", again).isEqualTo("8월 재마감");
        assertThat(d.path("unlockMemo").asText()).as("지워지면 안 된다").isEqualTo("누락분 추가");
    }
}
