-- 특약점 재고현황 교재(PM 2026-11-12 §1): 모든 특약점에 같은 교재 목록을 보인다.
-- 레거시 특약점 화면은 DSRE 교재 목록(tbl_booklist_info/_dtl USE_YN='Y') 하나로 모든 특약점에
-- 같은 10종을 보였다. 신규는 거래처별 신청 노출(web_visible + 거래처×대분류 설정)을 재사용해서
-- 설정이 켜진 3곳에만 교재가 보였고, 나머지는 재고 기록이 있는 교재만 보였다.
-- web_visible 은 신청 화면 노출 기준이라 건드리지 않고 축을 따로 둔다.
ALTER TABLE products
    ADD COLUMN dealer_stock_listed BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '특약점 재고현황 교재 — 모든 특약점 재고현황에 보이고 재고 입력 가능';

-- 초기값 = 레거시 특약점 교재 10종(2026-11-11 대조, 정가 10종 일치)
UPDATE products SET dealer_stock_listed = TRUE
 WHERE code IN ('DL-H2017C-03', 'DL-H2017C-04', 'DL-H2017C-07', 'DL-H2017C-08', 'DL-H2024A-01',
                'DL-H2025A01-01', 'DL-H2025A01-02', 'DL-H2025A01-03', 'DL-H2025A01-04',
                'DL-H2025A02-01');
