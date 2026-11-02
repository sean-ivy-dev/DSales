package com.daesung.sales.logistics.service;

import java.util.LinkedHashMap;
import com.daesung.sales.dsre.gateway.LogisCostDetailRow;
import com.daesung.sales.logistics.dto.LogisCostDetailResponse.MissingProduct;
import com.daesung.sales.logistics.dto.LogisCostDetailResponse.MissingReason;
import com.daesung.sales.logistics.entity.ProductLogisRate;
import com.daesung.sales.logistics.repository.ProductLogisRateRepository;
import com.daesung.sales.material.entity.MaterialBom;
import com.daesung.sales.material.repository.MaterialBomRepository;
import com.daesung.sales.product.entity.MaterialType;
import com.daesung.sales.sale.repository.SaleRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>단독 상품</b>(수불부노출 Y)의 물류 작업비.
 *
 * <p>근거: 발주처 확정(2026-10-23 #10) — "상품별 출처 구분, 기준=도서관리 수불부노출.
 * N(DSRE병행)→DSRE 인원 값. <b>Y(단독)→'인원' 자리에 매출프로그램 출고수량</b>.
 * 병행 상품 두 값 합산 시 이중계산 주의. 계산식·단가 기존 그대로."
 *
 * <h3>계산식은 레거시 그대로다</h3>
 * <pre>
 *   자재비 = Σ(자재별 소요수량 × 출고수량 × 그 자재구분 단가)
 *   노무비 = 출고수량 × (기본작업비 + 출고비)
 * </pre>
 * 다른 것은 <b>'인원' 자리에 무엇이 들어가느냐</b> 하나뿐이다 —
 * DSRE 병행은 신청 인원, 단독은 우리 출고수량.
 *
 * <h3>자재구분 → 단가 (도서관리·제품수불부현황 구조보완 요청안 2026-08-31 §2-1)</h3>
 * <pre>
 *   시험지 → 시험지   OMR → OMR   단행본·책자 → 단행본   라벨 → 라벨
 *   해설지 → **시험지**   ← 별도 해설지 단가는 만들지 않는다(문서 명시)
 * </pre>
 *
 * <h3>‼️이중계산 방지</h3>
 * 병행(N) 상품은 DSRE 쪽에서 이미 집계돼 온다. 여기서는 <b>단독(Y)만</b> 센다 —
 * 한 상품이 양쪽에 다 잡히면 금액이 두 배가 된다. 발주처가 콕 집어 경고한 지점이다.
 *
 * <h3>단가가 없으면 행을 만들지 않는다</h3>
 * 단가 미등록 상품을 0원으로 끼워 넣으면 "물류비가 0인 출고"로 보여 그 사실이 묻힌다.
 * 등록이 필요한 상품은 물류비용등록 화면에서 담당자가 채운다.
 */
@Service
@RequiredArgsConstructor
public class StandaloneLogisCostService {

    private final SaleRepository saleRepository;
    private final ProductLogisRateRepository rateRepository;
    private final MaterialBomRepository materialBomRepository;
    private final com.daesung.sales.product.service.SalesDivisionService salesDivisionService;
    private final com.daesung.sales.inventory.repository.InventoryTxnRepository inventoryTxnRepository;

    /** 단독 상품 행. 비어 있으면 더할 것이 없다는 뜻이다. */
    @Transactional(readOnly = true)
    public List<LogisCostDetailRow> rows(LocalDate from, LocalDate to) {
        List<Object[]> shipped = shippedQty(from, to);
        if (shipped.isEmpty()) {
            return List.of();
        }

        List<LogisCostDetailRow> out = new ArrayList<>();
        for (Object[] r : shipped) {
            Long productId = ((Number) r[0]).longValue();
            String productCode = (String) r[1];
            String productName = (String) r[2];
            String grade = (String) r[3];
            String partnerCode = (String) r[4];
            String partnerName = (String) r[5];
            long qty = ((Number) r[6]).longValue();

            ProductLogisRate rate = rateRepository.findByProductId(productId).orElse(null);
            if (rate == null) {
                continue;   // 단가 미등록 — 0원 행으로 끼워 넣지 않는다(위 주석 참고)
            }

            Breakdown b = breakdown(productId, rate, qty);
            long total = b.total();
            // ‼️**0원 행은 내보내지 않는다.** 단가가 등록돼 있어도 금액이 0이 되는 길이 둘 더 있다 —
            //   ① 자재 구성(세트↔자재 매칭)이 비어 있어 소요수량이 0
            //   ② 작업구분의 기본작업비·출고작업비가 0
            //   실제로 '생명과학 SET'이 단가 50/50/50 인데 자재 0건이라 전부 0원으로 나갔다
            //   (프론트 실측 2026-10-26, 2026-08 단독 7줄 전부 0원).
            //   0원 행은 담당자가 **"작업비 0원 확정"으로 읽는다** — 등록이 덜 된 것과 구분되지 않는다.
            //   빠진 상품은 missingProducts 가 사유와 함께 알려 준다.
            if (total <= 0) {
                continue;
            }

            out.add(new LogisCostDetailRow(
                    null,                   // 신청일 — 기간 합계라 대표 날짜가 없다(DSRE 신청 축 없음)
                    0,                      // 신청번호 — 단독은 DSRE 신청이 없다
                    productCode, productName, grade,
                    0, "(단독)",             // 시행코드·시행명 — DSRE 축이 없다
                    partnerCode, partnerName,
                    null, null, null,       // 지역·도시·학교 — 거래처 축으로만 집계한다
                    b.paperQty() + b.omrQty() + b.etcQty() + b.labelQty(),
                    b.paperQty(), b.paperAmt(), b.omrQty(), b.omrAmt(),
                    b.etcQty() + b.labelQty(), b.etcAmt(),
                    (int) qty,              // ★'인원' 자리에 출고수량
                    b.basicAmt(), b.tradeAmt(),
                    total,
                    "S",                    // 구분 — 단독은 사고(A)가 없다
                    false,                  // 취소 — 단독은 DSRE 발송취소 축이 없다
                    false));                // 자동계산분이다(수기 등록 아님)
        }
        return out;
    }

    /**
     * 작업비가 안 잡혀 빠진 상품 — 코드와 <b>사유</b>를 함께 낸다.
     *
     * <p>★사유를 가르는 이유: 담당자가 할 일이 다르다.
     * "단가 미등록"은 물류비용등록 화면에서 작업구분을 고르면 되고,
     * "자재 구성 없음"은 도서관리 세트구성 탭에서 자재를 매칭해야 한다.
     * 한 덩이로 알리면 어느 화면을 열어야 하는지 모른다.
     *
     * <p>‼️판정은 {@link #rows}의 "0원이면 뺀다"와 <b>같은 계산</b>으로 한다.
     * 전에는 자재 구성이 없으면 무조건 목록에 올렸는데, 기본·출고 작업비가 있으면
     * 그 상품은 행이 정상으로 나간다 — 나간 상품을 "빠졌다"고 알리고 있었다.
     */
    @Transactional(readOnly = true)
    public List<MissingProduct> missingProducts(LocalDate from, LocalDate to) {
        Map<String, Boolean> seen = new HashMap<>();
        List<MissingProduct> out = new ArrayList<>();
        for (Object[] r : shippedQty(from, to)) {
            Long productId = ((Number) r[0]).longValue();
            String code = (String) r[1];
            String name = (String) r[2];
            if (seen.putIfAbsent(code, true) != null) {
                continue;
            }
            var rate = rateRepository.findByProductId(productId).orElse(null);
            if (rate == null) {
                out.add(new MissingProduct(code, name, MissingReason.NO_RATE));
            } else if (breakdown(productId, rate, 1).total() <= 0) {
                // 금액은 출고수량에 비례한다 — 1개에 0원이면 몇 개를 내도 0원이다.
                MissingReason reason = materialBomRepository.findBySet(productId).isEmpty()
                        ? MissingReason.NO_MATERIAL
                        : MissingReason.ZERO_RATE;
                out.add(new MissingProduct(code, name, reason));
            }
        }
        return out;
    }

    /**
     * 출고수량 {@code qty}의 작업비 내역. {@link #rows}와 {@link #missingProducts}가
     * 같은 계산을 쓰게 하려고 뽑았다 — 둘이 갈리면 "나간 행"과 "빠졌다는 목록"이 어긋난다.
     */
    private Breakdown breakdown(Long productId, ProductLogisRate rate, long qty) {
        // 자재 소요수량을 자재구분별로 모은다. 해설지는 시험지 쪽에 합친다.
        long paperQty = 0;
        long omrQty = 0;
        long etcQty = 0;
        long labelQty = 0;
        for (MaterialBom b : materialBomRepository.findBySet(productId)) {
            long need = (long) b.getQtyPerSet() * qty;
            switch (b.getMaterial().getMaterialType()) {
                case EXAM_PAPER, ANSWER_SHEET -> paperQty += need;   // 해설지→시험지 단가
                case OMR -> omrQty += need;
                case LABEL -> labelQty += need;
                default -> etcQty += need;                           // 단행본·기타
            }
        }
        // 라벨은 레거시 상세에도 별도 칸이 없어 '기타'에 합친다(금액은 라벨 단가로 계산).
        return new Breakdown(paperQty, omrQty, etcQty, labelQty,
                paperQty * rate.getPaper(), omrQty * rate.getOmr(),
                etcQty * rate.getEtc() + labelQty * rate.getLabel(),
                qty * rate.getBasic(), qty * rate.getTrade());
    }

    private record Breakdown(long paperQty, long omrQty, long etcQty, long labelQty,
                             long paperAmt, long omrAmt, long etcAmt, long basicAmt, long tradeAmt) {
        long total() {
            return paperAmt + omrAmt + etcAmt + basicAmt + tradeAmt;
        }
    }

    /**
     * 물류 작업비의 <b>수량 한 지점</b> — 매출 출고와 위탁 출고를 합쳐 낸다.
     *
     * <p>근거: 명세 §10 — "출처는 도서관리의 <b>수불부노출</b> 값으로 구분한다.
     * <b>Y(매출프로그램 단독 관리)</b>: <b>출고/반품 조회의 출고 수량을 가져와 계산한다</b>",
     * 그리고 "<b>이중 계산 방지 장치가 필요하다</b>. 같은 상품·수량이 두 경로로 잡히지
     * 않게 한다(위탁출고 §4-5 포함)". 작업지침 4번 — "매입처는 … <b>물류 작업비 계산에서
     * 뺀다</b>".
     *
     * <p>★<b>세 요구가 전부 "출고/반품 조회와 같은 수량이냐"로 모인다.</b> 예전엔 물류비가
     * 자기 조건을 따로 갖고 있어 그 화면과 수량이 어긋났다 —
     * 창고 없는 매출·매출만(배송X)·특강이 물류비에만 들어갔고, 매입처도 안 빠졌고,
     * 위탁은 <b>정산분</b>(예시의 40)만 잡혀 §4-4가 요구하는 원출고(50)와 달랐다.
     * 이제 판정 조각({@code SaleRepository.SHIPPED_PREDICATE})과 특강 목록을
     * 그 화면과 <b>공유</b>한다.
     *
     * <p>두 갈래가 <b>같은 상품·거래처</b>로 겹칠 수 있다(한 달에 일반출고도 위탁출고도 한 곳).
     * 그때는 수량을 <b>합친다</b> — 둘 다 실제로 나간 물량이다. 같은 물량이 두 번 세어지는
     * 경우는 "정산으로 생긴 매출"뿐이고 그건 판정 조각이 이미 뺀다.
     */
    private List<Object[]> shippedQty(LocalDate from, LocalDate to) {
        List<Long> purchasers = inventoryTxnRepository.purchaserPartnerIds();
        boolean noPurchaser = purchasers.isEmpty();   // 빈 컬렉션은 JPQL in 에서 문법 오류
        List<Object[]> rows = new ArrayList<>(saleRepository.standaloneShippedQty(
                from, to, salesDivisionService.lectureDivisionCodes(),
                noPurchaser, noPurchaser ? List.of(0L) : purchasers));
        rows.addAll(saleRepository.consignShippedQty(
                from, to, noPurchaser, noPurchaser ? List.of(0L) : purchasers));

        // 같은 (상품, 거래처)가 두 갈래에 다 있으면 한 행으로 합친다 — 아래 소비부가
        // 상품×거래처 단위로 단가를 매기므로 행이 둘이면 같은 쌍이 두 줄로 나간다.
        Map<String, Object[]> merged = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String key = r[0] + "|" + r[4];
            Object[] prev = merged.get(key);
            if (prev == null) {
                merged.put(key, r.clone());
            } else {
                prev[6] = ((Number) prev[6]).longValue() + ((Number) r[6]).longValue();
            }
        }
        return new ArrayList<>(merged.values());
    }
}
