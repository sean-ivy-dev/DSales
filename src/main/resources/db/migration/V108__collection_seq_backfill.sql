-- 수금 순번 **백필** — 프론트 B-7① "**모든 수금에** 거래처·수금일자별 순번을 매겨 응답에 실어 주세요".
--
-- ‼️V104 는 컬럼만 만들고 **기존 수금을 채우지 않았다**. 그래서 배포 뒤에 등록한 수금만
--   번호가 붙고, 그 전 수금은 전부 공란이었다 — 대성 테스트(2026-11-02 #53)에서
--   "11/2 수금 2건 전표코드 공란, 8~9월 기존 수금도 공란"으로 잡혔다.
--   담당자는 드롭다운에서 고를 수 없으니 그 수금을 불러와 고칠 길이 없다.
--
-- 규칙은 등록 경로와 같다 — **거래처 × 수금일자** 범위에서 1부터, 등록 순서(id)대로.

-- (1) 비어 있는 수금에 번호를 매긴다.
--     ★이미 번호가 있는 수금이 같은 범위에 있으면 **그 다음 번호부터** 이어 붙인다.
--       1부터 다시 시작하면 같은 번호가 서로 다른 수금을 가리킨다.
--     ★삭제된 수금은 번호를 쓰지 않는다 — 화면에 안 나오므로 번호를 소비할 이유가 없다.
UPDATE collection c
  JOIN (
        SELECT x.id,
               COALESCE(m.mx, 0)
                 + ROW_NUMBER() OVER (PARTITION BY x.partner_id, x.coll_date ORDER BY x.id) AS rn
          FROM collection x
          LEFT JOIN (SELECT partner_id, coll_date, MAX(coll_seq) mx
                       FROM collection
                      WHERE coll_seq IS NOT NULL
                      GROUP BY partner_id, coll_date) m
                 ON m.partner_id = x.partner_id AND m.coll_date = x.coll_date
         WHERE x.coll_seq IS NULL AND x.deleted_at IS NULL
       ) t ON t.id = c.id
   SET c.coll_seq = t.rn;

-- (2) 채번 카운터를 백필 결과에 맞춘다.
--     ‼️**이게 없으면 백필한 범위의 다음 수금이 1 부터 다시 나온다** — 그 범위는 이미
--       1,2,3 을 쓰고 있으므로 같은 번호가 둘이 된다(드롭다운에서 어느 수금인지 알 수 없다).
--     키 형식은 `SequenceService.nextInScope(SEQ_COLLECTION_SEQ, partnerId + ":" + collDate)`
--     가 만드는 것과 **글자까지 같아야** 한다 — `seq_collection_seq:{거래처id}:{yyyy-MM-dd}`.
INSERT INTO seq_registry (seq_name, seq_val)
SELECT CONCAT('seq_collection_seq:', partner_id, ':', DATE_FORMAT(coll_date, '%Y-%m-%d')),
       MAX(coll_seq)
  FROM collection
 WHERE coll_seq IS NOT NULL
 GROUP BY partner_id, coll_date
ON DUPLICATE KEY UPDATE seq_val = GREATEST(seq_registry.seq_val, VALUES(seq_val));
