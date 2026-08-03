package com.bidding.identity.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentCountPort;
import com.bidding.common.port.DocumentIndexCountPort;
import com.bidding.common.port.PackageCountPort;
import com.bidding.common.port.ProjectCountPort;
import com.bidding.common.port.ReviewStatusCountPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.model.Org;
import com.bidding.identity.domain.model.RoleCode;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.OrgRepository;
import com.bidding.identity.domain.port.UserRepository;
import com.bidding.identity.infrastructure.city.CityCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOrgAppServiceTest {

    @Mock
    private OrgRepository orgRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CityCatalog cityCatalog;

    @Mock
    private ProjectCountPort projectCountPort;

    @Mock
    private PackageCountPort packageCountPort;

    @Mock
    private DocumentIndexCountPort documentIndexCountPort;

    @Mock
    private DocumentCountPort documentCountPort;

    @Mock
    private ReviewStatusCountPort reviewStatusCountPort;

    @InjectMocks
    private AdminOrgAppService adminOrgAppService;

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
    void createOrgSuccess() {
        when(cityCatalog.containsName("北京市")).thenReturn(true);
        when(orgRepository.existsByName("华能招标有限公司")).thenReturn(false);
        when(orgRepository.existsByCode(anyString())).thenReturn(false);
        when(orgRepository.insert(anyString(), eq("华能招标有限公司"), eq("北京市"),
                eq("ACTIVE"), eq(1L))).thenReturn(10L);

        Org created = org(10L, "ORG_ABCDEF123456", "华能招标有限公司", "北京市", "ACTIVE");
        when(orgRepository.findById(10L)).thenReturn(Optional.of(created));
        when(orgRepository.countUsersByOrgId(10L)).thenReturn(0L);
        when(projectCountPort.countByOrgId(10L)).thenReturn(0L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser()));

        AdminOrgAppService.OrgSummary summary =
                adminOrgAppService.createOrg("  华能招标有限公司  ", "北京市");

        assertEquals(10L, summary.getId());
        assertEquals("华能招标有限公司", summary.getName());
        assertEquals("北京市", summary.getCity());
        assertEquals("ACTIVE", summary.getStatus());
        assertEquals(0L, summary.getUserCount());
        assertTrue(summary.getCode().startsWith("ORG_"));
        assertEquals("平台管理员", summary.getCreatedByName());

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(orgRepository).insert(codeCaptor.capture(), eq("华能招标有限公司"),
                eq("北京市"), eq("ACTIVE"), eq(1L));
        assertTrue(codeCaptor.getValue().startsWith("ORG_"));
    }

    @Test
    void updateOrgSuccess() {
        Org before = org(2L, "ORG_X", "旧公司", "上海市", "ACTIVE");
        Org after = org(2L, "ORG_X", "新公司", "深圳市", "ACTIVE");
        when(orgRepository.findById(2L))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(cityCatalog.containsName("深圳市")).thenReturn(true);
        when(orgRepository.existsByNameExceptOrgId("新公司", 2L)).thenReturn(false);
        when(orgRepository.countUsersByOrgId(2L)).thenReturn(1L);
        when(projectCountPort.countByOrgId(2L)).thenReturn(0L);
        when(packageCountPort.countActiveByOrgIdAndType(2L, "TENDER")).thenReturn(0L);
        when(packageCountPort.countActiveByOrgIdAndType(2L, "BID")).thenReturn(0L);

        AdminOrgAppService.OrgSummary summary =
                adminOrgAppService.updateOrg(2L, " 新公司 ", "深圳市");

        assertEquals("新公司", summary.getName());
        assertEquals("深圳市", summary.getCity());
        verify(orgRepository).updateNameAndCity(2L, "新公司", "深圳市");
    }

    @Test
    void updateOrgRejectDuplicateName() {
        Org org = org(2L, "ORG_X", "旧公司", "上海市", "ACTIVE");
        when(orgRepository.findById(2L)).thenReturn(Optional.of(org));
        when(orgRepository.existsByNameExceptOrgId("华能招标有限公司", 2L)).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.updateOrg(2L, "华能招标有限公司", "上海市"));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(orgRepository, never()).updateNameAndCity(anyLong(), anyString(), anyString());
    }

    @Test
    void updateOrgRejectSystemOrg() {
        when(orgRepository.findById(1L)).thenReturn(Optional.of(
                org(1L, "SYSTEM", "系统组织", "系统", "ACTIVE")));

        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.updateOrg(1L, "系统组织", "北京市"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(orgRepository, never()).updateNameAndCity(anyLong(), anyString(), anyString());
    }

    @Test
    void createOrgRejectInvalidCity() {
        when(cityCatalog.containsName("深圳")).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.createOrg("华能招标有限公司", "深圳"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(orgRepository, never()).insert(anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void createOrgRejectDuplicateName() {
        when(orgRepository.existsByName("华能招标有限公司")).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.createOrg("华能招标有限公司", "北京市"));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(orgRepository, never()).insert(anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void listOrgsOk() {
        Org org = org(2L, "ORG_X", "测试公司", "上海市", "ACTIVE");
        when(orgRepository.search(eq("测试"), eq(""), eq(""), eq(0), eq(20)))
                .thenReturn(List.of(org));
        when(orgRepository.countSearch(eq("测试"), eq(""), eq(""))).thenReturn(1L);
        when(orgRepository.countUsersByOrgId(2L)).thenReturn(3L);
        when(projectCountPort.countByOrgId(2L)).thenReturn(0L);
        when(packageCountPort.countActiveByOrgIdAndType(2L, "TENDER")).thenReturn(1L);
        when(packageCountPort.countActiveByOrgIdAndType(2L, "BID")).thenReturn(2L);
        when(orgRepository.countByStatus("ACTIVE")).thenReturn(5L);
        when(orgRepository.countByStatus("DISABLED")).thenReturn(2L);
        when(orgRepository.countPendingConfig()).thenReturn(1L);

        AdminOrgAppService.OrgPage page =
                adminOrgAppService.listOrgs("测试", "", "", 1, 20);

        assertEquals(1, page.getItems().size());
        assertEquals(3L, page.getItems().get(0).getUserCount());
        assertEquals(0L, page.getItems().get(0).getProjectCount());
        assertEquals(1L, page.getItems().get(0).getTenderPackageCount());
        assertEquals(2L, page.getItems().get(0).getBidPackageCount());
        assertEquals(1L, page.getTotal());
        assertEquals(5L, page.getActiveCount());
        assertEquals(2L, page.getDisabledCount());
        assertEquals(1L, page.getPendingConfigCount());
    }

    @Test
    void getOrgReturnsBusinessStats() {
        Org org = org(2L, "ORG_X", "测试公司", "上海市", "ACTIVE");
        when(orgRepository.findById(2L)).thenReturn(Optional.of(org));
        when(orgRepository.countUsersByOrgId(2L)).thenReturn(3L);
        when(projectCountPort.countByOrgId(2L)).thenReturn(5L);
        when(packageCountPort.countActiveByOrgIdAndType(2L, "TENDER")).thenReturn(2L);
        when(packageCountPort.countActiveByOrgIdAndType(2L, "BID")).thenReturn(4L);
        when(documentIndexCountPort.countActiveByOrgIdAndIndexStatus(2L, "SUCCESS")).thenReturn(10L);
        when(documentIndexCountPort.countActiveByOrgIdAndIndexStatus(2L, "FAILED")).thenReturn(1L);
        when(reviewStatusCountPort.countByOrgIdAndStatus(2L, "SUCCESS")).thenReturn(3L);
        when(reviewStatusCountPort.countByOrgIdAndStatus(2L, "FAILED")).thenReturn(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(adminUser()));

        AdminOrgAppService.OrgDetail detail = adminOrgAppService.getOrg(2L);

        assertEquals(2L, detail.getId());
        assertEquals(5L, detail.getProjectCount());
        assertEquals(2L, detail.getTenderPackageCount());
        assertEquals(4L, detail.getBidPackageCount());
        assertEquals(10L, detail.getDocumentIndexSuccessCount());
        assertEquals(1L, detail.getDocumentIndexFailedCount());
        assertEquals(3L, detail.getReviewSuccessCount());
        assertEquals(2L, detail.getReviewFailedCount());
    }

    @Test
    void updateStatusRejectSystemOrg() {
        when(orgRepository.findById(1L)).thenReturn(Optional.of(
                org(1L, "SYSTEM", "系统组织", "系统", "ACTIVE")));

        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.updateStatus(1L, "DISABLED"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(orgRepository, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    void updateStatusSuccess() {
        Org before = org(2L, "ORG_X", "测试公司", "上海市", "ACTIVE");
        Org after = org(2L, "ORG_X", "测试公司", "上海市", "DISABLED");
        when(orgRepository.findById(2L))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(orgRepository.countUsersByOrgId(2L)).thenReturn(1L);
        when(projectCountPort.countByOrgId(2L)).thenReturn(0L);
        when(userRepository.bumpTokenVersionByOrgId(2L)).thenReturn(1);

        AdminOrgAppService.OrgSummary summary =
                adminOrgAppService.updateStatus(2L, "DISABLED");

        assertEquals("DISABLED", summary.getStatus());
        verify(orgRepository).updateStatus(2L, "DISABLED");
        verify(userRepository).bumpTokenVersionByOrgId(2L);
    }

    @Test
    void updateStatusDisableAlreadyDisabledSkipsBump() {
        Org disabled = org(2L, "ORG_X", "测试公司", "上海市", "DISABLED");
        when(orgRepository.findById(2L))
                .thenReturn(Optional.of(disabled))
                .thenReturn(Optional.of(disabled));
        when(orgRepository.countUsersByOrgId(2L)).thenReturn(1L);
        when(projectCountPort.countByOrgId(2L)).thenReturn(0L);

        AdminOrgAppService.OrgSummary summary =
                adminOrgAppService.updateStatus(2L, "DISABLED");

        assertEquals("DISABLED", summary.getStatus());
        verify(orgRepository).updateStatus(2L, "DISABLED");
        verify(userRepository, never()).bumpTokenVersionByOrgId(anyLong());
    }

    @Test
    void updateStatusEnableDoesNotBump() {
        Org before = org(2L, "ORG_X", "测试公司", "上海市", "DISABLED");
        Org after = org(2L, "ORG_X", "测试公司", "上海市", "ACTIVE");
        when(orgRepository.findById(2L))
                .thenReturn(Optional.of(before))
                .thenReturn(Optional.of(after));
        when(orgRepository.countUsersByOrgId(2L)).thenReturn(1L);
        when(projectCountPort.countByOrgId(2L)).thenReturn(0L);

        AdminOrgAppService.OrgSummary summary =
                adminOrgAppService.updateStatus(2L, "ACTIVE");

        assertEquals("ACTIVE", summary.getStatus());
        verify(orgRepository).updateStatus(2L, "ACTIVE");
        verify(userRepository, never()).bumpTokenVersionByOrgId(anyLong());
    }

    @Test
    void listOrgUsersOk() {
        Org org = org(2L, "ORG_X", "测试公司", "上海市", "ACTIVE");
        when(orgRepository.findById(2L)).thenReturn(Optional.of(org));

        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.TENDERER.name());
        UserAccount account = new UserAccount(
                9L, 2L, "zhangsan", "hash", "张三", "13800138000",
                "zhangsan@example.com", "ACTIVE", false, 0, Instant.now(), Instant.now(), null, roles);
        when(userRepository.searchByOrgId(eq(2L), eq(""), eq(0), eq(20)))
                .thenReturn(List.of(account));
        when(userRepository.countSearchByOrgId(eq(2L), eq(""))).thenReturn(1L);

        PageResult<AdminUserAppService.UserSummary> page =
                adminOrgAppService.listOrgUsers(2L, "", 1, 20);

        assertEquals(1, page.getItems().size());
        assertEquals("zhangsan", page.getItems().get(0).getUsername());
        assertEquals(2L, page.getItems().get(0).getOrgId());
        assertEquals(1L, page.getTotal());
    }

    @Test
    void listOrgUsersOrgNotFound() {
        when(orgRepository.findById(99L)).thenReturn(Optional.empty());

        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.listOrgUsers(99L, "", 1, 20));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
        verify(userRepository, never()).searchByOrgId(anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void getOrgDeletedReturnsNotFound() {
        when(orgRepository.findById(2L)).thenReturn(Optional.of(
                org(2L, "ORG_X", "测试公司", "上海市", "DELETED")));

        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.getOrg(2L));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    void deleteOrgForbiddenForNormalAdmin() {
        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.deleteOrg(2L));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        verify(orgRepository, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    void deleteOrgSuccessBySuperAdmin() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        roles.add(RoleCode.PLATFORM_SUPER_ADMIN.name());
        UserContext.set(new CurrentUser(1L, 1L, "admin", roles, 0, false));

        when(orgRepository.findById(2L)).thenReturn(Optional.of(
                org(2L, "ORG_X", "测试公司", "上海市", "ACTIVE")));
        when(orgRepository.countUsersByOrgId(2L)).thenReturn(0L);
        when(projectCountPort.countByOrgId(2L)).thenReturn(0L);
        when(packageCountPort.countActiveByOrgIdAndType(2L, "")).thenReturn(0L);
        when(documentCountPort.countActiveByOrgId(2L)).thenReturn(0L);
        when(userRepository.bumpTokenVersionByOrgId(2L)).thenReturn(0);

        adminOrgAppService.deleteOrg(2L);

        verify(orgRepository).updateStatus(2L, "DELETED");
        verify(userRepository).bumpTokenVersionByOrgId(2L);
    }

    @Test
    void deleteOrgRejectWhenHasUsers() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_SUPER_ADMIN.name());
        UserContext.set(new CurrentUser(1L, 1L, "admin", roles, 0, false));

        when(orgRepository.findById(2L)).thenReturn(Optional.of(
                org(2L, "ORG_X", "测试公司", "上海市", "ACTIVE")));
        when(orgRepository.countUsersByOrgId(2L)).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.deleteOrg(2L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(orgRepository, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    void deleteOrgRejectSystemOrg() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_SUPER_ADMIN.name());
        UserContext.set(new CurrentUser(1L, 1L, "admin", roles, 0, false));

        when(orgRepository.findById(1L)).thenReturn(Optional.of(
                org(1L, "SYSTEM", "系统组织", "系统", "ACTIVE")));

        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.deleteOrg(1L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(orgRepository, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    void nonAdminForbidden() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.BIDDER.name());
        UserContext.set(new CurrentUser(2L, 1L, "bidder", roles, 0, false));

        BizException ex = assertThrows(BizException.class,
                () -> adminOrgAppService.listOrgs("", "", "", 1, 20));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        verify(orgRepository, never()).search(anyString(), anyString(), anyString(),
                anyInt(), anyInt());
    }

    private static Org org(long id, String code, String name, String city, String status) {
        return new Org(id, code, name, city, status, 1L, Instant.now(), Instant.now());
    }

    private static UserAccount adminUser() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        return new UserAccount(1L, 1L, "admin", "hash", "平台管理员",
                null, null, "ACTIVE", false, 0, Instant.now(), Instant.now(), null, roles);
    }
}
