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
 * 통합 매출 조회 — 학교 다중선택 · 학교명 보완 · 쿠폰 포함 카운트.
 *
 * <p>근거: 9/27 회의 항목 13 —
 * <ul>
 *   <li>② 학교별 중복조회(다중선택)</li>
 *   <li>③ 학교/학원명·학교코드 공란 → 값 표시</li>
 *   <li>⑤ '쿠폰 포함 카운트' 검색조건 추가</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("통합매출조회 학교·쿠폰(항목 13)")
class SalesSearchSchoolCouponIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SC" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2094-06-12";
    private static final String PERIOD = "fromDate=2094-01-01&toDate=2094-12-31";

    private static final String SCH_A = "SCA" + SFX;
    private static final String SCH_B = "SCB" + SFX;
    private static final String SCH_C = "SCC" + SFX;

    private Long partner;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "SCS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "SCP" + SFX, "name", "학교검증거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "SCW" + SFX, "name", "학교검증창고", "type", "MAIN"));

        Long book = book("SCB1" + SFX, "학교검증 교재");
        Long coupon = book("SCB2" + SFX, "해설강의 쿠폰 3개월");   // ★이름에 '쿠폰'

        for (Long p : List.of(book, coupon)) {
            post("/stock/inbound", Map.of("processedDate", DATE, "supplierClientId", supplier,
                    "destinationWarehouseId", wh,
                    "items", List.of(Map.of("productId", p, "unitCost", 1000, "qty", 1000))));
        }

        // 학교 마스터에는 C만 등록한다 — 매출에 이름 없이 코드만 있을 때 채워지는지 보려고.
        createId("/masters/schools", Map.of(
                "schoolCode", SCH_C, "custCode", "SCP" + SFX, "schoolName", "마스터에만있는고등학교",
                "isSchool", true, "schoolType", "SCHOOL"));

        sale(book, 10, SCH_A, "가고등학교");
        sale(book, 20, SCH_B, "나고등학교");
        sale(book, 30, SCH_C, null);          // ★이름 없이 코드만 — 마스터에서 채워져야 한다
        sale(coupon, 40, SCH_A, "가고등학교"); // ★쿠폰 상품
    }

    private Long book(String code, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "S2094A01");
        b.put("catName", "학교검증분류");
        b.put("salesDivision", "교재");
        return createId("/masters/products", b);
    }

    private void sale(Long productId, int qty, String schoolCode, String schoolName) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", productId);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", qty);
        item.put("schoolCode", schoolCode);
        item.put("schoolName", schoolName);
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", wh(),
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출등록: %s", r).isTrue();
    }

    private Long warehouseId;

    private Long wh() {
        if (warehouseId == null) {
            for (JsonNode w : data(get("/masters/warehouses?size=200")).path("content")) {
                if (("SCW" + SFX).equals(w.path("code").asText())) {
                    warehouseId = w.path("id").asLong();
                }
            }
        }
        return warehouseId;
    }

    private JsonNode rows(String query) {
        return data(get("/sales?size=200&partnerId=" + partner + "&" + PERIOD + query))
                .path("content");
    }

    @Test
    @DisplayName("★학교 다중선택 — 한 거래처가 여러 학교에 나가므로 거래처만으로는 안 좁혀진다")
    void 학교_다중선택() {
        assertThat(rows("")).as("전체").hasSize(4);
        assertThat(rows("&schoolCodes=" + SCH_A)).as("A만").hasSize(2);

        JsonNode two = rows("&schoolCodes=" + SCH_A + "," + SCH_B);
        assertThat(two).as("A+B 중복선택").hasSize(3);
        for (JsonNode r : two) {
            assertThat(r.path("schoolCode").asText()).isIn(SCH_A, SCH_B);
        }
    }

    @Test
    @DisplayName("★학교명이 비어 있으면 마스터에서 채운다 — 코드만 넣은 건이 빈칸으로 남지 않게")
    void 학교명_보완() {
        JsonNode row = null;
        for (JsonNode r : rows("&schoolCodes=" + SCH_C)) {
            row = r;
        }
        assertThat(row).isNotNull();
        assertThat(row.path("schoolCode").asText()).isEqualTo(SCH_C);
        assertThat(row.path("schoolName").asText()).isEqualTo("마스터에만있는고등학교");
        assertThat(row.path("schoolTypeName").asText()).isEqualTo("학교");
    }

    @Test
    @DisplayName("매출에 이름이 있으면 마스터로 덮어쓰지 않는다 — 등록 당시 이름이 맞을 수 있다")
    void 기존이름은_유지() {
        for (JsonNode r : rows("&schoolCodes=" + SCH_A)) {
            assertThat(r.path("schoolName").asText()).isEqualTo("가고등학교");
        }
    }

    @Test
    @DisplayName("★쿠폰 포함 카운트 — 기본은 제외(수량 0), 켜면 그대로. 금액은 어느 쪽이든 그대로")
    void 쿠폰_카운트() {
        JsonNode off = null;
        for (JsonNode r : rows("")) {
            if (r.path("productName").asText().contains("쿠폰")) {
                off = r;
            }
        }
        assertThat(off).isNotNull();
        assertThat(off.path("qty").asInt()).as("기본(체크 해제)이면 수량 0").isZero();
        assertThat(off.path("supplyAmount").asLong()).as("금액은 그대로").isEqualTo(280_000);

        JsonNode on = null;
        for (JsonNode r : rows("&couponCount=true")) {
            if (r.path("productName").asText().contains("쿠폰")) {
                on = r;
            }
        }
        assertThat(on).isNotNull();
        assertThat(on.path("qty").asInt()).as("켜면 수량 그대로").isEqualTo(40);
        assertThat(on.path("supplyAmount").asLong()).isEqualTo(280_000);
    }

    @Test
    @DisplayName("쿠폰이 아닌 상품은 영향이 없다 — 이름 판별이 넓게 걸리면 안 된다")
    void 쿠폰아닌건_그대로() {
        for (JsonNode r : rows("&schoolCodes=" + SCH_B)) {
            assertThat(r.path("qty").asInt()).isEqualTo(20);
        }
    }
}
