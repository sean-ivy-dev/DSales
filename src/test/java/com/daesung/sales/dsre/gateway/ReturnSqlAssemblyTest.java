package com.daesung.sales.dsre.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 회수 SQL <b>조립</b>이 성한지 — DSRE 연동 없이 보는 안전망.
 *
 * <p>‼️<b>왜 이 테스트가 생겼나.</b> 회수 명세 SQL 을 {@code String.format} 으로 조립하면서
 * SQL 주석에 퍼센트 기호를 썼더니, 그 뒤 글자가 포맷 지정자로 읽혀
 * {@code UnknownFormatConversionException} 이 났다 — <b>회수 명세가 통째로 500</b>이었고
 * 배포 후 라이브를 찍어 보고서야 알았다(2026-11-24). DSRE 연동이 테스트에서 꺼져 있어
 * ({@code daesung.dsre.enabled}) 1,119건 중 아무것도 그걸 잡지 못했다.
 *
 * <p>→ 조립만 떼어 두면 연동 없이도 못박을 수 있다. <b>조립은 토큰 치환</b>이라
 * 퍼센트 함정이 다시 생기지 않지만, 자리표가 남는 사고는 여전히 가능하다.
 */
@DisplayName("회수 SQL 조립 — 자리표 치환·잔여 퍼센트(2026-11-24)")
class ReturnSqlAssemblyTest {

    @Test
    @DisplayName("★★모든 구분에서 **자리표가 남지 않고**, 포맷 지정자로 읽힐 퍼센트도 없다")
    void 조립_성함() {
        for (LogisMode mode : LogisMode.values()) {
            for (String sql : new String[]{
                    JdbcDsreGateway.returnDetailSql(mode),
                    JdbcDsreGateway.returnPeriodSql(mode)}) {
                assertThat(sql)
                        .as("%s — 자리표가 남으면 DSRE 가 문법 오류를 낸다", mode)
                        .doesNotContain("{{SOURCE}}");
                assertThat(sql)
                        .as("‼️%s — 퍼센트가 남아 있으면 format 으로 되돌릴 때 또 깨진다", mode)
                        .doesNotContain("%");
                // 바인딩 자리는 그대로 있어야 한다(기간) — 조립이 그걸 먹으면 조회가 안 돈다.
                assertThat(sql.chars().filter(c -> c == '?').count())
                        .as("%s — 기간 바인딩 2개", mode).isGreaterThanOrEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("★구분마다 원본이 다르다 — ALL 은 둘을 UNION 하고, 사고/반품은 자기 표만 본다")
    void 구분별_원본() {
        String all = JdbcDsreGateway.returnDetailSql(LogisMode.ALL);
        assertThat(all).contains("tbl_wol_dtl", "tbl_wol_dtl_b", "UNION ALL");

        assertThat(JdbcDsreGateway.returnDetailSql(LogisMode.ACCIDENT))
                .contains("tbl_wol_dtl").doesNotContain("UNION ALL");
        assertThat(JdbcDsreGateway.returnDetailSql(LogisMode.NORMAL))
                .contains("tbl_wol_dtl_b").doesNotContain("UNION ALL");
    }

    @Test
    @DisplayName("‼️**명세와 총계는 다른 원본을 쓴다** — 한쪽 상수를 둘 다에 쓰면 조용히 틀린다")
    void 명세와_총계는_원본이_다르다() {
        // 명세는 거래처·시행을 실은 판이라 그 칸이 들어 있다.
        assertThat(JdbcDsreGateway.returnDetailSql(LogisMode.ALL))
                .contains("CUST_CD", "DTL_CD", "tbl_request_info");
        // 총계는 자재·수량·일자만 쓴다 — 거래처 조인을 끌고 오면 느려지고 행이 달라진다.
        assertThat(JdbcDsreGateway.returnPeriodSql(LogisMode.ALL))
                .doesNotContain("tbl_request_info");
    }

    @Test
    @DisplayName("명세에 상품·시행·거래처 조인이 들어 있다 — 발주처 2026-11-23 4번 8쪽")
    void 명세_조인() {
        String sql = JdbcDsreGateway.returnDetailSql(LogisMode.ALL);
        assertThat(sql).contains("tbl_product_dtl", "tbl_product_info", "tbl_cust_info");
        // ‼️전부 LEFT JOIN 이어야 한다 — 마스터가 비면 행이 통째로 사라진다.
        assertThat(sql).contains("LEFT JOIN tbl_product_dtl")
                .contains("LEFT JOIN tbl_product_info")
                .contains("LEFT JOIN tbl_cust_info");
    }
}
