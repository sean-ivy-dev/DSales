package com.daesung.sales.dsre.gateway;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DSRE2(MySQL) 직접 연결 구현. 저장함수/쿼리를 호출만 하고 로직은 재구현하지 않음.
 * daesung.dsre.enabled=true일 때만 활성(dsreJdbcTemplate 필요).
 */
@Component
@ConditionalOnProperty(name = "daesung.dsre.enabled", havingValue = "true")
@RequiredArgsConstructor
public class JdbcDsreGateway implements DsreGateway {

    private final JdbcTemplate dsreJdbcTemplate;
    private final org.springframework.transaction.support.TransactionTemplate dsreTransactionTemplate;

    @Override
    public Integer reqInwon(int reqCd) {
        // DSRE2 저장함수 호출(재구현 아님). NULL 가능 → Integer.
        return dsreJdbcTemplate.queryForObject("SELECT FUNC_REQINWON_GET(?)", Integer.class, reqCd);
    }

    private static final String MATERIAL_SQL = """
            SELECT
              COALESCE(SUM(CASE WHEN c.NAME NOT IN ('OMR','단행본','책자','라벨') THEN lc.REQCNT*cost.PAPER ELSE 0 END),0) paper_amt,
              COALESCE(SUM(CASE WHEN c.NAME='OMR' THEN lc.REQCNT*cost.OMR ELSE 0 END),0) omr_amt,
              COALESCE(SUM(CASE WHEN c.NAME IN ('단행본','책자') THEN lc.REQCNT*cost.ETC ELSE 0 END),0) etc_amt,
              COALESCE(SUM(CASE WHEN c.NAME='라벨' THEN lc.REQCNT*cost.LABEL ELSE 0 END),0) label_amt,
              COALESCE(MAX(cost.PACKTYPE),0) packtype, COALESCE(MAX(cost.BASIC),0) basic, COALESCE(MAX(cost.TRADE),0) trade
            FROM tbl_logis_cnt lc
              JOIN tbl_resource_info r ON lc.RES_CD=r.RES_CD
              JOIN tbl_materials_info m ON r.MAT_CD=m.MAT_CD
              JOIN tbl_comon_info c ON m.MAT_GN=c.CMD_CD
              JOIN tbl_request_info req ON lc.REQ_CD=req.REQ_CD
              JOIN tbl_logis_cost cost ON cost.DTL_CD=req.DTL_CD
            WHERE lc.REQ_CD=? AND lc.RES_GN='R'
            """;

    @Override
    public OutboundLogisCost calcOutbound(int reqCd) {
        // 1) 자재금액 + 단가정보(DSRE2에서 집계)
        Map<String, Object> r = dsreJdbcTemplate.queryForMap(MATERIAL_SQL, reqCd);
        long paper = num(r.get("paper_amt"));
        long omr = num(r.get("omr_amt"));
        long etc = num(r.get("etc_amt"));
        long label = num(r.get("label_amt"));
        int packtype = (int) num(r.get("packtype"));
        int basic = (int) num(r.get("basic"));
        int trade = (int) num(r.get("trade"));
        long material = paper + omr + etc + label;

        // 2) 인원(PACKTYPE별 DSRE2 함수 호출). 1·2=PACKTYPE1, 3(SET)=PACKTYPE2.
        // SQL을 조립하지 않고 완성된 상수 중에서 고른다 — 문자열 결합이 없으면 인젝션 여지 자체가 없다.
        String sql = (packtype == 3) ? INWON_PACKTYPE2_SQL : INWON_PACKTYPE1_SQL;
        Integer inwonObj = dsreJdbcTemplate.queryForObject(sql, Integer.class, reqCd);
        int inwon = (inwonObj == null) ? 0 : inwonObj;

        long labor = (long) inwon * (basic + trade);
        return new OutboundLogisCost(reqCd, paper, omr, etc, label, material,
                inwon, basic, trade, labor, material + labor);
    }

    /** PACKTYPE별 인원 산출 함수 호출. 조립 대신 완성된 상수 2개로 둔다(정적분석 SQLi 0건 기준). */
    private static final String INWON_PACKTYPE1_SQL = "SELECT FUNC_REQINWON_GET_PACKTYPE1(?)";
    private static final String INWON_PACKTYPE2_SQL = "SELECT FUNC_REQINWON_GET_PACKTYPE2(?)";

    private static long num(Object o) {
        return (o == null) ? 0L : ((Number) o).longValue();
    }

    // ── 기간 출고 물류비 집계 (물류비계산2.vb OutData) ─────────────────────────────
    // 자재금액: 기간(REQ_DATE)·구분(APPLY_GN)·취소제외(STATE) 필터로 4분류 합산.
    // mode/cancel 조각은 LogisMode enum·불리언에서만 생성(사용자 문자열 미주입).
    private static final String OUT_MATERIAL_SQL = """
            SELECT
              COALESCE(SUM(CASE WHEN c.NAME NOT IN ('OMR','단행본','책자','라벨') THEN lc.REQCNT*cost.PAPER ELSE 0 END),0) paper_amt,
              COALESCE(SUM(CASE WHEN c.NAME='OMR' THEN lc.REQCNT*cost.OMR ELSE 0 END),0) omr_amt,
              COALESCE(SUM(CASE WHEN c.NAME IN ('단행본','책자') THEN lc.REQCNT*cost.ETC ELSE 0 END),0) etc_amt,
              COALESCE(SUM(CASE WHEN c.NAME='라벨' THEN lc.REQCNT*cost.LABEL ELSE 0 END),0) label_amt
            FROM tbl_logis_cnt lc
              JOIN tbl_resource_info r ON lc.RES_CD=r.RES_CD
              JOIN tbl_materials_info m ON r.MAT_CD=m.MAT_CD
              JOIN tbl_comon_info c ON m.MAT_GN=c.CMD_CD
              JOIN tbl_request_info req ON lc.REQ_CD=req.REQ_CD
              JOIN tbl_logis_cost cost ON cost.DTL_CD=req.DTL_CD
            WHERE lc.RES_GN='R' AND req.REQ_DATE BETWEEN ? AND ?
              AND (? IS NULL OR req.APPLY_GN = ?)
              AND (? = 1 OR req.STATE != 'C')
            """;

    // 인원비: 신청(REQ) 단위로 인원 1회 산정(PACKTYPE별 함수) 후 인별 단가(BASIC+TRADE)로 합산.
    // 레거시 @vjob rownum 트릭(신청당 인원 1회)을 GROUP BY REQ_CD로 동등 구현.
    private static final String OUT_LABOR_SQL = """
            SELECT COALESCE(SUM(inwon),0) inwon_total,
                   COALESCE(SUM(inwon*(basic+trade)),0) labor,
                   COUNT(*) req_cnt
            FROM (
              SELECT req.REQ_CD,
                CASE WHEN MAX(cost.PACKTYPE)=3 THEN FUNC_REQINWON_GET_PACKTYPE2(req.REQ_CD)
                     ELSE FUNC_REQINWON_GET_PACKTYPE1(req.REQ_CD) END inwon,
                MAX(cost.BASIC) basic, MAX(cost.TRADE) trade
              FROM tbl_logis_cnt lc
                JOIN tbl_request_info req ON lc.REQ_CD=req.REQ_CD
                JOIN tbl_logis_cost cost ON cost.DTL_CD=req.DTL_CD
              WHERE lc.RES_GN='R' AND req.REQ_DATE BETWEEN ? AND ?
                AND (? IS NULL OR req.APPLY_GN = ?)
                AND (? = 1 OR req.STATE != 'C')
              GROUP BY req.REQ_CD ) t
            """;

    // ── 출고 물류비 명세 행(28p 그리드) — 물류비계산2.vb 그리드 ────────────────────
    // 정본 28p가 요구하는 컬럼(접수일자·상품·학년·시행·거래처·자재/시험지/OMR/기타·인원·작업비·합계)을
    // 상품×학년×시행×신청×거래처 단위로 낸다.
    //
    // ★인원을 이 GROUP BY 안에서 SUM 하지 않는 이유
    //   FUNC_REQINWON_GET_PACKTYPE*는 신청(REQ_CD) 단위 값이라, 자재 행마다 곱해 더하면
    //   자재 종류 수만큼 부풀려진다. 레거시가 rownum 트릭으로 "신청당 1회"를 만든 것도 같은 이유다.
    //   여기서는 신청이 GROUP BY에 들어 있으므로 MAX()로 한 번만 집는다.
    //
    // ★구분(APPLY_GN)·취소(STATE)로 <b>걸러서 내지 않는다</b> — 행에 담아 보내고 자바에서 판정한다.
    //   마감된 달은 저장해 둔 스냅샷을 읽는데, SQL에서 걸러 버리면 라이브는 SQL 조건,
    //   스냅샷은 자바 조건으로 판정이 두 벌이 되어 마감 전후로 숫자가 달라질 수 있다.
    //
    // ★거래처 <b>코드는 신청에서</b>, 이름만 거래처 마스터에서 가져온다.
    //   마스터에 없는 코드가 실재한다(복제본 실측: 7월 1,600건 중 638건 미매칭).
    //   조인 결과의 코드를 쓰면 그런 신청이 전부 코드 NULL이 되어, 거래처 축으로 묶을 때
    //   서로 다른 거래처가 한 덩어리로 뭉친다. 이름은 못 붙여도 코드로는 갈라져야 한다.
    private static final String OUT_DETAIL_SQL = """
            SELECT req.REQ_DATE, req.REQ_CD, pi.PROD_CD, pi.PROD_NM,
                   pd.GRADE, req.DTL_CD, pd.DTL_NM, req.CUST_CD,
              -- ★거래처명은 CUST_NM(상호)이 아니라 CUST_FNM(지사 풀네임)이다.
              --   레거시 물류비계산2.vb 가 `max(CUST_FNM) AS '거래처명'` 으로 쓴다. 우리가 상호를
              --   쓰고 있어 화면에 짧은 이름이 나갔다(2026-09-17 레거시 대조로 발견).
                   cu.CUST_FNM,
              -- 지역=특약지역명(거래처의 관할), 도시명=도시지역명. 레거시가 이 둘을 나눠 보여 준다.
                   cu.CITY_NM, city.CITY_NM AS city_nm, sch.SCH_NM,
              COALESCE(SUM(lc.REQCNT),0) mat_qty,
              COALESCE(SUM(CASE WHEN c.NAME NOT IN ('OMR','단행본','책자','라벨') THEN lc.REQCNT ELSE 0 END),0) paper_qty,
              COALESCE(SUM(CASE WHEN c.NAME NOT IN ('OMR','단행본','책자','라벨') THEN lc.REQCNT*cost.PAPER ELSE 0 END),0) paper_amt,
              COALESCE(SUM(CASE WHEN c.NAME='OMR' THEN lc.REQCNT ELSE 0 END),0) omr_qty,
              COALESCE(SUM(CASE WHEN c.NAME='OMR' THEN lc.REQCNT*cost.OMR ELSE 0 END),0) omr_amt,
              COALESCE(SUM(CASE WHEN c.NAME IN ('단행본','책자','라벨') THEN lc.REQCNT ELSE 0 END),0) etc_qty,
              COALESCE(SUM(CASE WHEN c.NAME IN ('단행본','책자') THEN lc.REQCNT*cost.ETC
                                WHEN c.NAME='라벨' THEN lc.REQCNT*cost.LABEL ELSE 0 END),0) etc_amt,
              MAX(CASE WHEN cost.PACKTYPE=3 THEN FUNC_REQINWON_GET_PACKTYPE2(req.REQ_CD)
                       ELSE FUNC_REQINWON_GET_PACKTYPE1(req.REQ_CD) END) inwon,
              MAX(cost.BASIC) basic, MAX(cost.TRADE) trade,
              MAX(req.APPLY_GN) apply_gn, MAX(req.STATE='C') canceled
            FROM tbl_logis_cnt lc
              JOIN tbl_resource_info r ON lc.RES_CD=r.RES_CD
              JOIN tbl_materials_info m ON r.MAT_CD=m.MAT_CD
              JOIN tbl_comon_info c ON m.MAT_GN=c.CMD_CD
              JOIN tbl_request_info req ON lc.REQ_CD=req.REQ_CD
              JOIN tbl_logis_cost cost ON cost.DTL_CD=req.DTL_CD
              JOIN tbl_product_dtl pd ON pd.DTL_CD=req.DTL_CD
              JOIN tbl_product_info pi ON pi.PROD_CD=pd.PROD_CD
              LEFT JOIN tbl_cust_info cu ON cu.CUST_CD=req.CUST_CD
              LEFT JOIN tbl_city_info city ON city.CITY_CD=cu.CITY_CD
              -- ‼️레거시는 학교를 **내부 조인**으로 걸어 학교가 없으면 행이 통째로 사라진다.
              --   우리 복제본은 학교·학원 매칭이 5%뿐이라 그대로 베끼면 95%가 증발한다 → LEFT JOIN.
              LEFT JOIN (SELECT MGR_CD, SCH_NM FROM tbl_school_info
                         UNION ALL
                         SELECT MGR_CD, HAK_NM FROM tbl_hakwon_info) sch ON sch.MGR_CD=req.MGR_CD
            WHERE lc.RES_GN='R' AND req.REQ_DATE BETWEEN ? AND ?
              -- ★시행 다중 필터. 좌측 연도·시행 트리가 여러 시행을 한 번에 건다(T-4).
              --   ‼️IN 절을 문자열로 조립하지 않는다(게이트규칙) — 쉼표로 이은 값 하나를
              --     바인딩해 FIND_IN_SET 으로 푼다. 값이 없으면 조건 자체가 참이다.
              AND (? IS NULL OR FIND_IN_SET(req.DTL_CD, ?) > 0)
            GROUP BY req.REQ_DATE, req.REQ_CD, pi.PROD_CD, pi.PROD_NM,
                     pd.GRADE, req.DTL_CD, pd.DTL_NM, req.CUST_CD, cu.CUST_FNM,
                     cu.CITY_NM, city.CITY_NM, sch.SCH_NM
            ORDER BY pi.PROD_CD, pd.GRADE DESC, req.DTL_CD DESC, req.CUST_CD, req.REQ_DATE
            """;

    @Override
    public List<LogisCostDetailRow> outboundDetail(LocalDate from, LocalDate to) {
        return outboundDetail(from, to, null);
    }

    @Override
    public List<LogisCostDetailRow> outboundDetail(LocalDate from, LocalDate to,
                                                   java.util.Collection<Integer> dtlCds) {
        String f = from.format(YYYYMMDD), t = to.format(YYYYMMDD);
        String dtlList = csv(dtlCds);
        return dsreJdbcTemplate.query(OUT_DETAIL_SQL, (rs, i) -> {
            int inwon = rs.getInt("inwon");
            long basicAmt = (long) inwon * rs.getInt("basic");
            long tradeAmt = (long) inwon * rs.getInt("trade");
            long matAmt = rs.getLong("paper_amt") + rs.getLong("omr_amt") + rs.getLong("etc_amt");
            return new LogisCostDetailRow(
                    parseYmd(rs.getString("REQ_DATE")), rs.getInt("REQ_CD"),
                    rs.getString("PROD_CD"), rs.getString("PROD_NM"),
                    rs.getString("GRADE"), rs.getInt("DTL_CD"), rs.getString("DTL_NM"),
                    rs.getString("CUST_CD"), rs.getString("CUST_FNM"),
                    rs.getString("CITY_NM"), rs.getString("city_nm"), rs.getString("SCH_NM"),
                    rs.getLong("mat_qty"),
                    rs.getLong("paper_qty"), rs.getLong("paper_amt"),
                    rs.getLong("omr_qty"), rs.getLong("omr_amt"),
                    rs.getLong("etc_qty"), rs.getLong("etc_amt"),
                    inwon, basicAmt, tradeAmt, matAmt + basicAmt + tradeAmt,
                    rs.getString("apply_gn"), rs.getBoolean("canceled"), false);
        }, f, t, dtlList, dtlList);
    }

    /** DSRE2 날짜는 yyyyMMdd 문자열이다(varchar). 형식이 어긋나면 null — 행을 버리지는 않는다. */
    private static LocalDate parseYmd(String v) {
        if (v == null || v.length() < 8) {
            return null;
        }
        try {
            return LocalDate.parse(v.substring(0, 8), YYYYMMDD);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    @Override
    public PeriodLogisCost calcOutboundPeriod(LocalDate from, LocalDate to, LogisMode mode, boolean includeCancel) {
        // 필터를 SQL 조각으로 이어붙이지 않고 바인딩 값으로 넘긴다(게이트규칙: 파라미터 바인딩 전수 적용).
        //   applyGn = null 이면 구분 필터 없음(ALL), 값이 있으면 해당 APPLY_GN 만
        //   cancelFlag = 1 이면 취소 포함, 0 이면 STATE='C' 제외
        String applyGn = mode.applyGnValue();
        int cancelFlag = includeCancel ? 1 : 0;
        String f = from.format(YYYYMMDD), t = to.format(YYYYMMDD);

        Map<String, Object> mat = dsreJdbcTemplate.queryForMap(
                OUT_MATERIAL_SQL, f, t, applyGn, applyGn, cancelFlag);
        Map<String, Object> lab = dsreJdbcTemplate.queryForMap(
                OUT_LABOR_SQL, f, t, applyGn, applyGn, cancelFlag);

        return PeriodLogisCost.outbound(mode, from, to,
                num(mat.get("paper_amt")), num(mat.get("omr_amt")),
                num(mat.get("etc_amt")), num(mat.get("label_amt")),
                (int) num(lab.get("inwon_total")), num(lab.get("labor")),
                (int) num(lab.get("req_cnt")));
    }

    // ── 기간 회수 물류비 집계 (물류비계산2.vb InData) ─────────────────────────────
    // 회수분 원천: 사고=tbl_wol_dtl, 반품=tbl_wol_dtl_b. 회수 단가는 tbl_logis_cost DTL_CD=0.
    // 기간은 REG_DATE(yyyy-MM-dd)를 yyyyMMdd로 변환해 문자열 비교(레거시 동일).
    private static final String RETURN_TEMPLATE = """
            SELECT
              COALESCE(SUM(CASE WHEN c.NAME NOT IN ('OMR','단행본','책자','라벨') THEN t.CNT*cost.PAPER ELSE 0 END),0) paper_amt,
              COALESCE(SUM(CASE WHEN c.NAME='OMR' THEN t.CNT*cost.OMR ELSE 0 END),0) omr_amt,
              COALESCE(SUM(CASE WHEN c.NAME IN ('단행본','책자') THEN t.CNT*cost.ETC ELSE 0 END),0) etc_amt,
              COALESCE(SUM(CASE WHEN c.NAME='라벨' THEN t.CNT*cost.LABEL ELSE 0 END),0) label_amt
            FROM ( %s ) t
              JOIN tbl_materials_info m ON t.MAT_CD=m.MAT_CD
              JOIN tbl_comon_info c ON m.MAT_GN=c.CMD_CD
              JOIN (SELECT PAPER,OMR,ETC,LABEL FROM tbl_logis_cost WHERE DTL_CD=0 ORDER BY idx DESC LIMIT 1) cost
            WHERE t.SDATE BETWEEN ? AND ?
            """;

    private static final String WOL_ACCIDENT = "SELECT MAT_CD, CNT, REPLACE(REG_DATE,'-','') SDATE FROM tbl_wol_dtl";
    private static final String WOL_NORMAL = "SELECT MAT_CD, CNT, REPLACE(REG_DATE,'-','') SDATE FROM tbl_wol_dtl_b";

    /** 회수 명세 — 위 총계와 <b>같은 단가 규칙</b>이다. 다르면 행 합이 총계와 안 맞는다. */
    private static final String RETURN_DETAIL_TEMPLATE = """
            SELECT t.SDATE, t.MAT_CD, m.MAT_NM, c.NAME mat_gn, t.MODE_GN, t.CNT,
                   CASE WHEN c.NAME = 'OMR' THEN cost.OMR
                        WHEN c.NAME IN ('단행본','책자') THEN cost.ETC
                        WHEN c.NAME = '라벨' THEN cost.LABEL
                        ELSE cost.PAPER END unit_cost
              FROM ( %s ) t
              JOIN tbl_materials_info m ON t.MAT_CD = m.MAT_CD
              JOIN tbl_comon_info c ON m.MAT_GN = c.CMD_CD
              JOIN (SELECT PAPER,OMR,ETC,LABEL FROM tbl_logis_cost
                     WHERE DTL_CD=0 ORDER BY idx DESC LIMIT 1) cost
             WHERE t.SDATE BETWEEN ? AND ?
             ORDER BY t.SDATE, m.MAT_NM
            """;

    // 명세는 사고/반품을 구분해 보여줘야 해서 구분값을 실어 UNION 한다(총계는 안 쓰던 컬럼).
    private static final String WOL_ACCIDENT_D =
            "SELECT MAT_CD, CNT, REPLACE(REG_DATE,'-','') SDATE, 'ACCIDENT' MODE_GN FROM tbl_wol_dtl";
    private static final String WOL_NORMAL_D =
            "SELECT MAT_CD, CNT, REPLACE(REG_DATE,'-','') SDATE, 'NORMAL' MODE_GN FROM tbl_wol_dtl_b";

    // ── 물류단가 관리(DSRE2 tbl_logis_cost write-back) ─────────────────────────────
    // 상품명·시행명을 함께 뽑는다(정본 10.물류비용등록 목록 컬럼).
    // LEFT JOIN인 이유: dtl_cd=0(회수단가 특수행)은 시행이 없어 INNER면 행이 사라진다.
    private static final String RATE_LIST_SQL = """
            SELECT lc.DTL_CD, lc.PAPER, lc.OMR, lc.ETC, lc.LABEL, lc.BASIC, lc.TRADE,
                   lc.PACKTYPE, lc.bSpare, lc.INPUTDATE,
                   pi.PROD_NM, pd.DTL_NM
              FROM tbl_logis_cost lc
              LEFT JOIN tbl_product_dtl  pd ON pd.DTL_CD  = lc.DTL_CD
              LEFT JOIN tbl_product_info pi ON pi.PROD_CD = pd.PROD_CD
             ORDER BY lc.DTL_CD
            """;

    @Override
    public List<LogisCostRate> listLogisCosts() {
        return dsreJdbcTemplate.query(RATE_LIST_SQL,
                (rs, i) -> new LogisCostRate(
                        rs.getInt("DTL_CD"),
                        rs.getString("PROD_NM"), rs.getString("DTL_NM"),
                        rs.getInt("PAPER"), rs.getInt("OMR"), rs.getInt("ETC"),
                        rs.getInt("LABEL"), rs.getInt("BASIC"), rs.getInt("TRADE"),
                        rs.getInt("PACKTYPE"), rs.getString("bSpare"),
                        rs.getTimestamp("INPUTDATE") == null
                                ? null : rs.getTimestamp("INPUTDATE").toLocalDateTime()));
    }

    @Override
    public java.util.Optional<LogisCostRate> findLogisCost(int dtlCd) {
        // 목록을 재사용한다 — 조인·매핑이 한 곳에만 있어야 두 경로의 결과가 갈리지 않는다.
        // 단가 행은 시행 수만큼이라 전량을 훑어도 부담이 없다.
        return listLogisCosts().stream().filter(r -> r.dtlCd() == dtlCd).findFirst();
    }

    @Override
    public void upsertLogisCost(int dtlCd, int paper, int omr, int etc, int label,
                                int basic, int trade, int packtype, String bSpare) {
        // PK가 (idx, dtl_cd)라 dtl_cd 단일 ON DUPLICATE 불가 → 존재검사 후 UPDATE/INSERT(레거시 동일).
        Integer cnt = dsreJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tbl_logis_cost WHERE dtl_cd=?", Integer.class, dtlCd);
        if (cnt != null && cnt > 0) {
            dsreJdbcTemplate.update("""
                    UPDATE tbl_logis_cost SET PAPER=?,OMR=?,ETC=?,LABEL=?,BASIC=?,TRADE=?,PACKTYPE=?,bSpare=?,INPUTDATE=now()
                    WHERE dtl_cd=?
                    """, paper, omr, etc, label, basic, trade, packtype, bSpare, dtlCd);
        } else {
            dsreJdbcTemplate.update("""
                    INSERT INTO tbl_logis_cost (dtl_cd,paper,omr,etc,label,basic,trade,packtype,bSpare,inputDate)
                    VALUES (?,?,?,?,?,?,?,?,?,now())
                    """, dtlCd, paper, omr, etc, label, basic, trade, packtype, bSpare);
        }
    }

    @Override
    public int deleteLogisCost(int dtlCd) {
        return dsreJdbcTemplate.update("DELETE FROM tbl_logis_cost WHERE dtl_cd=?", dtlCd);
    }

    @Override
    public void updateReturnRate(int paper, int omr, int etc) {
        int updated = dsreJdbcTemplate.update(
                "UPDATE tbl_logis_cost SET PAPER=?,OMR=?,ETC=?,INPUTDATE=now() WHERE dtl_cd=0", paper, omr, etc);
        if (updated == 0) {
            // 회수단가 행(dtl_cd=0) 부재 시 생성(LABEL/BASIC/TRADE=0, packtype=1).
            dsreJdbcTemplate.update("""
                    INSERT INTO tbl_logis_cost (dtl_cd,paper,omr,etc,label,basic,trade,packtype,bSpare,inputDate)
                    VALUES (0,?,?,?,0,0,0,1,'Y',now())
                    """, paper, omr, etc);
        }
    }

    @Override
    public PeriodLogisCost calcReturnPeriod(LocalDate from, LocalDate to, LogisMode mode) {
        String source = switch (mode) {
            case ACCIDENT -> WOL_ACCIDENT;
            case NORMAL -> WOL_NORMAL;
            case ALL -> WOL_ACCIDENT + " UNION ALL " + WOL_NORMAL;
        };
        Map<String, Object> r = dsreJdbcTemplate.queryForMap(
                String.format(RETURN_TEMPLATE, source), from.format(YYYYMMDD), to.format(YYYYMMDD));
        return PeriodLogisCost.ret(mode, from, to,
                num(r.get("paper_amt")), num(r.get("omr_amt")),
                num(r.get("etc_amt")), num(r.get("label_amt")));
    }

    @Override
    public List<ReturnCostDetailRow> returnDetail(LocalDate from, LocalDate to, LogisMode mode) {
        String source = switch (mode) {
            case ACCIDENT -> WOL_ACCIDENT_D;
            case NORMAL -> WOL_NORMAL_D;
            case ALL -> WOL_ACCIDENT_D + " UNION ALL " + WOL_NORMAL_D;
        };
        // ‼️%s 자리에 들어가는 것은 **우리가 쓴 상수 셋 중 하나**다(사용자 입력이 아니다).
        //   총계 쿼리(calcReturnPeriod)가 쓰는 방식과 같다 — 기간은 바인딩으로 넘긴다.
        return dsreJdbcTemplate.query(String.format(RETURN_DETAIL_TEMPLATE, source), (rs, i) -> {
            int qty = rs.getInt("CNT");
            int unit = rs.getInt("unit_cost");
            return new ReturnCostDetailRow(
                    LocalDate.parse(rs.getString("SDATE"), YYYYMMDD),
                    rs.getInt("MAT_CD"), rs.getString("MAT_NM"), rs.getString("mat_gn"),
                    rs.getString("MODE_GN"), qty, unit, (long) qty * unit);
        }, from.format(YYYYMMDD), to.format(YYYYMMDD));
    }

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private static final String BOOKLIST_SQL = """
            SELECT BLC.REQ_CD reqcd,
              (SELECT machul_cd FROM tbl_cust_info CUI WHERE CUI.CUST_CD=BLF.cust_cd) cust_cd,
              (SELECT cust_fnm FROM tbl_cust_info CUI WHERE CUI.CUST_CD=BLF.cust_cd) cust_nm,
              BLC.LST_CD lst_cd, BLC.DTL_CD dtl_cd, max(BLD.DTL_NM) book_nm, max(BLC.PRICE) price,
              IFNULL(max(CASE WHEN BLC.req_gn='M' THEN BLC.dissusu END),0) sale_rate, IFNULL(sum(CASE WHEN BLC.req_gn='M' THEN BLC.reqcnt END),0) sale_qty,
              IFNULL(max(CASE WHEN BLC.req_gn='J' THEN BLC.dissusu END),0) gift_rate, IFNULL(sum(CASE WHEN BLC.req_gn='J' THEN BLC.reqcnt END),0) gift_qty,
              IFNULL(max(CASE WHEN BLC.req_gn='B' THEN BLC.dissusu END),0) free_rate, IFNULL(sum(CASE WHEN BLC.req_gn='B' THEN BLC.reqcnt END),0) free_qty,
              max(BLF.memo) memo
            FROM tbf_booklist_cnt BLC
              LEFT JOIN tbf_booklist_ref BLF ON BLF.req_cd=BLC.req_cd
              LEFT JOIN tbl_booklist_dtl BLD ON BLC.lst_cd=BLD.lst_cd AND BLC.dtl_cd=BLD.dtl_cd
            WHERE BLC.state='A' AND BLF.reqdt BETWEEN ? AND ?
            GROUP BY BLC.REQ_CD, BLF.cust_cd, BLC.lst_cd, BLC.dtl_cd
            HAVING sum(BLC.reqcnt) > 0
            ORDER BY BLC.REQ_CD, BLC.lst_cd, BLC.dtl_cd
            """;

    @Override
    public List<BooklistImportRow> readPendingBooklist(LocalDate from, LocalDate to) {
        return dsreJdbcTemplate.query(BOOKLIST_SQL,
                (rs, i) -> new BooklistImportRow(
                        rs.getInt("reqcd"), rs.getString("cust_cd"), rs.getString("cust_nm"),
                        rs.getString("lst_cd"), rs.getString("dtl_cd"), rs.getString("book_nm"),
                        rs.getInt("price"),
                        rs.getInt("sale_rate"), rs.getInt("sale_qty"),
                        rs.getInt("gift_rate"), rs.getInt("gift_qty"),
                        rs.getInt("free_rate"), rs.getInt("free_qty"),
                        rs.getString("memo")),
                from.format(YYYYMMDD), to.format(YYYYMMDD));
    }

    @Override
    public void markBooklistDone(int reqCd, String lstCd, String dtlCd) {
        // 해당 (신청×분류×도서)의 M/J/B 전 행을 처리완료(state='T')로. 중복방지.
        dsreJdbcTemplate.update(
                "UPDATE tbf_booklist_cnt SET state='T' WHERE req_cd=? AND lst_cd=? AND dtl_cd=?",
                reqCd, lstCd, dtlCd);
    }

    // ── 매출일괄등록(14p, 더프) ───────────────────────────────────────────────────
    // 근거: 레거시 매출가져오기.vb:415. 지사신청분(apply_gn='S')만.
    //
    // ★레거시와 다르게 한 곳: 단가·청구인원·총금액을 SQL에서 계산하지 않는다.
    //   레거시는 화면 콤보값(처리구분)을 SQL 문자열에 끼워 넣어 CASE를 만든다
    //   ({IIf(ComboBox_처리구분.Text = "처리", True, False)}). 조건마다 다른 쿼리가 나가고,
    //   미리보기와 실제 등록이 서로 다른 문자열을 쓰게 된다.
    //   여기서는 인원 4종을 다 실어 보내고 판정은 Java 한 곳에서 한다.
    //
    // ★단 정가 조인만은 SQL에 남는다 — tbl_product_amt가 처리/비처리(proc_gn)별로
    //   단가를 따로 갖고 있어 조인 시점에 어느 쪽을 볼지 정해야 한다.
    //   문자열을 붙이는 대신 바인딩 파라미터 하나를 CASE로 받는다.
    //
    // 그룹 축은 레거시 그대로다(거래처·학교·학년·분류·과목·처리구분·청구구분·단가).
    // 레거시 주석 원문: "신청과목수로 하면 인문/자연 때문에 6/5로 나뉘는 경우가 생겨 단가로 그룹".
    private static final String DUFF_SQL = """
            SELECT
              MAX(처리순번) 처리순번, MAX(신청일자) 신청일자, MAX(매출코드) 매출코드,
              거래처코드, MAX(도시명) 도시명, MAX(거래처명) 거래처명, MAX(거래처풀네임) 거래처풀네임,
              학교코드, MAX(학교명) 학교명, 학년,
              분류코드, MAX(분류명) 분류명, 과목코드, MAX(과목명) 과목명,
              처리구분, 청구구분, MAX(신청과목수) 신청과목수,
              SUM(신청인원) 신청인원, SUM(처리인원) 처리인원,
              SUM(비처리인원) 비처리인원, SUM(등록인원) 등록인원,
              MAX(정가) 정가, MAX(공급률) 공급률, MAX(할인액) 할인액
            FROM (
              SELECT
                mInfo.req_cd 처리순번, mInfo.req_date 신청일자,
                cInfo.machul_cd 매출코드, mInfo.cust_cd 거래처코드,
                cInfo.city_nm 도시명, cInfo.cust_nm 거래처명, cInfo.cust_fnm 거래처풀네임,
                mInfo.mgr_cd 학교코드, schInfo.sch_nm 학교명, pDtl.grade 학년,
                pInfo.prod_cd 분류코드, pInfo.prod_nm 분류명,
                mInfo.dtl_cd 과목코드, pDtl.dtl_nm 과목명,
                sCnt.cnt 신청과목수,
                Func_reqinwon_get(mInfo.req_cd) 신청인원,
                IF(rRtnSum.pSum IS NULL, IFNULL(rRtn.pSum,0), rRtnSum.pSum) 처리인원,
                Func_reqinwon_get(mInfo.req_cd) + IFNULL(rCancel.cancelCnt,0)
                  - IF(rRtnSum.pSum IS NULL, IFNULL(rRtn.pSum,0), rRtnSum.pSum) 비처리인원,
                IFNULL(uCnt.cnt,0) 등록인원,
                amt.amt 정가, mInfo.proc_yn2 처리구분,
                sRef.charge_gn 청구구분, sRef.amtsusu 공급률, sRef.dissusu 할인액
              FROM (
                SELECT req_cd, dtl_cd, cust_cd, mgr_cd, req_date, proc_yn2
                FROM tbl_request_info
                WHERE req_date BETWEEN ? AND ?
                  AND apply_gn='S'
                  AND (? = 0 OR state='D')
              ) mInfo
              LEFT JOIN (
                SELECT req_cd, MAX(cnt) cnt FROM (
                  SELECT req_cd, seq, COUNT(res_cd) cnt FROM tbl_request_cnt
                  WHERE cnt > 0 GROUP BY req_cd, seq
                ) T GROUP BY req_cd
              ) sCnt ON mInfo.req_cd = sCnt.req_cd
              LEFT JOIN tbl_mgrcd_cnt uCnt
                ON mInfo.dtl_cd = uCnt.dtl_cd AND mInfo.mgr_cd = uCnt.mgr_cd
              LEFT JOIN tbl_product_amt amt
                ON mInfo.dtl_cd = amt.dtl_cd
               AND amt.proc_gn = CASE ?
                     WHEN 'Y' THEN 'Y'
                     WHEN 'N' THEN 'N'
                     ELSE IF(mInfo.proc_yn2='N','N','Y')
                   END
               AND IF(sCnt.cnt IS NULL, 5, sCnt.cnt) >= amt.substcnt
               AND IF(sCnt.cnt IS NULL, 5, sCnt.cnt) <= amt.subedcnt
              LEFT JOIN tbl_product_dtl  pDtl  ON mInfo.dtl_cd  = pDtl.dtl_cd
              LEFT JOIN tbl_product_info pInfo ON pDtl.prod_cd  = pInfo.prod_cd
              LEFT JOIN tbl_school_ref   sRef  ON mInfo.mgr_cd  = sRef.mgr_cd
                                              AND pInfo.prod_cd = sRef.prod_cd
              LEFT JOIN (SELECT req_cd, SUM(IFNULL(proCnt,0)) pSum
                           FROM tbl_request_rtn GROUP BY req_cd) rRtn
                ON mInfo.req_cd = rRtn.req_cd
              LEFT JOIN (SELECT req_cd, SUM(IFNULL(proc_y_Cnt,0)) pSum
                           FROM tbl_request_rtnSum GROUP BY req_cd) rRtnSum
                ON mInfo.req_cd = rRtnSum.req_cd
              LEFT JOIN (SELECT req_cd, SUM(IFNULL(cancelCnt,0)) cancelCnt
                           FROM tbl_request_cancel GROUP BY req_cd) rCancel
                ON mInfo.req_cd = rCancel.req_cd
              LEFT JOIN (
                SELECT mgr_cd, sch_nm FROM tbl_school_info
                UNION
                SELECT mgr_cd, hak_nm FROM tbl_hakwon_info
              ) schInfo ON mInfo.mgr_cd = schInfo.mgr_cd
              LEFT JOIN tbl_cust_info cInfo ON mInfo.cust_cd = cInfo.cust_cd
            ) t
            GROUP BY 거래처코드, 학교코드, 학년, 분류코드, 과목코드, 처리구분, 청구구분,
                     IF(할인액 > 0, 정가 - 할인액, 정가 * 공급률 / 100)
            ORDER BY 처리순번, 거래처코드, 학교코드, 과목코드
            """;

    @Override
    public List<DuffSalesRow> readDuffSales(LocalDate from, LocalDate to, boolean onlyComplete,
                                            DuffChargeMode mode) {
        return dsreJdbcTemplate.query(DUFF_SQL,
                (rs, i) -> new DuffSalesRow(
                        rs.getInt("처리순번"),
                        rs.getString("신청일자") == null ? null
                                : LocalDate.parse(rs.getString("신청일자"), YYYYMMDD),
                        rs.getString("매출코드"), rs.getString("거래처코드"),
                        rs.getString("거래처명"), rs.getString("거래처풀네임"), rs.getString("도시명"),
                        rs.getString("학교코드"), rs.getString("학교명"), rs.getString("학년"),
                        rs.getString("분류코드"), rs.getString("분류명"),
                        rs.getString("과목코드"), rs.getString("과목명"),
                        rs.getInt("신청과목수"), rs.getString("처리구분"), rs.getString("청구구분"),
                        rs.getInt("신청인원"), rs.getInt("처리인원"),
                        rs.getInt("비처리인원"), rs.getInt("등록인원"),
                        rs.getInt("정가"), rs.getInt("공급률"), rs.getInt("할인액"),
                        null),
                from.format(YYYYMMDD), to.format(YYYYMMDD),
                onlyComplete ? 1 : 0,
                // 단가구분 — 이건 Java로 뺄 수 없다. tbl_product_amt가 처리/비처리별로
                // 단가를 따로 갖고 있어 조인 시점에 정해져야 한다. 값은 바인딩으로 넘긴다.
                mode.rateCode());
    }

    /**
     * 학교관리 가져오기 원본. tbl_cust_ref(지사↔학교/학원)를 기준으로 거래처·학교 정보를 붙인다.
     * 학교(MGR_GN='S')는 tbl_school_info, 학원('A')은 tbl_hakwon_info에서 이름을 가져온다.
     * 도시명은 tbl_city_info PK가 (GROUP_CD, CITY_CD)라 CITY_CD 단독 조인 시 행이 불어난다
     * → 상관 서브쿼리 LIMIT 1로 고정(행 증식 방지).
     *
     * <p><b>거래처·학교 마스터는 LEFT JOIN이다(INNER 아님).</b> 실데이터 검증에서 tbl_cust_ref 76건 중
     * 32건이 tbl_cust_info에 없는 고아 매핑이었다. INNER로 조이면 매핑의 42%가 소리 없이 사라진다
     * → 매핑은 살리고 이름만 비운 뒤, 불완전 건수를 동기화 결과에 노출한다.
     */
    @Override
    public List<SchoolRefRow> readSchoolRefs() {
        return dsreJdbcTemplate.query("""
                SELECT r.CUST_CD                     cust_cd,
                       r.MGR_CD                      mgr_cd,
                       r.MGR_GN                      mgr_gn,
                       c.CUST_FNM                    cust_nm,
                       c.CITY_NM                     region,
                       (SELECT ci.CITY_NM FROM tbl_city_info ci
                         WHERE ci.CITY_CD = c.CITY_CD LIMIT 1) city,
                       COALESCE(s.SCH_NM, h.HAK_NM)  sch_nm,
                       c.MACHUL_CD                   machul_cd,
                       CONCAT(IFNULL(c.CITY_NM,''), ' ', IFNULL(c.CUST_NM,'')) partner_label
                  FROM tbl_cust_ref r
                  LEFT JOIN tbl_cust_info c   ON c.CUST_CD = r.CUST_CD
                  LEFT JOIN tbl_school_info s ON r.MGR_GN = 'S' AND s.MGR_CD = r.MGR_CD
                  LEFT JOIN tbl_hakwon_info h ON r.MGR_GN = 'A' AND h.MGR_CD = r.MGR_CD
                 ORDER BY r.CUST_CD, r.MGR_CD
                """,
                (rs, i) -> new SchoolRefRow(
                        trim(rs.getString("cust_cd")),
                        trim(rs.getString("mgr_cd")),
                        !"A".equalsIgnoreCase(trim(rs.getString("mgr_gn"))),
                        rs.getString("cust_nm"),
                        rs.getString("city"),
                        rs.getString("region"),
                        rs.getString("sch_nm"),
                        trim(rs.getString("machul_cd")),
                        trim(rs.getString("partner_label"))));
    }

    // ── 주문·진행상태 조회 (읽기 전용) — 근거: FM_DSRE_RegStateChng.cs 조회 SQL ──────────
    // 상태 전이는 DSRE2 데스크톱 소관이라 여기엔 조회만 둔다(발주처 확정 2026-08-11).
    // 레거시 화면은 'AND STATE NOT IN (W,D,C) AND APPLY_GN=S'로 대상을 좁히지만,
    // 그건 '상태를 바꿀 수 있는 건'만 뽑는 그 화면의 사정이라 일반 조회에는 옮기지 않는다.
    // 필터는 전부 바인딩 값으로 넘긴다(게이트규칙: SQL 조각 조립 금지).
    private static final String ORDER_SQL = """
            SELECT req.REQ_CD                                   req_cd,
                   req.REQ_DATE                                 req_date,
                   req.STATE                                    state,
                   req.CUST_CD                                  cust_cd,
                   cust.CUST_NM                                 cust_nm,
                   cust.CUST_FNM                                cust_fnm,
                   city.CITY_NM                                 city_nm,
                   req.MGR_CD                                   mgr_cd,
                   COALESCE(sch.SCH_NM, hak.HAK_NM)             mgr_nm,
                   prod.PROD_NM                                 prod_nm,
                   dtl.DTL_NM                                   dtl_nm,
                   -- ★코드도 함께 준다(프론트 실측 2026-10-12 C-2). 조건(prodCodes·dtlCds·
                   --   productYear)만 열어 두고 응답엔 이름만 있어서, 화면이 그 조건을 만들 수 없었다.
                   --   ‼️지난 주문의 시행명을 코드로 되돌릴 방법도 없다 — `/orders/exams` 는
                   --     등록용이라 판매 끝난 시행이 빠진다.
                   dtl.PROD_CD                                  prod_cd,
                   req.DTL_CD                                   dtl_cd,
                   -- ‼️`YEAR` 타입은 JDBC 가 **java.sql.Date** 로 돌려준다 —
                   --   숫자로 읽는 매퍼가 전부 null 을 내서 상품연도가 전건 공란이었다
                   --   (프론트 실측 2026-10-12: 500건 모두 빈 값). 숫자로 캐스팅해 받는다.
                   CAST(prod.YEAR AS SIGNED)                    prod_year,
                   dtl.GRADE                                    grade,
                   FUNC_REQINWON_GET(req.REQ_CD)                inwon,
                   cls.CLS_CNT                                  cls_cnt,
                   req.PROC_YN                                  proc_yn,
                   req.TEACHER                                  teacher,
                   req.TEL                                      tel,
                   -- ★담당자 이메일·발송구분(2026-10-08 프론트 요청).
                   --   이메일은 주문 조회 화면이 그대로 쓰고,
                   --   LGS_GN 은 작업요청서 발송구분(화물 H / 택배 T) 자동 입력에 쓴다.
                   req.EMAIL                                    email,
                   req.LGS_GN                                   lgs_gn,
                   req.ADDRESS                                  address,
                   req.BIGO                                     bigo,
                   -- 과목신청 수량 + 간편신청 인원. 한 주문은 둘 중 하나로만 채워진다.
                   COALESCE(cnt.TOTAL_QTY, 0) + COALESCE(cls.EASY_QTY, 0)  total_qty,
                   COALESCE(cnt.ITEM_CNT, 0)                    item_cnt,
                   -- ★예상금액 = 단가 × 인원. 단가는 (시행 × 과목수 구간 × 처리여부)로 정해진다.
                   --   더프가 인원 종량제라 과목을 몇 개 신청했느냐로 단가가 달라진다
                   --   (예: 시행19 → 1과목 7,000 / 2과목 12,000 / 3과목~ 15,000, 처리 Y 기준).
                   --   ‼️실측 커버리지 61%(2019~2026 고르게). 단가가 없는 시행은 NULL로 둔다 —
                   --     0으로 채우면 0원짜리 주문으로 읽힌다.
                   (SELECT amt.AMT * FUNC_REQINWON_GET(req.REQ_CD)
                      FROM tbl_product_amt amt
                     WHERE amt.DTL_CD = req.DTL_CD
                       AND amt.PROC_GN = req.PROC_YN
                       AND COALESCE(cnt.ITEM_CNT, 0) BETWEEN amt.SUBSTCNT AND amt.SUBEDCNT
                     LIMIT 1)                                   est_amt
              FROM tbl_request_info req
              LEFT JOIN tbl_product_dtl  dtl  ON dtl.DTL_CD  = req.DTL_CD
              LEFT JOIN tbl_product_info prod ON prod.PROD_CD = dtl.PROD_CD
              LEFT JOIN tbl_cust_info    cust ON cust.CUST_CD = req.CUST_CD
              LEFT JOIN tbl_city_info    city ON city.CITY_CD = cust.CITY_CD
              LEFT JOIN tbl_school_info  sch  ON sch.MGR_CD  = req.MGR_CD
              LEFT JOIN tbl_hakwon_info  hak  ON hak.MGR_CD  = req.MGR_CD
              -- 반 수 + **간편신청 인원**. 간편신청은 과목별 수량(tbl_request_cnt)이 없고
              -- 반별 인문·자연·통합 인원만 있다 — 그게 곧 수량이다.
              -- ‼️이걸 안 더하면 간편신청 주문이 목록에서 총수량 0으로 보인다(2026-09-18 지적).
              LEFT JOIN (SELECT REQ_CD, COUNT(CLS_NM) CLS_CNT,
                                SUM(COALESCE(GEYUL1,0) + COALESCE(GEYUL2,0) + COALESCE(GEYULT,0)) EASY_QTY
                           FROM tbl_request_dtl GROUP BY REQ_CD) cls ON cls.REQ_CD = req.REQ_CD
              -- ★총수량·품목건수. 화면이 "신청 자료에 총수량·품목건수가 없다"며 주문조회 연결을
              --   미뤄 두고 있었다(2026-09-16). tbl_request_cnt 에 과목별 신청갯수가 있다.
              --   ‼️금액은 이 테이블에도, DSRE2 어디에도 없다 — 신청이지 매출이 아니다.
              LEFT JOIN (SELECT REQ_CD, SUM(CNT) TOTAL_QTY, COUNT(DISTINCT RES_CD) ITEM_CNT
                           FROM tbl_request_cnt GROUP BY REQ_CD) cnt ON cnt.REQ_CD = req.REQ_CD
            """;

    // 기간·필터 조회 / 단건 조회. 앞부분(ORDER_SQL)을 공유해 컬럼 구성이 갈리지 않게 한다.
    private static final String ORDER_LIST_SQL = ORDER_SQL + """
             WHERE req.REQ_DATE BETWEEN ? AND ?
               AND (? IS NULL OR req.STATE = ?)
               AND (? IS NULL OR req.CUST_CD = ?)
               -- ★거래처 다중 선택(좌측 트리 T-1). 단건 필터와 **함께** 걸리면 교집합이다.
               --   IN 절 조립 대신 쉼표 문자열 하나를 바인딩한다(게이트규칙).
               AND (? IS NULL OR FIND_IN_SET(req.CUST_CD, ?) > 0)
               AND (? IS NULL OR req.APPLY_GN = ?)
               -- ★도서(상품)·시행·상품연도 조건(2026-10-08). 없던 동안 화면이 전체를 받아 걸렀다.
               AND (? IS NULL OR FIND_IN_SET(dtl.PROD_CD, ?) > 0)
               AND (? IS NULL OR FIND_IN_SET(req.DTL_CD, ?) > 0)
               AND (? IS NULL OR prod.YEAR = ?)
             ORDER BY req.REQ_DATE DESC, req.REQ_CD DESC
            """;

    private static final String ORDER_BY_ID_SQL = ORDER_SQL + " WHERE req.REQ_CD = ?";

    private static final org.springframework.jdbc.core.RowMapper<DsreOrderRow> ORDER_MAPPER = (rs, i) -> {
        String code = trim(rs.getString("state"));
        return new DsreOrderRow(
                rs.getInt("req_cd"),
                trim(rs.getString("req_date")),
                code,
                OrderState.labelOf(code),
                trim(rs.getString("cust_cd")),
                rs.getString("cust_nm"),
                rs.getString("cust_fnm"),
                rs.getString("city_nm"),
                trim(rs.getString("mgr_cd")),
                rs.getString("mgr_nm"),
                rs.getString("prod_nm"),
                rs.getString("dtl_nm"),
                trim(rs.getString("prod_cd")),
                intOrNull(rs.getObject("dtl_cd")),
                intOrNull(rs.getObject("prod_year")),
                trim(rs.getString("grade")),
                intOrNull(rs.getObject("inwon")),
                intOrNull(rs.getObject("cls_cnt")),
                procLabel(trim(rs.getString("proc_yn"))),
                rs.getString("teacher"),
                rs.getString("tel"),
                rs.getString("email"),
                trim(rs.getString("lgs_gn")),
                rs.getString("address"),
                rs.getString("bigo"),
                rs.getLong("total_qty"),
                rs.getInt("item_cnt"),
                (Long) rs.getObject("est_amt"),   // 단가 없는 시행은 null 그대로
                null);                            // 매출번호는 우리 DB라 서비스가 채운다
    };

    private static final String ORDER_PAGE_SQL = ORDER_LIST_SQL + " LIMIT ? OFFSET ?";

    /**
     * 같은 WHERE 로 건수만. 저장함수·파생테이블을 안 타 목록보다 훨씬 싸다.
     *
     * <p>‼️<b>목록과 조건이 하나라도 어긋나면 안 된다.</b> 총건수만 틀리면 화면은
     * 있지도 않은 페이지를 만들고, 담당자는 마지막 장이 비어 있는 것을 보고 자료가 빠졌다고 읽는다.
     * 도서·시행·연도 조건을 목록에 더하면서 여기에도 같은 조인·같은 조건을 넣었다(2026-10-08).
     */
    private static final String ORDER_COUNT_SQL = """
            SELECT COUNT(*)
              FROM tbl_request_info req
              LEFT JOIN tbl_product_dtl  dtl  ON dtl.DTL_CD  = req.DTL_CD
              LEFT JOIN tbl_product_info prod ON prod.PROD_CD = dtl.PROD_CD
             WHERE req.REQ_DATE BETWEEN ? AND ?
               AND (? IS NULL OR req.STATE = ?)
               AND (? IS NULL OR req.CUST_CD = ?)
               AND (? IS NULL OR FIND_IN_SET(req.CUST_CD, ?) > 0)
               AND (? IS NULL OR req.APPLY_GN = ?)
               AND (? IS NULL OR FIND_IN_SET(dtl.PROD_CD, ?) > 0)
               AND (? IS NULL OR FIND_IN_SET(req.DTL_CD, ?) > 0)
               AND (? IS NULL OR prod.YEAR = ?)
            """;

    @Override
    public List<DsreOrderRow> findOrders(LocalDate from, LocalDate to, OrderState state,
                                         String custCode, java.util.Collection<String> custCodes,
                                         LogisMode mode, int offset, int limit) {
        return findOrders(from, to, state, custCode, custCodes, mode, null, null, null,
                offset, limit);
    }

    @Override
    public List<DsreOrderRow> findOrders(LocalDate from, LocalDate to, OrderState state,
                                         String custCode, java.util.Collection<String> custCodes,
                                         LogisMode mode, java.util.Collection<String> prodCodes,
                                         java.util.Collection<Integer> dtlCds, Integer productYear,
                                         int offset, int limit) {
        Object[] base = orderArgs(from, to, state, custCode, custCodes, mode,
                prodCodes, dtlCds, productYear);
        Object[] args = java.util.Arrays.copyOf(base, base.length + 2);
        args[base.length] = Math.max(1, limit);
        args[base.length + 1] = Math.max(0, offset);
        return dsreJdbcTemplate.query(ORDER_PAGE_SQL, ORDER_MAPPER, args);
    }

    @Override
    public int countOrders(LocalDate from, LocalDate to, OrderState state,
                           String custCode, java.util.Collection<String> custCodes, LogisMode mode) {
        return countOrders(from, to, state, custCode, custCodes, mode, null, null, null);
    }

    @Override
    public int countOrders(LocalDate from, LocalDate to, OrderState state,
                           String custCode, java.util.Collection<String> custCodes, LogisMode mode,
                           java.util.Collection<String> prodCodes,
                           java.util.Collection<Integer> dtlCds, Integer productYear) {
        Integer n = dsreJdbcTemplate.queryForObject(ORDER_COUNT_SQL, Integer.class,
                orderArgs(from, to, state, custCode, custCodes, mode, prodCodes, dtlCds, productYear));
        return (n == null) ? 0 : n;
    }

    /**
     * 목록·건수가 <b>같은 조건·같은 순서</b>로 바인딩되게 한 곳에서 만든다.
     * 두 곳에 따로 적어 두면 조건을 더할 때 한쪽을 빠뜨려 총건수만 틀어진다
     * (화면은 있지도 않은 페이지를 만들고, 담당자는 자료가 빠졌다고 읽는다).
     */
    private Object[] orderArgs(LocalDate from, LocalDate to, OrderState state,
                               String custCode, java.util.Collection<String> custCodes,
                               LogisMode mode, java.util.Collection<String> prodCodes,
                               java.util.Collection<Integer> dtlCds, Integer productYear) {
        String stateCode = (state == null) ? null : state.code();
        String cust = (custCode == null || custCode.isBlank()) ? null : custCode.trim();
        String custList = csv(custCodes);
        String applyGn = (mode == null) ? null : mode.applyGnValue();
        String prodList = csv(prodCodes);
        String dtlList = csvNum(dtlCds);
        return new Object[]{
                from.format(YYYYMMDD), to.format(YYYYMMDD),
                stateCode, stateCode, cust, cust, custList, custList, applyGn, applyGn,
                prodList, prodList, dtlList, dtlList, productYear, productYear};
    }

    /** 숫자 목록을 FIND_IN_SET 용 CSV 로. 비면 null(조건 미적용). */
    private static String csvNum(java.util.Collection<Integer> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Integer v : values) {
            if (v == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public java.util.Optional<DsreOrderRow> findOrder(int reqCd) {
        // 기간 조회와 같은 SQL을 쓰되 REQ_CD로만 좁힌다(컬럼 구성이 갈리지 않게).
        List<DsreOrderRow> rows = dsreJdbcTemplate.query(ORDER_BY_ID_SQL, ORDER_MAPPER, reqCd);
        return rows.stream().findFirst();
    }

    // 발송준비중 전환. STATE='S'(상품준비중)일 때만 바꾼다 — 재출력해도 D를 되돌리지 않는다.
    private static final String READY_TO_SHIP_SQL =
            "UPDATE tbl_request_info SET STATE='W' WHERE REQ_CD=? AND STATE='S'";

    @Override
    public int markReadyToShip(int reqCd) {
        return dsreJdbcTemplate.update(READY_TO_SHIP_SQL, reqCd);
    }

    // 진행상태 전환. 현재 상태가 일치할 때만 — 조회와 UPDATE 사이에 DSRE2 데스크톱이 먼저 옮길 수 있다.
    // ★SQL은 완성된 상수 하나다(조각 결합 금지 — 정적분석 게이트규칙). 상태값도 바인딩으로 넘긴다.
    private static final String CHANGE_STATE_SQL =
            "UPDATE tbl_request_info SET STATE=? WHERE REQ_CD=? AND STATE=?";

    @Override
    public int changeState(int reqCd, String fromCode, String toCode) {
        return dsreJdbcTemplate.update(CHANGE_STATE_SQL, toCode, reqCd, fromCode);
    }

    // ── 발송완료 / 발송취소 (DSRE2 FM_LOGI_DeliveryMng 와 같은 처리) ─────────────────────

    // 행 잠금 — 조회와 변경 사이에 DSRE2 데스크톱이 같은 신청을 옮기지 못하게 한다.
    private static final String SHIP_LOCK_SQL =
            "SELECT STATE, APPLY_GN FROM tbl_request_info WHERE REQ_CD=? FOR UPDATE";

    private static final String SHIP_DEL_DT_SQL =
            "SELECT DEL_DT FROM tbl_logis_info WHERE REQ_CD=?";

    // 자재 현재고 차감. DSRE2 Use_Resource 와 같은 원천(TBL_LOGIS_CNT RES_GN='R' × 자재 연결된 리소스).
    private static final String SHIP_DEDUCT_SQL = """
            UPDATE tbl_materials_info m
              JOIN (SELECT r.MAT_CD, SUM(lc.REQCNT) AS CNT
                      FROM tbl_logis_cnt lc
                      JOIN tbl_resource_info r ON lc.RES_CD = r.RES_CD
                     WHERE lc.REQ_CD = ? AND lc.RES_GN = 'R' AND r.MAT_CD IS NOT NULL
                     GROUP BY r.MAT_CD) b ON m.MAT_CD = b.MAT_CD
               SET m.CNT = m.CNT - b.CNT
            """;

    // 자재 출고내역. DSRE2 는 리소스(RES_CD)별로 한 줄씩 넣는다 — 같은 단위로 맞춘다.
    private static final String SHIP_DTL_INSERT_SQL = """
            INSERT INTO tbl_materials_dtl (REQ_CD, MAT_CD, INOUT_GN, ACCD_YN, DELI_YN, CNT, SDATE, REG_DATE, REG_USER)
            SELECT lc.REQ_CD, r.MAT_CD, 'O', ?, 'N', SUM(lc.REQCNT), ?, NOW(), ?
              FROM tbl_logis_cnt lc
              JOIN tbl_resource_info r ON lc.RES_CD = r.RES_CD
             WHERE lc.REQ_CD = ? AND lc.RES_GN = 'R' AND r.MAT_CD IS NOT NULL
             GROUP BY lc.REQ_CD, lc.RES_CD, r.MAT_CD
            """;

    private static final String SHIP_SET_DEL_DT_SQL =
            "UPDATE tbl_logis_info SET DEL_DT=? WHERE REQ_CD=?";

    // 이미 발송완료인 건은 발송일만 고친다(DSRE2 CHK='Y' 분기와 같다).
    private static final String SHIP_REDATE_LOGIS_SQL =
            "UPDATE tbl_logis_info SET DEL_DT=?, REG_DATE=NOW(), REG_USER=? WHERE REQ_CD=?";
    // ‼️<b>ACCD_YN 은 보지 않는다.</b> 날짜를 고치는 데 사고처리 여부를 가릴 이유가 없고,
    //   그 조건을 두면 **상자계산 화면(FM_LOGI_BoxCalc)으로 발송된 건이 빠진다** —
    //   그쪽은 사고처리 신청이어도 ACCD_YN 을 기본값 'N' 으로 넣기 때문에,
    //   우리가 'Y' 로 찾으면 한 줄도 안 걸린다(발송일만 바뀌고 출고내역 날짜는 옛날 그대로 남는다).
    //   되돌리는 쪽(UNSHIP_*)도 ACCD_YN 을 안 보므로 조건을 맞춰 둔다.
    private static final String SHIP_REDATE_DTL_SQL = """
            UPDATE tbl_materials_dtl SET SDATE=?, REG_DATE=NOW(), REG_USER=?
             WHERE REQ_CD=? AND INOUT_GN='O' AND DELI_YN='N'
            """;

    // 발송취소: 출고내역에 적힌 만큼 되돌린다.
    // ★DSRE2 는 이때 REQ_CD 의 출고내역을 조건 없이 전부 지우지만, 접수 때 생기는 발송의뢰분(DELI_YN='Y')까지
    //   지워져 재고 근거가 사라진다. 우리는 발송처리가 넣은 줄(INOUT_GN='O', DELI_YN='N')만 되돌린다.
    private static final String UNSHIP_RESTORE_SQL = """
            UPDATE tbl_materials_info m
              JOIN (SELECT MAT_CD, SUM(CNT) AS CNT
                      FROM tbl_materials_dtl
                     WHERE REQ_CD = ? AND INOUT_GN = 'O' AND DELI_YN = 'N'
                     GROUP BY MAT_CD) b ON m.MAT_CD = b.MAT_CD
               SET m.CNT = m.CNT + b.CNT
            """;
    private static final String UNSHIP_DTL_DELETE_SQL =
            "DELETE FROM tbl_materials_dtl WHERE REQ_CD=? AND INOUT_GN='O' AND DELI_YN='N'";
    private static final String UNSHIP_CLEAR_DEL_DT_SQL =
            "UPDATE tbl_logis_info SET DEL_DT=NULL WHERE REQ_CD=?";

    // 신청번호 목록 → 진행상태. ‼️SQL 을 조립하지 않는다 — 목록은 CSV 한 개를 바인딩해
    //   FIND_IN_SET 으로 푼다(이 파일의 custCodes 필터와 같은 방식).
    private static final String STATES_OF_SQL =
            "SELECT REQ_CD, STATE FROM tbl_request_info WHERE FIND_IN_SET(REQ_CD, ?) > 0";

    @Override
    public java.util.Map<Integer, String> statesOf(java.util.Collection<Integer> reqCds) {
        String csv = csvNum(reqCds);
        if (csv == null) {
            return java.util.Map.of();
        }
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        dsreJdbcTemplate.query(STATES_OF_SQL, rs -> {
            out.put(rs.getInt("REQ_CD"), trim(rs.getString("STATE")));
        }, csv);
        return out;
    }

    private static final DateTimeFormatter SDATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;   // char(8) yyyyMMdd

    @Override
    public List<DsreShipResult> ship(List<Integer> reqCds, LocalDate delDate, String actor) {
        String user = regUser(actor);
        String sdate = delDate.format(SDATE_FMT);
        List<DsreShipResult> out = dsreTransactionTemplate.execute(status -> {
            List<DsreShipResult> rs = new ArrayList<>();
            for (Integer reqCd : new java.util.LinkedHashSet<>(reqCds)) {
                List<Map<String, Object>> row = dsreJdbcTemplate.queryForList(SHIP_LOCK_SQL, reqCd);
                if (row.isEmpty()) {
                    rs.add(new DsreShipResult(reqCd, null, DsreShipResult.Outcome.NOT_FOUND, null));
                    continue;
                }
                String state = trim((String) row.get(0).get("STATE"));
                // 사고처리분(APPLY_GN='A')이면 출고내역 ACCD_YN='Y' — DSRE2 라디오 버튼 기준과 같다.
                String accdYn = "A".equals(trim((String) row.get(0).get("APPLY_GN"))) ? "Y" : "N";
                LocalDate prev = delDtOf(reqCd);
                if (OrderState.SHIPPED.code().equals(state)) {
                    dsreJdbcTemplate.update(SHIP_REDATE_LOGIS_SQL, delDate, user, reqCd);
                    // ‼️accdYn 은 넘기지 않는다 — 상자계산 화면으로 발송된 건은 ACCD_YN 이
                    //   기본값 'N' 이라, 'Y' 로 찾으면 한 줄도 안 걸린다(위 SQL 주석 참고).
                    dsreJdbcTemplate.update(SHIP_REDATE_DTL_SQL, sdate, user, reqCd);
                    rs.add(new DsreShipResult(reqCd, state, DsreShipResult.Outcome.DATE_ONLY, prev));
                    continue;
                }
                if (!OrderState.READY_TO_SHIP.code().equals(state)) {
                    rs.add(new DsreShipResult(reqCd, state, DsreShipResult.Outcome.SKIPPED, prev));
                    continue;
                }
                dsreJdbcTemplate.update(SHIP_DEDUCT_SQL, reqCd);
                dsreJdbcTemplate.update(SHIP_DTL_INSERT_SQL, accdYn, sdate, user, reqCd);
                dsreJdbcTemplate.update(CHANGE_STATE_SQL, OrderState.SHIPPED.code(), reqCd, state);
                dsreJdbcTemplate.update(SHIP_SET_DEL_DT_SQL, delDate, reqCd);
                rs.add(new DsreShipResult(reqCd, state, DsreShipResult.Outcome.CHANGED, prev));
            }
            return rs;
        });
        return (out == null) ? List.of() : out;
    }

    @Override
    public List<DsreShipResult> unship(List<Integer> reqCds, String actor) {
        List<DsreShipResult> out = dsreTransactionTemplate.execute(status -> {
            List<DsreShipResult> rs = new ArrayList<>();
            for (Integer reqCd : new java.util.LinkedHashSet<>(reqCds)) {
                List<Map<String, Object>> row = dsreJdbcTemplate.queryForList(SHIP_LOCK_SQL, reqCd);
                if (row.isEmpty()) {
                    rs.add(new DsreShipResult(reqCd, null, DsreShipResult.Outcome.NOT_FOUND, null));
                    continue;
                }
                String state = trim((String) row.get(0).get("STATE"));
                LocalDate prev = delDtOf(reqCd);
                if (!OrderState.SHIPPED.code().equals(state)) {
                    rs.add(new DsreShipResult(reqCd, state, DsreShipResult.Outcome.SKIPPED, prev));
                    continue;
                }
                dsreJdbcTemplate.update(UNSHIP_RESTORE_SQL, reqCd);
                dsreJdbcTemplate.update(UNSHIP_DTL_DELETE_SQL, reqCd);
                dsreJdbcTemplate.update(CHANGE_STATE_SQL, OrderState.READY_TO_SHIP.code(), reqCd, state);
                dsreJdbcTemplate.update(UNSHIP_CLEAR_DEL_DT_SQL, reqCd);
                rs.add(new DsreShipResult(reqCd, state, DsreShipResult.Outcome.CHANGED, prev));
            }
            return rs;
        });
        return (out == null) ? List.of() : out;
    }

    private LocalDate delDtOf(int reqCd) {
        List<java.sql.Date> d = dsreJdbcTemplate.queryForList(SHIP_DEL_DT_SQL, java.sql.Date.class, reqCd);
        return (d.isEmpty() || d.get(0) == null) ? null : d.get(0).toLocalDate();
    }

    /** DSRE {@code REG_USER} 는 varchar(20). 넘치면 INSERT 가 실패해 발송 전체가 막힌다. */
    private static String regUser(String actor) {
        String a = (actor == null || actor.isBlank()) ? "sales" : actor;
        return a.length() > 20 ? a.substring(0, 20) : a;
    }

    /**
     * 집계·저장함수 결과를 Integer로. MySQL이 COUNT()·FUNC_REQINWON_GET()을 BIGINT로 돌려줘
     * Integer 직접 캐스팅은 ClassCastException이 난다(실제로 발생).
     */
    private static Integer intOrNull(Object v) {
        return (v instanceof Number n) ? n.intValue() : null;
    }

    /** 성적처리 여부 표기(레거시 조회 SQL의 CASE와 동일). */
    private static String procLabel(String procYn) {
        if ("Y".equalsIgnoreCase(procYn)) {
            return "처리";
        }
        return "N".equalsIgnoreCase(procYn) ? "비처리" : "";
    }

    /** DSRE2 코드 컬럼이 char(5) 고정폭이라 뒤 공백이 붙는다 — 매칭키로 쓰기 전 제거. */
    private static String trim(String s) {
        return (s == null) ? null : s.trim();
    }

    // ── 신규 주문 등록(레거시 특약점 사이트 Application_SQL.xml:266~299 와 같은 3단) ──────────

    /**
     * 신청 가능 시행. 판매중 판정은 레거시와 같다 — {@code SALE_YN='Y'} + 판매종료일이 오늘 이후.
     * ‼️SQL은 완성된 상수 둘 중 하나를 고른다(조각을 붙이지 않는다 — 게이트규칙 파라미터 바인딩).
     */
    private static final String SQL_EXAMS = """
            SELECT d.DTL_CD, d.DTL_NM, d.PROD_CD, p.PROD_NM, d.GRADE, d.PROC_YN,
                   d.SALE_DT, d.EASY_GN
              FROM tbl_product_dtl d
              JOIN tbl_product_info p ON p.PROD_CD = d.PROD_CD
             WHERE d.USE_YN = 'Y' AND d.SALE_YN = 'Y'
               AND d.SALE_DT >= DATE_FORMAT(NOW(), '%Y%m%d')
             ORDER BY d.SALE_DT DESC, d.DTL_CD DESC
             LIMIT 500
            """;

    private static final String SQL_EXAMS_KEYWORD = """
            SELECT d.DTL_CD, d.DTL_NM, d.PROD_CD, p.PROD_NM, d.GRADE, d.PROC_YN,
                   d.SALE_DT, d.EASY_GN
              FROM tbl_product_dtl d
              JOIN tbl_product_info p ON p.PROD_CD = d.PROD_CD
             WHERE d.USE_YN = 'Y' AND d.SALE_YN = 'Y'
               AND d.SALE_DT >= DATE_FORMAT(NOW(), '%Y%m%d')
               AND (d.DTL_NM LIKE ? OR p.PROD_NM LIKE ?)
             ORDER BY d.SALE_DT DESC, d.DTL_CD DESC
             LIMIT 500
            """;

    @Override
    public List<ExamRow> listExams(String keyword) {
        RowMapper<ExamRow> mapper = (rs, i) -> new ExamRow(
                rs.getInt("DTL_CD"), rs.getString("DTL_NM"),
                rs.getString("PROD_CD"), rs.getString("PROD_NM"),
                rs.getString("GRADE"), rs.getString("PROC_YN"),
                rs.getString("SALE_DT"), rs.getString("EASY_GN"));
        if (keyword == null || keyword.isBlank()) {
            return dsreJdbcTemplate.query(SQL_EXAMS, mapper);
        }
        String like = "%" + keyword.trim() + "%";
        return dsreJdbcTemplate.query(SQL_EXAMS_KEYWORD, mapper, like, like);
    }

    private static final String SQL_SUBJECTS = """
            SELECT RES_CD, RES_NM, GYOSI, GEYUL, SORTKEY
              FROM tbl_resource_info
             WHERE DTL_CD = ? AND DISP_GN = 'Y'
             ORDER BY SORTKEY
            """;

    @Override
    public List<SubjectRow> listSubjects(int dtlCd) {
        return dsreJdbcTemplate.query(SQL_SUBJECTS, (rs, i) -> new SubjectRow(
                rs.getInt("RES_CD"), rs.getString("RES_NM"),
                rs.getString("GYOSI"), rs.getString("GEYUL"), rs.getInt("SORTKEY")), dtlCd);
    }

    /** ‼️STATE를 넣지 않는다 — DB 기본값 'A'(접수완료)에 맡긴다. 레거시 신청도 같다. */
    private static final String SQL_INSERT_ORDER = """
            INSERT INTO tbl_request_info
                (DTL_CD, CUST_CD, MGR_CD, REQ_DATE, PROC_YN, PROC_YN2, PROC_DT,
                 TEACHER, TEL, EMAIL, ZIP_CD, ADDRESS, BIGO, LGS_GN, REG_DATE, REG_USER)
            VALUES (?, ?, ?, DATE_FORMAT(NOW(), '%Y%m%d'),
                    -- ★미지정이면 **시행 기본값**을 쓴다. 예전엔 무조건 'N'으로 박아,
                    --   성적처리(Y) 시행에 비처리로 저장됐다(2026-09-18 지적).
                    --   스펙 문구가 "미지정 시 시행 기본값"이었으므로 문서가 아니라 코드를 맞춘다.
                    COALESCE(?, (SELECT d.PROC_YN FROM tbl_product_dtl d WHERE d.DTL_CD = ?), 'N'),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), ?)
            """;

    private static final String SQL_INSERT_CLASS = """
            INSERT INTO tbl_request_dtl
                (REQ_CD, SEQ, CLS_NM, REQ_GN, GEYUL1, GEYUL2, GEYULT, REG_DATE, REG_USER)
            VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?)
            """;

    private static final String SQL_LAST_ID = "SELECT LAST_INSERT_ID()";

    private static final String SQL_INSERT_QTY = """
            INSERT INTO tbl_request_cnt (REQ_CD, SEQ, RES_CD, CNT) VALUES (?, ?, ?, ?)
            """;

    @Override
    public int createOrder(NewOrder o, String actor) {
        // ★한 트랜잭션. 중간에 실패하면 반·수량이 빠진 반쪽 주문이 남는다 —
        //   물류는 그걸 보고 무엇을 보낼지 알 수 없다.
        Integer reqCd = dsreTransactionTemplate.execute(status -> {
            dsreJdbcTemplate.update(SQL_INSERT_ORDER,
                    o.dtlCd(), o.custCode(), o.schoolCode(),
                    // procYn 자리는 COALESCE(?, 시행기본값, 'N') 라 파라미터가 둘이다.
                    o.procYn(), o.dtlCd(),
                    o.procYn2(), o.procDate(),
                    o.teacher(), o.tel(), o.email(),
                    o.zipCode(), o.address(), o.memo(), o.deliveryGubun(), actor);

            // ★채번은 레거시와 같은 방식(Application_SQL.xml 의 selectKey = LAST_INSERT_ID()).
            //   ‼️LAST_INSERT_ID()는 **커넥션 단위**라 동시에 다른 주문이 들어와도 섞이지 않는다.
            //     같은 커넥션을 쓰는 것은 위 TransactionTemplate 이 보장한다.
            //   (PreparedStatementCreator + GeneratedKeyHolder 도 되지만, 그 관용구는
            //    정적분석이 Statement 미정리로 잡는다 — 스프링이 닫아 주는데도 그렇다.)
            Integer newKey = dsreJdbcTemplate.queryForObject(SQL_LAST_ID, Integer.class);
            if (newKey == null || newKey <= 0) {
                throw new IllegalStateException("REQ_CD 채번에 실패했습니다.");
            }
            int newReqCd = newKey;

            int seq = 0;
            for (NewOrder.ClassLine c : o.classes()) {
                dsreJdbcTemplate.update(SQL_INSERT_CLASS, newReqCd, seq, c.className(), c.applyType(),
                        c.humanities(), c.science(), c.combined(), actor);
                if (c.subjects() != null) {
                    for (NewOrder.SubjectQty q : c.subjects()) {
                        dsreJdbcTemplate.update(SQL_INSERT_QTY, newReqCd, seq, q.resCd(), q.qty());
                    }
                }
                seq++;
            }
            return newReqCd;
        });
        return (reqCd == null) ? 0 : reqCd;
    }


    // ── 주문 등록 보조 조회(지사·지사별 학교·상세) ─────────────────────────────

    private static final String SQL_BRANCHES = """
            SELECT CUST_CD, CUST_NM, CUST_FNM, CITY_NM, MACHUL_CD
              FROM tbl_cust_info
             ORDER BY CITY_NM, CUST_NM
            """;

    private static final String SQL_BRANCHES_KEYWORD = """
            SELECT CUST_CD, CUST_NM, CUST_FNM, CITY_NM, MACHUL_CD
              FROM tbl_cust_info
             WHERE CUST_NM LIKE ? OR CUST_FNM LIKE ? OR MACHUL_CD LIKE ? OR CUST_CD LIKE ?
             ORDER BY CITY_NM, CUST_NM
            """;

    @Override
    public List<BranchRow> listBranches(String keyword) {
        RowMapper<BranchRow> mapper = (rs, i) -> new BranchRow(
                trim(rs.getString("CUST_CD")), rs.getString("CUST_NM"),
                rs.getString("CUST_FNM"), rs.getString("CITY_NM"),
                trim(rs.getString("MACHUL_CD")));
        if (keyword == null || keyword.isBlank()) {
            return dsreJdbcTemplate.query(SQL_BRANCHES, mapper);
        }
        String like = "%" + keyword.trim() + "%";
        return dsreJdbcTemplate.query(SQL_BRANCHES_KEYWORD, mapper, like, like, like, like);
    }

    /** ‼️학교·학원이 별도 테이블이라 MGR_GN 으로 갈라 조인한다(레거시 readSchoolRefs 와 같은 방식). */
    private static final String SQL_BRANCH_SCHOOLS = """
            SELECT r.MGR_CD, r.MGR_GN, COALESCE(s.SCH_NM, h.HAK_NM) NM
              FROM tbl_cust_ref r
              LEFT JOIN tbl_school_info s ON r.MGR_GN = 'S' AND s.MGR_CD = r.MGR_CD
              LEFT JOIN tbl_hakwon_info h ON r.MGR_GN = 'A' AND h.MGR_CD = r.MGR_CD
             WHERE r.CUST_CD = ?
             ORDER BY NM
            """;

    @Override
    public List<BranchSchoolRow> listBranchSchools(String custCode) {
        return dsreJdbcTemplate.query(SQL_BRANCH_SCHOOLS, (rs, i) -> new BranchSchoolRow(
                trim(rs.getString("MGR_CD")), rs.getString("NM"),
                !"A".equalsIgnoreCase(trim(rs.getString("MGR_GN")))), custCode);
    }

    private static final String SQL_ORDER_DETAIL = """
            SELECT d.SEQ, d.CLS_NM, d.REQ_GN, d.GEYUL1, d.GEYUL2, d.GEYULT,
                   c.RES_CD, r.RES_NM, c.CNT
              FROM tbl_request_dtl d
              LEFT JOIN tbl_request_cnt  c ON c.REQ_CD = d.REQ_CD AND c.SEQ = d.SEQ
              LEFT JOIN tbl_resource_info r ON r.RES_CD = c.RES_CD
             WHERE d.REQ_CD = ?
             ORDER BY d.SEQ, r.SORTKEY
            """;

    @Override
    public List<OrderDetailRow> findOrderDetail(int reqCd) {
        // 반 하나에 과목이 여럿이라 조인 결과가 반별로 여러 줄이다 — SEQ로 다시 묶는다.
        Map<Integer, List<OrderDetailRow.SubjectQtyRow>> subjects = new LinkedHashMap<>();
        Map<Integer, Object[]> heads = new LinkedHashMap<>();
        dsreJdbcTemplate.query(SQL_ORDER_DETAIL, rs -> {
            int seq = rs.getInt("SEQ");
            heads.putIfAbsent(seq, new Object[]{
                    rs.getString("CLS_NM"), trim(rs.getString("REQ_GN")),
                    (Integer) rs.getObject("GEYUL1"), (Integer) rs.getObject("GEYUL2"),
                    (Integer) rs.getObject("GEYULT")});
            Integer resCd = (Integer) rs.getObject("RES_CD");
            if (resCd != null) {
                subjects.computeIfAbsent(seq, k -> new ArrayList<>())
                        .add(new OrderDetailRow.SubjectQtyRow(resCd, rs.getString("RES_NM"),
                                rs.getInt("CNT")));
            }
        }, reqCd);

        List<OrderDetailRow> out = new ArrayList<>();
        heads.forEach((seq, h) -> {
            List<OrderDetailRow.SubjectQtyRow> subs = subjects.getOrDefault(seq, List.of());
            // ★목록의 총수량과 **같은 방식**으로 센다 — 두 화면 숫자가 갈리면 아무도 안 믿는다.
            long qty = subs.isEmpty()
                    ? nz((Integer) h[2]) + nz((Integer) h[3]) + nz((Integer) h[4])
                    : subs.stream().mapToLong(OrderDetailRow.SubjectQtyRow::qty).sum();
            out.add(new OrderDetailRow(seq, (String) h[0], (String) h[1],
                    (Integer) h[2], (Integer) h[3], (Integer) h[4], qty, subs));
        });
        return out;
    }

    /**
     * 다중 선택 값을 <b>바인딩 한 개</b>로 만든다(쉼표 이음). 비면 null —
     * SQL 에서 {@code ? IS NULL} 로 조건 자체를 끈다.
     *
     * <p>‼️IN 절을 문자열로 조립하지 않기 위한 방식이다(게이트규칙 "파라미터 바인딩 전수 적용").
     * 값에 쉼표가 들어가면 쪼개지므로, 쉼표가 있을 수 없는 코드 값에만 쓴다.
     */
    private static String csv(java.util.Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().filter(java.util.Objects::nonNull)
                .map(Object::toString).map(String::trim).filter(v -> !v.isEmpty())
                .reduce((a, b) -> a + "," + b).orElse(null);
    }

    private static long nz(Integer v) {
        return (v == null) ? 0 : v;
    }

}
