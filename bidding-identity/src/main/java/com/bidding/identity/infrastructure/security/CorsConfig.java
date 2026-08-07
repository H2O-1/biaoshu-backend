package com.bidding.identity.infrastructure.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 前端联调 CORS：允许配置的 Origin 跨域访问 API。
 * 浏览器会拦截跨域；Apifox 不检查 CORS，所以会出现「工具能调、页面不能调」。
 * 仅 API（Servlet Web）需要；Worker 无 HTTP 入口，不加载。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    private static final String DEFAULT_ORIGINS =
            "http://127.0.0.1:5186,http://localhost:5186";

    @Bean
    public CorsConfigurationSource corsConfigurationSource(Environment env) {
        String raw = firstNonBlank(
                env.getProperty("CORS_ALLOWED_ORIGINS"),
                env.getProperty("bidding.cors.allowed-origins"));
        if (!StringUtils.hasText(raw) || "http".equals(raw.trim())) {
            raw = DEFAULT_ORIGINS;
        }

        List<String> origins = new ArrayList<>();
        for (String part : raw.split(",")) {
            String origin = part.trim();
            if (!origin.isEmpty() && !"http".equals(origin)) {
                origins.add(origin);
            }
        }
        if (origins.isEmpty()) {
            for (String part : DEFAULT_ORIGINS.split(",")) {
                origins.add(part.trim());
            }
        }

        log.info("CORS allowed origins: {}", origins);

        CorsConfiguration config = new CorsConfiguration();
        // patterns 比 allowedOrigins 更稳（与 allowCredentials 兼容）
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a;
        }
        if (StringUtils.hasText(b)) {
            return b;
        }
        return null;
    }
}
