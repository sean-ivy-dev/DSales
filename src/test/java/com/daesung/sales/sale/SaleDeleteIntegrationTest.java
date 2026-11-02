package com.daesung.sales.sale;

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
 * 마감 前 거래기록 삭제. 근거: 발주처 회신 2026-08-14 [4] —
 * "마감 확정 前 → 등록담당자가 우클릭 삭제 가능(레거시 수준).
 *  마감 확정 後 → 물리삭제 없이 취소 처리".
 *
 * <p>★<b>취소와 삭제는 다른 축이다.</b> 한 플래그로 합치면 마감 후 정당한 반품 취소와
 * 오입력이 섞여 세무 소명 때 가릴 수 없다. 이 테스트가 두 축이 갈려 있는지 고정한다.
 *
 * <p>★<b>물리삭제하지 않는다.</b> 레거시는 진짜 지웠지만(`UC_TabPages.vb:543`),
 * 그건 레거시가 재고 잔고를 저장 안 해 행을 지우면 재고가 저절로 맞았기 때문이다.
 * 우리는 {@code inventory_txn}이 유일 진실이라 같은 행동이 정반대로 작동한다 —
 * 그래서 <b>재고가 제대로 되돌아가는지</b>를 여기서 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("마감 前 삭제(2026-08-14 [4])")
class SaleDeleteIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DL" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2093;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private Long partner;
    private Long wh;
    private Long book;
    private Long sup;

    @BeforeAll
    void seed() {
        token();
        sup = createId("/masters/clients", Map.of("code", "DLS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "DLP" + SFX, "name", "삭제거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "DLW" + SFX, "name", "삭제창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "DLB" + SFX);
        b.put("name", "삭제교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "D" + YEAR + "A");
        b.put("catName", "삭제분류");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 1000))));
    }

    /** 매출 1건 등록 후 그 id. */
    private long sell(int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 70, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        String salesNo = data(r).path("items").get(0).path("salesNo").asText();
        for (JsonNode row : data(get("/sales" + RANGE + "&partnerId=" + partner + "&size=200"))
                .path("content")) {
            if (salesNo.equals(row.path("salesNo").asText())) {
                return row.path("id").asLong();
            }
        }
        throw new AssertionError("등록한 매출을 못 찾음: " + salesNo);
    }

    private int stock() {
        for (JsonNode row : data(get("/stock/ledger" + RANGE + "&keyword=DLB" + SFX + "&size=50"))
                .path("content")) {
            if (("DLB" + SFX).equals(row.path("productCode").asText())) {
                return row.path("closing").asInt();
            }
        }
        return 0;
    }

    private boolean listed(long id) {
        for (JsonNode row : data(get("/sales" + RANGE + "&partnerId=" + partner + "&size=200"))
                .path("content")) {
            if (row.path("id").asLong() == id) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("★삭제하면 목록에서 사라지고 재고가 되돌아온다")
    void 삭제_재고복원() {
        int before = stock();
        long id = sell(40);
        assertThat(stock()).as("출고분만큼 줄었다").isEqualTo(before - 40);

        JsonNode r = del("/sales/" + id, Map.of("reason", "거래처 잘못 골라 재등록"));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(listed(id)).as("목록에서 사라진다").isFalse();
        assertThat(stock()).as("★재고가 등록 전으로 되돌아온다").isEqualTo(before);
    }

    @Test
    @DisplayName("★취소와 다른 축이다 — 삭제는 역분개 기록을 남기지 않는다")
    void 취소와_다른축() {
        long canceled = sell(10);
        post("/sales/" + canceled + "/cancel", null);
        long deleted = sell(10);
        del("/sales/" + deleted, Map.of("reason", "오입력"));

        // 취소분은 '취소' 이력, 삭제분은 '삭제' 이력 — 같은 칸에 섞이면 안 된다.
        assertThat(field(canceled, "canceled")).as("취소 이력").isNotNull();
        assertThat(field(canceled, "deleted")).as("취소는 삭제가 아니다").isNull();
        assertThat(field(deleted, "deleted")).as("삭제 이력").isNotNull();
        assertThat(field(deleted, "canceled")).as("삭제는 취소가 아니다").isNull();
    }

    private JsonNode field(long saleId, String field) {
        JsonNode d = data(get("/audit/status-history?entityType=SALE&entityId=" + saleId));
        for (JsonNode h : (d.has("content") ? d.path("content") : d)) {
            if (field.equals(h.path("field").asText())) {
                return h;
            }
        }
        return null;
    }

    @Test
    @DisplayName("★지운 내용이 이력에 남는다 — 삭제 후엔 여기에만 남는다")
    void 이력에_남는다() {
        long id = sell(25);
        del("/sales/" + id, Map.of("reason", "수량 오기"));

        JsonNode h = field(id, "deleted");

        assertThat(h).isNotNull();
        assertThat(h.path("reason").asText())
                .contains("삭제거래처", "DLB" + SFX, "25부", "수량 오기");
        assertThat(h.path("changedBy").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("★사유 없이도 지울 수 있다(9/27 A-3) — 대신 이력에 '(사유 미입력)'으로 남는다")
    void 사유는_선택() {
        long id = sell(5);

        // 9/27 회의로 화면에서 사유 칸이 빠졌다. 빈 값도, 본문 누락도 통과해야 한다.
        JsonNode r = del("/sales/" + id, Map.of("reason", " "));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(listed(id)).as("지워졌다").isFalse();

        // ‼️사유가 없다고 이력까지 비면 안 된다 — "안 적은 것"과 "기록이 안 된 것"이 구분돼야 한다.
        JsonNode hist = data(get("/audit/status-history?entityType=SALE&entityId=" + id));
        JsonNode rows = hist.has("content") ? hist.path("content") : hist;
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).path("reason").asText()).contains("(사유 미입력)");
    }

    @Test
    @DisplayName("★마감된 달은 삭제 대신 취소 — 발주처 확정 그대로")
    void 마감후에는_못지운다() {
        long id = sell(3);
        post("/closing/periods/lock", Map.of("year", YEAR, "month", 2));
        try {
            JsonNode r = del("/sales/" + id, Map.of("reason", "마감 후 시도"));

            assertThat(r.path("success").asBoolean()).isFalse();
            assertThat(r.path("error").path("code").asText()).isEqualTo("PERIOD_LOCKED");
        } finally {
            post("/closing/periods/unlock", Map.of("year", YEAR, "month", 2, "memo", "테스트 정리"));
        }
        // 마감을 풀면 다시 지울 수 있다(같은 건이 계속 남아 다음 테스트를 오염시키지 않게 정리).
        assertThat(del("/sales/" + id, Map.of("reason", "정리")).path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★재고 전표(입고)도 같은 규칙으로 지운다")
    void 입고전표_삭제() {
        int before = stock();
        JsonNode in = post("/stock/inbound", Map.of("processedDate", YEAR + "-05-01",
                "supplierClientId", sup, "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 77))));
        String refNo = data(in).path("inboundNo").asText();
        assertThat(stock()).isEqualTo(before + 77);

        JsonNode r = del("/stock/inbound/" + refNo, Map.of("reason", "수량 오기"));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(stock()).as("★입고 전으로 되돌아온다").isEqualTo(before);
    }

    @Test
    @DisplayName("이미 취소된 전표는 삭제 불가 — 되돌린 기록이 이미 장부에 섰다")
    void 취소된전표는_삭제불가() {
        JsonNode in = post("/stock/inbound", Map.of("processedDate", YEAR + "-06-01",
                "supplierClientId", sup, "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 9))));
        String refNo = data(in).path("inboundNo").asText();
        post("/stock/inbound/" + refNo + "/cancel?reason=취소", null);

        JsonNode r = del("/stock/inbound/" + refNo, Map.of("reason", "지워보기"));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("이미 취소된");
    }
}
