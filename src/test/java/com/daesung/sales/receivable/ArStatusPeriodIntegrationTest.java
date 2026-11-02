package com.daesung.sales.receivable;

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
 * 미수금현황의 <b>기간 조건</b>(프론트 실측 2026-10-15 A-1).
 *
 * <p>★<b>같은 날짜의 잔액은 시작일을 어떻게 잡든 같아야 한다.</b> 그게 '잔액'의 뜻이다.
 * 예전엔 기초(opening)에 <b>그해 이월만</b> 넣고 1/1~시작 전날 거래를 빼먹어서,
 * 7월부터 조회하면 상반기 거래가 통째로 사라진 잔액이 나왔다
 * (실측: 1/1 시작 75,268,510 vs 7/1 시작 59,096,620).
 *
 * <p>‼️같은 파일의 외상매출장({@code arLedger})은 처음부터 맞게 계산하고 있었다 —
 * <b>두 화면이 같은 거래처의 잔액을 다르게 보여주고 있었다.</b> 둘 중 하나만 보면 못 찾는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("미수금현황 기간 조건(2026-10-15 A-1)")
class ArStatusPeriodIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-AP" + (System.nanoTime() % 1_000_000L);
    private static final String YEAR = "2107";
    /**
     * 공급자(우리) 사업자번호 — <b>테스트 프로파일 값</b>이다
     * ({@code IntegrationTestSupport} 가 `111-11-11111` 로 덮는다).
     * ‼️운영값(1088166057)을 적으면 안 된다 — 테스트에서는 안 맞아 필터가 안 걸린다(실제로 틀렸다).
     * ★하이픈 표기가 다른 것도 함께 본다: 여기는 하이픈 있고 거래처는 없이 넣는다.
     */
    private static final String SUPPLIER_BIZ_NO = "1111111111";

    private Long partner;
    private Long product;
    private Long warehouse;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "APS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        partner = createId("/masters/clients",
                Map.of("code", "APP" + SFX, "name", "기간검증거래처", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "APW" + SFX, "name", "기간검증창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "APB" + SFX);
        b.put("name", "기간검증 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "A2107A01");
        b.put("catName", "기간검증분류");
        b.put("salesDivision", "교재");
        product = createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "unitCost", 1000, "qty", 1000))));

        // 상반기 100부(70만) · 하반기 50부(35만). 수금은 상반기에 20만.
        sale(YEAR + "-03-10", 100);
        collect(YEAR + "-04-20", 200_000);
        sale(YEAR + "-09-10", 50);
    }

    private void sale(String date, int qty) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", qty);
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출: %s", r).isTrue();
    }

    private void collect(String date, long amount) {
        JsonNode r = post("/closing/collections", Map.of(
                "collDate", date, "partnerId", partner, "collType", "CASH", "collAmt", amount));
        assertThat(r.path("success").asBoolean()).as("수금: %s", r).isTrue();
    }

    /** 그 거래처 행. */
    private JsonNode row(String from, String to) {
        JsonNode rows = data(get("/closing/ar-status?fromDate=" + from + "&toDate=" + to
                + "&partnerId=" + partner)).path("rows");
        for (JsonNode r : rows) {
            if (r.hasNonNull("partnerId") && r.path("partnerId").asLong() == partner) {
                return r;
            }
        }
        throw new AssertionError("거래처 행 없음: " + rows);
    }

    @Test
    @DisplayName("이월 재생성 — 막지 않되 **무엇이 바뀌었는지** 알려준다")
    void 이월_재생성_델타() {
        int next = Integer.parseInt(YEAR) + 1;

        // 처음 만들면 바뀐 것이 없다.
        JsonNode first = data(post("/closing/carryforward?fiscalYear=" + next, null));
        assertThat(first.path("changed").asInt()).as("최초 생성").isZero();
        long firstTotal = first.path("totalCarryAmount").asLong();

        // 그대로 다시 만들어도 값이 같으니 바뀐 것이 없다 — 재생성 자체는 막지 않는다.
        assertThat(data(post("/closing/carryforward?fiscalYear=" + next, null))
                .path("changed").asInt()).as("같은 값 재생성").isZero();

        // ‼️스냅샷을 뜬 **뒤에** 전년 거래가 들어오면, 재생성에서 그 사실이 드러나야 한다.
        //   예전엔 조용히 덮여서 담당자가 잔액이 왜 변했는지 알 수 없었다.
        // ★전용 거래처를 쓴다 — 공유 거래처에 거래를 더하면 다른 테스트의 잔액 검증이 깨진다
        //   (실제로 그렇게 깨뜨렸다). 이월은 전 거래처를 도므로 델타는 그대로 잡힌다.
        Long other = createId("/masters/clients",
                Map.of("code", "APQ" + SFX, "name", "이월델타거래처", "type", "NORMAL"));
        Map<String, Object> it = new HashMap<>();
        it.put("productId", product);
        it.put("shipmentType", "NORMAL_SHIP");
        it.put("unitPrice", 10000);
        it.put("supplyRate", 70);
        it.put("qty", 10);
        assertThat(post("/sales/entries", Map.of(
                "salesDate", YEAR + "-11-11", "partnerId", other, "warehouseId", warehouse,
                "items", List.of(it))).path("success").asBoolean()).isTrue();
        JsonNode again = data(post("/closing/carryforward?fiscalYear=" + next, null));
        assertThat(again.path("changed").asInt()).as("바뀐 거래처 수").isPositive();
        assertThat(again.path("changedAmount").asLong()).isEqualTo(70_000);
        assertThat(again.path("totalCarryAmount").asLong()).isEqualTo(firstTotal + 70_000);
    }

    @Test
    @DisplayName("★★해를 걸친 기간 — 기초는 시작일 연도로 잡는다(1년치 이중계상 회귀)")
    void 해를_걸쳐도_기초가_맞다() {
        // 프론트 실측 2026-10-22: 2025-12-30~2026-01-05 조회 시 이월이 4.50억 커졌다.
        // 원인은 **이월은 종료일 연도, 앞 구간은 시작일 연도**를 보던 것.
        // 다음 해 이월은 이미 "그 전해 말 잔액"인데 거기에 그 전해 거래를 또 얹고 있었다.
        int nextYear = Integer.parseInt(YEAR) + 1;
        assertThat(post("/closing/carryforward?fiscalYear=" + nextYear, null)
                .path("success").asBoolean()).as("다음 해 이월 생성").isTrue();

        // YEAR 의 마지막 거래는 9/10 이므로 12/29 잔액 = 연말 잔액 = 850,000.
        long opening = row(YEAR + "-12-30", nextYear + "-01-05").path("opening").asLong();

        // ‼️고치기 전에는 1,700,000 이었다 — 이월(850,000)에 그해 거래(850,000)를 다시 더했다.
        assertThat(opening).as("기초는 12/29 잔액 하나뿐이다").isEqualTo(850_000);

        // 걸친 기간에 거래가 없으니 잔액도 기초와 같아야 한다.
        assertThat(row(YEAR + "-12-30", nextYear + "-01-05").path("balance").asLong())
                .isEqualTo(850_000);
    }

    @Test
    @DisplayName("★★같은 날짜의 잔액은 시작일을 바꿔도 같다")
    void 시작일이_잔액을_바꾸지_않는다() {
        String end = YEAR + "-12-31";

        long full = row(YEAR + "-01-01", end).path("balance").asLong();
        long half = row(YEAR + "-07-01", end).path("balance").asLong();
        long late = row(YEAR + "-10-01", end).path("balance").asLong();

        // 700,000 − 200,000 + 350,000 = 850,000
        assertThat(full).as("연초부터").isEqualTo(850_000);
        assertThat(half).as("하반기부터 — 같은 12/31 잔액이다").isEqualTo(full);
        assertThat(late).as("10월부터 — 역시 같다").isEqualTo(full);
    }

    @Test
    @DisplayName("★기초는 '시작일 전날까지의 잔액'이다 — 그해 이월만이 아니다")
    void 기초는_시작일_전날까지() {
        JsonNode half = row(YEAR + "-07-01", YEAR + "-12-31");

        // 상반기: 매출 700,000 − 수금 200,000 = 500,000 이 기초로 넘어와야 한다.
        assertThat(half.path("opening").asLong()).isEqualTo(500_000);
        // 기간 내(하반기) 발생분만 따로 잡힌다.
        assertThat(half.path("receivableGen").asLong()).isEqualTo(350_000);
        assertThat(half.path("balance").asLong()).isEqualTo(850_000);
    }

    @Test
    @DisplayName("★★기간 전에만 거래한 거래처도 잔액이 남아 있으면 보인다")
    void 기간전_거래만_있어도_보인다() {
        // 11~12월엔 거래가 없다. 그래도 미수가 남아 있으므로 목록에 있어야 한다 —
        // 빠지면 "11월부터 조회"한 담당자가 받을 돈을 못 본다.
        JsonNode r = row(YEAR + "-11-01", YEAR + "-12-31");

        assertThat(r.path("opening").asLong()).isEqualTo(850_000);
        assertThat(r.path("receivableGen").asLong()).as("기간 내 발생 없음").isZero();
        assertThat(r.path("balance").asLong()).isEqualTo(850_000);
    }

    @Test
    @DisplayName("★★매입(입고)도 그 거래처 원장에 선다 — 매입처가 '수금만 있는 곳'으로 보이면 안 된다")
    void 매입도_원장에_선다() {
        // 레거시 외상매출현황조회.vb:676 — `이월 + 매출 + 반품 + (입고 + 취소) − 수금 = 잔액`.
        // 매입을 빼면 매입처가 매출 0 · 수금만 있는 거래처로 보여 잔액이 크게 음수가 된다
        // (프론트 실측 2026-10-20 A-1: 25곳이 매출 0 · 수금 130~197억, 잔액 −190억).
        Long buyFrom = createId("/masters/clients",
                Map.of("code", "APV" + SFX, "name", "매입처", "type", "NORMAL"));

        // 매입입고 300부 × 단가 1,000 = 300,000
        JsonNode in = post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-03-02", "supplierClientId", buyFrom,
                "inboundType", "PURCHASE", "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "unitCost", 1000, "qty", 300))));
        assertThat(in.path("success").asBoolean()).as("매입입고: %s", in).isTrue();

        // 그 해 말에 매입대금을 정산한다(레거시가 하던 그대로 — 연 1건).
        JsonNode c = post("/closing/collections", Map.of(
                "collDate", YEAR + "-12-30", "partnerId", buyFrom,
                "collType", "CASH", "collAmt", 300_000));
        assertThat(c.path("success").asBoolean()).as("정산 수금: %s", c).isTrue();

        JsonNode row = null;
        for (JsonNode r : data(get("/closing/ar-status?fromDate=" + YEAR + "-01-01&toDate="
                + YEAR + "-12-31&partnerId=" + buyFrom)).path("rows")) {
            if (r.hasNonNull("partnerId") && r.path("partnerId").asLong() == buyFrom) {
                row = r;
            }
        }
        assertThat(row).as("매입만 있는 거래처도 목록에 선다").isNotNull();
        assertThat(row.path("saleAmount").asLong()).as("매출은 없다").isZero();
        assertThat(row.path("purchaseAmount").asLong()).as("매입액이 보인다").isEqualTo(300_000);
        // ★★매입을 안 세면 여기가 −300,000 이 된다. 그게 A-1 에서 본 그림이다.
        assertThat(row.path("balance").asLong()).as("매입과 정산이 상계돼 0").isZero();
    }

    @Test
    @DisplayName("★★사업자번호가 우리와 같아도 채권 원장에서 빼지 않는다 — 레거시에 없는 로직이었다")
    void 자기자신도_원장에_선다() {
        // 2026-10-21에 "사업자번호가 공급자와 같으면 같은 법인이니 제외"로 만들었다가 되돌렸다.
        // 레거시 어디에도 그런 분기가 없다 — 대성교재 외상매출현황은 `(주)대성학력개발연구소`도
        // **매입처로 그냥 집계**한다(외상매출현황조회.vb:650 GROUPING 소계).
        // 한 곳을 임의로 빼면 합계가 설명되지 않고, "왜 이 거래처만 없나"를 아무도 모른다.
        Long self = createId("/masters/clients", Map.of(
                "code", "SELF" + SFX, "name", "우리 다른 사업부", "type", "NORMAL",
                "bizNo", SUPPLIER_BIZ_NO));

        assertThat(post("/closing/collections", Map.of(
                "collDate", YEAR + "-06-10", "partnerId", self,
                "collType", "CASH", "collAmt", 500_000))
                .path("success").asBoolean()).isTrue();

        JsonNode row = null;
        for (JsonNode r : data(get("/closing/ar-status?fromDate=" + YEAR + "-01-01&toDate="
                + YEAR + "-12-31")).path("rows")) {
            if (r.hasNonNull("partnerId") && r.path("partnerId").asLong() == self) {
                row = r;
            }
        }
        assertThat(row).as("사업자번호가 같아도 원장에 선다").isNotNull();
        assertThat(row.path("collected").asLong()).isEqualTo(500_000);
        assertThat(row.path("balance").asLong()).isEqualTo(-500_000);  // 매입 없이 수금만 → 음수
    }

    /**
     * ★<b>10/27 피드백 §7-C로 뒤집힌 테스트다.</b> 예전엔 "매입처 행이 목록에 서고,
     * 매입처 소계 + 매출처 소계 = 합계"를 고정했다(레거시 대성교재 GROUPING 자리).
     * 지금은 <b>목록에서 매입처를 아예 뺀다</b> — 법인 분리(리브커넥스·대성교재) 예정이라
     * 이 법인 합계에 남의 잔액이 섞이면 안 된다(합계 잔액이 30,374,190,444원으로
     * 나오던 원인. 레거시가 보여 주던 값은 2,403,280,543원 = 매출처 소계였다).
     *
     * <p>매입처 판정 자체(입고 유무)·매입액 칸은 그대로다 — 거래처를 지정해 물으면 답한다.
     */
    @Test
    @DisplayName("★매입처는 목록에서 빠진다 — 지정해 물으면 매입액·구분은 그대로 답한다")
    void 매입처_목록에서_제외() {
        // 매입(입고)이 있으면 매입처. ‼️레거시의 `거래처코드 첫 글자 A` 규칙은 쓰지 않는다 —
        // 두 법인을 한 DB에 합친 뒤에는 A 거래처 대부분이 매출처다(실측 425곳 중 267곳).
        // ★매입처를 이 테스트 안에서 만든다 — 다른 테스트가 만든 것에 기대면 실행 순서에 묶인다.
        Long buyFrom = createId("/masters/clients",
                Map.of("code", "APG" + SFX, "name", "소계용 매입처", "type", "NORMAL"));
        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-03-02", "supplierClientId", buyFrom,
                "inboundType", "PURCHASE", "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "unitCost", 1000, "qty", 120))))
                .path("success").asBoolean()).isTrue();

        JsonNode d = data(get("/closing/ar-status?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));

        for (JsonNode r : d.path("rows")) {
            assertThat(r.path("partnerId").asLong())
                    .as("매입처는 목록에 없어야: %s", r).isNotEqualTo(buyFrom);
        }
        // 매입처가 없으니 합계도 매출처만의 합이다 — 둘이 갈리면 어느 쪽이 '합계'인지 알 수 없다.
        assertThat(d.path("total").path("balance").asLong())
                .isEqualTo(d.path("salesSubtotal").path("balance").asLong());

        // 거래처를 지정해 물으면 그 매입처를 그대로 답한다(판정·매입액 칸은 살아 있다).
        JsonNode buyRow = null;
        for (JsonNode r : data(get("/closing/ar-status?fromDate=" + YEAR + "-01-01&toDate="
                + YEAR + "-12-31&partnerId=" + buyFrom)).path("rows")) {
            if (r.hasNonNull("partnerId") && r.path("partnerId").asLong() == buyFrom) {
                buyRow = r;
            }
        }
        assertThat(buyRow).as("지정 조회엔 선다").isNotNull();
        assertThat(buyRow.path("purchaseAmount").asLong()).isEqualTo(120_000);
        assertThat(buyRow.path("partnerKind").asText()).isEqualTo("PURCHASE");
        assertThat(buyRow.path("purchaseQty").asLong()).as("입고수량도 보인다").isPositive();
    }

    @Test
    @DisplayName("★미수금현황과 외상매출장이 같은 잔액을 준다 — 갈리면 둘 다 못 믿는다")
    void 두_화면이_같은_값() {
        String from = YEAR + "-07-01";
        String to = YEAR + "-12-31";

        long status = row(from, to).path("balance").asLong();
        long ledger = data(get("/closing/ar-ledger?partnerId=" + partner
                + "&fromDate=" + from + "&toDate=" + to)).path("closing").asLong();

        assertThat(status).as("미수금현황 = 외상매출장").isEqualTo(ledger);
    }
}
