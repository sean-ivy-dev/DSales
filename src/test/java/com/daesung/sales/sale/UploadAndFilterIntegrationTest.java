package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 개발팀 점검(2026-09-11) 서버 결함 회귀 고정.
 *
 * <ol>
 *   <li><b>매출 엑셀 업로드가 구조적으로 100% 실패</b> — 분류코드와 도서코드를 문자열로
 *       이어붙여({@code catCode + code}) 전역 도서코드와 대조했다. {@code K202601BK-K2026-1}
 *       같은 값을 찾게 되어 <b>어떤 마스터로도 맞지 않는다.</b></li>
 *   <li>{@code GET /sales} 에 키워드·창고 필터가 없어 두 화면이 못 씀</li>
 *   <li>{@code GET /stock/ledger} 에 키워드·페이징이 없어 전량 반환</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("업로드 상품조회 · 조회 필터(2026-09-11 점검)")
class UploadAndFilterIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-UF" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2072;
    private static final String CAT = "U" + YEAR + "A";

    private Long partner;
    private Long wh;
    private String bookCode;

    @BeforeAll
    void seed() {
        token();
        partner = createId("/masters/clients", Map.of("code", "UFC" + SFX, "name", "업로드거래처", "type", "NORMAL"));
        Long sup = createId("/masters/clients", Map.of("code", "UFS" + SFX, "name", "인쇄", "type", "NORMAL"));
        wh = createId("/masters/warehouses", Map.of("code", "UFW" + SFX, "name", "업로드창고", "type", "MAIN"));

        bookCode = "UFB" + SFX;
        Map<String, Object> b = new HashMap<>();
        b.put("code", bookCode);
        b.put("name", "업로드도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", CAT);
        b.put("catName", "업로드분류");
        createId("/masters/products", b);

        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-05", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", productId(), "unitCost", 3000, "qty", 500))));

        // ★키워드·창고 필터 검증용 매출은 여기서 직접 만든다.
        //   업로드 테스트가 만든 것에 기대면 실행 순서에 따라 있기도 없기도 하다.
        post("/sales/entries", Map.of("salesDate", YEAR + "-02-10", "partnerId", partner,
                "warehouseId", wh,
                "items", List.of(Map.of("productId", productId(), "shipmentType", "NORMAL_SHIP",
                        "unitPrice", 10000, "supplyRate", 70, "qty", 5))));
    }

    private long productId() {
        for (JsonNode n : data(get("/masters/products?keyword=" + bookCode + "&size=10")).path("content")) {
            if (bookCode.equals(n.path("code").asText())) {
                return n.path("id").asLong();
            }
        }
        throw new IllegalStateException("도서 없음");
    }

    /** 발주처 표준양식 12컬럼. 금액은 정가×공급률×수량과 일치(10000×70%×10 = 70,000). */
    private byte[] sheet(String catCode, String code) {
        return sheet(catCode, code, 70000);
    }

    /** 금액 칸을 바꿔 가며 올린다 — null 이면 금액 칸을 비운다(선택 항목). */
    private byte[] sheet(String catCode, String code, Object amount) {
        String[] head = {"거래일자", "거래처코드", "학교코드", "분류코드", "도서코드", "회차",
                "정가", "공급률(%)", "수량", "금액", "구분(출고유형)", "메모"};
        Object[] row = {YEAR + "-03-05", "UFC" + SFX, "", catCode, code, "",
                10000, 0.7, 10, amount, "정상출고", "테스트"};
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("s");
            Row h = s.createRow(0);
            for (int i = 0; i < head.length; i++) {
                h.createCell(i).setCellValue(head[i]);
            }
            Row r = s.createRow(1);
            for (int i = 0; i < row.length; i++) {
                if (row[i] == null) {
                    r.createCell(i).setBlank();   // 선택 항목(금액)을 비운 경우
                } else if (row[i] instanceof Number n) {
                    r.createCell(i).setCellValue(n.doubleValue());
                } else {
                    r.createCell(i).setCellValue(String.valueOf(row[i]));
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("★분류코드 + 도서코드로 상품을 찾는다 — 이어붙이면 어떤 마스터로도 안 맞는다")
    void 업로드_상품조회() {
        JsonNode d = data(multipart("/sales/upload", sheet(CAT, bookCode), "up.xlsx"));

        assertThat(d.path("imported").asInt()).as("%s", d).isEqualTo(1);
        assertThat(d.path("failed").asInt()).isZero();
    }

    @Test
    @DisplayName("분류코드가 비어도 도서코드 단독으로 찾는다 — 전역 고유코드 체계도 받는다")
    void 도서코드_단독() {
        JsonNode d = data(multipart("/sales/upload", sheet("", bookCode), "up.xlsx"));

        assertThat(d.path("imported").asInt()).as("%s", d).isEqualTo(1);
    }

    @Test
    @DisplayName("★금액이 계산과 달라도 등록한다 — 알럿만 준다(10/27 피드백 §4-15)")
    void 금액_불일치는_경고만() {
        // 정가 10,000 × 공급률 70% × 10부 = 70,000 인데 파일엔 99,999 로 적혀 있다.
        JsonNode d = data(multipart("/sales/upload", sheet(CAT, bookCode, 99999), "up.xlsx"));

        assertThat(d.path("failed").asInt()).as("막으면 안 된다: %s", d).isZero();
        assertThat(d.path("imported").asInt()).as("등록은 돼야: %s", d).isEqualTo(1);
        assertThat(d.path("warned").asInt()).as("경고 건수: %s", d).isEqualTo(1);

        JsonNode line = d.path("lines").get(0);
        assertThat(line.path("result").asText()).isEqualTo("IMPORTED_WITH_WARNING");
        assertThat(line.path("salesNo").asText()).as("매출번호가 나와야(등록된 줄)").isNotEmpty();
        assertThat(line.path("message").asText()).contains("99,999").contains("70,000");

        // ‼️**뒤집힌 단언**(프론트 B-8). 예전엔 "저장값은 서버 계산"을 고정했는데,
        //   그러면 §4-15 가 말한 그 **"예외" 금액이 버려진다**. 이제 파일 값을 그대로 쓴다.
        assertThat(line.path("supplyAmount").asLong()).as("파일 금액이 저장된다").isEqualTo(99_999);

        // 매출 원장에도 파일 값으로 들어가고, **손으로 맞춘 금액**임이 표시된다
        //   (매출등록 창의 직접 입력과 같은 취급).
        // ‼️**매출번호로 집는다** — 같은 도서코드로 다른 테스트도 등록해서,
        //   keyword 로만 찾으면 남의 줄을 집는다(실제로 35,000 짜리를 집었다).
        String salesNo = line.path("salesNo").asText();
        JsonNode sale = data(get("/sales?keyword=" + salesNo + "&size=5")).path("content").get(0);
        assertThat(sale.path("supplyAmount").asLong()).isEqualTo(99_999);
        assertThat(sale.path("amountOverridden").asBoolean())
                .as("계산값과 다르면 override 표시").isTrue();
    }

    @Test
    @DisplayName("금액이 계산과 같으면 override 로 표시하지 않는다 — 전부 노랗게 만들지 않는다")
    void 금액_일치하면_override_아님() {
        // 70,000 = 10,000 × 70% × 10 — 계산과 같다. 담당자가 손으로 맞춘 것이 아니다.
        JsonNode d = data(multipart("/sales/upload", sheet(CAT, bookCode, 70000), "same.xlsx"));
        assertThat(d.path("warned").asInt()).as("%s", d).isZero();

        String salesNo = d.path("lines").get(0).path("salesNo").asText();
        JsonNode sale = data(get("/sales?keyword=" + salesNo + "&size=5")).path("content").get(0);
        assertThat(sale.path("amountOverridden").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("금액이 맞거나 비어 있으면 경고가 없다 — 멀쩡한 줄까지 노랗게 만들지 않는다")
    void 금액_일치하거나_비면_경고없음() {
        JsonNode same = data(multipart("/sales/upload", sheet(CAT, bookCode, 70000), "up.xlsx"));
        assertThat(same.path("warned").asInt()).as("일치: %s", same).isZero();
        assertThat(same.path("lines").get(0).path("result").asText()).isEqualTo("IMPORTED");

        JsonNode blank = data(multipart("/sales/upload", sheet(CAT, bookCode, null), "up.xlsx"));
        assertThat(blank.path("warned").asInt()).as("금액 칸은 선택이다: %s", blank).isZero();
        assertThat(blank.path("imported").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 도서는 행 단위 오류 — 어느 줄인지 분류코드와 함께 알려준다")
    void 없는_도서() {
        JsonNode d = data(multipart("/sales/upload", sheet(CAT, "없는코드"), "up.xlsx"));

        assertThat(d.path("failed").asInt()).isEqualTo(1);
        assertThat(d.path("lines").get(0).path("message").asText()).contains("도서 없음");
    }

    @Test
    @DisplayName("★매출 조회에 키워드가 먹는다 — 거래처명·도서명·도서코드를 함께 훑는다")
    void 매출_키워드() {
        String range = "?startDate=" + YEAR + "-01-01&endDate=" + YEAR + "-12-31&size=100";

        assertThat(data(get("/sales" + range + "&keyword=업로드도서")).path("content"))
                .as("도서명으로").isNotEmpty();
        assertThat(data(get("/sales" + range + "&keyword=" + bookCode)).path("content"))
                .as("도서코드로").isNotEmpty();
        assertThat(data(get("/sales" + range + "&keyword=없는검색어xyz")).path("content"))
                .as("안 맞으면 빈 결과").isEmpty();
    }

    @Test
    @DisplayName("매출 조회에 창고 필터가 먹는다")
    void 매출_창고필터() {
        String range = "?startDate=" + YEAR + "-01-01&endDate=" + YEAR + "-12-31&size=100";

        assertThat(data(get("/sales" + range + "&warehouseIds=" + wh)).path("content")).isNotEmpty();
        assertThat(data(get("/sales" + range + "&warehouseIds=999999")).path("content")).isEmpty();
    }

    @Test
    @DisplayName("★수불부가 페이지 형태로 오고 키워드가 먹는다")
    void 수불부_페이징과_키워드() {
        JsonNode d = data(get("/stock/ledger?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&keyword=" + bookCode + "&page=0&size=10"));

        assertThat(d.has("content")).as("배열이 아니라 페이지 형태여야 한다").isTrue();
        assertThat(d.path("content")).isNotEmpty();
        assertThat(d.path("totalElements").asLong()).isPositive();

        assertThat(data(get("/stock/ledger?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"
                + "&keyword=없는도서xyz")).path("content")).isEmpty();
    }

    @Test
    @DisplayName("★날짜 파라미터는 startDate/fromDate 둘 다 받는다 — 이름이 갈려 조용히 전체조회 되던 문제")
    void 날짜_파라미터_별칭() {
        String a = "?startDate=" + YEAR + "-01-01&endDate=" + YEAR + "-12-31&size=100";
        String b = "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31&size=100";

        long byStart = data(get("/sales" + a)).path("totalElements").asLong();
        long byFrom = data(get("/sales" + b)).path("totalElements").asLong();

        assertThat(byStart).as("기존 이름으로 조회되던 건").isPositive();
        assertThat(byFrom).as("별칭으로도 같은 결과여야 한다").isEqualTo(byStart);

        // ‼️이름이 안 맞으면 400이 아니라 null이 되어 필터가 통째로 사라진다 —
        //   화면은 "조회됐다"고 믿는데 실은 전체기간이다. 그 상태와 구분되는지 본다.
        long narrowed = data(get("/sales?fromDate=" + YEAR + "-03-01&toDate=" + YEAR + "-03-31&size=100"))
                .path("totalElements").asLong();
        assertThat(narrowed).as("좁힌 기간이 실제로 먹어야 한다").isLessThan(byFrom);
    }
}
