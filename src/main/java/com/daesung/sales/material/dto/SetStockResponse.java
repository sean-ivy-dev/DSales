package com.daesung.sales.material.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 세트 재고 현황 — <b>숫자 두 개를 함께</b> 낸다.
 *
 * <p>★★<b>의미가 정정됐다(F-37, 발주처 확정 2026-11-09).</b> 10/22 회신의
 * "세트 재고 = 회차별 시험지 재고 최소값"은 <b>폐기됐고</b>, 새 기준은
 * "재고는 <b>자재 낱장이 기준 원장</b>. SET 재고는 <b>세트 조립·해체로 증감</b>"이다.
 *
 * <p>그래서 두 숫자는 서로 다른 것이고 둘 다 필요하다.
 * <pre>
 *   setStock       지금 **조립돼 있는** 세트 수. 조립 +, 해체 −, 세트 출고 −
 *   assemblableSets 자재로 **앞으로 더 만들 수 있는** 세트 수 = 회차별 가능수량의 최소값
 * </pre>
 * 예전 {@code availableSets} 가 계산한 값은 <b>후자</b>였다 — 계산이 틀린 게 아니라
 * 그것을 "세트 재고"라고 부른 것이 틀렸다. 이름을 바꿨다.
 *
 * <p>★최소값인 이유: 세트는 회차 시험지를 모아 만드는 것이라 <b>가장 모자란 회차</b>가
 * 만들 수 있는 수를 정한다. 100·120·105·111 이면 120 짜리가 많아도 100 세트까지다.
 *
 * <p>‼️재고가 <b>음수</b>인 회차가 있으면 음수로 낸다. 0으로 올려 보여주면
 * "만들 수 있다"로 읽혀 모자란 사실이 묻힌다(재고 음수를 막지 않기로 한 것과 같은 결).
 */
@Schema(name = "SetStockResponse", description = "세트 재고(조립분) + 조립 가능 수량(자재 기준)")
public record SetStockResponse(

        @Schema(description = "세트 상품 id") Long setProductId,
        @Schema(description = "세트 상품코드") String setProductCode,
        @Schema(description = "세트 상품명") String setProductName,

        @Schema(description = """
                ★**지금 조립돼 있는 세트 재고.** 조립 +, 해체 −, 세트 출고 − 로 움직이는 그 숫자다
                (F-37 "SET 재고는 세트 조립·해체로 증감").""")
        int setStock,

        @Schema(description = """
                ★**자재로 앞으로 더 만들 수 있는 세트 수** = 구성 회차별 `가능수량` 의 **최소값**.
                ‼️`setStock` 과 **다른 숫자**다 — 이미 조립된 것과 더 만들 수 있는 것은 별개다.
                구성이 없으면 null — 0과 다르다. 0은 "못 만든다", null 은 "구성이 안 잡혀 있다"다.

                (옛 이름 `availableSets`. 계산은 그대로이고 **이름만** 바뀌었다 —
                그 값을 "세트 재고"로 부른 것이 10/22 기준이고, F-37 로 폐기됐다.)""")
        Integer assemblableSets,

        @Schema(description = "회차별 내역 — 어느 회차가 모자라 세트가 막혔는지 보인다")
        List<Round> rounds
) {
    @Schema(name = "SetStockRound", description = "세트 구성 회차 1건")
    public record Round(
            @Schema(description = "회차 상품 id. 공통 자재(회차 없음)면 null") Long roundProductId,
            @Schema(description = "회차 상품코드. 공통이면 '(공통)'") String roundProductCode,
            @Schema(description = "회차명") String roundProductName,
            @Schema(description = "시험지 자재 id") Long materialId,
            @Schema(description = "시험지 자재코드") String materialCode,
            @Schema(description = "시험지 자재명") String materialName,
            @Schema(description = "현재 시험지 재고") int stockQty,
            @Schema(description = "세트당 소요수량") int qtyPerSet,
            @Schema(description = "이 회차로 만들 수 있는 세트 수 = 재고 ÷ 소요수량(버림)") int possibleSets,
            @Schema(description = """
                    이 회차를 **단독으로** 몇 부 낼 수 있나 = 재고 ÷ **회차당** 소요수량(버림).
                    회차 전용 자재는 세트당 소요와 같고, 공통 반복형은 세트당÷회차수다.
                    F-37 "회차 단독 재고는 해당 회차 자재 낱장 잔여에서 산출해 표시" 가 이 칸이다.""")
            int standaloneQty,
            @Schema(description = "이 회차가 세트 수를 정하고 있나(= 최소값)") boolean bottleneck
    ) {
    }
}
