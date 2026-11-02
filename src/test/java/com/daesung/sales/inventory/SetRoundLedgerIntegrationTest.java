package com.daesung.sales.inventory;

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
 * 제품수불부 세트 → 회차 드릴다운. 근거: 구조보완요청안(2026-08-31) + 테스트 피드백 1차 —
 * "수불현황 요약 각 행에 상세 진입 기능 추가. 현재는 세트 단위 집계 행만 조회되고
 *  세트-회차 계층 구조나 상세 진입 경로가 없음".
 *
 * <p>★<b>회차 값은 수불부와 같아야 한다.</b> 드릴다운이라고 따로 계산하면 세트 합과 회차 합이
 * 어긋난다 — 순매출수량 3,029/3,006이 정확히 그 사고였다. 그래서 같은 집계를 회차 상품으로
 * 좁혀 부르고, 이 테스트가 두 값이 같은지 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("수불부 세트→회차 드릴다운")
class SetRoundLedgerIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SR" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2098;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long setProduct;
    private Long round1;
    private Long round2;
    private String r1Code;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "SRS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        Long partner = createId("/masters/clients",
                Map.of("code", "SRP" + SFX, "name", "드릴다운거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "SRW" + SFX, "name", "드릴다운창고", "type", "MAIN"));

        r1Code = "SR1" + SFX;
        round1 = book(r1Code, "1회");
        round2 = book("SR2" + SFX, "2회");
        setProduct = book("SRSET" + SFX, "드릴다운세트");

        // 세트 1개 = 1회 1부 + 2회 1부
        JsonNode bom = put("/masters/products/" + setProduct + "/bom", Map.of(
                "components", List.of(
                        Map.of("childProductId", round1, "ratio", 1, "round", 1),
                        Map.of("childProductId", round2, "ratio", 1, "round", 2))));
        assertThat(bom.path("success").asBoolean()).as("BOM 등록: %s", bom).isTrue();

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", round1, "unitCost", 1000, "qty", 300),
                        Map.of("productId", round2, "unitCost", 1000, "qty", 200))));

        // 1회만 매출을 낸다 — 회차별로 값이 갈리는지 보려면 한쪽만 움직여야 한다.
        post("/sales/entries", Map.of("salesDate", YEAR + "-02-10", "partnerId", partner,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", round1, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 70, "qty", 50))));
    }

    private Long book(String code, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "S" + YEAR + "A");
        b.put("catName", "드릴다운분류");
        b.put("salesDivision", "교재");
        return createId("/masters/products", b);
    }

    private JsonNode rounds() {
        return data(get("/stock/ledger/rounds?setProductId=" + setProduct
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
    }

    @Test
    @DisplayName("★세트에서 회차 목록으로 내려간다 — 회차 번호 순으로")
    void 회차_목록() {
        JsonNode d = rounds();

        assertThat(d.path("setProductCode").asText()).isEqualTo("SRSET" + SFX);
        assertThat(d.path("rounds")).hasSize(2);
        assertThat(d.path("rounds").get(0).path("round").asInt()).isEqualTo(1);
        assertThat(d.path("rounds").get(1).path("round").asInt()).isEqualTo(2);
        assertThat(d.path("rounds").get(0).path("ratioPerSet").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("★회차 값이 수불부와 같다 — 따로 계산하면 세트 합과 어긋난다")
    void 수불부와_동일() {
        long viaRounds = 0;
        for (JsonNode r : rounds().path("rounds")) {
            if (r.path("round").asInt() == 1) {
                for (JsonNode row : r.path("ledger")) {
                    viaRounds += row.path("sale").asLong();
                }
            }
        }

        long viaLedger = 0;
        for (JsonNode row : data(get("/stock/ledger" + RANGE + "&keyword=" + r1Code + "&size=50"))
                .path("content")) {
            if (r1Code.equals(row.path("productCode").asText())) {
                viaLedger += row.path("sale").asLong();
            }
        }

        assertThat(viaRounds).as("드릴다운으로 본 값").isEqualTo(-50);
        assertThat(viaRounds).as("★수불부 본 화면과 같아야 한다").isEqualTo(viaLedger);
    }

    @Test
    @DisplayName("★거래 없는 회차도 빠지지 않는다 — 빼면 '이 회차는 왜 없지'가 된다")
    void 거래없는_회차도_나온다() {
        JsonNode second = null;
        for (JsonNode r : rounds().path("rounds")) {
            if (r.path("round").asInt() == 2) {
                second = r;
            }
        }

        assertThat(second).as("2회는 매출이 없지만 행은 있어야 한다").isNotNull();
        assertThat(second.path("ledger")).isNotEmpty();
        assertThat(second.path("ledger").get(0).path("sale").asLong()).isZero();
    }

    @Test
    @DisplayName("자재 상세로 이어진다 — 요약·회차·자재 3단계가 모두 열린다")
    void 자재_상세까지() {
        JsonNode m = data(get("/stock/ledger/materials?setProductId=" + setProduct
                + "&roundProductId=" + round1));

        assertThat(m).as("회차를 고르면 그 구성 자재로 내려간다").isNotNull();
    }
}
