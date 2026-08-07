package com.bidding.identity.infrastructure.security;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * HTTP 安全链：只给 API 用。
 * <p>
 * Worker 启动类通过 {@code excludeFilters} 不扫描本类；
 * 另加 {@link ConditionalOnWebApplication}，防止其它无 Web 进程误装
 * {@code SecurityFilterChain}（依赖 MVC {@code mvcHandlerMappingIntrospector}）。
 * 密码哈希见 {@link PasswordEncoderConfig}（API/Worker 共用）。
 */
@Configuration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
        http.cors(Customizer.withDefaults());
        // 不用 Session，每次靠 JWT
        http.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/actuator/health", "/error").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/admin/**").hasAnyRole("PLATFORM_ADMIN", "PLATFORM_SUPER_ADMIN")
                .anyRequest().authenticated()
        );

        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> writeJson(res, 401, ErrorCode.UNAUTHORIZED))
                .accessDeniedHandler((req, res, e) -> writeJson(res, 403, ErrorCode.FORBIDDEN))
        );

        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(jakarta.servlet.http.HttpServletResponse res,
                           int httpStatus,
                           ErrorCode errorCode) throws java.io.IOException {
        res.setStatus(httpStatus);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(res.getOutputStream(),
                ApiResponse.fail(errorCode.code(), errorCode.defaultMessage()));
    }
}
