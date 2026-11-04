package com.daesung.sales.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 도메인/공통 에러코드. 코드=enum명, message=기본 안내문. */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // --- 공통 ---
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다."),          // 로그인/토큰 실패
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),               // 권한 부족(403)
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 메서드입니다."),  // 경로는 맞고 메서드만 틀린 호출
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),  // 업로드에 JSON을 보낸 경우 등
    // 업로드 크기 초과(프론트 B-6). ‼️**500이 아니라 413**이다 — 담당자가 "서버 오류"로 보면
    //   파일을 줄여 볼 생각을 못 하고 개발에 문의한다. 상한을 함께 알려 준다.
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "업로드 용량이 상한을 넘었습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // --- 도메인(시트 근거) ---
    PERIOD_LOCKED(HttpStatus.CONFLICT, "마감된 월입니다. 수정할 수 없습니다."),   // 월마감(4.9)
    // ⚠️아래 셋은 한때 **차단 → 경고**로 걷어냈던 것들이다(발주처 2026-08-31 회신).
    //   지금 상태가 서로 다르니 섞지 말 것 —
    //
    //   ★NEGATIVE_STOCK  **기본적으로 던지지 않는다**. 발주처 확정(2026-08-31 화면7):
    //                    "재고 음수 차단 로직은 적용되면 안됩니다. 입고 전 출고되는 상품은
    //                     재고 (−)로 처리되며 … 마이너스로 표시되는 게 정상입니다."
    //                    막는 대신 응답 warnings[] 에 같은 코드로 담는다(StockWarning).
    //                    ‼️2026-09-11 사내 점검이 "폐기 초과 통과"를 결함으로 보고 차단을
    //                      넣었다가 발주처 기준으로 되돌렸다 — 다시 넣기 전에 원문을 확인할 것.
    //                    예외적으로 막아야 하면 daesung.inventory.block-negative-stock=true.
    //   RETURN_EXCEEDS   여전히 경고. SalesEntryResponse.warnings ("RETURN_EXCEEDS")
    //   OVER_SETTLEMENT  여전히 경고. SettlementDraftResponse.warnings ("OVER_SETTLEMENT")
    //                    ‼️V56에서 DB 제약·엔티티는 걷어냈는데 정산초안 경로에 차단이
    //                    남아 있었다(2026-09-11 제거). 다시 throw 하지 말 것.
    NEGATIVE_STOCK(HttpStatus.CONFLICT, "재고가 부족합니다."),
    RETURN_EXCEEDS(HttpStatus.CONFLICT, "반품 수량이 출고 잔여를 초과했습니다."),   // ✝️경고로만
    OVER_SETTLEMENT(HttpStatus.CONFLICT, "정산 수량이 미결 잔여를 초과했습니다."),  // ✝️경고로만
    // 같은 Idempotency-Key 의 요청이 아직 처리 중이거나, 저장된 응답을 복원하지 못한 경우.
    // ★이때 **다시 실행하지 않는다** — 그게 이중처리다.
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "이미 처리 중이거나 처리된 요청입니다."),

    // --- 특약점 사이트(서버 간 호출) ---
    // 사이트가 보낸 거래처코드(DSRE 매출코드)로 우리 거래처를 못 찾은 경우.
    // ★INVALID_INPUT 과 **따로 둔 이유**: 사이트가 이 상황에만 "매출 거래처 연결이 되어
    //   있지 않습니다" 안내를 띄운다(특약점팀 전달 2026-11-03 §5). 문구로 분기하면 우리가
    //   메시지를 다듬는 순간 그 안내가 깨지므로, 코드로 구분할 수 있게 한다.
    PARTNER_NOT_LINKED(HttpStatus.BAD_REQUEST, "매출 거래처 연결이 되어 있지 않습니다."),
    // 그 거래처에 노출되지 않는 도서를 주문·수정에 담은 경우(10/26 §3 노출 조건).
    PRODUCT_NOT_VISIBLE(HttpStatus.BAD_REQUEST, "그 거래처에 노출되지 않는 도서입니다."),
    // 발송구분이 '화물'인 건에 송장·택배사를 입력하려 한 경우(11/04 회신 §3).
    // ★전용 코드로 둔 이유: 사이트·화면이 "택배 건만 입력 가능"이라는 안내를 띄우고
    //   입력칸을 비활성화해야 한다 — 문구로 분기하면 메시지를 다듬는 순간 깨진다.
    TRACKING_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "발송구분이 '택배'인 건에만 송장을 입력할 수 있습니다.");

    private final HttpStatus status;
    private final String message;
}
