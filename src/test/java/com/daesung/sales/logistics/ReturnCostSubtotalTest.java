package com.daesung.sales.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import com.daesung.sales.dsre.gateway.LogisMode;
import com.daesung.sales.dsre.gateway.ReturnCostDetailRow;
import com.daesung.sales.logistics.dto.ReturnCostDetailResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 회수 작업비 명세 <b>소계 다섯 축</b>(발주처 2026-11-23 4번 8쪽).
 *
 * <p>DSRE 연동은 테스트에서 꺼져 있어({@code daesung.dsre.enabled}) 조회를 띄울 수 없다.
 * 소계 묶는 규칙은 순수 계산이라 여기서 고정한다 — 행이 들어오면 축이 맞게 갈리는지.
 */
@DisplayName("회수 작업비 소계 — 상품·시행·거래처(2026-11-23 4번)")
class ReturnCostSubtotalTest {

    private static final LocalDate D1 = LocalDate.of(2026, 10, 5);
    private static final LocalDate D2 = LocalDate.of(2026, 10, 7);

    private static ReturnCostDetailRow row(String mode, String prodCd, String prodNm,
                                           int exam, String examNm,
                                           String custCd, String custNm, int qty, int unit) {
        return new ReturnCostDetailRow(D1, 100, "시험지", "시험지", mode,
                prodCd, prodNm, "3", exam, examNm, custCd, custNm, "서울",
                qty, unit, (long) qty * unit);
    }

    @Test
    @DisplayName("★세 축이 각각 맞게 묶이고, 축마다 합이 총계와 같다")
    void 세축_소계() {
        List<ReturnCostDetailRow> rows = List.of(
                row("NORMAL", "A0122", "더 프리미엄", 3785, "11월 2일", "10003", "강남 하람도서", 10, 50),
                row("NORMAL", "A0122", "더 프리미엄", 3785, "11월 2일", "20002", "부산 교학사", 4, 50),
                row("ACCIDENT", "A0122", "더 프리미엄", 3788, "11월 9일", "10003", "강남 하람도서", 6, 50),
                row("NORMAL", "B0199", "강대모의고사", 3790, "11월 16일", "10003", "강남 하람도서", 2, 50));

        var r = ReturnCostDetailResponse.of(D1, D2, LogisMode.ALL, rows);

        assertThat(r.totalQty()).isEqualTo(22);
        assertThat(r.totalAmount()).isEqualTo(1100);

        // 상품 — 두 상품으로 갈린다. 키는 `코드 이름`.
        assertThat(r.byProduct()).extracting("key", "qty", "amount")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A0122 더 프리미엄", 20L, 1000L),
                        org.assertj.core.groups.Tuple.tuple("B0199 강대모의고사", 2L, 100L));

        // 시행 — 세 시행.
        assertThat(r.byExam()).extracting("key", "qty")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("3785 11월 2일", 14L),
                        org.assertj.core.groups.Tuple.tuple("3788 11월 9일", 6L),
                        org.assertj.core.groups.Tuple.tuple("3790 11월 16일", 2L));

        // 거래처 — 두 곳.
        assertThat(r.byPartner()).extracting("key", "qty")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("10003 강남 하람도서", 18L),
                        org.assertj.core.groups.Tuple.tuple("20002 부산 교학사", 4L));

        // ‼️축마다 합이 총계와 같아야 한다 — 하나라도 빠지면 화면 소계가 총계와 안 맞는다.
        for (List<ReturnCostDetailResponse.Subtotal> axis :
                List.of(r.byProduct(), r.byExam(), r.byPartner(), r.byType(), r.byMode())) {
            assertThat(axis.stream().mapToLong(ReturnCostDetailResponse.Subtotal::qty).sum())
                    .as("축 합 = 총 수량").isEqualTo(r.totalQty());
            assertThat(axis.stream().mapToLong(ReturnCostDetailResponse.Subtotal::amount).sum())
                    .as("축 합 = 총 금액").isEqualTo(r.totalAmount());
        }
    }

    @Test
    @DisplayName("‼️마스터가 비어도 **행이 사라지지 않는다** — `(미지정)` 으로 묶인다")
    void 마스터가_비면_미지정() {
        // 사고 중 신청이 지워진 건이 이 모양으로 온다(시행코드 0 · 상품·거래처 null).
        var r = ReturnCostDetailResponse.of(D1, D2, LogisMode.ACCIDENT, List.of(
                row("ACCIDENT", null, null, 0, null, null, null, 3, 50)));

        assertThat(r.byProduct()).extracting("key").containsExactly("(미지정)");
        assertThat(r.byExam()).extracting("key").containsExactly("(미지정)");
        assertThat(r.byPartner()).extracting("key").containsExactly("(미지정)");
        assertThat(r.totalQty()).as("행은 살아 있어야 한다").isEqualTo(3);
    }

    @Test
    @DisplayName("코드만 있고 이름이 없으면 코드만 쓴다 — 빈 꼬리를 붙이지 않는다")
    void 이름이_없으면_코드만() {
        var r = ReturnCostDetailResponse.of(D1, D2, LogisMode.NORMAL, List.of(
                row("NORMAL", "A0122", null, 3785, null, "10003", null, 1, 50)));
        assertThat(r.byProduct()).extracting("key").containsExactly("A0122");
        assertThat(r.byExam()).extracting("key").containsExactly("3785");
        assertThat(r.byPartner()).extracting("key").containsExactly("10003");
    }
}
