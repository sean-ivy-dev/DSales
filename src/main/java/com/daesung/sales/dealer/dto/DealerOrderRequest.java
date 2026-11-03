package com.daesung.sales.dealer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;

/**
 * 특약점 사이트 [새 주문] 탭에서 들어온 주문.
 *
 * <p>근거: 10/27 명세 §1 — "새 주문 탭에서 들어온 주문은 매출프로그램
 * [주문 조회/등록(통합)]에 보여야 한다 … <b>새 탭 주문은 매출 주문으로 저장</b>"
 * (DSRE2 {@code tbl_request_info} 에 담지 않는다).
 *
 * <p>★<b>가격 칸이 없는 것이 설계다.</b> 10/26 명세 §1 — "<b>공급률은 신청 화면에
 * 표시하지도, 반영하지도 않는다. 매출 금액은 매출프로그램 기준으로 계산한다</b>".
 * 정가·공급률·금액을 받을 자리를 아예 두지 않아 사이트가 값을 넘길 수 없게 했다
 * (받아 놓고 무시하면 사이트 쪽은 자기 값이 쓰인다고 믿는다). 단가는 서버가
 * 거래처별 단가(V19) → 도서 마스터 순으로 정한다.
 *
 * <p>★<b>창고·발송구분 칸도 없다.</b> §2-10 — "발송구분 기본은 화물이다 …
 * <b>특약점 사이트에서는 고르지 않는다</b>". 창고도 특약점이 알 바가 아니라
 * 서버가 물류창고를 정한다.
 */
@Schema(name = "DealerOrderRequest", description = "특약점 사이트 새 주문 접수")
public record DealerOrderRequest(

        @Schema(description = """
                주문 거래처 코드(우리 거래처 코드). ‼️이 값을 믿는 근거는 **사이트 서버가
                특약점 로그인 세션을 들고 있다**는 것뿐이다 — 브라우저에서 직접 호출하게
                하면 남의 거래처로 주문을 넣을 수 있다(명세 §10-3 서버간 호출).""",
                example = "A10A195", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String partnerCode,

        @Schema(description = "주문일자. 비우면 오늘", example = "2026-11-02")
        LocalDate orderDate,

        @Schema(description = "학교/학원 코드(선택). 한 거래처가 여러 학교에 나갈 때 쓴다",
                example = "S001")
        String schoolCode,

        @Schema(description = "학교/학원명(선택)", example = "독재자학원")
        String schoolName,

        @Schema(description = "주문 메모(선택)", example = "11월 2주 배송 요청")
        String memo,

        @Schema(description = """
                담당자명(선택). 10/26 §4 "담당자 정보" — "신청 화면에 들어가면 거래처관리에
                저장된 값을 미리 채운다. 사용자는 이 값을 수정할 수 있다." **안 보내면**
                거래처 마스터의 대표자명으로 채운다. 보내면 **그 값 그대로** 저장하고(빈
                문자열이어도 그대로 — "지우고 싶다"는 뜻일 수 있다), **거래처 마스터는
                바꾸지 않는다**("수정한 값은 그 신청 건에만 저장한다").""", example = "홍길동")
        String receiverName,

        @Schema(description = "담당자 연락처(선택). 규칙은 `receiverName` 과 같다",
                example = "010-0000-0000")
        String receiverPhone,

        @Schema(description = "담당자 이메일(선택). 규칙은 `receiverName` 과 같다",
                example = "teacher@example.com")
        String receiverEmail,

        @Schema(description = """
                배송지 우편번호 — 선택. 특약점팀 요청 2026-11-03 §2.
                신청 화면이 학교 주소를 미리 채우고 사용자가 고칠 수 있게 되어 있다.""",
                example = "06236")
        @Size(max = 7) String zipCode,

        @Schema(description = """
                배송지 주소 — 선택. **그 주문에만** 저장된다(거래처·학교 마스터는 안 바뀐다).
                작업요청서가 이 주소로 나간다. 수정(PUT)에서 비워 보내면 기존 값을 유지한다.""",
                example = "서울 강남구 테헤란로 1")
        @Size(max = 300) String address,

        @Schema(description = "주문 도서", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Valid List<Item> items
) {
    @Schema(name = "DealerOrderItem")
    public record Item(

            @Schema(description = """
                    도서코드(우리 상품코드). 사이트가 노출 도서 목록에서 받은 코드를 그대로 보낸다.
                    SET 또는 회차 단위다(10/26 §1 "도서명은 SET 또는 회차 단위").""",
                    example = "B2026001", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String productCode,

            @Schema(description = "수량", example = "30", requiredMode = Schema.RequiredMode.REQUIRED)
            @Positive Integer qty
    ) { }
}
