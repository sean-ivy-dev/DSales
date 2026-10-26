package com.daesung.sales.logistics.service;

import com.daesung.sales.audit.entity.StatusEntityType;
import com.daesung.sales.audit.service.StatusHistoryService;
import com.daesung.sales.common.audit.CurrentAuditor;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.tx.AfterRollback;
import com.daesung.sales.dsre.gateway.DsreGateway;
import com.daesung.sales.dsre.gateway.DsreShipResult;
import com.daesung.sales.dsre.gateway.OrderState;
import com.daesung.sales.logistics.dto.DsreStateSync;
import com.daesung.sales.logistics.dto.ShippingUpdateRequest;
import com.daesung.sales.logistics.dto.TrackingUploadResponse;
import com.daesung.sales.logistics.entity.DeliveryType;
import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
import com.daesung.sales.partner.entity.Partner;
import com.daesung.sales.sale.entity.Sale;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발송(작업) 단위 관리. 레거시 {@code sendData} 흐름을 그대로 옮긴다 —
 * 매출등록이 만들고, 작업요청서가 채우고, 작업결과가 읽는다.
 */
@Service
@RequiredArgsConstructor
public class ShipmentService {

    /** status_history 의 상태축 이름. 화면 라벨이 아니라 감사 조회 키라 바꾸면 과거 이력과 끊긴다. */
    private static final String FIELD_PRINTED = "printed";
    private static final String FIELD_ACKNOWLEDGED = "acknowledged";

    private final ShipmentRepository shipmentRepository;
    private final StatusHistoryService statusHistoryService;
    private final CurrentAuditor currentAuditor;
    /** DSRE 연동이 꺼져 있으면(daesung.dsre.enabled=false) 비어 있다 — 그땐 우리 발송일만 다룬다. */
    private final ObjectProvider<DsreGateway> dsreGateway;

    /**
     * 매출 1건에 대응하는 발송 건을 확보한다(없으면 생성). 근거: UC_TabPages.vb:752 —
     * 레거시도 매출등록 시점에 sendData를 만든다.
     *
     * <p>묶는 단위는 <b>(거래일자 · 거래처 · 학교 · 분류)</b>다. 같은 날 같은 학교로 여러 품목을
     * 등록해도 <b>발송은 한 건</b>이어야 한다 — 물류는 품목이 아니라 상자 단위로 일한다.
     *
     * <p>반품은 대상이 아니다. 들어오는 물건이라 내보낼 작업이 없다.
     */
    @Transactional
    public Shipment ensureFor(Sale sale) {
        String tradeClass = sale.getProduct().getSalesDivision();
        return shipmentRepository.findKey(sale.getSalesDate(), sale.getPartner().getId(),
                        sale.getSchoolCode(), tradeClass)
                .orElseGet(() -> shipmentRepository.save(Shipment.of(
                        tradeClass, sale.getSalesDate(), sale.getPartner(),
                        sale.getSchoolCode(), sale.getSchoolName(), null)));
    }

    /** status_history 의 발송 상태축 이름. */
    private static final String FIELD_SENT = "sent";

    /**
     * 발송취소에 맞춰 <b>우리 쪽 발송 기록도</b> 내린다(2026-10-08 [A]5).
     *
     * <p>★<b>이게 없어서 발송취소가 안 되는 것으로 보였다.</b> 발송취소는 DSRE
     * {@code tbl_request_info.STATE} 를 D→W 로 실제로 되돌리는데, 작업요청서 화면의
     * '발송완료'는 우리 {@code shipment.sent_date} 로 판정한다 — 축이 둘이라
     * DSRE 만 돌아가고 화면은 그대로였다.
     *
     * <p>한 신청이 여러 발송 건에 걸릴 수 있고(분류가 다르면 발송이 갈린다) 한 발송 건에
     * 신청이 여럿일 수도 있다. 후자는 <b>의도된 동작</b>이다 — 같은 날 같은 학교로 들어온
     * 두 주문을 한 상자에 싸서 보냈다면, 그 상자의 발송을 취소하는 것이 맞다.
     * 다만 <b>남은 신청의 상태는 DSRE 쪽에서 D 로 남는다</b>(우리가 임의로 바꾸지 않는다) —
     * 화면에서 함께 취소하도록 신청번호 목록({@code reqCds})을 이미 내려주고 있다.
     *
     * @return 실제로 내려간 발송 건 수(0이면 그 신청으로 세운 발송이 없거나 이미 발송 전이다)
     */
    @Transactional
    public int revertSentByReqCd(Integer reqCd, String reason) {
        if (reqCd == null) {
            return 0;
        }
        int reverted = 0;
        for (Shipment s : shipmentRepository.findByReqCd(reqCd)) {
            if (s.revertSent()) {
                statusHistoryService.record(StatusEntityType.SHIPMENT, s.getId(),
                        FIELD_SENT, true, false,
                        "발송취소(신청번호 " + reqCd + ") — " + orNoReason(reason));
                reverted++;
            }
        }
        return reverted;
    }

    /**
     * 주문의 「택배 발송」·담당자를 그 매출의 작업요청서에 <b>자동 입력</b>한다
     * (2026-10-08 프론트 요청 [A]4 / 9/27 회의 1·20번).
     *
     * <p>DSRE 배송구분 {@code LGS_GN} 을 우리 발송구분으로 옮긴다 — <b>T=택배 / H=화물</b>.
     * 두 축은 원래 별개(DSRE는 "지사로 보내나 학교로 보내나", 우리는 "무엇으로 보내나")지만,
     * 운영상 학교·학원 직송이 택배이고 지사 묶음이 화물이라 값이 1:1로 대응한다.
     *
     * <p>비어 있는 항목만 채운다({@link Shipment#fillDeliveryIfBlank}) — 이유는 그쪽 주석 참고.
     * 알 수 없는 구분값이면 발송구분은 건드리지 않고 담당자만 채운다.
     *
     * @param deliveryGubun DSRE {@code LGS_GN}(H/T). null·기타값이면 발송구분 미설정
     */
    @Transactional
    public void autoFillDelivery(Sale sale, String deliveryGubun, String teacher, String tel) {
        DeliveryType type = deliveryTypeOf(deliveryGubun);
        if (type == null && (teacher == null || teacher.isBlank())
                && (tel == null || tel.isBlank())) {
            return;   // 채울 것이 없다
        }
        shipmentRepository.findKey(sale.getSalesDate(), sale.getPartner().getId(),
                        sale.getSchoolCode(), sale.getProduct().getSalesDivision())
                .ifPresent(s -> s.fillDeliveryIfBlank(type, teacher, tel));
    }

    /** DSRE 배송구분(H/T) → 우리 발송구분. 모르는 값은 null(미지정) — 임의로 한쪽에 넣지 않는다. */
    private static DeliveryType deliveryTypeOf(String lgsGn) {
        if (lgsGn == null || lgsGn.isBlank()) {
            return null;
        }
        // ★대소문자 변환 없이 값을 그대로 본다. 한 글자 코드라 변환할 이유가 없고,
        //   변환 후 비교는 로케일에 따라 결과가 달라진다(정적분석 IMPROPER_UNICODE).
        return switch (lgsGn.trim()) {
            case "T", "t" -> DeliveryType.COURIER;
            case "H", "h" -> DeliveryType.FREIGHT;
            default -> null;
        };
    }

    /**
     * 작업요청서 단위 <b>발송취소</b>(프론트 실측 2026-10-12 A-3 → 2026-10-23 DSRE 연동).
     *
     * <p>★<b>발송일만 내린다.</b> 박스 수·송장번호·수령인은 그대로 둔다 —
     * 물건을 싸 둔 사실은 취소되지 않고, 다시 보낼 때 그 값이 필요하다
     * (송장번호는 지우면 복원할 수 없다). 10/08 회신의 원칙과 같다.
     *
     * <p>★<b>묶인 신청의 DSRE 상태도 되돌린다</b>(2026-10-23, 발주처 정의 "발송완료 = 물류 담당자가
     * 발송 처리한 시점"). 발송완료(D)인 신청만 발송준비중(W)으로 내리고, 발송완료 때 뺀 자재를
     * 돌려놓는다({@link DsreGateway#unship}). 그 신청이 걸린 <b>다른 발송 건</b>도 함께 내린다 —
     * 신청은 발송준비중인데 그 신청의 다른 상자는 발송완료로 남으면 두 화면이 서로 다른 말을 한다.
     * (10/12 에는 "상태를 바꾸는 주체가 셋이 된다"며 보류했던 것이다 — 발주처 정의로 닫혔다.)
     */
    @Transactional
    public RevertOutcome revertSent(Long shipmentId, String reason) {
        Shipment s = getOrThrow(shipmentId);
        boolean changed = s.revertSent();
        if (!changed) {
            return new RevertOutcome(false, List.of());
        }
        String why = orNoReason(reason);
        statusHistoryService.record(StatusEntityType.SHIPMENT, s.getId(),
                FIELD_SENT, true, false, "발송취소 — " + why);

        DsreGateway gw = dsreGateway.getIfAvailable();
        List<Integer> reqCds = (gw == null) ? List.of() : shipmentRepository.findReqCds(s.getId());
        if (reqCds.isEmpty()) {
            return new RevertOutcome(true, List.of());   // DSRE 꺼짐 또는 수기 매출만 있는 발송
        }
        List<DsreShipResult> rs = gw.unship(reqCds, currentAuditor.username());
        List<DsreStateSync> out = new ArrayList<>();
        for (DsreShipResult r : rs) {
            if (r.changed()) {
                statusHistoryService.record(StatusEntityType.DSRE_ORDER, (long) r.reqCd(), "STATE",
                        r.beforeState(), OrderState.READY_TO_SHIP.code(),
                        "작업요청서 발송취소(발송건 " + s.getId() + ") — " + why);
                revertSentByReqCd(r.reqCd(), why);   // 같은 신청의 다른 발송 건
                AfterRollback.register(AfterRollback.Kind.UNDO_UNSHIP, r.reqCd(), () -> gw.ship(
                        List.of(r.reqCd()),
                        r.prevDelDate() != null ? r.prevDelDate() : LocalDate.now(),
                        currentAuditor.username()));
            }
            out.add(toSync(r, OrderState.READY_TO_SHIP, unshipMessage(r)));
        }
        return new RevertOutcome(true, out);
    }

    /** 발송취소 결과. {@code changed=false} 면 애초에 발송 전이었다(오류가 아니다). */
    public record RevertOutcome(boolean changed, List<DsreStateSync> dsre) {
    }

    /**
     * 발송완료(발송일 입력) 때 묶인 신청의 DSRE 상태를 발송완료(D)로 옮긴다.
     *
     * <p>규칙 — 발주처 정의(자료요청서 3-2(가)) "발송완료 = 물류 담당자가 발송 처리한 시점".
     * <ul>
     *   <li><b>발송준비중(W)인 신청만</b> 옮긴다. 접수완료·상품준비중은 건너뛰고 이유를 돌려준다 —
     *       단계를 건너뛰어 발송완료가 되면 검수·준비 단계가 의미 없어진다. 우리 발송일은 그대로 저장한다.</li>
     *   <li>한 신청이 <b>여러 발송 건</b>에 걸리면(분류가 다르면 상자가 갈린다), 그 발송 건이 <b>전부</b>
     *       발송된 뒤에야 옮긴다. 상자 하나 보냈다고 신청 전체가 발송완료가 되면 안 된다.</li>
     *   <li>DSRE 처리는 DSRE2 발송처리 화면과 같다(자재 차감·출고내역·발송일). DSRE 가 실패하면 예외로
     *       우리 발송일도 저장되지 않고, DSRE 는 됐는데 우리 저장이 실패하면 DSRE 를 되돌린다.</li>
     * </ul>
     */
    private List<DsreStateSync> shipToDsre(Shipment s, LocalDate sentDate) {
        DsreGateway gw = dsreGateway.getIfAvailable();
        List<Integer> reqCds = (gw == null) ? List.of() : shipmentRepository.findReqCds(s.getId());
        if (reqCds.isEmpty()) {
            return List.of();
        }
        List<DsreStateSync> out = new ArrayList<>();
        List<Integer> ready = new ArrayList<>();
        for (Integer reqCd : reqCds) {
            List<Long> pending = shipmentRepository.findByReqCd(reqCd).stream()
                    .filter(o -> !o.getId().equals(s.getId()) && !o.isSent() && o.getDeletedAt() == null)
                    .map(Shipment::getId)
                    .toList();
            if (pending.isEmpty()) {
                ready.add(reqCd);
            } else {
                out.add(new DsreStateSync(reqCd, false, null, null,
                        OrderState.SHIPPED.code(), OrderState.SHIPPED.label(),
                        "같은 신청의 다른 발송 건 " + pending + " 이 아직 발송 전이라 발송완료로 바꾸지 않았습니다."));
            }
        }
        if (ready.isEmpty()) {
            return out;
        }
        List<DsreShipResult> rs = gw.ship(ready, sentDate, currentAuditor.username());
        for (DsreShipResult r : rs) {
            if (r.changed()) {
                statusHistoryService.record(StatusEntityType.DSRE_ORDER, (long) r.reqCd(), "STATE",
                        r.beforeState(), OrderState.SHIPPED.code(),
                        "작업요청서 발송완료(발송건 " + s.getId() + ")");
                AfterRollback.register(AfterRollback.Kind.UNDO_SHIP, r.reqCd(),
                        () -> gw.unship(List.of(r.reqCd()), currentAuditor.username()));
            }
            out.add(toSync(r, OrderState.SHIPPED, shipMessage(r)));
        }
        return out;
    }

    private static DsreStateSync toSync(DsreShipResult r, OrderState to, String message) {
        return new DsreStateSync(r.reqCd(), r.changed(), r.beforeState(), OrderState.labelOf(r.beforeState()),
                to.code(), to.label(), message);
    }

    private static String shipMessage(DsreShipResult r) {
        return switch (r.outcome()) {
            case CHANGED -> null;
            case DATE_ONLY -> "이미 발송완료라 발송일만 고쳤습니다.";
            case NOT_FOUND -> "DSRE2에 해당 신청이 없습니다.";
            case SKIPPED -> OrderState.labelOf(r.beforeState())
                    + " 상태라 발송완료로 바꾸지 않았습니다. 발송준비중(거래명세서 출력)을 먼저 거쳐야 합니다.";
        };
    }

    private static String unshipMessage(DsreShipResult r) {
        return switch (r.outcome()) {
            case CHANGED -> null;
            case NOT_FOUND -> "DSRE2에 해당 신청이 없습니다.";
            case DATE_ONLY, SKIPPED -> OrderState.labelOf(r.beforeState())
                    + " 상태라 DSRE에서 되돌릴 발송완료가 없습니다.";
        };
    }

    /**
     * 위탁출고용 발송 건 확보(2026-09-30 검수피드백 p.12 — "위탁출고도 실제 출고가 발생하므로
     * 작업요청서에 포함되어야 합니다").
     *
     * <p>★<b>{@link #ensureFor(Sale)} 를 못 쓴다.</b> 그쪽은 매출 행에서 키를 뽑는데
     * 위탁출고는 <b>매출이 아직 없다</b>(미결 상태로 나가고 정산 때 매출이 선다).
     * 그래서 같은 키(일자·거래처·학교·분류)를 직접 받아 만든다.
     *
     * <p>묶는 단위가 같으므로 <b>같은 날 같은 학교로 정상출고와 위탁출고가 함께 나가면
     * 한 발송 건이 된다</b> — 물류는 그것을 한 상자에 싣는다. 그게 맞다.
     */
    @Transactional
    public Shipment ensureForConsignment(java.time.LocalDate tradeDate, Partner partner,
                                         String schoolCode, String schoolName, String tradeClass) {
        return shipmentRepository.findKey(tradeDate, partner.getId(), schoolCode, tradeClass)
                .orElseGet(() -> shipmentRepository.save(
                        Shipment.of(tradeClass, tradeDate, partner, schoolCode, schoolName, null)));
    }

    /**
     * 작업요청서 출력 처리 → '출력' 표시. 근거: 작업요청서_신청비교.vb:280.
     *
     * <p>재출력해도 최초 시각을 덮지 않는다 — 이 기록의 의미는 "언제 처음 작업지시가 나갔나"다.
     *
     * @return 이번 호출로 실제 표시가 붙었으면 true(이미 출력된 건이면 false)
     */
    @Transactional
    public boolean markPrinted(Long shipmentId) {
        boolean changed = getOrThrow(shipmentId).markPrinted(LocalDateTime.now());
        if (changed) {
            statusHistoryService.record(StatusEntityType.SHIPMENT, shipmentId,
                    FIELD_PRINTED, false, true, null);
        }
        return changed;
    }

    /**
     * 출력 표시를 되돌린다(B-14). 근거: 프론트 회신 2026-09-17 —
     * "되돌리기는 아직 준비 중입니다 — 한번 올린 출력 기록은 내릴 수 없습니다".
     *
     * <p>‼️<b>사유를 반드시 받는다.</b> 이 기록의 의미는 "언제 처음 작업지시가 나갔나"이고,
     * 되돌리기는 그 답을 지우는 행위다. 사유 없이 내릴 수 있게 하면 나중에
     * "이 건은 왜 지시가 안 나간 걸로 되어 있나"에 아무도 답하지 못한다.
     * 값은 {@code shipment}에서 지우되 <b>누가·언제·왜</b>는 {@code status_history}에 남는다.
     *
     * @return 실제로 내려갔으면 true(애초에 출력 전이면 false — 이미 원하는 상태다)
     */
    @Transactional
    public boolean revertPrinted(Long shipmentId, String reason) {
        boolean changed = getOrThrow(shipmentId).revertPrinted();
        if (changed) {
            statusHistoryService.record(StatusEntityType.SHIPMENT, shipmentId,
                    FIELD_PRINTED, true, false, orNoReason(reason));
        }
        return changed;
    }

    /**
     * 작업 확인 표시(B-14). 출력 다음 단계로, 작업결과의 '확인' ○가 된다.
     *
     * <p>재확인해도 최초 시각·처리자를 덮지 않는다 — 출력과 같은 규칙이다.
     *
     * @return 이번 호출로 표시가 붙었으면 true(이미 확인된 건이면 false)
     */
    @Transactional
    public boolean acknowledge(Long shipmentId) {
        boolean changed = getOrThrow(shipmentId)
                .acknowledge(currentAuditor.username(), LocalDateTime.now());
        if (changed) {
            statusHistoryService.record(StatusEntityType.SHIPMENT, shipmentId,
                    FIELD_ACKNOWLEDGED, false, true, null);
        }
        return changed;
    }

    /**
     * 사유가 비었을 때 이력에 남길 문구.
     *
     * <p>9/27 회의(A-3)로 화면에서 사유 칸이 빠져 빈 값이 정상이 됐다.
     * 그래도 <b>null 로 두지 않는다</b> — 이력이 비어 있으면 "안 적은 것"인지
     * "기록이 안 된 것"인지 나중에 가릴 수 없다.
     */
    private static String orNoReason(String reason) {
        return (reason == null || reason.isBlank()) ? "(사유 미입력)" : reason.trim();
    }

    /** 확인 표시를 되돌린다. 출력 되돌리기와 같은 이유로 사유를 받는다(9/27부터 선택). */
    @Transactional
    public boolean revertAcknowledged(Long shipmentId, String reason) {
        boolean changed = getOrThrow(shipmentId).revertAcknowledged();
        if (changed) {
            statusHistoryService.record(StatusEntityType.SHIPMENT, shipmentId,
                    FIELD_ACKNOWLEDGED, true, false, orNoReason(reason));
        }
        return changed;
    }

    /**
     * 물류 발송정보 입력. 박스 수·발송일·발송메모(작업요청서.vb:915)에
     * 26p 확정 항목인 <b>발송구분·수령인·송장</b>을 함께 받는다.
     *
     * <p>보내지 않은 항목은 건드리지 않는다 — 박스 수만 고치려다 발송일이나
     * 수령인이 지워지면 택배가 누구에게 가는지 알 수 없게 된다.
     */
    @Transactional
    public List<DsreStateSync> updateShipping(Long shipmentId, ShippingUpdateRequest req) {
        Shipment s = getOrThrow(shipmentId);
        s.updateShipping(req.boxCount(), req.sentDate(), req.sendMemo());
        s.updateDelivery(req.deliveryType(), req.receiverName(), req.receiverPhone());
        s.updateTracking(req.courierName(), req.trackingNo());
        // 발송일이 들어오면 = 발송 처리. 묶인 신청의 DSRE 상태도 발송완료로(2026-10-23).
        return (req.sentDate() == null) ? List.of() : shipToDsre(s, req.sentDate());
    }

    /**
     * 송장번호 엑셀 일괄 등록. 택배사에 넘긴 목록에 송장번호가 채워져 돌아오는 흐름을 그대로 받는다.
     *
     * <p>양식 3컬럼: <b>발송건ID · 택배사 · 송장번호</b>. 발송건ID로 맞추는 이유는,
     * 거래처·학교 이름으로 맞추면 같은 날 같은 학교에 두 건이 나갈 때 어느 쪽인지 가릴 수 없기 때문이다
     * (내보내는 양식에 ID를 넣어 두므로 담당자가 따로 채울 필요가 없다).
     *
     * <p>한 행이 실패해도 나머지는 반영한다 — 택배사 파일에 우리가 모르는 행이 섞여 있다고
     * 전체가 취소되면, 담당자는 어느 줄이 문제인지 모른 채 처음부터 다시 해야 한다.
     * ⚠️택배사 API를 부르지 않는다(연동 여부는 물류팀 인터뷰 회신 대기).
     */
    @Transactional
    public TrackingUploadResponse uploadTracking(MultipartFile file) {
        List<TrackingUploadResponse.Line> lines = new ArrayList<>();
        int updated = 0;
        int failed = 0;

        com.daesung.sales.common.excel.ExcelSheetReader.assertReadable(file);
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {   // 0행=헤더
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Long id = longOrNull(row.getCell(0));
                if (id == null) {
                    continue;   // 발송건ID가 숫자가 아니면 설명행/빈행 → 스킵
                }
                int rowNo = r + 1;
                String courier = str(row.getCell(1));
                String tracking = str(row.getCell(2));
                try {
                    if (tracking == null || tracking.isBlank()) {
                        throw new BusinessException(ErrorCode.INVALID_INPUT, "송장번호가 비어 있음");
                    }
                    Shipment s = shipmentRepository.findById(id).orElseThrow(() ->
                            new BusinessException(ErrorCode.NOT_FOUND, "발송 건이 없습니다. id=" + id));
                    s.updateTracking(courier, tracking);
                    updated++;
                    lines.add(new TrackingUploadResponse.Line(rowNo, "UPDATED", id, tracking, null));
                } catch (BusinessException e) {
                    failed++;
                    lines.add(new TrackingUploadResponse.Line(rowNo, "ERROR", id, tracking, e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
        }
        return new TrackingUploadResponse(updated, failed, lines);
    }

    private static String str(org.apache.poi.ss.usermodel.Cell c) {
        if (c == null) {
            return null;
        }
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            // 송장번호가 숫자로 들어오면 지수표기(1.23E11)가 되지 않게 정수로 찍는다
            case NUMERIC -> String.valueOf((long) c.getNumericCellValue());
            default -> null;
        };
    }

    private static Long longOrNull(org.apache.poi.ss.usermodel.Cell c) {
        if (c == null) {
            return null;
        }
        try {
            return switch (c.getCellType()) {
                case NUMERIC -> (long) c.getNumericCellValue();
                case STRING -> Long.parseLong(c.getStringCellValue().trim());
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 발송 건 <b>삭제</b>(9/27 회의 항목 20 ② — "확인 되돌리기 → 삭제").
     *
     * <p>레거시가 하던 그대로다 — {@code 작업요청서.vb:1181} 의 우클릭 '삭제'가
     * {@code sendData.isDelete = 2} 를 쓴다(주석: {@code 1:발주처에서 삭제 / 2:물류에서 삭제}).
     * <b>확인 표시를 내리는 것이 아니라 발송 건 자체를 없애는 것</b>이다.
     *
     * <p>★<b>행은 지우지 않는다.</b> 목록에는 남고 화면이 취소선으로 표시한다
     * (레거시 768행이 그렇게 그린다). 숨겨 버리면 "취소된 건"과 "원래 없던 건"이 구분되지 않아
     * 담당자가 같은 발송을 다시 만든다. 대신 출력·확인·발송 처리에서는 빠진다.
     *
     * <p>사유는 <b>선택</b>이다(9/27 A-3). 없으면 이력에 "(사유 미입력)"으로 적는다.
     *
     * @return 이번 호출로 삭제됐으면 true(이미 삭제된 건이면 false — 이미 원하는 상태다)
     */
    @Transactional
    public boolean delete(Long shipmentId, String reason) {
        Shipment s = shipmentRepository.findByIdIncludingDeleted(shipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "발송 건이 없습니다. id=" + shipmentId));
        if (s.getDeletedAt() != null) {
            return false;
        }
        s.markDeleted(currentAuditor.username());
        statusHistoryService.record(StatusEntityType.SHIPMENT, shipmentId,
                "deleted", "false", "true", orNoReason(reason));
        return true;
    }

    /**
     * 삭제된 건이면 거부한다. 출력·확인·발송정보 입력이 공통으로 쓴다 —
     * 레거시도 삭제분을 {@code Continue For} 로 건너뛴다(377·890·1016행).
     */
    private static void assertNotDeleted(Shipment s) {
        if (s.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "삭제된 발송 건입니다. 작업 대상이 아닙니다.");
        }
    }

    private Shipment getOrThrow(Long id) {
        return shipmentRepository.findByIdIncludingDeleted(id)
                .map(s -> {
                    assertNotDeleted(s);
                    return s;
                })
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "발송 건이 없습니다. id=" + id));
    }
}
