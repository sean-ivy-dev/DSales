package com.daesung.sales.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

/**
 * 엑셀 다운로드 전수 검증.
 *
 * <p><b>왜 따로 만들었나</b>: 지금까지 리포트를 만들 때 데이터 API만 테스트로 고정하고
 * export는 빼 왔다. "같은 데이터를 형식만 바꾼 것"이라 여겼는데, 실제로는 그렇지 않았다 —
 * 응시현황 엑셀이 <b>헤더 31칸은 멀쩡한데 월별 값 24칸이 통째로 비어</b> 나온 적이 있다
 * (ExcelExportUtil이 {@code monthlyQty[0]} 같은 표기를 못 읽었다).
 * 눈으로 열어봐서 찾았고, 테스트가 있었으면 즉시 잡혔을 결함이다.
 *
 * <p>그래서 이 테스트의 핵심은 "200이 왔다"가 아니라 <b>"칸에 값이 실제로 들어 있다"</b>이다.
 *
 * <p>DSRE 의존 export(물류비·매출일괄등록)는 테스트에서 연동이 꺼져 있어 대상이 아니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("엑셀 다운로드")
class ExcelExportIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-XL" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2033;

    private Long partner;
    private Long product;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "XLS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "XLP" + SFX, "name", "엑셀거래처",
                "type", "NORMAL", "cityName", "서울"));
        Long wh = createId("/masters/warehouses", Map.of("code", "XLW" + SFX, "name", "엑셀창고", "type", "MAIN"));
        product = createId("/masters/products", Map.of("code", "XLB" + SFX, "name", "엑셀도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 75, "salesDivision", "교재",
                "catCode", "A2033A1", "catName", "국어"));

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", product, "unitCost", 3000, "qty", 1000))));
        post("/sales/entries", Map.of("salesDate", YEAR + "-03-10", "partnerId", partner,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", product, "shipmentType", "NORMAL_SHIP",
                        "qty", 100, "tax", 75_000))));
        post("/closing/collections", Map.of("collDate", YEAR + "-03-20", "writeDate", YEAR + "-03-31",
                "partnerId", partner, "collType", "CASH", "collAmt", 300_000));
    }

    // ── 검증 도우미 ─────────────────────────────────────────────────────────

    /** xlsx를 표(행×열)로 읽는다. 파싱 자체가 실패하면 파일이 깨진 것이다. */
    private List<List<Object>> read(String path) {
        ResponseEntity<byte[]> res = getBytes(path);
        assertThat(res.getStatusCode().value()).as("HTTP %s", path).isEqualTo(200);
        assertThat(res.getBody()).as("본문 %s", path).isNotNull();

        List<List<Object>> table = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(res.getBody()))) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row row : sheet) {
                List<Object> cells = new ArrayList<>();
                for (int i = 0; i < row.getLastCellNum(); i++) {
                    Cell c = row.getCell(i);
                    cells.add(switch (c == null ? Cell.class.getSimpleName() : c.getCellType().name()) {
                        case "STRING" -> c.getStringCellValue();
                        case "NUMERIC" -> c.getNumericCellValue();
                        case "BOOLEAN" -> c.getBooleanCellValue();
                        default -> null;
                    });
                }
                table.add(cells);
            }
        } catch (Exception e) {
            throw new AssertionError("xlsx 파싱 실패: " + path, e);
        }
        assertThat(table).as("헤더조차 없음 %s", path).isNotEmpty();
        return table;
    }

    /**
     * 헤더 행의 위치. 리포트에는 위에 <b>제목·조회기준 2행</b>이 붙을 수 있다
     * (재무팀 실파일 형식 — 프론트 전달 2026-08-20 §A-2).
     * 그 행들은 <b>첫 칸에만 값이 있고 나머지는 빈</b> 모양이라 그걸로 가려낸다.
     *
     * <p>0행 고정으로 두면 제목을 붙이는 리포트마다 테스트가 깨지고,
     * 그때 "형식이 바뀐 것"인지 "값이 사라진 것"인지 구분이 안 된다.
     */
    private static int headerRow(List<List<Object>> table) {
        for (int i = 0; i < table.size(); i++) {
            List<Object> r = table.get(i);
            long filled = r.stream().filter(java.util.Objects::nonNull).count();
            if (filled > 1) {
                return i;
            }
        }
        return 0;
    }

    /** 헤더가 한글로 채워져 있는지(빈 헤더는 화면에서 이름 없는 칸이 된다). */
    private void assertHeader(List<List<Object>> table, String path, String... expected) {
        List<Object> header = table.get(headerRow(table));
        assertThat(header).as("헤더 %s", path).isNotEmpty();
        assertThat(header).as("빈 헤더 칸 %s", path).doesNotContainNull();
        for (String h : expected) {
            assertThat(header).as("헤더에 '%s' 없음 %s", h, path).contains(h);
        }
    }

    /**
     * 데이터 행이 있고, <b>그 행의 칸이 실제로 채워져</b> 있는지.
     *
     * <p>비어 있는 칸 수를 세는 이유: 응시현황 결함이 정확히 "행은 있는데 대부분 칸이 null"이었다.
     * "행이 하나라도 있으면 통과"로 두면 그 결함을 못 잡는다.
     */
    private void assertFilled(List<List<Object>> table, String path, int minFilled) {
        int h = headerRow(table);
        assertThat(table.size()).as("데이터 행 없음 %s", path).isGreaterThan(h + 1);
        // ★**가장 많이 찬 행**으로 본다. 첫 행만 보면 장부류가 걸린다 —
        //   외상매출장은 첫 줄이 '이월 금액' 집계행이라 칸이 거의 비어 있는 게 정상이다
        //   (특약점에 나가는 PDF 실물도 그렇다). 검사 의도는 "값이 실제로 실리는가"지
        //   "첫 줄이 명세행인가"가 아니다.
        List<Object> best = table.subList(h + 1, table.size()).stream()
                .max(java.util.Comparator.comparingLong(
                        r -> r.stream().filter(java.util.Objects::nonNull).count()))
                .orElseThrow();
        long filled = best.stream().filter(java.util.Objects::nonNull).count();
        assertThat(filled).as("데이터 행이 대부분 비어 있음 %s → %s", path, best)
                .isGreaterThanOrEqualTo(minFilled);
    }

    // ── 매출 리포트 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("매출 리포트 6종 — 헤더·값이 채워진다")
    void 매출_리포트() {
        String range = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31";

        var stmt = read("/sales/statement/export" + range);
        assertHeader(stmt, "매출액명세서", "분류코드", "도서명", "금액");
        assertFilled(stmt, "매출액명세서", 4);

        var net = read("/sales/net-summary/export" + range);
        assertHeader(net, "순매출조회", "상품코드", "상품명");
        assertFilled(net, "순매출조회", 4);

        var cat = read("/sales/category-summary/export" + range + "&partnerId=" + partner);
        assertHeader(cat, "과목별매출현황", "거래처명", "도서명");
        assertFilled(cat, "과목별매출현황", 4);

        var inout = read("/sales/book-inout/export" + range);
        assertHeader(inout, "도서입출고현황", "도서코드", "도서명");
        assertFilled(inout, "도서입출고현황", 4);

        var yoy = read("/sales/yoy-comparison/export" + range + "&groupBy=PARTNER");
        assertHeader(yoy, "매출대비표");
        assertFilled(yoy, "매출대비표", 3);

        var tx = read("/sales/transaction-statement/export" + range + "&partnerId=" + partner);
        assertHeader(tx, "거래명세서", "품명", "도서코드");
        assertFilled(tx, "거래명세서", 3);
    }

    @Test
    @DisplayName("월별매출액명세서 · 응시현황 — 크로스탭 칸이 비지 않는다")
    void 크로스탭_리포트() {
        var monthly = read("/sales/monthly-statement/export?year=" + YEAR + "&month=3");
        assertHeader(monthly, "월별매출액명세서", "구분");
        assertFilled(monthly, "월별매출액명세서", 4);

        // ★이 화면이 실제로 깨졌던 곳이다 — 헤더 31칸 중 월별 24칸이 통째로 비어 나왔다.
        var att = read("/sales/attendance-yearly/export?year=" + YEAR);
        assertHeader(att, "응시현황", "특약점코드", "1월[수량]", "12월[매출]", "합계[수량]");
        assertThat(att.get(0)).as("응시현황 컬럼 수").hasSize(31);
        assertFilled(att, "응시현황", 10);

        // 3월 매출을 넣었으므로 3월[수량] 칸에 값이 있어야 한다(칸 위치까지 확인).
        int idx = att.get(0).indexOf("3월[수량]");
        assertThat(idx).as("3월[수량] 컬럼 위치").isGreaterThan(0);
        boolean any = att.stream().skip(1)
                .anyMatch(r -> idx < r.size() && r.get(idx) instanceof Number n && n.longValue() == 100);
        assertThat(any).as("3월[수량]=100이 실제 칸에 들어 있어야 한다").isTrue();
    }

    // ── 마감·세무 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("마감·세무 리포트 — 헤더·값이 채워진다")
    void 마감_세무() {
        var rev = read("/closing/revenue-report/export?taxType=TAXABLE&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31");
        assertHeader(rev, "수익신고", "거래처명");
        assertFilled(rev, "수익신고", 3);
        // ★월 가로펼침(레거시 수익신고.vb:295). 픽스처 매출이 YEAR-03 한 건이라 그 달 칸이 선다.
        //   예전 엑셀은 총계 7칸뿐이라 화면과 다른 표가 내려갔다.
        assertHeader(rev, "수익신고", "순번", "건수(33.03)", "순매출금액(33.03)", "세액(33.03)");

        var filing = read("/closing/tax-filing/export?fromYearMonth=" + YEAR + "-01&toYearMonth=" + YEAR + "-12");
        assertHeader(filing, "계산서월별신고", "월");
        assertFilled(filing, "계산서월별신고", 4);

        var adj = read("/closing/invoice-adjustments/export?year=" + YEAR + "&month=3");
        assertHeader(adj, "계산서10일분기");

        var arStatus = read("/closing/ar-status/export?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31");
        assertHeader(arStatus, "미수금현황", "거래처명");
        assertFilled(arStatus, "미수금현황", 4);

        // ★담보보기 전용 export(6단계) — 상품군 분해 칸은 없고 담보만기·담보내용이 있어야 한다.
        //   이 둘은 응답 DTO엔 있었지만 지금까지 어느 엑셀에도 안 나가고 있었다.
        var arStatusCollateral = read("/closing/ar-status/export/collateral?fromDate="
                + YEAR + "-01-01&toDate=" + YEAR + "-12-31");
        assertHeader(arStatusCollateral, "미수금현황(담보보기)", "거래처명", "담보만기", "담보내용");
        assertFilled(arStatusCollateral, "미수금현황(담보보기)", 4);

        var arLedger = read("/closing/ar-ledger/export?partnerId=" + partner
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31");
        assertHeader(arLedger, "외상매출장");
        assertFilled(arLedger, "외상매출장", 3);
    }

    @Test
    @DisplayName("계산서 홈택스 양식 — 제출 서식이라 형식이 깨지면 신고가 반려된다")
    void 홈택스_계산서() {
        // TaxInvoiceExcelExporter는 지금까지 검증이 전혀 없었다(커버리지 0%).
        // 다른 export와 달리 '우리 화면용'이 아니라 국세청 제출 양식이라 형식이 곧 요건이다.
        var tax = read("/closing/tax-invoices/export?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31");
        assertThat(tax).as("홈택스 양식이 비어 있음").isNotEmpty();
        assertThat(tax.get(0)).as("헤더 칸 수").isNotEmpty();
    }

    // ── 재고·물류·위탁 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("재고·물류·위탁 리포트 — 헤더·값이 채워진다")
    void 재고_물류() {
        var ledger = read("/stock/ledger/export?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31");
        assertHeader(ledger, "제품수불부", "도서코드");
        assertFilled(ledger, "제품수불부", 4);

        var work = read("/logistics/work-results/export?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31");
        assertHeader(work, "작업결과", "거래처명", "Box");
        assertFilled(work, "작업결과", 4);

        var settle = read("/consignment/settlement-statement/export"
                + "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31&partnerId=" + partner);
        assertHeader(settle, "정산내역서");
    }

    // ── 기초정보·감사 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("기초정보·감사 목록 — 헤더·값이 채워진다")
    void 기초정보_감사() {
        var clients = read("/masters/clients/export?keyword=XLP" + SFX);
        assertHeader(clients, "거래처목록", "거래처코드", "거래처명", "사업자주민번호");
        assertFilled(clients, "거래처목록", 2);

        var products = read("/masters/products/export?keyword=XLB" + SFX);
        assertHeader(products, "도서목록", "도서코드", "도서명");
        assertFilled(products, "도서목록", 4);

        assertHeader(read("/masters/warehouses/export"), "창고목록", "창고코드");
        assertHeader(read("/masters/schools/export"), "학교목록");
        assertHeader(read("/masters/clients/collateral-expiry/export"), "담보만기");
        assertHeader(read("/masters/person-members/export"), "개인회원");
        assertHeader(read("/audit/status-history/export"), "상태변경이력");

        var changes = read("/audit/master-changes/export");
        assertHeader(changes, "기초정보 변경이력", "변경자", "이전 값", "이후 값");
    }

    @Test
    @DisplayName("거래처 엑셀에도 사업자주민번호가 마스킹된다")
    void 마스킹_엑셀() {
        // 화면에서만 가리면 엑셀 다운로드로 그대로 새어나간다 — 그 경로를 여기서 막는다.
        Long id = createId("/masters/clients", Map.of("code", "XLM" + SFX, "name", "마스킹처",
                "type", "NORMAL"));
        put("/masters/clients/" + id, Map.of("name", "마스킹처", "type", "NORMAL",
                "bossId", "800101-1234567"));

        var rows = read("/masters/clients/export?keyword=XLM" + SFX);
        int idx = rows.get(0).indexOf("사업자주민번호");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        for (int i = 1; i < rows.size(); i++) {
            Object v = idx < rows.get(i).size() ? rows.get(i).get(idx) : null;
            if (v != null) {
                assertThat(v.toString()).as("엑셀에 원본이 나가면 안 된다")
                        .doesNotContain("800101-1234567").contains("*");
            }
        }
    }
}
