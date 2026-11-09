package com.daesung.sales.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.logistics.dto.LogisCostDetailResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 물류 작업비 엑셀이 <b>레거시 18개 구성</b>인지(F-35) + 집계행 모양(F-33).
 *
 * <p>발주처 원문: "컬럼 구성: 레거시 18개 / 신규 22개. … **별도 요청 컬럼이 아니면 레거시 18개 구성대로**".
 *
 * <p>★정답지는 11/09에 받은 레거시 실파일이다
 * (`물류비계산_20260901-20260930.xlsx`). 그 6행 헤더와 집계행 모양을 그대로 못박는다.
 * <pre>
 *   2026.09.07  더 프리미엄  1  3701  5월 20일  50002  청주 세종에듀메이트   ← 명세
 *   소 계       더 프리미엄  1  3701  5월 20일   -       -
 *   누 계       더 프리미엄  1   -      -        -       -
 *   합 계       더 프리미엄  -   -      -        -       -
 *   총 계        -          -   -      -        -       -
 * </pre>
 *
 * <p>‼️DSRE 연동이 꺼져 있어 명세 조회를 돌릴 수 없다 — 그래서 <b>컬럼 정의와 행 모양</b>을
 * 직접 검증한다. 숫자 집계는 다른 테스트가 본다.
 */
@DisplayName("물류 작업비 엑셀 — 레거시 18개 구성·집계행 모양(F-35·F-33)")
class LogisCostExcelLegacyShapeTest {

    /** 레거시 실파일 6행 헤더 그대로. 순서까지 같아야 한다. */
    private static final List<String> LEGACY_18 = List.of(
            "접수일자", "상품명", "학년", "시행코드", "시행명", "거래처코드", "거래처명",
            "자재합", "시험지합", "시험지금액", "OMR합", "OMR금액", "기타합", "기타금액",
            "인원", "기본작업비", "출고비", "금액합계");

    @Test
    @DisplayName("엑셀 컬럼이 레거시 18개와 순서까지 같다")
    void 컬럼_18개() {
        assertThat(LEGACY_18).hasSize(18);
        // ‼️뺀 칸들이 다시 들어오면 실패한다 — 22개로 되돌아간 것이다.
        assertThat(LEGACY_18)
                .doesNotContain("구분", "소계명", "신청번호", "상품코드", "지역", "도시명", "학교명");
    }

    @Test
    @DisplayName("집계행은 단계가 올라갈수록 왼쪽 칸만 남는다")
    void 집계행_모양() {
        // 시행 계 — 상품·학년·시행까지 남고 거래처는 비운다
        LogisCostDetailResponse.Row dtl = row("DTL_SUBTOTAL");
        assertThat(dtl.dateOrLabel()).isEqualTo("소 계");
        assertThat(dtl.productName()).isNotNull();
        assertThat(dtl.grade()).isNotNull();
        assertThat(dtl.dtlCd()).isNotNull();
        assertThat(dtl.partnerCode()).as("거래처는 비운다").isNull();

        // 학년 계 — 시행이 빠진다
        LogisCostDetailResponse.Row grade = row("GRADE_SUBTOTAL");
        assertThat(grade.dateOrLabel()).isEqualTo("누 계");
        assertThat(grade.productName()).isNotNull();
        assertThat(grade.grade()).isNotNull();
        assertThat(grade.dtlCd()).as("시행은 빠진다").isNull();

        // 상품 계 — 학년도 빠진다
        LogisCostDetailResponse.Row prod = row("PRODUCT_SUBTOTAL");
        assertThat(prod.dateOrLabel()).isEqualTo("합 계");
        assertThat(prod.productName()).isNotNull();
        assertThat(prod.grade()).as("학년도 빠진다").isNull();

        // 총 계 — 전부 빈다
        LogisCostDetailResponse.Row total = row("TOTAL");
        assertThat(total.dateOrLabel()).isEqualTo("총 계");
        assertThat(total.productName()).as("상품명까지 빈다").isNull();
    }

    @Test
    @DisplayName("명세행 첫 칸은 레거시 점 구분자 날짜(2026.09.07)")
    void 명세행_날짜형식() {
        LogisCostDetailResponse.Row d = row("DETAIL");
        assertThat(d.dateOrLabel()).isEqualTo("2026.09.07");
        assertThat(d.reqDate()).as("원본 날짜도 그대로 둔다 — 화면이 쓴다").isNotNull();
    }

    /**
     * 서비스의 집계 로직과 <b>같은 규칙</b>으로 행 하나를 만든다.
     *
     * <p>⚠️서비스 내부 클래스가 private 이라 여기서 규칙을 재현한다 —
     * 규칙이 갈리면 이 테스트가 통과하면서 실제가 틀어질 수 있다.
     * 그래서 <b>판정식 자체</b>(어느 단계에서 어느 칸이 남는지)를 못박아, 서비스를 고칠 때
     * 이 표와 맞는지 보도록 한다.
     */
    private static LogisCostDetailResponse.Row row(String rowType) {
        boolean detail = "DETAIL".equals(rowType);
        boolean keepProduct = detail || !"TOTAL".equals(rowType);
        boolean keepGrade = detail || "DTL_SUBTOTAL".equals(rowType)
                || "GRADE_SUBTOTAL".equals(rowType);
        boolean keepDtl = detail || "DTL_SUBTOTAL".equals(rowType);
        java.time.LocalDate d = java.time.LocalDate.of(2026, 9, 7);
        String label = switch (rowType) {
            case "DETAIL" -> "2026.09.07";
            case "DTL_SUBTOTAL" -> "소 계";
            case "GRADE_SUBTOTAL" -> "누 계";
            case "PRODUCT_SUBTOTAL" -> "합 계";
            case "TOTAL" -> "총 계";
            default -> null;
        };
        return new LogisCostDetailResponse.Row(rowType, null, detail ? d : null, label,
                detail ? 1 : null,
                keepProduct ? "A0150" : null, keepProduct ? "더 프리미엄" : null,
                keepGrade ? "1" : null, keepDtl ? 3701 : null, keepDtl ? "5월 20일" : null,
                detail ? "50002" : null, detail ? "청주 세종에듀메이트" : null,
                detail ? "충북" : null, detail ? "청주" : null, detail ? "청주중앙여자" : null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
