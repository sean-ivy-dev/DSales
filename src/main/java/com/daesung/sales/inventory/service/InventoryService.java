package com.daesung.sales.inventory.service;

import com.daesung.sales.audit.entity.StatusEntityType;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.sequence.SequenceService;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.query.MultiSelect;
import com.daesung.sales.inventory.dto.BomWorkRequest;
import com.daesung.sales.inventory.dto.BomWorkResponse;
import com.daesung.sales.inventory.dto.DisposalRequest;
import com.daesung.sales.inventory.dto.BomWorkStatusResponse;
import com.daesung.sales.inventory.dto.DisposalRecordRow;
import com.daesung.sales.inventory.dto.DisposalSummaryResponse;
import com.daesung.sales.inventory.dto.DisposalResponse;
import com.daesung.sales.inventory.dto.InboundRequest;
import com.daesung.sales.inventory.dto.InboundResponse;
import com.daesung.sales.inventory.entity.InboundType;
import com.daesung.sales.inventory.dto.StockLedgerRow;
import com.daesung.sales.inventory.dto.StockRecordRow;
import com.daesung.sales.inventory.dto.StockSettlementRow;
import com.daesung.sales.warehouse.entity.WarehouseType;
import com.daesung.sales.inventory.dto.TransferRequest;
import com.daesung.sales.inventory.dto.TransferResponse;
import com.daesung.sales.inventory.entity.BomDirection;
import com.daesung.sales.inventory.entity.Inventory;
import com.daesung.sales.inventory.entity.InventoryTxn;
import com.daesung.sales.inventory.entity.TxnType;
import com.daesung.sales.inventory.entity.VoucherCancel;
import com.daesung.sales.inventory.repository.InventoryRepository;
import com.daesung.sales.inventory.repository.InventoryTxnRepository;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.partner.repository.PartnerRepository;
import com.daesung.sales.product.entity.BomItem;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.BomItemRepository;
import com.daesung.sales.product.repository.ProductRepository;
import com.daesung.sales.salestype.entity.ShipmentType;
import com.daesung.sales.warehouse.entity.Warehouse;
import com.daesung.sales.warehouse.repository.WarehouseRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InventoryService.class);

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final InventoryRepository inventoryRepository;
    private final SequenceService sequenceService;
    private final InventoryTxnRepository inventoryTxnRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final PartnerRepository partnerRepository;
    private final BomItemRepository bomItemRepository;
    /** 자재 매칭 — 조립·해체가 깎는 축(F-37). 매칭이 있으면 자재, 없으면 상품 구성. */
    private final com.daesung.sales.material.repository.MaterialBomRepository materialBomRepository;
    private final com.daesung.sales.inventory.config.InventoryProperties inventoryProperties;
    private final StockWarningCollector stockWarningCollector;
    private final com.daesung.sales.inventory.repository.VoucherCancelRepository voucherCancelRepository;
    private final com.daesung.sales.closing.service.PeriodLockService periodLockService;
    private final com.daesung.sales.audit.service.StatusHistoryService statusHistoryService;

    /** 일반 입고. 품목마다 (1) 재고이벤트 INBOUND 기록 + (2) 재고 잔량 가산을 한 트랜잭션으로. */
    @Transactional
    public InboundResponse inbound(InboundRequest req) {
        // ★마감된 달이어도 **등록은 막지 않는다** — 경고만 싣는다.
        //   근거: 발주처 확정(2026-10-23) "마감된 달 처리 : 입고·대체·폐기 등록 차단 안 함, 안내 메시지만".
        //   ‼️2026-10-22에 "등록은 되는데 취소는 안 되는 비대칭이 나쁘다"는 판단으로 막았다가 되돌렸다.
        //     그건 회신 대기 중이던 항목이었고, 우리가 정할 일이 아니었다.
        //   재고 음수를 막지 않기로 한 것과 같은 결이다 — 막으면 처리가 안 되는 게 아니라
        //   **시스템 밖에서 처리된다**. 수정·취소·삭제는 그대로 막는다(되돌림은 마감 숫자를 흔든다).
        if (periodLockService.isLocked(req.processedDate())) {
            stockWarningCollector.add(com.daesung.sales.inventory.dto.StockWarning.periodLocked(req.processedDate()));
        }
        Warehouse warehouse = warehouseRepository.findById(req.destinationWarehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "창고가 없습니다. id=" + req.destinationWarehouseId()));
        // ★입고 거래처는 **선택**이다(현장 피드백 2026-10-14). 비우면 매입처 없이 등록된다.
        //   ‼️다만 **보낸 id가 없는 거래처면 거부**한다 — 조용히 null 로 떨어뜨리면
        //     담당자는 매입처를 골랐다고 믿는데 기록에는 빈칸이 남는다.
        Partner supplier = (req.supplierClientId() == null) ? null
                : partnerRepository.findById(req.supplierClientId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                                "거래처가 없습니다. id=" + req.supplierClientId()));

        // ★전표번호를 붙인다. 예전엔 입고에만 없어 "무엇을 되돌릴지" 특정할 수 없었다(취소 신설).
        String inboundNo = "IN-" + req.processedDate().format(YYYYMMDD) + "-"
                + sequenceService.next(SequenceService.SEQ_INBOUND);

        // ★매입반품은 **부호가 반대**다 — 사 온 물건을 공급처로 되돌려 보내므로 재고가 준다.
        //   화면은 다른 입고와 같이 양수로 입력하고 여기서 뒤집는다(폐기와 같은 규칙).
        //   근거: 발주처 1차회신 2026-10-02 #6 "매입반품 실제 발생".
        int sign = (req.inboundType() == InboundType.PURCHASE_RETURN) ? -1 : 1;

        List<InboundResponse.Line> lines = new ArrayList<>();
        for (InboundRequest.InboundItem item : req.items()) {
            Product product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "상품이 없습니다. id=" + item.productId()));

            int delta = sign * item.qty();
            InventoryTxn txn = InventoryTxn.inbound(product, warehouse, delta,
                    item.unitCost(), req.inboundType(), req.processedDate(), supplier, item.memo(), inboundNo);
            txn.applyLogisCostTarget(Boolean.TRUE.equals(req.logisCostTarget()));   // 물류작업비 대상(8p)
            inventoryTxnRepository.save(txn);
            int currentQty = applyDelta(product, warehouse, delta);

            // 응답 수량은 **입력한 그대로**(양수) 돌려준다 — 화면이 되돌려 보여주는 값이라
            // 여기서 음수로 바뀌면 담당자는 자기가 친 숫자와 다른 것을 본다.
            lines.add(new InboundResponse.Line(
                    product.getId(), product.getCode(), item.qty(), currentQty));
        }
        InboundType type = (req.inboundType() != null) ? req.inboundType() : InboundType.NORMAL;
        return new InboundResponse(inboundNo, warehouse.getId(), warehouse.getName(), type, lines,
                stockWarningCollector.drain());
    }

    /** 단순 이고(창고 이동). 출발창고 −qty(음수재고 방지), 도착창고 +qty. 매출 미발생. */
    @Transactional
    public TransferResponse transfer(TransferRequest req) {
        // ★마감된 달이어도 **등록은 막지 않는다** — 경고만 싣는다.
        //   근거: 발주처 확정(2026-10-23) "마감된 달 처리 : 입고·대체·폐기 등록 차단 안 함, 안내 메시지만".
        //   ‼️2026-10-22에 "등록은 되는데 취소는 안 되는 비대칭이 나쁘다"는 판단으로 막았다가 되돌렸다.
        //     그건 회신 대기 중이던 항목이었고, 우리가 정할 일이 아니었다.
        //   재고 음수를 막지 않기로 한 것과 같은 결이다 — 막으면 처리가 안 되는 게 아니라
        //   **시스템 밖에서 처리된다**. 수정·취소·삭제는 그대로 막는다(되돌림은 마감 숫자를 흔든다).
        if (periodLockService.isLocked(req.processedDate())) {
            stockWarningCollector.add(com.daesung.sales.inventory.dto.StockWarning.periodLocked(req.processedDate()));
        }
        if (req.fromWarehouseId().equals(req.toWarehouseId())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "출발/도착 창고가 같습니다.");
        }
        Warehouse from = warehouseRepository.findById(req.fromWarehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "출발 창고가 없습니다. id=" + req.fromWarehouseId()));
        Warehouse to = warehouseRepository.findById(req.toWarehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "도착 창고가 없습니다. id=" + req.toWarehouseId()));

        // ★전표번호(TR-)를 붙인다. 없으면 이고를 되돌릴 방법이 없다 —
        //   취소·삭제가 전부 전표번호로 대상을 찾는다(예전엔 NULL 이라 가리킬 수가 없었다).
        String transferNo = "TR-" + req.processedDate().format(YYYYMMDD) + "-"
                + sequenceService.next(SequenceService.SEQ_TRANSFER);

        List<TransferResponse.Line> lines = new ArrayList<>();
        for (TransferRequest.Item item : req.items()) {
            Product product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "상품이 없습니다. id=" + item.productId()));
            moveStock(product, from, to, item.qty(), req.processedDate(), transferNo, item.reason());
            lines.add(new TransferResponse.Line(product.getId(), product.getCode(), item.qty(),
                    balanceOf(product.getId(), from.getId()), balanceOf(product.getId(), to.getId())));
        }
        return new TransferResponse(transferNo, from.getId(), from.getName(),
                to.getId(), to.getName(), lines, stockWarningCollector.drain());
    }

    /**
     * 재고 이동 1건(출발 −qty 음수방지, 도착 +qty) + 재고이벤트 2다리. 이고·위탁출고 공용 빌딩블록.
     * 반환값 = 출발다리 이벤트(위탁출고의 origin_txn 링크에 사용). 반드시 호출자 트랜잭션 내에서.
     */
    public InventoryTxn moveStock(Product product, Warehouse from, Warehouse to, int qty,
                                  LocalDate tradeDate, String reason) {
        return moveStock(product, from, to, qty, tradeDate, null, reason);
    }

    /**
     * 전표번호를 붙여 이동한다. {@code refNo}가 있어야 나중에 <b>되돌릴 수 있다</b> —
     * 취소·삭제는 전부 refNo 로 대상을 찾는다.
     *
     * <p>위탁 자동이고는 원본 출고번호(OUT-)를 그대로 넘겨 매출 쪽과 한 전표로 묶인다.
     */
    public InventoryTxn moveStock(Product product, Warehouse from, Warehouse to, int qty,
                                  LocalDate tradeDate, String refNo, String reason) {
        applyDelta(product, from, -qty);
        InventoryTxn outLeg = inventoryTxnRepository.save(
                InventoryTxn.transfer(product, from, -qty, tradeDate, null, refNo, reason));
        applyDelta(product, to, qty);
        inventoryTxnRepository.save(
                InventoryTxn.transfer(product, to, qty, tradeDate, outLeg, refNo, reason));
        return outLeg;
    }

    /**
     * 출고/반품 재고 반영 + 이벤트 1건. 정상출고=OUTBOUND(delta 음수, 음수재고 방지), 반품=RETURN(delta 양수).
     * shipmentType을 이벤트에 태그해 수불부가 매출/무상/교사용/반품으로 분해. 반환값 = 갱신 후 잔량.
     * 반드시 호출자 트랜잭션 내에서(매출등록과 한 트랜잭션).
     */
    public int applyShipment(Product product, Warehouse warehouse, int delta, TxnType txnType,
                             ShipmentType shipmentType, LocalDate tradeDate, String refNo, String memo) {
        // ★★회차 단독 출고는 **그 회차 자재**를 깎는다(F-37 ⑤, 발주처 확정 2026-11-09).
        //   원문: "SET 출고 → SET 재고 차감, **회차 단독 출고 → 해당 회차 자재 차감**."
        //   회차 도서는 쌓아 두는 물건이 아니라 자재에서 산출되는 것이라, 상품 재고를 깎으면
        //   실물(자재 낱장)은 그대로인데 장부만 줄어든다.
        //
        //   ‼️여기 한 곳에 두는 이유: 출고 경로가 매출등록·엑셀업로드·일괄등록·반품으로 여럿인데
        //   전부 이 메서드를 지난다. 경로마다 넣으면 한 곳을 빠뜨린다.
        //   ‼️**상품 처리는 그대로 두고 자재를 더 깎는다**(둘 다 한다).
        //     처음엔 상품 쪽을 건너뛰게 만들었는데 기존 테스트 6건이 깨졌다 —
        //     수불부 자재 상세·물류작업비가 **회차 단독 출고량을 상품 txn**
        //     (`consumedQtyByProduct`)으로 세기 때문에 상품 이벤트가 사라지면 그 칸이 0이 된다.
        //     발주처 ⑤도 "물류 작업비·수불부 소요량은 **기존 기준(출고수량 × 소요수량) 유지**"다.
        //     발주처가 요구한 것은 "자재가 차감되는 것"이고, 상품 재고를 깎지 말라고 한 적은 없다
        //     ("회차 단독 재고는 자재 낱장 잔여에서 **산출해 표시**"는 표시 방법에 대한 말이다).
        //   ⚠️알려진 부작용: 회차 상품은 입고가 없으니 그 재고가 음수로 쌓인다. 화면은 자재
        //     잔여를 보므로 쓰이지 않는 숫자이고, 필요하면 '수불부노출'을 끄면 집계에서 빠진다.
        applyRoundMaterials(product, warehouse, delta, txnType, tradeDate, refNo, memo);
        int balance = applyDelta(product, warehouse, delta);
        inventoryTxnRepository.save(
                InventoryTxn.shipment(product, warehouse, delta, txnType, shipmentType, tradeDate, refNo, memo));
        return balance;
    }

    /**
     * 출고 상품이 <b>다른 세트의 회차</b>면 그 회차 자재를 증감한다.
     *
     * <p>회차당 소요는 {@link com.daesung.sales.material.entity.MaterialBom#qtyPerRound(int)}가
     * 정한다 — <b>규칙을 다시 짜지 않는다</b>(수불부·물류작업비가 쓰는 그 규칙이다).
     * <pre>
     *   회차 전용(시험지·해설지) → 세트당수량 그대로
     *   공통 반복형(OMR)         → 세트당수량 ÷ 회차수   (세트당 6, 6회 → 회차 1부당 1)
     *   공통 1회형(쿠폰)         → 0 — 세트를 사야 붙는다
     * </pre>
     *
     * <p>★<b>상품 재고와 함께 쓴다</b>(대체가 아니다). 상품 이벤트를 없애면 수불부 자재
     * 상세·물류작업비의 '회차 단독' 칸이 0이 된다 — 그쪽은 상품 txn 으로 센다.
     *
     * @return 움직인 자재 수(회차 도서가 아니면 0)
     */
    private int applyRoundMaterials(Product product, Warehouse warehouse, int delta,
                                        TxnType txnType, LocalDate tradeDate,
                                        String refNo, String memo) {
        List<com.daesung.sales.material.entity.MaterialBom> asRound =
                materialBomRepository.findByRoundProductId(product.getId());
        if (asRound.isEmpty()) {
            return 0;   // 회차 도서가 아니다 — 자재를 깎을 일이 없다
        }
        // 소속 세트별로 처리한다. 같은 회차 도서가 여러 세트에 들어가 있을 수 있다.
        java.util.Set<Long> setIds = new java.util.LinkedHashSet<>();
        asRound.forEach(b -> setIds.add(b.getSetProduct().getId()));

        int moved = 0;
        for (Long setId : setIds) {
            List<com.daesung.sales.material.entity.MaterialBom> all =
                    materialBomRepository.findBySet(setId);
            int roundCount = (int) all.stream()
                    .filter(b -> !b.isCommon())
                    .map(b -> b.getRoundProduct().getId())
                    .distinct().count();
            for (com.daesung.sales.material.entity.MaterialBom b : all) {
                // 이 회차 전용 자재 + 공통 자재만. 다른 회차 전용 자재는 건드리지 않는다.
                boolean mine = b.isCommon()
                        || product.getId().equals(b.getRoundProduct().getId());
                if (!mine) {
                    continue;
                }
                long per = b.qtyPerRound(roundCount);
                if (per == 0) {
                    continue;   // 1회형 공통(쿠폰) — 회차만 사면 나가지 않는다
                }
                int d = (int) (delta * per);
                applyMaterialDelta(b.getMaterial(), warehouse, d);
                moved++;
                inventoryTxnRepository.save(
                        com.daesung.sales.inventory.entity.InventoryTxn.materialTxn(
                                b.getMaterial(), warehouse, txnType, d, tradeDate, refNo,
                                "회차 단독 " + (b.isCommon() ? "(공통) " : "")
                                        + "소요 " + per + "개/부"
                                        + ((memo == null || memo.isBlank()) ? "" : " / " + memo)));
            }
        }
        return moved;
    }

    /**
     * 매출 수정으로 바뀐 수량만큼 재고를 보정한다(2026-10-08 [A]2).
     *
     * <p>★<b>원 이벤트를 고치지 않는다.</b> {@code inventory_txn} 은 재고의 유일 진실인
     * <b>이벤트 로그</b>다 — 원 행의 수량을 덮으면 "그날 실제로 몇 개 나갔나"가 사라지고,
     * 과거 수불부를 다시 뽑았을 때 당시와 다른 숫자가 나온다.
     * 대신 <b>차액 이벤트</b>를 같은 매출번호로 남긴다. 합계는 수정 후 수량과 같아진다.
     *
     * <p>★<b>버킷(txnType·shipmentType)과 거래일자는 원 이벤트를 그대로 따른다.</b>
     * 차액이 양수라고 반품 버킷에 넣으면 수불부의 반품 칸이 부풀고 매출 칸이 줄지 않는다
     * (매출취소 역분개가 같은 규칙을 쓴다). 날짜도 원 거래일이어야 그 달 수불부가 맞다.
     *
     * @param stockDelta 재고 증감(= 새 부호수량 − 원 부호수량)
     * @return 보정 후 잔량. 재고를 쓰지 않는 매출(미출고·재고미관리)이면 0
     */
    public int adjustShipment(String refNo, int stockDelta, String memo) {
        if (stockDelta == 0) {
            return 0;
        }
        List<InventoryTxn> origins = inventoryTxnRepository.findShipmentsByRefNo(refNo);
        if (origins.isEmpty()) {
            return 0;   // 창고 없는 매출이거나 재고미관리 상품 — 보정할 재고가 애초에 없다
        }
        InventoryTxn o = origins.get(0);
        int balance = applyDelta(o.getProduct(), o.getWarehouse(), stockDelta);
        inventoryTxnRepository.save(InventoryTxn.shipment(
                o.getProduct(), o.getWarehouse(), stockDelta,
                o.getTxnType(), o.getShipmentType(), o.getTradeDate(), refNo, memo));
        return balance;
    }

    /**
     * 매출취소 역분개. refNo(매출번호)로 생성된 출고/반품 이벤트를 찾아, 같은 버킷에 반대 부호로 되돌린다.
     * 재고 복구(반대 delta) + 상쇄 이벤트 생성 → 수불부 버킷도 상쇄(net 0). 원출고 없으면(위탁정산 등) no-op.
     * 반드시 호출자 트랜잭션 내에서. 반품 취소로 차감 시 음수재고면 NEGATIVE_STOCK.
     */
    public void reverseShipments(String refNo, LocalDate reverseDate) {
        for (InventoryTxn origin : inventoryTxnRepository.findShipmentsByRefNo(refNo)) {
            int reverseDelta = -origin.getQty();
            applyDelta(origin.getProduct(), origin.getWarehouse(), reverseDelta);
            inventoryTxnRepository.save(InventoryTxn.shipment(
                    origin.getProduct(), origin.getWarehouse(), reverseDelta,
                    origin.getTxnType(), origin.getShipmentType(), reverseDate, refNo,
                    "매출취소 역분개: " + refNo));
        }
    }

    /**
     * 폐기·입고 전표 취소. 물리 삭제가 아니라 <b>역분개 + 취소 이력</b>이다.
     *
     * <p>근거: 발주처 회신 「삭제권한」 — "마감 확정 전에는 잘못 등록한 건을 삭제할 수 있어야.
     * 확정 후에는 물리 삭제 없이 취소 처리로". 재고는 이벤트 로그가 유일 진실이라
     * 확정 전후와 무관하게 <b>지우지 않고 되돌린다</b> — 지우면 이력이 사라진다.
     *
     * <p>‼️<b>마감된 달은 막는다.</b> 되돌리면 그 달 재고·수불부가 바뀌는데,
     * 마감은 "이 달 숫자를 더 안 건드린다"는 선언이다.
     *
     * @param refNo 폐기 {@code P-…} / 입고 {@code IN-…}
     */
    @Transactional
    public com.daesung.sales.inventory.dto.VoucherCancelResponse cancelVoucher(String refNo, String reason) {
        if (voucherCancelRepository.existsByRefNo(refNo)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 취소된 전표입니다: " + refNo);
        }
        List<InventoryTxn> origins = inventoryTxnRepository.findAllByRefNo(refNo);
        if (origins.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "전표가 없습니다: " + refNo);
        }
        // 원 전표의 거래일 기준으로 마감을 본다 — 되돌리는 대상이 그 달의 숫자다.
        periodLockService.assertNotLocked(origins.get(0).getTradeDate());

        String kind = voucherKind(origins.get(0).getTxnType());
        int n = reverseByRefNo(refNo, LocalDate.now(),
                voucherKindLabel(kind) + " 취소 역분개: " + refNo);
        voucherCancelRepository.save(VoucherCancel.of(refNo, kind, reason, n));
        return new com.daesung.sales.inventory.dto.VoucherCancelResponse(
                refNo, kind, n, stockWarningCollector.drain());
    }

    /**
     * 전표(refNo)의 재고 이벤트를 <b>통째로</b> 되돌린다. 폐기·입고 취소용.
     *
     * <p>★<b>물리 삭제하지 않는다.</b> 반대 부호 이벤트를 새로 적어 상쇄한다 —
     * 재고는 이벤트 로그가 유일 진실이라, 지우면 "언제 왜 되돌렸나"가 사라진다.
     * 매출취소({@link #reverseShipments})와 같은 규율이다.
     *
     * <p>‼️이미 되돌린 전표를 또 되돌리면 재고가 반대로 밀린다. 중복 취소는 호출부가 막는다
     * (폐기·입고 엔티티의 canceled 플래그).
     *
     * @return 되돌린 이벤트 수. 0이면 그 전표로 만들어진 재고 이벤트가 없다는 뜻이다
     *         (재고 미관리 상품만 있던 전표 등) — 오류가 아니다.
     */
    @Transactional
    public int reverseByRefNo(String refNo, LocalDate reverseDate, String memo) {
        int n = 0;
        for (InventoryTxn origin : inventoryTxnRepository.findAllByRefNo(refNo)) {
            int reverseDelta = -origin.getQty();
            applyTxnDelta(origin, reverseDelta);
            inventoryTxnRepository.save(
                    InventoryTxn.reverseOf(origin, reverseDelta, reverseDate, refNo, memo));
            n++;
        }
        return n;
    }

    /** 이벤트의 축(도서/자재)을 보고 알맞은 잔량에 delta 를 민다. 취소·삭제가 공통으로 쓴다. */
    private void applyTxnDelta(InventoryTxn origin, int delta) {
        if (origin.getMaterial() != null) {
            applyMaterialDelta(origin.getMaterial(), origin.getWarehouse(), delta);
        } else {
            applyDelta(origin.getProduct(), origin.getWarehouse(), delta);
        }
    }

    /**
     * 자재 잔량 증감(V78). 도서와 같은 이유로 <b>원자적 UPDATE</b>만 쓴다 —
     * 읽고 더해 저장하면 동시 입고에서 한쪽이 유실된다.
     *
     * <p>음수는 막지 않는다(도서와 동일 — 발주처 지시로 차단 규칙은 V56에서 걷어냈다).
     *
     * @return 처리 후 잔량
     */
    public int applyMaterialDelta(com.daesung.sales.material.entity.Material material,
                                  Warehouse warehouse, int delta) {
        int updated = inventoryRepository.addMaterialQty(material.getId(), warehouse.getId(), delta);
        if (updated == 0) {
            inventoryRepository.save(Inventory.createMaterial(material, warehouse, delta));
            return delta;
        }
        return inventoryRepository.findByMaterialIdAndWarehouseId(material.getId(), warehouse.getId())
                .map(Inventory::getQty)
                .orElse(delta);
    }

    /** 이벤트가 가리키는 품목 코드(도서코드 또는 자재코드). 이력 문구용. */
    private static String itemCode(InventoryTxn t) {
        return (t.getMaterial() != null) ? t.getMaterial().getCode() : t.getProduct().getCode();
    }

    /**
     * 재고실사 조정. delta=실물−시스템(부호 포함). 캐시 갱신 + ADJUST 이벤트. 반환값=조정 후 잔량(=실물수량).
     * 반드시 호출자 트랜잭션 내에서(실사 등록과 한 트랜잭션).
     */
    public int adjust(Product product, Warehouse warehouse, int delta,
                      LocalDate tradeDate, String refNo, String memo) {
        int balance = applyDelta(product, warehouse, delta);
        inventoryTxnRepository.save(InventoryTxn.adjust(product, warehouse, delta, tradeDate, refNo, memo));
        return balance;
    }

    /** 상품×창고 현재 잔량(캐시). 없으면 0. */
    public int balanceOf(Long productId, Long warehouseId) {
        return inventoryRepository.findByProductIdAndWarehouseId(productId, warehouseId)
                .map(Inventory::getQty)
                .orElse(0);
    }

    /**
     * BOM 조립/해체. 구성품·비율은 상품 BOM 마스터에서 읽는다.
     * 조립: 완제품 +workQty / 구성품 각 −(비율×workQty). 해체: 반대. 전체 한 트랜잭션.
     */
    @Transactional
    public BomWorkResponse bom(BomWorkRequest req) {
        Warehouse warehouse = warehouseRepository.findById(req.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "창고가 없습니다. id=" + req.warehouseId()));
        Product parent = productRepository.findById(req.parentProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "완제품 상품이 없습니다. id=" + req.parentProductId()));
        // ★★어느 축을 깎을지 — **자재 매칭이 있으면 자재**다(F-37 발주처 확정 2026-11-09).
        //
        //   원문: "재고는 **자재 낱장이 기준 원장**. SET 재고는 세트 조립·해체로 증감.
        //         회차 단독 재고는 해당 회차 자재 낱장 잔여에서 산출해 표시."
        //
        //   예전에는 `bom_items`(상품↔상품)만 깎았다 — 세트+N / 구성 **회차 상품**−(비율×N).
        //   그러면 자재 낱장은 조립해도 줄지 않아 실물과 어긋난다.
        //   ‼️전환이 안전한 이유: 운영 조립·해체 이력이 **0건**이었다(2026-11-09 실측).
        //     바뀔 과거 숫자가 없다. 출고가 시작된 뒤에는 이렇게 못 바꾼다.
        //
        //   자재 매칭이 없는 세트는 **기존 상품 축 그대로** 둔다. 둘 중 하나만 고르는 것이
        //   아니라 "자재 매칭이 곧 자재 축 선언"이다 — 매칭을 넣는 순간 그 세트가 넘어간다.
        List<com.daesung.sales.material.entity.MaterialBom> matBoms =
                materialBomRepository.findBySet(parent.getId());
        List<BomItem> boms = matBoms.isEmpty()
                ? bomItemRepository.findByParentId(parent.getId()) : List.of();
        if (matBoms.isEmpty() && boms.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "BOM 구성이 없습니다: " + parent.getCode()
                            + " (상품 구성도 자재 매칭도 없습니다)");
        }

        boolean assemble = req.direction() == BomDirection.ASSEMBLE;
        TxnType txnType = assemble ? TxnType.BOM_ASSEMBLE : TxnType.BOM_DISASSEMBLE;

        // ⚠️조립 시점 작업비 계산은 없다(발주처 회신 2026-08-21 철회) —
        //   "조립·해체 시점에는 별도 정산하지 않고, 실제 출고되는 수량만큼만
        //    출고 시점에 출고 작업비에 포함해 청구하는 현행 방식으로 운영".

        // 완제품: 조립 +, 해체 −
        int parentDelta = assemble ? req.workQty() : -req.workQty();
        int parentBal = applyDelta(parent, warehouse, parentDelta);
        // ★전표번호를 붙인다(BW-). 없으면 이 작업을 되돌릴 방법이 없다 — 취소·삭제가 refNo 로 찾는다.
        String bomNo = "BW-" + req.processedDate().format(YYYYMMDD) + "-"
                + sequenceService.next(SequenceService.SEQ_BOMWORK);
        InventoryTxn parentTxn = InventoryTxn.bom(
                parent, warehouse, parentDelta, txnType, req.processedDate(), bomNo, req.memo());
        inventoryTxnRepository.save(parentTxn);
        // 완제품 행: 비율·자재구분은 구성품에만 있는 값이라 비운다.
        BomWorkResponse.Line parentLine = new BomWorkResponse.Line(
                parent.getId(), null, parent.getCode(), parent.getName(),
                null, null, parentDelta, parentBal);

        // 구성: 조립 −(소요×수량), 해체 +(소요×수량)
        List<BomWorkResponse.Line> compLines = new ArrayList<>();

        // ① 자재 축 — 세트당 소요수량 × 작업수량.
        //   ‼️공통 자재도 같은 식이다. '세트당' 소요라 회차 수가 이미 반영돼 있다
        //   (국어 OMR 소요 6 = 6회분) — 발주처 32장 "SET 800 조립 시 OMR 4,800장"과 같은 값.
        //   perRound 는 **회차 단독 출고**에서 쓰는 구분이지 조립 계산에는 들어가지 않는다.
        for (com.daesung.sales.material.entity.MaterialBom mb : matBoms) {
            com.daesung.sales.material.entity.Material material = mb.getMaterial();
            int delta = (assemble ? -1 : 1) * mb.getQtyPerSet() * req.workQty();
            int bal = applyMaterialDelta(material, warehouse, delta);
            // ★같은 전표번호(BW-)로 묶는다 — 되돌리기가 refNo 로 찾고,
            //   조립 시점 소요량도 이 행들로 되짚는다(발주처 요구 "그 시점 소요량을 이력에 남길 것").
            //   메모에 소요수량을 적어 둔다: 뒤에 매칭이 바뀌어도 당시 값이 남는다.
            inventoryTxnRepository.save(com.daesung.sales.inventory.entity.InventoryTxn.materialTxn(
                    material, warehouse, txnType, delta, req.processedDate(), bomNo,
                    "세트당 " + mb.getQtyPerSet() + "개"
                            + (mb.isCommon() ? " (공통)" : " (" + mb.getRoundProduct().getName() + ")")
                            + ((req.memo() == null || req.memo().isBlank()) ? "" : " / " + req.memo())));
            compLines.add(new BomWorkResponse.Line(null, material.getId(),
                    material.getCode(), material.getName(),
                    mb.getQtyPerSet(),
                    (material.getMaterialType() == null) ? null : material.getMaterialType().name(),
                    delta, bal));
        }

        // ② 상품 축(자재 매칭이 없는 세트) — 기존 동작 그대로.
        for (BomItem b : boms) {
            Product child = b.getChild();
            int compDelta = (assemble ? -1 : 1) * b.getRatio() * req.workQty();
            int compBal = applyDelta(child, warehouse, compDelta);
            inventoryTxnRepository.save(InventoryTxn.bom(
                    child, warehouse, compDelta, txnType, req.processedDate(), bomNo, req.memo()));
            compLines.add(new BomWorkResponse.Line(child.getId(), null,
                    child.getCode(), child.getName(),
                    b.getRatio(),
                    (b.getMaterialType() == null) ? null : b.getMaterialType().name(),
                    compDelta, compBal));
        }

        return new BomWorkResponse(bomNo, warehouse.getId(), warehouse.getName(), req.direction(),
                parentLine, req.workQty(), compLines, stockWarningCollector.drain());
    }


    /** 폐기. 품목마다 재고 즉시 차감(음수재고 방지) + DISPOSE 이벤트. 폐기번호(P) 채번. 한 트랜잭션. */
    @Transactional
    public DisposalResponse dispose(DisposalRequest req) {
        // ★마감된 달이어도 **등록은 막지 않는다** — 경고만 싣는다.
        //   근거: 발주처 확정(2026-10-23) "마감된 달 처리 : 입고·대체·폐기 등록 차단 안 함, 안내 메시지만".
        //   ‼️2026-10-22에 "등록은 되는데 취소는 안 되는 비대칭이 나쁘다"는 판단으로 막았다가 되돌렸다.
        //     그건 회신 대기 중이던 항목이었고, 우리가 정할 일이 아니었다.
        //   재고 음수를 막지 않기로 한 것과 같은 결이다 — 막으면 처리가 안 되는 게 아니라
        //   **시스템 밖에서 처리된다**. 수정·취소·삭제는 그대로 막는다(되돌림은 마감 숫자를 흔든다).
        if (periodLockService.isLocked(req.processedDate())) {
            stockWarningCollector.add(com.daesung.sales.inventory.dto.StockWarning.periodLocked(req.processedDate()));
        }
        Warehouse warehouse = warehouseRepository.findById(req.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "창고가 없습니다. id=" + req.warehouseId()));
        String disposalNo = "P-" + req.processedDate().format(YYYYMMDD) + "-"
                + sequenceService.next(SequenceService.SEQ_PURGE);

        List<DisposalResponse.Line> lines = new ArrayList<>();
        for (DisposalRequest.Item item : req.items()) {
            Product product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "상품이 없습니다. id=" + item.productId()));
            int balance = applyDelta(product, warehouse, -item.qty());
            inventoryTxnRepository.save(InventoryTxn.dispose(product, warehouse, -item.qty(),
                    // 화면 라벨이 '비고'라 reason/memo 어느 쪽으로 와도 같은 칸이다(note()).
                    req.processedDate(), disposalNo, item.note()));
            lines.add(new DisposalResponse.Line(product.getId(), product.getCode(), item.qty(), balance));
        }
        return new DisposalResponse(disposalNo, warehouse.getId(), warehouse.getName(), lines,
                stockWarningCollector.drain());
    }

    /**
     * 제품수불부. inventory_txn을 이월/입고/이고/BOM/폐기/출고 버킷으로 집계 → 현재재고(단일 공식).
     * closing(이벤트 합계)과 cachedBalance(inventory.qty)를 대사(reconciled)로 검증.
     */
    @Transactional(readOnly = true)
    public List<StockLedgerRow> stockLedger(LocalDate fromDate, LocalDate toDate,
                                            Long productId, Long warehouseId,
                                            WarehouseType warehouseType) {
        return stockLedger(fromDate, toDate, productId, warehouseId, warehouseType, null);
    }

    /**
     * 제품수불부. {@code keyword}는 도서코드·도서명·창고명을 함께 훑는다(부분일치).
     *
     * <p>★키워드는 <b>메모리에서</b> 거른다. 집계 쿼리가 상품×창고로 묶은 뒤에 걸러야
     * 이월·마감이 정확하다 — SQL WHERE에 넣으면 걸러진 행만으로 이월을 다시 계산하게 된다.
     * 수불부는 화면 한 장 분량(수천 행)이라 메모리에서 걸러도 부담이 없다.
     */
    @Transactional(readOnly = true)
    public List<StockLedgerRow> stockLedger(LocalDate fromDate, LocalDate toDate,
                                            Long productId, Long warehouseId,
                                            WarehouseType warehouseType, String keyword) {
        return stockLedger(fromDate, toDate, productId, warehouseId, warehouseType, keyword, null);
    }

    /** 정렬까지 받는 수불부. {@code sort}는 `필드,방향`(예: `closing,desc`). */
    @Transactional(readOnly = true)
    public List<StockLedgerRow> stockLedger(LocalDate fromDate, LocalDate toDate,
                                            Long productId, Long warehouseId,
                                            WarehouseType warehouseType, String keyword,
                                            String sort) {
        return stockLedger(fromDate, toDate, productId, warehouseId, warehouseType, keyword, sort, null);
    }

    /**
     * 분류 조건까지 받는 수불부(2026-10-08).
     *
     * <p>★분류는 <b>SQL에서</b> 거른다. 키워드와 달리 이월 계산에 영향을 주지 않기 때문이다 —
     * 분류는 상품의 속성이라 어떤 분류를 빼도 남은 상품의 이월은 그대로다.
     * (키워드는 메모리에서 거른다. 그 이유는 위 주석 참고.)
     */
    @Transactional(readOnly = true)
    public List<StockLedgerRow> stockLedger(LocalDate fromDate, LocalDate toDate,
                                            Long productId, Long warehouseId,
                                            WarehouseType warehouseType, String keyword,
                                            String sort, List<String> catCodes) {
        LocalDate from = (fromDate != null) ? fromDate : LocalDate.now().withDayOfYear(1);
        LocalDate to = (toDate != null) ? toDate : LocalDate.now();

        List<StockLedgerRow> result = new ArrayList<>();
        // 창고구분은 enum 이름 그대로 저장돼 있다(MAIN/CONSIGN). null이면 전체.
        String whType = (warehouseType == null) ? null : warehouseType.name();
        for (Object[] r : inventoryTxnRepository.stockLedger(from, to, productId, warehouseId, whType,
                MultiSelect.isAny(catCodes), MultiSelect.orPlaceholder(catCodes, "\u0000"))) {
            long closing = num(r[15]);
            long cached = num(r[16]);
            long sale = num(r[10]);          // 음수(재고를 깎는다)
            long salesReturn = num(r[13]);   // 양수(되돌아온다)
            // 순매출수량 = 매출 − 반품. 원장은 매출을 음수로 적으므로 부호를 뒤집어 뺀다.
            // ‼️교사용·증정은 무가라 순매출에 안 들어간다 — 순매출조회(SaleRepository)와 같은 정의다.
            long netSaleQty = (-sale) - salesReturn;
            result.add(new StockLedgerRow(
                    num(r[0]), (String) r[1], (String) r[2], num(r[3]), (String) r[4],
                    num(r[5]), num(r[6]), num(r[7]), num(r[8]), num(r[9]),
                    sale, num(r[11]), num(r[12]), salesReturn, num(r[14]),
                    netSaleQty, closing, cached, closing == cached));
        }

        String kw = (keyword == null || keyword.isBlank())
                ? null : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        if (kw != null) {
            result = result.stream()
                    .filter(r -> contains(r.productCode(), kw) || contains(r.productName(), kw)
                            || contains(r.warehouseName(), kw))
                    .toList();
        }
        return sorted(result, sort);
    }

    /**
     * 수불부 정렬. 기본은 도서코드·창고명 순(쿼리 ORDER BY).
     *
     * <p>★<b>메모리에서 정렬한다.</b> 집계가 끝난 행을 다시 세우는 것이라 SQL로 내릴 이유가 없고,
     * 이월·마감이 걸러진 행 기준으로 다시 계산되는 일도 없다(키워드 필터와 같은 이유).
     *
     * <p>‼️모르는 필드는 거부한다. 조용히 기본 순서로 돌려주면 담당자는 정렬이 먹은 줄 알고
     * 위에서부터 몇 개만 보고 판단한다 — 화면엔 오류도 안 뜬다.
     *
     * @param sort {@code 필드,방향} 예: {@code closing,desc}. 방향 생략 시 asc.
     */
    private static List<StockLedgerRow> sorted(List<StockLedgerRow> rows, String sort) {
        if (sort == null || sort.isBlank()) {
            return rows;
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        // ‼️equalsIgnoreCase 를 쓰지 않는다 — 로케일·유니코드 확장에 따라 다른 문자열이
        //   같아질 수 있다(정적분석 IMPROPER_UNICODE). 받아들일 표기를 그대로 나열한다.
        String dir = (parts.length > 1) ? parts[1].trim() : "";
        boolean desc = "desc".equals(dir) || "DESC".equals(dir) || "Desc".equals(dir);

        java.util.Comparator<StockLedgerRow> cmp = switch (field) {
            case "productCode" -> java.util.Comparator.comparing(StockLedgerRow::productCode,
                    java.util.Comparator.nullsLast(String::compareTo));
            case "productName" -> java.util.Comparator.comparing(StockLedgerRow::productName,
                    java.util.Comparator.nullsLast(String::compareTo));
            case "warehouseName" -> java.util.Comparator.comparing(StockLedgerRow::warehouseName,
                    java.util.Comparator.nullsLast(String::compareTo));
            case "opening" -> java.util.Comparator.comparingLong(StockLedgerRow::opening);
            case "inbound" -> java.util.Comparator.comparingLong(StockLedgerRow::inbound);
            case "sale" -> java.util.Comparator.comparingLong(StockLedgerRow::sale);
            case "netSaleQty" -> java.util.Comparator.comparingLong(StockLedgerRow::netSaleQty);
            case "salesReturn" -> java.util.Comparator.comparingLong(StockLedgerRow::salesReturn);
            case "dispose" -> java.util.Comparator.comparingLong(StockLedgerRow::dispose);
            case "closing" -> java.util.Comparator.comparingLong(StockLedgerRow::closing);
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "정렬할 수 없는 필드입니다: " + field
                    + " (productCode/productName/warehouseName/opening/inbound/sale/"
                    + "netSaleQty/salesReturn/dispose/closing)");
        };
        return rows.stream().sorted(desc ? cmp.reversed() : cmp).toList();
    }

    /** 키워드 부분일치(대소문자 무시). null 필드는 안 맞는 것으로 본다. */
    private static boolean contains(String v, String kw) {
        return v != null && v.toLowerCase(java.util.Locale.ROOT).contains(kw);
    }

    /** 입고/대체 내역이 다루는 재고이벤트 — 입고·이고·세트조립·세트해체. */
    private static final java.util.List<TxnType> RECORD_KINDS = java.util.List.of(
            TxnType.INBOUND, TxnType.TRANSFER, TxnType.BOM_ASSEMBLE, TxnType.BOM_DISASSEMBLE);

    /**
     * 입고/대체 내역 조회(8p·9p). 등록만 되고 조회가 없던 것을 채운다.
     *
     * <p>★수량 부호를 <b>그대로</b> 준다. 폐기는 "12권 버렸다"라 양수로 뒤집었지만,
     * 여기서는 방향이 곧 정보다 — 이고는 출발(−)·도착(+) 두 줄이고
     * 세트작업도 완제품(+)·구성품(−)으로 갈린다. 절댓값으로 바꾸면 그 구분이 사라진다.
     *
     * <p>도서·창고는 <b>다중선택</b>이다(좌측 트리뷰 체크박스, 2026-08-31 공통 요구).
     *
     * @param kinds 작업구분 필터(TxnType). 비면 네 종류 전부
     */
    @Transactional(readOnly = true)
    public List<StockRecordRow> stockRecords(LocalDate fromDate, LocalDate toDate,
                                             List<Long> productIds, List<Long> warehouseIds,
                                             List<TxnType> kinds) {
        List<TxnType> effectiveKinds = MultiSelect.isAny(kinds) ? RECORD_KINDS : kinds;
        return inventoryTxnRepository
                .findStockRecords(effectiveKinds, fromDate, toDate,
                        MultiSelect.isAny(productIds), MultiSelect.orPlaceholder(productIds, 0L),
                        MultiSelect.isAny(warehouseIds), MultiSelect.orPlaceholder(warehouseIds, 0L)).stream()
                .map(t -> new StockRecordRow(
                        t.getId(), t.getTradeDate(), StockRecordRow.kindOf(t.getTxnType()),
                        t.getWarehouse().getId(), t.getWarehouse().getName(),
                        t.getProduct().getId(), t.getProduct().getCode(), t.getProduct().getName(),
                        t.getProduct().getCatCode(), t.getProduct().getCatName(),
                        t.getQty(), t.getInboundType(),
                        (t.getPartner() == null) ? null : t.getPartner().getId(),
                        (t.getPartner() == null) ? null : t.getPartner().getCode(),
                        (t.getPartner() == null) ? null : t.getPartner().getName(),
                        t.getUnitCost(), t.getRefNo(), t.getMemo()))
                .toList();
    }

    /**
     * 폐기 내역 조회(10p). 재고이벤트(DISPOSE)가 원천이다 — 별도 폐기 원장을 두지 않는다.
     *
     * <p>★수량은 원장에 <b>음수</b>로 들어 있다(재고를 깎으므로). 화면에는 양수로 보여야 하니
     * 여기서 부호를 뒤집는다. "10권 버렸다"를 −10으로 보여주면 담당자가 다시 읽어야 한다.
     *
     * <p>★<b>사유와 비고는 같은 칸이다.</b> 등록할 때 사유를 memo에 넣고 있어
     * 지금은 사유만 채우고 비고는 비운다 — 둘을 나누려면 컬럼을 하나 더 만들어야 하고,
     * 그건 화면에서 실제로 둘 다 쓰는지 확인한 뒤에 할 일이다.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<DisposalRecordRow> disposals(
            LocalDate fromDate, LocalDate toDate, List<Long> productIds, List<Long> warehouseIds,
            org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<InventoryTxn> page =
                inventoryTxnRepository.findDisposals(fromDate, toDate,
                        MultiSelect.isAny(productIds), MultiSelect.orPlaceholder(productIds, 0L),
                        MultiSelect.isAny(warehouseIds), MultiSelect.orPlaceholder(warehouseIds, 0L),
                        pageable);
        java.util.Set<String> canceled = canceledRefNos(page.getContent());
        return page.map(t -> disposalRow(t, isCanceled(canceled, t)));
    }

    /** 폐기 내역 전량(엑셀 다운로드 전용). 화면은 페이지로 보지만 파일은 전량이 나가야 쓸모가 있다. */
    @Transactional(readOnly = true)
    public List<DisposalRecordRow> disposalsAll(LocalDate fromDate, LocalDate toDate,
                                                List<Long> productIds, List<Long> warehouseIds) {
        List<InventoryTxn> rows = inventoryTxnRepository.findDisposalsAll(fromDate, toDate,
                        MultiSelect.isAny(productIds), MultiSelect.orPlaceholder(productIds, 0L),
                        MultiSelect.isAny(warehouseIds), MultiSelect.orPlaceholder(warehouseIds, 0L));
        java.util.Set<String> canceled = canceledRefNos(rows);
        return rows.stream().map(t -> disposalRow(t, isCanceled(canceled, t))).toList();
    }

    /** 페이지에 실린 전표 중 취소된 것(N+1 방지 — 번호만 모아 한 번 묻는다). */
    /**
     * 이 묶음 안에서 <b>취소된 전표번호</b>만 추린다.
     *
     * <p>‼️빈 결과를 {@code Set.of()}로 돌려주면 안 된다 — 불변 Set은 <b>null-hostile</b>이라
     * {@code contains(null)}이 NPE를 던진다. 전표번호가 없는 행(이관분)만 있는 페이지에서
     * 폐기 내역 조회가 통째로 500이 났다(2026-10-22, DISPOSE 4,374건 전부 ref_no NULL).
     * 호출부에서도 null을 먼저 거르지만, 여기서도 null을 받아낼 수 있는 Set을 돌려준다.
     */
    private java.util.Set<String> canceledRefNos(List<InventoryTxn> txns) {
        java.util.Set<String> refNos = new java.util.HashSet<>();
        txns.forEach(t -> {
            if (t.getRefNo() != null) {
                refNos.add(t.getRefNo());
            }
        });
        if (refNos.isEmpty()) {
            return new java.util.HashSet<>();
        }
        return new java.util.HashSet<>(voucherCancelRepository.findRefNosIn(refNos));
    }

    /** 전표번호가 없는 행은 취소될 수 없다 — 조회(contains)에 null을 흘리지 않는다. */
    private static boolean isCanceled(java.util.Set<String> canceled, InventoryTxn t) {
        return t.getRefNo() != null && canceled.contains(t.getRefNo());
    }

    private static DisposalRecordRow disposalRow(InventoryTxn t, boolean canceled) {
        return new DisposalRecordRow(
                t.getId(), t.getRefNo(), t.getTradeDate(),
                t.getWarehouse().getId(), t.getWarehouse().getName(),
                t.getProduct().getId(), t.getProduct().getCode(), t.getProduct().getName(),
                t.getProduct().getCatCode(), t.getProduct().getCatName(),
                Math.abs(t.getQty()), canceled, t.getMemo(), null);
    }

    /**
     * 폐기 분류명별 요약(10p). 상세({@link #disposals})가 낱건이라면 이건 분류 단위 합계다.
     * 수량은 양수로 뒤집어 낸다 — 원장은 음수지만 "몇 부 버렸나"에 음수를 주면 다시 읽어야 한다.
     */
    @Transactional(readOnly = true)
    public DisposalSummaryResponse disposalSummary(LocalDate fromDate, LocalDate toDate,
                                                   List<Long> productIds, List<Long> warehouseIds) {
        List<DisposalSummaryResponse.Row> rows = new ArrayList<>();
        long totalCount = 0;
        long totalQty = 0;
        // ★상세(disposals)와 똑같은 필터를 넘긴다 — 갈리면 요약과 상세가 서로 다른 말을 한다.
        for (Object[] r : inventoryTxnRepository.disposalSummaryByCategory(fromDate, toDate,
                MultiSelect.isAny(productIds), MultiSelect.orPlaceholder(productIds, 0L),
                MultiSelect.isAny(warehouseIds), MultiSelect.orPlaceholder(warehouseIds, 0L))) {
            long cnt = num(r[2]);
            long qty = num(r[3]);
            rows.add(new DisposalSummaryResponse.Row((String) r[0], (String) r[1], cnt, qty));
            totalCount += cnt;
            totalQty += qty;
        }
        return new DisposalSummaryResponse(fromDate, toDate, rows, totalCount, totalQty);
    }

    /**
     * 세트 <b>조립·해체 현황</b>(30p). 근거: 발주처 화면검토(2026-08-31) 화면30 —
     * "화면 27(물류 작업비 계산)의 비용 산출과는 <b>연동되지 않도록 분리</b>해,
     * 세트 조립·해체 작업을 진행한 <b>현황(결과)만</b> 보여주는 조회 화면으로 유지".
     *
     * <p>★<b>이 화면은 비용을 말하지 않는다.</b> 발주처가 요구한 '분리'는 화면을 나누는 게 아니라
     * 여기서 금액을 빼는 것이다. 금액 칸이 하나라도 있으면 화면27과 값이 갈리는 순간
     * 어느 쪽이 맞는지 다투게 된다. 그래서 응답에 작업비 필드가 <b>한 칸도 없다</b>.
     *
     * <p>⚠️<b>이전 구현(회차별 포장유형 물량 집계)을 대체한다.</b> 그건 레거시
     * {@code IC회차별작업현황.vb}를 그대로 옮긴 것이라 레거시로서는 맞았지만,
     * 발주처가 "후자라면 조립·해체 현황으로 구성해 달라"고 했다.
     * 포장유형 집계가 필요하면 {@code GET /sales/round-work-status}가 그대로 남아 있다.
     */
    @Transactional(readOnly = true)
    public BomWorkStatusResponse bomWorkStatus(LocalDate fromDate, LocalDate toDate,
                                               List<Long> warehouseIds, String catCode) {
        boolean anyWarehouse = MultiSelect.isAny(warehouseIds);
        java.util.Collection<Long> whIds = MultiSelect.orPlaceholder(warehouseIds, 0L);
        String cat = (catCode == null || catCode.isBlank()) ? null : catCode;

        List<BomWorkStatusResponse.Row> rows = new ArrayList<>();
        long asmCnt = 0;
        long asmQty = 0;
        long disCnt = 0;
        long disQty = 0;
        for (Object[] r : inventoryTxnRepository.bomWorkByProduct(fromDate, toDate, anyWarehouse, whIds, cat)) {
            long ac = num(r[5]);
            long aq = num(r[6]);
            long dc = num(r[7]);
            long dq = num(r[8]);
            rows.add(new BomWorkStatusResponse.Row(
                    (String) r[0], (String) r[1], num(r[2]), (String) r[3], (String) r[4],
                    ac, aq, dc, dq, aq - dq, toLocalDate(r[9])));
            asmCnt += ac;
            asmQty += aq;
            disCnt += dc;
            disQty += dq;
        }

        List<BomWorkStatusResponse.Component> comps = new ArrayList<>();
        for (Object[] c : inventoryTxnRepository.bomWorkComponents(fromDate, toDate, anyWarehouse, whIds)) {
            long used = num(c[3]);
            long back = num(c[4]);
            comps.add(new BomWorkStatusResponse.Component(
                    num(c[0]), (String) c[1], (String) c[2], used, back, used - back));
        }
        return new BomWorkStatusResponse(rows, comps, asmCnt, asmQty, disCnt, disQty);
    }

    /** 네이티브 쿼리의 날짜 컬럼 → LocalDate. 드라이버가 java.sql.Date로 줄 수도, 이미 LocalDate로 줄 수도 있다. */
    private static LocalDate toLocalDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        return (v instanceof java.sql.Date d) ? d.toLocalDate() : null;
    }

    /**
     * 제품수불부 <b>결산내역</b>(연초~기준일 누적). 근거: 정본 11p 데이터 항목 +
     * 레거시 제품수불부 「결산내역」 체크박스.
     *
     * <p>★기간을 받지 않는다 — <b>기준일 하나만</b> 받고 시작일은 그 해 1월 1일로 못 박는다.
     * 레거시가 체크박스를 켜는 순간 하는 일이 정확히 그것이다
     * ({@code DateTimePicker_Start.Value = 기준일.ToString("yyyy.01.01")}).
     * 시작일을 호출부가 고를 수 있게 두면 "결산"이라는 이름이 거짓말이 된다.
     *
     * <p>분류 필터도 두지 않는다. 레거시는 결산내역을 켜면 분류 콤보를 <b>비활성</b>시킨다
     * ({@code ComboBox_CatList.Enabled = False}) — 결산은 전 분류를 놓고 소계·총계를 보는 화면이다.
     */
    @Transactional(readOnly = true)
    public List<StockSettlementRow> stockSettlement(LocalDate baseDate, Long productId,
                                                    WarehouseType warehouseType) {
        LocalDate to = (baseDate != null) ? baseDate : LocalDate.now();
        LocalDate from = to.withDayOfYear(1);
        String whType = (warehouseType == null) ? null : warehouseType.name();

        List<StockSettlementRow> rows = new ArrayList<>();
        long[] cat = new long[BUCKETS];      // 분류 소계
        long[] grand = new long[BUCKETS];    // 총계
        String curCat = null;
        boolean catOpen = false;

        for (Object[] r : inventoryTxnRepository.stockSettlement(from, to, productId, whType)) {
            String catCode = (String) r[0];
            if (catOpen && !java.util.Objects.equals(catCode, curCat)) {
                rows.add(StockSettlementRow.catSubtotal(curCat, cat));
                catOpen = false;
            }
            if (!catOpen) {
                curCat = catCode;
                cat = new long[BUCKETS];
                catOpen = true;
            }

            long[] b = new long[BUCKETS];
            for (int i = 0; i < BUCKETS; i++) {
                b[i] = num(r[5 + i]);
                cat[i] += b[i];
                grand[i] += b[i];
            }
            rows.add(StockSettlementRow.detail(catCode, (String) r[1], num(r[2]),
                    (String) r[3], (String) r[4], b));
        }
        if (catOpen) {
            rows.add(StockSettlementRow.catSubtotal(curCat, cat));
        }
        // ‼️행이 하나도 없어도 총계는 낸다 — 빈 화면과 "0으로 결산됐다"는 다른 말이다.
        rows.add(StockSettlementRow.total(grand));
        return rows;
    }

    /**
     * 세트의 <b>회차별</b> 수불 현황(요약 → 회차 → 자재 중 가운데 단계).
     *
     * <p>★<b>여기서 다시 계산하지 않는다.</b> 회차 상품으로 좁혀 {@link #stockLedger}를 부른다 —
     * 화면마다 계산식이 갈리면 세트 합과 회차 합이 어긋난다(3,029/3,006 사고와 같은 이유).
     *
     * <p>‼️<b>거래가 없는 회차도 0으로 담는다.</b> 빼 버리면 담당자는 "이 회차는 왜 없지"를
     * 확인하러 다른 화면을 열어야 한다. BOM에 있으면 구성 회차다.
     */
    @Transactional(readOnly = true)
    public com.daesung.sales.inventory.dto.SetRoundLedgerResponse setRounds(
            Long setProductId, LocalDate fromDate, LocalDate toDate,
            WarehouseType warehouseType) {
        Product set = productRepository.findById(setProductId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "상품이 없습니다. id=" + setProductId));

        List<com.daesung.sales.inventory.dto.SetRoundLedgerResponse.Round> rounds = new ArrayList<>();
        for (BomItem item : bomItemRepository.findByParentId(setProductId)) {
            List<StockLedgerRow> ledger = stockLedger(fromDate, toDate,
                    item.getChild().getId(), null, warehouseType, null);
            rounds.add(new com.daesung.sales.inventory.dto.SetRoundLedgerResponse.Round(
                    item.getRound(), item.getRatio(), ledger));
        }
        rounds.sort(java.util.Comparator.comparingInt(
                com.daesung.sales.inventory.dto.SetRoundLedgerResponse.Round::round));

        return new com.daesung.sales.inventory.dto.SetRoundLedgerResponse(
                set.getId(), set.getCode(), set.getName(), rounds);
    }

    /** 결산 버킷 수(이월·입고·이고·조립해체·폐기·매출·무상·교사용·반품·조정·재고). */
    private static final int BUCKETS = 11;

    private static long num(Object o) {
        return (o == null) ? 0L : ((Number) o).longValue();
    }

    /**
     * 재고 잔량 증감(원자적). delta>=0이면 가산(없으면 생성), delta<0이면 음수재고 방지 차감.
     * 반환값 = 갱신 후 잔량.
     */
    /**
     * 재고 전표 삭제(마감 前 오입력 정정) — 입고·폐기·이고·실사에 공통.
     * 근거: 발주처 회신 2026-08-14 [4] "마감 확정 前 → 우클릭 삭제 가능".
     *
     * <p>취소({@link #cancelVoucher})와 갈라 두는 이유는 매출과 같다 —
     * 취소는 "되돌렸다"를 장부에 남기고, 삭제는 "애초에 없던 일"로 만든다.
     * 이미 취소된 전표는 삭제할 수 없다(되돌린 기록이 장부에 선 뒤라 오입력이 아니다).
     */
    @Transactional
    public void deleteVoucher(String refNo, String reason, String actor) {
        List<InventoryTxn> origins = inventoryTxnRepository.findAllByRefNo(refNo);
        if (origins.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "전표가 없습니다: " + refNo);
        }
        if (voucherCancelRepository.existsByRefNo(refNo)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "이미 취소된 전표는 삭제할 수 없습니다: " + refNo + " (취소 기록이 장부에 남아 있습니다)");
        }
        periodLockService.assertNotLocked(origins.get(0).getTradeDate());

        // ‼️지운 내용을 먼저 남긴다 — 삭제 후에는 이 기록에만 남는다.
        StringBuilder sb = new StringBuilder("전표 삭제(").append(refNo).append(") ");
        for (InventoryTxn t : origins) {
            sb.append(itemCode(t)).append(' ').append(t.getQty()).append("부 / ");
        }
        statusHistoryService.record(StatusEntityType.INVENTORY_VOUCHER, origins.get(0).getId(),
                "deleted", "false", "true", sb.append("사유: ").append(reason).toString());

        softDeleteByRefNo(refNo, actor);
    }

    /**
     * 폐기 전표 수정(2026-10-08 [A]2 / 9/27 회의 항목 7·9 「우클릭 수정」).
     *
     * <p>★<b>삭제 + 재등록이다</b>(한 트랜잭션). 새 전표번호가 나간다.
     * 수량을 제자리에서 고치거나 취소+재등록으로 하지 않는 이유는 둘 다 숫자를 틀어 놓기 때문이다 —
     * <ul>
     *   <li><b>제자리 수정</b>: 폐기 목록·요약이 {@code inventory_txn} 을 직접 읽는다.
     *       차액 행을 더하면 한 전표가 두 줄로 보이거나, 줄어든 차액 행(양수)이
     *       목록에서는 빠지고 요약에는 들어가 <b>상세와 요약이 어긋난다</b>
     *       (2026-10-01에 바로 그 결함을 고쳤다 — 되돌릴 수 없다).</li>
     *   <li><b>취소 + 재등록</b>: 취소 역분개는 <b>취소한 달</b>로 잡혀 원 전표의 수량이
     *       그 기간 합계에 그대로 남는다. 200을 150으로 고쳤는데 합계가 350이 된다.</li>
     * </ul>
     * 삭제는 "애초에 없던 일"로 만들므로 기간 합계에 수정 후 수량만 남는다.
     *
     * <p>마감 검사는 <b>원 전표의 달과 새 처리일자의 달 둘 다</b>다 — 한쪽만 보면
     * 마감된 달의 폐기를 열린 달로 옮기거나 그 반대로 밀어 넣을 수 있다.
     *
     * <p>이미 취소된 전표는 수정할 수 없다({@link #deleteVoucher} 규칙) — 되돌린 기록이
     * 장부에 선 뒤라 오입력이 아니다.
     */
    @Transactional
    public DisposalResponse reviseDisposal(String refNo, DisposalRequest req, String actor) {
        List<InventoryTxn> origins = inventoryTxnRepository.findAllByRefNo(refNo);
        if (origins.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "전표가 없습니다: " + refNo);
        }
        if (origins.stream().noneMatch(t -> t.getTxnType() == TxnType.DISPOSE)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "폐기 전표가 아닙니다: " + refNo + ". 입고·이고·실사 전표는 이 API로 고칠 수 없습니다.");
        }
        periodLockService.assertNotLocked(req.processedDate());   // 옮겨 갈 달
        // 원 전표의 달은 deleteVoucher 가 검사한다(같은 규칙을 두 곳에 적지 않는다).

        deleteVoucher(refNo, "폐기 수정으로 재등록", actor);
        DisposalResponse revised = dispose(req);

        // ★어느 전표가 어느 전표로 바뀌었는지 남긴다. 삭제 이력과 등록만 남으면
        //   나중에 둘이 같은 건이었다는 사실을 아무도 못 잇는다.
        statusHistoryService.record(StatusEntityType.INVENTORY_VOUCHER,
                origins.get(0).getId(), "revised", refNo, revised.disposalNo(),
                "폐기 수정: " + refNo + " → " + revised.disposalNo());
        return revised;
    }

    /**
     * 전표 하나가 만든 재고 이벤트를 <b>없던 것으로</b> 되돌린다(마감 前 삭제 전용).
     *
     * <p>취소({@link #reverseShipments})와 다르다 — 취소는 <b>반대 부호 이벤트를 새로 남겨</b>
     * "되돌렸다"를 장부에 기록한다. 삭제는 애초에 잘못 친 것이라 그 기록이 남으면 안 된다.
     * 그래서 원 이벤트를 논리삭제하고 잔량만 반대로 민다.
     *
     * <p>★<b>행은 지우지 않는다.</b> {@code inventory_txn}은 재고의 유일 진실이고
     * 재고·수불부·채권이 전부 여기서 파생된다. 물리삭제하면 그 원장을 찢는 것과 같다.
     * 삭제행은 {@code deleted_at}으로 집계에서만 빠지고, 누가·언제 지웠는지는 남는다.
     *
     * <p>이미 취소된 전표도 그대로 처리된다 — 원 이벤트와 역분개 이벤트의 합이 0이라
     * 잔량이 움직이지 않고 두 행이 함께 삭제 표시된다.
     *
     * @return 삭제 표시된 이벤트 수(0이면 재고를 안 쓰는 전표였다 — 오류가 아니다)
     */
    @Transactional
    public int softDeleteByRefNo(String refNo, String actor) {
        List<InventoryTxn> txns = inventoryTxnRepository.findAllByRefNo(refNo);

        // ‼️순서가 중요하다. applyDelta 의 원자적 UPDATE는 @Modifying(clearAutomatically=true)라
        //   **영속성 컨텍스트를 비운다** — 먼저 잔량을 밀면 뒤이은 markDeleted 가 detached 엔티티에
        //   걸려 조용히 아무것도 안 한다(실제로 이 순서로 짰다가 재고도 목록도 안 바뀌었다).
        //   삭제 표시를 먼저 확정(flush)하고, 잔량은 미리 빼둔 값으로 민다.
        record Delta(Product product, com.daesung.sales.material.entity.Material material,
                     Warehouse warehouse, int qty) {
        }
        List<Delta> deltas = new ArrayList<>(txns.size());
        for (InventoryTxn t : txns) {
            deltas.add(new Delta(t.getProduct(), t.getMaterial(), t.getWarehouse(), t.getQty()));
            t.markDeleted(actor);
        }
        inventoryTxnRepository.flush();

        for (Delta d : deltas) {
            if (d.material() != null) {
                applyMaterialDelta(d.material(), d.warehouse(), -d.qty());
            } else {
                applyDelta(d.product(), d.warehouse(), -d.qty());
            }
        }
        return txns.size();
    }

    /**
     * 전표 종류 판정. 예전엔 {@code INBOUND ? "INBOUND" : "DISPOSE"} 둘뿐이라
     * 이고·세트작업 전표를 취소하면 <b>폐기로 기록</b>됐다(번호가 없어 실제로 닿진 않았지만,
     * 번호를 붙인 지금은 닿는다).
     */
    private static String voucherKind(TxnType txnType) {
        return switch (txnType) {
            case INBOUND -> "INBOUND";
            case TRANSFER -> "TRANSFER";
            case BOM_ASSEMBLE, BOM_DISASSEMBLE -> "BOM_WORK";
            default -> "DISPOSE";
        };
    }

    private static String voucherKindLabel(String kind) {
        return switch (kind) {
            case "INBOUND" -> "입고";
            case "TRANSFER" -> "이고";
            case "BOM_WORK" -> "세트작업";
            default -> "폐기";
        };
    }

    /**
     * 음수재고 경고 한 건. 상품코드·창고명을 <b>id 로 다시 읽는다</b>.
     *
     * <p>‼️여기까지 오는 사이 영속성 컨텍스트가 비워졌을 수 있다(잔량 갱신이 원자적 UPDATE라
     * {@code clearAutomatically}). 그러면 넘어온 엔티티가 detached 프록시라
     * {@code getName()}·{@code getCode()} 에서 {@code LazyInitializationException} 이 나고
     * 요청 전체가 500이 된다.
     *
     * <p>★<b>위탁 반품이 이것 때문에 500이었다</b>(프론트 실측 2026-10-12 A-1).
     * 조건이 "<b>재고가 음수</b>" 하나라서 평소엔 멀쩡하다가 그 건에서만 터졌다 —
     * 경고를 만드는 드문 경로에만 있어 통합테스트도 오래 비껴갔다.
     * ‼️재현 조건을 "재고 미관리 상품"으로 잘못 짚은 적이 있다(그 테스트도 입고를 안 해
     * 재고가 음수였을 뿐이다). 상품 속성이 아니라 <b>잔량 부호</b>가 조건이다.
     */
    private com.daesung.sales.inventory.dto.StockWarning negativeWarning(
            Long productId, Long warehouseId, int delta, int balance) {
        return com.daesung.sales.inventory.dto.StockWarning.negative(
                productId,
                productRepository.findById(productId).map(Product::getCode).orElse(null),
                warehouseId,
                warehouseRepository.findById(warehouseId).map(Warehouse::getName).orElse(null),
                delta, balance);
    }

    private int applyDelta(Product product, Warehouse warehouse, int delta) {
        // ★음수 재고는 **막지 않는다**. 발주처 확정(2026-08-31 화면7) 원문 —
        //   "재고 음수 차단 로직은 적용되면 안됩니다. 입고 전 출고되는 상품은 재고 (−)로 처리되며,
        //    DSRE에서 수불관리하는 상품도 출고 수량만 나타나 재고가 마이너스로 표시되는 게 정상입니다."
        //
        // ‼️이 판단은 두 번 뒤집혔다. 2026-09-11 사내 점검이 "폐기 초과가 통과된다"를 결함으로 보고
        //   차단을 넣었는데, 발주처 기준으로는 **통과가 정상**이라 다시 걷어냈다.
        //   되돌리기 전에 위 원문을 반드시 확인할 것 — 막으면 "입고 전 출고"가 통째로 불가능해진다.
        //
        //   예외적으로 막아야 할 상황이 생기면 daesung.inventory.block-negative-stock=true.
        if (delta < 0 && inventoryProperties.blockNegative()) {
            // ‼️"조회해서 비교한 뒤 빼는" 방식은 쓰지 않는다. 두 요청이 같은 잔량을 읽고
            //   둘 다 통과해 음수가 된다(read-modify-write). 조건을 UPDATE 문 안에 넣어
            //   **DB가 행을 잠근 채 판단**하게 한다.
            int ok = inventoryRepository.addQtyIfEnough(product.getId(), warehouse.getId(), delta);
            if (ok == 0) {
                // 0은 두 가지다 — 재고 부족, 또는 아직 입고된 적 없어 행 자체가 없음.
                int current = inventoryRepository
                        .findByProductIdAndWarehouseId(product.getId(), warehouse.getId())
                        .map(Inventory::getQty)
                        .orElse(0);
                throw new BusinessException(ErrorCode.NEGATIVE_STOCK,
                        "재고가 부족합니다. 현재고 " + current + ", 요청 " + (-delta));
            }
            return inventoryRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId())
                    .map(Inventory::getQty)
                    .orElse(0);
        }

        int updated = inventoryRepository.addQty(product.getId(), warehouse.getId(), delta);
        if (updated == 0) {
            // 행이 없으면 만든다. 첫 거래가 출고면 음수로 시작한다 — 그게 "입고 전 출고"다.
            inventoryRepository.save(Inventory.create(product, warehouse, delta));
        }
        int balance = inventoryRepository.findByProductIdAndWarehouseId(product.getId(), warehouse.getId())
                .map(Inventory::getQty)
                .orElse(delta);

        // ‼️막지는 않되 조용히 넘기지도 않는다.
        //   "입고 전 출고"라 정상인 음수와 오타로 생긴 음수는 데이터만 봐서는 구분되지 않는다.
        //   담당자가 그 자리에서 알아채도록 응답에 싣고, 나중에 되짚도록 서버 로그에도 남긴다.
        // ★★<b>재고를 줄이는 작업일 때만</b> 경고한다(delta < 0).
        //
        //   ‼️예전엔 "잔량이 음수"만 보고 붙였는데, 그러면 **반품입고에도 경고가 붙는다**
        //     — 반품은 재고가 느는 쪽인데도 잔량이 아직 음수면 걸렸다(프론트 실측 2026-10-23 §6④).
        //     이관 데이터에 음수 재고가 흔해서(발주처도 "교재 외 상품의 마이너스 165종은 정상"으로
        //     확인) 반품·입고마다 경고가 붙어 **노이즈가 된다.**
        //
        //   경고의 뜻은 "이 작업으로 재고가 모자라게 됐다"이지 "지금 잔량이 음수다"가 아니다.
        //   잔량이 음수인 것 자체는 발주처가 정상으로 본 상태라 알릴 일이 아니고,
        //   재고를 **채우는** 작업에 경고를 붙이면 담당자가 잘못한 줄 안다.
        //   (같은 파일의 테스트 주석대로 — "경고가 늘 붙으면 아무도 안 본다".)
        if (delta < 0 && balance < 0) {
            // ‼️이름·코드는 **id 로 다시 읽는다.** 여기까지 오는 사이 영속성 컨텍스트가
            //   비워졌을 수 있고(이 메서드의 원자적 UPDATE가 clearAutomatically),
            //   그러면 엔티티가 detached 프록시라 getName()/getCode() 에서
            //   LazyInitializationException 이 난다 → 500.
            //
            // ★위탁 반품이 이것 때문에 500이었다(프론트 실측 2026-10-12 A-1).
            //   조건이 "**재고가 음수**" 하나라서 평소엔 멀쩡하다가 그 건에서만 터졌다 —
            //   경고를 만드는 드문 경로에만 있어 통합테스트도 오래 비껴갔다.
            //   ‼️재현 조건을 "재고 미관리 상품"으로 잘못 짚은 적이 있다(그 테스트도 입고를
            //     안 해 재고가 음수였을 뿐이다). 상품 속성이 아니라 **잔량 부호**가 조건이다.
            // 이름·코드 조회는 **별도 메서드**로 둔다 — 여기에 DB에서 읽은 문자열 지역변수가 있으면
            //   아래 log.warn 이 정적분석 CRLF_INJECTION_LOGS 에 걸린다(같은 스코프를 오염으로 본다).
            stockWarningCollector.add(negativeWarning(
                    product.getId(), warehouse.getId(), delta, balance));
            // ★로그에는 **숫자 id만** 넣는다. 상품코드·창고명은 마스터에서 온 문자열이라
            //   줄바꿈이 섞이면 로그 한 줄을 위조할 수 있다.
            log.warn("재고 음수(차단 해제 상태): productId={} warehouseId={} 잔량={} 증감={}",
                    product.getId(), warehouse.getId(), balance, delta);
        }
        return balance;
    }
}
