package com.daesung.sales.master;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.junit.jupiter.api.TestInstance;

/**
 * 기초마스터 확장 필드(32p) 통합테스트.
 * 도서(매출구분·수불부노출·Web게시) / 창고(실물재고여부·소속거래처) + 수불부노출 연동.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("기초마스터 확장 통합테스트")
class MasterFieldIntegrationTest extends IntegrationTestSupport {

    @BeforeAll
    void auth() {
        token();
    }

    @Test
    @DisplayName("도서 신규필드 — 세부구분(구 매출구분)·수불부노출·Web게시 저장/응답")
    void 도서_신규필드() {
        JsonNode d = data(post("/masters/products", Map.of(
                "code", "MF-BK1", "name", "확장필드 도서", "contentType", "SELF",
                "salesDivision", "교재", "ledgerVisible", false, "webVisible", true,
                "productYear", 2026, "productType", "교재")));
        assertThat(d.path("salesDivision").asText()).isEqualTo("교재");
        // 대분류는 세부구분 마스터에서 파생된다(회신 2026-08-20)
        assertThat(d.path("majorCategoryName").asText()).isEqualTo("교재");
        assertThat(d.path("ledgerVisible").asBoolean()).isFalse();
        assertThat(d.path("webVisible").asBoolean()).isTrue();
        assertThat(d.path("productYear").asInt()).isEqualTo(2026);   // 32p 상품년도
        assertThat(d.path("productType").asText()).isEqualTo("교재");  // 32p 상품구분

        // 수정으로도 반영
        long id = d.path("id").asLong();
        JsonNode upd = data(put("/masters/products/" + id, Map.of(
                "name", "확장필드 도서", "contentType", "SELF", "set", false, "taxFree", false,
                "useYn", true, "ledgerVisible", true, "webVisible", false, "stockManaged", true,
                "productYear", 2027, "productType", "모의고사")));
        assertThat(upd.path("productYear").asInt()).isEqualTo(2027);
        assertThat(upd.path("productType").asText()).isEqualTo("모의고사");

        // 미지정 시 기본값(수불부노출 true, Web게시 false)
        JsonNode def = data(post("/masters/products", Map.of(
                "code", "MF-BK2", "name", "기본값 도서", "contentType", "SELF",
                "salesDivision", "교재")));
        assertThat(def.path("ledgerVisible").asBoolean()).isTrue();
        assertThat(def.path("webVisible").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("창고 신규필드 — 실물재고여부·소속거래처 저장/응답")
    void 창고_신규필드() {
        Long owner = createId("/masters/clients",
                Map.of("code", "MF-OWN", "name", "소속거래처A", "type", "NORMAL"));
        JsonNode d = data(post("/masters/warehouses", Map.of(
                "code", "MF-WH-C", "name", "위탁창고", "type", "CONSIGN",
                "physicalStock", false, "ownerClientId", owner)));
        assertThat(d.path("physicalStock").asBoolean()).isFalse();
        assertThat(d.path("ownerClientId").asLong()).isEqualTo(owner);
        assertThat(d.path("ownerClientName").asText()).isEqualTo("소속거래처A");

        // 미지정 시 유형 기본값: CONSIGN→false
        JsonNode consign = data(post("/masters/warehouses", Map.of(
                "code", "MF-WH-C2", "name", "위탁창고2", "type", "CONSIGN", "ownerClientId", owner)));
        assertThat(consign.path("physicalStock").asBoolean()).isFalse();
        // MAIN→true
        JsonNode main = data(post("/masters/warehouses",
                Map.of("code", "MF-WH-M", "name", "물류창고", "type", "MAIN")));
        assertThat(main.path("physicalStock").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★거래처·창고 사용 중지 — 지우지 않고 숨긴다. 재고 남은 창고는 거부")
    void 사용중지() {
        // ‼️컨테이너 DB가 실행 간 공유돼 고정 코드는 두 번째 실행에서 충돌한다.
        String u = "-DC" + (System.nanoTime() % 1_000_000L);

        // ── 거래처: 만료 처리되어 기본 조회에서 빠진다 ──
        Long cid = createId("/masters/clients",
                Map.of("code", "DCC" + u, "name", "중지거래처", "type", "NORMAL"));
        assertThat(del("/masters/clients/" + cid).path("success").asBoolean()).isTrue();
        assertThat(codesOf(data(get("/masters/clients?keyword=DCC" + u)).path("content")))
                .as("기본 조회에서 빠져야").doesNotContain("DCC" + u);
        assertThat(codesOf(data(get("/masters/clients?keyword=DCC" + u + "&includeExpired=true")).path("content")))
                .as("‼️지운 게 아니라 숨긴 것이다 — 과거 매출이 partner_id 로 물려 있다")
                .contains("DCC" + u);

        // ── 창고: 재고가 있으면 막고, 비면 미사용 처리 ──
        Long sup = createId("/masters/clients",
                Map.of("code", "DCS" + u, "name", "인쇄소", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "DCW" + u, "name", "중지창고", "type", "MAIN"));
        Long wh2 = createId("/masters/warehouses",
                Map.of("code", "DCW2" + u, "name", "받는창고", "type", "MAIN"));
        Long book = createId("/masters/products",
                Map.of("code", "DCB" + u, "name", "중지도서", "contentType", "SELF",
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2026-04-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 1000, "qty", 10))));

        JsonNode blocked = del("/masters/warehouses/" + wh);
        assertThat(blocked.path("error").path("code").asText())
                .as("재고 남은 창고는 막아야: %s", blocked).isEqualTo("INVALID_INPUT");

        // 비우면 중지된다
        post("/stock/transfer", Map.of("processedDate", "2026-04-02",
                "fromWarehouseId", wh, "toWarehouseId", wh2,
                "items", List.of(Map.of("productId", book, "qty", 10))));
        assertThat(del("/masters/warehouses/" + wh).path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★위탁창고는 소속 거래처 없이도 만들 수 있다 — 고정 매핑이 아니다")
    void 위탁창고_소속거래처_선택() {
        // ‼️2026-09-11 점검에서 "소속 없는 위탁창고가 만들어진다"를 결함으로 보고 막았다가
        //   발주처 회신으로 되돌렸다 — "같은 거래처라도 건별로 본사물류창고와 위탁창고 중
        //   선택해서 쓰며, 특정 거래처는 무조건 위탁창고 식의 고정 매핑이 아니다".
        //   운영 DSRE2도 같다(거래처 212곳 중 도서재고 보유 42곳).
        //   정산은 이 매핑이 아니라 실제 자동이고 기록에서 창고를 찾으므로 비어 있어도 안전하다.
        JsonNode ok = post("/masters/warehouses",
                Map.of("code", "MF-WH-NOOWN", "name", "소속없는위탁", "type", "CONSIGN"));
        assertThat(ok.path("success").asBoolean())
                .as("소속 없는 위탁창고도 만들 수 있어야: %s", ok).isTrue();

        // 물류창고는 소속이 없는 것이 정상이다
        Long mainId = createId("/masters/warehouses",
                Map.of("code", "MF-WH-M2", "name", "물류창고2", "type", "MAIN"));
        assertThat(mainId).isPositive();

        // ★수정 때 안 보낸 소속은 지우지 않는다.
        //   Warehouse.update가 받은 값을 그대로 덮어써서, 이름만 고치려고 ownerClientId를 빼면
        //   소속이 조용히 날아갔다(도서에서 같은 문제를 부분수정으로 고친 적이 있다).
        Long owner = createId("/masters/clients",
                Map.of("code", "MF-OWN2", "name", "소속거래처B", "type", "NORMAL"));
        Long whId = createId("/masters/warehouses", Map.of(
                "code", "MF-WH-KEEP", "name", "유지위탁", "type", "CONSIGN", "ownerClientId", owner));
        JsonNode renamed = data(put("/masters/warehouses/" + whId,
                Map.of("name", "이름만변경", "type", "CONSIGN")));
        assertThat(renamed.path("name").asText()).isEqualTo("이름만변경");
        assertThat(renamed.path("ownerClientId").asLong())
                .as("소속이 유지되어야: %s", renamed).isEqualTo(owner);
    }

    @Test
    @DisplayName("수불부노출=false 도서는 제품수불부에서 제외")
    void 수불부노출_제외() {
        Long wh = createId("/masters/warehouses",
                Map.of("code", "MF-LWH", "name", "수불부창고", "type", "MAIN"));
        Long visible = createId("/masters/products", Map.of(
                "code", "MF-VIS", "name", "노출도서", "contentType", "SELF", "ledgerVisible", true,
                "salesDivision", "교재"));
        Long hidden = createId("/masters/products", Map.of(
                "code", "MF-HID", "name", "비노출도서", "contentType", "SELF", "ledgerVisible", false,
                "salesDivision", "교재"));
        inbound(wh, visible);
        inbound(wh, hidden);

        JsonNode rows = data(get("/stock/ledger?warehouseId=" + wh)).path("content");
        assertThat(codes(rows)).contains("MF-VIS").doesNotContain("MF-HID");
    }

    @Test
    @DisplayName("재고관리 여부 — 모의고사(false)는 입고 없이 매출 성공·재고이벤트 없음")
    void 재고미관리_매출() {
        Long wh = createId("/masters/warehouses",
                Map.of("code", "MF-MSWH", "name", "모의고사창고", "type", "MAIN"));
        // 재고관리 안 함(모의고사) — 입고 전혀 없음
        Long exam = createId("/masters/products", Map.of(
                "code", "MF-EXAM", "name", "더프모의고사", "contentType", "SELF", "stockManaged", false,
                "salesDivision", "교재"));

        // 입고 0인데도 정상출고 매출 성공(NEGATIVE_STOCK 안 남)
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2026-04-15", "partnerId", ownerFallback(), "warehouseId", wh,
                "items", List.of(Map.of("productId", exam, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 100, "qty", 30))));
        assertThat(r.path("success").asBoolean()).as("재고 미관리 상품 매출 성공: %s", r).isTrue();

        // 재고이벤트 없음 → 제품수불부에 해당 상품 행 없음
        JsonNode rows = data(get("/stock/ledger?warehouseId=" + wh)).path("content");
        assertThat(codes(rows)).doesNotContain("MF-EXAM");

        // 대조: 재고관리 상품(기본 true)은 재고이벤트가 남는다. 입고 없이 출고해도 **막히지 않는다**.
        // ★발주처 확정(2026-08-31 화면7): "입고 전 출고되는 상품은 재고 (−)로 처리되며 …
        //   마이너스로 표시되는 게 정상입니다." 2026-09-11 사내 점검이 이걸 결함으로 보고
        //   차단을 넣었다가 발주처 기준으로 되돌렸다 — 막는 쪽으로 다시 바꾸기 전에 원문을 확인할 것.
        //   막지 않는 대신 응답 warnings[] 로 알린다.
        Long book = createId("/masters/products",
                Map.of("code", "MF-BOOK", "name", "일반교재", "contentType", "SELF",
                "salesDivision", "교재"));
        JsonNode passed = post("/sales/entries", Map.of(
                "salesDate", "2026-04-15", "partnerId", ownerFallback(), "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 100, "qty", 30))));
        assertThat(passed.path("success").asBoolean()).as("입고 없는 출고도 통과한다: %s", passed).isTrue();
        assertThat(passed.path("data").path("items").get(0).path("stockBalance").asInt()).isEqualTo(-30);
        assertThat(passed.path("data").path("warnings"))
                .as("막지 않는 대신 경고는 실려야: %s", passed).isNotEmpty();
    }

    @Test
    @DisplayName("거래처별 단가(34p) — 축은 거래처×대분류. upsert/조회/삭제")
    void 거래처별단가매핑() {
        Long partner = createId("/masters/clients",
                Map.of("code", "PP-CUST", "name", "매핑거래처", "type", "NORMAL"));
        String base = "/masters/partner-supply-rates/" + partner;

        // 같은 거래처라도 대분류마다 다른 공급률(정본 34p "특약점 D모의고사 75%, 교재 60%")
        JsonNode d = data(put(base + "/ETC_EXAM",
                Map.of("supplyRate", 75, "discountAmount", 0, "webVisible", true)));
        assertThat(d.path("supplyRate").asInt()).isEqualTo(75);
        assertThat(d.path("majorCategoryName").asText()).isEqualTo("기타고사");
        assertThat(d.path("partnerName").asText()).isEqualTo("매핑거래처");

        data(put(base + "/TEXTBOOK", Map.of("supplyRate", 60)));
        assertThat(data(get("/masters/partner-supply-rates?partnerId=" + partner))).hasSize(2);

        // upsert(수정) — 중복 생성이 아니다
        data(put(base + "/TEXTBOOK", Map.of("supplyRate", 65, "webVisible", false)));
        JsonNode one = data(get(base + "/TEXTBOOK"));
        assertThat(one.path("supplyRate").asInt()).isEqualTo(65);
        assertThat(one.path("webVisible").asBoolean()).isFalse();
        assertThat(data(get("/masters/partner-supply-rates?partnerId=" + partner))).hasSize(2);

        // 보내지 않은 항목은 건드리지 않는다 — 공급률만 고치려다 Web게시가 켜지면 안 된다
        data(put(base + "/TEXTBOOK", Map.of("supplyRate", 70)));
        assertThat(data(get(base + "/TEXTBOOK")).path("webVisible").asBoolean()).isFalse();

        // 삭제 후 조회 404
        del(base + "/TEXTBOOK");
        assertThat(get(base + "/TEXTBOOK").path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("정산내역서 — 위탁출고→부분정산→내역서(매출금액·미결현황)")
    void 정산내역서() {
        Long main = createId("/masters/warehouses", Map.of("code", "CS-MAIN", "name", "물류창고", "type", "MAIN"));
        Long partner = createId("/masters/clients", Map.of("code", "CS-CUST", "name", "위탁거래처", "type", "NORMAL"));
        Long consign = createId("/masters/warehouses", Map.of("code", "CS-CONS", "name", "위탁창고",
                "type", "CONSIGN", "ownerClientId", partner));   // 위탁창고는 소속 거래처 필수
        Long p = createId("/masters/products",
                Map.of("code", "CS-BK", "name", "위탁도서", "contentType", "SELF", "price", 10000, "supplyRate", 75,
                "salesDivision", "교재"));
        inbound(main, p);   // 물류창고 재고 100

        // 위탁출고 100 (물류→위탁)
        JsonNode outResp = post("/consignment/out", Map.of(
                "processedDate", "2026-06-01", "partnerId", partner,
                "fromWarehouseId", main, "toWarehouseId", consign,
                "items", List.of(Map.of("productId", p, "qty", 100))));
        assertThat(outResp.path("success").asBoolean()).as("위탁출고: %s", outResp).isTrue();
        long coId = data(outResp).path("items").get(0).path("consignmentOutId").asLong();

        // 부분정산 30 (정가 10000, 공급률 70) → 매출 210,000
        // 세액은 자동산출되지 않으므로(발주처 확정) 명시 입력한다: 10000×70%×30 = 210,000 → 세액 21,000
        JsonNode settle = post("/consignment/settle", Map.of(
                "salesDate", "2026-10-15", "settlements",
                List.of(Map.of("consignmentOutId", coId, "settleQty", 30,
                        "unitPrice", 10000, "supplyRate", 70, "tax", 21_000))));
        assertThat(settle.path("success").asBoolean()).as("정산: %s", settle).isTrue();

        // 정산내역서 — partnerId로 이 테스트의 거래처만 조회(공유 DB라 전역 조회하면 합계가 오염된다)
        JsonNode d = data(get("/consignment/settlement-statement"
                + "?fromDate=2020-01-01&toDate=2030-12-31&partnerId=" + partner));
        JsonNode row = rowByField(d.path("rows"), "partnerName", "위탁거래처");
        assertThat(row.path("settleQty").asLong()).isEqualTo(30);
        assertThat(row.path("supplyAmount").asLong()).isEqualTo(210_000);   // 10000×70%×30
        assertThat(row.path("tax").asLong()).isEqualTo(21_000);
        assertThat(row.path("totalQty").asLong()).isEqualTo(100);
        assertThat(row.path("remainingQty").asLong()).isEqualTo(70);
        assertThat(row.path("status").asText()).isEqualTo("PARTIAL");
        JsonNode sum = d.path("summary");
        assertThat(sum.path("totalSettleQty").asLong()).isEqualTo(30);
        assertThat(sum.path("totalSupply").asLong()).isEqualTo(210_000);
    }

    @Test
    @DisplayName("위탁정산 매출 취소 → 미결원장 복원(감사 결함 수정)")
    void 위탁정산취소_미결복원() {
        Long main = createId("/masters/warehouses", Map.of("code", "CX-MAIN", "name", "물류", "type", "MAIN"));
        Long partner = createId("/masters/clients", Map.of("code", "CX-CUST", "name", "취소거래처", "type", "NORMAL"));
        Long consign = createId("/masters/warehouses", Map.of("code", "CX-CONS", "name", "위탁",
                "type", "CONSIGN", "ownerClientId", partner));   // 위탁창고는 소속 거래처 필수
        Long p = createId("/masters/products", Map.of("code", "CX-BK", "name", "취소도서", "contentType", "SELF", "price", 10000, "supplyRate", 75,
                "salesDivision", "교재"));
        inbound(main, p);

        Long coId = data(post("/consignment/out", Map.of(
                "processedDate", "2026-06-01", "partnerId", partner, "fromWarehouseId", main,
                "toWarehouseId", consign, "items", List.of(Map.of("productId", p, "qty", 100)))))
                .path("items").get(0).path("consignmentOutId").asLong();

        // 정산 40 (11월로 격리)
        post("/consignment/settle", Map.of("salesDate", "2026-11-15", "settlements",
                List.of(Map.of("consignmentOutId", coId, "settleQty", 40, "unitPrice", 10000, "supplyRate", 70))));

        // 정산 후: settled 40, remaining 60
        JsonNode before = coRow(partner, coId);
        assertThat(before.path("settledQty").asInt()).isEqualTo(40);
        assertThat(before.path("remainingQty").asInt()).isEqualTo(60);

        // 그 위탁매출 취소
        long saleId = data(get("/sales?startDate=2026-11-01&endDate=2026-11-30&partnerId=" + partner))
                .path("content").get(0).path("id").asLong();
        JsonNode cancel = post("/sales/" + saleId + "/cancel", Map.of());
        assertThat(cancel.path("success").asBoolean()).as("취소: %s", cancel).isTrue();

        // 취소 후: 미결 복원 settled 0, remaining 100, OPEN (좌초 안 됨)
        JsonNode after = coRow(partner, coId);
        assertThat(after.path("settledQty").asInt()).isEqualTo(0);
        assertThat(after.path("remainingQty").asInt()).isEqualTo(100);
        assertThat(after.path("status").asText()).isEqualTo("OPEN");
    }

    /** 거래처 미결목록에서 특정 co 행. */
    private JsonNode coRow(Long partnerId, long coId) {
        JsonNode rows = data(get("/consignment/pending?partnerId=" + partnerId)).path("items");
        for (JsonNode r : rows) {
            if (r.path("consignmentOutId").asLong() == coId) {
                return r;
            }
        }
        throw new AssertionError("co " + coId + " 없음: " + rows);
    }

    @Test
    @DisplayName("위탁 반품 — 역-자동이고(위탁→물류 재고복귀) + 미결원장 축소, 초과분은 Case1")
    void 위탁반품() {
        Long main = createId("/masters/warehouses", Map.of("code", "CR-MAIN", "name", "물류", "type", "MAIN"));
        Long partner = createId("/masters/clients", Map.of("code", "CR-CUST", "name", "위탁반품거래처", "type", "NORMAL"));
        Long consign = createId("/masters/warehouses", Map.of("code", "CR-CONS", "name", "위탁",
                "type", "CONSIGN", "ownerClientId", partner));   // 위탁창고는 소속 거래처 필수
        // ★정가·공급률이 있어야 위탁출고가 등록된다 — 나갈 때 조건이 정해져야 정산할 수 있다.
        //   예전엔 둘 다 비어도 출고가 됐고, 그 미결은 정산 때 가서야 막혔다(이미 물건이 나간 뒤다).
        Long p = createId("/masters/products", Map.of("code", "CR-BK", "name", "위탁반품도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75,
                "salesDivision", "교재"));
        inbound(main, p);   // 물류 100

        // 위탁출고 100 → 물류 0, 위탁 100
        JsonNode out = data(post("/consignment/out", Map.of(
                "processedDate", "2026-06-01", "partnerId", partner,
                "fromWarehouseId", main, "toWarehouseId", consign,
                "items", List.of(Map.of("productId", p, "qty", 100)))));
        long coId = out.path("items").get(0).path("consignmentOutId").asLong();

        // 위탁 반품 30 (미판매분) → 위탁 100−30=70, 물류 0+30=30, 미결 잔여 100→70
        JsonNode ret = data(post("/consignment/return", Map.of(
                "processedDate", "2026-06-30",
                "items", List.of(Map.of("consignmentOutId", coId, "returnQty", 30)))));
        JsonNode line = ret.path("items").get(0);
        assertThat(line.path("returnQty").asInt()).isEqualTo(30);
        assertThat(line.path("remainingQty").asInt()).isEqualTo(70);
        assertThat(line.path("status").asText()).isEqualTo("OPEN");
        assertThat(line.path("mainBalance").asInt()).isEqualTo(30);      // 물류 복귀
        assertThat(line.path("consignBalance").asInt()).isEqualTo(70);   // 위탁 차감

        // ★초과 반품을 더 이상 막지 않는다(발주처 2026-08-31).
        //   잔여 70 상태에서 80을 반품하면 70은 Case2, 초과분 10은 Case1(확정매출분 반품)로 갈린다.
        //   ※단 **나간 것보다 많이는** 못 돌려받는다. 이 미결은 정산 이력이 없어
        //     돌려받을 수 있는 최대가 잔여 70이다 → 80은 "출고수량 초과"로 거부된다.
        //     "미결잔여 초과 차단"(제거됨)과 "출고수량 초과"(유지)는 다른 규칙이다.
        JsonNode over = post("/consignment/return", Map.of(
                "processedDate", "2026-06-30",
                "items", List.of(Map.of("consignmentOutId", coId, "returnQty", 80))));
        assertThat(over.path("error").path("code").asText())
                .as("출고수량 초과로 거부되어야: %s", over)
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("위탁정산 동시성 — 동시 정산 2건이 모두 반영된다(lost update 없음, 이슈#97)")
    void 위탁정산_동시성() throws Exception {
        Long main = createId("/masters/warehouses", Map.of("code", "CC-MAIN", "name", "물류", "type", "MAIN"));
        Long partner = createId("/masters/clients", Map.of("code", "CC-CUST", "name", "위탁동시성거래처", "type", "NORMAL"));
        Long consign = createId("/masters/warehouses", Map.of("code", "CC-CONS", "name", "위탁",
                "type", "CONSIGN", "ownerClientId", partner));   // 위탁창고는 소속 거래처 필수
        Long p = createId("/masters/products",
                Map.of("code", "CC-BK", "name", "위탁동시성도서", "contentType", "SELF", "price", 10000, "supplyRate", 75,
                "salesDivision", "교재"));
        inbound(main, p);   // 물류 100

        // 위탁출고 100 → 미결 잔여 100
        JsonNode out = data(post("/consignment/out", Map.of(
                "processedDate", "2026-06-01", "partnerId", partner,
                "fromWarehouseId", main, "toWarehouseId", consign,
                "items", List.of(Map.of("productId", p, "qty", 100)))));
        long coId = out.path("items").get(0).path("consignmentOutId").asLong();

        // 동시에 각각 100 정산 시도(합계 200 > 잔여 100). 락 없으면 둘 다 통과(lost update).
        Object body = Map.of("salesDate", "2026-10-15", "settlements",
                List.of(Map.of("consignmentOutId", coId, "settleQty", 100, "unitPrice", 10000, "supplyRate", 70)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<JsonNode> f1 = pool.submit(() -> { gate.await(); return post("/consignment/settle", body); });
            Future<JsonNode> f2 = pool.submit(() -> { gate.await(); return post("/consignment/settle", body); });
            gate.countDown();   // 동시 출발
            JsonNode r1 = f1.get();
            JsonNode r2 = f2.get();

            // ★초과정산 차단을 제거했으므로(발주처 2026-08-31) **둘 다 성공한다.**
            //   이 테스트가 지키는 것은 이제 "차단"이 아니라 **lost update가 없다**는 것이다 —
            //   비관적 락이 두 정산을 직렬화하므로 둘이 서로를 덮어쓰지 않는다.
            int success = (r1.path("success").asBoolean() ? 1 : 0) + (r2.path("success").asBoolean() ? 1 : 0);
            assertThat(success).as("동시 정산 결과 r1=%s r2=%s", r1, r2).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }

        // 장부 정합: 두 건이 모두 쌓여 정산 200. 잔여는 −100으로 **음수 그대로 보인다** —
        // 초과했다는 사실이 화면에 드러나야 담당자가 수기로 정리한다(발주처 요구).
        JsonNode d = data(get("/consignment/settlement-statement?fromDate=2020-01-01&toDate=2030-12-31"));
        JsonNode row = rowByField(d.path("rows"), "partnerName", "위탁동시성거래처");
        assertThat(row.path("settledQtyCum").asLong())
                .as("두 건이 모두 누적돼야(lost update 없음): %s", row).isEqualTo(200);
        assertThat(row.path("remainingQty").asLong()).as("초과분이 음수로 드러난다").isEqualTo(-100);
        assertThat(row.path("totalQty").asLong()).isEqualTo(100);

        // 정리: 남긴 정산 매출 취소(정산내역서 전역 summary 오염 방지 — settled_at 기준이라 날짜격리 불가)
        long saleId = data(get("/sales?startDate=2026-10-01&endDate=2026-10-31&partnerId=" + partner))
                .path("content").get(0).path("id").asLong();
        post("/sales/" + saleId + "/cancel", Map.of());
    }

    @Test
    @DisplayName("거래처명 분리 — 도시명/거래처명1(상호)/거래처명2(풀네임) 등록·조회·수정")
    void 거래처명분리() {
        // 등록: 도시명 진주 / 거래처명1 이룸도서 / 거래처명2(풀네임) 진주 이룸도서
        long id = createId("/masters/clients", Map.of(
                "code", "NM-CUST", "name", "진주 이룸도서",
                "cityName", "진주", "name1", "이룸도서", "type", "NORMAL"));

        JsonNode one = data(get("/masters/clients/" + id));
        assertThat(one.path("name").asText()).isEqualTo("진주 이룸도서");   // 거래처명2(풀네임)
        assertThat(one.path("cityName").asText()).isEqualTo("진주");        // 도시명
        assertThat(one.path("name1").asText()).isEqualTo("이룸도서");        // 거래처명1(상호)

        // 수정: 상호 변경
        JsonNode upd = data(put("/masters/clients/" + id, Map.of(
                "name", "진주 이룸북스", "cityName", "진주", "name1", "이룸북스", "type", "NORMAL")));
        assertThat(upd.path("name1").asText()).isEqualTo("이룸북스");
        assertThat(upd.path("cityName").asText()).isEqualTo("진주");
    }

    @Test
    @DisplayName("교재식 반품 — 도서 단위 반품가능 조회 + 초과 시 경고(차단 아님) + 공급률 수정 허용")
    void 반품교재식_범위검증() {
        Long wh = createId("/masters/warehouses", Map.of("code", "RB-WH", "name", "반품창고", "type", "MAIN"));
        Long p = createId("/masters/products",
                Map.of("code", "RB-BK", "name", "교재반품도서", "contentType", "SELF", "price", 10000,
                "salesDivision", "교재"));
        Long partner = createId("/masters/clients", Map.of("code", "RB-CUST", "name", "교재반품거래처", "type", "NORMAL"));
        inbound(wh, p);   // 물류 100

        // 정상출고(판매) 30 @공급률70 → 반품가능 30
        post("/sales/entries", Map.of(
                "salesDate", "2026-08-03", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", p, "shipmentType", "NORMAL_SHIP",
                        "qty", 30, "unitPrice", 10000, "supplyRate", 70))));

        // 반품가능 조회 → 도서×정가×공급률 라인, 반품가능 30
        JsonNode able = data(get("/sales/returnable?partnerId=" + partner)).path("rows");
        JsonNode line = rowByField(able, "productCode", "RB-BK");
        assertThat(line.path("saleQty").asLong()).isEqualTo(30);
        assertThat(line.path("returnedQty").asLong()).isEqualTo(0);
        assertThat(line.path("returnableQty").asLong()).isEqualTo(30);
        assertThat(line.path("supplyRate").asInt()).isEqualTo(70);

        // 범위 내(20) → 성공, 경고 없음, 이후 반품가능 30−20=10
        JsonNode ok = post("/sales/return-inbound", Map.of(
                "returnDate", "2026-08-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", p, "unitPrice", 10000, "supplyRate", 70, "qty", 20))));
        assertThat(ok.path("success").asBoolean()).as("정상 반품: %s", ok).isTrue();
        assertThat(ok.path("data").path("warnings"))
                .as("★범위 안이면 경고가 없어야 한다 — 아무 때나 뜨면 담당자가 무시하게 된다").isEmpty();
        JsonNode after = rowByField(data(get("/sales/returnable?partnerId=" + partner)).path("rows"),
                "productCode", "RB-BK");
        assertThat(after.path("returnedQty").asLong()).isEqualTo(20);
        assertThat(after.path("returnableQty").asLong()).isEqualTo(10);

        // ★공급률을 바꿔도 반품 가능해야 한다(발주처 확정 2026-08-05 §2.2 — 표시는 원 출고건 값,
        //   담당자가 필요 시 수정 가능. 고정·잠금 아님). 범위 판정은 도서 단위 잔여로만 한다.
        JsonNode otherRate = post("/sales/return-inbound", Map.of(
                "returnDate", "2026-08-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", p, "unitPrice", 10000, "supplyRate", 50, "qty", 5))));
        assertThat(otherRate.path("success").asBoolean())
                .as("공급률을 수정해도 반품이 되어야 함: %s", otherRate).isTrue();

        // 도서 단위 잔여에서 차감된다: 30 − 20 − 5 = 5
        JsonNode after2 = rowByField(data(get("/sales/returnable?partnerId=" + partner)).path("rows"),
                "productCode", "RB-BK");
        assertThat(after2.path("returnableQty").asLong()).isEqualTo(5);

        // ★잔여(5)를 넘겨도 **막지 않는다** — 경고로 알린다(발주처 화면검토 2026-08-31 화면28:
        //   "출고내역보다 반품 등록 내역이 더 많이 입력되는 경우 경고 알림(alert)을 넣어주시기 바랍니다").
        //   현장에서는 컷오버 전 출고분이 반품으로 들어온다 — 막으면 실제로 들어온 물건을 못 적는다.
        JsonNode over = post("/sales/return-inbound", Map.of(
                "returnDate", "2026-08-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", p, "unitPrice", 10000, "supplyRate", 55, "qty", 6))));
        assertThat(over.path("success").asBoolean()).as("초과여도 등록된다: %s", over).isTrue();

        JsonNode warns = over.path("data").path("warnings");
        assertThat(warns).as("★초과분을 숨기지 않는다 — 조용히 통과시키면 담당자가 모른다").hasSize(1);
        assertThat(warns.get(0).path("code").asText()).isEqualTo("RETURN_EXCEEDS");
        assertThat(warns.get(0).path("returnableQty").asLong()).as("잔여 5").isEqualTo(5);
        assertThat(warns.get(0).path("requestedQty").asInt()).isEqualTo(6);
        assertThat(warns.get(0).path("exceededQty").asLong()).as("6 − 5").isEqualTo(1);
        assertThat(warns.get(0).path("message").asText()).contains("RB-BK");

        // 장부에는 실제로 들어간다: 30 − 20 − 5 − 6 = −1
        JsonNode after3 = rowByField(data(get("/sales/returnable?partnerId=" + partner)).path("rows"),
                "productCode", "RB-BK");
        assertThat(after3.path("returnableQty").asLong())
                .as("음수로 보여야 고칠 수 있다 — 0으로 깎으면 초과분이 사라진다").isEqualTo(-1);
    }

    @Test
    @DisplayName("통합 매출 조회 12p 컬럼 노출 — 거래처명분리·지역·거래처구분·학교·학년·분류·회차")
    void 매출조회_거래처명분리노출() {
        Long wh = createId("/masters/warehouses", Map.of("code", "NV-WH", "name", "노출창고", "type", "MAIN"));
        // 도서: 분류(대분류)·학년 세팅
        Long p = createId("/masters/products", Map.of(
                "code", "NV-BK", "name", "노출도서", "contentType", "SELF", "price", 10000,
                "grade", "고2", "catCode", "S2026A02", "catName", "국어모의고사",
                "salesDivision", "교재"));
        Long partner = createId("/masters/clients", Map.of(
                "code", "NV-CUST", "name", "진주 노출도서", "cityName", "진주", "name1", "노출도서",
                "region", "경남", "clientCategory", "특약점", "type", "NORMAL"));
        inbound(wh, p);
        post("/sales/entries", Map.of(
                "salesDate", "2026-05-12", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", p, "shipmentType", "NORMAL_SHIP",
                        "qty", 5, "unitPrice", 10000, "supplyRate", 70,
                        "schoolCode", "SCHOOL-1", "schoolName", "진주고", "round", 4))));

        JsonNode content = data(get("/sales?startDate=2026-05-12&endDate=2026-05-12&partnerId=" + partner))
                .path("content");
        JsonNode row = content.get(0);
        // 거래처 축
        assertThat(row.path("partnerCode").asText()).isEqualTo("NV-CUST");
        assertThat(row.path("partnerName").asText()).isEqualTo("진주 노출도서");   // 거래처명2
        assertThat(row.path("partnerCityName").asText()).isEqualTo("진주");        // 도시명
        assertThat(row.path("partnerName1").asText()).isEqualTo("노출도서");       // 거래처명1
        assertThat(row.path("region").asText()).isEqualTo("경남");                 // 지역
        assertThat(row.path("clientCategory").asText()).isEqualTo("특약점");        // 거래처구분
        // 학교·학년·분류·회차 축
        assertThat(row.path("schoolCode").asText()).isEqualTo("SCHOOL-1");
        assertThat(row.path("schoolName").asText()).isEqualTo("진주고");
        assertThat(row.path("grade").asText()).isEqualTo("2");                   // 학년=상품 속성
        assertThat(row.path("catCode").asText()).isEqualTo("S2026A02");
        assertThat(row.path("catName").asText()).isEqualTo("국어모의고사");
        assertThat(row.path("bookRound").asInt()).isEqualTo(4);                    // 회차
    }

    @Test
    @DisplayName("매출 엑셀 업로드 — 표준양식 파싱→일괄등록(공급률 0.75→75, 위탁출고 거부)")
    void 매출엑셀업로드() throws Exception {
        // ★발주처 표준양식은 분류코드·도서코드를 **따로** 받는다 —
        //   "⚠분류코드마다 01부터 재사용되므로 분류코드 없이는 특정 불가".
        //   예전에는 두 값을 이어붙여 상품코드(UP01)와 대조했는데, 그러면 실제 마스터
        //   (도서코드가 전역 고유)로는 어떤 값도 맞지 않아 업로드가 100% 실패했다(2026-09-11 발견).
        createId("/masters/products", Map.of("code", "01", "name", "업로드도서",
                "contentType", "SELF", "price", 10000,
                "catCode", "U2026A", "catName", "업로드분류",
                "salesDivision", "교재"));
        createId("/masters/clients", Map.of("code", "UPCUST", "name", "업로드거래처", "type", "NORMAL"));

        JsonNode r = uploadXlsx(buildUploadXlsx());
        assertThat(r.path("success").asBoolean()).as("업로드: %s", r).isTrue();
        JsonNode d = data(r);
        assertThat(d.path("imported").asInt()).isEqualTo(1);   // 정상출고 1
        assertThat(d.path("failed").asInt()).isEqualTo(1);     // 위탁출고 1 거부
        JsonNode ok = rowByField(d.path("lines"), "result", "IMPORTED");
        assertThat(ok.path("supplyAmount").asLong()).isEqualTo(75_000);   // 10000×75%×10 (0.75→75)

        // 매출 원장 등록 확인(9월 격리)
        JsonNode all = data(get("/sales?startDate=2026-09-05&endDate=2026-09-05")).path("content");
        assertThat(all).isNotEmpty();

        // ★업로드도 재고를 뺀다. 예전엔 이게 빠져 있어 올린 만큼 매출은 서는데 재고는 그대로였고,
        //   제품수불부와 순매출 조회의 수량이 딱 업로드분만큼 어긋났다(2026-09-11 점검).
        //   양식에 창고 칸이 없어 물류창고에서 나간다.
        long uploaded = 0;
        for (JsonNode row : data(get("/stock/ledger?fromDate=2026-09-01&toDate=2026-09-30"
                + "&keyword=01&size=50")).path("content")) {
            if ("01".equals(row.path("productCode").asText())) {
                uploaded += row.path("sale").asLong();
            }
        }
        assertThat(uploaded).as("업로드 10부가 재고에서 빠져야 한다").isEqualTo(-10);
    }

    private byte[] buildUploadXlsx() throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var sh = wb.createSheet("업로드양식");
            sh.createRow(0);   // 헤더(파서가 스킵)
            var d = sh.createRow(1);   // 정상출고
            d.createCell(0).setCellValue("2026-09-05");
            d.createCell(1).setCellValue("UPCUST");
            d.createCell(2).setCellValue("10002");
            d.createCell(3).setCellValue("U2026A");
            d.createCell(4).setCellValue("01");
            d.createCell(5).setCellValue(0);
            d.createCell(6).setCellValue(10000);
            d.createCell(7).setCellValue(0.75);   // 소수 → 75로 정규화
            d.createCell(8).setCellValue(10);
            d.createCell(9).setCellValue(75000);
            d.createCell(10).setCellValue("정상출고");
            d.createCell(11).setCellValue("업로드테스트");
            var d2 = sh.createRow(2);   // 위탁출고 → 거부
            d2.createCell(0).setCellValue("2026-09-05");
            d2.createCell(1).setCellValue("UPCUST");
            d2.createCell(3).setCellValue("UP");
            d2.createCell(4).setCellValue("01");
            d2.createCell(8).setCellValue(5);
            d2.createCell(10).setCellValue("위탁출고");
            var bos = new java.io.ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private JsonNode uploadXlsx(byte[] bytes) throws Exception {
        var res = new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override public String getFilename() { return "upload.xlsx"; }
        };
        org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        body.add("file", res);
        var h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(token());
        var resp = rest.exchange("/api/v1/sales/upload", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body, h), String.class);
        return om.readTree(resp.getBody());
    }

    @Test
    @DisplayName("마스터 엑셀 다운로드 — 거래처목록 xlsx(한글 헤더)")
    void 마스터엑셀() throws Exception {
        createId("/masters/clients", Map.of("code", "XL-CUST", "name", "엑셀거래처", "type", "NORMAL"));
        var resp = getBytes("/masters/clients/export");
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        byte[] xlsx = resp.getBody();
        assertThat(new String(xlsx, 0, 2)).isEqualTo("PK");
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            var sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("거래처목록");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("거래처코드");
            assertThat(sheet.getLastRowNum()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("담보 만기 알림 — 임박/만료 포함, 먼 만기는 제외")
    void 담보만기알림() {
        // 기준일 2026-06-01 고정. 임박(20일후)·만료(5일전)·먼미래(200일후)
        Long imminent = collateralPartner("COL-IMM", "임박거래처", "2026-06-21");
        Long expired = collateralPartner("COL-EXP", "만료거래처", "2026-05-27");
        collateralPartner("COL-FAR", "여유거래처", "2026-12-01");

        JsonNode rows = data(get("/masters/clients/collateral-expiry?asOf=2026-06-01&withinDays=30")).path("rows");
        JsonNode imm = rowByCode(rows, "COL-IMM");
        assertThat(imm.path("status").asText()).isEqualTo("IMMINENT");
        assertThat(imm.path("daysUntilExpiry").asLong()).isEqualTo(20);
        JsonNode exp = rowByCode(rows, "COL-EXP");
        assertThat(exp.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(exp.path("daysUntilExpiry").asLong()).isEqualTo(-5);
        // 200일 후 만기는 30일 창에서 제외
        assertThat(codesOf(rows)).doesNotContain("COL-FAR");
    }

    private Long collateralPartner(String code, String name, String expiry) {
        Long id = createId("/masters/clients", Map.of("code", code, "name", name, "type", "NORMAL"));
        put("/masters/clients/" + id, Map.of(
                "name", name, "type", "NORMAL", "assureAmount", 50_000_000, "assureExpiry", expiry));
        return id;
    }

    private JsonNode rowByField(JsonNode rows, String field, String value) {
        for (JsonNode r : rows) {
            if (value.equals(r.path(field).asText())) {
                return r;
            }
        }
        throw new AssertionError(field + "=" + value + " 행 없음: " + rows);
    }

    private JsonNode rowByCode(JsonNode rows, String code) {
        for (JsonNode r : rows) {
            if (code.equals(r.path("code").asText())) {
                return r;
            }
        }
        throw new AssertionError("code=" + code + " 행 없음: " + rows);
    }

    private java.util.List<String> codesOf(JsonNode rows) {
        java.util.List<String> out = new java.util.ArrayList<>();
        rows.forEach(r -> out.add(r.path("code").asText()));
        return out;
    }

    @Test
    @DisplayName("매출등록 — 공급률 미입력 시 거래처별 단가 자동적용, 매핑 없으면 오류")
    void 단가자동적용() {
        Long wh = createId("/masters/warehouses", Map.of("code", "AP-WH", "name", "자동단가창고", "type", "MAIN"));
        // 매핑은 거래처×대분류다 → 상품이 대분류를 갖도록 세부구분을 준다(교재→TEXTBOOK)
        Long p = createId("/masters/products",
                Map.of("code", "AP-BK", "name", "자동단가도서", "contentType", "SELF", "price", 10000,
                        "salesDivision", "교재"));
        Long partner = createId("/masters/clients", Map.of("code", "AP-CUST", "name", "자동단가거래처", "type", "NORMAL"));
        put("/masters/partner-supply-rates/" + partner + "/TEXTBOOK", Map.of("supplyRate", 70));
        inbound(wh, p);

        // 정가·공급률 미입력 → 매핑(70%)·정가(10000) 자동적용: 10000×70%×10 = 70,000
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", "2026-04-20", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", p, "shipmentType", "NORMAL_SHIP", "qty", 10))));
        assertThat(r.path("success").asBoolean()).as("자동단가 매출등록: %s", r).isTrue();
        assertThat(data(r).path("items").get(0).path("supplyAmount").asLong()).isEqualTo(70_000);

        // 매핑 없는 상품 + 공급률 미입력 → 오류
        // ‼️세부구분은 필수가 됐지만(G-3) **매핑이 걸린 대분류를 주면 안 된다** —
        //   위에서 거래처×TEXTBOOK 에 70%를 매핑해 두었으므로 '교재'를 주면 자동적용에 성공해
        //   이 테스트가 보려던 "매핑 없음 → 오류"가 사라진다. 다른 대분류를 쓴다.
        Long p2 = createId("/masters/products",
                Map.of("code", "AP-NOBK", "name", "매핑없음도서", "contentType", "SELF", "price", 10000,
                "salesDivision", "모의고사"));
        inbound(wh, p2);
        JsonNode fail = post("/sales/entries", Map.of(
                "salesDate", "2026-04-20", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", p2, "shipmentType", "NORMAL_SHIP", "qty", 5))));
        assertThat(fail.path("success").asBoolean()).isFalse();
    }

    private void inbound(Long whId, Long productId) {
        post("/stock/inbound", Map.of(
                "processedDate", "2026-06-01", "supplierClientId", ownerFallback(), "destinationWarehouseId", whId,
                "items", List.of(Map.of("productId", productId, "unitCost", 3000, "qty", 100))));
    }

    private Long ownerFallback = null;

    private Long ownerFallback() {
        if (ownerFallback == null) {
            ownerFallback = createId("/masters/clients",
                    Map.of("code", "MF-SUP", "name", "입고처", "type", "NORMAL"));
        }
        return ownerFallback;
    }

    private java.util.List<String> codes(JsonNode rows) {
        java.util.List<String> out = new java.util.ArrayList<>();
        rows.forEach(r -> out.add(r.path("productCode").asText()));
        return out;
    }

    @Test
    @DisplayName("분류코드 형식 검증 — 연도 없는 코드는 거부, 6자·8자 실제 형식은 통과")
    void 분류코드_형식검증() {
        // 레거시 실데이터 4,600건이 모두 따르는 형식: [영문1자][연도4자][영문·숫자1~3자]
        // 법인마다 뒤 자릿수가 다르다 — 교재 6자(A2026A), 연구소 8자(M2026A01). 둘 다 허용해야 한다.
        assertThat(post("/masters/products", Map.of(
                "code", "CC-OK6", "name", "6자형식", "contentType", "SELF",
                "catCode", "A2026A",
                "salesDivision", "교재")).path("success").asBoolean())
                .as("교재 법인 6자 형식은 통과해야 함").isTrue();
        assertThat(post("/masters/products", Map.of(
                "code", "CC-OK8", "name", "8자형식", "contentType", "SELF",
                "catCode", "M2026A01",
                "salesDivision", "교재")).path("success").asBoolean())
                .as("연구소 8자 형식은 통과해야 함").isTrue();

        // 연도가 빠진 옛 표기(A01)는 리포트 집계축을 깨뜨린다 → 거부
        JsonNode noYear = post("/masters/products", Map.of(
                "code", "CC-BAD1", "name", "연도없음", "contentType", "SELF", "catCode", "A01",
                "salesDivision", "교재"));
        assertThat(noYear.path("success").asBoolean()).as("연도 없는 코드: %s", noYear).isFalse();
        assertThat(noYear.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");

        // 소문자·특수문자·자릿수 초과도 거부
        for (String bad : new String[] {"a2026A01", "A2026A0123", "A2026-01", "2026A01"}) {
            assertThat(post("/masters/products", Map.of(
                    "code", "CC-BAD-" + bad.hashCode(), "name", "불량", "contentType", "SELF",
                    "catCode", bad,
                "salesDivision", "교재")).path("success").asBoolean())
                    .as("거부돼야 할 코드: %s", bad).isFalse();
        }

        // 미입력은 허용 — 분류를 나중에 지정하는 상품이 있다
        assertThat(post("/masters/products", Map.of(
                "code", "CC-NULL", "name", "분류미지정", "contentType", "SELF",
                "salesDivision", "교재")).path("success").asBoolean())
                .as("분류코드 미입력은 허용").isTrue();
    }

    @Test
    @DisplayName("거래처관리(30p) — 연락처·거래기간 필드 왕복 + '만료된 거래처 포함' 필터")
    void 거래처_연락처_만료필터() {
        // 레거시 거래처관리.vb 조회 컬럼 전수 대응 필드
        Map<String, Object> body = new java.util.HashMap<>();   // Map.of는 10쌍 제한
        body.put("code", "PC-A");
        body.put("name", "연락처거래처");
        body.put("type", "NORMAL");
        body.put("bossId", "800101-1234567");
        body.put("tel1", "02-123-4567");
        body.put("tel2", "02-123-4568");
        body.put("cellPhone", "010-1234-5678");
        body.put("fax", "02-123-4569");
        body.put("zip", "13588");
        body.put("zone2", "경남권");
        body.put("startDate", "2026-01-01");
        long id = createId("/masters/clients", body);

        JsonNode d = data(get("/masters/clients/" + id));
        // 사업자주민번호는 마스킹되어 나간다(DB-73) — 입력한 원본이 그대로 돌아오지 않는 게 정상.
        assertThat(d.path("bossId").asText()).isEqualTo("800101-1******");
        assertThat(d.path("tel1").asText()).isEqualTo("02-123-4567");
        assertThat(d.path("cellPhone").asText()).isEqualTo("010-1234-5678");
        assertThat(d.path("fax").asText()).isEqualTo("02-123-4569");
        assertThat(d.path("zip").asText()).isEqualTo("13588");
        assertThat(d.path("zone2").asText()).isEqualTo("경남권");
        assertThat(d.path("expired").asBoolean()).as("만료일 없으면 거래중").isFalse();

        // 만료 거래처 등록 — 기본 조회에서는 빠지고, 포함 옵션에서만 보인다
        createId("/masters/clients", Map.of(
                "code", "PC-END", "name", "만료거래처", "type", "NORMAL", "endDate", "2026-06-30"));

        assertThat(data(get("/masters/clients?keyword=PC-&size=100")).path("content")
                .findValuesAsText("code"))
                .as("기본 조회는 거래중만").contains("PC-A").doesNotContain("PC-END");

        assertThat(data(get("/masters/clients?keyword=PC-&includeExpired=true&size=100")).path("content")
                .findValuesAsText("code"))
                .as("포함 옵션이면 만료분도 노출").contains("PC-A", "PC-END");
    }

    @Test
    @DisplayName("세액 — 자동산출하지 않는다(미입력=0, 입력값 그대로, 면세 상품엔 입력 거부)")
    void 세액_수동입력() {
        Long wh = createId("/masters/warehouses", Map.of("code", "TX-WH", "name", "세액창고", "type", "MAIN"));
        Long partner = createId("/masters/clients", Map.of("code", "TX-CUST", "name", "세액거래처", "type", "NORMAL"));
        // 과세 상품(taxFree=false)
        Long taxable = createId("/masters/products", Map.of(
                "code", "TX-TAXABLE", "name", "과세도서", "contentType", "SELF", "price", 10000, "taxFree", false,
                "salesDivision", "교재"));
        Long free = createId("/masters/products", Map.of(
                "code", "TX-FREE", "name", "면세도서", "contentType", "SELF", "price", 10000, "taxFree", true,
                "salesDivision", "교재"));
        inbound(wh, taxable);
        inbound(wh, free);

        // ① 과세 상품인데 세액 미입력 → 0 (예전엔 공급가액의 10%가 자동으로 붙었다)
        JsonNode noTax = data(post("/sales/entries", Map.of(
                "salesDate", "2026-12-01", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", taxable, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 100, "qty", 10)))));
        JsonNode line = noTax.path("items").get(0);
        assertThat(line.path("supplyAmount").asLong()).isEqualTo(100_000);
        assertThat(line.path("tax").asLong()).as("자동산출 없음 → 0").isZero();
        assertThat(line.path("totalAmount").asLong()).as("총금액=금액+세액").isEqualTo(100_000);

        // ② 담당자가 입력하면 그 값이 그대로 반영된다
        JsonNode withTax = data(post("/sales/entries", Map.of(
                "salesDate", "2026-12-02", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", taxable, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 100, "qty", 10, "tax", 7_777)))));
        JsonNode line2 = withTax.path("items").get(0);
        assertThat(line2.path("tax").asLong()).as("입력값 그대로").isEqualTo(7_777);
        assertThat(line2.path("totalAmount").asLong()).isEqualTo(107_777);

        // ③ 면세 상품에 세액을 넣으면 거부 — 조용히 무시하면 담당자가 값이 사라진 걸 모른다
        JsonNode bad = post("/sales/entries", Map.of(
                "salesDate", "2026-12-03", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", free, "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 100, "qty", 5, "tax", 5_000))));
        assertThat(bad.path("success").asBoolean()).as("면세+세액: %s", bad).isFalse();
        assertThat(bad.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("도서 기본 공급률 — 거래처 매핑이 없으면 이 값이 쓰인다(입력 > 매핑 > 도서 기본)")
    void 도서_기본공급률_우선순위() {
        // ★다른 테스트와 겹치지 않는 달(11월)을 쓴다 — 6~7월은 매출액명세서·수익신고 등
        //   기간 집계 검증이 쓰고 있어, 여기서 매출을 만들면 그쪽 기대값이 깨진다.
        String sfx = "-SR" + (System.nanoTime() % 1_000_000L);
        Long sup = createId("/masters/clients", Map.of("code", "SRS" + sfx, "name", "인쇄소", "type", "NORMAL"));
        Long pt = createId("/masters/clients", Map.of("code", "SRP" + sfx, "name", "거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "SRW" + sfx, "name", "창고", "type", "MAIN"));
        // 거래처 매핑은 거래처×대분류라 상품이 대분류를 가져야 한다(교재→TEXTBOOK)
        Long pr = createId("/masters/products", Map.of("code", "SRB" + sfx, "name", "도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 60, "catCode", "A2026SR",
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2026-11-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", pr, "unitCost", 3000, "qty", 500))));

        // 매핑도 입력도 없으면 도서 기본값 60%
        JsonNode a = data(post("/sales/entries", Map.of("salesDate", "2026-11-10", "partnerId", pt,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", pr, "shipmentType", "NORMAL_SHIP", "qty", 10)))));
        assertThat(a.path("items").get(0).path("supplyAmount").asLong()).isEqualTo(60_000);

        // 거래처별 매핑이 생기면 그게 이긴다
        put("/masters/partner-supply-rates/" + pt + "/TEXTBOOK", Map.of("supplyRate", 70));
        JsonNode b = data(post("/sales/entries", Map.of("salesDate", "2026-11-10", "partnerId", pt,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", pr, "shipmentType", "NORMAL_SHIP", "qty", 10)))));
        assertThat(b.path("items").get(0).path("supplyAmount").asLong()).isEqualTo(70_000);

        // 라인에 직접 넣으면 그게 최우선
        JsonNode c = data(post("/sales/entries", Map.of("salesDate", "2026-11-10", "partnerId", pt,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", pr, "shipmentType", "NORMAL_SHIP", "qty", 10,
                        "supplyRate", 50)))));
        assertThat(c.path("items").get(0).path("supplyAmount").asLong()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("거래처별 단가 일괄 적용 — 예외 단가는 기본적으로 덮지 않는다")
    void 단가_일괄적용() {
        String sfx = "-BK" + (System.nanoTime() % 1_000_000L);
        Long p1 = createId("/masters/clients", Map.of("code", "BP1" + sfx, "name", "특약점1", "type", "NORMAL"));
        Long p2 = createId("/masters/clients", Map.of("code", "BP2" + sfx, "name", "특약점2", "type", "NORMAL"));
        put("/masters/partner-supply-rates/" + p2 + "/MOCK_EXAM", Map.of("supplyRate", 55));   // 예외 단가

        JsonNode r = data(put("/masters/partner-supply-rates/bulk",
                Map.of("partnerIds", List.of(p1, p2), "majorCategory", "MOCK_EXAM", "supplyRate", 70)));
        assertThat(r.path("created").asInt()).isEqualTo(1);
        assertThat(r.path("skipped").asInt()).as("예외 단가를 가진 거래처는 건너뛴다").isEqualTo(1);
        // 건너뛴 대상을 코드로 돌려줘야 담당자가 누락인지 의도인지 구분한다
        assertThat(r.path("skippedPartnerCodes").get(0).asText()).isEqualTo("BP2" + sfx);
        assertThat(data(get("/masters/partner-supply-rates/" + p2 + "/MOCK_EXAM"))
                .path("supplyRate").asInt()).as("예외 단가 보존").isEqualTo(55);

        JsonNode f = data(put("/masters/partner-supply-rates/bulk",
                Map.of("partnerIds", List.of(p1, p2), "majorCategory", "MOCK_EXAM",
                        "supplyRate", 70, "overwrite", true)));
        assertThat(f.path("updated").asInt()).isEqualTo(2);
        assertThat(data(get("/masters/partner-supply-rates/" + p2 + "/MOCK_EXAM"))
                .path("supplyRate").asInt()).isEqualTo(70);
    }

    @Test
    @DisplayName("사업자주민번호 — 조회·엑셀은 마스킹, DB 원본은 보존(DB-73)")
    void 사업자주민번호_마스킹() {
        String sfx = "-MK" + (System.nanoTime() % 1_000_000L);
        Long id = createId("/masters/clients", Map.of("code", "MK" + sfx, "name", "마스킹거래처",
                "type", "NORMAL", "bossId", "800101-1234567"));

        assertThat(data(get("/masters/clients/" + id)).path("bossId").asText())
                .as("조회 응답은 마스킹").isEqualTo("800101-1******");

        // ★마스킹된 값을 그대로 되보내도 원본이 파괴되지 않아야 한다.
        //   화면이 조회값을 폼에 담아 저장하는 흔한 흐름이라, 막지 않으면 원본이 사라진다.
        put("/masters/clients/" + id, Map.of("name", "마스킹거래처", "type", "NORMAL",
                "bossId", "800101-1******"));
        assertThat(data(get("/masters/clients/" + id)).path("bossId").asText())
                .as("여전히 원본 기준 마스킹(파괴 안 됨)").isEqualTo("800101-1******");

        // 진짜 값으로는 바뀐다
        put("/masters/clients/" + id, Map.of("name", "마스킹거래처", "type", "NORMAL",
                "bossId", "900202-2345678"));
        assertThat(data(get("/masters/clients/" + id)).path("bossId").asText())
                .isEqualTo("900202-2******");
    }

    @Test
    @DisplayName("기초정보 변경이력 — 바뀐 필드만, 이전→이후 값이 남는다")
    void 기초정보_변경이력() {
        String sfx = "-CH" + (System.nanoTime() % 1_000_000L);
        Long p = createId("/masters/clients", Map.of("code", "CH" + sfx, "name", "이력거래처",
                "type", "NORMAL", "tel1", "02-111-1111", "bossId", "800101-1234567"));

        // 거래처명·연락처만 바꾼다(나머지는 그대로 보낸다).
        Map<String, Object> body = new HashMap<>();
        body.put("name", "이력거래처(변경)");
        body.put("type", "NORMAL");
        body.put("tel1", "02-222-2222");
        body.put("bossId", "800101-1234567");
        put("/masters/clients/" + p, body);

        JsonNode rows = data(get("/audit/master-changes?entityType=PARTNER&entityId=" + p)).path("content");
        Map<String, String> changed = new HashMap<>();
        for (JsonNode r : rows) {
            changed.put(r.path("field").asText(), r.path("oldValue").asText() + ">" + r.path("newValue").asText());
        }
        assertThat(changed).containsKeys("name", "tel1");
        assertThat(changed.get("name")).isEqualTo("이력거래처>이력거래처(변경)");
        assertThat(changed.get("tel1")).isEqualTo("02-111-1111>02-222-2222");
        // 안 바뀐 항목은 이력에 남지 않는다 — 남기면 실제 변경이 묻힌다.
        assertThat(changed).doesNotContainKey("bizNo");
        // ★사업자주민번호는 이력에도 마스킹되어야 한다(이력 조회로 원본이 새면 마스킹이 무의미).
        for (JsonNode r : rows) {
            assertThat(r.path("oldValue").asText()).doesNotContain("800101-1234567");
            assertThat(r.path("newValue").asText()).doesNotContain("800101-1234567");
        }
    }

    @Test
    @DisplayName("변경이력 — 값이 그대로면 저장해도 이력이 생기지 않는다")
    void 변경없으면_이력없음() {
        String sfx = "-CN" + (System.nanoTime() % 1_000_000L);
        Long p = createId("/masters/clients", Map.of("code", "CN" + sfx, "name", "무변경", "type", "NORMAL"));
        put("/masters/clients/" + p, Map.of("name", "무변경", "type", "NORMAL"));

        assertThat(data(get("/audit/master-changes?entityType=PARTNER&entityId=" + p))
                .path("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("변경이력 — 거래처별 단가 변경도 남는다(신규 생성은 대상 아님)")
    void 단가_변경이력() {
        String sfx = "-CP" + (System.nanoTime() % 1_000_000L);
        Long pt = createId("/masters/clients", Map.of("code", "CP" + sfx, "name", "단가처", "type", "NORMAL"));

        put("/masters/partner-supply-rates/" + pt + "/TEXTBOOK", Map.of("supplyRate", 70));  // 신규
        put("/masters/partner-supply-rates/" + pt + "/TEXTBOOK", Map.of("supplyRate", 65));  // 변경

        JsonNode rows = data(get("/audit/master-changes?entityType=PARTNER_PRICE")).path("content");
        boolean found = false;
        for (JsonNode r : rows) {
            if (r.path("entityCode").asText().contains("CP" + sfx)) {
                assertThat(r.path("oldValue").asText()).isEqualTo("70");
                assertThat(r.path("newValue").asText()).isEqualTo("65");
                found = true;
            }
        }
        assertThat(found).as("신규 생성은 이력 대상이 아니고, 변경만 1건 남는다").isTrue();
    }

    @Test
    @DisplayName("일부 필드만 보낸 PUT이 Y/N 플래그를 조용히 끄지 않는다")
    void 부분수정_플래그_보존() {
        // 실제로 있었던 결함: 요청 DTO가 primitive boolean이라 JSON에서 빠지면 false가 됐다.
        // 그래서 이름만 바꾸는 PUT이 사용여부·수불부노출·재고관리를 전부 꺼버렸다.
        // 특히 재고관리가 꺼지면 매출을 넣어도 재고가 차감되지 않는데 오류조차 나지 않는다.
        String sfx = "-FX" + (System.nanoTime() % 1_000_000L);
        Long id = createId("/masters/products", Map.of("code", "FX" + sfx, "name", "플래그도서",
                "contentType", "SELF", "price", 10000,
                "salesDivision", "교재"));

        put("/masters/products/" + id, Map.of("name", "플래그도서(수정)",
                "contentType", "SELF", "price", 12000));

        JsonNode d = data(get("/masters/products/" + id));
        assertThat(d.path("useYn").asBoolean()).as("사용여부 유지").isTrue();
        assertThat(d.path("ledgerVisible").asBoolean()).as("수불부노출 유지").isTrue();
        assertThat(d.path("stockManaged").asBoolean()).as("재고관리 유지 — 꺼지면 재고가 안 깎인다").isTrue();
        assertThat(d.path("price").asInt()).as("보낸 값은 반영").isEqualTo(12000);

        // 의도적으로 false를 보내면 꺼져야 한다(유지 로직이 수정 자체를 막으면 안 된다).
        Map<String, Object> off = new HashMap<>();
        off.put("name", "플래그도서(수정)");
        off.put("contentType", "SELF");
        off.put("price", 12000);
        off.put("stockManaged", false);
        put("/masters/products/" + id, off);
        assertThat(data(get("/masters/products/" + id)).path("stockManaged").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("창고 실물재고여부도 부분수정에 꺼지지 않는다")
    void 부분수정_창고플래그_보존() {
        // 물류창고가 조용히 가상창고가 되면 제품수불부 실재고 집계에서 통째로 빠진다.
        String sfx = "-FW" + (System.nanoTime() % 1_000_000L);
        Long id = createId("/masters/warehouses", Map.of("code", "FW" + sfx,
                "name", "플래그창고", "type", "MAIN"));

        put("/masters/warehouses/" + id, Map.of("name", "플래그창고(수정)", "type", "MAIN"));

        assertThat(data(get("/masters/warehouses/" + id)).path("physicalStock").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("도서 Y/N 일괄 변경 — 지정한 항목만, 실제 변경 건수를 구분해 돌려준다")
    void 도서플래그_일괄변경() {
        String sfx = "-BF" + (System.nanoTime() % 1_000_000L);
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ids.add(createId("/masters/products", Map.of("code", "BF" + i + sfx,
                    "name", "일괄도서" + i, "contentType", "SELF", "price", 10000,
                "salesDivision", "교재")));
        }

        JsonNode r = data(put("/masters/products/flags",
                Map.of("productIds", ids, "webVisible", true, "taxFree", true)));
        assertThat(r.path("changed").asInt()).isEqualTo(3);

        for (Long id : ids) {
            JsonNode d = data(get("/masters/products/" + id));
            assertThat(d.path("webVisible").asBoolean()).isTrue();
            assertThat(d.path("taxFree").asBoolean()).isTrue();
            // 지정하지 않은 항목은 건드리지 않는다
            assertThat(d.path("ledgerVisible").asBoolean()).as("수불부노출 유지").isTrue();
            assertThat(d.path("stockManaged").asBoolean()).as("재고관리 유지").isTrue();
        }

        // 같은 요청을 또 보내면 바뀐 건 없다 — "3건 적용"으로 뭉뚱그리면 무슨 일이 났는지 모른다.
        JsonNode again = data(put("/masters/products/flags",
                Map.of("productIds", ids, "webVisible", true, "taxFree", true)));
        assertThat(again.path("changed").asInt()).isZero();
        assertThat(again.path("unchanged").asInt()).isEqualTo(3);

        // 없는 id는 건너뛰고 알려 준다(전체를 실패시키지 않는다)
        List<Long> withGhost = new ArrayList<>(ids);
        withGhost.add(999_999_999L);
        JsonNode ghost = data(put("/masters/products/flags",
                Map.of("productIds", withGhost, "ledgerVisible", false)));
        assertThat(ghost.path("notFoundIds").get(0).asLong()).isEqualTo(999_999_999L);
        assertThat(ghost.path("changed").asInt()).isEqualTo(3);

        // 일괄 변경도 건별로 이력에 남아야 되짚을 수 있다
        JsonNode hist = data(get("/audit/master-changes?entityType=PRODUCT&size=100")).path("content");
        long count = 0;
        for (JsonNode h : hist) {
            if (h.path("entityCode").asText().endsWith(sfx)
                    && "ledgerVisible".equals(h.path("field").asText())) {
                count++;
            }
        }
        assertThat(count).as("수불부노출 일괄 변경 3건이 이력에 남는다").isEqualTo(3);
    }

    @Test
    @DisplayName("일괄 변경 — 바꿀 항목을 하나도 안 주면 거부한다")
    void 도서플래그_항목없음() {
        // 전 건을 훑고 아무것도 안 하는 요청이라, 조용히 성공시키면 담당자가 적용됐다고 오해한다.
        String sfx = "-BN" + (System.nanoTime() % 1_000_000L);
        Long id = createId("/masters/products", Map.of("code", "BN" + sfx,
                "name", "무항목", "contentType", "SELF", "price", 1000,
                "salesDivision", "교재"));

        assertThat(exchangeRaw(HttpMethod.PUT, "/masters/products/flags",
                Map.of("productIds", List.of(id)), token(), null).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("요구 누락 필드 — 매출 창고·창고 사용여부/비고·반품률·물류작업비여부")
    void 요구누락필드_보강() {
        String sfx = "-GP" + (System.nanoTime() % 1_000_000L);
        Long sup = createId("/masters/clients", Map.of("code", "GPS" + sfx, "name", "인쇄", "type", "NORMAL"));
        Long pt = createId("/masters/clients", Map.of("code", "GPP" + sfx, "name", "검증처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "GPW" + sfx,
                "name", "본사물류창고", "type", "MAIN", "memo", "본사 1층"));
        Long pr = createId("/masters/products", Map.of("code", "GPB" + sfx, "name", "도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75,
                "salesDivision", "교재"));

        // 31p 창고 — 사용여부 기본 true, 비고 저장
        JsonNode w = data(get("/masters/warehouses/" + wh));
        assertThat(w.path("useYn").asBoolean()).isTrue();
        assertThat(w.path("memo").asText()).isEqualTo("본사 1층");
        // 사용여부만 끄면 비고는 유지된다(부분수정 원칙)
        put("/masters/warehouses/" + wh, Map.of("name", "본사물류창고", "type", "MAIN", "useYn", false));
        JsonNode w2 = data(get("/masters/warehouses/" + wh));
        assertThat(w2.path("useYn").asBoolean()).isFalse();
        assertThat(w2.path("memo").asText()).isEqualTo("본사 1층");

        // 8p 물류작업비여부
        post("/stock/inbound", Map.of("processedDate", "2038-01-01", "supplierClientId", sup,
                "destinationWarehouseId", wh, "logisCostTarget", true,
                "items", List.of(Map.of("productId", pr, "unitCost", 3000, "qty", 200))));

        post("/sales/entries", Map.of("salesDate", "2038-02-10", "partnerId", pt, "warehouseId", wh,
                "items", List.of(
                        Map.of("productId", pr, "shipmentType", "NORMAL_SHIP", "qty", 100),
                        Map.of("productId", pr, "shipmentType", "RETURN", "qty", 5))));

        // 7p 매출에 출고 창고가 남는다 — 지금까지는 재고만 차감하고 버려서 되짚을 수 없었다
        for (JsonNode r : data(get("/sales?fromDate=2038-01-01&toDate=2038-12-31&partnerId=" + pt))
                .path("content")) {
            assertThat(r.path("warehouseName").asText()).isEqualTo("본사물류창고");
        }

        // 16p 반품률
        for (JsonNode r : data(get("/sales/net-summary?fromDate=2038-01-01&toDate=2038-12-31"))
                .path("rows")) {
            if (r.path("productName").asText().equals("도서") && r.path("saleQty").asLong() == 100) {
                assertThat(r.path("returnRate").asDouble()).as("5/100").isEqualTo(5.0);
            }
        }
    }
}
