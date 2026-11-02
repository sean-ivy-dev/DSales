package com.daesung.sales.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 특약점 배포용 PDF 보관함 — 10/26 특약점 명세 §7 + 10/27 피드백 §7-8.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>파일명에서 종류·거래처코드·기간을 읽고 <b>기준월 = 기간 종료일의 월</b></li>
 *   <li>같은 거래처·종류·기준월을 다시 올리면 <b>교체</b>(행이 늘지 않는다)</li>
 *   <li>파일 하나가 틀려도 <b>나머지는 올라간다</b></li>
 *   <li>거래처를 못 찾아도 <b>파일은 받는다</b>(경고만)</li>
 *   <li>다운로드는 올린 그 바이트를 그대로 돌려준다</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("특약점 PDF 보관함(10/26 §7 · 10/27 §7-8)")
class PartnerDocumentIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-PD" + (System.nanoTime() % 1_000_000L);

    /** 삭제 시험용 DSRE 거래처코드. 다른 케이스와 섞이지 않게 따로 둔다. */
    private static final String DEL_CODE = "9" + (System.nanoTime() % 100_000L);

    private String partnerCode;

    @BeforeAll
    void seed() {
        token();
        partnerCode = "PDC" + SFX;
        createId("/masters/clients", Map.of("code", partnerCode, "name", "특약점거래처", "type", "NORMAL"));
    }

    private byte[] pdf(String marker) {
        // 내용은 바이트가 그대로 오가는지만 보면 된다(실제 PDF 파싱은 하지 않는다).
        return ("%PDF-1.4 " + marker).getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode upload(String fileName, byte[] body) {
        return data(multipart("/closing/partner-documents", body, fileName, "files"));
    }

    private JsonNode list(String code) {
        return data(get("/closing/partner-documents?partnerCode=" + code));
    }

    @Test
    @DisplayName("★파일명에서 메타를 읽는다 — 기준월은 기간 '종료일'의 월")
    void 파일명_파싱() {
        String name = "SALES_" + partnerCode + "_특약점거래처_20260101-20260831.pdf";
        JsonNode r = upload(name, pdf("a"));

        assertThat(r.path("failed").asInt()).as("%s", r).isZero();
        JsonNode line = r.path("lines").get(0);
        assertThat(line.path("result").asText()).isEqualTo("CREATED");
        assertThat(line.path("docType").asText()).isEqualTo("SALES");
        assertThat(line.path("partnerCode").asText()).isEqualTo(partnerCode);
        // 1/1~8/31 → 기준월은 8월. 시작일로 잡으면 8월을 고른 특약점에게 안 보인다.
        assertThat(line.path("baseMonth").asText()).isEqualTo("2026-08");
    }

    @Test
    @DisplayName("★같은 거래처·종류·기준월을 다시 올리면 교체된다 — 행이 늘지 않는다")
    void 재업로드는_교체() {
        String code = "PDR" + SFX;
        createId("/masters/clients", Map.of("code", code, "name", "교체거래처", "type", "NORMAL"));
        String name = "RESULT_" + code + "_교체거래처_20260801-20260831.pdf";

        upload(name, pdf("first"));
        JsonNode second = upload(name, pdf("second"));

        assertThat(second.path("replaced").asInt()).as("%s", second).isEqualTo(1);
        assertThat(second.path("created").asInt()).isZero();

        JsonNode rows = list(code);
        assertThat(rows).as("행이 늘면 안 된다: %s", rows).hasSize(1);

        // 내용도 새것이어야 한다.
        long id = rows.get(0).path("id").asLong();
        byte[] got = getBytes("/closing/partner-documents/" + id + "/download").getBody();
        assertThat(new String(got, StandardCharsets.UTF_8)).contains("second");
    }

    @Test
    @DisplayName("★파일 하나가 틀려도 나머지는 올라간다 — 수백 개를 한 번에 올리는 작업이다")
    void 일부_실패해도_나머지는_등록() {
        String code = "PDM" + SFX;
        createId("/masters/clients", Map.of("code", code, "name", "다건거래처", "type", "NORMAL"));

        JsonNode r = data(multipartMany("/closing/partner-documents", List.of(
                new FilePart("SALES_" + code + "_다건거래처_20260901-20260930.pdf", pdf("ok1")),
                new FilePart("엉뚱한이름.pdf", pdf("bad")),
                new FilePart("RESULT_" + code + "_다건거래처_20260901-20260930.pdf", pdf("ok2")))));
        // (위 FilePart 는 IntegrationTestSupport 의 중첩 record 다)

        assertThat(r.path("created").asInt()).as("%s", r).isEqualTo(2);
        assertThat(r.path("failed").asInt()).isEqualTo(1);
        boolean hasError = false;
        for (JsonNode l : r.path("lines")) {
            if ("ERROR".equals(l.path("result").asText())) {
                hasError = true;
                assertThat(l.path("message").asText()).isNotEmpty();
            }
        }
        assertThat(hasError).isTrue();
    }

    @Test
    @DisplayName("★거래처를 못 찾아도 파일은 받는다 — 막으면 그 달 전체를 못 올린다")
    void 거래처_못찾아도_보관() {
        String unknown = "ZZZ" + SFX;
        JsonNode r = upload("SALES_" + unknown + "_없는거래처_20260701-20260731.pdf", pdf("orphan"));

        assertThat(r.path("created").asInt()).as("%s", r).isEqualTo(1);
        JsonNode line = r.path("lines").get(0);
        assertThat(line.path("result").asText()).isEqualTo("CREATED");
        assertThat(line.path("message").asText()).as("경고는 있어야").contains("못 찾았습니다");

        // 거래처 연결만 비어 있고 파일은 조회된다.
        JsonNode rows = list(unknown);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).hasNonNull("partnerId")).isFalse();
    }

    @Test
    @DisplayName("PDF가 아니거나 종류를 모르면 그 파일만 빠진다")
    void 형식_오류() {
        JsonNode notPdf = upload("SALES_" + partnerCode + "_x_20260101-20260131.txt", pdf("x"));
        assertThat(notPdf.path("failed").asInt()).isEqualTo(1);
        assertThat(notPdf.path("lines").get(0).path("message").asText()).contains("PDF");

        JsonNode badType = upload("EXAM_" + partnerCode + "_x_20260101-20260131.pdf", pdf("x"));
        assertThat(badType.path("failed").asInt()).isEqualTo(1);
        assertThat(badType.path("lines").get(0).path("message").asText()).contains("SALES/RESULT");
    }

    @Test
    @DisplayName("다운로드는 올린 바이트를 그대로 돌려준다")
    void 다운로드() {
        String code = "PDD" + SFX;
        createId("/masters/clients", Map.of("code", code, "name", "다운거래처", "type", "NORMAL"));
        byte[] body = pdf("download-me");
        upload("SALES_" + code + "_다운거래처_20260601-20260630.pdf", body);

        long id = list(code).get(0).path("id").asLong();
        var res = getBytes("/closing/partner-documents/" + id + "/download");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo(body);
    }

    @Test
    @DisplayName("★1MB 넘는 PDF 도 올라간다 — 스프링 기본값(1MB)에 막히던 것(B-6)")
    void 큰파일도_올라간다() {
        // ‼️설정이 없으면 스프링 기본값이 **파일 1MB** 라, 특약점 PDF 2.4MB 가 500 이었다
        //   (프론트 B-6). 외상매출장은 기간이 길면 10쪽 넘어 수 MB 가 된다.
        byte[] big = new byte[2_500_000];      // 2.5MB — 실제로 걸렸던 크기
        System.arraycopy("%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0,
                big, 0, 9);

        String code = "PDB" + SFX;
        createId("/masters/clients", Map.of("code", code, "name", "큰파일거래처", "type", "NORMAL"));

        JsonNode r = upload("SALES_" + code + "_큰파일거래처_20260801-20260831.pdf", big);
        assertThat(r.path("created").asInt()).as("2.5MB 가 등록된다: %s", r).isEqualTo(1);

        // 내용이 잘리지 않았는지까지 본다 — 상한만 올리고 저장이 잘리면 PDF 가 깨진다.
        long id = list(code).get(0).path("id").asLong();
        byte[] got = getBytes("/closing/partner-documents/" + id + "/download").getBody();
        assertThat(got).as("받은 바이트가 올린 것과 같다").hasSize(big.length);
    }

    @Test
    @DisplayName("★삭제 — 잘못 올린 파일을 지운다(프론트 I-5)")
    void 삭제() {
        // 요청 원문: "같은 거래처·종류·기준월은 다시 올리면 교체되지만, **거래처를 잘못 골라
        //   올린 파일은 지울 길이 없어** 특약점 사이트(최근 12개월)에 그대로 보입니다."
        JsonNode up = multipartMany("/closing/partner-documents", List.of(
                new FilePart("SALES_" + DEL_CODE + "_삭제시험_20260701-20260731.pdf",
                        "%PDF-1.4\n삭제대상\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat(up.path("success").asBoolean()).as("%s", up).isTrue();

        long id = -1;
        for (JsonNode r : data(get("/closing/partner-documents?partnerCode=" + DEL_CODE))) {
            id = r.path("id").asLong();
        }
        assertThat(id).as("올린 파일을 찾았다").isPositive();

        JsonNode del = del("/closing/partner-documents/" + id);
        assertThat(del.path("success").asBoolean()).as("%s", del).isTrue();
        assertThat(data(del).path("fileName").asText())
                .as("무엇을 지웠는지 돌려준다").contains("삭제시험");

        // ‼️논리삭제가 아니다 — 목록에서도, 다운로드에서도 사라진다.
        assertThat(data(get("/closing/partner-documents?partnerCode=" + DEL_CODE))).isEmpty();
        assertThat(get("/closing/partner-documents/" + id + "/download")
                .path("success").asBoolean()).as("다운로드도 안 된다").isFalse();
    }
}
