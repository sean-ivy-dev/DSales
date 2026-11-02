package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 조회 필터 <b>다중선택</b> 회귀 고정.
 * 근거: 발주처 화면검토(2026-08-31) 화면3 — "무상/유상/반품/입고 <b>중복선택</b> 체크박스",
 * 공통 — "좌측 트리뷰 + 체크박스 다중선택".
 *
 * <p>★고정하려는 것은 넷이다.
 * <ol>
 *   <li>여러 개를 고르면 <b>합집합</b>으로 나온다(체크박스의 뜻).</li>
 *   <li>단수 파라미터가 <b>계속 먹는다</b> — 프론트가 이미 붙어 있다.
 *       필터가 조용히 안 먹으면 에러도 없이 전체가 보여 오래 안 들킨다.</li>
 *   <li>입고·폐기가 섞여도 <b>나머지는 살아남는다</b>. 단 그것만 고르면 빈 결과.</li>
 *   <li>폐기 <b>요약과 상세가 같은 답</b>을 한다 — 갈리면 둘 다 못 믿는다.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("조회 필터 다중선택(화면3·공통 트리뷰)")
class MultiSelectFilterIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-MS" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2062;
    private static final String D1 = YEAR + "-03-05";

    private Long partnerA;
    private Long partnerB;
    private Long book;
    private Long wh1;
    private Long wh2;

    @BeforeAll
    void seed() {
        token();
        partnerA = createId("/masters/clients", Map.of("code", "MSA" + SFX, "name", "다중가", "type", "NORMAL"));
        partnerB = createId("/masters/clients", Map.of("code", "MSB" + SFX, "name", "다중나", "type", "NORMAL"));
        Long sup = createId("/masters/clients", Map.of("code", "MSS" + SFX, "name", "인쇄", "type", "NORMAL"));
        wh1 = createId("/masters/warehouses", Map.of("code", "MW1" + SFX, "name", "창고하나", "type", "MAIN"));
        wh2 = createId("/masters/warehouses", Map.of("code", "MW2" + SFX, "name", "창고둘", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "MSP" + SFX);
        b.put("name", "다중도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        // 분류코드 형식: [영문1자][연도4자][영문·숫자1~3자]
        b.put("catCode", "M" + YEAR + (System.nanoTime() % 100));
        b.put("catName", "다중분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh1,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh2,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));

        // 회계구분 3종을 깔아 둔다 — 체크박스가 실제로 갈라내는지 보려면 필요하다.
        // ★반품은 출고가 먼저 있어야 한다(반품가능수량 검증). 그래서 B에도 출고를 깐다.
        sale(partnerA, "NORMAL_SHIP", 10);   // SALE
        sale(partnerA, "GIFT", 3);           // FREE
        sale(partnerB, "NORMAL_SHIP", 5);    // SALE
        sale(partnerB, "RETURN", 2);         // RETURN

        post("/disposals", Map.of("processedDate", YEAR + "-04-10", "warehouseId", wh1,
                "items", List.of(Map.of("productId", book, "qty", 6, "reason", "파본"))));
        post("/disposals", Map.of("processedDate", YEAR + "-04-11", "warehouseId", wh2,
                "items", List.of(Map.of("productId", book, "qty", 4, "reason", "파본"))));
    }

    private void sale(Long partnerId, String shipmentType, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", D1, "partnerId", partnerId, "warehouseId", wh1,
                "items", List.of(Map.of("productId", book, "shipmentType", shipmentType,
                        "unitPrice", 10000, "supplyRate", 75, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("매출 등록: %s", r).isTrue();
    }

    private String range() {
        return "?startDate=" + YEAR + "-01-01&endDate=" + YEAR + "-12-31&size=200";
    }

    /** 조회 결과의 회계구분들. 우리 거래처 것만 남긴다 — 다른 테스트 데이터가 섞이면 안 된다. */
    private List<String> categories(String query) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : data(get("/sales" + range() + query)).path("content")) {
            long pid = n.path("partnerId").asLong();
            if (pid == partnerA || pid == partnerB) {
                out.add(n.path("salesCategory").asText());
            }
        }
        return out;
    }

    @Test
    @DisplayName("★거래분류 둘을 고르면 둘 다 나온다 — 합집합이 체크박스의 뜻")
    void 거래분류_다중선택() {
        assertThat(categories("&tradeClasses=FREE,RETURN"))
                .containsExactlyInAnyOrder("FREE", "RETURN");

        // 파라미터를 반복해도 같다(프론트가 어느 쪽으로 보내든)
        assertThat(categories("&tradeClasses=FREE&tradeClasses=RETURN"))
                .containsExactlyInAnyOrder("FREE", "RETURN");

        assertThat(categories("&tradeClasses=SALES"))
                .as("하나만 고르면 매출만 — 두 거래처의 출고 2건").containsOnly("SALE").hasSize(2);
    }

    @Test
    @DisplayName("★단수 파라미터가 계속 먹는다 — 조용히 무시되면 전체가 보여도 안 들킨다")
    void 단수_하위호환() {
        assertThat(categories("&tradeClass=FREE")).containsExactly("FREE");
        assertThat(categories("&salesCategory=RETURN")).containsExactly("RETURN");

        // 단수 + 복수는 합집합 — 단수는 '원소가 하나인 다중선택'이다
        assertThat(categories("&tradeClass=SALES&tradeClasses=RETURN"))
                .containsExactlyInAnyOrder("SALE", "SALE", "RETURN");
    }

    @Test
    @DisplayName("★입고가 섞여도 무상은 살아남는다 — 섞였다고 다 죽이면 담당자가 손해")
    void 입고가_섞여도_나머지는_산다() {
        assertThat(categories("&tradeClasses=FREE,INBOUND"))
                .as("입고는 재고 원장 거래라 무시되고 무상만 남는다")
                .containsExactly("FREE");
    }

    @Test
    @DisplayName("입고·폐기만 고르면 빈 결과 — 전체를 주면 '폐기'로 걸렀는데 매출이 쏟아진다")
    void 입고폐기만_고르면_빈결과() {
        assertThat(categories("&tradeClasses=INBOUND,DISPOSE")).isEmpty();
        assertThat(categories("&tradeClass=DISPOSE")).as("단수도 종전대로").isEmpty();
    }

    @Test
    @DisplayName("거래분류와 구분(상세)을 같이 주면 교집합 — 별개 축이라 둘 다 만족해야 한다")
    void 두_축은_교집합() {
        assertThat(categories("&tradeClasses=SALES,FREE&salesCategories=FREE"))
                .containsExactly("FREE");
        assertThat(categories("&tradeClasses=SALES&salesCategories=RETURN"))
                .as("어긋나는 조합은 빈 결과").isEmpty();
    }

    @Test
    @DisplayName("거래처를 여러 개 체크한다(좌측 트리뷰)")
    void 거래처_다중선택() {
        assertThat(categories("&partnerIds=" + partnerA + "," + partnerB))
                .as("둘 다 — 4건").hasSize(4);
        assertThat(categories("&partnerIds=" + partnerB))
                .as("하나만 체크하면 그 거래처만").containsExactlyInAnyOrder("SALE", "RETURN");
    }

    @Test
    @DisplayName("★폐기 요약과 상세가 같은 창고 다중선택에 같은 답을 한다")
    void 폐기_요약과_상세가_일치() {
        String q = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&warehouseIds=" + wh1 + "," + wh2 + "&productIds=" + book;

        long detail = 0;
        for (JsonNode r : rows(get("/disposals?size=200&" + q.substring(1)))) {
            detail += r.path("qty").asLong();
        }
        assertThat(detail).as("6 + 4").isEqualTo(10);
        assertThat(data(get("/disposals/summary" + q)).path("totalQty").asLong())
                .as("요약이 상세와 갈리면 둘 다 못 믿는다").isEqualTo(detail);

        // 창고 하나만 체크하면 양쪽 모두 그 창고만
        String one = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&warehouseIds=" + wh2 + "&productIds=" + book;
        assertThat(data(get("/disposals/summary" + one)).path("totalQty").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("입고이력도 창고·작업구분 다중선택")
    void 입고이력_다중선택() {
        String q = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31&productIds=" + book;

        assertThat(data(get("/stock/records" + q + "&warehouseIds=" + wh1 + "," + wh2)))
                .as("두 창고 입고 2건").hasSize(2);
        assertThat(data(get("/stock/records" + q + "&warehouseIds=" + wh1)))
                .as("한 창고만").hasSize(1);
        assertThat(data(get("/stock/records" + q + "&kinds=INBOUND,TRANSFER")))
                .as("작업구분 다중선택 — 입고만 있으니 2건").hasSize(2);
        assertThat(data(get("/stock/records" + q + "&kinds=TRANSFER")))
                .as("이고는 없다").isEmpty();
    }

    /**
     * 폐기 목록은 <b>페이지</b>로 온다(9/27 항목 10 "페이지네이션 필수").
     * 검증은 전량을 봐야 해서 size 를 크게 주고 content 만 꺼낸다.
     */
    private com.fasterxml.jackson.databind.JsonNode rows(com.fasterxml.jackson.databind.JsonNode res) {
        return data(res).path("content");
    }
}
