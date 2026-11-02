package com.daesung.sales.inventory;

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
 * 입고/대체 내역 조회(8p·9p) 회귀 고정.
 * 근거: 프론트가 {@code /inbound/records}를 호출하는데 서버에 조회 경로가 없었다
 * (등록 POST만 존재 — 폐기와 같은 상황).
 *
 * <p>★여기서 지키는 것은 <b>수량 부호를 그대로 준다</b>는 것이다.
 * 폐기는 "12권 버렸다"라 양수로 뒤집었지만, 여기서는 방향이 곧 정보다 —
 * 이고는 출발(−)·도착(+) 두 줄이고 세트작업도 완제품(+)·구성품(−)으로 갈린다.
 * 절댓값으로 바꾸면 그 구분이 통째로 사라진다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("입고/대체 내역 조회(8p·9p)")
class StockRecordsIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SR" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2057;

    private Long mainWh;
    private Long subWh;
    private Long product;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "SRS" + SFX, "name", "인쇄", "type", "NORMAL"));
        mainWh = createId("/masters/warehouses", Map.of("code", "SRW" + SFX, "name", "물류창고", "type", "MAIN"));
        subWh = createId("/masters/warehouses", Map.of("code", "SRX" + SFX, "name", "보조창고", "type", "MAIN"));
        product = createId("/masters/products", Map.of("code", "SRB" + SFX, "name", "내역도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재"));

        // 매입입고 60 → 입고구분·단가가 실려야 한다
        post("/stock/inbound", Map.of("processedDate", YEAR + "-05-01", "supplierClientId", sup,
                "destinationWarehouseId", mainWh, "inboundType", "PURCHASE",
                "items", List.of(Map.of("productId", product, "unitCost", 4000, "qty", 60))));
        // 이고 20 → 출발(−)·도착(+) 두 줄
        post("/stock/transfer", Map.of("processedDate", YEAR + "-05-02",
                "fromWarehouseId", mainWh, "toWarehouseId", subWh,
                "items", List.of(Map.of("productId", product, "qty", 20, "reason", "재배치"))));
    }

    private List<JsonNode> rows(String query) {
        List<JsonNode> out = new java.util.ArrayList<>();
        data(get("/stock/records?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&productId=" + product + query)).forEach(out::add);
        return out;
    }

    @Test
    @DisplayName("★등록한 입고가 조회된다 — 매입구분·단가까지 실린다")
    void 입고_조회() {
        List<JsonNode> in = rows("&kind=INBOUND");
        assertThat(in).hasSize(1);
        JsonNode r = in.get(0);

        assertThat(r.path("kind").asText()).as("한글 표기로 준다").isEqualTo("입고");
        assertThat(r.path("date").asText()).isEqualTo(YEAR + "-05-01");
        assertThat(r.path("warehouse").asText()).isEqualTo("물류창고");
        assertThat(r.path("bookName").asText()).isEqualTo("내역도서");
        assertThat(r.path("qtyDelta").asInt()).isEqualTo(60);
        // 16p 순매출의 매입액이 이 두 값에서 나온다 — 비면 이익률이 통째로 틀린다
        assertThat(r.path("inboundType").asText()).isEqualTo("PURCHASE");
        assertThat(r.path("unitCost").asLong()).isEqualTo(4000);

        // ★입고처가 실려야 **매입/정상 구분의 근거**가 보인다.
        //   이관분은 레거시 거래구분으로 갈랐는데, 입고처가 없어 화면에서
        //   "왜 이게 매입인지"를 확인할 길이 없었다(프론트 실측 2026-10-22 3-1).
        assertThat(r.path("supplierName").asText()).isEqualTo("인쇄");
        assertThat(r.hasNonNull("supplierId")).isTrue();
        assertThat(r.path("supplierCode").asText()).startsWith("SRS");
    }

    @Test
    @DisplayName("입고처 없이 등록한 입고도 조회된다 — 입고처는 선택 입력이다")
    void 입고처_없어도_조회된다() {
        // ★전용 도서를 쓴다 — 공유 도서에 행을 더하면 다른 테스트의 건수 검증이 깨진다
        //   (실제로 그렇게 깨뜨렸다).
        Long solo = createId("/masters/products", Map.of("code", "SRN" + SFX, "name", "입고처없음도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재"));

        // ‼️fetch join 을 INNER 로 걸면 이 행이 통째로 사라진다. LEFT 여야 한다.
        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-05-09", "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", solo, "unitCost", 1000, "qty", 7))))
                .path("success").asBoolean()).isTrue();

        // ‼️rows() 헬퍼는 공유 도서(product)로 서버에서 걸러낸다 — 그걸 타면 이 행이 안 나온다.
        //   (단수 productId 와 복수 productIds 는 서버에서 merge 된다.)
        List<JsonNode> hit = new java.util.ArrayList<>();
        data(get("/stock/records?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&kind=INBOUND&productId=" + solo)).forEach(hit::add);
        assertThat(hit).as("입고처 없는 행도 목록에 있다").hasSize(1);
        assertThat(hit.get(0).hasNonNull("supplierName")).as("입고처는 비어 있다").isFalse();
    }

    @Test
    @DisplayName("★이고는 출발(−)·도착(+) 두 줄로 나온다 — 부호를 뒤집으면 방향이 사라진다")
    void 이고_두줄_부호() {
        List<JsonNode> tr = rows("&kind=TRANSFER");
        assertThat(tr).as("한 번의 이고가 두 줄").hasSize(2);

        int from = 0;
        int to = 0;
        for (JsonNode r : tr) {
            assertThat(r.path("kind").asText()).isEqualTo("단순이고");
            if ("물류창고".equals(r.path("warehouse").asText())) {
                from = r.path("qtyDelta").asInt();
            } else {
                to = r.path("qtyDelta").asInt();
            }
        }
        assertThat(from).as("출발창고는 −").isEqualTo(-20);
        assertThat(to).as("도착창고는 +").isEqualTo(20);
        assertThat(from + to).as("이고는 총량을 바꾸지 않는다").isZero();
    }

    @Test
    @DisplayName("구분을 안 주면 입고·이고가 함께, 매출·폐기는 섞이지 않는다")
    void 전체조회_범위() {
        List<JsonNode> all = rows("");
        assertThat(all).hasSize(3);   // 입고 1 + 이고 2

        for (JsonNode r : all) {
            assertThat(r.path("kind").asText())
                    .as("입고/대체 화면에 매출·폐기가 섞이면 안 된다")
                    .isIn("입고", "단순이고", "세트조립", "세트해체");
        }
    }

    @Test
    @DisplayName("창고로 좁혀진다")
    void 창고필터() {
        assertThat(rows("&warehouseId=" + subWh)).as("보조창고는 이고 도착 1줄").hasSize(1);
        assertThat(rows("&warehouseId=" + subWh).get(0).path("qtyDelta").asInt()).isEqualTo(20);
    }
}
