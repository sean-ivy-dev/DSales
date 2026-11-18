package com.daesung.sales.closing;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 입력 가능 최소 거래일 — 이관 기간으로의 <b>신규 입력만</b> 막는다(「매출 마무리」 §2-3 ㄱ).
 *
 * <p>★이 테스트가 못박는 것은 <b>막는 범위</b>다. 하한을 걸면 그 전 날짜로 새로 등록하는 길은
 * 닫히지만, <b>이미 있는 자료의 취소·삭제는 그대로 된다</b> — 거기까지 막으면 이관분(1~11월)에
 * 잘못 들어간 자료를 바로잡을 길이 없어진다.
 *
 * <p>‼️설정은 싱글톤 행이라 <b>다른 테스트와 공유된다.</b> 끝에 반드시 되돌린다
 * (안 되돌리면 뒤에 도는 테스트가 전부 ENTRY_DATE_TOO_OLD 로 깨진다).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("입력 최소일 — 이관 기간 신규 입력 차단(2026-11-18)")
class EntryDateFloorIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "EF" + (System.nanoTime() % 1_000_000L);

    /** ★다른 테스트가 쓰지 않는 연도. 하한을 이 연도 안에 두면 다른 테스트 날짜엔 안 닿는다. */
    private static final String FLOOR = "2063-06-01";
    private static final String BEFORE = "2063-05-31";
    private static final String ON = "2063-06-01";

    private Long partner;
    private Long warehouse;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "EFS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "EFW" + SFX, "name", "창고", "type", "MAIN"));
        partner = createId("/masters/clients",
                Map.of("code", "EFP" + SFX, "name", "입력최소일검증거래처", "type", "NORMAL"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "EFB" + SFX);
        b.put("name", "입력최소일 검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2063A01");
        b.put("catName", "입력최소일검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2063-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 500, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();
    }

    @AfterAll
    void 되돌린다() {
        setFloor(null);
    }

    @Test
    @DisplayName("기본값은 제한 없음 — 올려도 아무 동작이 바뀌지 않는다")
    void 기본값_제한없음() {
        setFloor(null);
        JsonNode res = get("/closing/entry-date-floor");
        assertThat(res.path("success").asBoolean()).isTrue();
        assertThat(res.path("data").path("enabled").asBoolean()).isFalse();
        // ★응답 직렬화가 null 필드를 빼기도 해 둘 다 허용한다(MissingNode | NullNode).
        JsonNode floor = res.path("data").path("floorDate");
        assertThat(floor.isNull() || floor.isMissingNode()).isTrue();

        assertThat(sell(BEFORE, 1).path("success").asBoolean())
                .as("제한이 없으면 과거 날짜도 그대로 등록된다")
                .isTrue();
    }

    @Test
    @DisplayName("★하한을 걸면 그 전 날짜 매출 등록이 거부된다 — 문구에 「관리자에게 요청」")
    void 하한_이전_매출등록_거부() {
        setFloor(FLOOR);

        JsonNode res = sell(BEFORE, 2);
        assertThat(res.path("success").asBoolean()).isFalse();
        assertThat(res.path("error").path("code").asText()).isEqualTo("ENTRY_DATE_TOO_OLD");
        assertThat(res.path("error").path("message").asText())
                .as("담당자가 무엇을 해야 하는지가 문구에 있어야 한다")
                .contains("관리자에게 요청");

        assertThat(sell(ON, 2).path("success").asBoolean())
                .as("하한 당일은 통과한다(이전만 막는다)")
                .isTrue();
    }

    @Test
    @DisplayName("수금 등록도 같이 막힌다 — 「매출 · 수금 · 주문」")
    void 하한_이전_수금등록_거부() {
        setFloor(FLOOR);
        JsonNode res = post("/closing/collections", Map.of(
                "partnerId", partner, "collDate", BEFORE,
                "collType", "CASH", "collAmt", 10000));
        assertThat(res.path("success").asBoolean()).isFalse();
        assertThat(res.path("error").path("code").asText()).isEqualTo("ENTRY_DATE_TOO_OLD");
    }

    @Test
    @DisplayName("‼️이미 있는 자료의 취소·삭제는 막지 않는다 — 이관분을 고칠 길이 막히면 안 된다")
    void 기존자료_취소삭제는_허용() {
        setFloor(null);
        Long old = saleIdOf(sell(BEFORE, 3), 3);
        setFloor(FLOOR);

        assertThat(del("/sales/" + old, Map.of("reason", "하한 뒤에도 지워진다"))
                .path("success").asBoolean())
                .as("‼️여기서 막히면 이관분에 잘못 들어간 자료를 바로잡을 수 없다")
                .isTrue();
    }

    @Test
    @DisplayName("하한을 풀면 다시 들어간다 — 관리자가 되돌릴 수 있다")
    void 풀면_다시_등록된다() {
        setFloor(FLOOR);
        assertThat(sell(BEFORE, 4).path("success").asBoolean()).isFalse();

        setFloor(null);
        assertThat(sell(BEFORE, 4).path("success").asBoolean()).isTrue();
    }

    /** 설정 변경. null이면 해제. */
    private void setFloor(String date) {
        Map<String, Object> body = new HashMap<>();
        body.put("floorDate", date);
        JsonNode res = put("/closing/entry-date-floor", body);
        assertThat(res.path("success").asBoolean()).as("설정 변경: %s", res).isTrue();
        assertThat(res.path("data").path("enabled").asBoolean()).isEqualTo(date != null);
    }

    private JsonNode sell(String date, int qty) {
        return post("/sales/entries", Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10000, "supplyRate", 70))));
    }

    private Long saleIdOf(JsonNode res, int qty) {
        assertThat(res.path("success").asBoolean()).as("등록: %s", res).isTrue();
        for (JsonNode n : get("/sales?fromDate=2063-01-01&toDate=2063-12-31"
                + "&partnerId=" + partner + "&size=100").path("data").path("content")) {
            if (n.path("qty").asInt() == qty && !n.path("canceled").asBoolean()) {
                return n.path("id").asLong();
            }
        }
        throw new AssertionError("등록한 매출을 못 찾았다");
    }
}
