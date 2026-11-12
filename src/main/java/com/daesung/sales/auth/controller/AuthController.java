package com.daesung.sales.auth.controller;

import com.daesung.sales.auth.config.JwtProperties;
import com.daesung.sales.auth.dto.AuthDtos.LoginRequest;
import com.daesung.sales.auth.dto.AuthDtos.PasswordChangeRequest;
import com.daesung.sales.auth.dto.AuthDtos.PasswordResetRequest;
import com.daesung.sales.auth.dto.AuthDtos.RefreshRequest;
import com.daesung.sales.auth.dto.AuthDtos.TokenResponse;
import com.daesung.sales.auth.dto.AuthDtos.UserActiveRequest;
import com.daesung.sales.auth.dto.AuthDtos.UserCreateRequest;
import com.daesung.sales.auth.dto.AuthDtos.UserResponse;
import com.daesung.sales.auth.service.AuthService;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import com.daesung.sales.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 인증/계정. 실제 경로: /api/v1/auth. login/refresh/bootstrap-admin은 공개, users는 관리자. */
@Tag(name = "인증 · 계정", description = "로그인/토큰재발급/로그아웃 + 계정 생성(RBAC)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    /** refresh 토큰 쿠키명·경로(refresh 계열 엔드포인트에만 전송). */
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final JwtProperties jwtProperties;

    @Operation(summary = "최초 관리자 생성",
            description = "계정이 하나도 없을 때만 허용(부트스트랩). 이후 400. 생성 후 login으로 토큰 발급.")
    @PostMapping("/bootstrap-admin")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> bootstrapAdmin(@Valid @RequestBody UserCreateRequest req) {
        return ApiResponse.success(authService.bootstrapAdmin(req));
    }

    @Operation(summary = "계정 생성(관리자 전용)", description = "역할 지정해 직원 계정 생성. ROLE_ADMIN만 호출 가능.")
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> createUser(@Valid @RequestBody UserCreateRequest req) {
        return ApiResponse.success(authService.createUser(req));
    }

    @Operation(summary = "계정 사용 중지/재개(관리자 전용)",
            description = """
                    계정을 **지우지 않고 끈다**. 끄면 로그인·토큰재발급이 막힌다.
                    감사컬럼(created_by/updated_by)이 아이디를 가리켜 행을 지우면 과거 기록의
                    작성자를 잃기 때문에 DELETE가 아니라 이 경로다.

                    ★자기 계정과 **마지막 활성 관리자**는 끌 수 없다(400) — 끄면 되돌릴 사람이 없어진다.
                    ‼️이미 발급된 access 토큰은 만료(기본 30분)까지 살아 있다. 끌 때 refresh를 모두
                    폐기해 재발급을 막으므로 차단이 완전해지기까지 최대 그 시간이 걸린다.

                    같은 값을 두 번 보내도 오류가 아니다(멱등).""")
    @PutMapping("/users/{id}/active")
    public ApiResponse<UserResponse> setActive(
            @Parameter(description = "사용자 id", required = true) @PathVariable Long id,
            @Valid @RequestBody UserActiveRequest req,
            Principal principal) {
        return ApiResponse.success(
                authService.setActive(id, req.active(), principal == null ? null : principal.getName()));
    }

    @Operation(summary = "비밀번호 초기화(관리자 전용)",
            description = """
                    관리자가 정한 **임시 비밀번호**로 바꾼다. 근거: 발주처 2026-11-12 ③
                    "비밀번호를 잊은 경우는 관리자 초기화로 처리(별도 찾기 화면은 필요 없음)".

                    · 그 사용자의 다른 세션(refresh)은 모두 끊는다 — 예전 비밀번호로 연 세션이
                      재발급으로 이어지지 않게. 이미 발급된 access 는 만료(30분)까지 남는다.
                    · 임시 비밀번호는 관리자가 사용자에게 전달하고, 사용자는 로그인 후
                      `PUT /auth/me/password` 로 바꾼다.
                    · 8자 이상.""")
    @PutMapping("/users/{id}/password")
    public ApiResponse<UserResponse> resetPassword(
            @Parameter(description = "사용자 id", required = true) @PathVariable Long id,
            @Valid @RequestBody PasswordResetRequest req) {
        return ApiResponse.success(authService.resetPassword(id, req.newPassword()));
    }

    @Operation(summary = "내 비밀번호 변경",
            description = """
                    로그인한 사용자가 **본인** 비밀번호를 바꾼다. 근거: 발주처 2026-11-12 ③-2.

                    · 지금 비밀번호가 맞아야 한다(틀리면 400). 새 비밀번호는 8자 이상, 지금 것과 달라야 한다.
                    · 다른 곳에 남은 세션은 모두 끊긴다. 지금 화면도 access 만료(30분) 뒤에는
                      새 비밀번호로 다시 로그인해야 한다 — 프론트는 성공하면 바로 다시 로그인시키는 편이 깔끔하다.""")
    @PutMapping("/me/password")
    public ApiResponse<Void> changeMyPassword(@Valid @RequestBody PasswordChangeRequest req, Principal principal) {
        authService.changeOwnPassword(principal.getName(), req.currentPassword(), req.newPassword());
        return ApiResponse.success(null);
    }

    @Operation(summary = "로그인",
            description = "아이디/비밀번호 → access(JWT)는 바디, refresh는 httpOnly 쿠키로 발급(XSS 방어). "
                    + "응답 바디의 refreshToken은 null.")
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        return ApiResponse.success(issueWithCookie(authService.login(req), response));
    }

    @Operation(summary = "토큰 재발급",
            description = "refresh 토큰(httpOnly 쿠키, 없으면 바디)으로 새 access+refresh 발급(회전). "
                    + "기존 refresh 무효화 + 재사용(탈취) 탐지 시 전체 세션 무효화. 새 refresh는 다시 쿠키로.")
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            @RequestBody(required = false) RefreshRequest req,
            HttpServletResponse response) {
        String token = (cookieToken != null && !cookieToken.isBlank())
                ? cookieToken
                : (req != null ? req.refreshToken() : null);
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "refresh 토큰이 없습니다.");
        }
        return ApiResponse.success(issueWithCookie(authService.refresh(token), response));
    }

    @Operation(summary = "내 정보 조회",
            description = "현재 로그인 사용자의 아이디·이름·역할. 프론트 메뉴 노출 제어(RBAC)용.")
    @org.springframework.web.bind.annotation.GetMapping("/me")
    public ApiResponse<UserResponse> me(Principal principal) {
        return ApiResponse.success(authService.me(principal.getName()));
    }

    @Operation(summary = "로그아웃",
            description = "현재 사용자의 모든 refresh 토큰 무효화 + refresh 쿠키 삭제(access는 만료까지 유효).<br>"
                    + "★<b>access 토큰이 만료돼도 동작한다</b> — 인증이 없으면 refresh 쿠키로 끊는다. "
                    + "쿠키조차 없으면 서버에서 지울 세션은 없지만 쿠키 삭제 응답은 그대로 나간다.")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            Principal principal,
            @CookieValue(name = REFRESH_COOKIE, required = false) String cookieToken,
            HttpServletResponse response) {
        // ‼️예전엔 principal 을 그냥 역참조해서, 토큰이 만료된 상태의 로그아웃이 401 로 끝나고
        //   재로그인 쿠키(14일)가 남았다 — 공용 PC 에서 다음 사람이 세션을 이어받을 수 있었다.
        if (principal != null) {
            authService.logout(principal.getName());
        } else if (cookieToken != null && !cookieToken.isBlank()) {
            authService.logoutByRefreshToken(cookieToken);
        }
        // ★쿠키 삭제는 **무조건** 한다. 서버에서 지울 것을 못 찾았더라도
        //   브라우저에 남은 재로그인 수단은 없애는 것이 이 API 의 최소 보장이다.
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString());
        return ApiResponse.success(null);
    }

    /** refresh는 httpOnly 쿠키로 심고, 응답 바디에서는 refreshToken을 제거(null)한다. */
    private TokenResponse issueWithCookie(TokenResponse t, HttpServletResponse response) {
        Duration maxAge = Duration.ofDays(jwtProperties.refreshTokenDaysOrDefault());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(t.refreshToken(), maxAge).toString());
        return new TokenResponse(t.accessToken(), null, t.tokenType(),
                t.username(), t.role(), t.accessExpiresInMinutes());
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(jwtProperties.cookieSecureOrDefault())
                .sameSite(jwtProperties.cookieSameSiteOrDefault())
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
