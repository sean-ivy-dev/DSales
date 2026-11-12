-- 거래처 만료의 **출처**를 구분한다(발주처 지적 2026-11-12 후속).
--
-- 만료가 들어오는 길이 두 개다.
--   ① 이 프로그램에서 [거래 종료]      — 담당자가 직접 끝낸 것. 날짜도 그때가 맞다.
--   ② DSRE 동기화(`END_GUBUN='Y'`)     — DSRE 에서 끝낸 것. ‼️DSRE 에 날짜 칸이 없어
--                                        "동기화가 처음 Y 를 본 날"을 적는다(실제 만료일이 아니다).
--
-- ‼️이 칸이 없으면 **DSRE 가 N 으로 돌아올 때 ①까지 풀린다.**
--   담당자가 직접 끝낸 거래처가 DSRE 쪽 사정으로 되살아나면 안 된다.
--   그래서 동기화는 `end_source='DSRE'` 인 행만 풀고 나머지는 건드리지 않는다.
--
-- 기존 행은 전부 ① 이다:
--   · 레거시에서 이관된 458곳(V128)  — 레거시 거래처관리에서 끝낸 것
--   · 운영에서 [거래 종료]한 17곳     — 전부 시험 거래처(ZZT…)
--   DSRE 유래는 아직 하나도 없다(동기화가 END_GUBUN 을 읽기 시작한 적이 없다).

ALTER TABLE partners
    ADD COLUMN end_source VARCHAR(10) NULL
        COMMENT '만료 출처 MANUAL(거래 종료·레거시 이관) / DSRE(END_GUBUN 동기화). NULL=만료 아님';

UPDATE partners SET end_source = 'MANUAL' WHERE end_date IS NOT NULL;

CREATE INDEX ix_partners_end_source ON partners (end_source);
