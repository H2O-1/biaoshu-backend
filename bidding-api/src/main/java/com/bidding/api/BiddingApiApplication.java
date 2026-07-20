package com.bidding.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.bidding", exclude = {
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class
})
public class BiddingApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BiddingApiApplication.class, args);
    }
}
