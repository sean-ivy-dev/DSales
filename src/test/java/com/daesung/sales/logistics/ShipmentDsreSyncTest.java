package com.daesung.sales.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daesung.sales.audit.entity.StatusEntityType;
import com.daesung.sales.audit.service.StatusHistoryService;
import com.daesung.sales.common.audit.CurrentAuditor;
import com.daesung.sales.dsre.gateway.DsreGateway;
import com.daesung.sales.dsre.gateway.DsreShipResult;
import com.daesung.sales.dsre.gateway.DsreShipResult.Outcome;
import com.daesung.sales.logistics.dto.ShippingUpdateRequest;
import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
import com.daesung.sales.logistics.service.ShipmentService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 작업요청서 발송완료·발송취소 → DSRE 진행상태 연동 규칙(2026-10-23).
 * 근거: 자료요청서 3-2(가) "발송완료 = 물류 담당자가 발송 처리한 시점".
 *
 * <p>DSRE 는 통합테스트에서 꺼져 있어(daesung.dsre.enabled=false) 규칙은 여기서 고정한다.
 * SQL 자체는 DSRE 복제본(MariaDB 10.2)에서 따로 확인했다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("작업요청서 발송 ↔ DSRE 진행상태")
class ShipmentDsreSyncTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 23);

    @Mock private ShipmentRepository shipmentRepository;
    @Mock private StatusHistoryService statusHistoryService;
    @Mock private CurrentAuditor currentAuditor;
    @Mock private ObjectProvider<DsreGateway> provider;
    @Mock private DsreGateway gw;

    private ShipmentService service;

    @BeforeEach
    void setUp() {
        when(provider.getIfAvailable()).thenReturn(gw);
        when(currentAuditor.username()).thenReturn("logi01");
        service = new ShipmentService(shipmentRepository, statusHistoryService, currentAuditor, provider);
    }

    private Shipment shipment(long id, LocalDate sent) {
        Shipment s = Shipment.of("M", DAY, null, "S1", "학교", null);
        ReflectionTestUtils.setField(s, "id", id);
        if (sent != null) {
            s.updateShipping(null, sent, null);
        }
        when(shipmentRepository.findByIdIncludingDeleted(id)).thenReturn(Optional.of(s));
        return s;
    }

    private static ShippingUpdateRequest sentOn(LocalDate d) {
        return new ShippingUpdateRequest(null, d, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("★발송일 입력 = 묶인 신청 발송완료(D) + 이력")
    void 발송완료() {
        Shipment s = shipment(10L, null);
        when(shipmentRepository.findReqCds(10L)).thenReturn(List.of(501));
        when(shipmentRepository.findByReqCd(501)).thenReturn(List.of(s));
        when(gw.ship(List.of(501), DAY, "logi01"))
                .thenReturn(List.of(new DsreShipResult(501, "W", Outcome.CHANGED, null)));

        var r = service.updateShipping(10L, sentOn(DAY));

        assertThat(r).singleElement().satisfies(x -> {
            assertThat(x.changed()).isTrue();
            assertThat(x.toName()).isEqualTo("발송완료");
        });
        assertThat(s.getSentDate()).isEqualTo(DAY);
        verify(statusHistoryService).record(eq(StatusEntityType.DSRE_ORDER), eq(501L), eq("STATE"),
                eq("W"), eq("D"), anyString());
    }

    @Test
    @DisplayName("★발송준비중이 아니면 건너뛰고 이유를 준다 — 우리 발송일은 저장된다")
    void 단계_건너뛰기_금지() {
        Shipment s = shipment(11L, null);
        when(shipmentRepository.findReqCds(11L)).thenReturn(List.of(502));
        when(shipmentRepository.findByReqCd(502)).thenReturn(List.of(s));
        when(gw.ship(List.of(502), DAY, "logi01"))
                .thenReturn(List.of(new DsreShipResult(502, "A", Outcome.SKIPPED, null)));

        var r = service.updateShipping(11L, sentOn(DAY));

        assertThat(r.get(0).changed()).isFalse();
        assertThat(r.get(0).message()).contains("접수완료", "발송준비중");
        assertThat(s.getSentDate()).isEqualTo(DAY);
        verify(statusHistoryService, never()).record(any(), anyLong(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★같은 신청의 다른 발송 건이 아직이면 DSRE 를 옮기지 않는다 — 상자 하나로 신청 전체가 발송완료면 안 된다")
    void 다른_발송건_대기() {
        Shipment s = shipment(12L, null);
        Shipment other = Shipment.of("H", DAY, null, "S1", "학교", null);
        ReflectionTestUtils.setField(other, "id", 99L);
        when(shipmentRepository.findReqCds(12L)).thenReturn(List.of(503));
        when(shipmentRepository.findByReqCd(503)).thenReturn(List.of(s, other));

        var r = service.updateShipping(12L, sentOn(DAY));

        assertThat(r.get(0).changed()).isFalse();
        assertThat(r.get(0).message()).contains("99", "발송 전");
        verify(gw, never()).ship(anyList(), any(), any());
    }

    @Test
    @DisplayName("수기 매출만 있는 발송 건(신청 없음)은 발송일만 저장한다")
    void 신청_없음() {
        Shipment s = shipment(13L, null);
        when(shipmentRepository.findReqCds(13L)).thenReturn(List.of());

        assertThat(service.updateShipping(13L, sentOn(DAY))).isEmpty();
        assertThat(s.getSentDate()).isEqualTo(DAY);
        verify(gw, never()).ship(anyList(), any(), any());
    }

    @Test
    @DisplayName("발송일 없이 박스 수만 고치면 DSRE 를 건드리지 않는다")
    void 발송일_없음() {
        shipment(14L, null);
        var req = new ShippingUpdateRequest(3, null, null, null, null, null, null, null);

        assertThat(service.updateShipping(14L, req)).isEmpty();
        verify(gw, never()).ship(anyList(), any(), any());
    }

    @Test
    @DisplayName("★DSRE 처리가 실패하면 예외 — 우리 발송일도 저장되지 않는다(트랜잭션 롤백)")
    void DSRE_실패() {
        Shipment s = shipment(15L, null);
        when(shipmentRepository.findReqCds(15L)).thenReturn(List.of(505));
        when(shipmentRepository.findByReqCd(505)).thenReturn(List.of(s));
        when(gw.ship(anyList(), any(), any())).thenThrow(new IllegalStateException("DSRE down"));

        assertThatThrownBy(() -> service.updateShipping(15L, sentOn(DAY)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("DSRE 연동이 꺼져 있으면 지금처럼 발송일만 다룬다")
    void DSRE_꺼짐() {
        when(provider.getIfAvailable()).thenReturn(null);
        Shipment s = shipment(16L, null);

        assertThat(service.updateShipping(16L, sentOn(DAY))).isEmpty();
        assertThat(s.getSentDate()).isEqualTo(DAY);
    }

    @Test
    @DisplayName("★발송취소 = 신청 발송준비중(W)으로 + 같은 신청의 다른 발송 건도 내림")
    void 발송취소() {
        Shipment s = shipment(20L, DAY);
        Shipment other = Shipment.of("H", DAY, null, "S1", "학교", null);
        ReflectionTestUtils.setField(other, "id", 21L);
        other.updateShipping(null, DAY, null);
        when(shipmentRepository.findReqCds(20L)).thenReturn(List.of(601));
        when(shipmentRepository.findByReqCd(601)).thenReturn(List.of(s, other));
        when(gw.unship(List.of(601), "logi01"))
                .thenReturn(List.of(new DsreShipResult(601, "D", Outcome.CHANGED, DAY)));

        var r = service.revertSent(20L, "오발송");

        assertThat(r.changed()).isTrue();
        assertThat(r.dsre()).singleElement().satisfies(x -> assertThat(x.toCode()).isEqualTo("W"));
        assertThat(s.getSentDate()).isNull();
        assertThat(other.getSentDate()).as("신청은 W 인데 다른 상자가 발송완료로 남으면 안 된다").isNull();
        verify(statusHistoryService).record(eq(StatusEntityType.DSRE_ORDER), eq(601L), eq("STATE"),
                eq("D"), eq("W"), anyString());
    }

    @Test
    @DisplayName("발송 전인 건의 발송취소는 DSRE 를 건드리지 않는다")
    void 발송전_취소() {
        shipment(22L, null);

        var r = service.revertSent(22L, null);

        assertThat(r.changed()).isFalse();
        verify(gw, never()).unship(anyList(), any());
    }
}
