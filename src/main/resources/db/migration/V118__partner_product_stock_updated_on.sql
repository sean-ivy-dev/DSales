-- 특약점 재고현황 「최종수정일」 — 특약점팀 요청(2026-11-05).
--
-- 지금 운영 중인 DSRE [교재 > 재고현황] 화면에 "최종수정일"(특약점이 재고를 마지막으로
-- 고친 날) 칸이 있는데 새 API 응답엔 없어서, 화면을 옮기면 그 칸이 빈다.
--
-- ★왜 BaseEntity.updated_at 을 쓰지 않는가 — 그 값은 **어떤 수정이든** 바뀐다
--   (이관 시딩·매입/반품 변경 포함). 특약점팀 요구는 "**재고(stock_qty)를 저장한 날만**
--   바뀌어야 한다"이다. 자동집계·이관 때문에 날짜가 움직이면 "내가 안 고쳤는데 날짜가
--   바뀌었다"가 된다 → 전용 컬럼으로 분리한다.
--
-- 이관 행은 DSRE tbl_booklist_cnt.EDIT_WEB(웹 수정일, date)을 그대로 옮긴다 —
-- 그래야 지금 화면과 같은 값이 보인다.
ALTER TABLE partner_product_stock
    ADD COLUMN stock_updated_on DATE NULL
        COMMENT '재고(stock_qty)를 마지막으로 저장한 날. 이관 행은 DSRE EDIT_WEB 값';
