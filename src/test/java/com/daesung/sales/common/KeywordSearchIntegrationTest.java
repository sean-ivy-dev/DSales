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
 * 조회화면 키워드 검색 — 정본 공통 요구("전 화면 Ctrl+F 검색 기본 제공").
 *
 * <p>리포트 11곳이 {@code keyword}를 <b>받지도 않았다.</b> 더 나쁜 건 400도 아니고
 * <b>조용히 무시</b>했다는 점이다 — 화면은 검색했다고 믿는데 전체가 나오니, 담당자는
 * "검색이 안 먹네"가 아니라 <b>"이 거래처엔 이만큼 있구나"</b>로 잘못 읽는다.
 * (2026-09-16 프론트 번들 {@code unsupported:['keyword']} + 라이브 실측으로 확인)
 *
 * <p>★그래서 고정하는 것은 "파라미터를 받는다"가 아니라 <b>실제로 줄어든다</b>는 사실이다.
 * 받기만 하고 안 거르면 고치기 전과 똑같다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("조회 키워드 검색(2026-09-16 프론트 번들 실측)")
class KeywordSearchIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-KW" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2082;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    /** 검색으로 남겨야 할 것 / 걸러내야 할 것 — 두 벌을 만든다. */
    // ‼️한쪽 이름이 다른 쪽의 부분문자열이면 안 된다 — 부분일치라 둘 다 걸려
    //   "안 걸러진다"는 실패가 나고, 원인을 코드에서 찾게 된다(실제로 한 번 겪었다).
    private static final String HIT = "알파상사";
    private static final String MISS = "베타서적";

    private Long hitPartner;
    private String hitBook;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "KWS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        hitPartner = createId("/masters/clients",
                Map.of("code", "KWA" + SFX, "name", HIT, "type", "NORMAL"));
        Long miss = createId("/masters/clients",
                Map.of("code", "KWB" + SFX, "name", MISS, "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "KWW" + SFX, "name", "키워드창고", "type", "MAIN"));

        hitBook = "KWBK" + SFX;
        Long book = product(hitBook, "알파도서");
        Long book2 = product("KWBM" + SFX, "베타도서");

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500),
                        Map.of("productId", book2, "unitCost", 3000, "qty", 500))));

        sale(wh, hitPartner, book, 10);
        sale(wh, miss, book2, 10);

        post("/closing/collections", Map.of("collDate", YEAR + "-03-01", "partnerId", hitPartner,
                "collKind", "도서대금", "collType", "CASH", "collAmt", 50000));
        post("/closing/collections", Map.of("collDate", YEAR + "-03-01", "partnerId", miss,
                "collKind", "도서대금", "collType", "CASH", "collAmt", 50000));
    }

    private Long product(String code, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "K" + YEAR + "A");
        b.put("catName", "키워드분류");
        b.put("salesDivision", "교재");
        return createId("/masters/products", b);
    }

    private void sale(Long wh, Long partner, Long book, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 70, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    /** 응답에서 행 배열을 찾아 준다 — 리포트마다 rows/content/lines/invoices로 이름이 다르다. */
    private JsonNode rows(String path) {
        JsonNode d = data(get(path));
        for (String k : new String[]{"rows", "content", "lines", "invoices"}) {
            if (d.has(k)) {
                return d.path(k);
            }
        }
        return d;
    }

    private void 좁혀진다(String path, String kw, String 남을것, String 사라질것) {
        String sep = path.contains("?") ? "&" : "?";
        String all = toText(rows(path));
        String hit = toText(rows(path + sep + "keyword=" + kw));

        assertThat(all).as("%s — 검색 전에는 둘 다 있어야 전제가 성립한다", path)
                .contains(남을것).contains(사라질것);
        assertThat(hit).as("%s — 검색어와 맞는 건 남는다", path).contains(남을것);
        assertThat(hit).as("★%s — 안 맞는 건 사라져야 한다(조용히 무시하던 자리)", path)
                .doesNotContain(사라질것);
    }

    private String toText(JsonNode n) {
        return n.toString();
    }

    @Test
    @DisplayName("★거래처 기준 리포트 — 미수금현황·수익신고·매출대비표·수금")
    void 거래처_기준() {
        좁혀진다("/closing/ar-status" + RANGE, HIT, HIT, MISS);
        좁혀진다("/closing/revenue-report?taxType=FREE&" + RANGE.substring(1), HIT, HIT, MISS);
        좁혀진다("/sales/yoy-comparison" + RANGE, HIT, HIT, MISS);
        좁혀진다("/closing/collections" + RANGE + "&size=100", HIT, HIT, MISS);
    }

    @Test
    @DisplayName("★도서 기준 리포트 — 순매출조회·매출액정리")
    void 도서_기준() {
        좁혀진다("/sales/net-summary" + RANGE, hitBook, hitBook, "KWBM" + SFX);
        좁혀진다("/sales/summary" + RANGE, hitBook, hitBook, "KWBM" + SFX);
    }

    @Test
    @DisplayName("외상매출장 — 명세 줄이 좁혀지되 이월·마감은 그대로다")
    void 외상매출장() {
        String p = "/closing/ar-ledger?partnerId=" + hitPartner
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";
        JsonNode before = data(get(p));
        JsonNode after = data(get(p + "&keyword=" + hitBook));

        assertThat(after.path("lines")).isNotEmpty();
        // ‼️걸러진 줄만으로 잔액을 다시 계산하면 "검색했더니 잔액이 달라졌다"가 된다.
        assertThat(after.path("opening").asLong()).isEqualTo(before.path("opening").asLong());
        assertThat(after.path("closing").asLong()).isEqualTo(before.path("closing").asLong());
    }

    @Test
    @DisplayName("빈 키워드는 전체 — 공백을 검색으로 치면 화면이 텅 빈다")
    void 빈_키워드() {
        int all = rows("/closing/ar-status" + RANGE).size();
        assertThat(all).as("전제: 걸러지기 전 행이 있어야 한다").isPositive();

        assertThat(rows("/closing/ar-status" + RANGE + "&keyword=").size())
                .as("빈 값은 '검색 안 함'이다").isEqualTo(all);

        // ‼️공백만 넣은 경우는 URL로 못 태운다 — `%20`을 그대로 쓰면 테스트 클라이언트가
        //   한 번 더 인코딩해 리터럴 "%20"이 검색어로 들어간다(실제로 여기서 한 번 헛짚었다).
        //   공백 처리(trim 후 blank면 무시)는 Keywords.norm이 담당하고 아래로 대신 고정한다.
        assertThat(com.daesung.sales.common.query.Keywords.norm("   ")).isNull();
        assertThat(com.daesung.sales.common.query.Keywords.norm(null)).isNull();
        assertThat(com.daesung.sales.common.query.Keywords.norm("  알파 ")).isEqualTo("알파");
    }

    @Test
    @DisplayName("안 맞으면 빈 결과 — '전부 보여주기'로 흘러가면 안 된다")
    void 안_맞으면_빈결과() {
        assertThat(rows("/closing/ar-status" + RANGE + "&keyword=없는거래처zzz")).isEmpty();
        assertThat(rows("/sales/net-summary" + RANGE + "&keyword=없는도서zzz")).isEmpty();
    }
}
