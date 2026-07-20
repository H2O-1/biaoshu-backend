package com.bidding.identity.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.identity.domain.model.RoleCode;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.UserRepository;
import com.bidding.identity.infrastructure.security.JwtTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthAppService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public AuthAppService(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional(readOnly = true)
    public LoginResult login(String username, String password) {
        UserAccount user = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        if (!user.enabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已停用");
        }
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        CurrentUser currentUser = new CurrentUser(
                user.id(),
                user.orgId(),
                user.username(),
                user.roleCodes()
        );
        String token = jwtTokenService.createToken(currentUser);
        return new LoginResult(
                token,
                user.id(),
                user.orgId(),
                user.username(),
                user.displayName(),
                user.roleCodes()
        );
    }

    public record LoginResult(
            String accessToken,
            long userId,
            long orgId,
            String username,
            String displayName,
            java.util.Set<String> roleCodes
    ) {
    }
}
