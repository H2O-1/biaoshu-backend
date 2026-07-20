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

import java.util.Set;

/**
 * 首次启动时若无平台管理员，则用环境变量创建引导账号。密码不进 Flyway。
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
            return;
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "系统无平台管理员，请设置环境变量 BOOTSTRAP_ADMIN_PASSWORD 后启动");
        }
        long orgId = userRepository.findSystemOrgId()
                .orElseThrow(() -> new IllegalStateException("缺少系统组织种子数据"));
        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException("引导用户名已存在但无平台管理员角色: " + username);
        }
        long userId = userRepository.insert(
                orgId,
                username,
                passwordEncoder.encode(password),
                displayName,
                "ACTIVE"
        );
        userRepository.replaceRoles(userId, Set.of(RoleCode.PLATFORM_ADMIN.name()));
        log.warn("已创建引导平台管理员账号 username={}，请尽快修改密码", username);
    }
}
