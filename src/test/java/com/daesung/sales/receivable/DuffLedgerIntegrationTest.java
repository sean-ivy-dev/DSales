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
 * 외상매출장 '더프모만'(24p) 회귀 고정.
 *
 * <p>정본 24p: "'더프모만' 체크 시 그리드 컬럼 구조 자체가 학교(원)별/학년별/시행월별
 * 세분화 구조로 <b>완전 전환</b>됨(조건부 스키마)".
 *
 * <p>★레거시(외상매출장조회.vb:603)는 시행월·학년·처리를 <b>도서명/분류명 문자열에서 긁었고</b>,
 * 규칙에 안 맞으면 {@code '##ERROR'}를 찍었다. 우리는 셋 다 필드로 갖고 있어 파싱하지 않는다 —
 * 그것이 이 테스트가 지키는 것이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출장 더프모만(24p)")
class DuffLedgerIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-DF" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2043;

    private Long partner;
    private Long mockSet;
    /** 폴백 검증 전용 거래처 — 다른 테스트의 합계(22부)를 건드리지 않으려고 분리한다. */
    private Long fallbackPartner;
    /**
     * 전 거래처 조회 검증용 — <b>같은 기간에</b> 두 번째 거래처가 있어야 한다.
     * ‼️처음엔 이게 없어서, 거래처를 끊는 로직을 통째로 지워도 테스트가 통과했다
     * (기간에 거래처가 하나뿐이면 끊을 일이 없다).
     */
    private Long otherPartner;

    @BeforeAll
    void seed() {
        token();
        Long sup = createId("/masters/clients", Map.of("code", "DFS" + SFX, "name", "인쇄", "type", "NORMAL"));
        partner = createId("/masters/clients", Map.of("code", "DFP" + SFX, "name", "더프거래처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "DFW" + SFX, "name", "물류", "type", "MAIN"));

        // 모의고사 완제품(세트) + 구성 자재 — 시행예정일은 BOM에 있다(V27)
        Long material = createId("/masters/products", Map.of("code", "DFM" + SFX, "name", "시험지",
                "contentType", "SELF", "price", 1000, "salesDivision", "모의고사"));
        // ★더프 판별 = 분류코드 M + A/B/C 계열(레거시 외상매출장조회.vb:668)
        mockSet = createId("/masters/products", Map.of("code", "DFK" + SFX, "name", "모의고사세트",
                "contentType", "SELF", "price", 10000, "supplyRate", 100,
                "grade", "고3", "catCode", "M2043A01", "catName", "더프 고3",
                "salesDivision", "모의고사", "stockManaged", false));
        put("/masters/products/" + mockSet + "/bom", Map.of("components", List.of(Map.of(
                "childProductId", material, "ratio", 1, "round", 3,
                "examDate", YEAR + "-09-05"))));

        // 교재(모의고사 아님) — 섞이면 안 된다
        Long textBook = createId("/masters/products", Map.of("code", "DFT" + SFX, "name", "교재",
                "contentType", "SELF", "price", 10000, "supplyRate", 100,
                "catCode", "H2043H01", "catName", "교재", "salesDivision", "교재"));
        // ‼️모의고사이지만 더프가 아닌 계열(M…D) — 대분류로 걸렀다면 섞여 들어왔을 건이다
        Long nonDuff = createId("/masters/products", Map.of("code", "DFN" + SFX, "name", "비더프모의고사",
                "contentType", "SELF", "price", 10000, "supplyRate", 100,
                "catCode", "M2043D01", "catName", "기타 모의고사", "salesDivision", "모의고사"));
        post("/stock/inbound", Map.of("processedDate", YEAR + "-01-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", textBook, "unitCost", 1000, "qty", 500),
                        Map.of("productId", nonDuff, "unitCost", 1000, "qty", 500))));

        // 같은 달·같은 학교 2건(처리/비처리) + 다른 학교 1건
        sale(YEAR + "-09-10", wh, mockSet, 10, "A고", "GRADED", 3);
        sale(YEAR + "-09-11", wh, mockSet, 5, "A고", null, 3);
        sale(YEAR + "-09-12", wh, mockSet, 7, "B고", "GRADED", 3);
        // 제외돼야 하는 둘: 교재 / 더프가 아닌 모의고사
        sale(YEAR + "-09-13", wh, textBook, 99, "A고", "GRADED", null);
        sale(YEAR + "-09-14", wh, nonDuff, 88, "A고", "GRADED", null);

        // ★같은 기간(2043)의 두 번째 거래처 — 전 거래처 조회가 거래처를 제대로 끊는지 보려면
        //   한 기간에 둘 이상이 있어야 한다. 코드를 'DFZ…'로 둬 정렬에서 우리 뒤에 오게 한다.
        otherPartner = createId("/masters/clients",
                Map.of("code", "DFZ" + SFX, "name", "둘째거래처", "type", "NORMAL"));
        Map<String, Object> other = new java.util.HashMap<>();
        other.put("productId", mockSet);
        other.put("shipmentType", "NORMAL_SHIP");
        other.put("unitPrice", 10000);
        other.put("supplyRate", 100);
        other.put("qty", 5);
        other.put("schoolName", "Z고");
        other.put("procType", "GRADED");
        assertThat(post("/sales/entries", Map.of(
                "salesDate", YEAR + "-09-20", "partnerId", otherPartner,
                "warehouseId", wh, "items", List.of(other))).path("success").asBoolean()).isTrue();

        seedFallback(sup, wh);
    }

    /**
     * 시행월 폴백용 — BOM 시행예정일이 <b>없는</b> 상품 4종.
     * 연도를 2042로 띄워 위 2043 픽스처와 섞이지 않게 한다.
     */
    private void seedFallback(Long sup, Long wh) {
        fallbackPartner = createId("/masters/clients",
                Map.of("code", "DFF" + SFX, "name", "폴백거래처", "type", "NORMAL"));

        record Case(String code, String name, String school) {
        }
        List<Case> cases = List.of(
                new Case("DFF1" + SFX, "11월 고2 수능 (전영역)", "폴백고"),   // 정상
                new Case("DFF2" + SFX, "월례고사", "월례고"),                 // '월'은 있으나 앞이 숫자 아님
                new Case("DFF3" + SFX, "25 고1 국어_1영역", "연도표기고"),     // 25=연도, '월' 없음
                new Case("DFF4" + SFX, "고3 평가고사", "평가고"));             // 시행월 개념 없음

        for (Case c : cases) {
            Long p = createId("/masters/products", Map.of(
                    "code", c.code(), "name", c.name(), "contentType", "SELF",
                    "price", 10000, "supplyRate", 100, "grade", "고3",
                    "catCode", "M2042A01", "catName", "폴백검증",
                    "salesDivision", "모의고사", "stockManaged", false));
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("productId", p);
            item.put("shipmentType", "NORMAL_SHIP");
            item.put("unitPrice", 10000);
            item.put("supplyRate", 100);
            item.put("qty", 1);
            item.put("schoolName", c.school());
            assertThat(post("/sales/entries", Map.of(
                    "salesDate", "2042-05-10", "partnerId", fallbackPartner,
                    "warehouseId", wh, "items", List.of(item)))
                    .path("success").asBoolean()).as("폴백 픽스처 매출: %s", c.name()).isTrue();
        }
    }

    @Test
    @DisplayName("컬럼이 통째로 다르다 — 학교(원)·시행월·학년·처리 세분화")
    void 조건부_스키마() {
        JsonNode d = detail().get(0);

        assertThat(d.path("schoolName").asText()).isEqualTo("A고");
        assertThat(d.path("grade").asText()).as("도서명 파싱이 아니라 상품 마스터 학년").isEqualTo("3");
        // 처리 구분은 **입력된 건만** 값이 있다(미지정은 키가 빠진다 — NON_NULL).
        if (d.hasNonNull("procType")) {
            assertThat(d.path("procType").asText()).isIn("처리", "비처리");
        }
        assertThat(d.path("examMonth").asText())
                .as("BOM 시행예정일에서 온다(레거시는 도서명을 잘라 만들었다)")
                .isEqualTo(YEAR + "-09");
        assertThat(d.path("unitPrice").asInt()).isEqualTo(10000);

        // 기본 장부에 있는 '잔액'은 이 화면에 없다(잔액 개념이 없는 화면이다)
        assertThat(d.hasNonNull("balance")).isFalse();
    }

    @Test
    @DisplayName("더프만 나온다 — 교재도, 더프 아닌 모의고사(M…D)도 빠진다")
    void 더프만() {
        assertThat(detail()).hasSize(3);
        assertThat(data(ledger()).path("totalQty").asLong()).isEqualTo(22);
        // 교재 99가 섞이면 121, 비더프 모의고사 88까지 섞이면 209가 된다.
        // ‼️대분류(모의고사)로 걸렀다면 88이 섞여 110이 나왔을 것 — 더프는 모의고사의 부분집합이다.
    }

    @Test
    @DisplayName("소계가 학교(원) 계 → 월 계 순으로 붙는다")
    void 소계() {
        List<String> types = new java.util.ArrayList<>();
        data(ledger()).path("rows").forEach(r -> types.add(r.path("rowType").asText()));

        assertThat(types).contains("DETAIL", "SCHOOL_SUBTOTAL", "MONTH_SUBTOTAL");
        assertThat(types.get(types.size() - 1)).as("마지막은 월 계").isEqualTo("MONTH_SUBTOTAL");

        // 학교 계 합 = 전체(A고 15 + B고 7)
        long schoolSum = 0;
        for (JsonNode r : data(ledger()).path("rows")) {
            if ("SCHOOL_SUBTOTAL".equals(r.path("rowType").asText())) {
                schoolSum += r.path("qty").asLong();
            }
        }
        assertThat(schoolSum).isEqualTo(22);
    }

    @Test
    @DisplayName("처리/비처리는 성적처리 구분에서 온다 — 분류명 문자열이 아니다")
    void 처리구분() {
        long graded = detail().stream().filter(r -> "처리".equals(r.path("procType").asText()))
                .mapToLong(r -> r.path("qty").asLong()).sum();
        // ‼️**미지정은 비워 둔다.** 예전엔 '비처리'로 찍었는데, 이관 자료는 그 칸이 전부 비어 있어
        //   실제 처리 여부와 무관하게 전건이 '비처리'로 나갔다(프론트 실측 2026-10-26).
        //   NON_NULL 이라 값이 없으면 키 자체가 빠진다.
        long unset = detail().stream().filter(r -> !r.hasNonNull("procType"))
                .mapToLong(r -> r.path("qty").asLong()).sum();

        assertThat(graded).isEqualTo(17);    // 10 + 7
        assertThat(unset).isEqualTo(5);      // 미지정 — '비처리'로 지어내지 않는다
        assertThat(detail().stream().noneMatch(r -> "비처리".equals(r.path("procType").asText())))
                .as("입력하지 않은 건을 '비처리'로 찍지 않는다").isTrue();
    }

    @Test
    @DisplayName("★전 거래처 — 거래처당 원장 하나(PDF 일괄저장의 전제)")
    void 전거래처() {
        // 이 테스트의 두 거래처는 기간이 갈려 있다(본 픽스처 2043 · 폴백 2042).
        // 2043 으로 물으면 본 거래처만, 2042 면 폴백 거래처만 나와야 한다.
        JsonNode all = data(get("/closing/ar-ledger/duff/all"
                + "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));

        JsonNode mine = null;
        JsonNode second = null;
        for (JsonNode l : all) {
            if (partner.equals(l.path("partnerId").asLong())) {
                mine = l;
            }
            if (otherPartner.equals(l.path("partnerId").asLong())) {
                second = l;
            }
        }
        assertThat(mine).as("우리 거래처 원장이 들어 있다").isNotNull();

        // ‼️**거래처가 끊기는지**가 이 테스트의 핵심이다. 같은 기간의 두 거래처가
        //   각자 자기 합계로 떨어져 나와야 한다 — 안 끊으면 한 덩이로 뭉쳐
        //   둘 중 하나가 통째로 사라지고 나머지 하나의 합계가 27(22+5)이 된다.
        assertThat(second).as("같은 기간의 둘째 거래처도 따로 나온다").isNotNull();
        assertThat(second.path("totalQty").asLong()).isEqualTo(5);

        // 단건 조회와 **같은 값**이어야 한다 — 다르면 화면과 일괄발송이 어긋난다.
        assertThat(mine.path("totalQty").asLong()).isEqualTo(22);
        assertThat(mine.path("partnerCode").asText())
                .as("PDF 파일명에 쓰는 거래처코드").isEqualTo("DFP" + SFX);

        // 소계·요약도 단건과 같은 모양으로 붙는다(거래처 경계에서 끊기는지 본다).
        assertThat(mine.path("summary")).isNotEmpty();
        long schoolSum = 0;
        for (JsonNode r : mine.path("rows")) {
            if ("SCHOOL_SUBTOTAL".equals(r.path("rowType").asText())) {
                schoolSum += r.path("qty").asLong();
            }
        }
        assertThat(schoolSum).as("학교 계 합이 거래처 총량과 같다").isEqualTo(22);

        // ‼️폴백 거래처는 2042 라 이 기간에 없어야 한다 — 거래처별로 안 끊으면 섞여 든다.
        for (JsonNode l : all) {
            assertThat(l.path("partnerId").asLong()).isNotEqualTo(fallbackPartner);
        }
    }

    @Test
    @DisplayName("매출 없는 거래처는 안 나온다 — 0원 명세서가 메일로 나가면 사고다")
    void 매출없으면_제외() {
        JsonNode all = data(get("/closing/ar-ledger/duff/all?fromDate=2044-01-01&toDate=2044-12-31"));
        for (JsonNode l : all) {
            assertThat(l.path("partnerId").asLong()).isNotIn(partner, fallbackPartner);
        }
    }

    @Test
    @DisplayName("★시행월 폴백 — BOM이 없으면 도서명 'N월', 연도는 분류코드")
    void 시행월_폴백() {
        // BOM 시행예정일이 없는 상품. 이름이 "11월 …", 분류는 M2042A01 → 2042-11
        JsonNode d = data(get("/closing/ar-ledger/duff?partnerId=" + fallbackPartner
                + "&fromDate=2042-01-01&toDate=2042-12-31"));

        java.util.Map<String, String> byName = new java.util.HashMap<>();
        for (JsonNode r : d.path("rows")) {
            if ("DETAIL".equals(r.path("rowType").asText())) {
                byName.put(r.path("schoolName").asText(), r.path("examMonth").asText(""));
            }
        }
        // ① 정상 — "11월 고2 수능" + M2042A01
        assertThat(byName.get("폴백고")).as("도서명에서 시행월이 나온다").isEqualTo("2042-11");

        // ② ‼️'월'은 있지만 앞이 숫자가 아니다(레거시는 여기서 '월'을 찍었다)
        assertThat(byName.get("월례고")).as("월례고사 같은 이름은 안 찍는다").isEmpty();

        // ③ ‼️"25 고1 국어" — 25는 월이 아니라 연도다. '월' 글자가 없어 안 걸린다
        assertThat(byName.get("연도표기고")).as("25월 같은 값을 만들지 않는다").isEmpty();

        // ④ 시행월 개념이 없는 상품(고3 평가고사) — 빈 칸이 정답
        assertThat(byName.get("평가고")).as("이름에 정보가 없으면 비운다").isEmpty();
    }

    @Test
    @DisplayName("‼️BOM 시행예정일이 있으면 그쪽이 이긴다 — 도서명은 2순위")
    void BOM이_우선() {
        // mockSet 은 BOM examDate 2043-09-05 를 갖고 이름엔 '월'이 없다.
        assertThat(detail().get(0).path("examMonth").asText())
                .as("BOM 시행예정일").isEqualTo(YEAR + "-09");
    }

    @Test
    @DisplayName("‼️거래처명 자리에 코드가 들어가지 않는다 — 인자 순서가 뒤바뀐 적이 있다")
    void 거래처명과_코드가_안바뀐다() {
        // DuffLedgerResponse 는 partnerCode·partnerDsreCode·partnerName 이 **전부 String** 이라
        // 순서를 바꿔 넣어도 **컴파일이 통과한다**. 실제로 name 자리에 DSRE 코드가 들어가
        // 거래처명이 숫자로 나갔다(프론트 실측 2026-10-26, 100곳 중 90곳).
        // PDF 파일명·메일에 그대로 쓰이는 값이라 여기서 고정한다.
        JsonNode d = data(ledger());

        assertThat(d.path("partnerCode").asText()).isEqualTo("DFP" + SFX);
        assertThat(d.path("partnerName").asText()).isEqualTo("더프거래처");
        // 이름 자리에 코드가 들어가면 이 둘이 깨진다.
        assertThat(d.path("partnerName").asText()).isNotEqualTo(d.path("partnerCode").asText());
        assertThat(d.path("partnerName").asText()).doesNotMatch("\\d+");

        // 전 거래처 경로도 같은 조립부를 쓴다 — 함께 본다.
        for (JsonNode l : data(get("/closing/ar-ledger/duff/all"
                + "?fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"))) {
            assertThat(l.path("partnerName").asText())
                    .as("전 거래처 경로에서도 이름 자리에 코드가 오면 안 된다")
                    .doesNotMatch("\\d+");
        }
    }

    @Test
    @DisplayName("★요약표 — 처리 구분별 합계 + 총계(레거시 rollup(처리))")
    void 요약표() {
        JsonNode s = data(ledger()).path("summary");

        // 처리 17 · 비처리 5 두 행 + 총계 1행. SOLID 는 우리 모델에 없으니 행도 없다.
        assertThat(s).hasSize(3);

        long 처리 = 0;
        long 미지정 = 0;
        JsonNode total = null;
        for (JsonNode r : s) {
            if (r.path("total").asBoolean()) {
                total = r;
            } else if ("처리".equals(r.path("procType").asText())) {
                처리 = r.path("qty").asLong();
            } else if (!r.hasNonNull("procType")) {
                미지정 = r.path("qty").asLong();
            }
        }
        assertThat(처리).isEqualTo(17);
        // 미지정도 한 줄로 모인다 — 다만 이름을 '비처리'로 지어내지 않는다.
        assertThat(미지정).isEqualTo(5);

        assertThat(total).as("총계 행이 있다").isNotNull();
        assertThat(total.hasNonNull("procType")).as("총계 행의 처리 칸은 비어 있다").isFalse();
        assertThat(total.path("qty").asLong()).isEqualTo(22);
        assertThat(total.path("qty").asLong()).isEqualTo(data(ledger()).path("totalQty").asLong());
    }

    @Test
    @DisplayName("‼️요약 합 = 명세 합 — 따로 집계해 어긋나는 일이 없어야 한다")
    void 요약과_명세가_같다() {
        long detailAmt = detail().stream().mapToLong(r -> r.path("amount").asLong()).sum();
        long summaryAmt = 0;
        for (JsonNode r : data(ledger()).path("summary")) {
            if (!r.path("total").asBoolean()) {
                summaryAmt += r.path("amount").asLong();
            }
        }
        assertThat(summaryAmt).isEqualTo(detailAmt);
    }

    @Test
    @DisplayName("명세가 없으면 요약도 비운다 — 0원 총계만 찍으면 '0원 팔렸다'로 읽힌다")
    void 빈조회는_총계도_없다() {
        JsonNode d = data(get("/closing/ar-ledger/duff?partnerId=" + partner
                + "&fromDate=2044-01-01&toDate=2044-12-31"));

        assertThat(d.path("rows")).isEmpty();
        assertThat(d.path("summary")).isEmpty();
    }

    private JsonNode ledger() {
        return get("/closing/ar-ledger/duff?partnerId=" + partner
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31");
    }

    private List<JsonNode> detail() {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode r : data(ledger()).path("rows")) {
            if ("DETAIL".equals(r.path("rowType").asText())) {
                out.add(r);
            }
        }
        return out;
    }

    private void sale(String date, Long wh, Long productId, int qty,
                      String school, String procType, Integer round) {
        Map<String, Object> item = new java.util.HashMap<>();
        item.put("productId", productId);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 100);
        item.put("qty", qty);
        item.put("schoolName", school);
        if (procType != null) {
            item.put("procType", procType);
        }
        if (round != null) {
            item.put("round", round);
        }
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", date, "partnerId", partner, "warehouseId", wh,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("매출등록 성공: %s", r).isTrue();
    }
}
