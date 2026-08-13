package com.bidding.identity.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.model.Org;
import com.bidding.identity.domain.model.Role;
import com.bidding.identity.domain.model.RoleCode;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.OrgRepository;
import com.bidding.identity.domain.port.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserAppServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrgRepository orgRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AdminUserAppService adminUserAppService;

    @BeforeEach
    void setUp() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        UserContext.set(new CurrentUser(1L, 1L, "admin", roles, 0, false));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void nonAdminForbidden() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.BIDDER.name());
        UserContext.set(new CurrentUser(2L, 1L, "bidder", roles, 0, false));

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
        when(userRepository.countDistinctActiveUsersWithAnyRole(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.replaceRoles(1L, List.of(RoleCode.EXPERT.name())));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(userRepository, never()).replaceRoles(anyLong(), anySet());
    }

    @Test
    void onlySuperAdminCanGrantSuperAdmin() {
        Set<String> targetRoles = new HashSet<>();
        targetRoles.add(RoleCode.PLATFORM_ADMIN.name());
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, targetRoles)));

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.replaceRoles(9L,
                        List.of(RoleCode.PLATFORM_ADMIN.name(),
                                RoleCode.PLATFORM_SUPER_ADMIN.name())));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        verify(userRepository, never()).replaceRoles(anyLong(), anySet());
    }

    @Test
    void superAdminCanTransferSuperAdminRole() {
        Set<String> operatorRoles = new HashSet<>();
        operatorRoles.add(RoleCode.PLATFORM_ADMIN.name());
        operatorRoles.add(RoleCode.PLATFORM_SUPER_ADMIN.name());
        UserContext.set(new CurrentUser(1L, 1L, "admin", operatorRoles, 0, false));

        Set<String> oldRoles = new HashSet<>();
        oldRoles.add(RoleCode.PLATFORM_ADMIN.name());
        Set<String> newRoles = new HashSet<>();
        newRoles.add(RoleCode.PLATFORM_ADMIN.name());
        newRoles.add(RoleCode.PLATFORM_SUPER_ADMIN.name());

        when(userRepository.findById(9L))
                .thenReturn(Optional.of(user(9L, oldRoles)))
                .thenReturn(Optional.of(user(9L, newRoles)));
        when(userRepository.listUserIdsWithRole(RoleCode.PLATFORM_SUPER_ADMIN.name()))
                .thenReturn(List.of(1L, 9L));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, operatorRoles)));

        AdminUserAppService.UserSummary result = adminUserAppService.replaceRoles(
                9L, List.of("PLATFORM_ADMIN", "PLATFORM_SUPER_ADMIN"));

        assertTrue(result.getRoleCodes().contains("PLATFORM_SUPER_ADMIN"));
        verify(userRepository).replaceRoles(eq(9L), eq(newRoles));
        verify(userRepository).replaceRoles(eq(1L), eq(Set.of(RoleCode.PLATFORM_ADMIN.name())));
        verify(userRepository).bumpTokenVersion(1L);
        verify(userRepository).bumpTokenVersion(9L);
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
        verify(userRepository).bumpTokenVersion(9L);
    }

    @Test
    void listRolesOk() {
        when(userRepository.listRoles()).thenReturn(List.of(new Role("BIDDER", "投标方")));
        assertEquals(1, adminUserAppService.listRoles().size());
    }

    @Test
    void createPlatformAdminSuccess() {
        when(orgRepository.findSystemOrgId()).thenReturn(Optional.of(1L));
        when(orgRepository.findById(1L)).thenReturn(Optional.of(activeOrg(1L)));
        when(userRepository.existsByUsername("lisi")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd!")).thenReturn("hashed");
        when(userRepository.insert(eq(1L), eq("lisi"), eq("hashed"), eq("李四"),
                isNull(), isNull(), eq("ACTIVE"), eq(true), eq(1L)))
                .thenReturn(20L);

        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        when(userRepository.findById(20L)).thenReturn(Optional.of(
                new UserAccount(20L, 1L, "lisi", "hashed", "李四",
                        null, null, "ACTIVE", true, 0, Instant.now(), Instant.now(), 1L, roles)
        ));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, roles)));

        AdminUserAppService.UserSummary summary =
                adminUserAppService.createPlatformAdmin("lisi", "Passw0rd!", "李四");

        assertEquals("lisi", summary.getUsername());
        assertEquals("李四", summary.getDisplayName());
        assertEquals(1L, summary.getOrgId());
        assertEquals(1L, summary.getCreatedBy());
        assertTrue(summary.getRoleCodes().contains("PLATFORM_ADMIN"));
        assertTrue(summary.isMustChangePassword());
        verify(userRepository).replaceRoles(eq(20L), eq(roles));
        verify(userRepository, never()).existsByPhone(anyString());
        verify(userRepository, never()).existsByEmail(anyString());
    }

    @Test
    void listPlatformAdminsOk() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        UserAccount account = new UserAccount(
                1L, 1L, "admin", "hash", "平台管理员", null, null,
                "ACTIVE", false, 0, Instant.now(), Instant.now(), null, roles);
        when(userRepository.searchPlatformAdmins(eq(""), eq(""), eq(0), eq(20)))
                .thenReturn(List.of(account));
        when(userRepository.countPlatformAdmins(eq(""), eq(""))).thenReturn(1L);
        when(userRepository.countPlatformAdminsByStatus("ACTIVE")).thenReturn(3L);
        when(userRepository.countPlatformAdminsByStatus("DISABLED")).thenReturn(1L);

        var page = adminUserAppService.listPlatformAdmins("", "", 1, 20);

        assertEquals(1, page.getItems().size());
        assertEquals("admin", page.getItems().get(0).getUsername());
        assertEquals(1L, page.getTotal());
        assertEquals(3L, page.getActiveCount());
        assertEquals(1L, page.getDisabledCount());
    }

    @Test
    void listPlatformAdminsFilterDisabled() {
        when(userRepository.searchPlatformAdmins(eq(""), eq("DISABLED"), eq(0), eq(20)))
                .thenReturn(List.of());
        when(userRepository.countPlatformAdmins(eq(""), eq("DISABLED"))).thenReturn(0L);
        when(userRepository.countPlatformAdminsByStatus("ACTIVE")).thenReturn(2L);
        when(userRepository.countPlatformAdminsByStatus("DISABLED")).thenReturn(0L);

        var page = adminUserAppService.listPlatformAdmins("", "disabled", 1, 20);

        assertEquals(0, page.getItems().size());
        assertEquals(0L, page.getTotal());
        assertEquals(2L, page.getActiveCount());
        assertEquals(0L, page.getDisabledCount());
        verify(userRepository).searchPlatformAdmins(eq(""), eq("DISABLED"), eq(0), eq(20));
    }

    @Test
    void listPlatformAdminsRejectBadStatus() {
        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.listPlatformAdmins("", "DELETED", 1, 20));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(userRepository, never()).searchPlatformAdmins(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void createUserSuccess() {
        when(orgRepository.findById(1L)).thenReturn(Optional.of(activeOrg(1L)));
        when(userRepository.existsByUsername("zhangsan")).thenReturn(false);
        when(userRepository.existsByPhone("13800138000")).thenReturn(false);
        when(userRepository.existsByEmail("zhangsan@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd!")).thenReturn("hashed");
        when(userRepository.insert(eq(1L), eq("zhangsan"), eq("hashed"), eq("张三"),
                eq("13800138000"), eq("zhangsan@example.com"), eq("ACTIVE"), eq(true), eq(1L)))
                .thenReturn(10L);

        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.TENDERER.name());
        when(userRepository.findById(10L)).thenReturn(Optional.of(
                new UserAccount(10L, 1L, "zhangsan", "hashed", "张三",
                        "13800138000", "zhangsan@example.com", "ACTIVE",
                        true, 0, Instant.now(), Instant.now(), 1L, roles)
        ));
        Set<String> adminRoles = new HashSet<>();
        adminRoles.add(RoleCode.PLATFORM_ADMIN.name());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, adminRoles)));

        AdminUserAppService.UserSummary summary = adminUserAppService.createUser(
                1L, "zhangsan", "Passw0rd!", "张三", "13800138000",
                "zhangsan@example.com", List.of("TENDERER")
        );

        assertEquals("zhangsan", summary.getUsername());
        assertEquals("13800138000", summary.getPhone());
        assertEquals("zhangsan@example.com", summary.getEmail());
        assertTrue(summary.isMustChangePassword());
        assertTrue(summary.getRoleCodes().contains("TENDERER"));
        verify(userRepository).replaceRoles(eq(10L), eq(roles));
    }

    @Test
    void createUserRejectShortPassword() {
        when(orgRepository.findById(1L)).thenReturn(Optional.of(activeOrg(1L)));
        when(userRepository.existsByUsername("zhangsan")).thenReturn(false);
        when(userRepository.existsByPhone("13800138000")).thenReturn(false);
        when(userRepository.existsByEmail("zhangsan@example.com")).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.createUser(
                        1L, "zhangsan", "1234567", "张三", "13800138000",
                        "zhangsan@example.com", List.of("BIDDER")));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(userRepository, never()).insert(
                anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void createUserRejectBadPhone() {
        when(orgRepository.findById(1L)).thenReturn(Optional.of(activeOrg(1L)));
        when(userRepository.existsByUsername("zhangsan")).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.createUser(
                        1L, "zhangsan", "Passw0rd!", "张三", "12345",
                        "zhangsan@example.com", List.of("BIDDER")));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void createUserRejectBadEmail() {
        when(orgRepository.findById(1L)).thenReturn(Optional.of(activeOrg(1L)));
        when(userRepository.existsByUsername("zhangsan")).thenReturn(false);
        when(userRepository.existsByPhone("13800138000")).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.createUser(
                        1L, "zhangsan", "Passw0rd!", "张三", "13800138000",
                        "not-an-email", List.of("BIDDER")));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void createUserRejectPasswordWithoutLetter() {
        when(orgRepository.findById(1L)).thenReturn(Optional.of(activeOrg(1L)));
        when(userRepository.existsByUsername("zhangsan")).thenReturn(false);
        when(userRepository.existsByPhone("13800138000")).thenReturn(false);
        when(userRepository.existsByEmail("zhangsan@example.com")).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.createUser(
                        1L, "zhangsan", "12345678", "张三", "13800138000",
                        "zhangsan@example.com", List.of("BIDDER")));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void createUserRejectBadUsername() {
        when(orgRepository.findById(1L)).thenReturn(Optional.of(activeOrg(1L)));

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.createUser(
                        1L, "zhang; drop", "Passw0rd!", "张三", "13800138000",
                        "zhangsan@example.com", List.of("BIDDER")));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void createUserRejectDisabledOrg() {
        when(orgRepository.findById(2L)).thenReturn(Optional.of(disabledOrg(2L)));

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.createUser(
                        2L, "zhangsan", "Passw0rd!", "张三", "13800138000",
                        "zhangsan@example.com", List.of("BIDDER")));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(userRepository, never()).insert(
                anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void updateTendererProfileSuccess() {
        when(orgRepository.findById(2L)).thenReturn(Optional.of(activeOrg(2L)));

        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.TENDERER.name());
        UserAccount before = new UserAccount(
                9L, 2L, "zhangsan", "hash", "张三", "13800138000", "old@example.com",
                "ACTIVE", false, 0, Instant.now(), Instant.now(), null, roles);
        UserAccount after = new UserAccount(
                9L, 2L, "zhangsan", "hash", "王五", "13900139000", "wangwu@example.com",
                "ACTIVE", false, 0, Instant.now(), Instant.now(), null, roles);
        when(userRepository.findById(9L))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(userRepository.existsByPhoneExceptUserId("13900139000", 9L)).thenReturn(false);
        when(userRepository.existsByEmailExceptUserId("wangwu@example.com", 9L)).thenReturn(false);

        AdminUserAppService.UserSummary summary = adminUserAppService.updateTendererProfile(
                2L, 9L, "王五", "13900139000", "wangwu@example.com");

        assertEquals("王五", summary.getDisplayName());
        assertEquals("13900139000", summary.getPhone());
        assertEquals("wangwu@example.com", summary.getEmail());
        verify(userRepository).updateProfile(9L, "王五", "13900139000", "wangwu@example.com");
    }

    @Test
    void updateTendererProfileRejectNonTenderer() {
        when(orgRepository.findById(2L)).thenReturn(Optional.of(activeOrg(2L)));
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.BIDDER.name());
        when(userRepository.findById(9L)).thenReturn(Optional.of(
                new UserAccount(9L, 2L, "u9", "hash", "用户9", "13900000009", "u9@example.com",
                        "ACTIVE", false, 0, Instant.now(), Instant.now(), null, roles)));

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.updateTendererProfile(
                        2L, 9L, "王五", "13900139000", "wangwu@example.com"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(userRepository, never()).updateProfile(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void updateTendererProfileRejectSystemOrg() {
        when(orgRepository.findById(1L)).thenReturn(Optional.of(
                new Org(1L, "SYSTEM", "系统组织", "系统", "ACTIVE",
                        1L, Instant.now(), Instant.now())));

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.updateTendererProfile(
                        1L, 9L, "王五", "13900139000", "wangwu@example.com"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(userRepository, never()).updateProfile(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void resetPasswordReturnsTemporaryPasswordOnce() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.BIDDER.name());
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, roles)));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");

        AdminUserAppService.ResetPasswordResult result = adminUserAppService.resetPassword(9L);

        assertEquals(9L, result.getUserId());
        assertTrue(result.isReset());
        assertNotNull(result.getTemporaryPassword());
        assertFalse(result.getTemporaryPassword().isBlank());
        assertTrue(result.getMessage().contains("临时密码"));
        verify(userRepository).updatePassword(eq(9L), eq("new-hash"), eq(true));
        verify(userRepository).bumpTokenVersion(9L);
    }

    @Test
    void disableUserBumpsTokenVersion() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.BIDDER.name());
        when(userRepository.findById(9L))
                .thenReturn(Optional.of(user(9L, roles)))
                .thenReturn(Optional.of(new UserAccount(
                        9L, 1L, "u9", "hash", "用户9", "13900139000", "u9@example.com",
                        "DISABLED", false, 1, Instant.now(), Instant.now(), null, roles)));

        AdminUserAppService.UserSummary summary = adminUserAppService.updateStatus(9L, "DISABLED");

        assertEquals("DISABLED", summary.getStatus());
        verify(userRepository).updateStatus(9L, "DISABLED");
        verify(userRepository).bumpTokenVersion(9L);
    }

    @Test
    void deleteUserPhysicallyDeletesAccount() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.BIDDER.name());
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, roles)));

        adminUserAppService.deleteUser(9L);

        verify(userRepository).deleteById(9L);
        verify(userRepository, never()).updateStatus(eq(9L), eq("DELETED"));
        verify(userRepository, never()).bumpTokenVersion(9L);
    }

    @Test
    void deleteUserRejectLastPlatformAdmin() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, roles)));
        when(userRepository.countDistinctActiveUsersWithAnyRole(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> adminUserAppService.deleteUser(9L));

        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(userRepository, never()).deleteById(anyLong());
    }

    private static UserAccount user(long id, Set<String> roles) {
        return new UserAccount(id, 1L, "u" + id, "hash", "用户" + id,
                "1390000000" + (id % 10), "u" + id + "@example.com",
                "ACTIVE", false, 0, Instant.now(), Instant.now(), null, roles);
    }

    private static Org activeOrg(long id) {
        return new Org(id, "ORG_" + id, "组织" + id, "北京", "ACTIVE",
                1L, Instant.now(), Instant.now());
    }

    private static Org disabledOrg(long id) {
        return new Org(id, "ORG_" + id, "组织" + id, "北京", "DISABLED",
                1L, Instant.now(), Instant.now());
    }
}
