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
 * 발송구분 자동판정 — 담당자정보가 있으면 택배, 없으면 화물(기본값). 근거: 10/27 검수 피드백
 * §8-6 "담당자명·연락처가 있을 때만 택배로 한다. 그 외는 화물".
 *
 * <p>각 테스트가 독립된 학교코드로 발송 건을 분리한다(ensureFor 키가 거래일자·거래처·학교·분류라
 * 학교코드만 다르면 별도 Shipment가 된다) — 한 클래스 안에서도 테스트끼리 섞이지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("발송구분 자동판정(10/27 피드백 §8-6)")
class DeliveryTypeAutoDecideIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DA" + (System.nanoTime() % 1_000_000L);
    private static final String RANGE = "?fromDate=2034-06-01&toDate=2034-06-30";

    private Long partner;
    private Long wh;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "DAS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "DAP" + SFX, "name", "자동판정거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "DAW" + SFX, "name", "물류", "type", "MAIN"));
        book = createId("/masters/products", Map.of("code", "DAB" + SFX, "name", "자동판정도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2034-06-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));
    }

    private Long sellWithSchool(String schoolCode, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("salesDate", "2034-06-10");
        body.put("partnerId", partner);
        body.put("warehouseId", wh);
        body.putAll(extra);
        body.put("items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", 1,
                "schoolCode", schoolCode, "schoolName", schoolCode + "학교")));
        JsonNode r = post("/sales/entries", body);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return findShipment(schoolCode);
    }

    private Long findShipment(String schoolCode) {
        for (JsonNode o : data(get("/logistics/work-orders" + RANGE + "&partnerId=" + partner))
                .path("content")) {
            if (schoolCode.equals(o.path("schoolCode").asText())) {
                return o.path("id").asLong();
            }
        }
        throw new AssertionError("학교코드 " + schoolCode + "의 발송 건을 못 찾음");
    }

    private JsonNode orderById(long id) {
        for (JsonNode o : data(get("/logistics/work-orders" + RANGE + "&partnerId=" + partner))
                .path("content")) {
            if (o.path("id").asLong() == id) {
                return o;
            }
        }
        throw new AssertionError("발송 건 id=" + id + "를 못 찾음");
    }

    @Test
    @DisplayName("★담당자명만 있어도 택배로 자동판정된다 — 명시적 발송구분 없이")
    void 담당자명만_있으면_택배() {
        Long id = sellWithSchool("DA001" + SFX, Map.of("receiverName", "홍길동"));
        JsonNode o = orderById(id);
        assertThat(o.path("deliveryType").asText()).isEqualTo("COURIER");
        assertThat(o.path("receiverName").asText()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("★연락처만 있어도 택배로 자동판정된다")
    void 연락처만_있으면_택배() {
        Long id = sellWithSchool("DA002" + SFX, Map.of("receiverPhone", "010-1234-5678"));
        JsonNode o = orderById(id);
        assertThat(o.path("deliveryType").asText()).isEqualTo("COURIER");
    }

    @Test
    @DisplayName("담당자정보도 명시적 지정도 없으면 화물(기본값)")
    void 아무것도_없으면_화물() {
        Long id = sellWithSchool("DA003" + SFX, Map.of());
        JsonNode o = orderById(id);
        assertThat(o.path("deliveryType").asText()).isEqualTo("FREIGHT");
    }

    @Test
    @DisplayName("담당자정보가 있어도 명시적으로 화물을 지정했으면 화물이 이긴다")
    void 명시적_지정이_담당자정보보다_우선() {
        Long id = sellWithSchool("DA004" + SFX,
                Map.of("receiverName", "박화물", "deliveryType", "FREIGHT"));
        JsonNode o = orderById(id);
        assertThat(o.path("deliveryType").asText()).as("명시적 지정이 자동판정보다 우선해야").isEqualTo("FREIGHT");
        assertThat(o.path("receiverName").asText()).isEqualTo("박화물");
    }
}
