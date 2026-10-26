package com.daesung.sales.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daesung.sales.audit.entity.StatusEntityType;
import com.daesung.sales.audit.service.StatusHistoryService;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.dsre.gateway.DsreGateway;
import com.daesung.sales.dsre.gateway.DsreOrderRow;
import com.daesung.sales.dsre.gateway.DsreShipResult;
import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.order.service.OrderService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 진행상태 <b>수동 전환 · 다건 일괄</b> 규칙 회귀 고정.
 * 근거: 자료요청서 3-2(가).진행상태 — 발송준비중 "되돌릴 때는 <b>수동 전환</b>",
 * 발송완료 "물류가 발송 처리(<b>체크박스 다건 일괄</b> 포함)".
 *
 * <p>DSRE2는 <b>남의 운영 DB</b>다. 잘못 쓰면 우리 화면이 아니라 본사 물류 업무가 틀어진다.
 * DSRE 연동은 통합테스트에서 꺼져 있으므로(daesung.dsre.enabled=false) 규칙은 여기서 못박는다.
 *
 * <p>★고정하려는 것은 넷이다.
 * <ol>
 *   <li>정본 표에 <b>있는 전이만</b> 통과한다. 접수완료가 곧바로 발송완료가 되면 안 된다.</li>
 *   <li>한 건이 안 된다고 <b>전체를 실패시키지 않는다</b>(다건 일괄의 존재 이유).</li>
 *   <li>조회와 변경 사이에 남이 먼저 바꿨으면 <b>성공으로 치지 않는다</b>.</li>
 *   <li>바꾼 건은 <b>이력에 남는다</b>. DSRE2엔 이력이 없어 우리가 유일한 기록이다.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("진행상태 수동 전환 · 다건 일괄")
class OrderStateChangeTest {

    @Mock private DsreGateway dsreGateway;
    @Mock private StatusHistoryService statusHistoryService;
    @Mock private com.daesung.sales.logistics.service.ShipmentService shipmentService;
    @Mock private com.daesung.sales.common.audit.CurrentAuditor currentAuditor;
    @InjectMocks private OrderService orderService;

    private static DsreOrderRow order(int reqCd, String stateCode) {
        return new DsreOrderRow(reqCd, "20260720", stateCode, "", "00001", "거래처", "거래처(주)", "서울",
                "00001", "학교", "상품", "1회", "P0001", 1, 2026, "3",
                100, 4, "처리", null, null, null, null, null, null,
                1200L, 4, null, null);
    }

    private static List<DsreShipResult> shipped(int reqCd, String before) {
        return List.of(new DsreShipResult(reqCd, before, DsreShipResult.Outcome.CHANGED, null));
    }

    private static List<DsreShipResult> conflict(int reqCd, String now) {
        return List.of(new DsreShipResult(reqCd, now, DsreShipResult.Outcome.SKIPPED, null));
    }

    private void given(int reqCd, String state) {
        when(dsreGateway.findOrder(reqCd)).thenReturn(Optional.of(order(reqCd, state)));
    }

    @Test
    @DisplayName("★발송준비중 → 상품준비중 되돌리기 — 사유가 이력에 남는다")
    void 되돌리기() {
        given(1, "W");
        when(dsreGateway.changeState(1, "W", "S")).thenReturn(1);

        var r = orderService.changeState(List.of(1), OrderState.PREPARING, "물류 재검수 필요");

        assertThat(r.changed()).isEqualTo(1);
        assertThat(r.results().get(0).fromName()).isEqualTo("발송준비중");
        assertThat(r.results().get(0).toName()).isEqualTo("상품준비중");
        // ★DSRE2는 제자리 UPDATE라 이전 값이 사라진다 — 안 남기면 되돌린 사실이 어디에도 없다.
        verify(statusHistoryService).record(eq(StatusEntityType.DSRE_ORDER), eq(1L), eq("STATE"),
                eq("W"), eq("S"), eq("물류 재검수 필요"));
    }

    @Test
    @DisplayName("사유를 안 적어도 이력은 남는다 — 기록이 사라지는 것보다 낫다")
    void 사유_미입력() {
        given(1, "W");
        when(dsreGateway.changeState(1, "W", "S")).thenReturn(1);

        orderService.changeState(List.of(1), OrderState.PREPARING, "  ");

        verify(statusHistoryService).record(any(), anyLong(), anyString(), anyString(), anyString(),
                eq("수동 되돌리기(사유 미입력)"));
    }

    @Test
    @DisplayName("★★발송취소는 우리 쪽 발송 기록도 내린다 — 그게 없어서 '취소가 안 된다'는 지적을 받았다")
    void 발송취소는_발송일도_내린다() {
        given(7, "D");
        when(dsreGateway.unship(eq(List.of(7)), any())).thenReturn(shipped(7, "D"));

        var r = orderService.changeState(List.of(7), OrderState.READY_TO_SHIP, "오발송");

        assertThat(r.changed()).isEqualTo(1);
        // DSRE STATE 만 되돌리면 작업요청서 화면은 계속 발송완료로 보인다 —
        // 그 화면의 '발송완료'는 shipment.sent_date 로 판정한다(축이 둘이었다).
        verify(shipmentService).revertSentByReqCd(eq(7), eq("오발송"));
    }

    @Test
    @DisplayName("발송취소가 아닌 전이에서는 발송 기록을 건드리지 않는다")
    void 다른_전이는_발송일을_안_건드린다() {
        given(8, "W");
        when(dsreGateway.changeState(8, "W", "S")).thenReturn(1);

        orderService.changeState(List.of(8), OrderState.PREPARING, "재검수");

        // 발송준비중 되돌리기는 애초에 발송된 건이 아니다. 여기서 내리면
        // 아직 안 보낸 건의 발송일을 지우려 들어 이력에 거짓 기록이 남는다.
        verify(shipmentService, never()).revertSentByReqCd(anyInt(), anyString());
    }

    @Test
    @DisplayName("★발송완료 다건 일괄 — 체크한 만큼 한 번에 처리된다")
    void 발송완료_일괄() {
        given(1, "W");
        given(2, "W");
        when(dsreGateway.ship(eq(List.of(1)), any(), any())).thenReturn(shipped(1, "W"));
        when(dsreGateway.ship(eq(List.of(2)), any(), any())).thenReturn(shipped(2, "W"));

        var r = orderService.changeState(List.of(1, 2), OrderState.SHIPPED, "물류 발송");

        assertThat(r.requested()).isEqualTo(2);
        assertThat(r.changed()).isEqualTo(2);
        assertThat(r.skipped()).isZero();
    }

    @Test
    @DisplayName("★한 건이 안 돼도 나머지는 처리된다 — 100건 체크했는데 1건 때문에 다 실패하면 못 쓴다")
    void 부분_실패는_전체를_막지_않는다() {
        given(1, "W");
        given(2, "D");   // 이미 발송완료 — 넘어가야 할 건
        when(dsreGateway.ship(eq(List.of(1)), any(), any())).thenReturn(shipped(1, "W"));

        var r = orderService.changeState(List.of(1, 2), OrderState.SHIPPED, null);

        assertThat(r.changed()).isEqualTo(1);
        assertThat(r.skipped()).isEqualTo(1);

        var skipped = r.results().stream().filter(x -> !x.changed()).findFirst().orElseThrow();
        assertThat(skipped.reqCd()).isEqualTo(2);
        assertThat(skipped.message()).as("왜 넘어갔는지 건별로 알려준다").contains("이미 발송완료");
        // 안 바뀐 건에 쓰기를 시도하지 않는다
        verify(dsreGateway, never()).ship(eq(List.of(2)), any(), any());
    }

    @Test
    @DisplayName("★정본 표에 없는 전이는 거부 — 접수완료가 곧바로 발송완료가 되면 검수·준비가 무의미해진다")
    void 허용되지_않은_전이() {
        given(1, "A");

        var r = orderService.changeState(List.of(1), OrderState.SHIPPED, null);

        assertThat(r.changed()).isZero();
        assertThat(r.results().get(0).message()).contains("접수완료 → 발송완료", "허용되지 않");
        verify(dsreGateway, never()).changeState(anyInt(), anyString(), anyString());
        verify(dsreGateway, never()).ship(any(), any(), any());
    }

    @Test
    @DisplayName("되돌리기는 언제나 한 칸 — 발송완료에서 상품준비중으로 바로는 못 뛴다")
    void 발송완료에서_두칸은_못_뛴다() {
        given(1, "D");

        var r = orderService.changeState(List.of(1), OrderState.PREPARING, "실수로 발송처리");

        assertThat(r.changed()).isZero();
        verify(dsreGateway, never()).changeState(anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("★발송취소(D→W) — 9/27 회의로 열린 전이. 그 전엔 막혀 있었다")
    void 발송취소() {
        given(1, "D");
        when(dsreGateway.unship(eq(List.of(1)), any())).thenReturn(shipped(1, "D"));

        var r = orderService.changeState(List.of(1), OrderState.READY_TO_SHIP, "오발송");

        assertThat(r.changed()).as("실물이 나간 뒤의 되돌리기라 되되, 한 칸만").isEqualTo(1);
        // ★상태만 바꾸지 않고 자재 복원까지 하는 경로로 간다(작업요청서 발송취소와 같은 처리)
        verify(dsreGateway).unship(eq(List.of(1)), any());
        verify(dsreGateway, never()).changeState(anyInt(), anyString(), anyString());
        // 실물이 나간 뒤라 이력이 특히 중요하다 — DSRE2는 제자리 UPDATE라 우리가 안 남기면 흔적이 없다.
        verify(statusHistoryService).record(any(), anyLong(), anyString(),
                eq("D"), eq("W"), eq("오발송"));
    }

    @Test
    @DisplayName("★그 사이 남이 바꿨으면 성공으로 치지 않는다 — 같은 행을 두 시스템이 쓴다")
    void 동시변경() {
        given(1, "W");
        when(dsreGateway.ship(eq(List.of(1)), any(), any()))
                .thenReturn(conflict(1, "D"));   // 잠그고 보니 이미 남이 옮겨 놓았다

        var r = orderService.changeState(List.of(1), OrderState.SHIPPED, null);

        assertThat(r.changed()).as("0행이 바뀌었는데 성공이라 하면 담당자가 속는다").isZero();
        assertThat(r.results().get(0).message()).contains("다른 곳에서 상태가 바뀌었");
        verify(statusHistoryService, never())
                .record(any(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("우리가 만들 수 없는 상태는 요청 자체를 막는다 — 데이터 문제가 아니라 화면 오류다")
    void 우리_범위_밖_상태() {
        assertThatThrownBy(() -> orderService.changeState(List.of(1), OrderState.CANCELED, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("바꿀 수 없는 상태");

        assertThatThrownBy(() -> orderService.changeState(List.of(1), OrderState.INSPECTING, null))
                .isInstanceOf(BusinessException.class);

        verify(dsreGateway, never()).findOrder(anyInt());
    }

    @Test
    @DisplayName("DSRE2에 없는 건은 넘어간다(전체 실패 아님) · 같은 건을 두 번 체크해도 한 번만")
    void 없는_건과_중복() {
        when(dsreGateway.findOrder(9)).thenReturn(Optional.empty());

        var r = orderService.changeState(List.of(9, 9), OrderState.SHIPPED, null);

        assertThat(r.requested()).as("중복 제거").isEqualTo(1);
        assertThat(r.results().get(0).message()).contains("DSRE2에 해당 주문이 없습니다");
    }
}
