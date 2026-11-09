package com.daesung.sales.product;

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
 * 상품 <b>완전 삭제</b> — 참조가 없을 때만(F-36).
 *
 * <p>발주처 요청: "(우클릭) **삭제** 기능. 지금은 사용 중지만 있음".
 * 레거시는 실제로 물리삭제다(`도서관리.vb:467` — 그리드 행 제거 후 {@code DataAdapter.Update}).
 *
 * <p>‼️<b>우리는 그대로 못 한다.</b> 레거시는 FK 가 없었고 우리는 매출·재고가 상품을 가리킨다.
 * 지우면 과거 매출의 상품이 사라져 수불부·리포트가 깨진다. 그래서
 * <b>참조가 하나라도 있으면 거부</b>하고, 무엇이 몇 건인지 알려준다.
 *
 * <p>★이 테스트가 지키는 것은 <b>"참조가 있는데 지워지는 일이 없다"</b>는 것이다.
 * 참조 테이블이 늘었는데 집계 쿼리에 안 넣으면 FK 오류로 500 이 나므로, 대표 두 축
 * (재고·매출)으로 막히는지 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("상품 완전 삭제 — 참조 없을 때만(F-36)")
class ProductHardDeleteIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-HD" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2098-05-12";

    private Long partner;
    private Long warehouse;
    private Long supplier;

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients",
                Map.of("code", "HDS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "HDP" + SFX, "name", "삭제검증거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "HDW" + SFX, "name", "창고", "type", "MAIN"));
    }

    private Long book(String code) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", code);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2098A01");
        b.put("catName", "삭제검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        return createId("/masters/products", b);
    }

    @Test
    @DisplayName("참조가 없으면 **실제로 지워진다** — 잘못 만든 도서를 바로 치우는 용도")
    void 참조없으면_삭제() {
        Long id = book("HDA" + SFX);
        assertThat(get("/masters/products/" + id).path("success").asBoolean()).isTrue();

        JsonNode res = del("/masters/products/" + id + "/hard");
        assertThat(res.path("success").asBoolean()).as("삭제: %s", res).isTrue();

        // 행이 사라졌으니 조회가 404 다(논리삭제였다면 여전히 200 이다).
        JsonNode after = get("/masters/products/" + id);
        assertThat(after.path("success").asBoolean()).as("지운 뒤 조회: %s", after).isFalse();
    }

    @Test
    @DisplayName("재고가 걸려 있으면 거부 — 무엇이 몇 건인지 알려준다")
    void 재고있으면_거부() {
        Long id = book("HDB" + SFX);
        assertThat(post("/stock/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", id, "qty", 10, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();

        JsonNode res = del("/masters/products/" + id + "/hard");
        assertThat(res.path("success").asBoolean()).as("거부돼야 한다: %s", res).isFalse();
        String msg = res.path("error").path("message").asText();
        assertThat(msg).contains("참조가 있어");
        // ‼️어디에 몇 건 걸렸는지 **숫자까지** 나와야 담당자가 판단할 수 있다.
        assertThat(msg).contains("재고");
        assertThat(msg).contains("건");
        // 안내도 함께 — 그냥 막기만 하면 담당자는 무엇을 해야 할지 모른다.
        assertThat(msg).contains("사용 중지");

        // 거부됐으니 상품은 그대로 있다.
        assertThat(get("/masters/products/" + id).path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("매출이 걸려 있으면 거부 — 과거 매출의 상품이 사라지면 안 된다")
    void 매출있으면_거부() {
        Long id = book("HDC" + SFX);
        assertThat(post("/stock/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", id, "qty", 50, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();
        assertThat(post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", id, "shipmentType", "NORMAL_SHIP",
                        "qty", 5, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();

        JsonNode res = del("/masters/products/" + id + "/hard");
        assertThat(res.path("success").asBoolean()).as("거부돼야 한다: %s", res).isFalse();
        assertThat(res.path("error").path("message").asText()).contains("매출");
    }

    @Test
    @DisplayName("사용 중지(논리삭제)는 그대로 — 행이 남고 목록에서만 빠진다")
    void 논리삭제는_그대로() {
        Long id = book("HDD" + SFX);
        assertThat(del("/masters/products/" + id).path("success").asBoolean()).isTrue();

        // 행은 살아 있다
        assertThat(get("/masters/products/" + id).path("success").asBoolean()).isTrue();
        // 기본 목록에서는 빠지고, includeUnused=true 면 보인다
        assertThat(found("/masters/products?keyword=HDD" + SFX, id)).isFalse();
        assertThat(found("/masters/products?includeUnused=true&keyword=HDD" + SFX, id)).isTrue();
    }

    /**
     * 목록 조건 <b>3가지</b> — 빼고 보기 / 함께 보기 / <b>사용 중지만</b>(프론트 요청 2026-11-09).
     *
     * <p>‼️「사용 중지만」은 <b>화면에서 걸러낼 수 없다</b> — 페이징이라 페이지마다 사용중지
     * 비율이 달라 총 건수·페이지 수가 틀어진다. 그래서 서버 조건이어야 한다.
     */
    @Test
    @DisplayName("‼️`useYn=false` 는 **사용 중지만** 낸다 — 쓰는 도서가 섞이면 안 된다")
    void 사용중지만_보기() {
        Long live = book("HDE" + SFX);                 // 쓰는 도서
        Long stopped = book("HDF" + SFX);              // 사용 중지할 도서
        assertThat(del("/masters/products/" + stopped).path("success").asBoolean()).isTrue();

        // 사용 중지만 — 중지한 것만 나오고 쓰는 것은 빠진다
        assertThat(found("/masters/products?useYn=false&keyword=" + SFX, stopped)).isTrue();
        assertThat(found("/masters/products?useYn=false&keyword=" + SFX, live)).isFalse();

        // 사용중만 — 반대
        assertThat(found("/masters/products?useYn=true&keyword=" + SFX, live)).isTrue();
        assertThat(found("/masters/products?useYn=true&keyword=" + SFX, stopped)).isFalse();

        // 함께 보기 — 둘 다
        assertThat(found("/masters/products?includeUnused=true&keyword=" + SFX, live)).isTrue();
        assertThat(found("/masters/products?includeUnused=true&keyword=" + SFX, stopped)).isTrue();

        // ‼️useYn 이 includeUnused 를 이긴다 — 둘을 같이 보내도 "사용 중지만"이다
        assertThat(found("/masters/products?includeUnused=true&useYn=false&keyword=" + SFX, live))
                .as("useYn 이 더 구체적인 조건이라 이겨야 한다").isFalse();
    }

    private boolean found(String path, Long id) {
        JsonNode res = get(path);
        for (JsonNode n : res.path("data").path("content")) {
            if (n.path("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }
}
