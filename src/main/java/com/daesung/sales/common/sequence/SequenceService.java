package com.daesung.sales.common.sequence;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채번(전표번호 순번) 서비스. MySQL 8 시퀀스 부재 → seq_registry 행잠금으로 원자 채번.
 * 상수: SEQ_INVOICE(I 매출)/SEQ_PURGE(P 폐기)/SEQ_CONSIGNMENT(OUT 위탁)/SEQ_COLLECTION(수금)/SEQ_STOCKTAKE(실사).
 * 호출 트랜잭션에 참여(REQUIRED) — UPDATE 행잠금이 커밋까지 유지되어 동시 채번 직렬화.
 */
@Service
@RequiredArgsConstructor
public class SequenceService {

    public static final String SEQ_INVOICE = "seq_invoice_no";
    public static final String SEQ_PURGE = "seq_purge_no";
    public static final String SEQ_CONSIGNMENT = "seq_consignment_no";
    public static final String SEQ_COLLECTION = "seq_collection_no";
    public static final String SEQ_STOCKTAKE = "seq_stocktake_no";

    /** IN: 입고번호. ‼️예전엔 입고에 전표번호가 없어 "무엇을 되돌릴지" 특정할 수 없었다(취소 신설 시 추가). */
    public static final String SEQ_INBOUND = "seq_inbound_no";

    /** 이고 전표(TR-). 없으면 이고를 되돌릴 수 없다 — 취소·삭제가 전표번호로 대상을 찾는다. */
    public static final String SEQ_TRANSFER = "seq_transfer_no";

    /**
     * 매출 <b>등록 묶음</b> 번호(E-). 한 번의 등록(여러 줄)을 가리킨다.
     *
     * <p>★매출번호(I-)와 별개 시퀀스다 — 한 묶음에 줄이 여럿이라 증가 속도가 다르고,
     * 섞으면 "3번 매출"이 묶음인지 줄인지 알 수 없게 된다.
     */
    public static final String SEQ_SALES_ENTRY = "seq_sales_entry_no";

    /** 수금 <b>전표코드</b>(V-). 한 번에 등록한 수금 묶음을 가리킨다(수금번호 C- 와 별개). */
    public static final String SEQ_COLLECTION_VOUCHER = "seq_collection_voucher_no";

    /** 세트 조립·해체 전표(BW-). 이고와 같은 이유. */
    public static final String SEQ_BOMWORK = "seq_bomwork_no";

    /**
     * 거래순번(10/27 피드백 §3-6·8-2) — {@code Shipment.tradeSeq}. "거래처별로 매일
     * 1번부터"라 스코프는 {@code partnerId + ":" + 거래일자}. {@link #nextInScope}로
     * 발송(Shipment) 신규 생성 시 1회만 매긴다(기존엔 필드 기본값 1에서 멈춰 있었다).
     */
    public static final String SEQ_TRADE = "seq_trade_no";

    private final SequenceRepository repository;

    /** 다음 순번(원자적). */
    @Transactional(propagation = Propagation.REQUIRED)
    public long next(String seqName) {
        int updated = repository.bump(seqName);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "채번 시퀀스 없음: " + seqName);
        }
        Long val = repository.current(seqName);
        return (val == null) ? 0L : val;
    }

    /**
     * 범위(scope)별로 1부터 리셋되는 순번(10/27 피드백 §6-1·§3-6·8-2 — "그 수금일자 안에서만
     * 1,2,3…", "거래처별로 매일 1번부터"). 키를 {@code prefix + ":" + scope}로 동적 생성해
     * {@link SequenceRepository#upsertBump}를 쓴다 — {@link #next(String)}과 달리 미리
     * 행을 심어 둘 필요가 없다(스코프가 매일·매 거래처마다 새로 생기기 때문).
     *
     * @param prefix 고정 접두어(이 클래스의 {@code SEQ_*} 상수 중 하나를 쓴다)
     * @param scope  그 안에서 1부터 세는 범위(예: 날짜, 또는 "거래처id:날짜")
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public long nextInScope(String prefix, String scope) {
        String key = prefix + ":" + scope;
        repository.upsertBump(key);
        Long val = repository.current(key);
        return (val == null) ? 0L : val;
    }
}
