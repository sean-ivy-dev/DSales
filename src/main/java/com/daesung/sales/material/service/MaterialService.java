package com.daesung.sales.material.service;

import com.daesung.sales.common.audit.CurrentAuditor;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.sequence.SequenceService;
import com.daesung.sales.material.dto.MaterialRequest;
import com.daesung.sales.material.dto.MaterialResponse;
import com.daesung.sales.material.entity.Material;
import com.daesung.sales.material.repository.MaterialRepository;
import com.daesung.sales.product.entity.MaterialType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자재 마스터 CRUD(33p 세트구성 탭). 근거: 발주처 구조보완요청안(2026-08-31) —
 * 자재를 BOM에 매번 입력하지 않고 별도 목록에서 관리한다.
 */
@Service
@RequiredArgsConstructor
public class MaterialService {

    /** 생성한 코드가 이미 있을 때 다음 번호로 넘어가는 횟수 상한(과거 수기 등록분 대비). */
    private static final int CODE_RETRY_LIMIT = 50;

    private final MaterialRepository materialRepository;
    private final CurrentAuditor currentAuditor;
    private final SequenceService sequenceService;

    @Transactional(readOnly = true)
    public List<MaterialResponse> search(MaterialType type, Boolean useYn, String keyword) {
        String kw = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        return materialRepository.search(type, useYn, kw).stream()
                .map(MaterialResponse::from)
                .toList();
    }

    @Transactional
    public MaterialResponse create(MaterialRequest req) {
        Material m = Material.create(nextCode(), req.name(), req.materialType(), req.memo());
        if (Boolean.FALSE.equals(req.useYn())) {
            m.update(null, null, false, req.memo());
        }
        return MaterialResponse.from(materialRepository.save(m));
    }

    /**
     * 자재코드를 서버가 매긴다(10/27 피드백 §11-3 — 담당자 직접 입력 폐지).
     *
     * <p>★<b>DSRE와 같은 규칙(순수 숫자 순번)</b>에 대역만 띄운다 —
     * {@link SequenceService#MATERIAL_CODE_BASE} + 순번이라 첫 자재가 {@code 100001}이다.
     * 왜 이 규칙인지는 {@link SequenceService#SEQ_MATERIAL} 주석과 {@code V102} 참고.
     *
     * <p>생성값이라 중복은 원칙적으로 생기지 않지만, 과거 수기 등록분이 우연히 10만 대역
     * 숫자일 수 있어 그때만 다음 번호로 넘어간다(채번은 단조증가라 몇 칸이면 벗어난다).
     */
    private String nextCode() {
        for (int i = 0; i < CODE_RETRY_LIMIT; i++) {
            String code = String.valueOf(
                    SequenceService.MATERIAL_CODE_BASE + sequenceService.next(SequenceService.SEQ_MATERIAL));
            if (!materialRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "자재코드를 매기지 못했습니다 — 이미 쓰이는 코드가 연속 " + CODE_RETRY_LIMIT + "건입니다");
    }

    @Transactional
    public MaterialResponse update(Long id, MaterialRequest req) {
        Material m = getOrThrow(id);
        m.update(req.name(), req.materialType(), req.useYn(), req.memo());
        return MaterialResponse.from(m);
    }

    /**
     * 논리삭제. ★<b>BOM에 매칭돼 있으면 막는다</b> —
     * 지우면 그 세트의 소요수량이 조용히 사라져 물류 작업비가 줄어든다.
     * 쓰지 않으려면 {@code useYn=false}로 두면 목록에서만 빠지고 기존 매칭은 유지된다.
     */
    @Transactional
    public void delete(Long id, long matchedCount) {
        if (matchedCount > 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "세트 구성에 매칭된 자재는 삭제할 수 없습니다(" + matchedCount + "건). "
                            + "매칭을 먼저 해제하거나 사용여부를 N으로 두세요.");
        }
        getOrThrow(id).markDeleted(currentAuditor.username());
    }

    @Transactional(readOnly = true)
    public Material getOrThrow(Long id) {
        return materialRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "자재가 없습니다. id=" + id));
    }
}
