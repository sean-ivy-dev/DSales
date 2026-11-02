package com.daesung.sales.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.junit.jupiter.api.TestInstance;

/**
 * 검수 시나리오 S-3 — 위탁 미결 부분정산.
 * 근거: 개발문서 2탭 13.0 / 1탭 18 완료조건 "13p 분할정산 시나리오 <b>5종 이상</b> 인수테스트 통과".
 *
 * <p>통과 기준 3가지(누적 정확 · 출고수량 초과 정산 차단 · 원본출고번호 역추적)를
 * 5개 시나리오로 나눠 검증한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("검수 시나리오 S-3(위탁 미결 부분정산 5종)")
class ConsignSettlementScenarioTest extends IntegrationTestSupport {

    /**
     * 실행별 고유 접두어. Gradle 테스트 워커 JVM이 빌드 간 재사용되면 static 컨테이너의 데이터가
     * 남아, 고정 코드를 쓰면 두 번째 실행에서 "이미 존재하는 코드"로 깨진다.
     */
    private static final String SFX = "-" + (System.nanoTime() % 1_000_000L);

    private Long supplier;
    private Long partner;
    private Long mainWh;
    private Long consignWh;

    @BeforeAll
    void seed() {
        token();
        supplier = createId("/masters/clients", Map.of("code", "CS-SUP" + SFX, "name", "정산인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "CS-CUST" + SFX, "name", "정산특약점", "type", "CONSIGN"));
        mainWh = createId("/masters/warehouses", Map.of("code", "CS-M" + SFX, "name", "정산물류창고", "type", "MAIN"));
        consignWh = createId("/masters/warehouses",
                Map.of("code", "CS-C" + SFX, "name", "정산위탁창고", "type", "CONSIGN", "ownerClientId", partner));
    }

    @Test
    @DisplayName("S-3-1 전량 정산 — 100 출고 → 100 정산, 미결 소진(CLOSED)")
    void 시나리오1_전량정산() {
        JsonNode pending = ship("CS-P1", 100);
        long outId = pending.path("consignmentOutId").asLong();

        settle(outId, 100, "2026-07-01");

        JsonNode after = pendingLine("CS-P1");
        assertThat(after).as("잔여 0이면 미결 목록에서 빠져야 함").isNull();
    }

    @Test
    @DisplayName("S-3-2 분할 정산 — 100을 30+70으로 나눠 정산, 누적이 정확히 쌓인다")
    void 시나리오2_분할누적() {
        JsonNode pending = ship("CS-P2", 100);
        long outId = pending.path("consignmentOutId").asLong();

        settle(outId, 30, "2026-07-02");
        JsonNode mid = pendingLine("CS-P2");
        assertThat(mid.path("settledQty").asInt()).as("1차 누적").isEqualTo(30);
        assertThat(mid.path("remainingQty").asInt()).as("잔여").isEqualTo(70);
        assertThat(mid.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(mid.path("totalQty").asInt())
                .as("불변식: 총출고 = 기정산 + 미결잔여")
                .isEqualTo(mid.path("settledQty").asInt() + mid.path("remainingQty").asInt());

        settle(outId, 70, "2026-07-03");
        assertThat(pendingLine("CS-P2")).as("전량 소진 후 미결 없음").isNull();
    }

    @Test
    @DisplayName("S-3-3 초과 정산 — 잔여 20인데 50 정산: 차단하지 않고 경고 + 잔여 음수")
    void 시나리오3_초과정산_허용() {
        JsonNode pending = ship("CS-P3", 100);
        long outId = pending.path("consignmentOutId").asLong();
        settle(outId, 80, "2026-07-04");

        JsonNode before = pendingLine("CS-P3");
        assertThat(before.path("remainingQty").asInt()).isEqualTo(20);

        // ★2026-08-31 발주처 확정: "자동 차단하던 로직 제거, 초과 시 경고 알림만 표시하고
        //   이후 처리는 담당자가 수기로". 그래서 등록은 되고 **경고가 함께 온다**.
        JsonNode over = post("/consignment/settle", Map.of(
                "salesDate", "2026-07-05",
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", 50,
                        "unitPrice", 20000, "supplyRate", 75))));
        assertThat(over.path("success").asBoolean()).as("초과여도 등록된다: %s", over).isTrue();

        JsonNode line = over.path("data").path("items").get(0);
        assertThat(line.path("overWarning").asText())
                .as("초과 사실을 경고로 알려줘야 화면이 alert를 띄운다").contains("미결 잔여를 초과");

        JsonNode after = pendingLine("CS-P3");
        assertThat(after.path("settledQty").asInt()).as("80 + 50").isEqualTo(130);
        assertThat(after.path("remainingQty").asInt())
                .as("초과분이 음수로 드러난다 — 0으로 깎으면 초과 사실이 사라진다").isEqualTo(-30);
    }

    @Test
    @DisplayName("S-3-4 원본출고번호 역추적 — 정산 건에서 OUT- 번호로 원 출고를 되짚을 수 있다")
    void 시나리오4_원본출고번호_역추적() {
        JsonNode pending = ship("CS-P4", 60);
        String outNo = pending.path("sourceOutNo").asText();
        assertThat(outNo).as("위탁출고번호 채번").startsWith("OUT-");

        settle(pending.path("consignmentOutId").asLong(), 25, "2026-07-06");

        JsonNode stmt = data(get("/consignment/settlement-statement"
                + "?startDate=2026-07-01&endDate=2026-07-31&partnerId=" + partner));
        boolean found = false;
        for (JsonNode r : stmt.path("rows")) {
            if (outNo.equals(r.path("sourceOutNo").asText())) {
                found = true;
                assertThat(r.path("settleQty").asInt()).as("정산수량").isEqualTo(25);
                assertThat(r.path("totalQty").asInt()).as("원 출고수량 역추적").isEqualTo(60);
                assertThat(r.path("remainingQty").asInt()).as("미결 잔여").isEqualTo(35);
            }
        }
        assertThat(found).as("정산내역서에서 원본출고번호 %s로 역추적 가능해야 함: %s", outNo, stmt).isTrue();
    }

    @Test
    @DisplayName("S-3-5 정산 후 매출취소 — 미결원장이 정산 전으로 복원된다")
    void 시나리오5_정산취소_미결복원() {
        JsonNode pending = ship("CS-P5", 100);
        long outId = pending.path("consignmentOutId").asLong();
        settle(outId, 40, "2026-08-10");

        JsonNode mid = pendingLine("CS-P5");
        assertThat(mid.path("settledQty").asInt()).isEqualTo(40);

        long saleId = data(get("/sales?startDate=2026-08-01&endDate=2026-08-31&partnerId=" + partner))
                .path("content").get(0).path("id").asLong();
        JsonNode cancel = post("/sales/" + saleId + "/cancel", Map.of());
        assertThat(cancel.path("success").asBoolean()).as("취소: %s", cancel).isTrue();

        JsonNode after = pendingLine("CS-P5");
        assertThat(after.path("settledQty").asInt()).as("정산 전으로 복원").isZero();
        assertThat(after.path("remainingQty").asInt()).as("잔여 복원").isEqualTo(100);
    }

    @Test
    @DisplayName("‼️공급률이 없으면 위탁출고를 막는다 — 나간 뒤에 알면 되돌릴 수 없다")
    void 공급률없으면_출고거부() {
        // 근거: 프론트 실측 2026-10-23 §6⑤ "공급률이 비어 있는(null) 위탁 출고가 있습니다.
        //       정산 때 금액 기준을 확인 부탁드립니다."
        //
        // 예전엔 null 인 채로 저장됐고, 그 미결은 **정산할 때 비로소** 막혔다.
        // 그때는 이미 물건이 위탁창고로 나간 뒤라 담당자가 되돌릴 수 없다.
        // 매출등록(createEntries)은 처음부터 같은 조건으로 막고 있었다 — 규칙을 맞춘다.
        Long p = createId("/masters/products", Map.of(
                "code", "CS-NORATE" + SFX, "name", "공급률없는도서",
                "contentType", "SELF", "price", 20000,
                "salesDivision", "교재"));   // ← supplyRate 없음
        post("/stock/inbound", Map.of(
                "processedDate", "2026-06-01", "supplierClientId", supplier,
                "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", p, "unitCost", 3000, "qty", 10))));

        JsonNode r = post("/consignment/out", Map.of(
                "processedDate", "2026-06-05", "partnerId", partner,
                "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", p, "qty", 10))));

        assertThat(r.path("success").asBoolean()).as("막혀야 한다: %s", r).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
        // 담당자가 어디를 고쳐야 하는지 메시지에 들어 있어야 한다.
        assertThat(r.path("error").path("message").asText()).contains("공급률");

        // ★막힌 건 미결도 안 남는다 — 반쯤 들어간 상태가 제일 나쁘다.
        assertThat(pendingLine("CS-NORATE")).as("미결이 생기면 안 된다").isNull();
    }

    // ── helpers ──

    /** 상품 생성 → 입고 → 위탁출고. 생성된 미결 라인을 반환. */
    private JsonNode ship(String code, int qty) {
        Long p = createId("/masters/products",
                Map.of("code", code + SFX, "name", code, "contentType", "SELF", "price", 20000,
                        // ★공급률이 있어야 위탁출고가 등록된다 — 나갈 때 조건이 정해져야
                        //   나중에 정산할 수 있다(매출등록과 같은 규칙).
                        "supplyRate", 75,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of(
                "processedDate", "2026-06-01", "supplierClientId", supplier, "destinationWarehouseId", mainWh,
                "items", List.of(Map.of("productId", p, "unitCost", 3000, "qty", qty))));
        JsonNode out = post("/consignment/out", Map.of(
                "processedDate", "2026-06-05", "partnerId", partner,
                "fromWarehouseId", mainWh, "toWarehouseId", consignWh,
                "items", List.of(Map.of("productId", p, "qty", qty))));
        assertThat(out.path("success").asBoolean()).as("위탁출고: %s", out).isTrue();
        JsonNode line = pendingLine(code);
        assertThat(line).as("미결 생성 확인").isNotNull();
        return line;
    }

    private void settle(long outId, int qty, String salesDate) {
        JsonNode r = post("/consignment/settle", Map.of(
                "salesDate", salesDate,
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", qty,
                        "unitPrice", 20000, "supplyRate", 75))));
        assertThat(r.path("success").asBoolean()).as("정산 %d건: %s", qty, r).isTrue();
    }

    /** 거래처 미결 목록에서 상품코드로 라인 찾기. 없으면 null(=소진). */
    private JsonNode pendingLine(String productCode) {
        JsonNode lines = data(get("/consignment/pending?partnerId=" + partner)).path("items");
        for (JsonNode l : lines) {
            if ((productCode + SFX).equals(l.path("productCode").asText())) {
                return l;
            }
        }
        return null;
    }

    @Test
    @DisplayName("한 줄에서 정산+반품 동시 처리 — 정산분만 매출, 반품분은 재고 복귀")
    void 정산_반품_동시처리() {
        // 발주처 확정(확인요청서 v1 2번 No.4 · 이슈#43, 2026-07-29):
        // "위탁출고내역 그리드에 '반품수량' 칼럼 추가. 정산수량은 매출 확정,
        //  반품수량은 매출 영향 없이 미결 잔여만 차감되고 실물재고가 증가"
        // 회신 예시 그대로 검증한다 — 100부 출고 → 80 정산 + 20 반품.
        String sfx = "-RQ" + (System.nanoTime() % 1_000_000L);
        Long sup = createId("/masters/clients", Map.of("code", "RQS" + sfx, "name", "인쇄", "type", "NORMAL"));
        Long pt = createId("/masters/clients", Map.of("code", "RQP" + sfx, "name", "위탁처", "type", "NORMAL"));
        Long mw = createId("/masters/warehouses", Map.of("code", "RQM" + sfx, "name", "물류", "type", "MAIN"));
        Long cw = createId("/masters/warehouses", Map.of("code", "RQC" + sfx, "name", "위탁",
                "type", "CONSIGN", "ownerClientId", pt));
        Long bk = createId("/masters/products", Map.of("code", "RQB" + sfx, "name", "디파인수학",
                "contentType", "SELF", "price", 20000, "supplyRate", 75,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2034-08-01", "supplierClientId", sup,
                "destinationWarehouseId", mw,
                "items", List.of(Map.of("productId", bk, "unitCost", 5000, "qty", 500))));
        post("/consignment/out", Map.of("processedDate", "2034-08-01", "partnerId", pt,
                "fromWarehouseId", mw, "toWarehouseId", cw,
                "items", List.of(Map.of("productId", bk, "qty", 100))));
        long outId = data(get("/consignment/pending?partnerId=" + pt))
                .path("items").get(0).path("consignmentOutId").asLong();

        JsonNode line = data(post("/consignment/settle", Map.of(
                "salesDate", "2034-09-01",
                "settlements", List.of(Map.of("consignmentOutId", outId,
                        "settleQty", 80, "returnQty", 20,
                        "unitPrice", 20000, "supplyRate", 75)))))
                .path("items").get(0);

        assertThat(line.path("settleQty").asInt()).isEqualTo(80);
        assertThat(line.path("returnQty").asInt()).isEqualTo(20);
        assertThat(line.path("remainingQty").asInt()).as("80+20=100 전량 소진").isZero();
        assertThat(line.path("status").asText()).isEqualTo("CLOSED");
        // 매출은 정산분만 — 80 × 20,000 × 75%
        assertThat(line.path("supplyAmount").asLong()).isEqualTo(1_200_000);

        // 반품분은 물류창고로 돌아온다(500 − 100 출고 + 20 반품 = 420)
        assertThat(line.path("mainBalance").asInt()).isEqualTo(420);
        // ★불변식: 위탁창고 잔량 == 미결 잔여수량
        //   근거: 정본 31p "실물재고여부 N인 창고(위탁창고)는 실재고 합계에서 제외하고
        //   **별도 미결수량으로만 집계**". 정산분을 위탁창고에서 빼지 않으면 미결은 0인데
        //   위탁창고에는 80이 남아 두 숫자가 어긋나고, 잔량이 영구 누적된다.
        assertThat(line.path("consignBalance").asInt())
                .as("위탁창고 잔량은 미결 잔여와 같아야 한다").isEqualTo(0);

        // ★매출 장부에는 정산분 80만 잡혀야 한다. 반품 20이 섞이면 매출이 부풀려진다.
        long soldQty = 0;
        for (JsonNode s : data(get("/sales?fromDate=2034-09-01&toDate=2034-09-30&partnerId=" + pt))
                .path("content")) {
            soldQty += s.path("qty").asLong();
        }
        assertThat(soldQty).as("반품 20은 매출이 아니다").isEqualTo(80);
    }

    @Test
    @DisplayName("정산+반품 합계가 미결 잔여를 넘어도 등록된다 — 경고만, 초과분은 Case1")
    void 정산_반품_합계초과() {
        // 잔여 50에 정산 30 + 반품 30 = 60. 차단하지 않고, 반품 초과분은 확정매출분 반품(Case1)으로 간다.
        String sfx = "-RO" + (System.nanoTime() % 1_000_000L);
        Long sup = createId("/masters/clients", Map.of("code", "ROS" + sfx, "name", "인쇄", "type", "NORMAL"));
        Long pt = createId("/masters/clients", Map.of("code", "ROP" + sfx, "name", "위탁처", "type", "NORMAL"));
        Long mw = createId("/masters/warehouses", Map.of("code", "ROM" + sfx, "name", "물류", "type", "MAIN"));
        Long cw = createId("/masters/warehouses", Map.of("code", "ROC" + sfx, "name", "위탁",
                "type", "CONSIGN", "ownerClientId", pt));
        Long bk = createId("/masters/products", Map.of("code", "ROB" + sfx, "name", "도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2034-08-01", "supplierClientId", sup,
                "destinationWarehouseId", mw,
                "items", List.of(Map.of("productId", bk, "unitCost", 3000, "qty", 200))));
        post("/consignment/out", Map.of("processedDate", "2034-08-01", "partnerId", pt,
                "fromWarehouseId", mw, "toWarehouseId", cw,
                "items", List.of(Map.of("productId", bk, "qty", 50))));
        long outId = data(get("/consignment/pending?partnerId=" + pt))
                .path("items").get(0).path("consignmentOutId").asLong();

        JsonNode both = post("/consignment/settle", Map.of(
                "salesDate", "2034-09-01",
                "settlements", List.of(Map.of("consignmentOutId", outId,
                        "settleQty", 30, "returnQty", 30,
                        "unitPrice", 10000, "supplyRate", 75))));
        assertThat(both.path("success").asBoolean()).as("30+30 > 잔여 50이어도 등록: %s", both).isTrue();

        JsonNode l = both.path("data").path("items").get(0);
        assertThat(l.path("overWarning").asText()).contains("미결 잔여를 초과");
        // 정산 30이 먼저 반영돼 잔여가 20 → 반품 30 중 20은 Case2, 10은 Case1(확정매출분)
        assertThat(l.path("returnCase2Qty").asInt()).as("미결잔여만큼: %s", l).isEqualTo(20);
        assertThat(l.path("returnCase1Qty").asInt()).as("초과분은 확정매출분 반품").isEqualTo(10);

        // 아무것도 입력 안 하면 400 — 전 건을 훑고 아무 일도 안 하는 요청이다.
        assertThat(exchangeRaw(HttpMethod.POST, "/consignment/settle", Map.of(
                "salesDate", "2034-09-01",
                "settlements", List.of(Map.of("consignmentOutId", outId,
                        "unitPrice", 10000, "supplyRate", 75))), token(), null)
                .getStatusCode().value()).isEqualTo(400);

        // 반품만 입력하면 정가·공급률 없이도 처리된다(매출을 만들지 않으므로 금액이 필요 없다).
        JsonNode only = data(post("/consignment/settle", Map.of(
                "salesDate", "2034-09-01",
                "settlements", List.of(Map.of("consignmentOutId", outId, "returnQty", 10)))))
                .path("items").get(0);
        // 앞에서 정산 30 + 반품 30(Case2 20 + Case1 10)이 이미 반영됐다 →
        // 잔여 0에서 다시 반품 10을 넣으면 그 10은 전부 Case1(확정매출분)로 간다.
        assertThat(only.path("returnCase1Qty").asInt()).as("잔여가 없으니 전부 Case1: %s", only).isEqualTo(10);
        assertThat(only.path("remainingQty").asInt()).as("잔여는 그대로 0").isZero();
        assertThat(only.hasNonNull("salesNo")).as("반품만이면 매출번호가 없다").isFalse();
    }

    @Test
    @DisplayName("불변식 — 정산·반품 어느 쪽으로 줄어도 위탁창고 잔량 == 미결 잔여")
    void 위탁창고_미결_일치() {
        String sfx = "-CI" + (System.nanoTime() % 1_000_000L);
        Long sup = createId("/masters/clients", Map.of("code", "CIS" + sfx, "name", "인쇄", "type", "NORMAL"));
        Long pt = createId("/masters/clients", Map.of("code", "CIP" + sfx, "name", "위탁처", "type", "NORMAL"));
        Long mw = createId("/masters/warehouses", Map.of("code", "CIM" + sfx, "name", "물류", "type", "MAIN"));
        Long cw = createId("/masters/warehouses", Map.of("code", "CIC" + sfx, "name", "위탁",
                "type", "CONSIGN", "ownerClientId", pt));
        Long bk = createId("/masters/products", Map.of("code", "CIB" + sfx, "name", "도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2035-01-01", "supplierClientId", sup,
                "destinationWarehouseId", mw,
                "items", List.of(Map.of("productId", bk, "unitCost", 3000, "qty", 300))));
        post("/consignment/out", Map.of("processedDate", "2035-01-01", "partnerId", pt,
                "fromWarehouseId", mw, "toWarehouseId", cw,
                "items", List.of(Map.of("productId", bk, "qty", 100))));
        long outId = data(get("/consignment/pending?partnerId=" + pt))
                .path("items").get(0).path("consignmentOutId").asLong();

        // 정산만 30 → 잔여 70, 위탁창고도 70이어야 한다
        JsonNode a = data(post("/consignment/settle", Map.of("salesDate", "2035-02-01",
                "settlements", List.of(Map.of("consignmentOutId", outId, "settleQty", 30,
                        "unitPrice", 10000, "supplyRate", 75))))).path("items").get(0);
        assertThat(a.path("remainingQty").asInt()).isEqualTo(70);
        assertThat(a.path("consignBalance").asInt()).as("정산만 해도 위탁창고가 줄어야 한다").isEqualTo(70);

        // 반품만 20 → 잔여 50, 위탁창고 50
        JsonNode b = data(post("/consignment/settle", Map.of("salesDate", "2035-02-01",
                "settlements", List.of(Map.of("consignmentOutId", outId, "returnQty", 20)))))
                .path("items").get(0);
        assertThat(b.path("remainingQty").asInt()).isEqualTo(50);
        assertThat(b.path("consignBalance").asInt()).isEqualTo(50);

        // 별도 반품 API(단독 경로)로 줄여도 같은 규칙이어야 한다
        JsonNode c = data(post("/consignment/return", Map.of("processedDate", "2035-02-01",
                "items", List.of(Map.of("consignmentOutId", outId, "returnQty", 50)))))
                .path("items").get(0);
        assertThat(c.path("remainingQty").asInt()).isZero();
        assertThat(c.path("consignBalance").asInt()).as("두 입구에서 규칙이 갈리면 안 된다").isZero();
    }
}
