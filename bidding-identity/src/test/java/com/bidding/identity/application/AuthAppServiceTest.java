package com.bidding.identity.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.OrgRepository;
import com.bidding.identity.domain.port.UserRepository;
import com.bidding.identity.infrastructure.security.JwtTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthAppServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrgRepository orgRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenService jwtTokenService;

    @InjectMocks
    private AuthAppService authAppService;

    @Test
    void deletedUserLoginReturnsBadCredentials() {
        when(userRepository.findByUsername("deleted")).thenReturn(Optional.of(user("DELETED")));

        BizException ex = assertThrows(BizException.class,
                () -> authAppService.login("deleted", "password"));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.errorCode());
        assertEquals("用户名或密码错误", ex.getMessage());
        verify(orgRepository, never()).findById(2L);
        verify(passwordEncoder, never()).matches("password", "hash");
    }

    private static UserAccount user(String status) {
        return new UserAccount(
                9L,
                2L,
                "deleted",
                "hash",
                "已删除账号",
                null,
                null,
                status,
                false,
                0,
                Instant.now(),
                Instant.now(),
                null,
                Set.of("TENDERER")
        );
    }
}
