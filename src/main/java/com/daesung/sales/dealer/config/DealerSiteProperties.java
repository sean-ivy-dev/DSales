package com.daesung.sales.dealer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 특약점 사이트(order.dsdo.co.kr) <b>서버 간 호출</b> 설정.
 *
 * <p>근거: 10/26 명세 §10-3 — "데이터 접근 방식: 사이트가 매출 데이터에 접근하는 방식을
 * 정한다. <b>권장안은 사이트 서버가 매출 API를 서버 간 호출하는 것</b>이다.
 * 매출 DB 직접 접속은 지양한다."
 *
 * <p>★<b>왜 역할(Role)을 안 쓰고 전용 키인가.</b> 우리 역할은 4종 고정이고(ADMIN·FINANCE·
 * LOGISTICS·SALES) 권한 매트릭스는 DB 표(V52)다. 권한표에 없는 역할명을 넣으면 enum 변환이
 * 터져 <b>전 사용자가 500</b>이 된다(V80 에서 실제로 그랬다). 사이트 서버는 사람이 아니라
 * 기계이고 화면 권한이 아니라 <b>경로 하나</b>만 필요하므로, 역할 축을 늘리지 않고 키로 가른다.
 *
 * <p>‼️<b>기본값을 두지 않는다.</b> 비어 있으면 그 경로는 전부 거부된다 — 기본 키를 심어
 * 두면 그게 곧 공개된 비밀이 된다. 운영은 환경변수로 넣는다.
 *
 * @param apiKey             사이트 서버가 {@code X-Dealer-Site-Key} 헤더로 보내는 공유 키
 * @param auditName          이 경로로 들어온 변경의 감사 주체명(created_by 에 박힌다)
 * @param warehouseCode      특약점 주문이 나갈 물류창고 코드. 특약점은 창고를 고르지 않으므로
 *                           서버가 정한다. 비우면 "실물재고 물류창고가 하나일 때 그것"으로 되고,
 *                           둘 이상이면 거부한다 — 임의로 고르면 재고가 엉뚱한 창고에서 빠진다
 * @param stockHistoryMonths 재고현황 목록 범위 확장(11/04 §4)에서 "최근 몇 개월 매출 출고까지
 *                           포함할지". 발주처가 아직 이 기간을 확정하지 않아 상수로 박지 않고
 *                           설정값으로 둔다. 10/26 §7 PDF 보관함이 쓰는 "최근 12개월"과 맞춘다.
 * @param stockHistorySource {@code SALES}면 매출 출고 이력을 범위에 포함하고, {@code NONE}이면
 *                           노출 교재만 보여준다(11/04 §4 이전 동작). DSRE 출고는 포함하지
 *                           않는다 — DSRE 연동이 꺼진 환경에서 목록이 달라지면 안 된다.
 */
@ConfigurationProperties(prefix = "daesung.dealer-site")
public record DealerSiteProperties(String apiKey, String auditName, String warehouseCode,
                                   Integer stockHistoryMonths, String stockHistorySource) {

    public DealerSiteProperties {
        auditName = (auditName == null || auditName.isBlank()) ? "dealer-site" : auditName;
        warehouseCode = (warehouseCode == null || warehouseCode.isBlank()) ? null : warehouseCode;
        stockHistoryMonths = (stockHistoryMonths == null) ? Integer.valueOf(12) : stockHistoryMonths;
        stockHistorySource = (stockHistorySource == null || stockHistorySource.isBlank())
                ? "SALES" : stockHistorySource;
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 매출 출고 이력을 재고현황 범위에 포함할지.
     *
     * <p>대소문자를 가리지 않으려고 {@code equalsIgnoreCase} 를 쓰면 FindSecBugs가
     * {@code IMPROPER_UNICODE} 로 잡는다(케이스 매핑이 글자 수·내용을 바꾸는 경우가 있어서).
     * 이 값은 운영이 환경변수로 직접 넣는 설정값(SALES/NONE)이라 <b>정확일치</b>로 본다 —
     * 자유 입력을 받는 칸이 아니다.
     */
    public boolean stockHistoryFromSales() {
        return "SALES".equals(stockHistorySource);
    }
}
