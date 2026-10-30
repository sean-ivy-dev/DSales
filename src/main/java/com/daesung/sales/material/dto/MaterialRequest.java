package com.daesung.sales.material.dto;

import com.daesung.sales.product.entity.MaterialType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 자재 등록·수정 요청(33p 세트구성 탭의 자재 마스터). */
public record MaterialRequest(

        @Schema(description = """
                자재코드. ‼️**보내도 무시된다** — 서버가 자동으로 매긴다(10/27 피드백 §11-3 확정,
                "직접 입력하지 않고 자동으로 끌어와 채운다"). **10만 번대 순수 숫자**(첫 자재 `100001`)로,
                DSRE 자재코드(`MAT_CD`, 1~12,334)와 같은 규칙에 대역만 띄운 것이다.
                등록 응답의 `code`에 생성된 값이 담겨 나온다. 생성 후에는 바뀌지 않는다(BOM 매칭이 참조).
                화면에서는 입력란을 없애고 저장 후 받은 값을 보여주면 된다.""",
                example = "100001", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String code,

        @Schema(description = """
                자재명. 상품명·시즌·회차 등 **콘텐츠를 특정할 수 있게** 등록한다.
                시험지는 회차마다 전용 자재이고, OMR·라벨만 여러 세트에 공통으로 쓴다.""",
                example = "2026_D.ARCHIVE 국어 시즌1_01회", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String name,

        @Schema(description = """
                자재구분. **물류 작업비 단가를 고르는 키**라 정확히 선택해야 한다(발주처 강조).
                EXAM_PAPER(시험지)/ANSWER_SHEET(해설지)/OMR/LABEL(라벨)/BOOK(단행본·책자)/ETC.
                ‼️해설지는 목록에서는 시험지와 별개지만 **단가는 시험지를 따른다**.""",
                example = "EXAM_PAPER", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull MaterialType materialType,

        @Schema(description = "사용여부(미지정 시 true)") Boolean useYn,

        @Schema(description = "비고") String memo
) {
}
