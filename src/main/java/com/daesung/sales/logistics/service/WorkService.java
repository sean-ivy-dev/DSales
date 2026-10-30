package com.daesung.sales.logistics.service;

import com.daesung.sales.logistics.dto.WorkOrderResponse;
import com.daesung.sales.logistics.dto.WorkResultRow;
import com.daesung.sales.logistics.entity.DeliveryType;
import com.daesung.sales.logistics.entity.Shipment;
import com.daesung.sales.logistics.repository.ShipmentRepository;
import com.daesung.sales.common.audit.CurrentAuditor;
import com.daesung.sales.sale.dto.ShipmentQtyAgg;
import com.daesung.sales.sale.dto.ShipmentWarehouseAgg;
import com.daesung.sales.warehouse.entity.WarehouseType;
import com.daesung.sales.sale.dto.WorkOrderLineAgg;
import com.daesung.sales.sale.repository.SaleRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 물류 작업요청서 · 작업결과 조회.
 *
 * <p>둘 다 <b>발송 건(shipment)에 매출 수량을 붙여</b> 만든다. 발송 건은 진행 상태(출력·박스·발송일)를,
 * 매출은 내용(무엇을 몇 개)을 갖고 있어 한쪽만으로는 화면이 안 된다.
 * 레거시도 같은 구조다 — sendData에 salesData를 조인한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkService {

    private final ShipmentRepository shipmentRepository;
    private final com.daesung.sales.consignment.repository.ConsignmentOutRepository consignmentOutRepository;
    private final SaleRepository saleRepository;
    private final CurrentAuditor currentAuditor;
    /**
     * DSRE 진행상태를 나란히 보여주기 위한 게이트웨이.
     * ‼️<b>선택 주입</b>이다 — 연동이 꺼져 있으면 빈이 없다. 필수로 두면 작업결과 화면이 통째로 못 뜬다.
     */
    private final org.springframework.beans.factory.ObjectProvider<
            com.daesung.sales.dsre.gateway.DsreGateway> dsreGateway;

    /** 발송 건을 찾기 위한 키. 학교·분류가 null일 수 있어 빈 문자열로 정규화해 맞춘다. */
    /**
     * 발송 건별 <b>DSRE 진행상태</b>. 연동이 꺼져 있으면 빈 맵이다.
     *
     * <p>한 발송 건에 신청이 여럿 묶일 수 있다. 그때는 <b>가장 덜 진행된 상태</b>를 쓴다 —
     * 하나라도 아직 안 나갔으면 그 발송 건은 끝난 게 아니기 때문이다.
     * (발송완료 처리도 "묶인 신청이 다 나간 뒤에" 옮기는 것과 같은 결이다.)
     */
    private java.util.Map<Long, com.daesung.sales.dsre.gateway.OrderState> dsreStates(
            List<Shipment> shipments) {
        var gw = dsreGateway.getIfAvailable();
        if (gw == null || shipments.isEmpty()) {
            return java.util.Map.of();
        }
        List<Long> ids = shipments.stream().map(Shipment::getId).toList();
        java.util.Map<Long, java.util.List<Integer>> reqByShipment = new java.util.HashMap<>();
        java.util.Set<Integer> allReq = new java.util.HashSet<>();
        for (Object[] r : shipmentRepository.findReqCdsOf(ids)) {
            Long sid = ((Number) r[0]).longValue();
            Integer reqCd = ((Number) r[1]).intValue();
            reqByShipment.computeIfAbsent(sid, k -> new ArrayList<>()).add(reqCd);
            allReq.add(reqCd);
        }
        if (allReq.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Integer, String> states = gw.statesOf(allReq);

        java.util.Map<Long, com.daesung.sales.dsre.gateway.OrderState> out = new java.util.HashMap<>();
        reqByShipment.forEach((sid, reqCds) -> {
            com.daesung.sales.dsre.gateway.OrderState least = null;
            for (Integer reqCd : reqCds) {
                var st = com.daesung.sales.dsre.gateway.OrderState.ofCode(states.get(reqCd))
                        .orElse(null);
                if (st == null) {
                    continue;
                }
                // ‼️CANCELED 는 진행 순서가 아니라 **별개 종결**이다. ordinal 로 비교하면
                //   삭제된 신청이 '가장 진행된 것'으로 잡혀 나머지를 가린다 — 끼워 넣지 않는다.
                if (st == com.daesung.sales.dsre.gateway.OrderState.CANCELED) {
                    continue;
                }
                if (least == null || st.ordinal() < least.ordinal()) {
                    least = st;
                }
            }
            if (least != null) {
                out.put(sid, least);
            }
        });
        return out;
    }

    private static Long shipmentKey(Shipment s) {
        return s.getId();
    }

    private static String labelOrNull(com.daesung.sales.dsre.gateway.OrderState st) {
        return (st == null) ? null : st.label();
    }

    /**
     * 발송 건과 매출 집계를 잇는 키 — (일자 · 거래처 · 학교 · 분류 · <b>주문</b>).
     *
     * <p>★{@code entryNo}(주문 식별자 = 매출등록번호)가 들어간다. 발송이 주문 단위로 갈린 뒤
     * (10/27 명세 §8-2) 이 키에 주문이 없으면 <b>같은 날 같은 학교의 발송 두 건이 서로의 라인까지
     * 가져가</b> 수량이 배로 잡힌다(실제로 테스트에서 50이 100으로 나왔다).
     *
     * <p>‼️<b>옛 발송은 {@code entryNo} 가 null 이다</b> — 주문 단위가 생기기 전에 만들어졌다.
     * 그 건들은 여러 주문이 한 건에 합쳐져 있어 주문 하나로 되돌릴 수 없으므로,
     * <b>주문을 뺀 네 칸으로 맞춘다</b>({@link #legacyKey}). 소급해 채우면 없던 사실을 만드는 것이다.
     */
    private static String key(LocalDate date, Long partnerId, String schoolCode, String tradeClass,
                              String entryNo) {
        return legacyKey(date, partnerId, schoolCode, tradeClass)
                + "|" + (entryNo == null ? "" : entryNo);
    }

    /**
     * 집계 한 줄을 담을 키 <b>둘</b> — 다섯 칸(주문 포함)과 네 칸(주문 없음).
     *
     * <p>새 발송은 다섯 칸으로 찾아 <b>자기 주문의 것만</b> 가져가고, 옛 발송은 네 칸으로 찾아
     * <b>그 날 그 학교의 것을 합쳐</b> 가져간다(예전 묶음 그대로). 양쪽에 다 담아 두면
     * 한 번 읽은 집계로 두 세대를 모두 맞출 수 있다.
     */
    private static java.util.List<String> bothKeys(LocalDate date, Long partnerId, String schoolCode,
                                                   String tradeClass, String entryNo) {
        String legacy = legacyKey(date, partnerId, schoolCode, tradeClass);
        if (entryNo == null || entryNo.isBlank()) {
            return List.of(legacy);
        }
        return List.of(key(date, partnerId, schoolCode, tradeClass, entryNo), legacy);
    }

    /** 주문이 없던 시절의 키(일자·거래처·학교·분류). 옛 발송을 맞출 때만 쓴다. */
    private static String legacyKey(LocalDate date, Long partnerId, String schoolCode,
                                    String tradeClass) {
        return date + "|" + partnerId + "|" + (schoolCode == null ? "" : schoolCode)
                + "|" + (tradeClass == null ? "" : tradeClass);
    }

    /**
     * 발송 건이 찾아야 할 키. 주문이 있으면 다섯 칸, 없으면(옛 건) 네 칸이다.
     * 맵은 양쪽을 모두 담아 둔다({@link #putBothKeys}).
     */
    private static String lookupKey(com.daesung.sales.logistics.entity.Shipment s) {
        return (s.getEntryNo() == null || s.getEntryNo().isBlank())
                ? legacyKey(s.getTradeDate(), s.getPartner().getId(), s.getSchoolCode(), s.getTradeClass())
                : key(s.getTradeDate(), s.getPartner().getId(), s.getSchoolCode(), s.getTradeClass(),
                        s.getEntryNo());
    }

    /**
     * 작업결과 — 발송 건별 요약(상품군 수량·박스·발송일).
     *
     * <p>'출력'·'완료'는 레거시처럼 <b>날짜가 채워졌는지</b>로 판단한다. 별도 상태 컬럼이 없다.
     */
    public List<WorkResultRow> workResults(LocalDate from, LocalDate to, String tradeClass,
                                           Long partnerId, Boolean printed, Boolean acknowledged,
                                           WarehouseType warehouseType,
                                           List<com.daesung.sales.dsre.gateway.OrderState> statuses) {
        // ★역할별 기본값(발주처 확정 3-2 나): "물류에서 실제 발송하는 상품은 모두 본사물류창고이므로
        //   출고창고는 본사물류창고만 디폴트, 본사 출고담당(관리자)은 전체/본사물류/위탁 선택 가능".
        //   물류는 요청에 무엇을 넣든 본사물류창고로 강제한다 — 프론트도 필터를 관리자 전용으로 만들었고,
        //   두 층의 규칙이 갈리면 화면과 데이터가 어긋난다.
        WarehouseType effectiveType = currentAuditor.hasRole("ADMIN")
                ? warehouseType : WarehouseType.MAIN;
        // 발송 단위별 창고(여러 창고에서 나갔을 수 있다)
        Map<String, List<ShipmentWarehouseAgg>> whByKey = new LinkedHashMap<>();
        for (ShipmentWarehouseAgg a : saleRepository.shipmentWarehouses(from, to)) {
            for (String k : bothKeys(a.getTradeDate(), a.getPartnerId(), a.getSchoolCode(),
                    a.getTradeClass(), a.getEntryNo())) {
                whByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(a);
            }
        }

        Map<String, Map<String, Long>> qtyByKey = new LinkedHashMap<>();
        for (ShipmentQtyAgg a : saleRepository.shipmentQty(from, to)) {
            for (String k : bothKeys(a.getTradeDate(), a.getPartnerId(), a.getSchoolCode(),
                    a.getTradeClass(), a.getEntryNo())) {
                qtyByKey.computeIfAbsent(k, x -> new LinkedHashMap<>())
                        .merge(blankToEtc(a.getTradeClass()), a.getQty(), Long::sum);
            }
        }

        // ★진행상태 필터(발주처 확정 2026-10-23 #17 "조회조건에 진행상태 포함, 여러 개 선택").
        //   ‼️**삭제는 지정할 때만 나온다.** 평소 목록에 섞이면 처리할 수 없는 건이 섞여
        //     담당자가 매번 걸러야 한다. 지울 때만 보여 주는 쪽이 맞다.
        java.util.Set<com.daesung.sales.dsre.gateway.OrderState> wanted =
                (statuses == null || statuses.isEmpty()) ? null : new java.util.HashSet<>(statuses);
        boolean wantCanceled = wanted != null
                && wanted.contains(com.daesung.sales.dsre.gateway.OrderState.CANCELED);

        List<Shipment> found = wantCanceled
                ? shipmentRepository.findAllIncludingDeleted(from, to, tradeClass, partnerId,
                        boolToInt(printed), boolToInt(acknowledged), null)
                : shipmentRepository.search(from, to, tradeClass, partnerId, printed, acknowledged, null);

        // ★DSRE 진행상태는 **한 번에** 읽는다. 행마다 findOrder 를 돌리면 원격 DB 왕복이 수십 번이다.
        //   연동이 꺼져 있으면 빈 맵 — 그 칸만 null 로 나가고 화면은 그대로 뜬다.
        java.util.Map<Long, com.daesung.sales.dsre.gateway.OrderState> dsreByShipment =
                dsreStates(found);

        List<WorkResultRow> rows = new ArrayList<>();
        for (Shipment s : found) {
            if (wanted != null && !wanted.contains(s.progressState())) {
                continue;
            }
            String k = lookupKey(s);
            List<ShipmentWarehouseAgg> whs = whByKey.getOrDefault(k, List.of());

            // 창고 필터. 창고가 기록되지 않은 발송(V40 이전 매출)은 필터를 걸면 판단할 수 없어 제외한다 —
            // 남겨두면 "본사물류창고만" 골랐는데 창고 불명 건이 섞여 나온다.
            if (effectiveType != null
                    && whs.stream().noneMatch(w -> w.getWarehouseType() == effectiveType)) {
                continue;
            }

            Map<String, Long> q = qtyByKey.getOrDefault(k, Map.of());
            long total = q.values().stream().mapToLong(Long::longValue).sum();
            String whNames = whs.stream().map(ShipmentWarehouseAgg::getWarehouseName)
                    .distinct().collect(java.util.stream.Collectors.joining(", "));
            rows.add(new WorkResultRow(s.getId(), s.getTradeClass(), s.getTradeDate(), s.getTradeSeq(),
                    s.getPartner().getCode(), s.getPartner().getName(),
                    s.getSchoolCode(), s.getSchoolName(),
                    s.getPrintedAt() != null,
                    s.getAcknowledgedAt() != null, s.getAcknowledgedBy(),
                    s.progressState(), s.progressState().label(),
                    dsreByShipment.get(shipmentKey(s)),
                    labelOrNull(dsreByShipment.get(shipmentKey(s))),
                    s.getDeletedAt() != null,
                    s.getCompletedAt() != null, s.getSentDate(),
                    whNames.isEmpty() ? null : whNames,
                    q, total, s.getBoxCount(), s.getSendMemo(), s.getMemo()));
        }
        return rows;
    }

    /**
     * 작업요청서 <b>목록(페이지)</b> — 발송 건 + 그 안에 담을 도서 목록.
     *
     * <p>‼️<b>전량 반환은 서버를 죽인다.</b> 이관 뒤 발송 48,755건에서 넓은 기간(2020~2035)을
     * 부르면 <b>17,866건 / 11.6MB / 27.6초</b>였고, 배포 서버(힙 478MB)가
     * <b>OutOfMemoryError</b> 로 내려앉았다(2026-10-19 실측). 화면이 기간을 비우면 그렇게 부른다.
     * → 발송 건을 <b>페이지 단위로</b> 읽고, 줄도 그 페이지 몫만 모은다.
     */
    public org.springframework.data.domain.Page<WorkOrderResponse> workOrdersPage(
            LocalDate from, LocalDate to, String tradeClass, Long partnerId,
            Boolean printed, Boolean acknowledged, DeliveryType deliveryType,
            org.springframework.data.domain.Pageable pageable) {
        var page = shipmentRepository.findPageIncludingDeleted(from, to, tradeClass, partnerId,
                toFlag(printed), toFlag(acknowledged),
                deliveryType == null ? null : deliveryType.name(), pageable);
        List<WorkOrderResponse> rows = buildWorkOrders(page.getContent(), from, to);
        return new org.springframework.data.domain.PageImpl<>(rows, pageable, page.getTotalElements());
    }

    /**
     * 작업요청서 <b>전량</b> — 송장 템플릿처럼 한 번에 다 필요한 경로만 쓴다.
     * 화면 목록은 {@link #workOrdersPage} 를 쓸 것.
     */
    public List<WorkOrderResponse> workOrders(LocalDate from, LocalDate to, String tradeClass,
                                              Long partnerId, Boolean printed, Boolean acknowledged,
                                              DeliveryType deliveryType) {
        // ‼️**발송 건을 먼저 읽는다.** 예전엔 기간 내 매출 전량을 Java 맵으로 올린 다음
        //   발송 건을 훑었다. 그래서 발송이 **0건이어도** 매출 33만 행을 집계·적재했고,
        //   넓은 기간(2020~2035)으로 부르면 0건을 돌려주는 데 **13초**가 걸렸다
        //   (프론트 실측 2026-10-16 B-3 — 같은 시각 매출 조회는 0.2초).
        //   화면이 기간을 비우면 이 넓은 기간으로 부른다.
        //   ★목록의 주인은 발송 건이다. 매출 줄은 그 발송에 붙일 내용일 뿐이라
        //     발송이 없으면 읽을 이유가 없다.
        List<Shipment> shipments = shipmentRepository.findAllIncludingDeleted(from, to, tradeClass,
                partnerId, toFlag(printed), toFlag(acknowledged),
                deliveryType == null ? null : deliveryType.name());
        return buildWorkOrders(shipments, from, to);
    }

    /** 발송 건 묶음에 줄·신청번호를 붙여 응답으로 만든다(페이지·전량 공용). */
    private List<WorkOrderResponse> buildWorkOrders(List<Shipment> shipments,
                                                    LocalDate from, LocalDate to) {
        if (shipments.isEmpty()) {
            return List.of();
        }
        // 매출·위탁 줄도 **발송 건이 실제로 걸쳐 있는 날짜 범위**로만 좁힌다.
        // 요청 기간이 2035년까지여도 발송이 올해 것뿐이면 올해만 읽는다.
        LocalDate minDate = null;
        LocalDate maxDate = null;
        for (Shipment s : shipments) {
            LocalDate d = s.getTradeDate();
            if (d == null) {
                continue;
            }
            if (minDate == null || d.isBefore(minDate)) {
                minDate = d;
            }
            if (maxDate == null || d.isAfter(maxDate)) {
                maxDate = d;
            }
        }
        // 거래일자가 전부 비어 있으면(이론상) 요청 기간을 그대로 쓴다 — 줄을 못 붙이는 것보다 낫다.
        LocalDate lineFrom = (minDate != null) ? minDate : from;
        LocalDate lineTo = (maxDate != null) ? maxDate : to;

        Map<String, List<WorkOrderResponse.Line>> linesByKey = new LinkedHashMap<>();
        for (WorkOrderLineAgg a : saleRepository.workOrderLines(lineFrom, lineTo)) {
            WorkOrderResponse.Line line = new WorkOrderResponse.Line(
                    a.getCatCode(), a.getProductCode(), a.getProductName(),
                    a.getBookRound(), a.getUnitPrice(), a.getSupplyRate(),
                    a.getQty(), a.getAmount() == null ? 0 : a.getAmount());
            for (String k : bothKeys(a.getTradeDate(), a.getPartnerId(), a.getSchoolCode(),
                    a.getTradeClass(), a.getEntryNo())) {
                linesByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(line);
            }
        }

        // ★위탁출고 지시도 같은 키로 합친다(검수피드백 2026-09-30 p.12).
        //   위탁출고는 매출이 없어(미결로 나간다) 매출 기반 목록엔 안 잡힌다 —
        //   그동안 위탁 건은 작업요청서에 **빈 목록**으로 떠서 물류가 뭘 싸야 할지 몰랐다.
        for (var c : consignmentOutRepository.workOrderLines(lineFrom, lineTo)) {
            // 위탁출고는 주문 식별자가 없다 — 네 칸 키에만 담는다.
            linesByKey.computeIfAbsent(
                            legacyKey(c.getTradeDate(), c.getPartnerId(), c.getSchoolCode(),
                                    c.getTradeClass()),
                            k -> new ArrayList<>())
                    .add(new WorkOrderResponse.Line(c.getCatCode(), c.getProductCode(),
                            c.getProductName(), null, c.getUnitPrice(), c.getSupplyRate(),
                            c.getQty() == null ? 0 : c.getQty(), 0));
        }

        // 발송 건별 신청번호(2026-10-01). 한 발송에 신청이 여럿일 수 있어 목록으로 모은다.
        Map<String, List<Integer>> reqCdsByKey = new LinkedHashMap<>();
        for (var r : saleRepository.workOrderReqCds(lineFrom, lineTo)) {
            for (String k : bothKeys(r.getTradeDate(), r.getPartnerId(), r.getSchoolCode(),
                    r.getTradeClass(), r.getEntryNo())) {
                reqCdsByKey.computeIfAbsent(k, x -> new ArrayList<>()).add(r.getReqCd());
            }
        }

        List<WorkOrderResponse> out = new ArrayList<>();
        // ★삭제된 건도 함께 읽는다(레거시와 같다 — 취소선으로 보여주고 작업 대상에서만 뺀다).
        for (Shipment s : shipments) {
            List<WorkOrderResponse.Line> lines = linesByKey.getOrDefault(lookupKey(s), List.of());
            long total = lines.stream().mapToLong(WorkOrderResponse.Line::qty).sum();
            DeliveryType dt = s.getDeliveryType();
            out.add(new WorkOrderResponse(s.getId(), s.getTradeClass(), s.getTradeDate(),
                    s.getTradeSeq(),
                    s.getPartner().getCode(), s.getPartner().getName(),
                    s.getSchoolCode(), s.getSchoolName(),
                    s.getDeletedAt() != null,
                    reqCdsByKey.getOrDefault(lookupKey(s), List.of()),
                    s.getPrintedAt() != null,
                    s.getAcknowledgedAt() != null, s.getAcknowledgedBy(),
                    s.getBoxCount(), s.getSentDate(), s.getSendMemo(),
                    dt, (dt == null) ? null : dt.label(),
                    s.getReceiverName(), s.getReceiverPhone(),
                    s.getCourierName(), s.getTrackingNo(),
                    total, lines));
        }
        return out;
    }

    /** 매출구분이 비어 있는 상품은 '기타'로 묶는다 — 빈 키로 두면 화면에 이름 없는 칸이 생긴다. */
    private static String blankToEtc(String tradeClass) {
        return (tradeClass == null || tradeClass.isBlank()) ? "기타" : tradeClass;
    }

    /**
     * 네이티브 쿼리에 넘길 3-상태 플래그. null=전체 / true=1 / false=0.
     * ‼️JPQL 과 달리 네이티브에서는 Boolean 을 그대로 비교하면 드라이버·DB 마다 다르게 풀려
     * "전체"가 조용히 "false 만"으로 좁혀진다. 숫자로 고정한다.
     */
    private static Integer toFlag(Boolean v) {
        return (v == null) ? null : (v ? 1 : 0);
    }

    /** 네이티브 쿼리는 Boolean 을 못 받는다 — 0/1 로 바꾼다. null 은 그대로 '전체'다. */
    private static Integer boolToInt(Boolean b) {
        return (b == null) ? null : (b ? 1 : 0);
    }
}
