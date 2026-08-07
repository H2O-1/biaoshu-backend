package com.bidding.worker;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 异步任务 Worker 进程入口。
 * <p>
 * 与 API 共用业务模块，以 {@link WebApplicationType#NONE} 运行。
 * HTTP Controller / SecurityFilterChain 只给 API（注解 + REGEX 排除）。
 * {@code PasswordEncoder} 由 identity 的 {@code PasswordEncoderConfig} 提供，勿在此重复定义。
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class
})
@ComponentScan(
        basePackages = "com.bidding",
        excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = {
                                RestController.class,
                                Controller.class,
                                RestControllerAdvice.class,
                                ControllerAdvice.class
                        }
                ),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = {
                                "com\\.bidding\\.identity\\.infrastructure\\.security\\.SecurityConfig",
                                "com\\.bidding\\.identity\\.infrastructure\\.security\\.CorsConfig",
                                "com\\.bidding\\.identity\\.infrastructure\\.security\\.JwtAuthenticationFilter"
                        }
                )
        }
)
@EnableScheduling
public class BiddingWorkerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(BiddingWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.keep-alive=true")
                .run(args);
    }
}
