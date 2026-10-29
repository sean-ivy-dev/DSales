package com.daesung.sales.closing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 계산서(홈택스) 공급자(자사) 정보. daesung.supplier.* 설정에서 주입.
 * ★실값(사업자번호/주소 등)은 배포 시 환경변수/시크릿으로만 주입 — 소스 커밋 금지(§9).
 * TODO: 2법인(대성학력개발연구소/리브커넥스) 분리 주입.
 *
 * <p>★<b>비어 있으면 기동할 때 경고한다.</b> 안 그러면 계산서 응답의 공급자 칸이
 * 조용히 빈 채로 나간다 — 스펙에는 필드가 있어서 정상으로 보이고,
 * 실제로 배포 서버에서 5건 전부 빈 값인 것을 프론트가 실호출로 잡았다(갭리포트 §P-5).
 * 홈택스 양식은 공급자 정보가 없으면 제출 자체가 안 되므로 늦게 발견될수록 손해가 크다.
 */
@ConfigurationProperties(prefix = "daesung.supplier")
public record SupplierProperties(
        String bizNo,
        String name,
        String bossName,
        String addr,
        String bizStatus,
        String bizItem,
        String tel,

        /**
         * 입금계좌 안내 문구. 외상매출장 인쇄물·PDF 하단 오른쪽에 그대로 찍힌다
         * (10/27 피드백 §7-A "하단 오른쪽: 입금계좌 안내 문구").
         *
         * <p>근거: 특약점 배포용 실물 PDF({@code SALES_10003_강남 하람도서_*.pdf}) 하단 —
         * 왼쪽 "(주)대성학력개발연구소", 오른쪽 "입금계좌 : 신한은행 140-006-456354".
         *
         * <p>⚠️<b>계좌번호는 PM 확인 대상</b>이다 — 위 PDF 캡처에서 읽은 값이라
         * 현재 쓰는 계좌와 다를 수 있다. 다르면 이 설정만 고치면 된다.
         */
        String bankAccount
) {

    /** 계산서를 발행하려면 최소한 이 둘은 있어야 한다. */
    public boolean isConfigured() {
        return notBlank(bizNo) && notBlank(name);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
