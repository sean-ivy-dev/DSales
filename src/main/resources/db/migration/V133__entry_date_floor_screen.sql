-- 입력 최소일 **조회**를 전 역할에 연다(프론트 지적 2026-11-18 §2-1).
--
-- ‼️V132 로 `/closing/entry-date-floor` 를 냈는데 권한표에서 `/closing/**`(CLOSING, 90)에
--   먹혀 **영업·물류 계정은 403** 이 된다. 컨트롤러 주석엔 "조회는 누구나"라고 적어 뒀지만
--   권한표에 줄을 안 넣어 실제로는 마감 권한자만 읽을 수 있었다.
--   → V80(`/closing/period-status`)과 **같은 방식**으로 한 줄을 따로 둔다.
--
-- ★마감 화면(/closing/**)을 열어 주는 것이 아니다. 필요한 것은 "이 날짜 이전은 못 넣는다"
--   한 가지뿐이라, 그 한 가지만 내리는 경로를 따로 두고 거기만 연다.
--
-- ‼️sort_order 가 CLOSING(90)보다 **앞**이어야 한다. 매칭은 먼저 걸리는 패턴이 이긴다.
--   V80(85)과 겹치지 않게 86 으로 둔다.
--
-- ★패턴을 **두 줄로** 둔다 — `/closing/entry-date-floor` (정확히 그 경로)와
--   `/closing/entry-date-floor/**`. AntPathMatcher 에서 `/a/**` 가 `/a` 를 받아 주는지에
--   의존하지 않는다(버전·설정에 따라 갈리는 부분이라 추측으로 두면 403 이 조용히 남는다).
INSERT INTO menu_screen (code, name, menu_group, path_pattern, sort_order, created_at) VALUES
  ('ENTRY_DATE_FLOOR',    '입력최소일조회', 'CLOSING', '/closing/entry-date-floor',    86, NOW()),
  ('ENTRY_DATE_FLOOR_SUB','입력최소일조회', 'CLOSING', '/closing/entry-date-floor/**', 86, NOW());

-- 권한 — **ADMIN 은 WRITE, 나머지 셋은 READ.**
--
-- ‼️ADMIN 까지 READ 로 넣으면 **관리자의 변경(PUT)이 403** 이 된다.
--   V52 가 ADMIN 에게 전 화면 WRITE 를 주고({@code SELECT 'ADMIN', id, 'WRITE' FROM menu_screen}),
--   PermissionService 도 "관리자 권한은 낮출 수 없다"로 못박아 뒀다 — 새 화면도 같아야 한다.
--   (처음에 넷 다 READ 로 적었다가 이 규칙에 걸리는 것을 확인하고 고쳤다.)
--
-- 쓰기는 ADMIN 만 — 컨트롤러 @PreAuthorize("hasRole('ADMIN')") 가 한 번 더 본다(이중 방어).
--
-- ‼️역할은 4종뿐이다(ADMIN/FINANCE/LOGISTICS/SALES). 없는 역할명을 한 줄이라도 넣으면
--   권한표를 읽을 때 enum 변환이 터져 **모든 사용자의 모든 요청이 500** 이 된다(V80 주석 참고).
INSERT INTO role_screen_permission (role, screen_id, permission, updated_at)
SELECT 'ADMIN', ms.id, 'WRITE', NOW()
  FROM menu_screen ms
 WHERE ms.code IN ('ENTRY_DATE_FLOOR', 'ENTRY_DATE_FLOOR_SUB');

INSERT INTO role_screen_permission (role, screen_id, permission, updated_at)
SELECT r.role, ms.id, 'READ', NOW()
  FROM menu_screen ms
  CROSS JOIN (SELECT 'FINANCE' AS role UNION ALL SELECT 'LOGISTICS'
              UNION ALL SELECT 'SALES') r
 WHERE ms.code IN ('ENTRY_DATE_FLOOR', 'ENTRY_DATE_FLOOR_SUB');
