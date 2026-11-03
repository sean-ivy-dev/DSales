-- 창고 없이 저장된 옛 반품 3건 백필(특약점팀 전달 K-1, 2026-11-03).
--
-- 11/03 I-4 수정(return-inbound 경로가 sale.applyWarehouse(warehouse)를 호출하지 않던
-- 결함) 이전에 등록된 반품은 매출(sales) 행의 창고가 비어 있다. 그 결함은 Sale.warehouse_id
-- 컬럼만 비는 것이었고, 반품입고 당시 inventory_txn(재고 이벤트)은 이미 정상 기록됐다 —
-- 그래서 재고 수량은 다시 움직이지 않는다. 이 마이그레이션은 sales.warehouse_id 한 컬럼만
-- 채운다(V109 only_sales 백필과 같은 패턴 — 특정 salesNo만 지정).
--
-- 대상 3건(배포 서버 2026-06-01~2026-11-03 반품 565줄 중 창고가 빈 전부):
--   I-20261022-527, I-20261027-531, I-20261102-555
-- 창고는 본사 물류창고(W-MAIN)로 채운다 — "창고가 기록된 반품은 확인한 범위에서 모두
-- 본사 물류창고"(특약점팀 확인) + 반품은 실물 재고(물류창고)로 복귀하는 것이 정상 흐름.
--
-- ‼️특약점팀이 "같은 조건이 더 있으면 함께"라고 했어서, sales_no 세 개로 좁히기 전에
--   라이브 반품 565건(해당 기간 전체, 3페이지)을 직접 다 훑었다(2026-11-03) —
--   창고가 빈 반품은 이 세 건뿐이었다. 그래서 조건절(warehouse_id is null 전체)이 아니라
--   이 세 salesNo만 명시한다 — 다른 이유로 창고가 비어 있을 수 있는 행(아직 모르는 경우)을
--   이 마이그레이션이 조용히 건드리지 않도록 범위를 좁혀 둔다.
-- warehouse_id IS NULL 조건은 재실행 안전장치(이미 채워졌으면 다시 덮지 않음)다.
UPDATE sales
   SET warehouse_id = (SELECT id FROM warehouses WHERE code = 'W-MAIN')
 WHERE sales_no IN ('I-20261022-527', 'I-20261027-531', 'I-20261102-555')
   AND sales_category = 'RETURN'
   AND warehouse_id IS NULL;
