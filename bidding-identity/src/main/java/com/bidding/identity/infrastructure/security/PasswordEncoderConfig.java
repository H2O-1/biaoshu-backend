package com.bidding.identity.infrastructure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希 Bean：API 与 Worker 共用（改密/引导管理员等业务服务依赖）。
 * 与 HTTP 安全链无关，故不挂在 {@link SecurityConfig} 上。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
