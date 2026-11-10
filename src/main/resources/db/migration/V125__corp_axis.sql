-- 법인 축(corp) — **데이터에만 남긴다**. 조회 조건 주입은 다음 단계다.
--
-- 근거: 발주처 회신 2026-11-10 §1 — 법인 분리는 8/7 자료요청서 3부 3-1(나)로 이미 전달된 요구다
--   ("리브커넥스는 별도 법인이므로 데이터도 완전히 분리").
--   §7 — "11/11에는 네이티브 조회 40개에 법인 조건을 넣는 작업이 필요 없습니다.
--          법인 축·조건 주입은 다음 단계(법인별 조회)에서 합니다."
--
-- ★그런데 **컬럼은 지금 넣는다.** 11/11에 리브커넥스 데이터를 빼면 그 행들이 백업으로 가는데,
--   법인 값이 그 안에 함께 실려 있어야 나중에 복원할 때 다시 채우는 단계가 없다.
--   컬럼을 읽는 코드가 아직 없으므로 조회·화면에는 아무 영향이 없다(엔티티에도 아직 넣지 않는다).
--
-- 값: 'DSLAB'(연구소) / 'DSTXTBOOK'(리브커넥스) / NULL(판정 불가 — 접두어 없는 상품 4종 등)
-- ‼️NULL 을 'DSLAB' 으로 뭉개지 않는다. 근거 없이 채우면 나중에 그 행이 어느 법인인지
--   되짚을 수 없고, 연구소 합계가 조용히 부푼다.

ALTER TABLE products                ADD COLUMN corp VARCHAR(10) NULL COMMENT '법인 DSLAB/DSTXTBOOK';
ALTER TABLE partners                ADD COLUMN corp VARCHAR(10) NULL COMMENT '법인 DSLAB/DSTXTBOOK';
ALTER TABLE sales                   ADD COLUMN corp VARCHAR(10) NULL COMMENT '법인 DSLAB/DSTXTBOOK';
ALTER TABLE collection              ADD COLUMN corp VARCHAR(10) NULL COMMENT '법인 DSLAB/DSTXTBOOK';
ALTER TABLE inventory_txn           ADD COLUMN corp VARCHAR(10) NULL COMMENT '법인 DSLAB/DSTXTBOOK';
ALTER TABLE shipment                ADD COLUMN corp VARCHAR(10) NULL COMMENT '법인 DSLAB/DSTXTBOOK';
ALTER TABLE receivable_carryforward ADD COLUMN corp VARCHAR(10) NULL COMMENT '법인 DSLAB/DSTXTBOOK';

-- 조회 조건이 들어갈 곳에 미리 인덱스를 둔다(다음 단계에서 전 조회에 `corp = ?` 가 붙는다).
CREATE INDEX ix_products_corp   ON products (corp);
CREATE INDEX ix_partners_corp   ON partners (corp);
CREATE INDEX ix_sales_corp      ON sales (corp, sales_date);
CREATE INDEX ix_collection_corp ON collection (corp, coll_date);
CREATE INDEX ix_invtxn_corp     ON inventory_txn (corp, trade_date);
CREATE INDEX ix_shipment_corp   ON shipment (corp, trade_date);
CREATE INDEX ix_carry_corp      ON receivable_carryforward (corp);
