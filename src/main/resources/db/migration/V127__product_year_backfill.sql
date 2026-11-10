-- 상품년도(`product_year`) 백필 — 응시현황(기간별) 상품년도 조건이 **늘 0건**이던 것
-- (프론트 실측 2026-11-10 §4).
--
-- ★원인: V24 가 칸만 만들고 **이관에서 채우지 않았다.** 도서 14,808권 중 값이 있는 것이 0건이라,
--   `productYears` 를 어느 연도로 걸어도 0행이었다.
--
-- ★근거: 레거시 상품년도는 **분류코드의 연도**다 — `substring(catCode, 2, 4)`
--   (`CommonDB.GetCatYearList`). 분류코드 형식이 `[영문1][연도4][영문·숫자1~3]` 이고
--   **우리 14,808권 전부 그 형식**이다(실측). 연도 범위 2007~2026 으로 합리적이다.
--
-- ‼️**빈 칸만 채운다.** 담당자가 화면에서 넣은 값은 덮지 않는다.
-- ‼️형식에 맞지 않는 분류코드는 건드리지 않는다(지금은 0건이지만 수기 등록으로 생길 수 있다).
--   근거 없이 연도를 만들면 그 상품이 엉뚱한 해로 묶인다.
-- ‼️여러 번 돌려도 결과가 같다.

UPDATE products
   SET product_year = CAST(SUBSTRING(cat_code, 2, 4) AS UNSIGNED)
 WHERE product_year IS NULL
   AND cat_code REGEXP '^[A-Za-z][0-9]{4}'
   AND CAST(SUBSTRING(cat_code, 2, 4) AS UNSIGNED) BETWEEN 1990 AND 2100;
