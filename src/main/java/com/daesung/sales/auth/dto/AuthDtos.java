package com.daesung.sales.auth.dto;

import com.daesung.sales.auth.entity.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 인증 관련 요청/응답 DTO 모음. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @Schema(description = "아이디", example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String username,
            @Schema(description = "비밀번호", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String password) {
    }

    public record RefreshRequest(
            @Schema(description = "refresh 토큰", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String refreshToken) {
    }

    public record UserCreateRequest(
            @Schema(description = "아이디", example = "finance01", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String username,
            @Schema(description = "비밀번호", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String password,
            @Schema(description = "이름", example = "김재무", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String name,
            @Schema(description = "역할(ADMIN/FINANCE/LOGISTICS/SALES/VIEWER)", example = "FINANCE",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Role role) {
    }

    public record TokenResponse(
            @Schema(description = "access 토큰(JWT)") String accessToken,
            @Schema(description = "refresh 토큰") String refreshToken,
            @Schema(description = "토큰 타입") String tokenType,
            @Schema(description = "아이디") String username,
            @Schema(description = "역할") String role,
            @Schema(description = "access 토큰 유효(분)") long accessExpiresInMinutes) {
    }

    public record UserActiveRequest(
            @Schema(description = "true=사용, false=중지", example = "false",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull Boolean active) {
    }

    /** 관리자 비밀번호 초기화 — 관리자가 정한 임시 비밀번호로 바꾼다(발주처 11/12 ③-1·3). */
    public record PasswordResetRequest(
            @Schema(description = "새 비밀번호(8자 이상). 사용자에게 전달한 뒤 본인이 바꾸게 한다",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @jakarta.validation.constraints.Size(min = 8, max = 100) String newPassword) {
    }

    /** 본인 비밀번호 변경(발주처 11/12 ③-2). 지금 비밀번호를 한 번 더 받는다. */
    public record PasswordChangeRequest(
            @Schema(description = "지금 비밀번호", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String currentPassword,
            @Schema(description = "새 비밀번호(8자 이상, 지금 것과 달라야 함)",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @jakarta.validation.constraints.Size(min = 8, max = 100) String newPassword) {
    }

    public record UserResponse(
            @Schema(description = "사용자 id") Long id,
            @Schema(description = "아이디") String username,
            @Schema(description = "이름") String name,
            @Schema(description = "역할") Role role,
            @Schema(description = "사용 여부 — false면 로그인·토큰재발급이 막힌다") boolean active) {
    }
}
