package com.daesung.sales.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 대분류 · 세부구분 축(발주처 회신 2026-08-20) 회귀 고정.
 *
 * <p>지키려는 것 —
 * ① 대분류는 5종 고정이고 IC는 화면에서 빠진다(데이터는 보존).
 * ② 세부구분→대분류 매핑이 회신표 그대로다(D모의고사→기타고사 등).
 * ③ 상품의 대분류는 <b>파생</b>이다 — 매핑을 고치면 상품을 안 건드려도 따라 바뀐다.
 * ④ 마스터에 없는 세부구분은 거부한다(예전 자유 문자열 시절의 오타 유입 차단).
 * ⑤ 쓰는 상품이 있는 세부구분은 지워지지 않고 비활성으로 돌아간다.
 */
class SalesDivisionIntegrationTest extends IntegrationTestSupport {

    /** 실행별 고유 접미어 — 컨테이너를 클래스 간 공유하고 워커 JVM이 재사용돼 코드가 겹치면 깨진다. */
    private final String sfx = String.valueOf(System.nanoTime()).substring(9);

    @Test
    @DisplayName("대분류는 5종 고정이고 IC는 목록에서 빠진다 — 숨김이지 삭제가 아니다")
    void 대분류_5종_고정() {
        JsonNode list = data(get("/masters/sales-divisions/major-categories"));

        // ‼️findValuesAsText는 하위 divisions[].code까지 재귀로 긁는다 — 최상위만 읽어야 한다.
        java.util.List<String> codes = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        list.forEach(n -> {
            codes.add(n.path("code").asText());
            names.add(n.path("name").asText());
        });

        assertThat(list).hasSize(5);
        assertThat(codes).containsExactly("MOCK_EXAM", "TEXTBOOK", "ETC_EXAM", "SPECIAL_LECTURE", "ETC");
        assertThat(names).containsExactly("모의고사", "교재", "기타고사", "특강", "기타");
        // IC는 enum에는 살아 있고(과거 데이터 보존) 화면 목록에만 없다.
        assertThat(codes).doesNotContain("IC");
    }

    @Test
    @DisplayName("세부구분→대분류 매핑이 회신표 그대로다")
    void 매핑표_일치() {
        Map<String, String> expected = Map.of(
                "모의고사", "MOCK_EXAM",
                "교재", "TEXTBOOK",
                "D모의고사", "ETC_EXAM",
                "학원콘텐츠", "ETC_EXAM",
                "D:VOCA", "TEXTBOOK",
                "지자체", "SPECIAL_LECTURE",
                "기타", "ETC");

        Map<String, String> actual = new HashMap<>();
        for (JsonNode d : data(get("/masters/sales-divisions"))) {
            actual.put(d.path("code").asText(), d.path("majorCategory").asText());
        }

        assertThat(actual).containsAllEntriesOf(expected);
    }

    @Test
    @DisplayName("상품의 대분류는 세부구분에서 파생된다 — 매핑을 고치면 상품을 안 건드려도 따라 바뀐다")
    void 대분류는_파생값() {
        // 이 테스트 전용 세부구분(교재 소속)
        String code = "테스트구분" + sfx;
        long divisionId = createId("/masters/sales-divisions", Map.of(
                "code", code, "name", code, "majorCategory", "TEXTBOOK", "sortOrder", 50));

        long productId = createId("/masters/products", Map.of(
                "code", "P" + sfx, "name", "파생검증도서" + sfx,
                "contentType", "SELF", "set", false, "price", 10000,
                "taxFree", true, "salesDivision", code));

        JsonNode before = data(get("/masters/products/" + productId));
        assertThat(before.path("majorCategory").asText()).isEqualTo("TEXTBOOK");
        assertThat(before.path("majorCategoryName").asText()).isEqualTo("교재");
        assertThat(before.path("salesDivisionName").asText()).isEqualTo(code);

        // 매핑만 바꾼다(상품은 손대지 않는다)
        put("/masters/sales-divisions/" + divisionId, Map.of(
                "code", code, "name", code, "majorCategory", "SPECIAL_LECTURE"));

        JsonNode after = data(get("/masters/products/" + productId));
        assertThat(after.path("majorCategory").asText()).isEqualTo("SPECIAL_LECTURE");
        assertThat(after.path("majorCategoryName").asText()).isEqualTo("특강");
        // 상품이 들고 있는 값은 여전히 세부구분 코드 하나뿐이다(대분류를 복사해두지 않았다는 증거)
        assertThat(after.path("salesDivision").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("마스터에 없는 세부구분으로 상품을 등록하면 거부한다")
    void 미등록_세부구분_거부() {
        JsonNode r = post("/masters/products", Map.of(
                "code", "PX" + sfx, "name", "오타도서" + sfx,
                "contentType", "SELF", "set", false, "price", 1000,
                "taxFree", true, "salesDivision", "존재하지않는구분" + sfx));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("★세부구분 미지정은 **더 이상 등록할 수 없다**(G-3로 뒤집힘)")
    void 미지정은_등록거부() {
        // ‼️**뒤집힌 테스트다.** 예전엔 "미지정 상품은 통과하고 대분류는 미분류로 나간다"를
        //   고정했다. 10/27 §7-C 3번("신규 상품에서 미분류가 생기지 않게")과 프론트 G-3로
        //   **등록 자체를 막는 쪽**으로 바뀌었다. 미분류 상품은 이제 이관분으로만 남는다.
        JsonNode r = post("/masters/products", Map.of(
                "code", "PN" + sfx, "name", "미지정도서" + sfx,
                "contentType", "SELF", "set", false, "price", 1000, "taxFree", true));

        assertThat(r.path("success").asBoolean()).as("%s", r).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("세부구분");
    }

    @Test
    @DisplayName("쓰는 상품이 있으면 삭제 대신 비활성 — 지우면 과거 매출의 대분류를 잃는다")
    void 사용중이면_비활성() {
        String used = "사용중구분" + sfx;
        long usedId = createId("/masters/sales-divisions", Map.of(
                "code", used, "name", used, "majorCategory", "ETC", "sortOrder", 51));
        createId("/masters/products", Map.of(
                "code", "PU" + sfx, "name", "사용중도서" + sfx,
                "contentType", "SELF", "set", false, "price", 1000,
                "taxFree", true, "salesDivision", used));

        // 사용 중 → false(비활성 처리), 값은 남아 있다
        assertThat(data(del("/masters/sales-divisions/" + usedId)).asBoolean()).isFalse();
        assertThat(codesOf(get("/masters/sales-divisions?includeUnused=true"))).contains(used);
        // 기본 목록(사용중만)에서는 사라진다 — 상품 등록 화면에 다시 뜨면 안 된다
        assertThat(codesOf(get("/masters/sales-divisions"))).doesNotContain(used);

        // 아무도 안 쓰는 구분 → 실제 삭제
        String unused = "미사용구분" + sfx;
        long unusedId = createId("/masters/sales-divisions", Map.of(
                "code", unused, "name", unused, "majorCategory", "ETC", "sortOrder", 52));
        assertThat(data(del("/masters/sales-divisions/" + unusedId)).asBoolean()).isTrue();
        assertThat(codesOf(get("/masters/sales-divisions?includeUnused=true"))).doesNotContain(unused);
    }

    @Test
    @DisplayName("같은 코드로 두 번 등록하면 거부한다")
    void 코드_중복_거부() {
        String code = "중복구분" + sfx;
        Map<String, Object> body = Map.of(
                "code", code, "name", code, "majorCategory", "ETC", "sortOrder", 53);
        createId("/masters/sales-divisions", body);

        assertThat(post("/masters/sales-divisions", body).path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("매출액명세서: 분류코드가 달라도 같은 대분류면 '분류 계'가 한 번만 찍힌다")
    void 명세서_대분류_rollup() {
        // 같은 대분류(기타고사)에 속하는 세부구분 둘 — 회신의 D모의고사·학원콘텐츠와 같은 관계
        String d1 = "기타A" + sfx;
        String d2 = "기타B" + sfx;
        createId("/masters/sales-divisions", Map.of(
                "code", d1, "name", d1, "majorCategory", "ETC_EXAM", "sortOrder", 60));
        createId("/masters/sales-divisions", Map.of(
                "code", d2, "name", d2, "majorCategory", "ETC_EXAM", "sortOrder", 61));

        Long wh = createId("/masters/warehouses",
                Map.of("code", "SDW" + sfx, "name", "명세창고", "type", "MAIN"));
        Long partner = createId("/masters/clients",
                Map.of("code", "SDC" + sfx, "name", "명세거래처", "type", "NORMAL"));

        // ‼️분류코드를 서로 다르게 준다 — 예전 로직(대분류=분류코드 첫 글자)이라면
        //   'X'와 'Y'로 갈려 대분류가 둘로 쪼개졌을 조합이다.
        long p1 = product("SDP1" + sfx, "X2028X01", d1, wh, partner);
        long p2 = product("SDP2" + sfx, "Y2028Y01", d2, wh, partner);

        // 다른 테스트와 겹치지 않는 기간(2028-03)에서만 검증한다
        sale(partner, wh, p1, 10);
        sale(partner, wh, p2, 7);

        JsonNode rows = data(get("/sales/statement?fromDate=2028-03-01&toDate=2028-03-31")).path("rows");

        java.util.List<JsonNode> majorTotals = new java.util.ArrayList<>();
        rows.forEach(r -> {
            if ("MAJOR_TOTAL".equals(r.path("rowType").asText())) {
                majorTotals.add(r);
            }
        });

        assertThat(majorTotals).hasSize(1);
        assertThat(majorTotals.get(0).path("majorCategory").asText()).isEqualTo("ETC_EXAM");
        assertThat(majorTotals.get(0).path("majorName").asText()).isEqualTo("기타고사");
        assertThat(majorTotals.get(0).path("qty").asLong()).isEqualTo(17);   // 10 + 7
    }

    @Test
    @DisplayName("34p 단가: 매핑 하나가 그 대분류의 모든 도서에 걸린다 — 도서마다 깔 필요가 없다")
    void 단가는_대분류_단위() {
        Long wh = createId("/masters/warehouses",
                Map.of("code", "PSW" + sfx, "name", "단가창고", "type", "MAIN"));
        Long partner = createId("/masters/clients",
                Map.of("code", "PSC" + sfx, "name", "단가거래처", "type", "NORMAL"));

        // 같은 대분류(교재)에 속하는 서로 다른 도서 둘
        long book1 = product("PSB1" + sfx, "H2028H01", "교재", wh, partner);
        long book2 = product("PSB2" + sfx, "H2028H02", "교재", wh, partner);

        // 거래처 × 대분류 매핑 한 건만 등록
        put("/masters/partner-supply-rates/" + partner + "/TEXTBOOK", Map.of("supplyRate", 80));

        // 도서별 매핑을 따로 안 깔았는데도 두 도서 모두 80%가 적용된다
        assertThat(supplyAmountOf(partner, wh, book1)).isEqualTo(80_000);   // 10000 × 80% × 10
        assertThat(supplyAmountOf(partner, wh, book2)).isEqualTo(80_000);

        // 사용여부를 끄면 자동조회에서 빠진다 → 도서 기본 공급률이 없어 등록 실패
        put("/masters/partner-supply-rates/" + partner + "/TEXTBOOK", Map.of("useYn", false));
        JsonNode fail = post("/sales/entries", Map.of(
                "salesDate", "2028-03-10", "partnerId", partner, "warehouseId", wh,
                "items", java.util.List.of(Map.of(
                        "productId", book1, "shipmentType", "NORMAL_SHIP", "qty", 1))));
        assertThat(fail.path("success").asBoolean()).as("꺼진 매핑은 없는 것으로 본다: %s", fail).isFalse();
    }

    @Test
    @DisplayName("할인액: 있으면 공급률 대신 쓰인다 — 금액 = (정가−할인액)×수량, 이중할인 아님")
    void 할인액_금액반영() {
        Long wh = createId("/masters/warehouses",
                Map.of("code", "DCW" + sfx, "name", "할인창고", "type", "MAIN"));
        Long partner = createId("/masters/clients",
                Map.of("code", "DCC" + sfx, "name", "할인거래처", "type", "NORMAL"));
        long book = product("DCB" + sfx, "H2028D01", "교재", wh, partner);

        // 공급률 80%만 있을 때: 10000 × 80% × 10 = 80,000
        put("/masters/partner-supply-rates/" + partner + "/TEXTBOOK", Map.of("supplyRate", 80));
        assertThat(supplyAmountOf(partner, wh, book)).isEqualTo(80_000);

        // 할인액 3000을 주면 공급률은 금액에 쓰이지 않는다: (10000−3000) × 10 = 70,000
        // ‼️이중할인(10000×80%−3000)×10 = 50,000 이 아니어야 한다
        put("/masters/partner-supply-rates/" + partner + "/TEXTBOOK",
                Map.of("supplyRate", 80, "discountAmount", 3000));
        assertThat(supplyAmountOf(partner, wh, book)).isEqualTo(70_000);

        // 적용된 할인액이 매출에 남는다 — 매핑은 나중에 바뀌므로 금액 근거가 사라지면 안 된다.
        // 이 거래처의 매출 중 할인 70,000짜리 건을 찾아 확인한다(앞의 80,000 건은 할인 없음).
        JsonNode sales = data(get("/sales?fromDate=2028-03-01&toDate=2028-03-31&partnerId=" + partner))
                .path("content");
        boolean found = false;
        for (JsonNode s : sales) {
            if (s.path("supplyAmount").asLong() == 70_000L) {
                assertThat(s.path("discountAmount").asInt()).isEqualTo(3000);
                found = true;
            }
        }
        assertThat(found).as("할인 적용 매출이 조회돼야 함: %s", sales).isTrue();

        // 라인에 직접 넣은 할인액이 매핑을 이긴다: (10000−5000) × 10 = 50,000
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2028-03-10", "partnerId", partner, "warehouseId", wh,
                "items", java.util.List.of(Map.of(
                        "productId", book, "shipmentType", "NORMAL_SHIP", "qty", 10,
                        "discountAmount", 5000))));
        assertThat(data(r).path("items").get(0).path("supplyAmount").asLong()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("할인액이 정가보다 크면 거부 — 단가가 음수가 되면 매출이 마이너스로 쌓인다")
    void 할인액_정가초과_거부() {
        Long wh = createId("/masters/warehouses",
                Map.of("code", "DXW" + sfx, "name", "초과창고", "type", "MAIN"));
        Long partner = createId("/masters/clients",
                Map.of("code", "DXC" + sfx, "name", "초과거래처", "type", "NORMAL"));
        long book = product("DXB" + sfx, "H2028X01", "교재", wh, partner);

        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2028-03-10", "partnerId", partner, "warehouseId", wh,
                "items", java.util.List.of(Map.of(
                        "productId", book, "shipmentType", "NORMAL_SHIP", "qty", 1,
                        "supplyRate", 75, "discountAmount", 99999))));

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
    }

    /** 공급률 미입력으로 매출 1건 등록 → 자동적용된 공급가액. */
    private long supplyAmountOf(Long partner, Long wh, long productId) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2028-03-10", "partnerId", partner, "warehouseId", wh,
                "items", java.util.List.of(Map.of(
                        "productId", productId, "shipmentType", "NORMAL_SHIP", "qty", 10))));
        assertThat(r.path("success").asBoolean()).as("매출등록 성공: %s", r).isTrue();
        return data(r).path("items").get(0).path("supplyAmount").asLong();
    }

    private long product(String code, String catCode, String division, Long wh, Long partner) {
        long id = createId("/masters/products", Map.of(
                "code", code, "name", code, "contentType", "SELF", "set", false,
                "price", 10000, "taxFree", true, "catCode", catCode, "catName", catCode,
                "salesDivision", division));
        post("/stock/inbound", Map.of(
                "processedDate", "2028-03-01", "supplierClientId", partner, "destinationWarehouseId", wh,
                "items", java.util.List.of(Map.of("productId", id, "unitCost", 1000, "qty", 100))));
        return id;
    }

    private void sale(Long partner, Long wh, long productId, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2028-03-10", "partnerId", partner, "warehouseId", wh,
                "items", java.util.List.of(Map.of(
                        "productId", productId, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 75, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("매출등록 성공: %s", r).isTrue();
    }

    private java.util.List<String> codesOf(JsonNode apiResponse) {
        return data(apiResponse).findValuesAsText("code");
    }
}
