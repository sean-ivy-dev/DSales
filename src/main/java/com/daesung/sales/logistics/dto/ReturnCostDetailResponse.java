package com.daesung.sales.logistics.dto;

import com.daesung.sales.dsre.gateway.LogisMode;
import com.daesung.sales.dsre.gateway.ReturnCostDetailRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 회수 물류비 명세 + 소계(자재구분별·구분별) + 총계. */
@Schema(name = "ReturnCostDetailResponse", description = "회수 작업비 명세")
public record ReturnCostDetailResponse(
        @Schema(description = "시작일") LocalDate fromDate,
        @Schema(description = "종료일") LocalDate toDate,
        @Schema(description = "구분 ALL/NORMAL/ACCIDENT") LogisMode mode,
        @Schema(description = "행 명세") List<ReturnCostDetailRow> rows,

        @Schema(description = """
                자재구분별 소계. ★합은 `GET /logistics-costs/return` 총계와 같다 —
                같은 단가 규칙으로 계산하기 때문이다.""")
        List<Subtotal> byType,

        @Schema(description = "구분(사고/반품)별 소계") List<Subtotal> byMode,

        @Schema(description = """
                **상품(분류)별 소계** — 발주처 2026-11-23 4번 8쪽
                "회수전체 화면도 출고전체처럼 좌측 시행 트리 + 상품·시행·거래처별 소계".
                `key` 는 `상품코드 상품명`, 마스터가 비면 `(미지정)`.""")
        List<Subtotal> byProduct,

        @Schema(description = "**시행별 소계** — `key` 는 `시행코드 시행명`") List<Subtotal> byExam,

        @Schema(description = "**거래처별 소계** — `key` 는 `거래처코드 거래처명`") List<Subtotal> byPartner,
        @Schema(description = "총 수량") long totalQty,
        @Schema(description = "총 금액") long totalAmount
) {
    @Schema(name = "ReturnCostSubtotal")
    public record Subtotal(
            @Schema(description = "구분 값") String key,
            @Schema(description = "수량 합") long qty,
            @Schema(description = "금액 합") long amount
    ) {
    }

    public static ReturnCostDetailResponse of(LocalDate from, LocalDate to, LogisMode mode,
                                              List<ReturnCostDetailRow> rows) {
        Map<String, long[]> byType = new LinkedHashMap<>();
        Map<String, long[]> byMode = new LinkedHashMap<>();
        // ★세 축을 함께 낸다(발주처 4번 8쪽). 행이 이미 상품코드 → 학년 → 시행 → 거래처 순으로
        //   정렬돼 오므로 LinkedHashMap 이 **그 순서**를 유지한다 — 화면이 다시 정렬할 필요가 없다.
        Map<String, long[]> byProduct = new LinkedHashMap<>();
        Map<String, long[]> byExam = new LinkedHashMap<>();
        Map<String, long[]> byPartner = new LinkedHashMap<>();
        long qty = 0;
        long amt = 0;
        for (ReturnCostDetailRow r : rows) {
            accumulate(byType, r.materialType(), r.qty(), r.amount());
            accumulate(byMode, r.mode(), r.qty(), r.amount());
            accumulate(byProduct, label(r.productCode(), r.productName()), r.qty(), r.amount());
            accumulate(byExam, label(examCode(r), r.examName()), r.qty(), r.amount());
            accumulate(byPartner, label(r.partnerCode(), r.partnerName()), r.qty(), r.amount());
            qty += r.qty();
            amt += r.amount();
        }
        return new ReturnCostDetailResponse(from, to, mode, rows,
                toList(byType), toList(byMode),
                toList(byProduct), toList(byExam), toList(byPartner), qty, amt);
    }

    /** 시행코드 0 은 「없음」이다 — 사고 중 신청이 지워진 건이 그렇게 온다. */
    private static String examCode(ReturnCostDetailRow r) {
        return (r.examCode() == 0) ? null : String.valueOf(r.examCode());
    }

    /**
     * 소계 키 — {@code 코드 이름}. 둘 다 없으면 {@code (미지정)}.
     *
     * <p>★코드만 쓰면 화면에서 무엇인지 알 수 없고, 이름만 쓰면 같은 이름이 여럿일 때 합쳐진다.
     * 출고 명세 소계가 같은 모양이라 두 화면이 같게 읽힌다.
     */
    private static String label(String code, String name) {
        boolean hasCode = code != null && !code.isBlank();
        boolean hasName = name != null && !name.isBlank();
        if (!hasCode && !hasName) {
            return "(미지정)";
        }
        if (!hasName) {
            return code.trim();
        }
        return hasCode ? code.trim() + " " + name.trim() : name.trim();
    }

    private static void accumulate(Map<String, long[]> acc, String key, long qty, long amount) {
        long[] v = acc.computeIfAbsent((key == null) ? "미지정" : key, k -> new long[2]);
        v[0] += qty;
        v[1] += amount;
    }

    private static List<Subtotal> toList(Map<String, long[]> acc) {
        List<Subtotal> out = new ArrayList<>();
        acc.forEach((k, v) -> out.add(new Subtotal(k, v[0], v[1])));
        return out;
    }
}
