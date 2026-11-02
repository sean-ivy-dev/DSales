package com.daesung.sales.consignment;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 위탁 매출 확정의 <b>1회 처리 보장</b>(프론트 실측 2026-10-22 ③-①).
 *
 * <p>‼️확정 중 504·연결끊김이 나면 사용자는 다시 누른다. 그런데 <b>초과정산 차단은 발주처 확정으로
 * 걷어냈기 때문에</b>(2026-08-31) 서버가 막아 주지 않아 매출이 두 번 잡힌다.
 * 프론트가 "반영됐을 수도 있음" 안내로 덮고 있었지만 그건 덮은 것이지 막은 것이 아니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("위탁 확정 1회 처리(Idempotency-Key)")
class SettleIdempotencyIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-IK" + (System.nanoTime() % 1_000_000L);
    private static final String YEAR = "2095";

    private Long partner;
    private Long book;
    private Long outId;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "IKS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "IKP" + SFX, "name", "위탁거래처", "type", "CONSIGN"));
        Long main = createId("/masters/warehouses",
                Map.of("code", "IKM" + SFX, "name", "물류창고", "type", "MAIN"));
        Long consign = createId("/masters/warehouses",
                Map.of("code", "IKC" + SFX, "name", "위탁창고", "type", "CONSIGN",
                        "ownerClientId", partner));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "IKB" + SFX);
        b.put("name", "멱등검증 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        // ★위탁출고는 공급률이 없으면 등록이 막힌다 — 나갈 때 조건이 정해져야 정산할 수 있다.
        b.put("supplyRate", 75);
        b.put("catCode", "I2095A01");
        b.put("catName", "멱등검증");
        b.put("taxFree", false);
        b.put("useYn", true);
        b.put("set", false);
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", main,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 500))))
                .path("success").asBoolean()).isTrue();

        JsonNode out = post("/consignment/out", Map.of(
                "processedDate", YEAR + "-02-01", "partnerId", partner,
                "fromWarehouseId", main, "toWarehouseId", consign,
                "items", List.of(Map.of("productId", book, "qty", 100))));
        assertThat(out.path("success").asBoolean()).as("위탁출고: %s", out).isTrue();
        outId = data(out).path("items").get(0).path("consignmentOutId").asLong();
    }

    /** Idempotency-Key 를 붙여 확정 호출. */
    private ResponseEntity<String> settle(String key, int qty) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token());
        if (key != null) {
            h.add("Idempotency-Key", key);
        }
        String body = om.writeValueAsString(Map.of(
                "salesDate", YEAR + "-03-01",
                "settlements", List.of(Map.of(
                        "consignmentOutId", outId, "settleQty", qty,
                        "unitPrice", 10000, "supplyRate", 70))));
        return rest.exchange("/api/v1/consignment/settle", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    private long remaining() {
        for (JsonNode r : data(get("/consignment/pending?partnerId=" + partner)).path("items")) {
            if (r.path("consignmentOutId").asLong() == outId) {
                return r.path("remainingQty").asLong();
            }
        }
        return -1;
    }

    @Test
    @DisplayName("★같은 키로 두 번 불러도 한 번만 처리되고, 두 번째도 같은 응답이 나온다")
    void 같은키는_한번만() throws Exception {
        String key = "settle-" + SFX;

        ResponseEntity<String> first = settle(key, 30);
        assertThat(first.getStatusCode().is2xxSuccessful()).as("첫 호출: %s", first.getBody()).isTrue();
        String firstSalesNo = om.readTree(first.getBody())
                .path("data").path("items").get(0).path("salesNo").asText();
        long afterFirst = remaining();
        assertThat(afterFirst).as("100 − 30").isEqualTo(70);

        // ‼️클라이언트가 응답을 못 받고 다시 누른 상황. 같은 키다.
        ResponseEntity<String> retry = settle(key, 30);
        assertThat(retry.getStatusCode().is2xxSuccessful()).as("재시도도 성공으로 받는다").isTrue();

        // ★첫 응답이 **그대로** 나와야 한다 — 타임아웃 난 쪽은 전표번호를 아직 모른다.
        assertThat(om.readTree(retry.getBody()).path("data").path("items").get(0).path("salesNo").asText())
                .as("같은 매출번호").isEqualTo(firstSalesNo);

        // ★그리고 미결이 더 깎이면 안 된다 — 이게 진짜 보장이다.
        assertThat(remaining()).as("두 번 깎이지 않았다").isEqualTo(70);
    }

    @Test
    @DisplayName("키가 다르면 각각 처리된다 — 과하게 막지 않았는지 함께 본다")
    void 다른키는_각각처리() throws Exception {
        long before = remaining();

        assertThat(settle("settle-a" + SFX, 10).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(settle("settle-b" + SFX, 10).getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(remaining()).as("둘 다 반영된다").isEqualTo(before - 20);
    }

    @Test
    @DisplayName("키가 없으면 예전처럼 매번 실행된다 — 기존 호출을 깨지 않는다")
    void 키없으면_그대로() throws Exception {
        long before = remaining();
        assertThat(settle(null, 5).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(settle(null, 5).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(remaining()).isEqualTo(before - 10);
    }
}
