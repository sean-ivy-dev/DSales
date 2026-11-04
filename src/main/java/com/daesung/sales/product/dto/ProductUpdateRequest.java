package com.daesung.sales.product.dto;

import com.daesung.sales.product.entity.ContentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 상품 수정 요청 DTO. 코드(code)는 불변이라 제외. */
/*
 * ★Boolean(래퍼)인 이유 — primitive였을 때 실제로 데이터가 소실됐다.
 *   JSON에서 필드가 빠지면 primitive는 false가 되어 "안 보냄"과 "false로 설정"을 구분할 수 없다.
 *   그래서 일부 필드만 담아 PUT하면 나머지 플래그가 조용히 전부 꺼졌다 —
 *   특히 stockManaged=false 는 매출을 넣어도 재고가 안 깎이는데 오류도 나지 않는다.
 *   지금은 null = "그대로 두기"로 처리한다(기초정보 변경이력 도입 중 발견, 2026-08-12).
 */
public record ProductUpdateRequest(

        @Schema(description = """
                상품명. **미지정(null)이면 기존 값 유지**.
                ‼️빈 문자열은 거부한다 — 이름 없는 상품은 목록에서 찾을 수 없다.""",
                example = "2026 D.ARCHIVE 국어 세트")
        String name,

        @Schema(description = "콘텐츠구분. 미지정이면 기존 값 유지", example = "SELF")
        ContentType contentType,

        @Schema(description = "세트 여부", example = "true")
        Boolean set,

        @Schema(description = "정가(원)", example = "20000")
        Integer price,

        @Schema(description = "면세 여부", example = "false")
        Boolean taxFree,

        @Schema(description = "학년", example = "고3")
        String grade,

        @Schema(description = "분류코드 — [영문1자][연도4자][영문·숫자1~3자]. 첫 글자=대분류, 연도가 코드에 포함된다",
                example = "M2026A01")
        @CatCode String catCode,

        @Schema(description = "분류명", example = "국어 모의고사")
        String catName,

        @Schema(description = "사용 여부", example = "true")
        Boolean useYn,

        @Schema(description = "세부구분(구 '매출구분' — 발주처 2026-08-31 명칭 통일). 매출액정리·순매출조회 집계기준", example = "정상")
        String salesDivision,

        @Schema(description = "상품년도(32p)", example = "2026")
        Integer productYear,

        @Schema(description = "상품구분(32p, 레거시 bookData.type 원시값)", example = "교재")
        String productType,

        @Schema(description = "기본 공급률(%). 거래처별 매핑이 없을 때 적용되는 바탕값", example = "75")
        @PositiveOrZero @Max(100) Integer supplyRate,

        @Schema(description = "수불부노출 여부", example = "true")
        Boolean ledgerVisible,

        @Schema(description = "단가노출 여부 — 거래처별 단가(34p) 화면 노출. 수불부노출과 별개 축")
        Boolean priceVisible,

        @Schema(description = "Web게시 여부", example = "false")
        Boolean webVisible,

        @Schema(description = """
                지정 거래처 전용(화이트리스트, 미지정 시 false). 근거: 11/04 클라이언트 회신 §1.
                true면 특약점 사이트에 **'열기'로 지정한 거래처에만** 노출하고
                거래처x대분류 매핑은 보지 않는다. 지정은 [도서관리] '거래처별 노출' 탭에서 한다.""",
                example = "false")
        Boolean restrictedVisible,


        @Schema(description = "재고관리 여부(false=모의고사 등 인원기반, 매출 시 재고 미차감)", example = "true")
        Boolean stockManaged,

        @Schema(description = """
                읽을 때 받은 수정 버전(선택). 보내면 그 사이 다른 사람이 고쳤는지 검사해
                어긋나면 409 로 거부한다 — 앞사람 수정을 덮어쓰지 않는다.
                안 보내면 예전처럼 그대로 저장한다(기존 호출 호환).""")
        Long version
) {
}
