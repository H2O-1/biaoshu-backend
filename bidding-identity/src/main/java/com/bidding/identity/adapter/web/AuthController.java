package com.bidding.identity.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.identity.application.AuthAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthAppService authAppService;

    public AuthController(AuthAppService authAppService) {
        this.authAppService = authAppService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthAppService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authAppService.login(request.username(), request.password()));
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }
}
