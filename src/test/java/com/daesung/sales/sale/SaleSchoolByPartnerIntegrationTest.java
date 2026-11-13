package com.daesung.sales.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 매출 응답의 <b>학교 속성은 거래처까지 맞춰</b> 고른다(프론트 전달 2026-11-13 §2).
 *
 * <p>학교 마스터는 <b>(거래처 × 학교)</b> 단위라 <b>같은 학교코드가 지사마다 따로</b> 있고
 * 그 행들의 구분이 갈린다. 운영 실측 — 같은 코드에 학교·학원이 함께 있는 코드가 <b>52개</b>,
 * 2026 매출 <b>192줄</b>이 거기 걸린다.
 *
 * <pre>
 *   30081 수원학원 · cust_code 30002(미고북스)      → HAKWON   ← A14C122 매출은 이쪽
 *   30081 수원학원 · cust_code 30003(수원 미고북스) → SCHOOL
 * </pre>
 *
 * <p>★그전엔 학교코드만으로 찾아 <b>먼저 읽힌 행이 이겼다</b> — A14C122 매출에 SCHOOL 이
 * 붙었고 레거시(학원)와 달랐다. 이 테스트가 지키는 것은 <b>거래처가 다르면 다른 행을 본다</b>는 것이다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("매출 학교구분 — 거래처 + 학교코드로 찾는다(프론트 2026-11-13 §2)")
class SaleSchoolByPartnerIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "SP" + (System.nanoTime() % 1_000_000L);
    private static final String DATE = "2091-06-06";

    /** 두 거래처가 **같은 학교코드**를 쓴다 — 운영의 30081 과 같은 모양. */
    private static final String SCHOOL_CODE = "SC" + SFX;

    @Autowired
    private PartnerRepository partnerRepository;

    private Long hakwonPartner;   // 학원 쪽 거래처
    private Long schoolPartner;   // 학교 쪽 거래처
    private Long warehouse;
    private Long book;

    @BeforeAll
    void seed() {
        token();
        Long supplier = createId("/masters/clients",
                Map.of("code", "SPS" + SFX, "name", "인쇄소", "type", "NORMAL"));
        warehouse = createId("/masters/warehouses",
                Map.of("code", "SPW" + SFX, "name", "창고", "type", "MAIN"));

        // 거래처 2곳 — 각각 다른 DSRE 코드를 갖는다(schools.cust_code 와 맞물리는 값).
        // ‼️dsreCode 는 등록 API 로 못 넣는다(동기화가 채우는 칸) — 엔티티로 직접 심는다.
        //   운영 실측: schools.cust_code 5,008/5,011 이 DSRE 코드이고,
        //   2026 매출 14,324줄 중 13,060줄이 partners.dsre_code 로 붙는다(partners.code 로는 0줄).
        hakwonPartner = createId("/masters/clients",
                Map.of("code", "SPA" + SFX, "name", "학원쪽거래처", "type", "NORMAL"));
        schoolPartner = createId("/masters/clients",
                Map.of("code", "SPB" + SFX, "name", "학교쪽거래처", "type", "NORMAL"));
        applyDsre(hakwonPartner, "D1" + SFX);
        applyDsre(schoolPartner, "D2" + SFX);

        // ★같은 학교코드 · 다른 거래처 · 다른 구분
        createId("/masters/schools", Map.of(
                "schoolCode", SCHOOL_CODE, "custCode", "D1" + SFX, "custName", "학원쪽거래처",
                "schoolName", "같은이름학교", "schoolType", "HAKWON", "isSchool", false));
        createId("/masters/schools", Map.of(
                "schoolCode", SCHOOL_CODE, "custCode", "D2" + SFX, "custName", "학교쪽거래처",
                "schoolName", "같은이름학교", "schoolType", "SCHOOL", "isSchool", true));

        Map<String, Object> b = new HashMap<>();
        b.put("code", "SPB1" + SFX);
        b.put("name", "학교구분 검증도서");
        b.put("contentType", "SELF");
        b.put("price", 10000);
        b.put("catCode", "S2091A01");
        b.put("catName", "학교구분검증");
        b.put("salesDivision", "교재");
        b.put("taxFree", true);
        book = createId("/masters/products", b);

        assertThat(post("/stock/inbound", Map.of(
                "processedDate", "2091-01-05", "supplierClientId", supplier,
                "destinationWarehouseId", warehouse,
                "items", List.of(Map.of("productId", book, "qty", 100, "unitCost", 1000))))
                .path("success").asBoolean()).isTrue();

        sell(hakwonPartner);
        sell(schoolPartner);
    }

    private void applyDsre(Long partnerId, String dsreCode) {
        Partner p = partnerRepository.findById(partnerId).orElseThrow();
        p.applyDsreCode(dsreCode);
        partnerRepository.saveAndFlush(p);
    }

    private void sell(Long partner) {
        assertThat(post("/sales/entries", Map.of(
                "salesDate", DATE, "partnerId", partner, "warehouseId", warehouse,
                "items", List.of(Map.of(
                        "productId", book, "shipmentType", "NORMAL_SHIP", "qty", 1,
                        "unitPrice", 10000, "supplyRate", 70,
                        "schoolCode", SCHOOL_CODE))))
                .path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("★★같은 학교코드라도 **거래처에 맞는 구분**이 붙는다 — 학원 거래처는 학원")
    void 거래처별로_구분이_다르다() {
        String hakwon = schoolTypeOf(hakwonPartner);
        String school = schoolTypeOf(schoolPartner);

        assertThat(hakwon)
                .as("‼️학교코드만 보면 먼저 읽힌 행이 이겨 둘이 같아진다").isEqualTo("HAKWON");
        assertThat(school).isEqualTo("SCHOOL");
        assertThat(hakwon).as("두 거래처의 구분이 달라야 한다").isNotEqualTo(school);
    }

    private String schoolTypeOf(Long partnerId) {
        JsonNode res = get("/sales?fromDate=" + DATE + "&toDate=" + DATE
                + "&partnerId=" + partnerId + "&size=50");
        assertThat(res.path("success").asBoolean()).as("조회: %s", res).isTrue();
        for (JsonNode n : res.path("data").path("content")) {
            if (SCHOOL_CODE.equals(n.path("schoolCode").asText())) {
                return n.path("schoolType").asText(null);
            }
        }
        throw new AssertionError("그 거래처의 매출을 못 찾았다: " + res);
    }
}
