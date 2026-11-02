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
 * @param apiKey        사이트 서버가 {@code X-Dealer-Site-Key} 헤더로 보내는 공유 키
 * @param auditName     이 경로로 들어온 변경의 감사 주체명(created_by 에 박힌다)
 * @param warehouseCode 특약점 주문이 나갈 물류창고 코드. 특약점은 창고를 고르지 않으므로
 *                      서버가 정한다. 비우면 "실물재고 물류창고가 하나일 때 그것"으로 되고,
 *                      둘 이상이면 거부한다 — 임의로 고르면 재고가 엉뚱한 창고에서 빠진다
 */
@ConfigurationProperties(prefix = "daesung.dealer-site")
public record DealerSiteProperties(String apiKey, String auditName, String warehouseCode) {

    public DealerSiteProperties {
        auditName = (auditName == null || auditName.isBlank()) ? "dealer-site" : auditName;
        warehouseCode = (warehouseCode == null || warehouseCode.isBlank()) ? null : warehouseCode;
    }

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
