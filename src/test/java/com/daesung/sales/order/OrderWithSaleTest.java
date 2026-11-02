package com.daesung.sales.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daesung.sales.audit.service.StatusHistoryService;
import com.daesung.sales.common.audit.CurrentAuditor;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.dsre.gateway.DsreGateway;
import com.daesung.sales.dsre.gateway.DsreOrderRow;
import com.daesung.sales.order.dto.OrderCreateRequest;
import com.daesung.sales.order.dto.OrderCreateResponse;
import com.daesung.sales.order.service.OrderService;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.sale.dto.SalesEntryRequest;
import com.daesung.sales.sale.dto.SalesEntryResponse;
import com.daesung.sales.sale.repository.SaleRepository;
import com.daesung.sales.sale.service.SaleService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 주문 신규등록에서 <b>매출까지 함께</b> 세우는 경로(9/27 회의 항목 2).
 *
 * <p>근거 원문: "신규등록에서 매출직접입력의 정가·공급률·수량 가능해야됨. <b>매출등록도 그대로 유지</b>."
 *
 * <p>★<b>여기서 가장 중요한 것은 실패 경로다.</b> DSRE2와 우리 DB는 데이터소스가 달라
 * <b>한 트랜잭션에 못 묶인다</b>. 매출이 실패했는데 주문만 남으면 담당자는 화면에서
 * "등록됐다"를 보고 다시 치지 않는다 — 그 주문은 매출 없이 물류로 흘러간다.
 * 그래서 매출이 실패하면 방금 만든 주문을 되돌린다.
 */
@DisplayName("주문 등록 + 매출 직접입력(항목 2)")
class OrderWithSaleTest {

    private DsreGateway gateway;
    private ProductRepository productRepository;
    private SaleService saleService;
    private SaleRepository saleRepository;
    private com.daesung.sales.logistics.service.ShipmentService shipmentService;
    private OrderService service;

    @BeforeEach
    void setUp() {
        gateway = mock(DsreGateway.class);
        productRepository = mock(ProductRepository.class);
        saleService = mock(SaleService.class);
        saleRepository = mock(SaleRepository.class);
        StatusHistoryService history = mock(StatusHistoryService.class);
        CurrentAuditor auditor = mock(CurrentAuditor.class);
        when(auditor.username()).thenReturn("tester");
        when(gateway.createOrder(any(), anyString())).thenReturn(777);
        when(saleRepository.findBySalesNo(anyString())).thenReturn(Optional.empty());

        shipmentService = mock(com.daesung.sales.logistics.service.ShipmentService.class);
        service = new OrderService(gateway, history, auditor, saleRepository,
                productRepository, saleService, shipmentService);
    }

    /** 간편신청 두 반 = 인원 30 + 20 → 총 50. */
    private OrderCreateRequest req(OrderCreateRequest.SaleLine sale) {
        return new OrderCreateRequest(90001, "70501", "10001", null, null, null,
                null, null, null, null, null, null, null, sale,
                List.of(new OrderCreateRequest.ClassLine("1반", "S", 30, null, null, null),
                        new OrderCreateRequest.ClassLine("2반", "S", null, 20, null, null)));
    }

    private void givenSaleOk() {
        when(saleService.createEntries(any())).thenReturn(new SalesEntryResponse(
                1L, "거래처", "E-20960501-1", 1,
                List.of(new SalesEntryResponse.Line("I-20960501-1", 42L, "BK",
                        com.daesung.sales.salestype.entity.ShipmentType.NORMAL_SHIP,
                        com.daesung.sales.salestype.entity.SalesCategory.SALE,
                        10, 84_000L, 0L, 84_000L, 0)),
                List.of()));
    }

    /** 담당자·택배까지 실은 주문. 그 값이 작업요청서로 내려가는지 보는 용도. */
    private OrderCreateRequest reqWithContact(OrderCreateRequest.SaleLine sale) {
        return new OrderCreateRequest(90001, "70501", "10001", null, null, null,
                "김선생", "010-1111-2222", "t@example.com", null, null, null, "T", sale,
                List.of(new OrderCreateRequest.ClassLine("1반", "S", 30, null, null, null)));
    }

    @Test
    @DisplayName("★택배 발송·담당자가 작업요청서로 내려간다 — 물류가 다시 치지 않도록(2026-10-08)")
    void 택배_담당자_자동입력() {
        givenSaleOk();
        when(productRepository.findByCode("90001")).thenReturn(Optional.of(mock(Product.class)));
        com.daesung.sales.sale.entity.Sale sale = mock(com.daesung.sales.sale.entity.Sale.class);
        when(saleRepository.findBySalesNo("I-20960501-1")).thenReturn(Optional.of(sale));

        service.create(reqWithContact(new OrderCreateRequest.SaleLine(
                5L, 42L, LocalDate.of(2096, 5, 1), 9L, 12_000, 70, 120, 0, null)));

        // 주문의 신청번호 연결과 함께, 배송구분·담당자가 그대로 발송 건으로 넘어간다.
        verify(sale).linkOrder(777);
        verify(shipmentService).autoFillDelivery(sale, "T", "김선생", "010-1111-2222");
    }

    @Test
    @DisplayName("매출을 안 보내면 주문만 등록된다 — 기존 동작 그대로")
    void 매출없이_주문만() {
        OrderCreateResponse r = service.create(req(null));

        assertThat(r.reqCd()).isEqualTo(777);
        assertThat(r.salesNo()).isNull();
        verify(saleService, never()).createEntries(any());
    }

    @Test
    @DisplayName("★매출을 함께 보내면 주문 + 매출이 선다")
    void 주문과_매출() {
        givenSaleOk();
        when(productRepository.findByCode("90001")).thenReturn(Optional.of(mock(Product.class)));

        OrderCreateResponse r = service.create(req(new OrderCreateRequest.SaleLine(
                5L, 42L, LocalDate.of(2096, 5, 1), 9L, 12_000, 70, 120, 0, "직접입력")));

        assertThat(r.salesNo()).isEqualTo("I-20960501-1");

        ArgumentCaptor<SalesEntryRequest> cap = ArgumentCaptor.forClass(SalesEntryRequest.class);
        verify(saleService).createEntries(cap.capture());
        SalesEntryRequest sent = cap.getValue();
        assertThat(sent.partnerId()).isEqualTo(5L);
        assertThat(sent.warehouseId()).isEqualTo(9L);
        assertThat(sent.items().get(0).productId()).isEqualTo(42L);
        assertThat(sent.items().get(0).unitPrice()).isEqualTo(12_000);
        assertThat(sent.items().get(0).supplyRate()).isEqualTo(70);
        assertThat(sent.items().get(0).qty()).isEqualTo(120);
    }

    @Test
    @DisplayName("★수량을 비우면 주문의 총 신청 수량을 쓴다 — 같은 숫자를 두 번 치게 하지 않는다")
    void 수량_기본값() {
        givenSaleOk();
        when(productRepository.findByCode("90001")).thenReturn(Optional.of(mock(Product.class)));

        service.create(req(new OrderCreateRequest.SaleLine(
                5L, 42L, null, null, 12_000, 70, null, null, null)));

        ArgumentCaptor<SalesEntryRequest> cap = ArgumentCaptor.forClass(SalesEntryRequest.class);
        verify(saleService).createEntries(cap.capture());
        assertThat(cap.getValue().items().get(0).qty()).as("30 + 20").isEqualTo(50);
    }

    @Test
    @DisplayName("상품을 비우면 시행코드와 같은 도서코드를 찾는다 — 매출일괄등록과 같은 매핑")
    void 상품_기본값() {
        givenSaleOk();
        Product p = mock(Product.class);
        when(p.getId()).thenReturn(4242L);
        when(productRepository.findByCode("90001")).thenReturn(Optional.of(p));

        service.create(req(new OrderCreateRequest.SaleLine(
                5L, null, null, null, 12_000, 70, 10, null, null)));

        ArgumentCaptor<SalesEntryRequest> cap = ArgumentCaptor.forClass(SalesEntryRequest.class);
        verify(saleService).createEntries(cap.capture());
        assertThat(cap.getValue().items().get(0).productId()).isEqualTo(4242L);
    }

    @Test
    @DisplayName("그 도서코드가 없으면 404 — 아무 상품에나 붙이면 어느 도서 매출인지 알 수 없다")
    void 상품_못찾으면_거부() {
        when(productRepository.findByCode("90001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(req(new OrderCreateRequest.SaleLine(
                5L, null, null, null, 12_000, 70, 10, null, null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("90001");
    }

    @Test
    @DisplayName("★★매출이 실패하면 방금 만든 주문을 되돌린다 — 매출 없는 주문이 물류로 흘러가면 안 된다")
    void 매출_실패시_주문_취소() {
        when(productRepository.findByCode("90001")).thenReturn(Optional.of(mock(Product.class)));
        when(saleService.createEntries(any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_INPUT, "공급률이 없습니다"));
        // 보상 삭제가 부르는 조회 — 접수완료(A) 상태여야 지워진다.
        when(gateway.findOrder(777)).thenReturn(Optional.of(mock(DsreOrderRow.class)));

        assertThatThrownBy(() -> service.create(req(new OrderCreateRequest.SaleLine(
                5L, 42L, null, null, null, null, 10, null, null))))
                .isInstanceOf(BusinessException.class)
                // ‼️원래 원인이 보여야 한다. 보상 삭제가 실패해도 그 오류로 덮이면 안 된다.
                .hasMessageContaining("공급률");

        verify(gateway).findOrder(777);
    }

    @Test
    @DisplayName("보상 삭제가 실패해도 원래 오류를 올린다 — 덮으면 원인을 못 찾는다")
    void 보상삭제_실패해도_원인유지() {
        when(productRepository.findByCode("90001")).thenReturn(Optional.of(mock(Product.class)));
        when(saleService.createEntries(any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_INPUT, "공급률이 없습니다"));
        when(gateway.findOrder(anyInt()))
                .thenThrow(new IllegalStateException("DSRE2 연결 끊김"));

        assertThatThrownBy(() -> service.create(req(new OrderCreateRequest.SaleLine(
                5L, 42L, null, null, null, null, 10, null, null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("공급률");
        verify(gateway, never()).changeState(eq(777), anyString(), anyString());
    }
}
