-- 반품 입고를 **물류 계정에 연다**(발주처 확인요청 2026-11-24 · PDF 1쪽).
--
-- 현상: 입고/대체 등록(물류) > 반품 등록에서 **관리자 계정은 반품 도서가 보이고
--       물류 담당 계정은 「반품할 수 있는 도서가 없습니다」** 가 떴다.
--
-- 원인: 그 화면이 부르는 경로가 `/sales/returnable`·`/sales/return-inbound` 인데,
--       권한표의 `/sales/**`(SALES, 60)가 **LOGISTICS = NONE** 이다(V52).
--       → 물류 계정은 **403**. 화면은 조회 실패를 「목록이 비었다」로 보여 주고 있어
--         권한 문제가 「도서가 없다」로 나타났다.
--
-- ‼️`/sales/**` 를 물류에 열지 않는다. 그러면 통합 매출 조회·매출 등록·매출 취소까지
--   함께 열린다. **반품 입고에 필요한 두 경로만** 따로 둔다 —
--   29p 입고/대체등록(물류)은 이미 LOGISTICS = WRITE 다(`/stock/**`).
--
-- ★sort_order 는 `/sales/**`(60)보다 **앞**이어야 한다(먼저 걸리는 패턴이 이긴다).
--   `/sales/bulk/**`(50)과 겹치지 않게 55·56 으로 둔다.
--
-- ★패턴을 **정확한 경로와 `/**` 두 줄로** 둔다 — AntPathMatcher 에서 `/a/**` 가 `/a` 를
--   받아 주는지에 의존하지 않는다(V133 과 같은 규율. 추측으로 두면 403 이 조용히 남는다).
INSERT INTO menu_screen (code, name, menu_group, path_pattern, sort_order, created_at) VALUES
  ('RETURNABLE',        '반품가능내역',  'SALES', '/sales/returnable',        55, NOW()),
  ('RETURNABLE_SUB',    '반품가능내역',  'SALES', '/sales/returnable/**',     55, NOW()),
  ('RETURN_INBOUND',    '반품입고등록',  'SALES', '/sales/return-inbound',    56, NOW()),
  ('RETURN_INBOUND_SUB','반품입고등록',  'SALES', '/sales/return-inbound/**', 56, NOW());

-- 권한 ------------------------------------------------------------------------
-- ‼️**네 역할 모두 줄을 넣어야 한다.** 구체 패턴이 `/sales/**` 를 이기므로,
--   줄이 없는 역할은 종전에 되던 것도 **거부**된다(ADMIN·SALES 가 그렇게 막힌다).
--   종전 `/sales/**` 수준을 그대로 옮긴다 — ADMIN WRITE · SALES WRITE · FINANCE READ.
--   거기에 **LOGISTICS 를 WRITE 로** 더한다(반품 입고는 물류 담당 업무다).
--
-- ‼️역할은 4종뿐이다(ADMIN/FINANCE/LOGISTICS/SALES). 없는 역할명을 한 줄이라도 넣으면
--   권한표를 읽을 때 enum 변환이 터져 **모든 사용자의 모든 요청이 500** 이 된다.
INSERT INTO role_screen_permission (role, screen_id, permission, updated_at)
SELECT r.role, ms.id, 'WRITE', NOW()
  FROM menu_screen ms
  CROSS JOIN (SELECT 'ADMIN' AS role UNION ALL SELECT 'SALES'
              UNION ALL SELECT 'LOGISTICS') r
 WHERE ms.code IN ('RETURNABLE', 'RETURNABLE_SUB', 'RETURN_INBOUND', 'RETURN_INBOUND_SUB');

-- 재무는 조회만 — 종전 `/sales/**` 가 READ 였다(V52: "재무×매출관리 = ◐(조회만)").
INSERT INTO role_screen_permission (role, screen_id, permission, updated_at)
SELECT 'FINANCE', ms.id, 'READ', NOW()
  FROM menu_screen ms
 WHERE ms.code IN ('RETURNABLE', 'RETURNABLE_SUB', 'RETURN_INBOUND', 'RETURN_INBOUND_SUB');
