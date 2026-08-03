package com.bidding.identity.application;

import com.bidding.identity.domain.model.RoleCode;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.OrgRepository;
import com.bidding.identity.domain.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 启动时检查：无平台管理员则创建引导账号；无超管则给引导账号补超管角色。
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final UserRepository userRepository;
    private final OrgRepository orgRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String username;
    private final String password;
    private final String displayName;

    public BootstrapAdminRunner(
            UserRepository userRepository,
            OrgRepository orgRepository,
            PasswordEncoder passwordEncoder,
            @Value("${bidding.bootstrap-admin.enabled:true}") boolean enabled,
            @Value("${bidding.bootstrap-admin.username:admin}") String username,
            @Value("${bidding.bootstrap-admin.password:}") String password,
            @Value("${bidding.bootstrap-admin.display-name:平台管理员}") String displayName) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        long adminCount = userRepository.countUsersWithRole(RoleCode.PLATFORM_ADMIN.name());
        if (adminCount == 0) {
            createBootstrapAdmin();
        }
        ensureSuperAdminExists();
    }

    private void createBootstrapAdmin() {
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalStateException(
                    "系统无平台管理员，请设置环境变量 BOOTSTRAP_ADMIN_PASSWORD 后启动");
        }

        Optional<Long> optionalOrgId = orgRepository.findSystemOrgId();
        if (optionalOrgId.isEmpty()) {
            throw new IllegalStateException("缺少系统组织种子数据");
        }
        long orgId = optionalOrgId.get();

        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException("引导用户名已存在但无平台管理员角色: " + username);
        }

        String passwordHash = passwordEncoder.encode(password);
        long userId = userRepository.insert(
                orgId, username, passwordHash, displayName, null, null, "ACTIVE", false, null);

        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        roles.add(RoleCode.PLATFORM_SUPER_ADMIN.name());
        userRepository.replaceRoles(userId, roles);

        log.warn("已创建引导平台超管账号 username={}，请尽快修改密码", username);
    }

    /**
     * 升级兼容：已有普管但无超管时，给引导用户名（或首个平台管理员）补上超管角色。
     */
    private void ensureSuperAdminExists() {
        long superCount = userRepository.countUsersWithRole(RoleCode.PLATFORM_SUPER_ADMIN.name());
        if (superCount > 0) {
            return;
        }
        Optional<UserAccount> bootstrap = userRepository.findByUsername(username);
        if (bootstrap.isPresent()
                && bootstrap.get().getRoleCodes().contains(RoleCode.PLATFORM_ADMIN.name())) {
            grantSuperAdmin(bootstrap.get());
            return;
        }
        // 找不到引导账号则无法自动指定，留给人工赋权
        log.warn("系统尚无平台超级管理员，请由运维为唯一超管账号赋予 PLATFORM_SUPER_ADMIN");
    }

    private void grantSuperAdmin(UserAccount account) {
        Set<String> roles = new HashSet<>(account.getRoleCodes());
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        roles.add(RoleCode.PLATFORM_SUPER_ADMIN.name());
        userRepository.replaceRoles(account.getId(), roles);
        log.warn("已为账号 username={} 补齐平台超级管理员角色", account.getUsername());
    }
}
