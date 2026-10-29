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
}
