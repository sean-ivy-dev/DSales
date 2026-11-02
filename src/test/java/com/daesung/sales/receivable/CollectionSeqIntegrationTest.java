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

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    @Test
    @DisplayName("★★백필한 수금 뒤에도 번호가 이어진다 — 같은 번호가 둘이 되면 안 된다")
    void 백필뒤에도_번호가_이어진다() {
        // V108 백필이 뒤집은 지점(대성 테스트 2026-11-02 #53) —
        //   V104 는 컬럼만 만들고 기존 수금을 채우지 않아, 배포 전 수금이 전부 공란이었다.
        //   백필은 그걸 채우되 **채번 카운터까지 같이 올려야** 한다. 안 올리면 그 범위의
        //   다음 수금이 1 부터 다시 나와 같은 번호가 둘이 된다.
        //
        // 여기서는 그 상황을 만든다 — 순번을 **일부러 지운 뒤**(백필 전 상태) 백필과 같은
        // 규칙으로 메우고, 그 다음 등록이 겹치지 않는지 본다.
        String day = "2097-03-03";
        long c1 = collect(partnerB, day, 1_000L).path("id").asLong();
        long c2 = collect(partnerB, day, 2_000L).path("id").asLong();

        // 백필 전 상태 재현: 두 건의 순번을 비운다.
        jdbc.update("UPDATE collection SET coll_seq = NULL WHERE id IN (?, ?)", c1, c2);
        // V108 (1)과 같은 규칙으로 메운다.
        jdbc.update("""
                UPDATE collection c
                  JOIN (SELECT x.id,
                               COALESCE(m.mx,0) + ROW_NUMBER() OVER
                                 (PARTITION BY x.partner_id, x.coll_date ORDER BY x.id) rn
                          FROM collection x
                          LEFT JOIN (SELECT partner_id, coll_date, MAX(coll_seq) mx
                                       FROM collection WHERE coll_seq IS NOT NULL
                                      GROUP BY partner_id, coll_date) m
                                 ON m.partner_id=x.partner_id AND m.coll_date=x.coll_date
                         WHERE x.coll_seq IS NULL AND x.deleted_at IS NULL) t ON t.id=c.id
                   SET c.coll_seq = t.rn
                """);
        // V108 (2)와 같은 규칙으로 카운터를 맞춘다.
        jdbc.update("""
                INSERT INTO seq_registry (seq_name, seq_val)
                SELECT CONCAT('seq_collection_seq:', partner_id, ':',
                              DATE_FORMAT(coll_date, '%Y-%m-%d')), MAX(coll_seq)
                  FROM collection WHERE coll_seq IS NOT NULL
                 GROUP BY partner_id, coll_date
                ON DUPLICATE KEY UPDATE seq_val = GREATEST(seq_registry.seq_val, VALUES(seq_val))
                """);

        Integer s1 = jdbc.queryForObject("SELECT coll_seq FROM collection WHERE id=?",
                Integer.class, c1);
        Integer s2 = jdbc.queryForObject("SELECT coll_seq FROM collection WHERE id=?",
                Integer.class, c2);
        assertThat(List.of(s1, s2)).as("백필로 1,2 가 채워진다").containsExactly(1, 2);

        // ★다음 수금이 1 로 돌아가지 않는다.
        int next = collect(partnerB, day, 3_000L).path("collSeq").asInt();
        assertThat(next).as("백필 뒤에도 이어진다").isEqualTo(3);

        // 그 범위에 같은 번호가 둘인 수금이 없다.
        Integer dup = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                  SELECT coll_seq FROM collection
                   WHERE partner_id = ? AND coll_date = ? AND deleted_at IS NULL
                   GROUP BY coll_seq HAVING COUNT(*) > 1) d
                """, Integer.class, partnerB, day);
        assertThat(dup).as("중복 번호 없음").isZero();
    }
}
