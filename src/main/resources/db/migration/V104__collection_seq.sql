-- 수금 순번(coll_seq) — 10/27 명세 §6-1, 프론트 B-7.
--
-- 명세: "기존 매출프로그램처럼 해당 수금일자 안에서 1, 2, 3… **순번만 표시**한다
--        (레거시: 전표코드 드롭다운 1~5)."
-- 프론트 B-7: "레거시는 **거래처·수금일자마다** 모든 수금에 순번을 1부터 매기고,
--              수금 등록 창의 순번 드롭다운으로 그 수금을 **불러와 고칩니다**."
--
-- ★레거시 원본이 그대로 확인된다(`수금등록.vb`):
--   · RefeshSeq()  : SELECT collSeq FROM amtData
--                     WHERE collDate LIKE 'yyyyMMdd%' AND custCode = ? ORDER BY collSeq
--                    → 드롭다운을 채우고 **max+1** 을 새 번호로 덧붙인다
--   · 불러오기/삭제: WHERE left(collDate,8)=? AND custCode=? AND collSeq=?
--   즉 키는 (수금일자 + 거래처 + 순번)이고, 범위는 **날짜만이 아니라 거래처까지**다.
--
-- ‼️기존 `voucher_no`(V-yyyyMMdd-n)와 **다른 축이다. 합치지 말 것.**
--   · voucher_no : 한 번에 여러 건을 등록한 **묶음**(현장 피드백 2026-10-14 B-5).
--                  일괄 등록분에만 있고, 거래처가 섞여 있을 수 있다.
--   · coll_seq   : **거래처·수금일자 안에서의 그 수금 자체의 번호**. 모든 수금에 있다.
--                  담당자가 드롭다운에서 고르는 값이고, 그걸로 수금을 불러온다.
--   하나로 합치면 "일괄로 올린 건"과 "그 거래처의 몇 번째 수금인가"를 같이 표현할 수 없다.
--
-- 이관분은 NULL 이다 — 소급해 채우면 없던 순서를 만드는 것이다.
ALTER TABLE collection
    ADD COLUMN coll_seq INT NULL
        COMMENT '수금순번 — 그 거래처의 그 수금일자 안에서 몇 번째인가(1부터). 레거시 amtData.collSeq';

-- 조회 조건 "거래처 + 수금일자 + 순번"(프론트 B-7②)이 이 순서로 탄다.
CREATE INDEX ix_collection_seq ON collection (partner_id, coll_date, coll_seq);
