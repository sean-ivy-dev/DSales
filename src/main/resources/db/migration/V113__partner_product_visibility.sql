-- 거래처 × 도서 노출 예외(10/31 클라이언트 회신 §1). 근거:
-- docs-local/특약점 /특약점사이트-개발전달-261031.md §1-1~1-3.
--
-- 노출 판단 순서(위에서부터 처음 걸리는 규칙):
--   ① products.web_visible = N        → 비노출 (예외보다 우선)
--   ② 이 테이블에 행이 있음           → 그 행의 visible (열기=노출, 닫기=비노출)
--   ③ partner_supply_rate 매핑이 있음 → 그 행의 web_visible
--   ④ 매핑 행 없음                    → 비노출
--
-- 행이 없으면 "예외 없음"(③④로 떨어진다). 예외를 해제하면 행을 지운다(회신 원문).
CREATE TABLE partner_product_visibility
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    partner_id  BIGINT      NOT NULL,
    product_id  BIGINT      NOT NULL,
    visible     BOOLEAN     NOT NULL COMMENT 'true=열기(강제 노출) / false=닫기(강제 비노출)',
    created_at  DATETIME    NOT NULL,
    created_by  VARCHAR(50) NULL,
    updated_at  DATETIME    NULL,
    updated_by  VARCHAR(50) NULL,
    CONSTRAINT uq_ppv_partner_product UNIQUE (partner_id, product_id),
    CONSTRAINT fk_ppv_partner FOREIGN KEY (partner_id) REFERENCES partners (id),
    CONSTRAINT fk_ppv_product FOREIGN KEY (product_id) REFERENCES products (id)
) COMMENT '거래처×도서 노출 예외(10/31 회신 §1)';

CREATE INDEX ix_ppv_partner ON partner_product_visibility (partner_id);
