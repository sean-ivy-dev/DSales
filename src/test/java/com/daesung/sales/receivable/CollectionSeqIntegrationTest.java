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
 * 수금순번 — 10/27 명세 §6-1, 프론트 B-7.
 *
 * <p>명세: "기존 매출프로그램처럼 해당 수금일자 안에서 1, 2, 3… <b>순번만 표시</b>한다."
 * 프론트 B-7: "레거시는 <b>거래처·수금일자마다 모든 수금에</b> 순번을 1부터 매기고,
 * 수금 등록 창의 순번 드롭다운으로 그 수금을 <b>불러와 고칩니다</b>.
 * 지금은 여러 건을 한 번에 등록한 수금에만 {@code voucherNo} 가 있고,
 * <b>한 건씩 등록한 수금은 비어 있습니다</b>."
 *
 * <p>★레거시 원본이 그대로다({@code 수금등록.vb}) —
 * {@code SELECT collSeq FROM amtData WHERE collDate LIKE 'yyyyMMdd%' AND custCode=? ORDER BY collSeq}
 * 로 드롭다운을 채우고 {@code max+1} 을 새 번호로 덧붙인다. 키는 (수금일자+거래처+순번)이다.
 *
 * <p>고정하는 것
 * <ul>
 *   <li><b>단건 등록도</b> 순번을 받는다(예전엔 묶음 등록분만 번호가 있었다)</li>
 *   <li>범위는 <b>거래처 × 수금일자</b> — 날짜만이 아니다</li>
 *   <li>그 순번으로 수금 한 건을 불러올 수 있다</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("수금순번(§6-1 · 프론트 B-7)")
class CollectionSeqIntegrationTest extends IntegrationTestSupport {

    private static final String SFX = "-CS" + (System.nanoTime() % 1_000_000L);
    private static final String DAY = "2041-04-10";

    private Long partnerA;
    private Long partnerB;

    @BeforeAll
    void seed() {
        token();
        partnerA = createId("/masters/clients",
                Map.of("code", "CSA" + SFX, "name", "수금순번가", "type", "NORMAL"));
        partnerB = createId("/masters/clients",
                Map.of("code", "CSB" + SFX, "name", "수금순번나", "type", "NORMAL"));
    }

    private JsonNode collect(Long partnerId, String date, long amt) {
        JsonNode r = post("/closing/collections", Map.of("collDate", date,
                "partnerId", partnerId, "collType", "CASH", "collAmt", amt));
        assertThat(r.path("success").asBoolean()).as("%s", r).isTrue();
        return data(r);
    }

    @Test
    @DisplayName("★한 건씩 등록해도 순번이 붙는다 — 1,2,3")
    void 단건도_순번() {
        // ‼️뒤집힌 지점. 예전엔 묶음 등록(/batch)에만 전표코드가 있어, 한 건씩 넣은 수금은
        //   번호가 비었다 — 담당자가 드롭다운에서 고를 수 없으니 불러와 고칠 길이 없었다.
        List<Integer> seqs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            seqs.add(collect(partnerA, DAY, 10_000L * (i + 1)).path("collSeq").asInt());
        }
        assertThat(seqs).as("등록 순서대로 1,2,3").containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("범위는 거래처 × 수금일자 — 날짜만이 아니다")
    void 거래처별로_독립() {
        String day = "2041-04-11";
        int a1 = collect(partnerA, day, 1_000).path("collSeq").asInt();
        int b1 = collect(partnerB, day, 2_000).path("collSeq").asInt();
        int a2 = collect(partnerA, day, 3_000).path("collSeq").asInt();

        assertThat(a1).as("가 거래처 첫 건").isEqualTo(1);
        assertThat(b1).as("나 거래처도 1부터 — 날짜만 보면 2가 됐을 자리").isEqualTo(1);
        assertThat(a2).as("가 거래처 두 번째").isEqualTo(2);
    }

    @Test
    @DisplayName("날짜가 바뀌면 다시 1부터")
    void 날짜가_바뀌면_리셋() {
        assertThat(collect(partnerA, "2041-05-01", 1_000).path("collSeq").asInt()).isEqualTo(1);
        assertThat(collect(partnerA, "2041-05-02", 1_000).path("collSeq").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("거래처 + 수금일자 + 순번으로 그 수금 한 건만 불러온다(B-7②)")
    void 순번으로_조회() {
        String day = "2041-06-20";
        collect(partnerB, day, 11_000);
        JsonNode second = collect(partnerB, day, 22_000);
        int seq = second.path("collSeq").asInt();
        assertThat(seq).isEqualTo(2);

        JsonNode found = data(get("/closing/collections?partnerId=" + partnerB
                + "&fromDate=" + day + "&toDate=" + day + "&collSeq=" + seq + "&size=10"));

        assertThat(found.path("totalElements").asLong()).as("그 한 건만: %s", found).isEqualTo(1);
        JsonNode row = found.path("content").get(0);
        assertThat(row.path("collSeq").asInt()).isEqualTo(seq);
        assertThat(row.path("collAmt").asLong()).as("두 번째로 넣은 그 수금").isEqualTo(22_000);
    }
}
