-- 특약점 재고현황(10/31 클라이언트 회신 §3, 10/26 §6 확정). 근거:
-- docs-local/특약점 /특약점사이트-개발전달-261031.md §3 —
-- "로직: 현행 그대로다. 구매·반품·재고를 수기로 입력하고, 출고는 구매−재고−반품으로
--  계산해 표시만 한다. … 저장 위치: 도서 키가 매출 product_id로 바뀐다."
--
-- ★월 축이 없다 — 레거시(DSRE2 tbl_booklist_cnt)와 같은 모양으로, 거래처×도서 조합당
--   값이 하나이고 다시 입력하면 덮어쓴다. 출고(shipQty)는 저장하지 않고 조회 시
--   구매−재고−반품으로 계산한다(레거시 FM_DSRE_BookList.cs:229 와 같은 공식).
CREATE TABLE partner_product_stock
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    partner_id  BIGINT      NOT NULL,
    product_id  BIGINT      NOT NULL,
    purchase_qty INT        NOT NULL DEFAULT 0 COMMENT '구매 — 특약점 수기입력',
    return_qty  INT         NOT NULL DEFAULT 0 COMMENT '반품 — 특약점 수기입력',
    stock_qty   INT         NOT NULL DEFAULT 0 COMMENT '재고 — 특약점 수기입력',
    created_at  DATETIME    NOT NULL,
    created_by  VARCHAR(50) NULL,
    updated_at  DATETIME    NULL,
    updated_by  VARCHAR(50) NULL,
    CONSTRAINT uq_pps_partner_product UNIQUE (partner_id, product_id),
    CONSTRAINT fk_pps_partner FOREIGN KEY (partner_id) REFERENCES partners (id),
    CONSTRAINT fk_pps_product FOREIGN KEY (product_id) REFERENCES products (id)
) COMMENT '특약점 재고현황(10/31 회신 §3) — 거래처×도서, 월 축 없음(계속 덮어씀)';

CREATE INDEX ix_pps_partner ON partner_product_stock (partner_id);
