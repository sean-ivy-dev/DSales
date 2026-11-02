package com.daesung.sales.audit;

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
 * 상태변경 이력 회귀 고정.
 * 근거: 개발문서 5.0(상태변경이력 테이블) · 리스크 12.0(보안심사 지적 가능성)
 * · 갭분석 BE-3A "UPDATE 제자리 덮어쓰기로 이전 상태 소실".
 *
 * <p>기록은 서비스에서 명시 호출이라 빠뜨릴 수 있다 — 전이 지점마다 여기서 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("상태변경 이력 통합테스트")
class StatusHistoryIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-" + (System.nanoTime() % 1_000_000L);

    private Long supplier;
    private Long partner;
    private Long warehouse;

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients", Map.of("code", "SH-SUP" + SFX, "name", "이력인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "SH-CUST" + SFX, "name", "이력거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses", Map.of("code", "SH-WH" + SFX, "name", "이력창고", "type", "MAIN"));
    }

    private JsonNode history(String query) {
        return data(get("/audit/status-history?size=200&" + query)).path("content");
    }

    @Test
    @DisplayName("월마감 — 잠금·해제·재잠금 3회가 모두 남는다(기존엔 마지막만 남았다)")
    void 월마감_이력_누적() {
        int year = 2027;
        int month = 3;
        post("/closing/periods/lock", Map.of("year", year, "month", month, "memo", "3월 마감"));
        post("/closing/periods/unlock", Map.of("year", year, "month", month, "memo", "누락분 반영"));
        post("/closing/periods/lock", Map.of("year", year, "month", month, "memo", "재마감"));

        JsonNode rows = history("entityType=PERIOD_LOCK");
        List<String> reasons = rows.findValuesAsText("reason");
        assertThat(reasons).as("세 번의 변경이 모두 기록돼야 함")
                .contains("3월 마감", "누락분 반영", "재마감");

        // 최신순이므로 첫 행이 재잠금
        JsonNode latest = rows.get(0);
        assertThat(latest.path("field").asText()).isEqualTo("locked");
        assertThat(latest.path("fromStatus").asText()).as("해제 상태에서 잠금으로").isEqualTo("false");
        assertThat(latest.path("toStatus").asText()).isEqualTo("true");
        assertThat(latest.path("changedBy").asText()).isEqualTo("admin");
        assertThat(latest.path("changedAt").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("매출 취소 — 취소 시점·변경자가 남는다")
    void 매출취소_이력() {
        Long p = createId("/masters/products",
                Map.of("code", "SH-BK" + SFX, "name", "이력도서", "contentType", "SELF", "price", 10000,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of(
                "processedDate", "2026-06-01", "supplierClientId", supplier, "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", p, "unitCost", 3000, "qty", 100))));
        post("/sales/entries", Map.of(
                "salesDate", "2027-05-10", "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", p, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 75, "qty", 10))));

        long saleId = data(get("/sales?startDate=2027-05-01&endDate=2027-05-31&partnerId=" + partner))
                .path("content").get(0).path("id").asLong();
        post("/sales/" + saleId + "/cancel", Map.of());

        JsonNode rows = history("entityType=SALE&entityId=" + saleId);
        assertThat(rows).as("취소 이력 1건").hasSize(1);
        JsonNode h = rows.get(0);
        assertThat(h.path("field").asText()).isEqualTo("canceled");
        assertThat(h.path("fromStatus").asText()).as("바꾸기 전 값이 남아야 함").isEqualTo("false");
        assertThat(h.path("toStatus").asText()).isEqualTo("true");
        assertThat(h.path("changedBy").asText()).isEqualTo("admin");
    }

    @Test
    @DisplayName("변경자·기간으로 조회 — \"이 사람이 무엇을 바꿨나\"에 답할 수 있다")
    void 변경자_조회() {
        post("/closing/periods/lock", Map.of("year", 2027, "month", 8, "memo", "8월 마감"));

        JsonNode mine = history("changedBy=admin");
        assertThat(mine).as("admin이 바꾼 이력이 조회돼야 함").isNotEmpty();

        JsonNode none = history("changedBy=존재하지않는사용자");
        assertThat(none).as("없는 사용자는 빈 결과").isEmpty();
    }
}
