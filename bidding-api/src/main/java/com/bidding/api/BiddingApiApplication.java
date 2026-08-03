package com.bidding.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HTTP 入口启动类。
 * <p>
 * scanBasePackages 扫到 com.bidding，从而加载 identity 等业务模块的 Bean。
 * 排除默认 UserDetailsService：本系统用 JWT，不走 Spring Security 内存用户。
 * EnableScheduling：同进程认领 INDEX 等异步任务（M3）。
 */
@SpringBootApplication(scanBasePackages = "com.bidding", exclude = {
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class
})
@EnableScheduling
public class
BiddingApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BiddingApiApplication.class, args);
    }
}
