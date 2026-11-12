package com.daesung.sales.auth.service;

import com.daesung.sales.auth.dto.AuthDtos.LoginRequest;
import com.daesung.sales.auth.dto.AuthDtos.TokenResponse;
import com.daesung.sales.auth.dto.AuthDtos.UserCreateRequest;
import com.daesung.sales.auth.dto.AuthDtos.UserResponse;
import com.daesung.sales.auth.entity.AppUser;
import com.daesung.sales.auth.entity.Role;
import com.daesung.sales.auth.jwt.JwtProvider;
import com.daesung.sales.auth.repository.AppUserRepository;
import com.daesung.sales.common.exception.BusinessException;
import com.daesung.sales.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증(로그인/재발급/로그아웃) + 계정 생성. access(JWT)+refresh(DB 저장·회전).
 * 비밀번호 BCrypt(평문 금지). refresh는 해시 저장, 재발급 시 회전, 로그아웃 시 무효화.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository userRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    /** 최초 관리자 부트스트랩. 사용자가 하나도 없을 때만 허용(이후 400). */
    @Transactional
    public UserResponse bootstrapAdmin(UserCreateRequest req) {
        if (userRepository.count() > 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 계정이 존재합니다. 관리자 계정으로 생성하세요.");
        }
        return toUserResponse(saveUser(req.username(), req.password(), req.name(), Role.ADMIN));
    }

    /** 계정 생성(관리자 전용 — SecurityConfig에서 ROLE_ADMIN 강제). */
    @Transactional
    public UserResponse createUser(UserCreateRequest req) {
        return toUserResponse(saveUser(req.username(), req.password(), req.name(), req.role()));
    }

    private AppUser saveUser(String username, String rawPassword, String name, Role role) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 사용 중인 아이디입니다: " + username);
        }
        return userRepository.save(AppUser.create(username, passwordEncoder.encode(rawPassword), name, role));
    }

    /** 로그인. 아이디/비번 검증 후 access+refresh 발급. */
    @Transactional
    public TokenResponse login(LoginRequest req) {
        AppUser user = userRepository.findByUsername(req.username())
                .filter(AppUser::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED,
                        "아이디 또는 비밀번호가 올바르지 않습니다."));
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        return issueTokens(user);
    }

    /**
     * refresh 토큰으로 재발급(회전 + 재사용 탐지). 유효하면 기존 폐기 후 새 access+refresh 발급.
     * 이미 회전 폐기된 토큰이 다시 들어오면(탈취 의심) 해당 사용자 전체 세션을 무효화한다.
     */
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        String hash = sha256(refreshToken);
        Long userId = refreshTokenStore.findUserId(hash);  // 만료(TTL)·무효면 null
        if (userId == null) {
            // 재사용 탐지: 이미 회전으로 폐기된 토큰이면 탈취로 간주 → 전체 세션 무효화
            Long reusedUserId = refreshTokenStore.findUsedUserId(hash);
            if (reusedUserId != null) {
                refreshTokenStore.revokeAllByUser(reusedUserId);
                throw new BusinessException(ErrorCode.UNAUTHORIZED,
                        "재사용된 refresh 토큰이 감지되어 보안을 위해 전체 로그아웃 처리되었습니다. 다시 로그인하세요.");
            }
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "만료되었거나 유효하지 않은 refresh 토큰입니다.");
        }
        AppUser user = userRepository.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "계정을 찾을 수 없습니다."));
        refreshTokenStore.rotateOut(hash, userId); // 회전: 기존 폐기 + 사용됨 마커(재사용 탐지)
        return issueTokens(user);
    }

    /** 현재 로그인 사용자 정보(프론트 메뉴 노출 제어용). */
    @Transactional(readOnly = true)
    public UserResponse me(String username) {
        return userRepository.findByUsername(username)
                .map(AuthService::toUserResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "계정을 찾을 수 없습니다."));
    }

    /** 로그아웃. 해당 사용자의 모든 refresh 토큰 무효화. */
    @Transactional
    public void logout(String username) {
        userRepository.findByUsername(username)
                .ifPresent(u -> refreshTokenStore.revokeAllByUser(u.getId()));
    }

    /**
     * refresh 토큰만으로 로그아웃한다 — <b>access 토큰이 만료된 뒤에도</b> 끊어낼 수 있어야 한다.
     *
     * <p>‼️예전엔 로그아웃이 인증을 요구해서, 토큰이 만료된 상태로 로그아웃하면 401 이 나고
     * <b>재로그인 쿠키(14일)가 그대로 남았다</b>. 공용 PC 에서 다음 사람이 앞사람 세션을
     * 이어받을 수 있었다(프론트 실측 2026-10-22).
     *
     * <p>회전으로 이미 폐기된 토큰({@code findUsedUserId})도 받아 준다 — 그 토큰을 들고 있다는 것
     * 자체가 그 계정의 세션을 쥐고 있었다는 뜻이라, 끊어 주는 쪽이 안전하다.
     * 토큰이 아예 모르는 값이면 아무것도 하지 않는다(쿠키 삭제는 컨트롤러가 무조건 한다).
     */
    public void logoutByRefreshToken(String refreshToken) {
        String hash = sha256(refreshToken);
        Long userId = refreshTokenStore.findUserId(hash);
        if (userId == null) {
            userId = refreshTokenStore.findUsedUserId(hash);
        }
        if (userId != null) {
            refreshTokenStore.revokeAllByUser(userId);
        }
    }

    private TokenResponse issueTokens(AppUser user) {
        String access = jwtProvider.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshRaw = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        refreshTokenStore.save(user.getId(), sha256(refreshRaw));
        return new TokenResponse(access, refreshRaw, "Bearer",
                user.getUsername(), user.getRole().name(), jwtProvider.accessMinutes());
    }

    private static UserResponse toUserResponse(AppUser u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getName(), u.getRole(), u.isActive());
    }

    /**
     * 계정 사용 여부 변경(관리자 전용).
     *
     * <p>★<b>지우지 않고 끈다.</b> 감사컬럼(created_by/updated_by)이 아이디를 가리켜서
     * 행을 지우면 과거 기록의 작성자를 잃는다. 그래서 DELETE가 아니라 이 경로다.
     *
     * <p>막아 두는 두 가지 —
     * <ul>
     *   <li><b>자기 자신</b>은 못 끈다. 끄는 순간 되돌릴 사람이 없어진다.</li>
     *   <li><b>마지막 활성 관리자</b>도 못 끈다. 관리자가 0이 되면 계정 관리·권한 표를
     *       아무도 못 고쳐 서버에 직접 손대야 복구된다.</li>
     * </ul>
     *
     * <p>‼️끌 때 refresh를 모두 폐기한다. access 토큰은 stateless라 만료(30분)까지 살아 있어
     * <b>즉시 차단되지는 않는다</b> — 재발급을 막아 그 창을 30분으로 묶는 것이 최선이다.
     */
    @Transactional
    public UserResponse setActive(Long userId, boolean active, String actorUsername) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "계정이 없습니다. id=" + userId));
        if (user.isActive() == active) {
            return toUserResponse(user);   // 멱등 — 같은 요청을 두 번 보내도 오류가 아니다
        }
        if (!active) {
            if (user.getUsername().equals(actorUsername)) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "자기 계정은 중지할 수 없습니다.");
            }
            if (user.getRole() == Role.ADMIN && userRepository.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "마지막 관리자 계정은 중지할 수 없습니다. 다른 관리자를 먼저 만드세요.");
            }
            user.deactivate();
            refreshTokenStore.revokeAllByUser(user.getId());   // 재발급 차단
        } else {
            user.activate();
        }
        return toUserResponse(user);
    }

    /**
     * 관리자 비밀번호 초기화(발주처 2026-11-12 ③ — "잊은 경우는 관리자 초기화로 처리").
     *
     * <p>★그 사용자의 refresh 를 모두 폐기한다 — 비밀번호가 바뀌었는데 예전 세션이 14일 동안
     * 재발급으로 이어지면 초기화한 의미가 없다. access 는 만료(30분)까지 남는다(계정 중지와 같다).
     */
    @Transactional
    public UserResponse resetPassword(Long userId, String newPassword) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "계정이 없습니다. id=" + userId));
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenStore.revokeAllByUser(user.getId());
        return toUserResponse(user);
    }

    /**
     * 본인 비밀번호 변경(발주처 2026-11-12 ③). 지금 비밀번호가 맞아야 한다 —
     * 자리를 비운 사이 열린 화면으로 남이 바꾸는 것을 막는다.
     *
     * <p>다른 곳에 남은 세션(refresh)은 모두 끊는다. 지금 화면도 access 만료(30분) 뒤에는
     * 새 비밀번호로 다시 로그인해야 한다.
     */
    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "계정이 없습니다."));
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 비밀번호가 맞지 않습니다.");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "새 비밀번호가 지금 비밀번호와 같습니다.");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenStore.revokeAllByUser(user.getId());
    }

    /** refresh 토큰은 원문 대신 SHA-256 해시로 저장. */
    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "토큰 해시 실패");
        }
    }
}
