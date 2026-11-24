package com.daesung.sales.closing;

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
 * 외상매출장 — <b>「신고대상」은 조회를 바꾸지 않고</b>, <b>이월만 있는 거래처도 들어온다</b>.
 *
 * <p>발주처 2026-11-23 9번. 원인 셋을 레거시 소스로 확정해 고친 것을 못박는다:
 * <ol>
 *   <li>거래처코드 상한 {@code E110000} 은 레거시 전체에서 <b>거래처 콤보 목록 함수
 *       한 곳</b>에만 있다({@code CommonDB.GetCustList_Sales}). 장부 본문의 거래처 조건은
 *       「전체 거래처」면 <b>빈 문자열</b>이다({@code 외상매출장조회.vb:181}).
 *       → 특강 거래처 14곳·33.8억이 통째로 안 보였다.</li>
 *   <li>레거시 이월 행은 {@code sum(carrData where year=시작연도)} 로 <b>거래처 조건이 없다</b>
 *       → 이월만 있어도 장부에 들어온다. 우리는 매출 집계에서만 거래처를 뽑아
 *       <b>36곳·65,124,114원</b>이 빠졌다.</li>
 *   <li>이월 연도를 {@code from.minusDays(1)} 에서 뽑아 <b>1월 1일 조회가 한 해 어긋났다</b>
 *       — 그 해 이월 행을 안 읽고 전년을 통째로 재계산했다.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("외상매출장 — 신고대상 비필터·이월만 있는 거래처·이월 연도(2026-11-24)")
class ArLedgerReportableIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "AR24" + (System.nanoTime() % 1_000_000L);
    private static final int YEAR = 2076;

    /** 'E110000' 이상 — 레거시 콤보 목록에서 빠지는 계열(특강 거래처가 이 모양이다). */
    private static final String HIGH_CODE = "E30A" + (System.nanoTime() % 900 + 100);

    @org.springframework.beans.factory.annotation.Autowired
    private jakarta.persistence.EntityManager em;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private Long highPartner;      // 코드 상한 이상 + 기간 내 거래 있음
    private Long carryOnly;        // 이월만 있고 기간 거래 없음
    private Long warehouse;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "ARS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "ARW" + SFX, "name", "장부검증창고", "type", "MAIN"));
        highPartner = createId("/masters/clients",
                Map.of("code", HIGH_CODE, "name", "상한초과 특강거래처" + SFX, "type", "NORMAL"));
        carryOnly = createId("/masters/clients",
                Map.of("code", "ARC" + SFX, "name", "이월만거래처" + SFX, "type", "NORMAL"));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "ARB" + SFX);
        b.put("name", "장부검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "N" + YEAR + "A");
        b.put("catName", YEAR + "특강");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", YEAR + "-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 300, "unitCost", 3000))))
                .path("success").asBoolean()).isTrue();

        // 상한초과 거래처에 기간 내 매출 — 레거시 특강과 같은 모양.
        assertThat(post("/sales/entries", Map.of(
                "salesDate", YEAR + "-03-10", "partnerId", highPartner, "warehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "shipmentType", "NORMAL_SHIP",
                        "qty", 10, "unitPrice", 10000, "supplyRate", 70))))
                .path("success").asBoolean()).isTrue();

        // 이월만 있는 거래처 — 그 해 이월을 넣고 기간 거래는 만들지 않는다.
        // ‼️`POST /closing/carryforward` 는 **그 연도 전체를 지우고 다시 만든다** — 쓰면
        //   다른 테스트의 이월까지 날아간다. 그래서 행을 직접 넣는다.
        insertCarry(carryOnly, 1_234_500L);
    }

    @Test
    @DisplayName("★★「신고대상」을 켜도 **조회 결과가 같다** — 상한초과 거래처가 빠지지 않는다")
    void 신고대상은_조회를_바꾸지_않는다() {
        List<String> off = partnerCodes(false);
        List<String> on = partnerCodes(true);
        assertThat(on)
                .as("‼️다르면 「신고대상」이 또 조회 필터로 돌아간 것이다")
                .isEqualTo(off);
        assertThat(off)
                .as("‼️코드 상한(E110000) 이상이 빠지면 특강 33.8억이 다시 사라진다")
                .contains(HIGH_CODE);
    }

    @Test
    @DisplayName("★★**이월만 있고 기간 거래가 없는 거래처**도 장부에 들어온다 — 이월 금액 그대로")
    void 이월만_있는_거래처도_들어온다() {
        JsonNode rows = rows(false);
        JsonNode found = null;
        for (JsonNode n : rows) {
            if (("ARC" + SFX).equals(n.path("partnerCode").asText())) {
                found = n;
            }
        }
        assertThat(found)
                .as("‼️없으면 36곳 65,124,114원이 다시 빠지는 그 결함이다: %s", partnerCodes(false))
                .isNotNull();
        assertThat(found.path("opening").asLong())
                .as("이월 금액이 그대로 나와야 한다")
                .isEqualTo(1_234_500L);
    }

    @Test
    @DisplayName("‼️**1월 1일 조회**가 그 해 이월 행을 쓴다 — 예전엔 전년을 통째로 재계산했다")
    void 연초_조회가_그해_이월을_쓴다() {
        JsonNode d = data(get("/closing/ar-ledger?partnerId=" + carryOnly
                + "&fromDate=" + YEAR + "-01-01&toDate=" + YEAR + "-12-31"));
        assertThat(d.path("opening").asLong())
                .as("‼️0 이나 다른 값이면 연도를 from 이 아니라 from−1일에서 뽑은 것이다")
                .isEqualTo(1_234_500L);
    }

    @Test
    @DisplayName("페이징 조회도 **같은 거래처 집합**이다 — 전체엔 있는데 페이지엔 없으면 안 된다")
    void 페이징도_같은_집합() {
        JsonNode paged = data(get("/closing/ar-ledger/all?fromDate=" + YEAR + "-01-01"
                + "&toDate=" + YEAR + "-12-31&size=200"));
        List<String> codes = new java.util.ArrayList<>();
        paged.path("content").forEach(n -> codes.add(n.path("partnerCode").asText()));
        assertThat(codes).contains(HIGH_CODE, "ARC" + SFX);
    }

    /**
     * 이월 행 한 줄을 직접 넣는다.
     *
     * <p>‼️같은 클래스 안의 {@code @Transactional} 은 프록시를 안 거쳐 적용되지 않는다 —
     * 템플릿으로 직접 연다(다른 통합테스트가 쓰는 그 방식).
     */
    private void insertCarry(Long partnerId, long amount) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(status -> {
                    var pt = em.find(com.daesung.sales.partner.entity.Partner.class, partnerId);
                    em.persist(com.daesung.sales.receivable.entity.ReceivableCarryforward.create(
                            YEAR, pt, amount, java.time.LocalDateTime.now()));
                    em.flush();
                    em.clear();
                });
    }

    @Test
    @DisplayName("★★**법인 기본값은 연구소** — 리브커넥스 거래처가 섞이지 않는다")
    void 법인은_기본_연구소() {
        Long lib = createId("/masters/clients",
                Map.of("code", "ARL" + SFX, "name", "리브거래처" + SFX, "type", "NORMAL"));
        insertCarry(lib, 777_000L);
        // 등록 API 로는 법인을 정할 수 없다(기본 연구소) — 이관분과 같은 모양을 만든다.
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(status -> {
                    em.createQuery("update Partner p set p.corp = "
                                    + "com.daesung.sales.common.corp.Corp.DSTXTBOOK where p.id = :id")
                            .setParameter("id", lib)
                            .executeUpdate();
                    em.clear();
                });

        assertThat(partnerCodes(false))
                .as("‼️섞이면 레거시보다 금액이 커진다(실측 11.2억 → 25.8억)")
                .doesNotContain("ARL" + SFX);
        assertThat(codesOf("&allCorp=true"))
                .as("전 법인으로 열면 나온다").contains("ARL" + SFX);
        assertThat(codesOf("&corp=DSTXTBOOK"))
                .as("리브커넥스만 골라도 나온다").contains("ARL" + SFX);
    }

    private List<String> codesOf(String extra) {
        List<String> out = new java.util.ArrayList<>();
        data(get("/closing/ar-ledger/all?fromDate=" + YEAR + "-01-01&toDate=" + YEAR
                + "-12-31&size=200" + extra)).path("content")
                .forEach(n -> out.add(n.path("partnerCode").asText()));
        return out;
    }

    private JsonNode rows(boolean onlyReportable) {
        return data(get("/closing/ar-ledger/all?fromDate=" + YEAR + "-01-01&toDate=" + YEAR
                + "-12-31&size=200&onlyReportable=" + onlyReportable)).path("content");
    }

    private List<String> partnerCodes(boolean onlyReportable) {
        List<String> out = new java.util.ArrayList<>();
        rows(onlyReportable).forEach(n -> out.add(n.path("partnerCode").asText()));
        return out;
    }
}
