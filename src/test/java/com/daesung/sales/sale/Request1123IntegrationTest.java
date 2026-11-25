package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 발주처 요청(2026-11-23) 중 <b>답을 기다릴 필요가 없던 세 건</b>을 못박는다.
 *
 * <ul>
 *   <li><b>1-3</b> 재고관리 N 상품을 출고 반품으로 넣으면 <b>경고만</b>(저장은 된다)</li>
 *   <li><b>2번</b> 반품 가능내역에 <b>분류명</b>이 함께 내려온다</li>
 *   <li><b>6번</b> 업로드 양식 13번째 칸 「출고 창고」 — 적으면 출고/반품 조회에 들어오고,
 *       빈칸·「미선택」이면 지금처럼 빠진다. <b>12칸 기존 파일도 그대로 올라간다</b></li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("발주처 요청 1123 — 재고관리N 경고·분류명·출고창고 열")
class Request1123IntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "R23" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2074;
    private static final String CAT = "R" + YEAR + "A";
    private static final String CAT_NAME = "요청1123분류";

    private Long partner;
    private Long warehouse;
    private Long managed;        // 재고관리 Y
    private Long unmanaged;      // 재고관리 N
    private String managedCode;
    private String warehouseCode;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "R23S" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouseCode = "R23W" + SFX;
        warehouse = createId("/masters/warehouses",
                Map.of("code", warehouseCode, "name", "요청1123창고" + SFX, "type", "MAIN"));
        partner = createId("/masters/clients",
                Map.of("code", "R23P" + SFX, "name", "요청1123거래처", "type", "NORMAL"));

        managedCode = "R23BY" + SFX;
        managed = book(managedCode, "요청1123 관리도서", true);
        unmanaged = book("R23BN" + SFX, "요청1123 미관리도서", false);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", managed, "qty", 500, "unitCost", 3000))))
                .path("success").asBoolean()).isTrue();

        // 반품 가능내역·반품 경고를 보려면 먼저 출고가 있어야 한다.
        for (Long id : List.of(managed, unmanaged)) {
            assertThat(post("/sales/entries", Map.of(
                    "salesDate", YEAR + "-02-10", "partnerId", partner, "warehouseId", warehouse,
                    "items", List.of(Map.of("productId", id, "shipmentType", "NORMAL_SHIP",
                            "qty", 20, "unitPrice", 10000, "supplyRate", 70))))
                    .path("success").asBoolean()).isTrue();
        }
    }

    private Long book(String code, String name, boolean stockManaged) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", CAT);
        b.put("catName", CAT_NAME);
        b.put("salesDivision", "교재");
        b.put("stockManaged", stockManaged);
        return createId("/masters/products", b);
    }

    @Test
    @DisplayName("★1-3 재고관리 N 출고 반품 — **막지 않고 경고만**(1-2 '모든 상품 등록 가능'과 같은 방향)")
    void 재고관리N_반품은_경고만() {
        JsonNode d = data(post("/sales/return-inbound", Map.of(
                "returnDate", YEAR + "-03-05", "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", unmanaged, "qty", 3,
                        "unitPrice", 10000, "supplyRate", 70)))));

        assertThat(d.path("items")).as("‼️저장까지 거부하면 1-2 지시와 어긋난다").hasSize(1);
        assertThat(codes(d.path("warnings")))
                .as("경고: %s", d.path("warnings"))
                .contains("STOCK_UNMANAGED_RETURN");
    }

    @Test
    @DisplayName("재고관리 Y 반품엔 그 경고가 안 붙는다 — 아무 반품에나 뜨면 담당자가 무시하게 된다")
    void 재고관리Y_반품엔_경고없음() {
        JsonNode d = data(post("/sales/return-inbound", Map.of(
                "returnDate", YEAR + "-03-06", "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", managed, "qty", 2,
                        "unitPrice", 10000, "supplyRate", 70)))));
        assertThat(codes(d.path("warnings"))).doesNotContain("STOCK_UNMANAGED_RETURN");
    }

    @Test
    @DisplayName("★2번 반품 가능내역에 **분류명**이 함께 온다 — 도서명만으론 도서를 못 가린다")
    void 반품가능내역_분류명() {
        JsonNode rows = data(get("/sales/returnable?partnerId=" + partner)).path("rows");
        assertThat(rows).isNotEmpty();
        boolean found = false;
        for (JsonNode n : rows) {
            assertThat(n.path("catName").asText()).as("줄: %s", n).isEqualTo(CAT_NAME);
            assertThat(n.path("catCode").asText()).isEqualTo(CAT);
            found = true;
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("★6번 「출고 창고」를 적으면 **출고/반품 조회에 들어온다** — 온라인몰 매출이 작업비 수량 기준에 들어가야 한다")
    void 업로드_출고창고_지정() {
        JsonNode d = data(multipart("/sales/upload",
                sheet(YEAR + "-04-01", warehouseCode), "wh.xlsx"));
        assertThat(d.path("imported").asInt()).as("결과: %s", d).isEqualTo(1);

        JsonNode rows = data(get("/sales?fromDate=" + YEAR + "-04-01&toDate=" + YEAR + "-04-01"
                + "&partnerId=" + partner + "&shippedOnly=true&size=50")).path("content");
        assertThat(rows).as("‼️창고를 남기지 않으면 shippedOnly 가 통째로 걸러낸다").hasSize(1);
        assertThat(rows.get(0).path("warehouseName").asText()).contains("요청1123창고");
    }

    @Test
    @DisplayName("빈칸·「미선택」은 **지금처럼** — 창고를 남기지 않아 출고/반품 조회에서 빠진다")
    void 업로드_미선택은_현행유지() {
        for (String token : List.of("", "미선택")) {
            String day = "미선택".equals(token) ? YEAR + "-04-03" : YEAR + "-04-02";
            JsonNode d = data(multipart("/sales/upload", sheet(day, token), "blank.xlsx"));
            assertThat(d.path("imported").asInt()).as("칸=[%s] 결과: %s", token, d).isEqualTo(1);

            assertThat(data(get("/sales?fromDate=" + day + "&toDate=" + day
                    + "&partnerId=" + partner + "&shippedOnly=true&size=50")).path("content"))
                    .as("칸=[%s] — 창고가 없으니 출고/반품 조회엔 안 나온다", token)
                    .isEmpty();
            assertThat(data(get("/sales?fromDate=" + day + "&toDate=" + day
                    + "&partnerId=" + partner + "&size=50")).path("content"))
                    .as("칸=[%s] — 통합 매출엔 있어야 한다(매출만)", token)
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("‼️**13번째 칸이 없는 12칸 기존 파일도 그대로 올라간다** — 발주처가 호환을 명시 요구했다")
    void 업로드_12칸_기존양식() {
        JsonNode d = data(multipart("/sales/upload", sheet(YEAR + "-04-05", null), "old12.xlsx"));
        assertThat(d.path("imported").asInt()).as("결과: %s", d).isEqualTo(1);
    }

    @Test
    @DisplayName("적었는데 못 찾으면 **그 줄을 거부한다** — 조용히 물류창고로 돌리면 담당자가 모른다")
    void 업로드_없는_창고() {
        JsonNode d = data(multipart("/sales/upload",
                sheet(YEAR + "-04-07", "없는창고" + SFX), "bad.xlsx"));
        assertThat(d.path("imported").asInt()).isZero();
        assertThat(d.path("failed").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("★7번 도서별 묶음 합계 — **화면마다 그 화면 기준**(출고/반품=재고 움직인 줄만, 통합매출=전부)")
    void 도서별_묶음합계() {
        String day = YEAR + "-02-11";
        // 출고 창고가 있는 줄(= 재고가 움직인 줄)
        assertThat(post("/sales/entries", Map.of(
                "salesDate", day, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", managed, "shipmentType", "NORMAL_SHIP",
                        "qty", 7, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
        // 「매출만」 줄 — 출고 창고를 고르지 않았다. 출고/반품 조회는 이 줄을 뺀다.
        assertThat(post("/sales/entries", Map.of(
                "salesDate", day, "partnerId", partner,
                "items", List.of(Map.of("productId", unmanaged, "shipmentType", "NORMAL_SHIP",
                        "qty", 3, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();

        String q = "?fromDate=" + day + "&toDate=" + day
                + "&partnerIds=" + partner + "&groupBy=PRODUCT";

        // 통합 매출 조회 기준 — 두 줄 다. **도서별로 갈라져** 나와야 한다(7번).
        java.util.Map<String, Long> all = qtyByName(get("/sales/group-totals" + q));
        assertThat(all).as("‼️도서별로 안 갈라지면 트리 줄에 수량을 붙일 수 없다: %s", all)
                .containsEntry("요청1123 관리도서", 7L)
                .containsEntry("요청1123 미관리도서", 3L);

        // 출고/반품 조회 기준 — 창고 있는 줄만.
        java.util.Map<String, Long> shipped =
                qtyByName(get("/sales/group-totals" + q + "&shippedOnly=true"));
        assertThat(shipped)
                .as("‼️두 기준이 같은 값을 내면 7번이 요구한 '각 화면 조회 기준'이 아니다: %s", shipped)
                .containsExactly(java.util.Map.entry("요청1123 관리도서", 7L));
    }

    @Test
    @DisplayName("★★출고/반품 조회에서 **재고관리 N + 수불부노출 N** 만 뺀다(발주처 확정 2026-11-24 §2)")
    void 재고관리N_수불부노출N만_제외() {
        String day = YEAR + "-05-20";
        // ① 재고관리 N + 수불부노출 N — **빠진다**
        Long both = bookFlags("R23X1" + SFX, "둘다N", false, false);
        // ② 재고관리 N + 수불부노출 Y — 남는다(한쪽만 N 이면 안 빠진다)
        Long ledgerOnly = bookFlags("R23X2" + SFX, "수불부만Y", false, true);
        // ③ 재고관리 Y + 수불부노출 N — 남는다
        Long stockOnly = bookFlags("R23X3" + SFX, "재고관리만Y", true, false);

        for (Long id : List.of(both, ledgerOnly, stockOnly)) {
            assertThat(post("/sales/entries", Map.of(
                    "salesDate", day, "partnerId", partner, "warehouseId", warehouse,
                    "items", List.of(Map.of("productId", id, "shipmentType", "NORMAL_SHIP",
                            "qty", 5, "unitPrice", 10000, "supplyRate", 70))))
                    .path("success").asBoolean()).isTrue();
        }

        String q = "?fromDate=" + day + "&toDate=" + day + "&partnerIds=" + partner
                + "&groupBy=PRODUCT";
        java.util.Map<String, Long> all = qtyByName(get("/sales/group-totals" + q));
        java.util.Map<String, Long> shipped =
                qtyByName(get("/sales/group-totals" + q + "&shippedOnly=true"));

        assertThat(all).as("‼️통합 매출 조회에서는 **빼지 않는다**(발주처 명시)")
                .containsKeys("둘다N", "수불부만Y", "재고관리만Y");
        assertThat(shipped.keySet())
                .as("‼️출고/반품 조회 — 둘 다 N 인 것만 빠진다: %s", shipped)
                .containsExactlyInAnyOrder("수불부만Y", "재고관리만Y");
    }

    /** 재고관리·수불부노출 플래그를 지정해 도서를 만든다. */
    private Long bookFlags(String code, String name, boolean stockManaged, boolean ledgerVisible) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", CAT);
        b.put("catName", CAT_NAME);
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        b.put("stockManaged", stockManaged);
        b.put("ledgerVisible", ledgerVisible);
        return createId("/masters/products", b);
    }

    private java.util.Map<String, Long> qtyByName(JsonNode res) {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        data(res).forEach(n -> out.put(n.path("name").asText(), n.path("qty").asLong()));
        return out;
    }

    /**
     * 표준양식. {@code warehouseToken} 이 {@code null} 이면 <b>13번째 칸을 아예 안 만든다</b>
     * (= 기존 12칸 양식), 빈 문자열이면 칸은 있고 비어 있다.
     */
    private byte[] sheet(String date, String warehouseToken) {
        String[] head = {"거래일자", "거래처코드", "학교코드", "분류코드", "도서코드", "회차",
                "정가", "공급률(%)", "수량", "금액", "구분(출고유형)", "메모", "출고 창고"};
        Object[] row = {date, "R23P" + SFX, "", CAT, managedCode, "",
                10000, 0.7, 10, 70000, "정상출고", "요청1123", warehouseToken};
        int cols = (warehouseToken == null) ? 12 : 13;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("s");
            Row h = s.createRow(0);
            Row r = s.createRow(1);
            for (int i = 0; i < cols; i++) {
                h.createCell(i).setCellValue(head[i]);
                if (row[i] instanceof Number n) {
                    r.createCell(i).setCellValue(n.doubleValue());
                } else {
                    r.createCell(i).setCellValue(String.valueOf(row[i]));
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> codes(JsonNode warnings) {
        List<String> out = new java.util.ArrayList<>();
        warnings.forEach(w -> out.add(w.path("code").asText()));
        return out;
    }
}
