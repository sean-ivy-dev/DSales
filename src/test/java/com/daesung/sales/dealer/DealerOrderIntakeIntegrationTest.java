package com.daesung.sales.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
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
 * 특약점 사이트 [새 주문] 탭 접수 — <b>매출 주문으로 저장</b>(10/27 명세 §1 · 프론트 A-1).
 *
 * <p>정본 §1 — "§10-1 '신청 저장 구조'의 답은 <b>'새 탭 주문은 매출 주문으로 저장'</b>이다.
 * DSRE2 {@code tbl_request_info} 에 매출 도서를 담는 방식은 쓰지 않는다."
 * 10/26 §1 — "<b>공급률은 신청 화면에 표시하지도, 반영하지도 않는다. 매출 금액은
 * 매출프로그램 기준으로 계산한다</b>." §2-10 — "특약점 사이트에서는 (발송구분을) 고르지 않는다."
 * §10-3 — "<b>사이트 서버가 매출 API를 서버 간 호출</b>."
 *
 * <p>★이 테스트가 지키는 것은 <b>"특약점 주문도 매출등록과 똑같이 흐르는가"</b>다 —
 * 재고가 빠지고, 작업요청서가 생기고, 거래순번·전표번호가 붙는다. 다른 점은 진행상태가
 * <b>접수완료</b>에서 시작하는 것뿐이다(수기 등록은 상품준비중).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("특약점 새 주문 접수(§1 · A-1)")
class DealerOrderIntakeIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DO" + (System.nanoTime() % 1_000_000L);
    private static final String PARTNER_CODE = "DOP" + SFX;
    private static final String BOOK_CODE = "DOB" + SFX;
    private static final String DATE = "2092-05-11";

    private Long partner;
    private Long book;
    private Long wh;

    @org.springframework.beans.factory.annotation.Autowired
    private ShipmentRepository shipmentRepository;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "DOS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", PARTNER_CODE, "name", "특약점거래처", "type", "NORMAL"));

        // ★창고는 설정으로 못 박힌 고정 코드를 쓴다 — 테스트 DB 엔 다른 클래스가 만든
        //   MAIN 창고가 여럿이라 "하나일 때 자동" 규칙으로는 정해지지 않는다.
        //   코드가 고정이라 **있으면 쓰고 없으면 만든다**(컨테이너가 실행 간 살아 있다).
        wh = findOrCreateDealerWarehouse();

        Map<String, Object> b = new HashMap<>();
        b.put("code", BOOK_CODE);
        b.put("name", "특약점주문도서");
        b.put("contentType", "SELF");
        b.put("price", 20000);
        b.put("supplyRate", 70);
        b.put("catCode", "D2092A01");
        b.put("catName", "특약점검증");
        b.put("salesDivision", "교재");
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2092-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 100, "qty", 999))))
                .path("success").asBoolean()).isTrue();
    }

    private Long findOrCreateDealerWarehouse() {
        for (JsonNode w : data(get("/masters/warehouses?keyword=" + DEALER_WAREHOUSE_CODE))
                .path("content")) {
            if (DEALER_WAREHOUSE_CODE.equals(w.path("code").asText())) {
                return w.path("id").asLong();
            }
        }
        return createId("/masters/warehouses", Map.of(
                "code", DEALER_WAREHOUSE_CODE, "name", "특약점주문 물류창고", "type", "MAIN"));
    }

    private Map<String, Object> order(int qty) {
        Map<String, Object> req = new HashMap<>();
        req.put("partnerCode", PARTNER_CODE);
        req.put("orderDate", DATE);
        req.put("items", List.of(Map.of("productCode", BOOK_CODE, "qty", qty)));
        return req;
    }

    @Test
    @DisplayName("키가 없으면 401 — 이 경로는 열려 있지 않다")
    void 키없으면_거부() {
        JsonNode r = postAsDealerSite("/dealer-site/orders", order(1), null);

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("키가 틀리면 401")
    void 키틀리면_거부() {
        JsonNode r = postAsDealerSite("/dealer-site/orders", order(1), "wrong-key");

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("★접수되면 매출 주문이 된다 — 단가는 서버가 정하고(공급률 안 받는다) 재고가 빠진다")
    void 접수되면_매출주문() {
        long before = stockOf();

        JsonNode r = postAsDealerSite("/dealer-site/orders", order(30), DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        JsonNode d = data(r);

        // 매출등록과 같은 응답이다 — 등록번호·거래순번·줄별 매출번호가 붙는다.
        assertThat(d.path("entryNo").asText()).as("등록번호").isNotBlank();
        assertThat(d.path("tradeSeq").asInt()).as("거래순번").isPositive();
        assertThat(d.path("items")).hasSize(1);

        JsonNode line = d.path("items").get(0);
        assertThat(line.path("salesNo").asText()).isNotBlank();
        // ★공급률을 보내지 않았는데 금액이 선다 — 서버가 마스터 정가·공급률로 계산했다.
        //   20,000 × 70% × 30 = 420,000
        assertThat(line.path("supplyAmount").asLong()).isEqualTo(420_000L);

        // 재고가 실제로 빠진다(매출등록과 같은 흐름).
        assertThat(stockOf()).as("30부 차감").isEqualTo(before - 30);
    }

    @Test
    @DisplayName("★접수완료 상태로 시작한다 — A 에서만 수정·삭제한다는 규칙(§2-3)의 전제")
    void 접수완료로_시작() {
        JsonNode r = postAsDealerSite("/dealer-site/orders", order(5), DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        String entryNo = data(r).path("entryNo").asText();

        // ★작업요청서로 물류에 전달된다(§1 "작업요청서로 물류 전달").
        boolean delivered = false;
        for (JsonNode w : data(get("/logistics/work-orders?fromDate=" + DATE + "&toDate=" + DATE))
                .path("content")) {
            if (PARTNER_CODE.equals(w.path("partnerCode").asText())) {
                delivered = true;
            }
        }
        assertThat(delivered).as("작업요청서가 생긴다").isTrue();

        // ★진행상태는 **저장값**으로 확인한다.
        //   ‼️지금은 이 상태를 내보내는 API 가 없다 — 주문조회가 아직 DSRE 신청을 읽기
        //     때문이다(A-2). 그걸 Sale 기반으로 바꾸면 거기 실린다. 그 전까지는
        //     "저장은 되어 있다"를 여기서 지킨다.
        List<Shipment> shipments = shipmentRepository.findByEntryNo(entryNo);
        assertThat(shipments).as("등록번호로 발송 건을 찾는다").isNotEmpty();
        for (Shipment sh : shipments) {
            assertThat(sh.progressState()).as("접수완료(A)로 들어온다").isEqualTo(OrderState.RECEIVED);
        }
    }

    @Test
    @DisplayName("없는 거래처·도서는 사유를 말하고 거부한다")
    void 없는값은_사유와함께() {
        Map<String, Object> bad = order(1);
        bad.put("partnerCode", "없는거래처" + SFX);
        JsonNode r1 = postAsDealerSite("/dealer-site/orders", bad, DEALER_SITE_KEY);
        assertThat(r1.path("success").asBoolean()).isFalse();
        assertThat(r1.path("error").path("message").asText()).contains("거래처를 찾을 수 없습니다");

        Map<String, Object> bad2 = new HashMap<>(order(1));
        bad2.put("items", List.of(Map.of("productCode", "없는도서" + SFX, "qty", 1)));
        JsonNode r2 = postAsDealerSite("/dealer-site/orders", bad2, DEALER_SITE_KEY);
        assertThat(r2.path("success").asBoolean()).isFalse();
        assertThat(r2.path("error").path("message").asText()).contains("도서를 찾을 수 없습니다");
    }

    @Test
    @DisplayName("수량 0 이하는 400 — 양식 검증은 등록 전에 끊는다")
    void 수량이_0이하면_400() {
        JsonNode r = postAsDealerSite("/dealer-site/orders", order(0), DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★★담당자 정보를 안 보내면 거래처 마스터 값으로 채운다(10/26 §4)")
    void 담당자정보_기본값채움() {
        Long p = createId("/masters/clients", Map.of(
                "code", "DOM" + SFX, "name", "담당자거래처", "type", "NORMAL",
                "bossName", "김대표", "tel1", "02-111-2222", "email1", "boss@example.com"));

        Map<String, Object> req = new HashMap<>();
        req.put("partnerCode", "DOM" + SFX);
        req.put("orderDate", DATE);
        req.put("items", List.of(Map.of("productCode", BOOK_CODE, "qty", 1)));
        JsonNode r = postAsDealerSite("/dealer-site/orders", req, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        String entryNo = data(r).path("entryNo").asText();
        JsonNode wo = null;
        for (JsonNode w : data(get("/logistics/work-orders?fromDate=" + DATE + "&toDate=" + DATE))
                .path("content")) {
            if (entryNo.equals(w.path("entryNo").asText())) {
                wo = w;
            }
        }
        assertThat(wo).as("작업요청서를 찾았다").isNotNull();
        assertThat(wo.path("receiverName").asText()).isEqualTo("김대표");
        assertThat(wo.path("receiverPhone").asText()).isEqualTo("02-111-2222");
        assertThat(wo.path("receiverEmail").asText()).isEqualTo("boss@example.com");
        // ★★담당자정보가 채워져도 **화물**이다 — 발주처 추가요청 J-1(2026-11-03).
        //   §8-6의 "담당자명·연락처가 있으면 택배"는 매출등록 수기 주문에만 적용된다.
        //   이 경로는 바로 위에서 마스터 대표자명·연락처를 자동으로 채우므로, 그 규칙을
        //   같이 적용하면 운영 거래처 180곳 중 178곳이 전부 택배가 된다(= §8-6이 지적한 버그).
        assertThat(wo.path("deliveryType").asText())
                .as("특약점 사이트 주문은 담당자정보가 있어도 화물(§2-10·J-1)")
                .isEqualTo("FREIGHT");

        // ‼️거래처 마스터는 바뀌지 않는다("수정한 값은 그 신청 건에만 저장한다").
        JsonNode master = data(get("/masters/clients/" + p));
        assertThat(master.path("bossName").asText()).isEqualTo("김대표");
    }

    @Test
    @DisplayName("★보낸 담당자 정보는 거래처 마스터보다 우선한다 — 신청 건에만 저장된다")
    void 담당자정보_직접입력이_우선() {
        Long p = createId("/masters/clients", Map.of(
                "code", "DOV" + SFX, "name", "담당자override거래처", "type", "NORMAL",
                "bossName", "마스터대표", "tel1", "02-999-9999", "email1", "master@example.com"));

        Map<String, Object> req = new HashMap<>();
        req.put("partnerCode", "DOV" + SFX);
        req.put("orderDate", DATE);
        req.put("receiverName", "박선생");
        req.put("receiverPhone", "010-5555-5555");
        req.put("receiverEmail", "teacher@example.com");
        req.put("items", List.of(Map.of("productCode", BOOK_CODE, "qty", 1)));
        JsonNode r = postAsDealerSite("/dealer-site/orders", req, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        String entryNo = data(r).path("entryNo").asText();
        JsonNode wo = null;
        for (JsonNode w : data(get("/logistics/work-orders?fromDate=" + DATE + "&toDate=" + DATE))
                .path("content")) {
            if (entryNo.equals(w.path("entryNo").asText())) {
                wo = w;
            }
        }
        assertThat(wo).isNotNull();
        assertThat(wo.path("receiverName").asText()).as("보낸 값이 이긴다").isEqualTo("박선생");
        assertThat(wo.path("receiverPhone").asText()).isEqualTo("010-5555-5555");
        assertThat(wo.path("receiverEmail").asText()).isEqualTo("teacher@example.com");

        // 거래처 마스터는 여전히 '마스터대표' — 신청 건의 수정값으로 덮이지 않는다.
        JsonNode master = data(get("/masters/clients/" + p));
        assertThat(master.path("bossName").asText()).isEqualTo("마스터대표");
    }

    /** 재고는 제품수불부(단일 공식)로 본다 — 화면마다 다른 계산식을 쓰지 않는다. */
    private long stockOf() {
        long sum = 0;
        for (JsonNode r : data(get("/stock/ledger?fromDate=2092-01-01&toDate=2092-12-31"
                + "&productId=" + book)).path("content")) {
            sum += r.path("closing").asLong();
        }
        return sum;
    }
}
