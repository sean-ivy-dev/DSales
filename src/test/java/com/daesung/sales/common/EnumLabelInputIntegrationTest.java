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
 * 요청 본문의 구분값은 <b>한글로도 보낼 수 있고</b>, 틀리면 <b>왜 틀렸는지</b> 알려준다.
 *
 * <p>근거: 1차 테스트 피드백(2026-09-17) p20 — 세부구분 '추가'가
 * "요청 본문을 해석할 수 없습니다"로만 실패해 원인을 아무도 못 찾았다.
 *
 * <p>★<b>두 가지가 겹친 문제였다.</b>
 * <ol>
 *   <li>화면은 목록에서 고른 <b>한글</b>을 그대로 되보내는데 서버가 enum 이름만 받았다.</li>
 *   <li>그 400 이 무엇 때문인지 <b>한 글자도 알려주지 않았다</b> —
 *       {@code @JsonCreator} 가 친절히 던진 메시지가 일반 문구로 덮였다.</li>
 * </ol>
 * 둘 중 하나만 고치면 다음에 또 같은 자리에서 막힌다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("구분값 한글 입력 · 400 사유 노출")
class EnumLabelInputIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-EL" + (System.nanoTime() % 1_000_000L);

    @BeforeAll
    void auth() {
        token();
    }

    @Test
    @DisplayName("★세부구분을 한글 대분류로 등록할 수 있다 — 화면이 보여준 그 값 그대로")
    void 세부구분_한글_대분류() {
        JsonNode r = post("/masters/sales-divisions", Map.of(
                "code", "EL1" + SFX, "name", "한글대분류검증", "majorCategory", "교재"));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("majorCategory").asText()).isEqualTo("TEXTBOOK");
    }

    @Test
    @DisplayName("★모르는 값이면 400이고, 무엇이 가능한지 알려준다")
    void 모르는_대분류는_사유까지() {
        JsonNode r = post("/masters/sales-divisions", Map.of(
                "code", "EL2" + SFX, "name", "오타검증", "majorCategory", "교재류"));

        assertThat(r.path("success").asBoolean()).isFalse();
        String msg = r.path("error").path("message").asText();
        // ‼️"요청 본문을 해석할 수 없습니다"로만 끝나면 안 된다 — 그게 p20 의 증상이었다.
        assertThat(msg).as("사유가 그대로 올라와야: %s", msg)
                .contains("교재류")
                .contains("모의고사");
    }

    @Test
    @DisplayName("★매출등록 출고유형도 한글로 받는다 — 응답이 한글을 주니 요청도 받아야 한다")
    void 출고유형_한글() {
        Long sup = createId("/masters/clients",
                Map.of("code", "ELS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        Long partner = createId("/masters/clients",
                Map.of("code", "ELP" + SFX, "name", "한글거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "ELW" + SFX, "name", "한글창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "ELB" + SFX);
        b.put("name", "한글교재");
        b.put("contentType", "자체교재");      // ← ContentType 도 한글(라벨 그대로)
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "E2090A");
        b.put("catName", "한글분류");
        b.put("salesDivision", "교재");
        Long book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", "2090-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 100))));

        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2090-02-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "정상출고",
                        "unitPrice", 10000, "supplyRate", 70, "qty", 5))));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("items").get(0).path("shipmentType").asText())
                .isEqualTo("NORMAL_SHIP");
    }

    @Test
    @DisplayName("어느 필드가 틀렸는지도 알려준다 — 항목이 여럿이면 찾을 수 없다")
    void 어느_필드인지() {
        JsonNode r = post("/masters/clients", Map.of(
                "code", "EL3" + SFX, "name", "필드검증", "type", "없는거래처구분"));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText())
                .as("값이나 필드명 중 하나는 나와야 한다")
                .containsAnyOf("없는거래처구분", "type");
    }
}
