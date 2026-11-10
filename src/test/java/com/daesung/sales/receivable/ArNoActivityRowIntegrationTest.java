package com.daesung.sales.receivable;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 외상매출현황 — <b>그 기간에 이월·매출·수금·매입이 모두 없는 거래처는 행을 내지 않는다</b>
 * (PM 확정 2026-11-10 "레거시와 같게 해 주세요").
 *
 * <p>레거시도 같다. {@code 외상매출현황조회.vb:177} 의 union 안에 그 거래처 행이 하나도 없으면
 * {@code group by 거래처코드} 가 그룹을 만들지 않아 <b>화면에 아예 안 나온다</b>. 그 union 의
 * 바깥 WHERE 가 {@code (무상 and supRate>0) or (반품 and supRate>0) or (무상·반품·대체 아님)}
 * 이라, <b>공급률 0 무가(교사용·증정)만 있는 거래처</b>는 레거시 화면에 없다.
 *
 * <p>‼️<b>걸러내는 기준은 '잔액 0' 이 아니라 '활동 없음' 이다.</b> 매출 100 · 수금 100 이면
 * 잔액은 0 이지만 그 해에 거래가 있었으므로 남겨야 한다 — 잔액으로 걸러 버리면 "거래는 있었는데
 * 딱 맞게 수금된" 거래처가 장부에서 사라진다. 이 테스트가 지키는 것이 그 구분이다.
 *
 * <p>실측 근거: 운영 2026-01~09 에서 레거시와 다른 2곳이 정확히 이 케이스였다
 * (A30D274 교사용 200부 · A30D294 110부, 금액은 전부 0).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출현황 — 활동 없는 거래처는 행 없음(PM 확정 2026-11-10)")
class ArNoActivityRowIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-NA" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2091;
    private static final String RANGE = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

    private String freeOnlyCode;   // 무가(교사용)만 — 행이 없어야 한다
    private String nettedCode;     // 매출 = 수금 → 잔액 0 이지만 행이 있어야 한다
    private String plainCode;      // 보통 매출 — 대조용

    @BeforeAll
    void seed() {
        token();
        freeOnlyCode = "NAA" + SFX;
        nettedCode = "NAB" + SFX;
        plainCode = "NAC" + SFX;
        Long freeOnly = createId("/masters/clients",
                Map.of("code", freeOnlyCode, "name", "무가만 거래처", "type", "NORMAL"));
        Long netted = createId("/masters/clients",
                Map.of("code", nettedCode, "name", "매출=수금 거래처", "type", "NORMAL"));
        Long plain = createId("/masters/clients",
                Map.of("code", plainCode, "name", "보통 거래처", "type", "NORMAL"));
        Long supplier = createId("/masters/clients",
                Map.of("code", "NAS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "NAW" + SFX, "name", "물류", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of("code", "NAP" + SFX, "name", "검증도서",
                "contentType", "SELF", "price", 10_000, "supplyRate", 70, "salesDivision", "교재"));

        assertThat(post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05",
                "supplierClientId", supplier, "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 5_000, "qty", 1_000))))
                .path("success").asBoolean()).isTrue();

        // ① 무가(교사용) 공급률 0 — 수량만 남고 금액은 0 이다.
        JsonNode free = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-03-10", "partnerId", freeOnly, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "TEACHER_USE",
                        "qty", 200, "unitPrice", 10_000, "supplyRate", 0))));
        assertThat(free.path("success").asBoolean()).as("무가 등록: %s", free).isTrue();

        // ② 매출 70,000 → 같은 금액 수금 → 잔액 0
        assertThat(post("/sales/entries", Map.of(
                "salesDate", YEAR + "-03-11", "partnerId", netted, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 10, "unitPrice", 10_000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
        JsonNode coll = post("/closing/collections", Map.of(
                "collDate", YEAR + "-03-20", "partnerId", netted,
                "collKind", "도서대금", "collType", "CASH", "collAmt", 70_000));
        assertThat(coll.path("success").asBoolean()).as("수금 등록: %s", coll).isTrue();

        // ③ 보통 매출 — 화면이 비지 않았음을 보이는 대조군
        assertThat(post("/sales/entries", Map.of(
                "salesDate", YEAR + "-03-12", "partnerId", plain, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 5, "unitPrice", 10_000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("‼️무가(교사용) 수량만 있는 거래처는 행이 없다 — 레거시 화면과 같다")
    void 무가만_있으면_행없음() {
        assertThat(row(freeOnlyCode))
                .as("공급률 0 무가만 있는 거래처가 행으로 나왔다 — 레거시에는 없는 행이다").isNull();
    }

    @Test
    @DisplayName("‼️매출 = 수금 이라 잔액이 0 이어도 행은 남는다 — '잔액 0' 으로 걸러선 안 된다")
    void 잔액0이어도_거래있으면_행유지() {
        JsonNode r = row(nettedCode);
        assertThat(r).as("그 해에 매출·수금이 있었으므로 장부에 남아야 한다").isNotNull();
        assertThat(r.path("balance").asLong()).isZero();
        assertThat(r.path("receivableGen").asLong()).isEqualTo(70_000L);
        assertThat(r.path("collected").asLong()).isEqualTo(70_000L);
    }

    @Test
    @DisplayName("보통 매출은 그대로 나온다")
    void 보통매출은_유지() {
        JsonNode r = row(plainCode);
        assertThat(r).isNotNull();
        assertThat(r.path("balance").asLong()).isEqualTo(35_000L);
    }

    @Test
    @DisplayName("‼️엑셀도 같은 규칙 — 숨긴 거래처 코드가 파일 안에 남아 있으면 안 된다")
    void 엑셀도_같은규칙() throws Exception {
        var res = getBytes("/closing/ar-status/export" + RANGE);
        assertThat(res.getStatusCode().value()).as("엑셀 응답").isEqualTo(200);
        String text = xlsxText(res.getBody());
        assertThat(text).as("보통 거래처는 엑셀에 있어야 한다").contains(plainCode);
        assertThat(text).as("잔액 0 이어도 거래가 있었으니 엑셀에 있어야 한다").contains(nettedCode);
        assertThat(text).as("무가만 있는 거래처가 엑셀에 남았다").doesNotContain(freeOnlyCode);
    }

    /** xlsx(zip) 안의 xml 을 풀어 한 문자열로 — 셀 값은 sharedStrings.xml 에 들어 있다. */
    private static String xlsxText(byte[] xlsx) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            java.util.zip.ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().endsWith(".xml")) {
                    sb.append(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return sb.toString();
    }

    /**
     * ‼️<b>신규 프로그램에서 취소하면 채권이 제대로 상계되는가</b>(PM 요청 2026-11-10
     * "신규 프로그램에서 새로 취소할 때 상계가 정상인지는 별도로 계속 확인 부탁드립니다").
     *
     * <p>이관분과 구조가 다르다. 레거시는 원매출 행(+)과 취소 행(−)을 <b>둘 다 남겨</b> 상계했고,
     * 이관은 취소 행만 {@code canceled=1} 로 넣어 원매출 행이 남아 과대계상됐다. 신규 등록은
     * <b>행이 하나</b>뿐이고 취소하면 그 행에 {@code canceled} 가 서고, 채권 집계가
     * {@code canceled = false} 를 걸러 읽으므로 <b>그 매출이 집계에서 빠진다</b>.
     *
     * <p>이 테스트가 지키는 것: 취소 뒤 잔액이 <b>취소 전 − 그 매출액</b> 으로 정확히 돌아오고,
     * 활동이 그것뿐이었다면 행 자체가 사라진다(위의 '활동 없음' 규칙과 맞물린다).
     */
    @Test
    @DisplayName("‼️신규 취소는 채권에서 상계된다 — 취소 뒤 잔액이 정확히 되돌아온다")
    void 신규취소_상계정상() {
        Long partner = createId("/masters/clients",
                Map.of("code", "NAD" + SFX, "name", "취소 검증 거래처", "type", "NORMAL"));
        Long supplier = createId("/masters/clients",
                Map.of("code", "NAE" + SFX, "name", "인쇄소2", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses",
                Map.of("code", "NAX" + SFX, "name", "물류2", "type", "MAIN"));
        Long book = createId("/masters/products", Map.of("code", "NAQ" + SFX, "name", "취소검증도서",
                "contentType", "SELF", "price", 10_000, "supplyRate", 70, "salesDivision", "교재"));
        assertThat(post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05",
                "supplierClientId", supplier, "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", book, "unitCost", 5_000, "qty", 100))))
                .path("success").asBoolean()).isTrue();

        // 매출 2건 — 70,000 + 140,000
        long keep = saleId(partner, wh, book, 10);
        long drop = saleId(partner, wh, book, 20);
        JsonNode before = row("NAD" + SFX);
        assertThat(before).isNotNull();
        assertThat(before.path("balance").asLong()).isEqualTo(210_000L);

        // 뒷건을 취소 → 그만큼만 빠진다
        JsonNode c = post("/sales/" + drop + "/cancel?reason=오입력", Map.of());
        assertThat(c.path("success").asBoolean()).as("취소: %s", c).isTrue();
        JsonNode after = row("NAD" + SFX);
        assertThat(after).as("남은 매출이 있으니 행은 유지된다").isNotNull();
        assertThat(after.path("balance").asLong())
                .as("취소분 140,000 이 상계돼야 한다").isEqualTo(70_000L);
        assertThat(after.path("receivableGen").asLong()).isEqualTo(70_000L);

        // 남은 것까지 취소 → 활동이 없어져 행이 사라진다
        assertThat(post("/sales/" + keep + "/cancel?reason=오입력", Map.of())
                .path("success").asBoolean()).isTrue();
        assertThat(row("NAD" + SFX))
                .as("전부 취소되면 그 해 활동이 없으므로 행이 사라져야 한다").isNull();
    }

    private long saleId(Long partner, Long wh, Long book, int qty) {
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", YEAR + "-05-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", qty, "unitPrice", 10_000, "supplyRate", 70))));
        assertThat(r.path("success").asBoolean()).as("매출 등록: %s", r).isTrue();
        // 등록 응답은 매출번호를 주고 id 는 따로 찾는다(다른 테스트도 같은 방식).
        return idOfSale(data(r).path("items").get(0).path("salesNo").asText());
    }

    /** 등록 응답에는 id 가 없다 — 매출번호로 조회해 찾는다(ReviseIntegrationTest 와 같은 방식). */
    private long idOfSale(String salesNo) {
        JsonNode rows = data(get("/sales" + RANGE + "&size=200&keyword=" + salesNo)).path("content");
        for (JsonNode row : rows) {
            if (salesNo.equals(row.path("salesNo").asText())) {
                return row.path("id").asLong();
            }
        }
        throw new AssertionError("매출을 못 찾았다: " + salesNo);
    }

    private JsonNode row(String code) {
        JsonNode res = get("/closing/ar-status" + RANGE + "&keyword=" + code);
        assertThat(res.path("success").asBoolean()).as("조회: %s", res).isTrue();
        for (JsonNode n : res.path("data").path("rows")) {
            if ("DETAIL".equals(n.path("rowType").asText()) && code.equals(n.path("partnerCode").asText())) {
                return n;
            }
        }
        return null;
    }
}
