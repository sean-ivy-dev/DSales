package com.daesung.sales.consignment;

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
 * 위탁정산 <b>임시저장</b>(13p 1단계) 회귀 고정.
 * 근거: 정본 13p "매출등록은 [임시저장] → [매출확정등록] 2단계" +
 * 프론트가 이미 {@code /sales/settlement/draft}로 호출 중.
 *
 * <p>★여기서 지키는 것은 <b>"임시저장은 아무것도 확정하지 않는다"</b> 하나다.
 * 정본 원문이 "임시저장 상태의 데이터는 <b>매출 미반영</b>임을 명확히 구분해야 한다"이고,
 * 이게 깨지면 담당자가 저장만 했는데 미결이 줄어 매출이 두 번 서는 사고가 난다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("위탁정산 임시저장(13p 1단계)")
class SettlementDraftIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DR" + (System.nanoTime() % 1_000_000L);
    /** 다른 테스트의 전역 연간 집계와 겹치지 않는 해. */
    private static final int YEAR = 2054;

    private Long partner;
    private Long product;
    private Long outId;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "DRS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "DRP" + SFX, "name", "위탁처", "type", "NORMAL"));
        Long mainWh = createId("/masters/warehouses", Map.of("code", "DRW" + SFX, "name", "물류창고", "type", "MAIN"));
        Long consignWh = createId("/masters/warehouses", Map.of("code", "DRC" + SFX, "name", "위탁창고",
                "type", "CONSIGN", "ownerClientId", partner));
        product = createId("/masters/products", Map.of("code", "DRB" + SFX, "name", "위탁도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "salesDivision", "교재"));

        post("/stock/inbound", Map.of("processedDate", YEAR + "-03-01", "supplierClientId", sup,
                "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", product, "unitCost", 3000, "qty", 500))));
        post("/consignment/out", Map.of("processedDate", YEAR + "-03-05",
                "partnerId", partner, "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", product, "qty", 100))));
        outId = pending().path("consignmentOutId").asLong();
    }

    private JsonNode pending() {
        return data(get("/consignment/pending?partnerId=" + partner)).path("items").get(0);
    }

    @Test
    @DisplayName("★임시저장해도 미결은 그대로다 — 매출 미반영")
    void 저장해도_확정되지_않는다() {
        int before = pending().path("remainingQty").asInt();

        JsonNode d = data(post("/sales/settlement/draft", Map.of(
                "salesDate", YEAR + "-03-10",
                "input", List.of(Map.of("pendingId", outId, "settleQty", 30)))));

        assertThat(d.path("draftId").asText()).startsWith("DRAFT-");
        assertThat(d.path("totalQty").asInt()).isEqualTo(30);
        // 정가·공급률을 안 보냈으니 도서 마스터에서 채워져야 한다 — 10000 × 70% × 30
        assertThat(d.path("lines").get(0).path("unitPrice").asInt()).isEqualTo(10000);
        assertThat(d.path("lines").get(0).path("supplyRate").asInt()).isEqualTo(70);
        assertThat(d.path("totalAmount").asLong()).isEqualTo(210_000);

        // ‼️핵심: 저장만 했는데 미결이 줄면 안 된다
        assertThat(pending().path("remainingQty").asInt()).as("미결 잔여 불변").isEqualTo(before);
        assertThat(pending().path("settledQty").asInt()).as("정산 누적 불변").isZero();

        del("/sales/settlement/draft/" + d.path("draftId").asText());
    }

    @Test
    @DisplayName("★초과정산은 막지 않고 경고한다 — 발주처 확정(2026-08-31 화면9)")
    void 초과정산은_경고() {
        // 원문: "자동 차단하던 기존 로직은 제거. 초과 시 경고 알림(alert)만 표시하고,
        //        이후 처리는 담당자가 수기로 입력·등록할 수 있도록".
        // ⚠️V56에서 DB 제약·엔티티는 걷어냈는데 정산초안 경로에 차단이 남아 있었다.
        JsonNode r = post("/sales/settlement/draft", Map.of(
                "salesDate", YEAR + "-03-10",
                "input", List.of(Map.of("pendingId", outId, "settleQty", 999))));

        assertThat(r.path("success").asBoolean()).as("저장은 된다: %s", r).isTrue();

        JsonNode w = data(r).path("warnings");
        assertThat(w).as("★초과분을 숨기지 않는다 — 조용히 통과시키면 담당자가 모른다").hasSize(1);
        assertThat(w.get(0).path("code").asText()).isEqualTo("OVER_SETTLEMENT");
        assertThat(w.get(0).path("requestedQty").asInt()).isEqualTo(999);
        assertThat(w.get(0).path("exceededQty").asInt()).isPositive();
        assertThat(w.get(0).path("message").asText()).contains("미결 잔여를 초과");

        del("/sales/settlement/draft/" + data(r).path("draftId").asText());
    }

    @Test
    @DisplayName("같은 미결이 여러 줄이면 합쳐서 본다 — 줄마다 보면 각각은 통과하고 합만 넘는다")
    void 같은미결_여러줄_합산검사() {
        JsonNode r = post("/sales/settlement/draft", Map.of(
                "salesDate", YEAR + "-03-10",
                "input", List.of(
                        Map.of("pendingId", outId, "settleQty", 60),
                        Map.of("pendingId", outId, "settleQty", 60))));   // 각 60은 통과, 합 120은 초과

        assertThat(r.path("success").asBoolean()).isTrue();

        JsonNode w = data(r).path("warnings");
        assertThat(w).as("★합쳐서 **한 번만** 경고한다 — 줄마다 띄우면 같은 말이 반복된다").hasSize(1);
        assertThat(w.get(0).path("requestedQty").asInt()).as("60+60 합산").isEqualTo(120);

        del("/sales/settlement/draft/" + data(r).path("draftId").asText());
    }

    @Test
    @DisplayName("목록에 뜨고, 지우면 사라진다(논리삭제)")
    void 목록_삭제() {
        String id = data(post("/sales/settlement/draft", Map.of(
                "salesDate", YEAR + "-03-11",
                "input", List.of(Map.of("pendingId", outId, "settleQty", 10)))))
                .path("draftId").asText();

        assertThat(draftIds()).contains(id);
        del("/sales/settlement/draft/" + id);
        assertThat(draftIds()).doesNotContain(id);
    }

    @Test
    @DisplayName("★확정에 쓴 초안은 함께 정리된다 — 남으면 두 번 확정하려 든다")
    void 확정하면_초안이_사라진다() {
        String id = data(post("/sales/settlement/draft", Map.of(
                "salesDate", YEAR + "-03-12",
                "input", List.of(Map.of("pendingId", outId, "settleQty", 20)))))
                .path("draftId").asText();
        assertThat(draftIds()).contains(id);

        JsonNode s = post("/consignment/settle", Map.of(
                "salesDate", YEAR + "-03-12",
                "fromDraftId", id,
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", 20,
                        "unitPrice", 10000, "supplyRate", 70))));
        assertThat(s.path("success").asBoolean()).as("확정: %s", s).isTrue();

        assertThat(draftIds()).as("확정에 쓴 초안은 정리된다").doesNotContain(id);
        assertThat(pending().path("settledQty").asInt()).as("이제야 미결이 줄어든다").isEqualTo(20);
    }

    @Test
    @DisplayName("없는 초안번호로 확정해도 매출은 선다 — 초안 때문에 확정을 되돌리지 않는다")
    void 없는초안번호는_확정을_막지_않는다() {
        JsonNode s = post("/consignment/settle", Map.of(
                "salesDate", YEAR + "-03-13",
                "fromDraftId", "DRAFT-없는번호",
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", 10,
                        "unitPrice", 10000, "supplyRate", 70))));

        assertThat(s.path("success").asBoolean()).as("확정: %s", s).isTrue();
    }

    private List<String> draftIds() {
        List<String> ids = new java.util.ArrayList<>();
        data(get("/sales/settlement/drafts")).forEach(d -> ids.add(d.path("draftId").asText()));
        return ids;
    }
}
