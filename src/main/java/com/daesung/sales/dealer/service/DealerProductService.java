package com.daesung.sales.dealer.service;

import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.dealer.dto.DealerProductRow;
import com.daesung.sales.product.entity.Product;
import com.daesung.sales.product.repository.ProductRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 특약점 사이트 <b>신청 화면 노출 도서</b>. 근거: 10/26 특약점 명세 §3 +
 * 특약점팀 전달(2026-11-03) 요청 1번(사이트 최우선 — 이게 없어 신청 자체가 안 된다).
 *
 * <p>노출 판정은 {@link ProductRepository#findDealerVisible} 한 곳에만 있다 —
 * 목록과 접수·수정 검증이 <b>같은 쿼리</b>를 쓴다. 두 곳에서 각자 판정하면
 * "목록에는 보이는데 주문하면 거부" 같은 어긋남이 생긴다(특약점팀 요청 1번 마지막 줄
 * "접수·수정도 같은 계산으로").
 *
 * <p>★캐시를 두지 않는다 — §3 "도서관리에서 바꾸면 사이트에 바로 반영돼야 한다".
 */
@Service
@RequiredArgsConstructor
public class DealerProductService {

    private final ProductRepository productRepository;

    /** 그 거래처에 노출되는 도서 목록. */
    @Transactional(readOnly = true)
    public List<DealerProductRow> visible(Long partnerId) {
        return productRepository.findDealerVisible(partnerId).stream()
                .map(DealerProductRow::from)
                .toList();
    }

    /**
     * 주문에 담긴 도서가 전부 그 거래처에 노출되는지 확인한다. 아니면 한 번에 거부하고
     * <b>어떤 코드가 문제인지</b> 알려준다 — 한 건씩 돌려주면 사이트가 여러 번 고쳐 보내야 한다.
     *
     * <p>노출 목록을 <b>한 번만</b> 읽어 비교한다(도서 수만큼 쿼리하지 않는다).
     */
    @Transactional(readOnly = true)
    public void assertAllVisible(Long partnerId, List<String> productCodes) {
        if (productCodes.isEmpty()) {
            return;
        }
        Set<String> allowed = productRepository.findDealerVisible(partnerId).stream()
                .map(Product::getCode)
                .collect(java.util.stream.Collectors.toSet());
        // 입력 순서를 지키고 중복은 한 번만 — 사이트 화면이 가리키기 쉽게.
        Set<String> rejected = new LinkedHashSet<>();
        for (String code : productCodes) {
            if (code != null && !allowed.contains(code)) {
                rejected.add(code);
            }
        }
        if (!rejected.isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_VISIBLE,
                    "그 거래처에 노출되지 않는 도서입니다: " + String.join(", ", rejected));
        }
    }
}
