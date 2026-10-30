package com.daesung.sales.logistics;

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
 * 거래순번(Shipment.tradeSeq) — "거래처별로 그 날 안에서 1부터". 근거: 10/27 검수 피드백
 * §3-6·8-2. 전에는 필드 기본값 1에 멈춰 있어 작업결과 화면의 거래순번 칸이 항상 1이었다.
 *
 * <p>각 테스트가 독립된 거래일자를 써서 서로의 범위(partnerId+거래일자)가 안 섞이게 한다.
 *
 * <p>★<b>2026-10-30 뒤집힘</b> — 처음엔 순번을 <b>발송(작업요청서) 단위</b>에 얹었다.
 * 발송은 (일자·거래처·학교·분류)로 묶여서, <b>같은 날 같은 학교로 다섯 번 주문하면
 * 발송 한 건에 합쳐지고 순번도 1개뿐</b>이었다(프론트 B-2② 지적). 명세 §3-6 예시
 * "하루에 5번 주문하면 1~5"와 §8-2 "주문 단위"를 둘 다 어긴다.
 * 이제 순번은 <b>주문(매출등록 1회 = entryNo 하나)</b>에 붙는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("거래순번(10/27 피드백 §3-6·8-2)")
class TradeSeqIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-TS" + (System.nanoTime() % 1_000_000L);

    private Long partner;
    private Long otherPartner;
    private Long wh;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "TSS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "TSP" + SFX, "name", "거래순번거래처", "type", "NORMAL"));
        otherPartner = createId("/masters/clients",
                Map.of("code", "TSQ" + SFX, "name", "거래순번거래처2", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "TSW" + SFX, "name", "물류", "type", "MAIN"));
        book = createId("/masters/products", Map.of("code", "TSB" + SFX, "name", "거래순번도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75));
        post("/stock/inbound", Map.of("processedDate", "2034-05-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));
    }

    private void sell(Long partnerId, String date, String schoolCode) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("salesDate", date);
        body.put("partnerId", partnerId);
        body.put("warehouseId", wh);
        body.put("items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", 1,
                "schoolCode", schoolCode, "schoolName", schoolCode + "학교")));
        JsonNode r = post("/sales/entries", body);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private int tradeSeqOf(Long partnerId, String date, String schoolCode) {
        String range = "?fromDate=" + date + "&toDate=" + date + "&partnerId=" + partnerId;
        for (JsonNode row : data(get("/logistics/work-results" + range)).path("content")) {
            if (schoolCode.equals(row.path("schoolCode").asText())) {
                return row.path("tradeSeq").asInt();
            }
        }
        throw new AssertionError("학교코드 " + schoolCode + "의 작업결과 행을 못 찾음");
    }

    @Test
    @DisplayName("★같은 거래처·같은 날짜면 발송 건마다 순번이 1,2,3…으로 쌓인다")
    void 같은거래처_같은날_순번누적() {
        String date = "2034-05-10";
        sell(partner, date, "TS001" + SFX);
        sell(partner, date, "TS002" + SFX);
        sell(partner, date, "TS003" + SFX);

        assertThat(tradeSeqOf(partner, date, "TS001" + SFX)).isEqualTo(1);
        assertThat(tradeSeqOf(partner, date, "TS002" + SFX)).isEqualTo(2);
        assertThat(tradeSeqOf(partner, date, "TS003" + SFX)).isEqualTo(3);
    }

    @Test
    @DisplayName("★날짜가 바뀌면 같은 거래처라도 순번이 1부터 다시 시작한다")
    void 날짜바뀌면_리셋() {
        String dayA = "2034-05-11";
        String dayB = "2034-05-12";
        sell(partner, dayA, "TS004" + SFX);
        sell(partner, dayA, "TS005" + SFX);
        sell(partner, dayB, "TS006" + SFX);

        assertThat(tradeSeqOf(partner, dayA, "TS005" + SFX)).isEqualTo(2);
        assertThat(tradeSeqOf(partner, dayB, "TS006" + SFX)).as("날짜가 바뀌면 리셋돼야").isEqualTo(1);
    }

    @Test
    @DisplayName("★같은 날짜라도 거래처가 다르면 순번이 섞이지 않고 각자 1부터 시작한다")
    void 거래처다르면_독립적으로_1부터() {
        String date = "2034-05-13";
        sell(partner, date, "TS007" + SFX);
        sell(otherPartner, date, "TS008" + SFX);

        assertThat(tradeSeqOf(partner, date, "TS007" + SFX)).isEqualTo(1);
        assertThat(tradeSeqOf(otherPartner, date, "TS008" + SFX)).as("다른 거래처는 독립된 범위").isEqualTo(1);
    }

    @Test
    @DisplayName("★같은 날 같은 학교로 5번 주문하면 1~5 다 — 명세 §3-6 예시 그대로")
    void 같은학교_다섯번() {
        // ‼️이것이 뒤집힌 지점이다. 예전 구현은 발송이 한 건으로 합쳐져 **순번도 1 하나**였다.
        String date = "2034-06-11";
        for (int i = 0; i < 5; i++) {
            sell(partner, date, "SAME" + SFX);
        }

        java.util.List<Integer> seqs = new java.util.ArrayList<>();
        String range = "?fromDate=" + date + "&toDate=" + date + "&partnerId=" + partner;
        for (JsonNode row : data(get("/logistics/work-orders" + range)).path("content")) {
            seqs.add(row.path("tradeSeq").asInt());
        }

        assertThat(seqs).as("주문 다섯 건 → 발송도 다섯 건").hasSize(5);
        assertThat(seqs).as("1~5 가 하나씩").containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("한 주문이 학교·분류로 갈려도 순번은 하나 — 순번은 발송이 아니라 주문에 붙는다")
    void 한주문은_순번하나() {
        // 한 번의 등록에 학교 둘을 넣는다 → 발송은 둘로 갈리지만 같은 주문이다.
        String date = "2034-06-12";
        post("/sales/entries", Map.of("salesDate", date, "partnerId", partner, "warehouseId", wh,
                "items", List.of(
                        Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", 1,
                                "schoolCode", "SPLITA" + SFX, "schoolName", "갈림A"),
                        Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", 1,
                                "schoolCode", "SPLITB" + SFX, "schoolName", "갈림B"))));

        java.util.List<Integer> seqs = new java.util.ArrayList<>();
        String range = "?fromDate=" + date + "&toDate=" + date + "&partnerId=" + partner;
        for (JsonNode row : data(get("/logistics/work-orders" + range)).path("content")) {
            seqs.add(row.path("tradeSeq").asInt());
        }

        assertThat(seqs).as("학교가 둘이라 발송은 둘").hasSize(2);
        assertThat(seqs).as("같은 주문이므로 순번은 같은 값 하나")
                .containsExactly(seqs.get(0), seqs.get(0));
    }

    @Test
    @DisplayName("매출 조회가 거래순번을 내려주고, 그 값으로 주문 한 건만 추릴 수 있다(B-2①③)")
    void 조회조건_거래순번() {
        String date = "2034-06-13";
        sell(partner, date, "Q1" + SFX);
        sell(partner, date, "Q2" + SFX);

        String base = "/sales?fromDate=" + date + "&toDate=" + date + "&partnerId=" + partner;
        JsonNode all = data(get(base + "&size=50"));
        assertThat(all.path("totalElements").asLong()).isEqualTo(2);

        int first = all.path("content").get(0).path("tradeSeq").asInt();
        assertThat(first).as("응답에 거래순번이 실린다").isPositive();

        JsonNode one = data(get(base + "&tradeSeq=" + first + "&size=50"));
        assertThat(one.path("totalElements").asLong()).as("그 주문 한 건만").isEqualTo(1);
        assertThat(one.path("content").get(0).path("tradeSeq").asInt()).isEqualTo(first);
    }
}
