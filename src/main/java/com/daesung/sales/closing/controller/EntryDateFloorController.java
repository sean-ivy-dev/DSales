package com.daesung.sales.closing.controller;

import com.daesung.sales.closing.dto.EntryDateFloorResponse;
import com.daesung.sales.closing.dto.EntryDateFloorUpdateRequest;
import com.daesung.sales.closing.service.PeriodLockService;
import com.daesung.sales.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마감관리 · 입력 가능 최소 거래일. 근거: 「매출 마무리」 §2-3 ㄱ.
 *
 * <p>이 날짜 <b>이전</b>의 거래일로는 매출·수금을 새로 등록하거나 날짜를 그쪽으로 바꿀 수 없다
 * ({@code ENTRY_DATE_TOO_OLD}, 409). 12/10~11 이관이 그 기간을 담당하므로, 그 사이에 손으로
 * 넣으면 같은 거래가 두 번 남는다.
 *
 * <p>★<b>조회는 누구나, 변경은 관리자만.</b> 화면이 입력칸을 미리 막으려면 값을 읽어야 하고,
 * 푸는 쪽은 "그 기간에 손입력을 허용한다"는 결정이라 관리자여야 한다.
 *
 * <p>‼️<b>이미 있는 자료의 취소·삭제·금액수정은 막지 않는다</b> — 이관분(1~11월)을
 * 고치지 못하게 하면 잘못 들어간 자료를 바로잡을 길이 없어진다.
 */
@Tag(name = "마감관리 · 입력 최소일",
        description = "이관 기간으로의 신규 입력 차단(매출·수금). 변경은 관리자 전용")
@RestController
@RequiredArgsConstructor
@RequestMapping("/closing/entry-date-floor")
public class EntryDateFloorController {

    private final PeriodLockService periodLockService;

    @Operation(summary = "입력 가능 최소 거래일 조회",
            description = "`floorDate`가 null이면 제한 없음. `blockedDays`는 오늘 기준으로 "
                    + "막히는 과거 일수(화면 안내용).")
    @GetMapping
    public ApiResponse<EntryDateFloorResponse> get() {
        return ApiResponse.success(periodLockService.getEntryDateFloor());
    }

    @Operation(summary = "[관리자] 입력 가능 최소 거래일 변경",
            description = """
                    즉시 적용된다 — 배포·재시작 불필요(V117 재고현황 전환일과 같은 방식).

                    `floorDate`를 null로 보내면 제한을 **푼다**. 변경·해제는 상태변경
                    이력(`ENTRY_DATE_FLOOR`)에 남는다 — 푸는 순간 이관 기간에 손입력이
                    들어갈 수 있게 되므로 "누가 언제 풀었나"가 남아야 한다.""")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping
    public ApiResponse<EntryDateFloorResponse> update(
            @RequestBody EntryDateFloorUpdateRequest req) {
        return ApiResponse.success(periodLockService.updateEntryDateFloor(req.floorDate()));
    }
}
