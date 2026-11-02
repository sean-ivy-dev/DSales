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
 * 채권(미수금현황·수금·외상매출장) + 매출 대시보드 통합테스트(백로그 보강).
 * 러닝밸런스(이월+채권발생−수금)·담보비율·목표대비 실적(순매출)·달성률 회귀 고정.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("채권·대시보드 통합테스트")
class ReceivableDashboardIntegrationTest extends IntegrationTestSupport {

    @BeforeAll
    void auth() {
        token();
    }

    @Test
    @DisplayName("미수금현황 — 매출→채권발생, 수금→잔액 차감, 담보비율")
    void 미수금현황_수금() {
        Long supplier = createId("/masters/clients", Map.of("code", "AR-SUP", "name", "입고공급사", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "AR-WH", "name", "채권창고", "type", "MAIN"));
        Long p = createId("/masters/products",
                Map.of("code", "AR-BK", "name", "채권도서", "contentType", "SELF", "price", 10000,
                "salesDivision", "교재"));
        Long partner = createId("/masters/clients", Map.of("code", "AR-CUST", "name", "채권거래처", "type", "NORMAL"));
        // 담보 200,000 설정
        put("/masters/clients/" + partner, Map.of(
                "name", "채권거래처", "type", "NORMAL", "assureAmount", 200_000, "assureExpiry", "2027-12-31"));
        inbound(wh, supplier, p);

        // 외상매출: 정가10000 공급률100 수량10 → 공급가 100,000
        JsonNode sale = post("/sales/entries", Map.of(
                "salesDate", "2028-06-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", p, "shipmentType", "NORMAL_SHIP",
                        "qty", 10, "unitPrice", 10000, "supplyRate", 100))));
        assertThat(sale.path("success").asBoolean()).as("매출등록: %s", sale).isTrue();

        JsonNode row1 = arRow(partner);
        assertThat(row1.path("saleAmount").asLong()).isEqualTo(100_000);
        long recGen = row1.path("receivableGen").asLong();
        assertThat(recGen).isEqualTo(row1.path("saleAmount").asLong()
                + row1.path("tax").asLong() - row1.path("returnAmount").asLong());   // 채권발생=매출+세액−반품
        assertThat(row1.path("collected").asLong()).isEqualTo(0);
        assertThat(row1.path("balance").asLong()).isEqualTo(row1.path("opening").asLong() + recGen);   // 잔액=이월+채권발생−0
        assertThat(row1.path("assureAmount").asLong()).isEqualTo(200_000);
        assertThat(row1.path("assureRatio").isNull()).isFalse();   // 담보 있으니 비율 산출

        // 수금 50,000(현금) → 잔액 −50,000, 수금 +50,000
        JsonNode coll = post("/closing/collections", Map.of(
                "collDate", "2028-06-20", "partnerId", partner, "collType", "CASH", "collAmt", 50_000));
        assertThat(coll.path("success").asBoolean()).as("수금: %s", coll).isTrue();

        JsonNode row2 = arRow(partner);
        assertThat(row2.path("collected").asLong()).isEqualTo(50_000);
        assertThat(row2.path("balance").asLong()).isEqualTo(recGen - 50_000);
    }

    @Test
    @DisplayName("외상매출장 — 거래처 상세(매출/수금 명세 + 러닝밸런스)")
    void 외상매출장() {
        Long supplier = createId("/masters/clients", Map.of("code", "LG-SUP", "name", "입고공급사2", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "LG-WH", "name", "장부창고", "type", "MAIN"));
        Long p = createId("/masters/products",
                Map.of("code", "LG-BK", "name", "장부도서", "contentType", "SELF", "price", 10000,
                "salesDivision", "교재"));
        Long partner = createId("/masters/clients", Map.of("code", "LG-CUST", "name", "장부거래처", "type", "NORMAL"));
        inbound(wh, supplier, p);
        post("/sales/entries", Map.of(
                "salesDate", "2028-06-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", p, "shipmentType", "NORMAL_SHIP",
                        "qty", 5, "unitPrice", 10000, "supplyRate", 100))));
        post("/closing/collections", Map.of(
                "collDate", "2028-06-15", "partnerId", partner, "collType", "CASH", "collAmt", 20_000));

        JsonNode d = data(get("/closing/ar-ledger?partnerId=" + partner
                + "&fromDate=2028-01-01&toDate=2028-12-31"));
        assertThat(d.path("lines").size()).as("명세 라인(매출+수금): %s", d).isGreaterThanOrEqualTo(2);
        // 러닝밸런스 불변식: 기말잔액 = 이월 + Σ(라인 채권증감). 세액 포함여부 무관하게 성립해야 함.
        long sum = 0;
        for (JsonNode ln : d.path("lines")) {
            sum += ln.path("amount").asLong();
        }
        assertThat(d.path("closing").asLong()).isEqualTo(d.path("opening").asLong() + sum);
        // 마지막 라인 누계 == 기말잔액
        JsonNode last = d.path("lines").get(d.path("lines").size() - 1);
        assertThat(last.path("balance").asLong()).isEqualTo(d.path("closing").asLong());
    }

    @Test
    @DisplayName("외상매출장(24p) — 도서 단위 명세다. 전표 한 줄이 아니다")
    void 외상매출장_도서단위() {
        String sfx = "-AL" + (System.nanoTime() % 1_000_000L);
        Long sup = createId("/masters/clients", Map.of("code", "ALS" + sfx, "name", "인쇄", "type", "NORMAL"));
        Long pt = createId("/masters/clients", Map.of("code", "ALP" + sfx, "name", "장부처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "ALW" + sfx, "name", "창고", "type", "MAIN"));
        Long pr = createId("/masters/products", Map.of("code", "ALB" + sfx, "name", "국어교재",
                "contentType", "SELF", "price", 10000, "taxFree", false,
                "catCode", "H2050H01", "catName", "교재분류",
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2050-01-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", pr, "unitCost", 3000, "qty", 500))));

        // 매출 10권(공급률 75, 세액 7500) + 교사용 2권(공급률 50) + 증정 3권(공급률 0)
        post("/sales/entries", Map.of("salesDate", "2050-03-10", "partnerId", pt, "warehouseId", wh,
                "items", List.of(
                        Map.of("productId", pr, "shipmentType", "NORMAL_SHIP", "qty", 10,
                                "unitPrice", 10000, "supplyRate", 75, "tax", 7500,
                                "schoolName", "강남대성학원", "round", 3),
                        Map.of("productId", pr, "shipmentType", "TEACHER_USE", "qty", 2,
                                "unitPrice", 10000, "supplyRate", 50),
                        Map.of("productId", pr, "shipmentType", "GIFT", "qty", 3,
                                "unitPrice", 10000, "supplyRate", 0))));

        JsonNode lines = data(get("/closing/ar-ledger?partnerId=" + pt
                + "&fromDate=2050-01-01&toDate=2050-12-31")).path("lines");

        JsonNode sale = null;
        JsonNode teacher = null;
        JsonNode gift = null;
        for (JsonNode l : lines) {
            // ‼️집계행(이월·일계·월계·누계)도 saleQty 를 갖는다 — 명세행만 본다.
            //   안 거르면 마지막 '누 계' 행이 매출 행으로 잡혀 분류명이 빈 걸 보게 된다.
            if (!"DETAIL".equals(l.path("rowType").asText())) {
                continue;
            }
            if (l.hasNonNull("saleQty")) {
                sale = l;
            } else if (l.hasNonNull("teacherQty")) {
                teacher = l;
            } else {
                // 매출·교사용·반품 어느 칸도 안 찬 행 = 증정(공급률 0 무가)뿐이었다.
                // 10/27 피드백 §7-A로 **그런 빈 행은 아예 만들지 않게** 바뀌었다.
                gift = l;
            }
        }

        // 매출 행: 도서 정보가 칸으로 펼쳐진다(예전엔 적요 한 칸에 뭉개져 있었다)
        assertThat(sale).isNotNull();
        assertThat(sale.path("catName").asText()).isEqualTo("교재분류");
        assertThat(sale.path("supplyRate").asInt()).isEqualTo(75);
        assertThat(sale.path("saleQty").asLong()).isEqualTo(10);
        assertThat(sale.path("saleAmount").asLong()).isEqualTo(75_000);
        assertThat(sale.path("tax").asLong()).isEqualTo(7_500);
        // 도서명에 회차·학교가 붙는다 — 같은 책이 여러 줄 나오므로 이름만으로는 못 가린다
        assertThat(sale.path("productName").asText()).contains("[3회]").contains("<강남대성학원>");

        // ★집계행(특약점 배포 PDF 실물 SALES_*.pdf 와 같은 구성) — 이월·일계·월계·누계.
        //   없으면 화면이 직접 더해야 하고, 그러면 서버 숫자와 화면 숫자가 갈린다.
        java.util.List<String> types = new java.util.ArrayList<>();
        long detailSaleQty = 0;
        JsonNode running = null;
        for (JsonNode l : lines) {
            types.add(l.path("rowType").asText());
            if ("DETAIL".equals(l.path("rowType").asText())) {
                detailSaleQty += l.path("saleQty").asLong(0);
            }
            if ("RUNNING_TOTAL".equals(l.path("rowType").asText())) {
                running = l;
            }
        }
        assertThat(types.get(0)).as("첫 줄은 이월 금액이다").isEqualTo("OPENING");
        // ★이월 바로 아래에도 누 계(= 이월 그 자체)를 둔다 — 발주처 샘플 구조.
        //   없으면 받는 쪽이 "이월이 누계에 반영됐나"를 첫 거래까지 알 수 없다.
        assertThat(types.get(1)).as("이월 다음 줄은 누 계").isEqualTo("RUNNING_TOTAL");
        assertThat(lines.get(1).path("saleAmount").asLong())
                .as("거래 전 누계 = 이월").isEqualTo(
                        data(get("/closing/ar-ledger?partnerId=" + pt
                                + "&fromDate=2050-01-01&toDate=2050-12-31")).path("opening").asLong());

        // 날짜가 바뀔 때마다 누 계가 들어간다 — 일 계 바로 뒤다(달 끝이면 월 계가 사이에 낀다).
        for (int i = 0; i < types.size(); i++) {
            if ("DAY_SUBTOTAL".equals(types.get(i))) {
                String next = types.get(i + 1);
                assertThat(next).as("일 계 다음은 누 계 또는 월 계")
                        .isIn("RUNNING_TOTAL", "MONTH_SUBTOTAL");
            }
        }
        assertThat(types).contains("DAY_SUBTOTAL", "MONTH_SUBTOTAL", "RUNNING_TOTAL");
        assertThat(running).isNotNull();
        assertThat(running.path("saleQty").asLong())
                .as("누계 매출수량 = 명세 합 — 갈리면 둘 다 못 믿는다").isEqualTo(detailSaleQty);
        assertThat(running.path("label").asText()).isEqualTo("누 계");

        // ★★누계 **금액**에는 이월이 들어간다(프론트 실측 2026-10-12 B-4, 발주처 인쇄물 기준).
        //   예전엔 기간 매출만 더해 "이월 4,900만인데 첫 달 누계 740만"이 됐다 —
        //   받는 특약점이 보는 것은 "지금까지 얼마가 쌓였나"라 이월을 빼면 숫자가 의미를 잃는다.
        long opening = data(get("/closing/ar-ledger?partnerId=" + pt
                + "&fromDate=2050-01-01&toDate=2050-12-31")).path("opening").asLong();
        long detailSaleAmt = 0;
        for (JsonNode l : lines) {
            if ("DETAIL".equals(l.path("rowType").asText())) {
                detailSaleAmt += l.path("saleAmount").asLong(0);
            }
        }
        assertThat(running.path("saleAmount").asLong())
                .as("누계 금액 = 이월 + 기간 매출").isEqualTo(opening + detailSaleAmt);

        // ‼️일계·월계는 **그 구간만**이다 — 거기까지 이월을 섞으면 하루 매출이 부풀어 보인다.
        for (JsonNode l : lines) {
            if ("DAY_SUBTOTAL".equals(l.path("rowType").asText())) {
                assertThat(l.path("saleAmount").asLong())
                        .as("일계에 이월이 섞이면 안 된다").isEqualTo(detailSaleAmt);
            }
        }

        // 반품금액·수금액도 집계 줄에 실린다 — 없으면 인쇄물을 서버 숫자 그대로 못 찍는다.
        assertThat(running.has("returnAmount")).isTrue();
        assertThat(running.has("collectAmount")).isTrue();

        // 교사용 행: 무가 중 공급률이 있는 것만
        assertThat(teacher).isNotNull();
        assertThat(teacher.path("teacherQty").asLong()).isEqualTo(2);
        assertThat(teacher.hasNonNull("saleQty")).as("한 행에는 그 구분 칸만 찬다").isFalse();

        // ‼️증정(공급률 0)은 교사용이 아니다 — 합치면 교사용 수량이 부풀려진다.
        //   ★10/27 피드백 §7-A("값이 없는 행은 만들지 않는다")로 **행 자체가 사라졌다** —
        //   증정은 매출·교사용·반품 어느 칸도 안 채워 빈 줄로만 나갔기 때문이다.
        //   교사용 수량이 안 부풀려진다는 성질은 위 teacherQty=2 검증이 그대로 지킨다.
        assertThat(gift).as("빈 행(증정)은 더 이상 만들지 않는다").isNull();
    }

    @Test
    @DisplayName("매출 대시보드 — 목표대비 실적·달성률(상품별 격리)")
    void 대시보드_목표대비() {
        Long supplier = createId("/masters/clients", Map.of("code", "DB-SUP", "name", "입고공급사3", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "DB-WH", "name", "대시창고", "type", "MAIN"));
        Long mp = createId("/masters/products",
                Map.of("code", "DB-BK", "name", "대시도서", "contentType", "SELF", "price", 10000,
                "salesDivision", "교재"));
        Long partner = createId("/masters/clients", Map.of("code", "DB-CUST", "name", "대시거래처", "type", "NORMAL"));
        inbound(wh, supplier, mp);
        // 6월 순매출 100,000 (공급률100 × 10부)
        post("/sales/entries", Map.of(
                "salesDate", "2028-06-10", "partnerId", partner, "warehouseId", wh,
                "items", List.of(Map.of("productId", mp, "shipmentType", "NORMAL_SHIP",
                        "qty", 10, "unitPrice", 10000, "supplyRate", 100))));
        // 6월 목표 200,000 (상품별)
        JsonNode t = post("/dashboard/targets",
                Map.of("year", 2028, "month", 6, "productId", mp, "targetAmount", 200_000));
        assertThat(t.path("success").asBoolean()).as("목표설정: %s", t).isTrue();

        JsonNode d = data(get("/dashboard/sales?year=2028&productId=" + mp));
        JsonNode june = null;
        for (JsonNode m : d.path("months")) {
            if (m.path("month").asInt() == 6) {
                june = m;
            }
        }
        assertThat(june).as("6월 셀: %s", d).isNotNull();
        assertThat(june.path("target").asLong()).isEqualTo(200_000);
        assertThat(june.path("actual").asLong()).isEqualTo(100_000);        // 순매출
        assertThat(june.path("achievementPct").asDouble()).isEqualTo(50.0);  // 100k/200k
        assertThat(june.path("prevActual").asLong()).isEqualTo(0);           // 2027 없음
        // 연간 합계
        assertThat(d.path("summary").path("totalTarget").asLong()).isEqualTo(200_000);
        assertThat(d.path("summary").path("totalActual").asLong()).isEqualTo(100_000);
    }

    private JsonNode arRow(Long partnerId) {
        JsonNode rows = data(get("/closing/ar-status?fromDate=2028-01-01&toDate=2028-12-31&partnerId=" + partnerId))
                .path("rows");
        assertThat(rows).as("ar-status 행 존재").isNotEmpty();
        return rows.get(0);
    }

    private void inbound(Long whId, Long supplierId, Long productId) {
        JsonNode r = post("/stock/inbound", Map.of(
                "processedDate", "2028-06-01", "supplierClientId", supplierId, "destinationWarehouseId", whId,
                "items", List.of(Map.of("productId", productId, "unitCost", 3000, "qty", 100))));
        assertThat(r.path("success").asBoolean()).as("입고: %s", r).isTrue();
    }

    @Test
    @DisplayName("매출목표 — 연간 목표와 사업부문 축(발주처가 준 데이터 형태 그대로)")
    void 연간_사업부문_목표() {
        // 발주처 회신(자료요청서 1-6)은 월이 아니라 '연간', 대상은 상품이 아니라 '사업부문'으로 왔다.
        // 우리 양식이 월 단위 예시였던 탓에 구조가 월 필수였고, 그래서 한 줄도 못 넣던 것을 고쳤다.
        assertThat(post("/dashboard/targets", Map.of(
                "year", 2031, "scope", "DIVISION", "scopeKey", "더프리미엄",
                "targetAmount", 9_454_430_300L)).path("success").asBoolean()).isTrue();
        // 전년 실적: 레거시를 이관하지 않아 2030 매출이 DB에 없다 → 받은 실적값을 저장해 전년비를 살린다
        post("/dashboard/targets", Map.of(
                "year", 2030, "scope", "DIVISION", "scopeKey", "더프리미엄",
                "entryType", "ACTUAL", "targetAmount", 8_794_691_150L));

        JsonNode d = data(get("/dashboard/sales?year=2031&scope=DIVISION&scopeKey=더프리미엄"));
        assertThat(d.path("summary").path("totalTarget").asLong())
                .as("연간 목표가 연간 요약에 잡힌다").isEqualTo(9_454_430_300L);
        assertThat(d.path("summary").path("prevTotalActual").asLong())
                .as("전년 매출이 없으면 저장된 확정 실적으로 채운다").isEqualTo(8_794_691_150L);
        // ★연간 금액을 12로 나눠 월에 뿌리지 않는다 — 있지도 않은 월 목표를 만들어내면 달성률이 거짓이 된다
        for (JsonNode m : d.path("months")) {
            assertThat(m.path("target").asLong()).as("%s월 셀", m.path("month")).isZero();
        }
    }

    @Test
    @DisplayName("같은 연·대상·종류를 다시 등록하면 행이 늘지 않고 금액만 갱신된다")
    void 목표_중복등록() {
        post("/dashboard/targets", Map.of("year", 2032, "scope", "DIVISION",
                "scopeKey", "학원 컨텐츠", "targetAmount", 100));
        post("/dashboard/targets", Map.of("year", 2032, "scope", "DIVISION",
                "scopeKey", "학원 컨텐츠", "targetAmount", 200));

        JsonNode list = data(get("/dashboard/targets?year=2032"));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("amount").asLong()).isEqualTo(200);

        // 목표와 실적은 같은 연·대상이어도 별개 행이다(나란히 비교해야 하므로)
        post("/dashboard/targets", Map.of("year", 2032, "scope", "DIVISION",
                "scopeKey", "학원 컨텐츠", "entryType", "ACTUAL", "targetAmount", 300));
        assertThat(data(get("/dashboard/targets?year=2032"))).hasSize(2);
    }

    @Test
    @DisplayName("수금 기장일자 — 수금일자와 별개로 저장되고, 미입력이면 비어 있다")
    void 수금_기장일자() {
        // 레거시 AmtData.writeDate — 돈이 들어온 날과 장부에 기표한 날이 달라 재무팀이 둘을 나눠 본다.
        String sfx = "-WD" + (System.nanoTime() % 1_000_000L);
        Long p = createId("/masters/clients", Map.of("code", "WD" + sfx, "name", "수금처", "type", "NORMAL"));

        JsonNode a = data(post("/closing/collections", Map.of(
                "collDate", "2026-06-25", "writeDate", "2026-06-30",
                "partnerId", p, "collType", "CASH", "collAmt", 500_000)));
        assertThat(a.path("collDate").asText()).isEqualTo("2026-06-25");
        assertThat(a.path("writeDate").asText()).as("수금일자로 덮이지 않는다").isEqualTo("2026-06-30");

        // 미입력이면 수금일자로 자동으로 채우지 않는다 — 같다고 단정하면 따로 둘 이유가 없다.
        JsonNode b = data(post("/closing/collections", Map.of(
                "collDate", "2026-06-26", "partnerId", p, "collType", "CASH", "collAmt", 100_000)));
        assertThat(b.hasNonNull("writeDate")).isFalse();
    }

    @Test
    @DisplayName("담보만기 — 만료 건이 임박 건보다 앞에 모인다")
    void 담보만기_정렬() {
        // 발주처 확정(3-2 바): "만기 경과 건은 빨간색 표시 후 별도 목록으로 쏘팅".
        // 만기일 순으로만 두면 이미 지난 건과 아직 시간이 있는 건이 섞인다.
        String sfx = "-CE" + (System.nanoTime() % 1_000_000L);
        record Seed(String code, String expiry) { }
        for (Seed sd : List.of(new Seed("CE1", "2026-07-01"), new Seed("CE2", "2026-08-20"),
                new Seed("CE3", "2026-06-01"))) {
            Long id = createId("/masters/clients",
                    Map.of("code", sd.code() + sfx, "name", sd.code(), "type", "NORMAL"));
            put("/masters/clients/" + id, Map.of("name", sd.code(), "type", "NORMAL",
                    "assureAmount", 1_000_000, "assureExpiry", sd.expiry()));
        }

        List<String> statuses = new ArrayList<>();
        for (JsonNode r : data(get("/masters/clients/collateral-expiry?asOf=2026-08-12&withinDays=30"))
                .path("rows")) {
            if (r.path("code").asText().endsWith(sfx)) {
                statuses.add(r.path("status").asText());
            }
        }
        assertThat(statuses).containsExactly("EXPIRED", "EXPIRED", "IMMINENT");
    }

    @Test
    @DisplayName("대시보드 스냅샷 — 배치 전엔 실시간, 배치 후엔 스냅샷 기준시각이 붙는다")
    void 대시보드_스냅샷() {
        // 발주처 확정(3-2 아) "하루 1회 갱신". 다만 배치가 안 돈 날 화면이 비면 안 되므로
        // 스냅샷이 없으면 실시간으로 계산하고, computedAt으로 어느 쪽인지 드러낸다.
        String sfx = "-DS" + (System.nanoTime() % 1_000_000L);
        Long sup = createId("/masters/clients", Map.of("code", "DSS" + sfx, "name", "인쇄", "type", "NORMAL"));
        Long pt = createId("/masters/clients", Map.of("code", "DSP" + sfx, "name", "스냅처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "DSW" + sfx, "name", "스냅창고", "type", "MAIN"));
        Long pr = createId("/masters/products", Map.of("code", "DSB" + sfx, "name", "스냅도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 100,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2029-03-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", pr, "unitCost", 3000, "qty", 500))));
        post("/sales/entries", Map.of("salesDate", "2029-03-10", "partnerId", pt, "warehouseId", wh,
                "items", List.of(Map.of("productId", pr, "shipmentType", "NORMAL_SHIP", "qty", 100))));

        // 배치를 2029 기준으로 돌린다
        JsonNode run = data(post("/batch/jobs/dashboard-snapshot/run?baseDate=2029-12-31", null));
        assertThat(run.path("status").asText()).isEqualTo("SUCCESS");

        JsonNode d = data(get("/dashboard/sales?year=2029"));
        assertThat(d.hasNonNull("computedAt")).as("스냅샷을 썼으면 기준시각이 있다").isTrue();
        assertThat(d.path("summary").path("totalActual").asLong()).isEqualTo(1_000_000);

        // 같은 날 다시 돌려도 행이 늘지 않고 값만 갱신된다(지웠다 넣지 않는다).
        post("/sales/entries", Map.of("salesDate", "2029-03-11", "partnerId", pt, "warehouseId", wh,
                "items", List.of(Map.of("productId", pr, "shipmentType", "NORMAL_SHIP", "qty", 50))));
        post("/batch/jobs/dashboard-snapshot/run?baseDate=2029-12-31", null);
        assertThat(data(get("/dashboard/sales?year=2029")).path("summary").path("totalActual").asLong())
                .as("재실행하면 새 매출이 반영된다").isEqualTo(1_500_000);
    }

    @Test
    @DisplayName("비교연도를 고를 수 있다 — 전년이 아닌 해와도 견준다(20p)")
    void 비교연도_선택() {
        // 2044년 실적만 만들고, 2046년을 2044년과 견준다(전년 2045는 매출이 없다)
        String sfx = "-CY" + (System.nanoTime() % 1_000_000L);
        Long sup = createId("/masters/clients", Map.of("code", "CYS" + sfx, "name", "인쇄", "type", "NORMAL"));
        Long pt = createId("/masters/clients", Map.of("code", "CYP" + sfx, "name", "비교처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "CYW" + sfx, "name", "창고", "type", "MAIN"));
        Long pr = createId("/masters/products", Map.of("code", "CYB" + sfx, "name", "도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 100,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", "2044-01-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", pr, "unitCost", 3000, "qty", 100))));
        post("/sales/entries", Map.of("salesDate", "2044-03-10", "partnerId", pt, "warehouseId", wh,
                "items", List.of(Map.of("productId", pr, "shipmentType", "NORMAL_SHIP", "qty", 10))));

        // 기본(전년=2045)은 실적이 없다
        assertThat(data(get("/dashboard/sales?year=2046")).path("months").get(2)
                .path("prevActual").asLong()).isZero();

        // 비교연도를 2044로 고르면 그 해 3월 실적이 잡힌다
        JsonNode picked = data(get("/dashboard/sales?year=2046&compareYear=2044"));
        assertThat(picked.path("compareYear").asInt()).isEqualTo(2044);
        assertThat(picked.path("months").get(2).path("prevActual").asLong()).isEqualTo(100_000);
    }

    @Test
    @DisplayName("대시보드 누적목표·누적실적 — 1월부터 해당월까지의 합")
    void 대시보드_누적() {
        // 요구 20p 매출 상세 대시보드: 당월목표/누적목표/당월실적/누적실적/달성률/성장률
        int year = 2037;
        for (int m = 1; m <= 3; m++) {
            post("/dashboard/targets", Map.of("year", year, "month", m,
                    "scope", "COMPANY", "targetAmount", m * 1_000_000));
        }
        String sfx = "-AC" + (System.nanoTime() % 1_000_000L);
        Long sup = createId("/masters/clients", Map.of("code", "ACS" + sfx, "name", "인쇄", "type", "NORMAL"));
        Long pt = createId("/masters/clients", Map.of("code", "ACP" + sfx, "name", "누적처", "type", "NORMAL"));
        Long wh = createId("/masters/warehouses", Map.of("code", "ACW" + sfx, "name", "창고", "type", "MAIN"));
        Long pr = createId("/masters/products", Map.of("code", "ACB" + sfx, "name", "도서",
                "contentType", "SELF", "price", 10000, "supplyRate", 100,
                "salesDivision", "교재"));
        post("/stock/inbound", Map.of("processedDate", year + "-01-01", "supplierClientId", sup,
                "destinationWarehouseId", wh,
                "items", List.of(Map.of("productId", pr, "unitCost", 3000, "qty", 900))));
        for (int m = 1; m <= 3; m++) {
            post("/sales/entries", Map.of("salesDate", String.format("%d-%02d-10", year, m),
                    "partnerId", pt, "warehouseId", wh,
                    "items", List.of(Map.of("productId", pr, "shipmentType", "NORMAL_SHIP",
                            "qty", m * 10))));
        }

        JsonNode months = data(get("/dashboard/sales?year=" + year)).path("months");

        // ★'해당 월 포함' 누적이어야 한다. 더하는 순서를 잘못 잡으면 한 달씩 밀린다.
        assertThat(months.get(0).path("cumulativeTarget").asLong()).isEqualTo(1_000_000);
        assertThat(months.get(2).path("cumulativeTarget").asLong()).as("1+2+3월").isEqualTo(6_000_000);
        assertThat(months.get(0).path("cumulativeActual").asLong()).isEqualTo(100_000);
        assertThat(months.get(2).path("cumulativeActual").asLong()).as("1+2+3월").isEqualTo(600_000);

        // 매출이 없는 달은 누적이 유지된다(0으로 떨어지지 않는다).
        assertThat(months.get(3).path("actual").asLong()).isZero();
        assertThat(months.get(3).path("cumulativeActual").asLong()).isEqualTo(600_000);

        // 12월 누적 == 연간 합계
        assertThat(months.get(11).path("cumulativeActual").asLong())
                .isEqualTo(data(get("/dashboard/sales?year=" + year)).path("summary")
                        .path("totalActual").asLong());
    }
}
