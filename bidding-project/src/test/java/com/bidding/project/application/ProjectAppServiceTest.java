package com.bidding.project.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentCountPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.project.domain.model.BizPackage;
import com.bidding.project.domain.model.Project;
import com.bidding.project.domain.port.PackageRepository;
import com.bidding.project.domain.port.ProjectRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAppServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private PackageRepository packageRepository;

    @Mock
    private DocumentCountPort documentCountPort;

    @InjectMocks
    private ProjectAppService projectAppService;

    @BeforeEach
    void setUp() {
        Set<String> roles = new HashSet<>();
        roles.add("TENDERER");
        UserContext.set(new CurrentUser(10L, 2L, "tenderer01", roles, 0, false));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void platformAdminForbidden() {
        Set<String> roles = new HashSet<>();
        roles.add("PLATFORM_ADMIN");
        UserContext.set(new CurrentUser(1L, 1L, "admin", roles, 0, false));

        BizException ex = assertThrows(BizException.class,
                () -> projectAppService.list("", "", 1, 20));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
    }

    @Test
    void createSuccess_bindsTenderPackage() {
        when(packageRepository.findById(50L)).thenReturn(Optional.of(
                unboundTender(50L, 2L, "市政招标文件包")));
        when(projectRepository.existsByOrgIdAndName(eq(2L), eq("市政道路改造"), isNull()))
                .thenReturn(false);
        when(projectRepository.insert(eq(2L), eq("市政道路改造"), eq("某某代理公司"),
                eq("公开招标"), eq("张三"), eq("ACTIVE"), eq(10L))).thenReturn(100L);
        when(packageRepository.bindToProjectIfUnbound(50L, 100L, "TENDER")).thenReturn(1);

        Project created = project(100L, 2L, "市政道路改造", "ACTIVE");
        when(projectRepository.findById(100L)).thenReturn(Optional.of(created));
        when(packageRepository.findByProjectIdAndType(100L, "TENDER"))
                .thenReturn(Optional.of(boundTender(50L, 2L, 100L, "市政招标文件包")));

        ProjectAppService.ProjectSummary summary = projectAppService.create(
                "市政道路改造", "某某代理公司", "公开招标", "张三", 50L);

        assertEquals(100L, summary.getId());
        assertEquals(2L, summary.getOrgId());
        assertEquals(50L, summary.getTenderPackageId());
        assertEquals(1L, summary.getTenderPackageCount());
        verify(packageRepository).bindToProjectIfUnbound(50L, 100L, "TENDER");
    }

    @Test
    void createRejectWhenPackageAlreadyBound() {
        when(packageRepository.findById(50L)).thenReturn(Optional.of(
                boundTender(50L, 2L, 99L, "已占用包")));

        BizException ex = assertThrows(BizException.class,
                () -> projectAppService.create("市政道路改造", "代理", "公开招标", "张三", 50L));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(projectRepository, never()).insert(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void createRejectDuplicateName() {
        when(packageRepository.findById(50L)).thenReturn(Optional.of(
                unboundTender(50L, 2L, "包")));
        when(projectRepository.existsByOrgIdAndName(eq(2L), eq("市政道路改造"), isNull()))
                .thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> projectAppService.create("市政道路改造", "代理", "公开招标", "张三", 50L));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(projectRepository, never()).insert(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void getRejectCrossOrg() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(
                project(100L, 9L, "别的组织项目", "ACTIVE")));

        BizException ex = assertThrows(BizException.class, () -> projectAppService.get(100L));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    void updateAllowedWhenDisabled() {
        Project disabled = project(100L, 2L, "旧名称", "DISABLED");
        Project after = new Project(100L, 2L, "新名称", "新代理", "邀请招标", "李四",
                "DISABLED", 10L, Instant.now(), Instant.now());
        when(projectRepository.findById(100L))
                .thenReturn(Optional.of(disabled))
                .thenReturn(Optional.of(after));
        when(projectRepository.existsByOrgIdAndName(eq(2L), eq("新名称"), eq(100L)))
                .thenReturn(false);
        when(packageRepository.findByProjectIdAndType(100L, "TENDER"))
                .thenReturn(Optional.of(boundTender(50L, 2L, 100L, "包")));

        ProjectAppService.ProjectSummary summary = projectAppService.update(
                100L, "新名称", "新代理", "邀请招标", "李四");

        assertEquals("新名称", summary.getName());
        assertEquals("DISABLED", summary.getStatus());
        verify(projectRepository).updateBasicInfo(100L, "新名称", "新代理", "邀请招标", "李四");
    }

    @Test
    void listOk() {
        when(projectRepository.searchByOrgId(eq(2L), eq(""), eq(""), eq(0), eq(20)))
                .thenReturn(List.of(project(100L, 2L, "市政道路改造", "ACTIVE")));
        when(projectRepository.countSearchByOrgId(eq(2L), eq(""), eq(""))).thenReturn(1L);
        when(packageRepository.findByProjectIdAndType(100L, "TENDER"))
                .thenReturn(Optional.empty());

        PageResult<ProjectAppService.ProjectSummary> page =
                projectAppService.list("", "", 1, 20);

        assertEquals(1, page.getItems().size());
        assertEquals(1L, page.getTotal());
    }

    private static Project project(long id, long orgId, String name, String status) {
        return new Project(id, orgId, name, "代理机构", "公开招标", "张三",
                status, 10L, Instant.now(), Instant.now());
    }

    private static BizPackage unboundTender(long id, long orgId, String name) {
        return new BizPackage(id, orgId, null, "TENDER", name,
                null, null, "ACTIVE", 10L, Instant.now(), Instant.now());
    }

    private static BizPackage boundTender(long id, long orgId, long projectId, String name) {
        return new BizPackage(id, orgId, projectId, "TENDER", name,
                null, null, "ACTIVE", 10L, Instant.now(), Instant.now());
    }
}
