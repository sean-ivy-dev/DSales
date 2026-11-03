package com.daesung.sales.dealer.dto;

import com.daesung.sales.partner.entity.Partner;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 특약점 신청 화면 담당자 3칸 미리채우기. 근거: 10/26 §4 "신청 화면에 들어가면 거래처관리에
 * 저장된 값을 미리 채운다" + 특약점팀 전달(2026-11-03) 요청 3번.
 *
 * <p>★<b>접수 때 비워 보내면 채우는 값과 같은 출처</b>를 쓴다(거래처 대표자명·연락처1·
 * 이메일1) — 둘이 갈리면 화면에 보인 값과 저장된 값이 달라진다.
 * {@code DealerOrderService.buildEntry} 의 {@code receiverOrDefault} 가 그 자리다.
 *
 * <p>⚠️거래처관리에 <b>담당자 전용 칸</b>이 생기면 그 값으로 바꿔야 한다(고객사 확인 중 —
 * 특약점팀 요청 3번 괄호). 지금은 대표자 칸을 담당자로 쓰고 있다.
 */
@Schema(name = "DealerContactResponse", description = "담당자 정보 기본값(거래처관리 값)")
public record DealerContactResponse(
        @Schema(description = "거래처코드", example = "A10A195") String partnerCode,
        @Schema(description = "거래처명", example = "강남 하람도서") String partnerName,
        @Schema(description = "담당자명(거래처 대표자명). 없으면 null", example = "김대표")
        String receiverName,
        @Schema(description = "연락처(거래처 연락처1). 없으면 null", example = "02-111-2222")
        String receiverPhone,
        @Schema(description = "이메일(거래처 이메일1). 없으면 null", example = "boss@example.com")
        String receiverEmail
) {
    public static DealerContactResponse from(Partner p) {
        return new DealerContactResponse(p.getCode(), p.getName(),
                p.getBossName(), p.getTel1(), p.getEmail1());
    }
}
