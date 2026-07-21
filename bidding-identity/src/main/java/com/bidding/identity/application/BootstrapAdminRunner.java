package com.bidding.identity.application;

import com.bidding.identity.domain.model.RoleCode;
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
 * 启动时检查：如果还没有平台管理员，就自动创建一个。
 * 密码从配置读取，不写进 SQL 文件。
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String username;
    private final String password;
    private final String displayName;

    public BootstrapAdminRunner(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${bidding.bootstrap-admin.enabled:true}") boolean enabled,
            @Value("${bidding.bootstrap-admin.username:admin}") String username,
            @Value("${bidding.bootstrap-admin.password:}") String password,
            @Value("${bidding.bootstrap-admin.display-name:平台管理员}") String displayName) {
        this.userRepository = userRepository;
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
        if (adminCount > 0) {
            // 已经有管理员了，什么都不做
            return;
        }

        if (password == null || password.trim().isEmpty()) {
            throw new IllegalStateException(
                    "系统无平台管理员，请设置环境变量 BOOTSTRAP_ADMIN_PASSWORD 后启动");
        }

        Optional<Long> optionalOrgId = userRepository.findSystemOrgId();
        if (optionalOrgId.isEmpty()) {
            throw new IllegalStateException("缺少系统组织种子数据");
        }
        long orgId = optionalOrgId.get();

        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException("引导用户名已存在但无平台管理员角色: " + username);
        }

        String passwordHash = passwordEncoder.encode(password);
        // 引导管理员不强制改密，避免本地首次启动就被拦住
        long userId = userRepository.insert(orgId, username, passwordHash, displayName, null, "ACTIVE", false);

        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        userRepository.replaceRoles(userId, roles);

        log.warn("已创建引导平台管理员账号 username={}，请尽快修改密码", username);
    }
}
