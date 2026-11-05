-- 재고현황 매입·반품 자동집계 전환일(11/05 회신 §4) — DB 설정값으로 전환.
--
-- ★왜 환경변수가 아니라 DB인가 — 환경변수(daesung.dealer-site.stock-cutover-date)로
--   두면 값을 바꿀 때마다 GitHub Actions 변수 등록 + 재배포가 필요하다(월마감
--   period_locks·권한 매트릭스 menu_screen/role_screen_permission 과 달리 운영 중
--   바로 못 바꾼다). 발주처가 전환일을 정하는 그 순간 바로 적용되려면 관리자
--   API로 즉시 바꿀 수 있는 DB 값이어야 한다.
--
-- 싱글톤 행(id=1)만 쓴다 — 전사 통합 운영(법인 축 없음, CLAUDE.md §10)이라 설정도 하나다.
CREATE TABLE dealer_stock_cutover_setting (
    id BIGINT NOT NULL,
    cutover_date DATE NULL COMMENT '비어 있으면 자동집계 꺼짐(현행 수기입력 그대로)',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(100) NULL,
    updated_at DATETIME NULL,
    updated_by VARCHAR(100) NULL,
    PRIMARY KEY (id)
);

INSERT INTO dealer_stock_cutover_setting (id, cutover_date, created_at)
VALUES (1, NULL, NOW());
