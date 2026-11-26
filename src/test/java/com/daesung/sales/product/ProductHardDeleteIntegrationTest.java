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

    /**
     * ‼️<b>세트 구성 비우기</b> — 비울 길이 아예 없었다(발주처 2026-11-26).
     *
     * <p>{@code PUT /{id}/bom} 은 구성 목록이 비면 거부한다({@code @NotEmpty}) —
     * 그래서 한 번 세트가 된 도서는 <b>구성을 풀 길이 없었다.</b>
     *
     * <p>★★<b>비운 뒤에는 완전 삭제까지 된다</b>(PM 승인 2026-11-26).
     * 구성 비우기는 <b>논리삭제</b>(언제 어떤 구성이었는지 남긴다)라, 참조 검사가 그 기록까지
     * 세는 동안은 <b>비워도 지울 수 없었다.</b> 두 가지를 <b>함께</b> 바꿨다 —
     * ①검사에서 지워진 기록을 빼고 ②완전 삭제가 그 기록을 물리삭제한다.
     * ‼️한쪽만 바꾸면 FK 가 가리키는 도서가 없어져 <b>500</b> 이 난다. 이 테스트가 둘을 같이 묶는다.
     */
    @Test
    @DisplayName("★★세트 구성 **비우기** → 그 뒤 완전 삭제까지 된다(PM 승인 2026-11-26)")
    void 세트구성_비우면_삭제된다() {
        Long parent = book("HDZ" + SFX);
        Long child = book("HDY" + SFX);

        assertThat(put("/masters/products/" + parent + "/bom", Map.of(
                "components", List.of(Map.of("childProductId", child, "ratio", 1))))
                .path("success").asBoolean()).isTrue();
        assertThat(get("/masters/products/" + parent).path("data").path("set").asBoolean())
                .as("세트로 표시된다").isTrue();

        // 구성이 걸려 있으면 삭제가 거부된다.
        assertThat(del("/masters/products/" + parent + "/hard").path("success").asBoolean())
                .as("‼️구성이 남아 있는데 지워지면 구성품이 떠돈다").isFalse();

        JsonNode cleared = del("/masters/products/" + parent + "/bom");
        assertThat(cleared.path("success").asBoolean()).as("구성 비우기: %s", cleared).isTrue();
        assertThat(get("/masters/products/" + parent).path("data").path("set").asBoolean())
                .as("‼️세트 표시가 남으면 구성품 없는 세트가 된다").isFalse();
        assertThat(get("/masters/products/" + parent + "/bom").path("data").path("components").size())
                .isZero();

        // ‼️비운 뒤에는 지워진다 — 검사가 지워진 기록을 빼고, 삭제가 그 기록을 물리삭제한다.
        JsonNode gone = del("/masters/products/" + parent + "/hard");
        assertThat(gone.path("success").asBoolean()).as("비운 뒤 완전 삭제: %s", gone).isTrue();
        assertThat(get("/masters/products/" + parent).path("success").asBoolean())
                .as("행이 사라져야 한다").isFalse();
    }

    /**
     * ‼️<b>지우지 않고 참조 건수만</b> 본다 — "지우기 전에 대상 목록 먼저"(PM 2026-11-26).
     *
     * <p>전엔 삭제를 호출해 보는 수밖에 없었고, 참조가 없는 도서면 <b>그 호출로 정말 지워졌다.</b>
     */
    @Test
    @DisplayName("★참조 건수 조회 — **아무것도 바꾸지 않는다**(전엔 삭제를 호출해 봐야 알았다)")
    void 참조건수만_본다() {
        Long parent = book("HDR" + SFX);
        Long child = book("HDQ" + SFX);
        assertThat(put("/masters/products/" + parent + "/bom", Map.of(
                "components", List.of(Map.of("childProductId", child, "ratio", 1))))
                .path("success").asBoolean()).isTrue();

        JsonNode refs = data(get("/masters/products/" + parent + "/references"));
        assertThat(refs.path("세트구성").asLong()).as("구성 1건: %s", refs).isEqualTo(1L);

        // ★조회가 도서를 건드리지 않았다.
        assertThat(get("/masters/products/" + parent).path("success").asBoolean())
                .as("‼️조회만 했는데 도서가 사라지면 안 된다").isTrue();

        // 비우면 0건이 되고, 그 뒤 지워진다.
        assertThat(del("/masters/products/" + parent + "/bom").path("success").asBoolean()).isTrue();
        assertThat(data(get("/masters/products/" + parent + "/references")).path("세트구성").isNumber())
                .as("지워진 기록은 세지 않는다").isFalse();
        assertThat(del("/masters/products/" + parent + "/hard").path("success").asBoolean()).isTrue();
    }

    /**
     * ‼️<b>이미 지운 매출은 삭제를 막지 않는다</b> — 막고 있었다(2026-11-26).
     *
     * <p>논리삭제를 쓰는 넷(매출·재고이벤트·세트구성·물류단가)을 참조 검사가
     * <b>지워진 행까지 세고</b> 있었다. 그래서 조회에서 이미 사라진 것이 삭제만 막았고,
     * 오류 문구도 「매출 N건」으로 나와 <b>살아 있는 매출이 있는 것처럼</b> 읽혔다.
     *
     * <p>★<b>살아 있는 매출은 여전히 막는다</b> — 이 테스트가 두 방향을 모두 못박는다.
     */
    @Test
    @DisplayName("★★지운 매출은 삭제를 막지 않는다 — **살아 있는 매출은 여전히 막는다**")
    void 지운_매출은_삭제를_막지_않는다() {
        Long id = book("HDV" + SFX);
        assertThat(post("/stock/inbound", Map.of("processedDate", DATE,
                "supplierClientId", supplier, "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", id, "unitCost", 1000, "qty", 50))))
                .path("success").asBoolean()).isTrue();
        assertThat(post("/sales/entries", Map.of("salesDate", DATE,
                "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", id, "shipmentType", "NORMAL_SHIP",
                        "qty", 2, "unitPrice", 10_000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();

        // ① 살아 있는 매출이 있으면 **막는다**.
        JsonNode blocked = del("/masters/products/" + id + "/hard");
        assertThat(blocked.path("success").asBoolean()).as("살아 있는 매출: %s", blocked).isFalse();
        assertThat(blocked.path("error").path("message").asText()).contains("매출");

        // ② 그 매출을 지우면(논리삭제) 더 이상 막지 않아야 한다.
        Long saleId = null;
        for (JsonNode n : get("/sales?fromDate=" + DATE + "&toDate=" + DATE
                + "&partnerId=" + partner + "&size=100").path("data").path("content")) {
            if (n.path("productId").asLong() == id) {
                saleId = n.path("id").asLong();
            }
        }
        assertThat(saleId).as("방금 넣은 매출을 못 찾았다").isNotNull();
        assertThat(del("/sales/" + saleId, Map.of("reason", "완전삭제 검증"))
                .path("success").asBoolean()).isTrue();

        assertThat(data(get("/masters/products/" + id + "/references")).path("매출").isNumber())
                .as("‼️지운 매출이 참조로 세어지면 도서를 영영 못 지운다").isFalse();

        // 재고 잔량이 남아 있으면 그것 때문에 막힌다 — 이 테스트의 관심은 '매출'이다.
        JsonNode refs = data(get("/masters/products/" + id + "/references"));
        assertThat(refs.path("재고이벤트").isNumber())
                .as("입고를 되돌리지 않았으니 재고이벤트는 살아 있다").isTrue();
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
