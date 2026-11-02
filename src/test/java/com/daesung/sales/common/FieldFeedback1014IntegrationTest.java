package com.daesung.sales.common;

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
 * 대성 현장 피드백 2026-10-14 반영분 — B-1 · B-2 · B-3 · B-4 · B-5 · B-6.
 *
 * <p>실무자가 쓰면서 나온 요구라, <b>화면이 기대하는 모양</b>을 여기서 고정한다.
 * (A-1 미수금현황 기간은 {@code ArStatusPeriodIntegrationTest} 에 따로 있다.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("현장 피드백 반영(2026-10-14)")
class FieldFeedback1014IntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-FF" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2108-05-20";
    private static final String RANGE = "?fromDate=2108-01-01&toDate=2108-12-31";

    private Long product;
    private Long warehouse;

    @BeforeAll
    void seed() {
        token();
        warehouse = createId("/masters/warehouses",
                Map.of("code", "FFW" + SFX, "name", "현장검증창고", "type", "MAIN"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "FFP" + SFX);
        b.put("name", "현장검증 교재");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("supplyRate", 70);
        b.put("catCode", "F2108A01");
        b.put("catName", "현장검증분류");
        b.put("salesDivision", "교재");
        product = createId("/masters/products", b);
    }

    @Test
    @DisplayName("★B-1 입고 매입처는 선택 — 비워도 등록된다")
    void 매입처_선택() {
        JsonNode r = post("/stock/inbound", Map.of(
                "processedDate", DATE, "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "unitCost", 1000, "qty", 500))));
        assertThat(r.path("success").asBoolean()).as("매입처 없이 입고: %s", r).isTrue();

        // ‼️다만 **없는 거래처 id 를 보내면 거부**한다 — 조용히 빈칸으로 떨어뜨리면
        //   담당자는 매입처를 골랐다고 믿는데 기록에는 없다.
        JsonNode bad = post("/stock/inbound", Map.of(
                "processedDate", DATE, "supplierClientId", 999_999_999L,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "unitCost", 1000, "qty", 1))));
        assertThat(bad.path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★★B-3 공급가액을 손으로 넣으면 그 값이 저장된다 + 표시가 붙는다")
    void 금액_직접입력() {
        Long partnerA = partner("AMT");
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", 10);
        item.put("supplyAmount", 95_000);   // 계산값 70,000 과 다른 금액

        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partnerA, "warehouseId", warehouse,
                "items", List.of(item)));
        assertThat(r.path("success").asBoolean()).as("직접입력: %s", r).isTrue();

        JsonNode row = find(partnerA, "FFP" + SFX);
        assertThat(row.path("supplyAmount").asLong()).as("계산값 70,000 이 아니라 입력값").isEqualTo(95_000);
        assertThat(row.path("amountOverridden").asBoolean()).as("손으로 고친 표시").isTrue();
        // ★정가·공급률은 입력한 그대로 남는다 — 금액에 맞춰 역산하면 담당자가 적은 조건이 사라진다.
        assertThat(row.path("unitPrice").asInt()).isEqualTo(10_000);
        assertThat(row.path("supplyRate").asInt()).isEqualTo(70);

        // 수정에서도 같은 칸이 먹는다.
        long id = row.path("id").asLong();
        assertThat(patch("/sales/" + id, Map.of("supplyAmount", 88_000))
                .path("success").asBoolean()).isTrue();
        assertThat(find(partnerA, "FFP" + SFX).path("supplyAmount").asLong()).isEqualTo(88_000);
    }

    @Test
    @DisplayName("★★B-4 한 번에 올린 줄들이 같은 등록번호 + 1,2,3 순번을 받는다")
    void 등록_묶음과_순번() {
        Long partnerB = partner("ENT");
        JsonNode r = post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partnerB, "warehouseId", warehouse,
                "items", List.of(
                        line(3), line(5), line(7))));
        assertThat(r.path("success").asBoolean()).as("3줄 등록: %s", r).isTrue();

        JsonNode rows = data(get("/sales" + RANGE + "&size=200&partnerIds=" + partnerB))
                .path("content");
        assertThat(rows).hasSize(3);

        String entryNo = rows.get(0).path("entryNo").asText();
        assertThat(entryNo).as("등록번호가 붙는다").startsWith("E-");

        java.util.List<Integer> seqs = new java.util.ArrayList<>();
        for (JsonNode row : rows) {
            assertThat(row.path("entryNo").asText()).as("세 줄이 같은 묶음").isEqualTo(entryNo);
            seqs.add(row.path("entrySeq").asInt());
        }
        assertThat(seqs).as("입력 순서대로 1,2,3").containsExactlyInAnyOrder(1, 2, 3);

        // 다음 등록은 **다른 묶음번호**를 받는다.
        post("/sales/entries", Map.of("salesDate", DATE, "partnerId", partnerB,
                "warehouseId", warehouse, "items", List.of(line(1))));
        JsonNode after = data(get("/sales" + RANGE + "&size=200&partnerIds=" + partnerB))
                .path("content");
        java.util.Set<String> entries = new java.util.HashSet<>();
        after.forEach(x -> entries.add(x.path("entryNo").asText()));
        assertThat(entries).as("등록이 둘이면 묶음도 둘").hasSize(2);
    }

    @Test
    @DisplayName("★★B-2 순매출 거래처별 — 같은 금액 칸, 매입·이익은 비운다")
    void 순매출_거래처별() {
        Long partnerA = partner("NSA");
        Long partnerB = partner("NSB");
        for (Long pt : List.of(partnerA, partnerB)) {
            post("/sales/entries", Map.of("salesDate", DATE, "partnerId", pt,
                    "warehouseId", warehouse, "items", List.of(line(8))));
        }

        JsonNode byProduct = data(get("/sales/net-summary" + RANGE
                + "&partnerIds=" + partnerA + "," + partnerB)).path("rows");
        JsonNode byPartner = data(get("/sales/net-summary" + RANGE
                + "&groupBy=PARTNER&partnerIds=" + partnerA + "," + partnerB)).path("rows");

        assertThat(byProduct).as("도서별 — 상품 하나").hasSize(1);
        assertThat(byPartner).as("거래처별 — 거래처 둘").hasSize(2);

        // 합계는 같아야 한다 — 같은 자료를 다른 축으로 본 것뿐이다.
        long sumProduct = 0;
        for (JsonNode x : byProduct) {
            sumProduct += x.path("saleAmount").asLong();
        }
        long sumPartner = 0;
        for (JsonNode x : byPartner) {
            sumPartner += x.path("saleAmount").asLong();
            assertThat(x.path("partnerCode").asText()).as("거래처 행엔 거래처코드").isNotBlank();
            // ‼️매입단가는 상품 속성이라 거래처로 묶으면 섞인다 — 비워서 틀린 이익을 안 만든다.
            assertThat(x.hasNonNull("purchaseUnitCost")).as("매입단가는 비운다").isFalse();
            assertThat(x.hasNonNull("profit")).as("이익도 비운다").isFalse();
        }
        assertThat(sumPartner).as("축만 다를 뿐 같은 자료다").isEqualTo(sumProduct);
    }

    @Test
    @DisplayName("★★B-5 수금 여러 줄이 한 전표코드로 묶인다")
    void 수금_묶음() {
        Long partnerA = partner("CLA");
        Long partnerB = partner("CLB");
        JsonNode r = post("/closing/collections/batch", Map.of("lines", List.of(
                Map.of("collDate", DATE, "partnerId", partnerA, "collType", "CASH", "collAmt", 300_000),
                Map.of("collDate", DATE, "partnerId", partnerA, "collType", "CASH", "collAmt", 200_000),
                Map.of("collDate", DATE, "partnerId", partnerB, "collType", "CASH", "collAmt", 100_000))));
        assertThat(r.path("success").asBoolean()).as("묶음 등록: %s", r).isTrue();

        JsonNode lines = data(r);
        assertThat(lines).hasSize(3);
        String voucher = lines.get(0).path("voucherNo").asText();
        assertThat(voucher).as("전표코드가 붙는다").startsWith("V-");

        java.util.Set<String> collNos = new java.util.HashSet<>();
        for (JsonNode l : lines) {
            assertThat(l.path("voucherNo").asText()).as("세 줄이 같은 전표").isEqualTo(voucher);
            collNos.add(l.path("collectionNo").asText());
        }
        // ★수금번호는 줄마다 따로다 — 개별 수정·삭제가 그 번호로 된다.
        assertThat(collNos).as("수금번호는 줄마다 다르다").hasSize(3);
    }

    @Test
    @DisplayName("★★B-6 계산서 작성일자를 바꿔 저장한다 — 키는 그대로")
    void 계산서_작성일자_수정() {
        Long partnerA = partner("TAX");
        // 매출 한 건
        Map<String, Object> item = new HashMap<>();
        item.put("productId", product);
        item.put("shipmentType", "NORMAL_SHIP");
        item.put("unitPrice", 10000);
        item.put("supplyRate", 70);
        item.put("qty", 5);
        post("/sales/entries", Map.of("salesDate", DATE, "partnerId", partnerA,
                "warehouseId", warehouse, "items", List.of(item)));

        String to = "2108-05-31";
        JsonNode before = invoice(partnerA, to);
        assertThat(before.path("issueDate").asText()).as("기본은 조회 종료일").isEqualTo(to);

        // 작성일자만 바꾼다. 키(issueDate)는 그대로 보낸다 — 그게 어느 계산서인지 가리킨다.
        JsonNode saved = put("/closing/tax-invoices/adjustments", Map.of(
                "issueDate", to, "partnerId", partnerA, "taxBucket", "FREE",
                "newIssueDate", "2108-05-25", "reason", "실제 발행일에 맞춤"));
        assertThat(saved.path("success").asBoolean()).as("작성일자 보정: %s", saved).isTrue();

        assertThat(invoice(partnerA, to).path("issueDate").asText())
                .as("바뀐 날짜로 나온다").isEqualTo("2108-05-25");
        // ‼️키가 바뀌지 않아야 같은 조회로 계속 찾힌다(바뀌면 보정을 다시 못 건다).
        assertThat(invoice(partnerA, to).path("adjusted").asBoolean()).isTrue();
    }

    /**
     * 테스트마다 **자기 거래처**를 만든다.
     * ‼️공유하면 다른 테스트의 매출이 집계·조회에 섞여 들어온다 —
     *   실제로 이 파일에서 그렇게 깨졌다(B-3 이 B-6 의 매출을 집었다).
     */
    @Test
    @DisplayName("★★[10/16 B-1] 금액을 안 보내고 수정하면 '손으로 고친 금액' 표시가 내려간다")
    void 금액_직접입력_해제() {
        Long p = partner("OVR");
        Map<String, Object> item = new HashMap<>(line(10));
        item.put("supplyAmount", 95_000);   // 계산값 70,000 과 다른 값
        assertThat(post("/sales/entries", Map.of("salesDate", DATE, "partnerId", p,
                "warehouseId", warehouse, "items", List.of(item)))
                .path("success").asBoolean()).isTrue();

        long id = find(p, "FFP" + SFX).path("id").asLong();
        assertThat(find(p, "FFP" + SFX).path("amountOverridden").asBoolean()).isTrue();

        // 수량만 고친다 — 금액 칸은 비운다. 이 API 규칙은 "보낸 값으로 맞춘다"라
        // 금액은 계산값(20부 × 7,000 = 140,000)으로 돌아간다.
        assertThat(patch("/sales/" + id, Map.of("qty", 20)).path("success").asBoolean()).isTrue();

        JsonNode row = find(p, "FFP" + SFX);
        assertThat(row.path("supplyAmount").asLong()).as("계산값으로 복귀").isEqualTo(140_000);
        // ★★여기가 핵심 — 금액이 계산값인데 표시가 true 로 남아 있으면
        //   화면이 계속 「손으로 고친 금액」으로 그리고, 세무 소명에서 묻는 구분이 틀어진다.
        assertThat(row.path("amountOverridden").asBoolean())
                .as("계산값으로 돌아갔으니 표시도 내려가야 한다").isFalse();

        // 같은 수정에서 금액을 함께 보내면 다시 올라간다.
        assertThat(patch("/sales/" + id, Map.of("qty", 20, "supplyAmount", 99_000))
                .path("success").asBoolean()).isTrue();
        JsonNode again = find(p, "FFP" + SFX);
        assertThat(again.path("supplyAmount").asLong()).isEqualTo(99_000);
        assertThat(again.path("amountOverridden").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★[10/16 B-2] 반품입고도 등록번호·순번을 받는다 — 여러 줄이 한 번에 들어가는 경로다")
    void 반품입고_묶음번호() {
        Long p = partner("RTE");
        // 반품 범위를 만들기 위해 먼저 출고.
        post("/stock/inbound", Map.of("processedDate", DATE, "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", product, "unitCost", 1000, "qty", 100))));
        assertThat(post("/sales/entries", Map.of("salesDate", DATE, "partnerId", p,
                "warehouseId", warehouse, "items", List.of(line(50))))
                .path("success").asBoolean()).isTrue();

        JsonNode r = post("/sales/return-inbound", Map.of(
                "returnDate", DATE, "partnerId", p, "warehouseId", warehouse,
                "items", List.of(
                        Map.of("productId", product, "unitPrice", 10000, "supplyRate", 70, "qty", 3),
                        Map.of("productId", product, "unitPrice", 10000, "supplyRate", 70, "qty", 4))));
        assertThat(r.path("success").asBoolean()).as("반품입고: %s", r).isTrue();

        java.util.List<String> entryNos = new java.util.ArrayList<>();
        java.util.List<Integer> seqs = new java.util.ArrayList<>();
        for (JsonNode row : data(get("/sales" + RANGE + "&size=200&partnerIds=" + p))
                .path("content")) {
            if ("RETURN".equals(row.path("salesCategory").asText())) {
                entryNos.add(row.path("entryNo").asText());
                seqs.add(row.path("entrySeq").asInt());
            }
        }
        assertThat(seqs).as("반품 2줄").hasSize(2);
        assertThat(entryNos).allMatch(e -> e.startsWith("E-"), "등록번호가 붙는다");
        assertThat(entryNos.get(0)).as("두 줄이 같은 묶음").isEqualTo(entryNos.get(1));
        assertThat(seqs).as("1,2 순번").containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("★[10/16 B-3] 작업요청서 — 발송 건이 없는 기간은 매출을 읽지 않는다")
    void 작업요청서_발송없으면_즉시() {
        // 발송 건이 없는 기간(이 클래스의 데이터는 2108년에만 있다).
        // 예전엔 발송이 0건이어도 **요청 기간 내 매출 전량**을 Java 맵으로 올린 뒤 0건을 돌려줬다 —
        // 실데이터 33만 행에서 2020~2035 로 부르면 13초였다(프론트 실측 2026-10-16 B-3).
        long t = System.nanoTime();
        JsonNode r = get("/logistics/work-orders?fromDate=2109-01-01&toDate=2135-12-31");
        long ms = (System.nanoTime() - t) / 1_000_000;

        assertThat(r.path("success").asBoolean()).as("조회: %s", r).isTrue();
        assertThat(data(r).path("content")).as("이 기간엔 발송 건이 없다").isEmpty();
        assertThat(data(r).path("totalElements").asInt()).isZero();
        // 시드 규모에서는 전후 둘 다 빠르다 — 이 테스트가 지키는 것은 "발송이 없으면 끝낸다"는 순서다.
        assertThat(ms).as("발송 0건이면 즉시 끝난다(걸린 시간 %dms)", ms).isLessThan(5_000);
    }

    @Test
    @DisplayName("★★[10/16 B-3] 발송 건이 있으면 매출 줄은 그대로 붙는다 — 빠르게 만들다 빠뜨리면 안 된다")
    void 작업요청서_줄은_그대로() {
        Long p = partner("WRK");
        assertThat(post("/sales/entries", Map.of("salesDate", DATE, "partnerId", p,
                "warehouseId", warehouse, "items", List.of(line(11))))
                .path("success").asBoolean()).isTrue();

        // 요청 기간을 **아주 넓게** 준다(화면이 기간을 비우면 이렇게 부른다).
        // 줄 조회는 발송 건이 걸쳐 있는 날짜로 좁혀지지만, 내용은 빠지지 않아야 한다.
        JsonNode rows = data(get("/logistics/work-orders"
                + "?fromDate=2000-01-01&toDate=2135-12-31&partnerId=" + p)).path("content");
        assertThat(rows).as("발송 건이 잡힌다").isNotEmpty();

        boolean found = false;
        for (JsonNode w : rows) {
            for (JsonNode ln : w.path("lines")) {
                if (("FFP" + SFX).equals(ln.path("productCode").asText())
                        && ln.path("qty").asInt() == 11) {
                    found = true;
                }
            }
        }
        assertThat(found).as("매출 줄(11부)이 작업요청서에 붙어야 한다: %s", rows).isTrue();
    }

    @Test
    @DisplayName("★★[10/20 C-1] 페이지를 이어 붙이면 겹치거나 빠지는 행이 없다")
    void 페이지_경계가_안정적이다() {
        Long p = partner("PAG");
        // 같은 거래일·거래처로 여러 건 — 거래일자만으로 정렬하면 순서가 안 정해지는 모양이다.
        for (int i = 0; i < 7; i++) {
            assertThat(post("/sales/entries", Map.of("salesDate", DATE, "partnerId", p,
                    "warehouseId", warehouse, "items", List.of(line(i + 1))))
                    .path("success").asBoolean()).isTrue();
        }
        // 2건씩 끊어 네 쪽을 받아 id 를 모은다.
        java.util.List<Long> ids = new java.util.ArrayList<>();
        int total = -1;
        for (int page = 0; page < 4; page++) {
            JsonNode d = data(get("/sales" + RANGE + "&partnerIds=" + p + "&size=2&page=" + page));
            if (total < 0) {
                total = d.path("totalElements").asInt();
            }
            for (JsonNode r : d.path("content")) {
                ids.add(r.path("id").asLong());
            }
        }
        // ★★겹치면 중복이 생기고, 빠지면 개수가 모자란다. 정렬이 흔들리면 둘 다 일어난다
        //   (실측: 작업요청서 23쪽에서 매번 한 건이 겹치고 한 건이 빠졌다 — 프론트 2026-10-20 C-1).
        assertThat(ids).as("겹치는 행이 없다").doesNotHaveDuplicates();
        assertThat(ids).as("빠지는 행이 없다 — 전체 %d건", total).hasSize(total);
    }

    @Test
    @DisplayName("★[10/20 C-2] 작업결과·외상매출장 전체가 페이지로 온다 — 전량 반환은 서버를 죽였다")
    void 큰_목록은_페이지로_온다() {
        // 작업결과: 전 기간 13.1MB / 외상매출장 전체: 127MB 였다(2026-10-20 실측, 힙 478MB).
        for (String path : List.of(
                "/logistics/work-results?fromDate=2000-01-01&toDate=2135-12-31&size=5",
                "/closing/ar-ledger/all?fromDate=2000-01-01&toDate=2135-12-31&size=5")) {
            JsonNode d = data(get(path));
            assertThat(d.has("content")).as("페이지 형태: %s", path).isTrue();
            assertThat(d.path("content").size())
                    .as("한 쪽은 요청한 크기를 넘지 않는다: %s", path).isLessThanOrEqualTo(5);
            assertThat(d.has("totalElements")).as("전체 건수를 함께 준다: %s", path).isTrue();
        }
    }

    @Test
    @DisplayName("★★[10/20 B-1] 바뀐 도서만 받는다 — 전량을 30분마다 다시 받지 않아도 된다")
    void 바뀐_도서만() {
        // 기준 시각 전에 도서 하나를 만들어 둔다.
        Map<String, Object> before = new HashMap<>();
        before.put("code", "UPD-OLD" + SFX);
        before.put("name", "기준 전 도서");
        before.put("contentType", "SELF");
        before.put("price", 1000);
        before.put("catCode", "U2108A01");
        before.put("salesDivision", "교재");
        before.put("catName", "갱신검증분류");
        long oldId = createId("/masters/products", before);

        JsonNode oldRow = findProduct(oldId);
        // ★한 번도 안 고친 도서도 기준에 걸려야 한다 — 서버가 등록시각으로 보기 때문이다.
        //   updatedAt 만 보면 **새로 등록된 도서가 영영 안 잡힌다**.
        String since = oldRow.path("updatedAt").isNull() || oldRow.path("updatedAt").asText().isEmpty()
                ? null : oldRow.path("updatedAt").asText();

        // 기준 시각 = 아주 과거로 두면 둘 다 잡혀야 하고, 아주 미래면 아무것도 안 잡혀야 한다.
        assertThat(ids(get("/masters/products?size=200&updatedSince=2000-01-01T00:00:00")))
                .as("과거 기준이면 잡힌다").contains(oldId);
        assertThat(ids(get("/masters/products?size=200&updatedSince=2999-01-01T00:00:00")))
                .as("미래 기준이면 아무것도 안 잡힌다").isEmpty();
        assertThat(since).as("응답에 updatedAt 칸이 있다(없으면 프론트가 기준을 못 만든다)").isNotNull();
    }

    @Test
    @DisplayName("★[10/20 B-2] view=slim 은 가볍게, 기본은 그대로 — 말없이 칸을 빼면 다른 화면이 빈다")
    void 가벼운_도서목록() {
        // ‼️값이 비면 Jackson 이 칸을 통째로 생략한다 — 아무 도서나 집으면 "칸이 없다"로 보인다.
        //   그래서 칸을 **다 채운 도서**를 만들어 그걸로 본다.
        Map<String, Object> b = new HashMap<>(line(1));
        b.remove("productId");
        b.remove("shipmentType");
        b.remove("qty");
        b.put("code", "SLIM" + SFX);
        b.put("name", "가벼운응답 검증도서");
        b.put("contentType", "SELF");
        b.put("price", 12000);
        b.put("supplyRate", 70);
        b.put("catCode", "S2108A01");
        b.put("catName", "가벼운응답분류");
   
        b.put("salesDivision", "교재");     b.put("salesDivision", "교재");
        b.put("grade", "3");
        createId("/masters/products", b);

        String q = "&keyword=SLIM" + SFX;
        JsonNode full = data(get("/masters/products?size=5" + q)).path("content").get(0);
        JsonNode slim = data(get("/masters/products?size=5&view=slim" + q)).path("content").get(0);

        // 화면이 쓰는 칸은 slim 에도 있어야 한다.
        for (String f : List.of("id", "code", "name", "catCode", "salesDivision",
                "useYn", "price", "taxFree", "supplyRate", "contentType",
                "priceVisible", "stockManaged", "updatedAt")) {
            assertThat(slim.has(f)).as("slim 에 %s 가 있어야 한다: %s", f, slim).isTrue();
        }
        // 기본 응답은 칸이 줄지 않았다(다른 화면 보호).
        assertThat(full.size()).as("기본 응답이 slim 보다 칸이 많다").isGreaterThan(slim.size());
        assertThat(full.has("webVisible")).as("기본에는 남아 있다").isTrue();
        assertThat(slim.has("webVisible")).as("slim 에서는 뺀다").isFalse();
    }

    private java.util.List<Long> ids(JsonNode res) {
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (JsonNode r : data(res).path("content")) {
            out.add(r.path("id").asLong());
        }
        return out;
    }

    private JsonNode findProduct(long id) {
        for (JsonNode r : data(get("/masters/products?size=200")).path("content")) {
            if (r.path("id").asLong() == id) {
                return r;
            }
        }
        throw new AssertionError("도서 없음: " + id);
    }

    @Test
    @DisplayName("★★[10/21] 도서의 updatedAt 은 비지 않는다 — 비면 화면이 기준을 못 잡는다")
    void updatedAt_은_비지_않는다() {
        // 한 번도 안 고친 도서도 값이 있어야 한다. 전부 비어 있으면 화면이
        // `updatedSince` 기준 자체를 만들 수 없어 예전 방식(전량 받기)으로 돌아간다.
        Map<String, Object> b = new HashMap<>();
        b.put("code", "UPA" + SFX);
        b.put("name", "수정이력 없는 도서");
        b.put("contentType", "SELF");
        b.put("salesDivision", "교재");
        b.put("price", 1000);
        b.put("catCode", "P2108A01");
        b.put("catName", "갱신기준분류");
        long id = createId("/masters/products", b);

        JsonNode row = null;
        for (JsonNode r : data(get("/masters/products?size=200&keyword=UPA" + SFX)).path("content")) {
            if (r.path("id").asLong() == id) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("updatedAt").isNull()).as("비어 있으면 안 된다: %s", row).isFalse();
        assertThat(row.path("updatedAt").asText()).isNotBlank();

        // ★그 값을 그대로 기준으로 되물으면 자기 자신이 다시 온다(경계 포함).
        //   서버가 거르는 기준과 응답 값이 **같아야** 화면이 다음 기준을 만들 수 있다.
        boolean found = false;
        for (JsonNode r : data(get("/masters/products?size=200&updatedSince="
                + row.path("updatedAt").asText())).path("content")) {
            if (r.path("id").asLong() == id) {
                found = true;
            }
        }
        assertThat(found).as("응답의 updatedAt 을 기준으로 되물으면 잡힌다").isTrue();
    }

    @Test
    @DisplayName("★[10/21] 응시현황은 12개월까지 — 페이징할 수 없는 응답이라 기간으로 막는다")
    void 응시현황_12개월_상한() {
        // 행에 학교 줄과 소계 줄이 섞여 있어 줄 단위로 자를 수 없다.
        // 그래서 기간 제한이 유일한 안전장치다(전 기간 15년이 7.2MB 였다).
        assertThat(get("/sales/attendance-period?fromDate=2108-01-01&toDate=2108-12-31")
                .path("success").asBoolean()).as("12개월은 허용").isTrue();
        JsonNode over = get("/sales/attendance-period?fromDate=2108-01-01&toDate=2109-06-30");
        assertThat(over.path("success").asBoolean()).as("12개월 초과는 거부: %s", over).isFalse();
    }

    private Long partner(String tag) {
        return createId("/masters/clients",
                Map.of("code", tag + SFX, "name", "현장검증" + tag, "type", "NORMAL"));
    }

    private Map<String, Object> line(int qty) {
        Map<String, Object> m = new HashMap<>();
        m.put("productId", product);
        m.put("shipmentType", "NORMAL_SHIP");
        m.put("unitPrice", 10000);
        m.put("supplyRate", 70);
        m.put("qty", qty);
        return m;
    }

    private JsonNode find(Long partner, String productCode) {
        for (JsonNode r : data(get("/sales" + RANGE + "&size=200&partnerIds=" + partner))
                .path("content")) {
            if (productCode.equals(r.path("productCode").asText())) {
                return r;
            }
        }
        throw new AssertionError("매출 없음");
    }

    private JsonNode invoice(Long partnerA, String to) {
        JsonNode d = data(get("/closing/tax-invoices?fromDate=2108-05-01&toDate=" + to
                + "&partnerId=" + partnerA));
        for (JsonNode inv : d.path("invoices")) {
            if ("FREE".equals(inv.path("taxType").asText())) {
                return inv;
            }
        }
        throw new AssertionError("계산서 없음: " + d);
    }
}
