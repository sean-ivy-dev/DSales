package com.daesung.sales.common;

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
 * 발주처 1차회신(2026-10-02) 반영분 — #5 공급자 전화 · #6 매입반품 · 판매채널.
 *
 * <p>★<b>#6 은 이전 판단을 뒤집은 것이다.</b> 정본 16p 인터뷰(2026-07-25)가
 * "매입반품은 연중이 아니라 연말에 이감과 일괄 계약"으로 닫아 두어 칸을 만들지 않았는데,
 * 발주처가 직접 <b>"매입반품 실제 발생"</b>으로 답했다. 낡은 정본을 근거로 기능을 빼면
 * 이렇게 된다는 기록을 겸해 여기 고정한다.
 *
 * <p>★<b>판매채널은 물어볼 것이 아니었다.</b> 프론트가 「클라이언트 답 후」로 올려 둔 항목이지만
 * 레거시에 정의가 다 있다 — {@code 조회.Designer.vb:365}(콤보 라벨 "판매채널"),
 * {@code CommonDB.vb:475}(목록 = {@code SELECT type1 FROM schDataDT GROUP BY type1}),
 * {@code 조회.vb:1203~}(필터 규칙, '미분류'는 별도 분기).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("발주처 1차회신 반영(2026-10-02)")
class ClientReply1002IntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CR" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2105-06-15";
    private static final String RANGE = "?fromDate=2105-01-01&toDate=2105-12-31";

    private Long partner;
    private Long supplier;
    private Long warehouse;
    private Long external;          // 외부콘텐츠(매입 대상)
    private String schoolMega;      // 판매채널 = 메가스터디
    private String schoolNone;      // 판매채널 미지정

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients",
                Map.of("code", "CRS" + SFX, "name", "매입처", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "CRP" + SFX, "name", "회신검증거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "CRW" + SFX, "name", "회신검증창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "CRB" + SFX);
        b.put("name", "회신검증 매입교재");
        b.put("contentType", "EXTERNAL");   // 외부콘텐츠라야 매입 칸이 나온다
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "C2105A01");
        b.put("catName", "회신검증분류");
        b.put("salesDivision", "교재");
        external = createId("/masters/products", b);

        schoolMega = "CRM" + SFX;
        schoolNone = "CRN" + SFX;
        school(schoolMega, "메가스터디");
        school(schoolNone, null);
    }

    private void school(String code, String channel) {
        Map<String, Object> s = new HashMap<>();
        s.put("schoolCode", code);
        s.put("custCode", "CRP" + SFX);
        s.put("custName", "회신검증거래처");
        s.put("schoolName", code + " 학교");
        s.put("city", "서울");          // 도시명
        s.put("region", "서울관할");     // 지역명 — 도시명과 다른 축이다
        s.put("salesChannel", channel);
        JsonNode r = post("/masters/schools", s);
        assertThat(r.path("success").asBoolean()).as("학교 등록: %s", r).isTrue();
    }

    private void inbound(String inboundType, int qty, int unitCost) {
        JsonNode r = post("/stock/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", warehouse, "inboundType", inboundType,
                "items", List.of(Map.of("productId", external, "unitCost", unitCost, "qty", qty))));
        assertThat(r.path("success").asBoolean()).as("%s: %s", inboundType, r).isTrue();
    }

    private void sell(String schoolCode, int qty) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", external);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", qty);
        item.put("schoolCode", schoolCode);
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출: %s", r).isTrue();
    }

    @Test
    @DisplayName("★★#6 매입반품 — 재고는 줄고, 매입액·이익에는 섞이지 않는다")
    void 매입반품() {
        inbound("PURCHASE", 1000, 500);          // 매입입고 1,000부 @500
        inbound("PURCHASE_RETURN", 120, 500);    // 매입반품 120부 — 공급처로 되돌려 보냄

        JsonNode row = null;
        for (JsonNode r : data(get("/sales/net-summary" + RANGE + "&productIds=" + external))
                .path("rows")) {
            if (r.path("productId").asLong() == external) {
                row = r;
            }
        }
        // 매출이 없어도 매입 흐름은 잡혀야 한다 — 없으면 합계행으로라도 확인한다.
        if (row == null) {
            sell(schoolMega, 10);
            for (JsonNode r : data(get("/sales/net-summary" + RANGE + "&productIds=" + external))
                    .path("rows")) {
                if (r.path("productId").asLong() == external) {
                    row = r;
                }
            }
        }
        assertThat(row).as("순매출 행").isNotNull();

        assertThat(row.path("inboundQty").asLong()).as("매입 입고수량").isEqualTo(1000);
        assertThat(row.path("purchaseReturnQty").asLong()).as("매입반품 수량은 **양수**로 준다")
                .isEqualTo(120);
        assertThat(row.path("purchaseReturnAmount").asLong()).as("120 × 500").isEqualTo(60_000);

        // ‼️매입액은 "판 물건의 원가"(단가×순매출수량)다 — 매입반품을 빼지 않는다.
        //   빼면 반품한 달의 이익률이 실제와 달라진다.
        long netQty = row.path("netQty").asLong();
        assertThat(row.path("purchaseAmount").asLong())
                .as("매입단가 × 순매출수량 — 매입반품과 무관").isEqualTo(500 * netQty);

        // 재고는 실제로 줄어든다(원장에는 음수로 적힌다).
        JsonNode ledger = data(get("/stock/ledger" + RANGE + "&size=200&productId=" + external))
                .path("content").get(0);
        assertThat(ledger.path("inbound").asLong()).as("1,000 − 120 = 880(순입고)").isEqualTo(880);
    }

    @Test
    @DisplayName("★판매채널 — 목록은 등록된 값에서 모으고, '미분류'는 별도 분기다")
    void 판매채널() {
        sell(schoolMega, 5);
        sell(schoolNone, 7);

        assertThat(data(get("/masters/schools/sales-channels")))
                .as("코드표가 아니라 등록된 값을 모은다")
                .anySatisfy(v -> assertThat(v.asText()).isEqualTo("메가스터디"));

        JsonNode mega = data(get("/sales" + RANGE + "&size=200&partnerIds=" + partner
                + "&salesChannels=메가스터디")).path("content");
        assertThat(mega).isNotEmpty();
        for (JsonNode r : mega) {
            assertThat(r.path("schoolCode").asText()).isEqualTo(schoolMega);
            assertThat(r.path("salesChannel").asText()).isEqualTo("메가스터디");
        }

        // ‼️'미분류'를 목록에 섞어 비교하면 영원히 안 걸린다 — 값이 NULL 이라 같을 대상이 없다.
        //   레거시도 별도 분기로 처리한다(조회.vb:1205 `type1 is null or ''`).
        JsonNode none = data(get("/sales" + RANGE + "&size=200&partnerIds=" + partner
                + "&salesChannels=미분류")).path("content");
        assertThat(none).isNotEmpty();
        for (JsonNode r : none) {
            assertThat(r.path("schoolCode").asText()).isEqualTo(schoolNone);
        }



        // 둘을 함께 고르면 합집합이다.
        assertThat(data(get("/sales" + RANGE + "&size=200&partnerIds=" + partner
                + "&salesChannels=메가스터디,미분류")).path("content"))
                .as("합집합").hasSize(mega.size() + none.size());

        // ★★**학교가 아예 없는 매출도 미분류다.** 실데이터에서는 이쪽이 73%(248,949건)인데,
        //   `not in` 만 쓰면 좌변이 NULL 이라 결과가 NULL(거짓)이 되어 **통째로 빠진다**
        //   (2026-10-16 이관 후 실측에서 0건이 나왔다).
        Map<String, Object> noSchool = new HashMap<>();
        noSchool.put("productId", external);
        noSchool.put("shipmentType", "NORMAL_SHIP");
        noSchool.put("unitPrice", 10000);
        noSchool.put("supplyRate", 70);
        noSchool.put("qty", 4);
        post("/sales/entries", Map.of("salesDate", DATE, "partnerId", partner,
                "warehouseId", warehouse, "items", List.of(noSchool)));

        boolean foundNoSchool = false;
        for (JsonNode r : data(get("/sales" + RANGE + "&size=200&partnerIds=" + partner
                + "&salesChannels=미분류")).path("content")) {
            if (!r.hasNonNull("schoolCode")) {
                foundNoSchool = true;
            }
        }
        assertThat(foundNoSchool).as("학교 없는 매출이 미분류에 잡혀야 한다").isTrue();
    }

    @Test
    @DisplayName("★#8 매출액정리 분류 소계 — 상세 합과 소계가 같아야 한다")
    void 매출액정리_소계() {
        sell(schoolMega, 4);   // 분류 C2105A01 하나뿐인 데이터

        JsonNode d = data(get("/sales/summary" + RANGE + "&partnerId=" + partner));
        JsonNode rows = d.path("rows");
        JsonNode subs = d.path("categorySubtotals");

        assertThat(rows).isNotEmpty();
        assertThat(subs).as("분류 소계가 내려와야 한다").isNotEmpty();

        // 행에 분류코드·분류명이 실린다(발주처 확정 컬럼: 분류코드·분류명·도서코드·도서명·…).
        assertThat(rows.get(0).path("catCode").asText()).isEqualTo("C2105A01");
        assertThat(rows.get(0).path("catName").asText()).isEqualTo("회신검증분류");

        long detail = 0;
        for (JsonNode r : rows) {
            detail += r.path("saleQty").asLong();
        }
        long sub = 0;
        for (JsonNode r : subs) {
            sub += r.path("saleQty").asLong();
            // 소계행은 상품 자리를 비운다 — 채우면 특정 도서의 숫자로 읽힌다.
            assertThat(r.hasNonNull("productId")).isFalse();
            assertThat(r.path("productName").asText()).isEqualTo("소계");
        }
        assertThat(sub).as("소계 합 = 상세 합 — 갈리면 둘 다 못 믿는다").isEqualTo(detail);
        assertThat(d.path("total").path("saleQty").asLong()).as("총계도 같다").isEqualTo(detail);
    }

    @Test
    @DisplayName("★#11 수익신고 '전체'는 없앴다 — 어느 양식인지 모른 채 제출하면 안 된다")
    void 수익신고_전체_삭제() {
        // 미지정이면 파라미터 단계에서 막힌다.
        JsonNode missing = get("/closing/revenue-report" + RANGE);
        assertThat(missing.path("success").asBoolean()).isFalse();
        assertThat(missing.path("error").path("message").asText()).contains("taxType");

        // 'ALL'을 **명시해도** 막는다. 여기를 안 막으면 예전 화면이 그대로 전체를 본다.
        JsonNode all = get("/closing/revenue-report" + RANGE + "&taxType=ALL");
        assertThat(all.path("success").asBoolean()).isFalse();
        assertThat(all.path("error").path("message").asText()).contains("과세/면세를 나눠서");

        assertThat(get("/closing/revenue-report" + RANGE + "&taxType=FREE")
                .path("success").asBoolean()).as("나눠서 고르면 된다").isTrue();
    }

    @Test
    @DisplayName("★★#2 자재 입고 — DSRE 병행 상품은 막는다(재고가 두 곳에서 움직이면 안 된다)")
    void 자재입고_병행상품_차단() {
        Long wh = createId("/masters/warehouses",
                Map.of("code", "CRMW" + SFX, "name", "자재검증창고", "type", "MAIN"));
        Long mat = createId("/masters/materials", Map.of("code", "CRMT" + SFX, "name", "자재검증 문제지",
                        "materialType", "EXAM_PAPER", "unit", "부"));

        // 단독관리(단가노출 Y) 도서 + 구성 자재
        Long solo = product("CRSL" + SFX, "단독관리 세트", true);
        post("/masters/products/" + solo + "/materials",
                Map.of("materialId", mat, "qtyPerSet", 1));

        JsonNode ok = post("/stock/materials/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", wh, "productId", solo,
                "items", List.of(Map.of("materialId", mat, "qty", 500))));
        assertThat(ok.path("success").asBoolean()).as("단독관리 도서: %s", ok).isTrue();

        // 병행(단가노출 N) 도서 — DSRE 가 자재를 관리한다. 여기서 받으면 재고가 두 곳이 된다.
        Long dual = product("CRDU" + SFX, "DSRE 병행 세트", false);
        post("/masters/products/" + dual + "/materials",
                Map.of("materialId", mat, "qtyPerSet", 1));

        JsonNode blocked = post("/stock/materials/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", wh, "productId", dual,
                "items", List.of(Map.of("materialId", mat, "qty", 500))));
        assertThat(blocked.path("success").asBoolean()).isFalse();
        assertThat(blocked.path("error").path("message").asText())
                .contains("DSRE 병행 상품");

        // 도서를 바꾼 뒤 이전 도서의 자재를 그대로 보내는 오입력도 막는다.
        Long other = product("CROT" + SFX, "다른 세트", true);
        Long otherMat = createId("/masters/materials", Map.of("code", "CROM" + SFX, "name", "다른 자재",
                        "materialType", "OMR", "unit", "부"));
        post("/masters/products/" + other + "/materials",
                Map.of("materialId", otherMat, "qtyPerSet", 1));

        JsonNode wrong = post("/stock/materials/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", wh, "productId", other,
                "items", List.of(Map.of("materialId", mat, "qty", 10))));
        assertThat(wrong.path("success").asBoolean()).isFalse();
        assertThat(wrong.path("error").path("message").asText()).contains("구성 자재가 아닙니다");
    }

    /** 단가노출로 단독/병행을 가른다(발주처 #1 확정 기준). */
    private Long product(String code, String name, boolean priceVisible) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "C2105B01");
        b.put("catName", "자재검증분류");
        b.put("salesDivision", "교재");
        b.put("priceVisible", priceVisible);
        return createId("/masters/products", b);
    }

    @Test
    @DisplayName("★#18 학교/학원검색 확정 7컬럼 — 지역명과 도시명은 다른 축이다")
    void 학교검색_확정컬럼() {
        JsonNode rows = data(get("/masters/schools/search?schoolCode=" + schoolMega)).path("content");
        assertThat(rows).isNotEmpty();
        JsonNode r = rows.get(0);

        assertThat(r.path("schoolCode").asText()).isEqualTo(schoolMega);
        assertThat(r.path("schoolName").asText()).isNotBlank();
        assertThat(r.path("cityName").asText()).as("도시명").isEqualTo("서울");

        // ★거래처명1(상호)·거래처명2(풀네임)는 **거래처 마스터**에서 온다.
        //   학교 마스터엔 거래처명이 한 칸뿐이라 둘로 나눌 수 없다.
        assertThat(r.path("partnerName2").asText()).isEqualTo("회신검증거래처");

        // ‼️지역명과 도시명은 **다른 축**이다. 예전엔 엑셀 '지역명' 칸에 도시명 값이 들어가 있었다 —
        //   라벨만 보고 쓰면 틀린 값을 옮기게 된다.
        assertThat(r.path("regionName").asText()).as("지역명").isEqualTo("서울관할");
        assertThat(r.path("regionName").asText()).isNotEqualTo(r.path("cityName").asText());
    }

    @Test
    @DisplayName("★C-5 자재 입출고 수정 — 새 전표번호 · 입고 전표는 거부")
    void 자재_입출고_수정() {
        Long wh = createId("/masters/warehouses",
                Map.of("code", "CRIW" + SFX, "name", "자재수정창고", "type", "MAIN"));
        Long mat = createId("/masters/materials", Map.of("code", "CRIM" + SFX,
                "name", "수정검증 자재", "materialType", "OMR", "unit", "부"));

        JsonNode in = post("/stock/materials/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", supplier,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("materialId", mat, "unitCost", 100, "qty", 1000))));
        String inboundNo = data(in).path("inboundNo").asText();

        JsonNode io = post("/stock/materials/io", Map.of(
                "processedDate", DATE, "warehouseId", wh, "io", "DISPOSE",
                "items", List.of(Map.of("materialId", mat, "qty", 200))));
        String oldNo = data(io).path("refNo").asText();

        JsonNode r = patch("/stock/materials/io/" + oldNo, Map.of(
                "processedDate", DATE, "warehouseId", wh, "io", "DISPOSE",
                "items", List.of(Map.of("materialId", mat, "qty", 150, "memo", "수량 정정"))));
        assertThat(r.path("success").asBoolean()).as("자재 입출고 수정: %s", r).isTrue();
        assertThat(data(r).path("refNo").asText()).isNotEqualTo(oldNo);

        // ‼️입고 전표는 거래처·단가가 붙어 있어 축이 다르다 — 여기서 고치면 그 값이 사라진다.
        JsonNode blocked = patch("/stock/materials/io/" + inboundNo, Map.of(
                "processedDate", DATE, "warehouseId", wh, "io", "DISPOSE",
                "items", List.of(Map.of("materialId", mat, "qty", 1))));
        assertThat(blocked.path("success").asBoolean()).isFalse();
        assertThat(blocked.path("error").path("message").asText()).contains("입고");
    }

    @Test
    @DisplayName("#5 공급자 전화가 거래명세서에 실린다 — 비어 있으면 거래처 서류에 빈칸이 나간다")
    void 공급자_전화() {
        sell(schoolMega, 3);

        JsonNode tel = data(get("/sales/transaction-statement" + RANGE + "&partnerId=" + partner))
                .path("provider").path("tel");
        // ‼️운영값(02-812-8001, 발주처 1차회신 #5)은 application.yml 에 있고, 통합테스트는
        //   IntegrationTestSupport 가 더미로 덮는다. 여기서 고정할 수 있는 것은
        //   "설정한 값이 거래명세서까지 실려 나가는가"다 — 그 경로가 끊기면 거래처 서류에 빈칸이 나간다.
        assertThat(tel.asText()).as("공급자 전화가 비어 있으면 안 된다").isNotBlank();
    }

    @Test
    @DisplayName("★#5 운영 설정에 실제 번호가 들어 있다 — 비워 두면 아무도 모르게 빈칸이 인쇄된다")
    void 운영_설정의_전화번호() throws Exception {
        // 설정 파일을 직접 읽는다. 테스트 컨텍스트는 더미로 덮여 있어 그 값으로는 확인할 수 없고,
        // 이 값은 **거래처마다 인쇄되어 나가는 값**이라 비는 순간을 테스트로 잡아야 한다.
        String yml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.yml"));
        assertThat(yml).as("발주처 1차회신(2026-10-02) #5 확정값")
                .contains("tel: \"02-812-8001\"");
    }
}
