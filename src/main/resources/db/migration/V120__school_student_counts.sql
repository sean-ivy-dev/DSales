-- 학교 학년별 **학급수·재학생 수**. 근거: 발주처 검수 F-22·F-23
--   ("응시현황에 학년 칸이 비어 있다" · "인원·비율이 무엇인지").
--
-- ★이 값이 응시현황의 '인원'이다 — 매출 수량이 아니다.
--   레거시 `응시현황.vb:526` 원문:
--     SUM(ISNULL(cls1SCnt,0)) AS '인원_1학년' … + cls2 + cls3 AS '인원_계'
--   그리고 '비율' = 판매 **부수** ÷ 재학생 수 × 100 = **보급률**(:511).
--
-- ★원천은 레거시 `DSLab.schAllData` **2,416개 학교**(학년별 값이 2,391곳 채워짐).
--   ‼️이관에서 빠졌던 것이다 — "자료가 없어 못 한다"고 보고했던 것을 2026-11-09에 정정했다.
--
-- ★연도 축을 두지 않는다. 레거시 `schAllData` 에 **연도 칸이 없고**(컬럼 19개 전수 확인),
--   `응시현황.vb` 가 그 값을 그대로 집계한다 = **한 벌만 두고 덮어쓰는** 구조다.
--   해마다 바뀌는 값이지만 레거시와 같은 숫자를 내는 것이 먼저다.
--   연도별 보관이 필요해지면 별도 표로 붙인다(지금 칸을 늘려 두면 쓰지 않는 축이 남는다).
--
-- ★`*_ratio`(학급당 평균 인원)는 **저장하지 않는다.** 레거시는 저장했지만
--   `ratio = 재학생 ÷ 학급수` 가 2,391건 **전부** 성립한다(실측) → 파생값이다.
--   저장하면 학급수를 고쳤을 때 둘이 어긋난다.

ALTER TABLE schools
    ADD COLUMN cls1_class_cnt   INT NULL COMMENT '1학년 학급수(레거시 schAllData.cls1GCnt)',
    ADD COLUMN cls1_student_cnt INT NULL COMMENT '1학년 재학생 수(cls1SCnt) — 응시현황 인원',
    ADD COLUMN cls2_class_cnt   INT NULL COMMENT '2학년 학급수(cls2GCnt)',
    ADD COLUMN cls2_student_cnt INT NULL COMMENT '2학년 재학생 수(cls2SCnt)',
    ADD COLUMN cls3_class_cnt   INT NULL COMMENT '3학년 학급수(cls3GCnt)',
    ADD COLUMN cls3_student_cnt INT NULL COMMENT '3학년 재학생 수(cls3SCnt)';
