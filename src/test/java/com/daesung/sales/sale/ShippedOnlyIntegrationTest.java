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
 * 출고/반품 조회 — <b>실제로 물건이 움직인 건만</b>(10/27 §3-3·4-10·4-12, 프론트 B-1).
 *
 * <p>명세 §3 머리말이 두 화면을 이렇게 가른다:
 * <ul>
 *   <li><b>출고/반품 조회</b>: 실제로 재고가 움직인 출고·반품. <b>물류 작업비 계산의 기준 수량</b></li>
 *   <li><b>통합 매출 조회</b>: 매출로 인식되는 전체. 출고 없는 매출(미출고 콘텐츠·특강)도 포함</li>
 * </ul>
 *
 * <p>빠져야 하는 셋 — <b>매출만(배송X) · 창고 없는 매출 · 특강</b>. 등록 쪽은 이미 이 셋에
 * 작업요청서를 만들지 않는데(10/29 반영), <b>조회에 거르는 조건이 없어</b> 화면에는 그대로 나왔다.
 *
 * <p>‼️<b>"작업요청서가 있나"로 가르지 않는다.</b> 이 규칙 이전 등록분은 매출만 건에도
 * 작업요청서가 남아 있어(재현 건 {@code E-20261027-55} 가 그 경우다), 그걸 기준 삼으면
 * 과거분이 그대로 샌다. 등록 쪽과 <b>같은 규칙</b>으로 직접 건다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("출고/반품 조회 조건(§3-3 · 프론트 B-1)")
class ShippedOnlyIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-SO" + (System.nanoTime() % 1_000_000L);
    private static final String DAY = "2045-03-10";

    private Long partner;
    private Long wh;
    private Long normal;
    private Long lecture;
    /** 재고관리 안 하는 상품 — 창고 없이 팔 수 있는 건 이것뿐이다(문항사용료 등). */
    private Long noStock;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients",
                Map.of("code", "SOS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "SOP" + SFX, "name", "출고조회거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses",
                Map.of("code", "SOW" + SFX, "name", "물류", "type", "MAIN"));
        normal = book("SON" + SFX, "정상도서", "교재");
        lecture = book("SOL" + SFX, "특강도서", "특강");
        Map<String, Object> ns = new HashMap<>();
        ns.put("code", "SOX" + SFX);
        ns.put("name", "문항사용료");
        ns.put("contentType", "SELF");
        ns.put("price", 10000);
        ns.put("supplyRate", 70);
        ns.put("salesDivision", "교재");
        ns.put("stockManaged", false);
        noStock = createId("/masters/products", ns);
        post("/stock/inbound", Map.of("processedDate", "2045-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", normal, "unitCost", 1000, "qty", 900),
                        Map.of("productId", lecture, "unitCost", 1000, "qty", 900))));

        // ① 정상 출고 — 남아야 한다
        sell(normal, 10, wh, false);
        // ② 매출만(배송X) — 빠져야 한다
        sell(normal, 20, wh, true);
        // ③ 창고 없는 매출 — 빠져야 한다.
        //   ‼️창고를 비울 수 있는 건 **재고관리 안 하는 상품**뿐이다(기존 규칙:
        //     "재고가 움직이는 매출은 창고를 지정해야 합니다. 출고 없는 매출(문항사용료 등)만").
        //   「매출만」은 체크하지 않는다 — **창고 없음**만으로 빠지는지를 보려는 것이다.
        sell(noStock, 30, null, false);
        // ④ 특강 — 빠져야 한다
        sell(lecture, 40, wh, false);
    }

    private Long book(String code, String name, String division) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("salesDivision", division);
        return createId("/masters/products", b);
    }

    private void sell(Long product, int qty, Long warehouse, boolean onlySales) {
        Map<String, Object> body = new HashMap<>();
        body.put("salesDate", DAY);
        body.put("partnerId", partner);
        if (warehouse != null) {
            body.put("warehouseId", warehouse);
        }
        body.put("onlySales", onlySales);
        body.put("items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                "qty", qty, "unitPrice", 10000, "supplyRate", 70)));
        JsonNode r = post("/sales/entries", body);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
    }

    private List<Long> qtysOf(String extra) {
        List<Long> out = new java.util.ArrayList<>();
        for (JsonNode l : data(get("/sales?fromDate=" + DAY + "&toDate=" + DAY
                + "&partnerId=" + partner + "&size=50" + extra)).path("content")) {
            out.add(l.path("qty").asLong());
        }
        return out;
    }

    @Test
    @DisplayName("기본(통합 매출 조회)은 넷 다 나온다 — 지금까지와 똑같다")
    void 기본은_전부() {
        assertThat(qtysOf("")).containsExactlyInAnyOrder(10L, 20L, 30L, 40L);
    }

    @Test
    @DisplayName("★shippedOnly=true 면 매출만·창고없음·특강이 빠진다")
    void 출고만_남는다() {
        assertThat(qtysOf("&shippedOnly=true"))
                .as("정상 출고 10만 남아야")
                .containsExactly(10L);
    }

    @Test
    @DisplayName("엑셀도 같은 조건을 받는다 — 화면에 없던 건이 파일에 실리면 안 된다")
    void 엑셀도_같은조건() {
        byte[] xlsx = getBytes("/sales/export?fromDate=" + DAY + "&toDate=" + DAY
                + "&partnerId=" + partner + "&shippedOnly=true").getBody();
        assertThat(xlsx).isNotNull().isNotEmpty();
        // 내용까지 보려면 시트를 열어야 한다 — 여기서는 조건이 **받아들여지는지**만 고정한다
        // (받지 않으면 400이 나고 본문이 비어 온다).
    }
}
