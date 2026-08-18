package com.bidding.identity.infrastructure.port;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.ExpertAccountPort;
import com.bidding.identity.application.TemporaryPasswordGenerator;
import com.bidding.identity.domain.model.Org;
import com.bidding.identity.domain.model.RoleCode;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.OrgRepository;
import com.bidding.identity.domain.port.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 专家临时账号实现：复用 sys_user / sys_user_role。
 */
@Component
public class ExpertAccountPortAdapter implements ExpertAccountPort {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final int MAX_USERNAME_TRIES = 16;

    private final UserRepository userRepository;
    private final OrgRepository orgRepository;
    private final PasswordEncoder passwordEncoder;

    public ExpertAccountPortAdapter(UserRepository userRepository,
                                     OrgRepository orgRepository,
                                     PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public CreateResult createTemporaryExpert(long orgId, String displayName, long createdBy) {
        Org org = requireActiveOrg(orgId);
        String safeDisplayName = normalizeDisplayName(displayName);
        String username = allocateUsername();
        String temporaryPassword = TemporaryPasswordGenerator.generate();
        String passwordHash = passwordEncoder.encode(temporaryPassword);

        long userId = userRepository.insert(
                org.getId(),
                username,
                passwordHash,
                safeDisplayName,
                null,
                null,
                STATUS_ACTIVE,
                false,
                createdBy
        );
        userRepository.replaceRoles(userId, java.util.Set.of(RoleCode.EXPERT.name()));

        CreateResult result = new CreateResult();
        result.setUserId(userId);
        result.setUsername(username);
        result.setDisplayName(safeDisplayName);
        result.setTemporaryPassword(temporaryPassword);
        result.setPasswordHash(passwordHash);
        return result;
    }

    @Override
    @Transactional
    public ResetPasswordResult resetTemporaryExpertPassword(long userId) {
        requireActiveExpert(userId, "当前账号不是可重置的专家账号");
        String temporaryPassword = TemporaryPasswordGenerator.generate();
        String passwordHash = passwordEncoder.encode(temporaryPassword);
        userRepository.updatePassword(userId, passwordHash, false);
        userRepository.bumpTokenVersion(userId);

        ResetPasswordResult result = new ResetPasswordResult();
        result.setUserId(userId);
        result.setTemporaryPassword(temporaryPassword);
        return result;
    }

    @Override
    @Transactional
    public void disableTemporaryExpert(long userId) {
        requireActiveExpert(userId, "当前账号不是可禁用的专家账号");
        userRepository.updateStatus(userId, STATUS_DISABLED);
        userRepository.bumpTokenVersion(userId);
    }

    @Override
    @Transactional
    public void deleteTemporaryExpert(long userId) {
        requireExpert(userId, "当前账号不是可删除的专家账号");
        userRepository.deleteById(userId);
    }

    private void requireActiveExpert(long userId, String conflictMessage) {
        UserAccount user = requireExpert(userId, conflictMessage);
        if (!user.isEnabled()) {
            throw new BizException(ErrorCode.CONFLICT, conflictMessage);
        }
    }

    private UserAccount requireExpert(long userId, String conflictMessage) {
        UserAccount user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "专家账号不存在"));
        if (user.isDeleted() || !user.getRoleCodes().contains(RoleCode.EXPERT.name())) {
            throw new BizException(ErrorCode.CONFLICT, conflictMessage);
        }
        return user;
    }

    private Org requireActiveOrg(long orgId) {
        Optional<Org> optionalOrg = orgRepository.findById(orgId);
        if (optionalOrg.isEmpty() || optionalOrg.get().isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "组织不存在");
        }
        Org org = optionalOrg.get();
        if (!org.isActive()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "组织已停用，不能创建专家账号");
        }
        if (org.isSystem()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "系统组织不能创建专家账号");
        }
        return org;
    }

    private String allocateUsername() {
        for (int i = 0; i < MAX_USERNAME_TRIES; i++) {
            String candidate = "expert_" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 12).toLowerCase(Locale.ROOT);
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
        String fallback = "expert_" + Math.abs(RANDOM.nextLong());
        if (!userRepository.existsByUsername(fallback)) {
            return fallback;
        }
        throw new BizException(ErrorCode.INTERNAL, "生成专家账号失败，请重试");
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "专家姓名不能为空");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > 128) {
            throw new BizException(ErrorCode.BAD_REQUEST, "专家姓名过长");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "专家姓名包含非法字符");
            }
        }
        return trimmed;
    }
}
