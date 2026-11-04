-- 노출 판단 순서 재작업(11/04 클라이언트 회신 §1 — 10/31 §1-1 대체).
--
-- 새 순서(위에서부터 처음 걸리는 규칙):
--   ① products.web_visible = N              → 비노출 (단락, 아래 전부 무시)
--   ② products.restricted_visible = Y       → 열기 행이 있으면 노출, 없으면 비노출
--                                             (★거래처×대분류 매핑을 아예 보지 않는다)
--   ③ 열기 행이 있음                         → 노출
--   ④ partner_supply_rate(거래처, 대분류).web_visible = Y AND use_yn = Y → 노출 / 그 외 비노출
--
-- ★무엇이 달라졌나 — '닫기'가 없어진다
--   10/31 판은 예외를 열기/닫기 두 상태로 뒀다. "지정 거래처에만 노출"을 그 구조로 하려면
--   나머지 모든 거래처에 닫기 행을 깔아야 하고, 거래처가 새로 생기면 기본 노출이라
--   의도와 반대로 열려 버린다. 11/04 회신이 그 자리를 도서 단위 스위치
--   (restricted_visible = 화이트리스트 모드)로 대체했다 → 닫기 행이 필요 없다.
--   그래서 이제 **행 존재 = 열기**이고, '닫기'는 행을 지우는 것이다.
--
-- 기존 닫기 행은 지운다. 실데이터는 0건이고(V113이 11/03에 들어갔고 설정 화면이
-- 아직 운영에 안 열렸다) 테스트 픽스처뿐이다. 남겨 두면 ②③과 충돌해 판정이 갈린다.
ALTER TABLE products
    ADD COLUMN restricted_visible BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '지정 거래처 전용(화이트리스트). Y면 열기 행이 있는 거래처에만 노출하고 대분류 매핑을 무시한다';

DELETE FROM partner_product_visibility WHERE visible = FALSE;

ALTER TABLE partner_product_visibility
    MODIFY COLUMN visible BOOLEAN NOT NULL
        COMMENT '열기 표시 — 행이 있으면 열기다. 항상 true(닫기는 행 삭제, 11/04 회신 §1)';

-- 설정 화면이 [거래처관리] 거래처 축 → [도서관리] 도서 축으로 옮겨 간다(11/04 §2).
-- V113 인덱스는 partner_id 하나뿐이라 도서 기준 조회가 풀스캔이 된다.
CREATE INDEX ix_ppv_product ON partner_product_visibility (product_id);
