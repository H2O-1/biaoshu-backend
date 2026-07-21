package com.bidding.identity.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.model.Org;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.UserRepository;
import com.bidding.identity.infrastructure.security.JwtTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

/**
 * 登录、当前用户、改密、退出。
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

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        CurrentUser currentUser = new CurrentUser(
                user.getId(),
                user.getOrgId(),
                user.getUsername(),
                user.getRoleCodes(),
                user.getTokenVersion(),
                user.isMustChangePassword()
        );
        String token = jwtTokenService.createToken(currentUser);

        LoginResult result = toLoginResult(user);
        result.setAccessToken(token);
        return result;
    }

    /**
     * 当前登录用户资料（含组织名称），供前端展示「我是谁、哪个组织」。
     * 刷新页面或本地缓存丢失时可重新拉取。
     */
    @Transactional(readOnly = true)
    public MeResult currentUser() {
        CurrentUser operator = UserContext.get();
        if (operator == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        Optional<UserAccount> optionalUser = userRepository.findById(operator.getUserId());
        if (optionalUser.isEmpty() || !optionalUser.get().isEnabled()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        return toMeResult(optionalUser.get());
    }

    /**
     * 修改密码：校验规则、不能与旧密码相同；成功后清除强制改密标记并吊销旧会话。
     */
    @Transactional
    public void changePassword(String newPassword) {
        CurrentUser operator = UserContext.get();
        if (operator == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        Optional<UserAccount> optionalUser = userRepository.findById(operator.getUserId());
        if (optionalUser.isEmpty() || !optionalUser.get().isEnabled()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或登录已失效");
        }
        UserAccount user = optionalUser.get();

        PasswordPolicy.validateNewPassword(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码不能与当前密码相同");
        }

        String newHash = passwordEncoder.encode(newPassword);
        userRepository.updatePassword(user.getId(), newHash, false);
        // 吊销包含旧密码时期的所有 Token
        userRepository.bumpTokenVersion(user.getId());
    }

    /** 退出登录：提升 token_version，使当前及其他会话立刻失效 */
    @Transactional
    public void logout() {
        CurrentUser operator = UserContext.get();
        if (operator == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        userRepository.bumpTokenVersion(operator.getUserId());
    }

    private LoginResult toLoginResult(UserAccount user) {
        OrgInfo org = loadOrgInfo(user.getOrgId());
        LoginResult result = new LoginResult();
        result.setUserId(user.getId());
        result.setOrgId(user.getOrgId());
        result.setOrgCode(org.code);
        result.setOrgName(org.name);
        result.setUsername(user.getUsername());
        result.setDisplayName(user.getDisplayName());
        result.setPhone(user.getPhone());
        result.setRoleCodes(user.getRoleCodes());
        result.setMustChangePassword(user.isMustChangePassword());
        return result;
    }

    private MeResult toMeResult(UserAccount user) {
        OrgInfo org = loadOrgInfo(user.getOrgId());
        MeResult result = new MeResult();
        result.setUserId(user.getId());
        result.setOrgId(user.getOrgId());
        result.setOrgCode(org.code);
        result.setOrgName(org.name);
        result.setUsername(user.getUsername());
        result.setDisplayName(user.getDisplayName());
        result.setPhone(user.getPhone());
        result.setRoleCodes(user.getRoleCodes());
        result.setMustChangePassword(user.isMustChangePassword());
        return result;
    }

    private OrgInfo loadOrgInfo(long orgId) {
        Optional<Org> optionalOrg = userRepository.findOrgById(orgId);
        if (optionalOrg.isEmpty()) {
            // 数据异常：用户挂了不存在的组织，仍返回登录能力，名称留空避免整单失败
            return new OrgInfo("", "");
        }
        Org org = optionalOrg.get();
        return new OrgInfo(org.getCode(), org.getName());
    }

    private static class OrgInfo {
        private final String code;
        private final String name;

        private OrgInfo(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    /** 登录成功返回：Token + 用户与组织展示信息 */
    public static class LoginResult {
        private String accessToken;
        private long userId;
        private long orgId;
        private String orgCode;
        private String orgName;
        private String username;
        private String displayName;
        private String phone;
        private Set<String> roleCodes;
        private boolean mustChangePassword;

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

        public String getOrgCode() {
            return orgCode;
        }

        public void setOrgCode(String orgCode) {
            this.orgCode = orgCode;
        }

        public String getOrgName() {
            return orgName;
        }

        public void setOrgName(String orgName) {
            this.orgName = orgName;
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

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public Set<String> getRoleCodes() {
            return roleCodes;
        }

        public void setRoleCodes(Set<String> roleCodes) {
            this.roleCodes = roleCodes;
        }

        public boolean isMustChangePassword() {
            return mustChangePassword;
        }

        public void setMustChangePassword(boolean mustChangePassword) {
            this.mustChangePassword = mustChangePassword;
        }
    }

    /** 当前用户资料（无 Token） */
    public static class MeResult {
        private long userId;
        private long orgId;
        private String orgCode;
        private String orgName;
        private String username;
        private String displayName;
        private String phone;
        private Set<String> roleCodes;
        private boolean mustChangePassword;

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

        public String getOrgCode() {
            return orgCode;
        }

        public void setOrgCode(String orgCode) {
            this.orgCode = orgCode;
        }

        public String getOrgName() {
            return orgName;
        }

        public void setOrgName(String orgName) {
            this.orgName = orgName;
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

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public Set<String> getRoleCodes() {
            return roleCodes;
        }

        public void setRoleCodes(Set<String> roleCodes) {
            this.roleCodes = roleCodes;
        }

        public boolean isMustChangePassword() {
            return mustChangePassword;
        }

        public void setMustChangePassword(boolean mustChangePassword) {
            this.mustChangePassword = mustChangePassword;
        }
    }
}
