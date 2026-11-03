package com.daesung.sales.dealer;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.partner.entity.PartnerDsreCode;
import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpStatus;

/**
 * 특약점 사이트용 <b>PDF 보관함</b>(10/26 §7 · §10-3).
 *
 * <p>§7 — "보관·열람: 사이트에서 <b>월을 골라</b> 지난달 PDF를 볼 수 있다. 범위는
 * <b>최근 12개월</b>이다. … 열람 권한: 서버가 <b>로그인 거래처 본인 파일인지 확인한 뒤</b>
 * 내려준다. 현행 공개 폴더 직접 링크 방식은 <b>파일명만 알면 남의 PDF가 열릴 수 있으니</b>,
 * 확인 후 인증 다운로드로 바꾼다."
 *
 * <p>★<b>방향이 뒤집힌 지점</b>: 현행 DSRE 는 파일을 사이트 서버에 SFTP 로 밀어넣었다.
 * 신규는 우리가 보관하고 사이트가 받아간다 — 밀어넣으면 "본인 파일인지 확인한 뒤 내려준다"를
 * 우리가 할 자리가 없다. 그래서 업로드는 백오피스, 열람은 이 경로다.
 *
 * <p>★<b>급소는 남의 파일</b>이다. "권한 없음"이라고 답하면 그 파일이 있다는 사실을 알려준다 —
 * <b>없음과 같은 답</b>이어야 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("특약점 사이트 PDF 보관함(§7)")
class DealerDocumentIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = String.valueOf(System.nanoTime() % 100_000L);
    /** 파일명 두 번째 토막이 **DSRE 거래처코드**다(§7 파일명 규칙). */
    private static final String MY_DSRE = "7" + SFX;
    private static final String OTHER_DSRE = "8" + SFX;
    private static final String MY_CODE = "DDA-" + SFX;
    private static final String OTHER_CODE = "DDB-" + SFX;

    private long myDocId;

    @org.springframework.beans.factory.annotation.Autowired
    private com.daesung.sales.partner.repository.PartnerDsreCodeRepository dsreCodeRepository;

    @BeforeAll
    void seed() {
        token();
        // 거래처 2곳. ‼️DSRE 코드는 **등록 API 입력이 아니다** — DSRE 동기화가 채우는 칸이라
        //   매핑표(V100)에 직접 심는다(운영에서도 그 경로로 들어온다).
        long mine = createId("/masters/clients",
                Map.of("code", MY_CODE, "name", "내거래처", "type", "NORMAL"));
        long other = createId("/masters/clients",
                Map.of("code", OTHER_CODE, "name", "남의거래처", "type", "NORMAL"));
        dsreCodeRepository.save(PartnerDsreCode.of(mine, MY_DSRE));
        dsreCodeRepository.save(PartnerDsreCode.of(other, OTHER_DSRE));

        // 내 PDF 2건(매출·성적) + 남의 PDF 1건을 백오피스로 올린다.
        JsonNode up = multipartMany("/closing/partner-documents", List.of(
                new FilePart("SALES_" + MY_DSRE + "_내거래처_20260901-20260930.pdf", pdf("내매출")),
                new FilePart("RESULT_" + MY_DSRE + "_내거래처_20260801-20260831.pdf", pdf("내성적")),
                new FilePart("SALES_" + OTHER_DSRE + "_남의거래처_20260901-20260930.pdf", pdf("남의매출")),
                // ★기본 범위(최근 12개월) 밖에 있을 **옛 파일**. §7 "이보다 오래된 파일은
                //   지우지 않는다" — 지우지 않으므로 범위를 넓히면 나와야 한다.
                new FilePart("SALES_" + MY_DSRE + "_내거래처_20200301-20200331.pdf", pdf("옛매출"))));
        assertThat(up.path("success").asBoolean()).as("%s", up).isTrue();

        // ‼️종류만으로 고르면 옛 파일(2020-03)까지 걸려 마지막 것이 잡힌다 — 기준월로 특정한다.
        for (JsonNode r : data(get("/closing/partner-documents?partnerCode=" + MY_DSRE))) {
            if ("SALES".equals(r.path("docType").asText())
                    && "2026-09".equals(r.path("baseMonth").asText())) {
                myDocId = r.path("id").asLong();
            }
        }
        assertThat(myDocId).as("내 매출 PDF 가 보관됐다").isPositive();
    }

    /** PDF 로 인식될 최소한의 바이트. 내용 자체는 검증에 쓰지 않는다. */
    private static byte[] pdf(String tag) {
        return ("%PDF-1.4\n" + tag + "\n%%EOF\n").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("키가 없으면 401 — 이 경로도 열려 있지 않다")
    void 키없으면_거부() {
        JsonNode r = getAsDealerSite("/dealer-site/documents?partnerCode=" + MY_CODE, null);
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("★내 PDF 만 나온다 — 남의 것은 섞이지 않는다")
    void 내것만_나온다() {
        JsonNode r = getAsDealerSite("/dealer-site/documents?partnerCode=" + MY_CODE
                + "&fromMonth=2026-01&toMonth=2026-12", DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();

        // 2026년 범위로 물었으니 2020년 옛 파일은 당연히 빠진다 — 그건 아래 범위 테스트가 본다.
        assertThat(data(r)).as("그 범위의 내 2건(매출·성적)").hasSize(2);
        for (JsonNode d : data(r)) {
            assertThat(d.path("partnerCode").asText()).isEqualTo(MY_DSRE);
            assertThat(d.path("fileName").asText()).doesNotContain("남의");
        }
    }

    @Test
    @DisplayName("종류로 걸러 부를 수 있다(SALES/RESULT)")
    void 종류필터() {
        JsonNode r = getAsDealerSite("/dealer-site/documents?partnerCode=" + MY_CODE
                + "&docType=RESULT&fromMonth=2026-01&toMonth=2026-12", DEALER_SITE_KEY);

        assertThat(data(r)).hasSize(1);
        assertThat(data(r).get(0).path("docType").asText()).isEqualTo("RESULT");
        assertThat(data(r).get(0).path("baseMonth").asText()).as("기준월=기간 종료일의 월")
                .isEqualTo("2026-08");
    }

    @Test
    @DisplayName("★기간을 안 주면 최근 12개월이다 — 옛 파일은 빠지고, 범위를 넓히면 나온다")
    void 기본범위는_최근12개월() {
        // §7 "범위는 **최근 12개월**이다. 이보다 오래된 파일은 **지우지 않는다**."
        // ‼️날짜를 고정값으로 기대하지 않는다 — 오늘이 언제든 2020년 파일은 범위 밖이다.
        JsonNode dflt = getAsDealerSite("/dealer-site/documents?partnerCode=" + MY_CODE,
                DEALER_SITE_KEY);
        assertThat(dflt.path("success").asBoolean()).as("%s", dflt).isTrue();
        for (JsonNode d : data(dflt)) {
            assertThat(d.path("baseMonth").asText())
                    .as("기본 범위엔 2020년 파일이 없다").isNotEqualTo("2020-03");
        }

        // 지우지 않았으니 범위를 넓히면 나온다.
        JsonNode wide = getAsDealerSite("/dealer-site/documents?partnerCode=" + MY_CODE
                + "&fromMonth=2020-01&toMonth=2026-12", DEALER_SITE_KEY);
        boolean found = false;
        for (JsonNode d : data(wide)) {
            if ("2020-03".equals(d.path("baseMonth").asText())) {
                found = true;
            }
        }
        assertThat(found).as("옛 파일은 지워지지 않았다: %s", data(wide)).isTrue();
    }

    @Test
    @DisplayName("★다운로드 — 올린 바이트가 그대로 내려온다")
    void 다운로드() {
        var resp = getBytesAsDealerSite("/dealer-site/documents/" + myDocId
                + "?partnerCode=" + MY_CODE, DEALER_SITE_KEY);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(resp.getBody(), StandardCharsets.UTF_8)).contains("내매출");
    }

    @Test
    @DisplayName("★거래처를 못 찾으면 PARTNER_NOT_LINKED — 특약점팀 전달(2026-11-03 확인 a)")
    void 거래처미연결_전용코드() {
        JsonNode r = getAsDealerSite("/dealer-site/documents?partnerCode=없는코드" + SFX, DEALER_SITE_KEY);
        assertThat(r.path("success").asBoolean()).isFalse();
        assertThat(r.path("error").path("code").asText())
                .as("다른 /dealer-site/** 경로와 같은 코드여야 한다").isEqualTo("PARTNER_NOT_LINKED");
    }

    @Test
    @DisplayName("★★남의 PDF 는 '없음'으로 답한다 — '권한 없음'이면 그 파일이 있다는 걸 알려준다")
    void 남의것은_없음() {
        var resp = getBytesAsDealerSite("/dealer-site/documents/" + myDocId
                + "?partnerCode=" + OTHER_CODE, DEALER_SITE_KEY);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("내려주면 안 된다").isFalse();
        String body = (resp.getBody() == null) ? "" : new String(resp.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("찾을 수 없습니다");
        assertThat(body).as("권한 얘기를 꺼내면 존재가 드러난다").doesNotContain("권한");
    }
}
