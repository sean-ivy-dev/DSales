package com.daesung.sales.order.dto;

import com.daesung.sales.logistics.dto.WorkOrderResponse;
import com.daesung.sales.logistics.entity.DeliveryType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 주문조회(통합) 한 줄 — <b>우리 주문</b> 하나다.
 *
 * <p>근거: 10/27 명세 §1 — "조회 대상 … 이를 <b>새 특약점 주문 + 매출에서 수기 등록한
 * 주문</b>으로 바꾼다" · §2-1 "수기 주문과 온라인(특약점) 주문을 함께 보여준다"
 * · §2-2 "등록한 주문이 [주문 조회]에 나오지 않는다(버그) — <b>등록 즉시</b> 보여야 한다".
 *
 * <p>★예전 이 화면은 DSRE2 신청({@code tbl_request_info})을 읽었다. 그래서 매출등록·특약점
 * 주문이 아무리 들어와도 <b>목록에 한 줄도 안 늘었다</b>(§2-2 버그의 정체다). 이제 우리
 * 주문을 읽는다. DSRE 신청 목록은 {@code GET /orders/dsre} 로 남겨 뒀다
 * (§2-3 "숨김 처리한다, <b>삭제하지 않음</b>").
 *
 * <p>한 주문이 학교·분류 때문에 발송 여러 건으로 갈려도 <b>한 줄</b>이다(§3-6) —
 * 갈린 발송 id 는 {@code shipmentIds} 로 함께 준다(물류 화면이 그걸로 집는다).
 */
@Schema(name = "SalesOrderRow", description = "주문조회(통합) 한 줄 = 우리 주문 하나")
public record SalesOrderRow(

        @Schema(description = """
                주문번호. 매출등록 묶음번호(`E-…`)다. ‼️V103 전 등록분·이관분은 묶음번호가
                없어 `#발송id` 로 대신한다 — 그때 (거래처·거래일자·거래순번)으로 묶으면
                서로 무관한 옛 주문이 한 줄로 합쳐진다(옛 행은 거래순번이 1에서 멈춰 있다).""",
                example = "E-20261102-7")
        String orderNo,

        @Schema(description = "거래순번 — 그 거래처의 그 날 몇 번째 주문인가(§3-6)", example = "2")
        int tradeSeq,

        @Schema(description = "주문(거래)일자", example = "2026-11-02")
        LocalDate orderDate,

        @Schema(description = "거래처코드", example = "A10A195") String partnerCode,
        @Schema(description = "거래처명", example = "강남 하람도서") String partnerName,

        @Schema(description = "학교/학원 코드. 주문 안에 여러 학교가 섞이면 비운다")
        String schoolCode,
        @Schema(description = "학교/학원명. 주문 안에 여러 학교가 섞이면 비운다")
        String schoolName,

        @Schema(description = "분류(매출구분). 주문 안에 여러 분류가 섞이면 비운다", example = "교재")
        String tradeClass,

        @Schema(description = """
                진행상태 코드. A 접수완료 / G 상품검수 / S 상품준비중 /
                W 발송준비중 / D 발송완료 / C 삭제.
                ★주문이 발송 여럿으로 갈렸으면 **가장 덜 진행된** 것이다 —
                세 상자 중 둘만 나갔으면 아직 발송완료가 아니다.""", example = "RECEIVED")
        String progressState,

        @Schema(description = "진행상태 명칭", example = "접수완료") String progressStateName,

        @Schema(description = """
                접수경로 코드 — `ONLINE`(특약점 사이트에서 들어옴) / `MANUAL`(수기 등록).
                근거: 10/27 §2-1 "**수기 주문과 온라인(특약점) 주문을 함께 보여주고**".
                ‼️값이 비어 있던 옛 주문은 **수기**로 내려준다 — 특약점 연동이 2026-11 에
                열렸으므로 그 전 주문은 전부 수기다.""", example = "MANUAL")
        String channel,

        @Schema(description = "접수경로 명칭(온라인/수기)", example = "수기") String channelName,

        @Schema(description = "주문 전체가 삭제됐는지. 일부만 삭제면 false(살아 있는 쪽이 이긴다)")
        boolean deleted,

        @Schema(description = "발송구분 코드(COURIER/FREIGHT). 섞이거나 미지정이면 null")
        DeliveryType deliveryType,
        @Schema(description = "발송구분 명칭(택배/화물)") String deliveryTypeName,

        @Schema(description = """
                담당자명 — 특약점팀 확인 2026-11-03 (d). 사이트 [조회/수정]이 수량만 고치고
                담당자 칸을 안 보낼 때, **이 값을 그대로 다시 보내도록** 목록에 싣는다.
                (안 보내도 이미 저장된 값은 유지된다 — 덮지 않는다.)
                발송 건이 여럿이고 값이 서로 다르면 null.""")
        String receiverName,
        @Schema(description = "담당자 연락처 — receiverName 과 같은 규칙") String receiverPhone,
        @Schema(description = "담당자 이메일 — receiverName 과 같은 규칙") String receiverEmail,
        @Schema(description = """
                배송지 우편번호 — 특약점팀 요청 2026-11-03 §2 "목록에도 실어 주세요".
                수정 화면이 이 값을 보여주고 고칠 수 있다.""")
        String zipCode,
        @Schema(description = "배송지 주소 — zipCode 와 같은 규칙") String address,

        @Schema(description = "총 수량") long totalQty,
        @Schema(description = "총 금액(공급가 합계)") long totalAmount,

        @Schema(description = "이 주문으로 생긴 발송 건 id 들(작업요청서 행과 같다)")
        List<Long> shipmentIds,

        @Schema(description = """
                묶인 DSRE 신청번호. **수기·특약점 주문은 빈 목록**이다 —
                DSRE 신청에서 온 옛 주문만 값이 있다.""")
        List<Integer> reqCds,

        @Schema(description = "주문 도서 줄")
        List<WorkOrderResponse.Line> lines
) { }
