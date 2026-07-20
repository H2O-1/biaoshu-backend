package com.bidding.identity.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.UserRepository;
import com.bidding.identity.infrastructure.security.JwtTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

/**
 * 登录相关业务。
 */
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

    /**
     * 登录：查用户 → 校验密码 → 发 Token。
     */
    @Transactional(readOnly = true)
    public LoginResult login(String username, String password) {
        Optional<UserAccount> optionalUser = userRepository.findByUsername(username.trim());
        if (optionalUser.isEmpty()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        UserAccount user = optionalUser.get();
        if (!user.isEnabled()) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已停用");
        }

        boolean passwordOk = passwordEncoder.matches(password, user.getPasswordHash());
        if (!passwordOk) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        CurrentUser currentUser = new CurrentUser(
                user.getId(),
                user.getOrgId(),
                user.getUsername(),
                user.getRoleCodes()
        );
        String token = jwtTokenService.createToken(currentUser);

        LoginResult result = new LoginResult();
        result.setAccessToken(token);
        result.setUserId(user.getId());
        result.setOrgId(user.getOrgId());
        result.setUsername(user.getUsername());
        result.setDisplayName(user.getDisplayName());
        result.setRoleCodes(user.getRoleCodes());
        return result;
    }

    /** 登录成功后返回给前端的数据 */
    public static class LoginResult {
        private String accessToken;
        private long userId;
        private long orgId;
        private String username;
        private String displayName;
        private Set<String> roleCodes;

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public long getOrgId() {
            return orgId;
        }

        public void setOrgId(long orgId) {
            this.orgId = orgId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public Set<String> getRoleCodes() {
            return roleCodes;
        }

        public void setRoleCodes(Set<String> roleCodes) {
            this.roleCodes = roleCodes;
        }
    }
}
