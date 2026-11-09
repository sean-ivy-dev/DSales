package com.daesung.sales.material.dto;

import com.daesung.sales.material.entity.MaterialBom;
import com.daesung.sales.product.entity.MaterialType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 자재 매칭 <b>한 줄</b> — 세트를 가로지르는 전역 목록용.
 *
 * <p>{@link MaterialBomResponse}는 세트 하나의 화면(33p)에 맞춰 세트를 머리에 두고
 * 줄을 아래 묶는 모양이다. 여기는 세트가 여럿이라 <b>줄마다 세트를 들고</b> 있어야 한다.
 */
@Schema(name = "MaterialBomFlatRow", description = "자재 매칭 1줄(세트 가로지름)")
public record MaterialBomFlatRow(

        @Schema(description = "매칭 id") Long id,
        @Schema(description = "세트 상품 id") Long setProductId,
        @Schema(description = "세트 도서코드") String setCode,
        @Schema(description = "세트명") String setName,
        @Schema(description = "세트 분류명") String setCatName,
        @Schema(description = "회차 상품 id — **공통이면 null**") Long roundProductId,
        @Schema(description = "구성회차 표기. 공통이면 '공통'", example = "1회") String roundLabel,
        @Schema(description = "공통(회차 미지정) 여부 — F-37 이 가리키는 그 구분") boolean common,
        @Schema(description = "자재 id") Long materialId,
        @Schema(description = "자재코드") String materialCode,
        @Schema(description = "자재명") String materialName,
        @Schema(description = "자재구분") MaterialType materialType,
        @Schema(description = "세트당 소요수량") int qtyPerSet,
        @Schema(description = "회차 반복형 여부(공통 자재 전용)") boolean perRound
) {

    public static MaterialBomFlatRow of(MaterialBom b) {
        boolean common = b.isCommon();
        return new MaterialBomFlatRow(
                b.getId(),
                b.getSetProduct().getId(), b.getSetProduct().getCode(),
                b.getSetProduct().getName(), b.getSetProduct().getCatName(),
                common ? null : b.getRoundProduct().getId(),
                common ? "공통" : b.getRoundProduct().getName(),
                common,
                b.getMaterial().getId(), b.getMaterial().getCode(),
                b.getMaterial().getName(), b.getMaterial().getMaterialType(),
                b.getQtyPerSet(), b.isPerRound());
    }
}
