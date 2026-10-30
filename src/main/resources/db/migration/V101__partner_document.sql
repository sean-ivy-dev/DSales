-- 특약점 배포용 PDF 보관함. 근거: 10/26 특약점 명세 §7 「특약점 PDF — 매출프로그램에서 직접 업로드」
-- + 10/27 검수 피드백 §7-8(버튼 이름을 [특약점 업로드]로, 그 자리에서 업로드).
--
-- ★ 무엇을 담나
--   거래처별 외상매출장을 PDF로 뽑아 올려 두면, 특약점이 사이트에서 자기 것만 내려받는다.
--   파일명이 곧 메타데이터다 — `SALES_10003_강남 하람도서_20260101-20260831.pdf`
--   (종류 SALES/RESULT · 거래처코드 · 기간). **기준월 = 기간 종료일의 월**(명세 §7).
--
-- ★ 왜 파일을 DB에 담나 (content LONGBLOB)
--   ① 앱이 도커 컨테이너로 떠서 **로컬 파일은 재배포 때 사라진다**.
--   ② S3 같은 외부 스토리지 설정이 이 프로젝트에 아직 없다(자격증명·버킷 모두).
--   ③ 규모가 감당된다 — 거래처 200 × 12개월 × 2종 × 약 300KB ≈ 1.4GB.
--   스토리지가 생기면 이 칸을 경로로 바꾸면 된다(서비스 한 곳만 고치면 되도록 묶어 뒀다).
--
-- ★ 거래처코드는 **DSRE 코드**(10003 꼴)다 — 우리 매출코드(A10A195)가 아니다.
--   레거시 파일명이 그 체계라 그대로 받고, 거래처 연결은 V100 의 매핑표(1:N)로 푼다.
--   ‼️못 찾아도 **파일은 받는다**(partner_id NULL). 코드만 맞춰 두면 나중에 매핑이
--     채워질 때 이어 붙일 수 있다 — 업로드를 막으면 담당자가 그 달 전체를 못 올린다.

CREATE TABLE partner_document
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_type     VARCHAR(10)  NOT NULL COMMENT 'SALES(외상매출장 일반)/RESULT(더프모)',
    partner_id   BIGINT       NULL COMMENT '우리 거래처. 파일명 코드로 못 찾으면 NULL',
    partner_code VARCHAR(20)  NOT NULL COMMENT '파일명에서 읽은 DSRE 거래처코드',
    partner_name VARCHAR(100) NULL COMMENT '파일명에서 읽은 거래처명(참고용 — 식별은 코드로 한다)',
    -- ‼️CHAR(7)이 아니라 VARCHAR(7)이다 — 엔티티의 String 매핑과 타입이 어긋나면
    --   Hibernate 스키마 검증이 기동 자체를 막는다(실제로 그렇게 떴다).
    base_month   VARCHAR(7)   NOT NULL COMMENT '기준월 yyyy-MM = 기간 종료일의 월',
    period_from  DATE         NOT NULL,
    period_to    DATE         NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    file_size    INT          NOT NULL COMMENT '바이트',
    content      LONGBLOB     NOT NULL COMMENT 'PDF 원본',
    created_at   DATETIME     NOT NULL,
    created_by   VARCHAR(50)  NULL,
    updated_at   DATETIME     NULL,
    updated_by   VARCHAR(50)  NULL,

    -- 같은 거래처·종류·기준월을 다시 올리면 **교체**한다(명세 §7 '중복').
    -- ‼️논리삭제를 두지 않는다 — 교체가 곧 덮어쓰기라 지워진 PDF를 되살릴 일이 없고,
    --   soft delete 를 두면 이 UNIQUE 가 죽은 행과 충돌해 재업로드가 막힌다.
    CONSTRAINT uq_partner_document UNIQUE (doc_type, partner_code, base_month),
    CONSTRAINT fk_pd_partner FOREIGN KEY (partner_id) REFERENCES partners (id)
) COMMENT '특약점 배포용 PDF(외상매출장). 파일명이 메타데이터다';

-- 사이트가 "내 것 + 최근 12개월"로 훑는다 — 그 조회의 축.
CREATE INDEX idx_pd_partner_month ON partner_document (partner_id, base_month);
CREATE INDEX idx_pd_code_month ON partner_document (partner_code, base_month);
