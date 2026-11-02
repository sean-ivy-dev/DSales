package com.daesung.sales.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 폐기수량 <b>일괄 등록</b>(엑셀 업로드).
 *
 * <p>근거: 9/27 회의 항목 8 — "폐기수량 일일이 적어야되는데 일괄 등록 가능하게.
 * 엑셀업로드(수불부 상품코드 + 수량) 적용."
 *
 * <p>★<b>핵심은 '전부 아니면 전무'다.</b> 폐기는 재고를 깎는 전표라 절반만 들어가면
 * 담당자가 파일과 대조해 무엇이 빠졌는지 찾아야 한다. 오류가 하나라도 있으면
 * 아무것도 등록되지 않는다는 것을 여기서 고정한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("폐기 일괄 등록(엑셀, 항목 8)")
class DisposalUploadIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DU" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2093-05-11";

    private Long warehouse;
    private String codeA;
    private String codeB;
    private Long productA;

    @BeforeAll
    void seed() {
        token();
        warehouse = createId("/masters/warehouses",
                Map.of("code", "DUW" + SFX, "name", "폐기업로드창고", "type", "MAIN"));
        Long supplier = createId("/masters/clients",
                Map.of("code", "DUS" + SFX, "name", "인쇄소", "type", "NORMAL"));

        codeA = "DUA" + SFX;
        codeB = "DUB" + SFX;
        productA = book(codeA, "업로드검증 교재A");
        Long productB = book(codeB, "업로드검증 교재B");

        for (Long p : List.of(productA, productB)) {
            post("/stock/inbound", Map.of("processedDate", DATE, "supplierClientId", supplier,
                    "destinationWarehouseId", warehouse,
                    "items", List.of(Map.of("productId", p, "unitCost", 1000, "qty", 1000))));
        }
    }

    private Long book(String code, String name) {
        Map<String, Object> b = new HashMap<>();
        b.put("code", code);
        b.put("name", name);
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "U2093A01");
        b.put("catName", "업로드검증");
        b.put("salesDivision", "교재");
        return createId("/masters/products", b);
    }

    /** 상품코드·수량 두 칸짜리 엑셀을 만든다. 헤더 이름은 담당자가 쓰는 말 그대로. */
    private byte[] xlsx(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("폐기");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("상품코드");
            header.createCell(1).setCellValue("수량");
            header.createCell(2).setCellValue("비고");
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i + 1);
                r.createCell(0).setCellValue(rows[i][0]);
                r.createCell(1).setCellValue(rows[i][1]);
                r.createCell(2).setCellValue(rows[i].length > 2 ? rows[i][2] : "");
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private JsonNode upload(byte[] bytes, boolean dryRun) {
        return multipart("/disposals/upload?processedDate=" + DATE + "&warehouseId=" + warehouse
                + "&dryRun=" + dryRun, bytes, "폐기.xlsx");
    }

    /** 처리일자·창고 없이 파일만 읽는 호출(화면이 그리드를 채울 때 쓴다). */
    private JsonNode parseOnly(byte[] bytes) {
        return multipart("/disposals/upload?dryRun=true", bytes, "폐기.xlsx");
    }

    /** ‼️수불부는 **페이지**로 온다 — content 를 꺼내지 않으면 조용히 0이 나온다. */
    private int stockOf(Long productId) {
        JsonNode rows = data(get("/stock/ledger?fromDate=2093-01-01&toDate=2093-12-31"
                + "&productId=" + productId)).path("content");
        int sum = 0;
        for (JsonNode r : rows) {
            sum += r.path("closing").asInt();
        }
        return sum;
    }

    @Test
    @DisplayName("★여러 줄을 한 전표로 등록한다 — 일일이 치지 않아도 된다")
    void 일괄_등록() throws Exception {
        int before = stockOf(productA);
        JsonNode r = upload(xlsx(new String[][]{
                {codeA, "30", "파본"},
                {codeB, "20", "오염"}}), false);

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("disposalNo").asText()).startsWith("P-");
        assertThat(data(r).path("ok").asInt()).isEqualTo(2);
        assertThat(data(r).path("failed").asInt()).isZero();
        assertThat(stockOf(productA)).as("재고가 깎인다").isEqualTo(before - 30);
    }

    @Test
    @DisplayName("★★한 줄이 틀리면 아무것도 등록되지 않는다 — 절반만 들어가면 되돌리기가 더 어렵다")
    void 하나라도_틀리면_전무() throws Exception {
        int before = stockOf(productA);
        JsonNode r = upload(xlsx(new String[][]{
                {codeA, "10"},
                {"없는코드" + SFX, "5"}}), false);

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).hasNonNull("disposalNo")).as("전표가 만들어지지 않는다").isFalse();
        assertThat(data(r).path("failed").asInt()).isEqualTo(1);
        assertThat(stockOf(productA)).as("정상이던 줄도 반영되지 않는다").isEqualTo(before);

        // 어느 줄이 왜 틀렸는지 알려준다 — 엑셀 행번호가 있어야 그 줄을 찾는다.
        JsonNode err = null;
        for (JsonNode line : data(r).path("lines")) {
            if ("ERROR".equals(line.path("result").asText())) {
                err = line;
            }
        }
        assertThat(err).isNotNull();
        assertThat(err.path("row").asInt()).as("엑셀 행번호").isPositive();
        assertThat(err.path("message").asText()).contains("상품코드");
    }

    @Test
    @DisplayName("수량이 0 이하면 그 줄이 오류 — 조용히 건너뛰지 않는다")
    void 수량_0이하() throws Exception {
        JsonNode r = upload(xlsx(new String[][]{{codeA, "0"}}), false);

        assertThat(data(r).path("failed").asInt()).isEqualTo(1);
        assertThat(data(r).path("lines").get(0).path("message").asText()).contains("1 이상");
    }

    @Test
    @DisplayName("같은 상품이 여러 줄이면 합산해 한 전표로")
    void 같은상품_합산() throws Exception {
        int before = stockOf(productA);
        JsonNode r = upload(xlsx(new String[][]{{codeA, "7"}, {codeA, "3"}}), false);

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(stockOf(productA)).isEqualTo(before - 10);
    }

    @Test
    @DisplayName("dryRun 이면 검증만 한다 — 올리기 전에 파일을 확인할 수 있어야 한다")
    void 검증만() throws Exception {
        int before = stockOf(productA);
        JsonNode r = upload(xlsx(new String[][]{{codeA, "999"}}), true);

        assertThat(data(r).path("ok").asInt()).isEqualTo(1);
        assertThat(data(r).hasNonNull("disposalNo")).isFalse();
        assertThat(stockOf(productA)).as("재고는 그대로").isEqualTo(before);
    }

    @Test
    @DisplayName("★파일만 읽을 땐 처리일자·창고가 없어도 된다 — 아직 어느 창고에서 뺄지 고르기 전이다")
    void 파일만_읽기() throws Exception {
        JsonNode r = parseOnly(xlsx(new String[][]{{codeA, "5"}, {codeB, "7"}}));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        assertThat(data(r).path("ok").asInt()).isEqualTo(2);
        assertThat(data(r).hasNonNull("disposalNo")).as("등록은 일어나지 않는다").isFalse();
        // 화면이 그리드에 채울 수 있게 도서명·수량이 함께 온다.
        assertThat(data(r).path("lines").get(0).path("productName").asText()).isNotEmpty();
        assertThat(data(r).path("lines").get(0).path("qty").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("등록할 땐 처리일자·창고가 필요하다 — 없으면 어디서 뺄지 알 수 없다")
    void 등록엔_필수() throws Exception {
        JsonNode r = multipart("/disposals/upload?dryRun=false",
                xlsx(new String[][]{{codeA, "5"}}), "폐기.xlsx");

        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("message").asText()).contains("처리일자");
    }

    @Test
    @DisplayName("비고를 memo 로 보내도 같은 칸 — 화면 라벨이 '비고'라 헷갈린다")
    void 비고는_두이름() {
        JsonNode r = post("/disposals", Map.of(
                "processedDate", DATE, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", productA, "qty", 3, "memo", "메모로 보냄"))));

        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        String refNo = data(r).path("disposalNo").asText();

        boolean found = false;
        // ‼️자기 상품으로 좁힌다 — 다른 테스트가 넣은 폐기가 쌓이면 첫 페이지 밖으로 밀린다.
        for (JsonNode row : data(get("/disposals?size=200&productIds=" + productA
                + "&fromDate=2093-01-01&toDate=2093-12-31")).path("content")) {
            if (refNo.equals(row.path("disposalNo").asText())) {
                found = true;
                // 등록 시 비고는 사유 칸에 저장된다(레거시도 한 칸이다).
                assertThat(row.path("reason").asText()).isEqualTo("메모로 보냄");
            }
        }
        assertThat(found).as("등록된 폐기를 찾을 수 있어야 한다").isTrue();
    }
}
