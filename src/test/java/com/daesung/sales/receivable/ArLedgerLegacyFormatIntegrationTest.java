package com.daesung.sales.receivable;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 외상매출장(일반) 레거시 양식 맞추기 — 10/27 검수 피드백 §7-A.
 *
 * <p>근거는 발주처가 보낸 <b>특약점 배포용 실물 PDF</b>
 * ({@code SALES_10003_강남 하람도서_20260101-20260831.pdf}, 2026-10-02 수신)다. 그 인쇄물이
 * 이 화면의 정답지다 — 컬럼 13개, 수금 행의 분류명 칸에 "현금", 하단에 회사명·입금계좌.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>같은 날·같은 도서의 매출·교사용·반품이 <b>한 행</b>으로 합쳐진다</li>
 *   <li>값이 없는 행(증정 등)은 아예 만들지 않는다</li>
 *   <li>수금 행은 <b>분류명 칸</b>에 수금 방법("현금")을 쓴다</li>
 *   <li>'구분'(kind) 칸은 응답에서 빠졌다 — 거래처코드·거래처명과 함께 제목으로 올라갔다</li>
 *   <li>헤더에 거래처코드·공급자명·입금계좌가 실린다(인쇄물 제목·하단용)</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출장 레거시 양식(10/27 피드백 §7-A)")
class ArLedgerLegacyFormatIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-LG" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2089;
    private static final String DAY = YEAR + "-05-20";

    private Long partner;
    private String partnerCode;
    private Long book;
    private Long wh;
    /** ‼️B-9 테스트 전용 거래처 — 기존 테스트가 **전체 기간**을 조회하므로
     *  같은 거래처에 줄을 더하면 그쪽 단언(명세행 1줄)이 깨진다. */
    private Long partnerB9;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "LGS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partnerCode = "LGP" + SFX;
        partner = createId("/masters/clients",
                Map.of("code", partnerCode, "name", "레거시양식거래처", "type", "NORMAL"));
        wh = createId("/masters/warehouses", Map.of("code", "LGW" + SFX, "name", "물류", "type", "MAIN"));
        book = createId("/masters/products", Map.of("code", "LGB" + SFX, "name", "양식도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 70,
                "catCode", "A" + YEAR + "A", "catName", "양식분류"));
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 3000, "qty", 500))));

        // ★같은 날·같은 도서로 매출 10부 + 교사용 2부 + 증정 3부 → 한 행으로 합쳐져야 한다.
        //   (증정은 공급률 0이라 어느 칸에도 안 들어가므로 행이 생기면 안 된다)
        post("/sales/entries", Map.of("salesDate", DAY, "partnerId", partner, "warehouseId", wh,
                "items", List.of(
                        Map.of("productId", book, "shipmentType", "NORMAL_SHIP", "qty", 10,
                                "unitPrice", 10000, "supplyRate", 70),
                        Map.of("productId", book, "shipmentType", "TEACHER_USE", "qty", 2,
                                "unitPrice", 10000, "supplyRate", 70),
                        Map.of("productId", book, "shipmentType", "GIFT", "qty", 3,
                                "unitPrice", 10000, "supplyRate", 0))));

        partnerB9 = createId("/masters/clients",
                Map.of("code", "LGP9" + SFX, "name", "B9전용거래처", "type", "NORMAL"));

        // 수금 1건 — 분류명 칸에 "현금"이 찍혀야 한다.
        post("/closing/collections", Map.of("collDate", DAY, "partnerId", partner,
                "collType", "CASH", "collAmt", 50_000));
    }

    private JsonNode ledger() {
        return data(get("/closing/ar-ledger?partnerId=" + partner
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
    }

    private List<JsonNode> details() {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode l : ledger().path("lines")) {
            if ("DETAIL".equals(l.path("rowType").asText())) {
                out.add(l);
            }
        }
        return out;
    }

    @Test
    @DisplayName("★같은 날 같은 도서의 매출·교사용은 한 행으로 합쳐진다")
    void 매출_교사용_한행() {
        List<JsonNode> rows = details().stream()
                .filter(l -> l.path("productName").asText().startsWith("양식도서"))
                .toList();

        assertThat(rows).as("도서 명세행은 한 줄이어야: %s", rows).hasSize(1);
        JsonNode r = rows.get(0);
        assertThat(r.path("saleQty").asLong()).isEqualTo(10);
        assertThat(r.path("saleAmount").asLong()).isEqualTo(10 * 7000);
        assertThat(r.path("teacherQty").asLong()).as("같은 행에 교사용도 함께: %s", r).isEqualTo(2);
        assertThat(r.path("catName").asText()).isEqualTo("양식분류");
        assertThat(r.path("supplyRate").asInt()).isEqualTo(70);
    }

    @Test
    @DisplayName("★값이 없는 행(증정)은 만들지 않는다 — 예전엔 빈 줄이 따로 생겼다")
    void 빈행_없음() {
        for (JsonNode r : details()) {
            boolean anyValue = r.hasNonNull("saleQty") || r.hasNonNull("teacherQty")
                    || r.hasNonNull("returnQty") || r.hasNonNull("collectAmount");
            assertThat(anyValue).as("빈 명세행이 있다: %s", r).isTrue();
        }
    }

    @Test
    @DisplayName("★수금 행은 분류명 칸에 수금 방법('현금')을 쓴다")
    void 수금행_분류명() {
        JsonNode coll = details().stream()
                .filter(l -> l.hasNonNull("collectAmount"))
                .findFirst().orElseThrow(() -> new AssertionError("수금 행 없음"));

        assertThat(coll.path("catName").asText()).isEqualTo("현금");
        assertThat(coll.path("collectAmount").asLong()).isEqualTo(50_000);
        // 레거시 인쇄물은 수금 행의 도서명 칸이 비어 있다(예전엔 여기에 "수금(현금)"을 넣었다).
        assertThat(coll.hasNonNull("productName")).as("도서명 칸은 비어야: %s", coll).isFalse();
    }

    @Test
    @DisplayName("'구분'(kind) 칸은 응답에서 빠졌다 — 거래처코드·거래처명과 함께 제목으로 올라감")
    void 구분칸_제거() {
        for (JsonNode l : ledger().path("lines")) {
            assertThat(l.has("kind")).as("kind 칸이 남아 있다: %s", l).isFalse();
        }
    }

    @Test
    @DisplayName("★제목·하단용 헤더 — 거래처코드·공급자명·입금계좌가 실린다")
    void 헤더_인쇄정보() {
        JsonNode h = ledger();

        assertThat(h.path("partnerCode").asText()).isEqualTo(partnerCode);
        assertThat(h.path("partnerName").asText()).isEqualTo("레거시양식거래처");
        assertThat(h.path("supplierName").asText()).as("하단 왼쪽 회사명").isNotEmpty();
        assertThat(h.path("bankAccount").asText()).as("하단 오른쪽 입금계좌").contains("은행");
    }

    @Test
    @DisplayName("★전표가 다르면 매출 줄도 갈린다 — 구분끼리만 합친다(B-9①)")
    void 전표가_다르면_안합친다() {
        // ‼️뒤집힌 지점. 예전엔 (일자+도서+공급률)로만 묶어 **매출 두 건까지 합쳤다** —
        //   발주처 샘플 PDF 의 2/10 독재자학원 36부·4부가 두 줄인데 40부 한 줄로 나왔다.
        //   §7-A 가 합치라고 한 것은 "같은 도서의 **매출·교사용·반품**"이지 전표끼리가 아니다.
        String day = YEAR + "-07-07";
        for (int qty : new int[] {36, 4}) {
            JsonNode r = post("/sales/entries", Map.of("salesDate", day, "partnerId", partnerB9,
                    "warehouseId", wh,
                    "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                            "qty", qty, "unitPrice", 10000, "supplyRate", 70))));
            assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        }

        JsonNode led = data(get("/closing/ar-ledger?partnerId=" + partnerB9
                + "&fromDate=" + day + "&toDate=" + day));
        List<Long> qtys = new ArrayList<>();
        for (JsonNode l : led.path("lines")) {
            if ("DETAIL".equals(l.path("rowType").asText()) && !l.path("saleQty").isNull()) {
                qtys.add(l.path("saleQty").asLong());
            }
        }
        assertThat(qtys).as("36부·4부가 두 줄로 남는다(40부 한 줄이 아니다)")
                .containsExactlyInAnyOrder(36L, 4L);
    }

    @Test
    @DisplayName("매출 줄의 세액은 0이라도 0으로 찍는다 — 빈칸이 아니다(B-9②)")
    void 세액은_0으로_찍는다() {
        String day = YEAR + "-07-08";
        JsonNode reg = post("/sales/entries", Map.of("salesDate", day, "partnerId", partnerB9,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 5, "unitPrice", 10000, "supplyRate", 70))));
        assertThat(reg.path("success").asBoolean()).as("%s", reg).isTrue();

        JsonNode led = data(get("/closing/ar-ledger?partnerId=" + partnerB9
                + "&fromDate=" + day + "&toDate=" + day));
        JsonNode detail = null;
        for (JsonNode l : led.path("lines")) {
            if ("DETAIL".equals(l.path("rowType").asText()) && !l.path("saleQty").isNull()) {
                detail = l;
            }
        }
        assertThat(detail).as("거래 줄이 있다").isNotNull();
        // 0 은 값이지 없음이 아니다 — 빈칸으로 두면 "세액을 안 채웠다"로 읽힌다.
        assertThat(detail.path("tax").isNull()).as("세액이 null 이면 안 된다").isFalse();
        assertThat(detail.path("tax").asLong()).isZero();
    }
}
