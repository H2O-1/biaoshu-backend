package com.bidding.project.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentCascadePort;
import com.bidding.common.port.DocumentCountPort;
import com.bidding.common.port.ProjectArtifactInvalidationPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.project.domain.model.BizPackage;
import com.bidding.project.domain.port.PackageRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageAppServiceTest {

    @Mock
    private PackageRepository packageRepository;

    @Mock
    private DocumentCountPort documentCountPort;

    @Mock
    private DocumentCascadePort documentCascadePort;

    @Mock
    private ProjectArtifactInvalidationPort projectArtifactInvalidationPort;

    @InjectMocks
    private PackageAppService packageAppService;

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
                () -> packageAppService.list("TENDER", "", false, 1, 20));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
    }

    @Test
    void createTenderSuccess() {
        when(packageRepository.existsByOrgIdAndTypeAndName(
                eq(2L), eq("TENDER"), eq("市政招标文件包"), isNull())).thenReturn(false);
        when(packageRepository.insert(eq(2L), isNull(), eq("TENDER"), eq("市政招标文件包"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("ACTIVE"), eq(10L))).thenReturn(50L);

        BizPackage created = tender(50L, 2L, null, "市政招标文件包");
        when(packageRepository.findById(50L)).thenReturn(Optional.of(created));
        when(documentCountPort.countActiveByPackageId(50L)).thenReturn(0L);

        PackageAppService.PackageSummary summary = packageAppService.createTender("市政招标文件包");

        assertEquals(50L, summary.getId());
        assertEquals("TENDER", summary.getType());
        assertNull(summary.getProjectId());
        assertEquals(0L, summary.getFileCount());
    }

    @Test
    void createRejectDuplicateName() {
        when(packageRepository.existsByOrgIdAndTypeAndName(
                eq(2L), eq("TENDER"), eq("重复包"), isNull())).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> packageAppService.createTender("重复包"));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(packageRepository, never()).insert(
                anyLong(), isNull(), anyString(), anyString(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                anyString(), anyLong());
    }

    @Test
    void createBidSuccess() {
        when(packageRepository.existsByOrgIdAndTypeAndName(
                eq(2L), eq("BID"), eq("华建集团"), isNull())).thenReturn(false);
        when(packageRepository.insert(eq(2L), isNull(), eq("BID"), eq("华建集团"),
                eq("王五"), eq("13800001111"), isNull(), isNull(), isNull(), isNull(),
                eq("ACTIVE"), eq(10L))).thenReturn(60L);

        BizPackage created = bid(60L, 2L, null, "华建集团");
        when(packageRepository.findById(60L)).thenReturn(Optional.of(created));
        when(documentCountPort.countActiveByPackageId(60L)).thenReturn(0L);

        PackageAppService.PackageSummary summary =
                packageAppService.createBid("华建集团", "王五", "13800001111",
                        null, null, null, null);

        assertEquals(60L, summary.getId());
        assertEquals("BID", summary.getType());
        assertNull(summary.getProjectId());
        assertEquals("王五", summary.getContactName());
    }

    @Test
    void createTenderAutoNameWhenBlank() {
        when(packageRepository.existsByOrgIdAndTypeAndName(
                eq(2L), eq("TENDER"), anyString(), isNull())).thenReturn(false);
        when(packageRepository.insert(eq(2L), isNull(), eq("TENDER"), anyString(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("ACTIVE"), eq(10L))).thenReturn(52L);
        when(packageRepository.findById(52L)).thenReturn(Optional.of(
                tender(52L, 2L, null, "未命名-20260729-1351")));
        when(documentCountPort.countActiveByPackageId(52L)).thenReturn(0L);

        PackageAppService.PackageSummary summary = packageAppService.createTender("  ");

        assertEquals(52L, summary.getId());
        org.mockito.ArgumentCaptor<String> nameCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(packageRepository).insert(eq(2L), isNull(), eq("TENDER"), nameCaptor.capture(),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("ACTIVE"), eq(10L));
        org.junit.jupiter.api.Assertions.assertTrue(nameCaptor.getValue().startsWith("未命名-"));
    }

    @Test
    void createBidAllowsBlankContact() {
        when(packageRepository.existsByOrgIdAndTypeAndName(
                eq(2L), eq("BID"), eq("华建集团"), isNull())).thenReturn(false);
        when(packageRepository.insert(eq(2L), isNull(), eq("BID"), eq("华建集团"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq("ACTIVE"), eq(10L))).thenReturn(61L);

        BizPackage created = new BizPackage(61L, 2L, null, "BID", "华建集团",
                null, null, null, null, null, null,
                "ACTIVE", 10L, Instant.now(), Instant.now());
        when(packageRepository.findById(61L)).thenReturn(Optional.of(created));
        when(documentCountPort.countActiveByPackageId(61L)).thenReturn(0L);

        PackageAppService.PackageSummary summary =
                packageAppService.createBid("华建集团", "  ", null,
                        null, null, null, null);

        assertEquals(61L, summary.getId());
        assertNull(summary.getContactName());
        assertNull(summary.getContactPhone());
    }

    @Test
    void deleteBidAllowedWhenBound() {
        when(packageRepository.findById(60L)).thenReturn(Optional.of(
                bid(60L, 2L, 100L, "华建集团")));
        when(projectArtifactInvalidationPort.hasActiveAiWork(100L)).thenReturn(false);

        packageAppService.delete(60L);

        verify(documentCascadePort).softDeleteAllActiveByPackageId(60L);
        verify(projectArtifactInvalidationPort).deleteReviewItemsByPackageId(100L, 60L);
        verify(projectArtifactInvalidationPort).obsoleteReviewsByProjectId(
                eq(100L), anyString());
        verify(packageRepository).updateStatus(60L, "DELETED");
    }

    @Test
    void deleteBidCascadesFilesWhenHasFiles() {
        when(packageRepository.findById(60L)).thenReturn(Optional.of(
                bid(60L, 2L, 100L, "华建集团")));
        when(projectArtifactInvalidationPort.hasActiveAiWork(100L)).thenReturn(false);

        packageAppService.delete(60L);

        verify(documentCascadePort).softDeleteAllActiveByPackageId(60L);
        verify(packageRepository).updateStatus(60L, "DELETED");
    }

    @Test
    void deleteBidRejectWhenReviewInProgress() {
        when(packageRepository.findById(60L)).thenReturn(Optional.of(
                bid(60L, 2L, 100L, "华建集团")));
        when(projectArtifactInvalidationPort.hasActiveAiWork(100L)).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> packageAppService.delete(60L));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(packageRepository, never()).updateStatus(anyLong(), anyString());
        verify(documentCascadePort, never()).softDeleteAllActiveByPackageId(anyLong());
    }

    @Test
    void deleteRejectWhenTenderBound() {
        when(packageRepository.findById(50L)).thenReturn(Optional.of(
                tender(50L, 2L, 100L, "已绑包")));

        BizException ex = assertThrows(BizException.class,
                () -> packageAppService.delete(50L));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(packageRepository, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    void deleteUnboundTenderSuccess() {
        when(packageRepository.findById(50L)).thenReturn(Optional.of(
                tender(50L, 2L, null, "未绑包")));

        packageAppService.delete(50L);

        verify(documentCascadePort).softDeleteAllActiveByPackageId(50L);
        verify(packageRepository).updateStatus(50L, "DELETED");
        verify(projectArtifactInvalidationPort, never())
                .obsoleteReviewsByProjectId(anyLong(), anyString());
    }

    @Test
    void listUnboundOnly() {
        when(packageRepository.searchByOrgId(eq(2L), eq("TENDER"), eq(""), eq(true), eq(0), eq(20)))
                .thenReturn(List.of(tender(51L, 2L, null, "可选包")));
        when(packageRepository.countSearchByOrgId(eq(2L), eq("TENDER"), eq(""), eq(true)))
                .thenReturn(1L);
        when(documentCountPort.countActiveByPackageId(51L)).thenReturn(0L);

        PageResult<PackageAppService.PackageSummary> page =
                packageAppService.list("TENDER", "", true, 1, 20);

        assertEquals(1, page.getItems().size());
        assertNull(page.getItems().get(0).getProjectId());
        verify(packageRepository).searchByOrgId(eq(2L), eq("TENDER"), eq(""), eq(true), anyInt(), anyInt());
    }

    @Test
    void getRejectCrossOrg() {
        when(packageRepository.findById(50L)).thenReturn(Optional.of(
                tender(50L, 9L, null, "别的组织")));

        BizException ex = assertThrows(BizException.class, () -> packageAppService.get(50L));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    private static BizPackage tender(long id, long orgId, Long projectId, String name) {
        return new BizPackage(id, orgId, projectId, "TENDER", name,
                null, null, null, null, null, null,
                "ACTIVE", 10L, Instant.now(), Instant.now());
    }

    private static BizPackage bid(long id, long orgId, Long projectId, String name) {
        return new BizPackage(id, orgId, projectId, "BID", name,
                "王五", "13800001111", null, null, null, null,
                "ACTIVE", 10L, Instant.now(), Instant.now());
    }
}
