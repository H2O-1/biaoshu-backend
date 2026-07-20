package com.bidding.identity.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.model.Role;
import com.bidding.identity.domain.model.RoleCode;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserAppServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminUserAppService adminUserAppService;

    @BeforeEach
    void setUp() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        UserContext.set(new CurrentUser(1L, 1L, "admin", roles));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void nonAdminForbidden() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.BIDDER.name());
        UserContext.set(new CurrentUser(2L, 1L, "bidder", roles));

        BizException ex = assertThrows(BizException.class, () -> adminUserAppService.listRoles());
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
    }

    @Test
    void rejectUnknownRole() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.BIDDER.name());
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, roles)));

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.replaceRoles(9L, List.of("NOT_A_ROLE")));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(userRepository, never()).replaceRoles(anyLong(), anySet());
    }

    @Test
    void keepAtLeastOnePlatformAdmin() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, roles)));
        when(userRepository.countUsersWithRole(RoleCode.PLATFORM_ADMIN.name())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.replaceRoles(1L, List.of(RoleCode.EXPERT.name())));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(userRepository, never()).replaceRoles(anyLong(), anySet());
    }

    @Test
    void replaceRolesSuccess() {
        Set<String> oldRoles = new HashSet<>();
        oldRoles.add(RoleCode.BIDDER.name());

        Set<String> newRoles = new HashSet<>();
        newRoles.add(RoleCode.TENDERER.name());
        newRoles.add(RoleCode.EXPERT.name());

        when(userRepository.findById(9L))
                .thenReturn(Optional.of(user(9L, oldRoles)))
                .thenReturn(Optional.of(user(9L, newRoles)));

        AdminUserAppService.UserSummary result =
                adminUserAppService.replaceRoles(9L, List.of("TENDERER", "EXPERT"));

        assertTrue(result.getRoleCodes().contains("TENDERER"));
        assertTrue(result.getRoleCodes().contains("EXPERT"));
        verify(userRepository).replaceRoles(eq(9L), eq(newRoles));
    }

    @Test
    void listRolesOk() {
        when(userRepository.listRoles()).thenReturn(List.of(new Role("BIDDER", "投标方")));
        assertEquals(1, adminUserAppService.listRoles().size());
    }

    private static UserAccount user(long id, Set<String> roles) {
        return new UserAccount(id, 1L, "u" + id, "hash", "用户" + id, "ACTIVE", Instant.now(), roles);
    }
}
