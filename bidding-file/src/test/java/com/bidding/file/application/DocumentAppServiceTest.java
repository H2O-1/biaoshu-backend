package com.bidding.file.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentIndexJobPort;
import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectArtifactInvalidationPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.file.domain.model.Document;
import com.bidding.file.domain.port.DocumentRepository;
import com.bidding.file.domain.port.ObjectStoragePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentAppServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ObjectStoragePort objectStoragePort;

    @Mock
    private PackageLookupPort packageLookupPort;

    @Mock
    private ProjectLookupPort projectLookupPort;

    @Mock
    private DocumentIndexJobPort documentIndexJobPort;

    @Mock
    private DocumentIndexStorePort documentIndexStorePort;

    @Mock
    private ProjectArtifactInvalidationPort projectArtifactInvalidationPort;

    private DocumentAppService documentAppService;

    @BeforeEach
    void setUp() {
        documentAppService = new DocumentAppService(
                documentRepository, objectStoragePort, packageLookupPort,
                projectLookupPort, documentIndexJobPort,
                documentIndexStorePort, projectArtifactInvalidationPort,
                104857600L, 5);
        Set<String> roles = new HashSet<>();
        roles.add("TENDERER");
        UserContext.set(new CurrentUser(10L, 2L, "tenderer01", roles, 0, false));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void uploadRejectWhenPackageFull() {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);
        when(documentRepository.countActiveByPackageId(50L)).thenReturn(5L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "a.pdf", "application/pdf", new byte[]{1, 2, 3});

        BizException ex = assertThrows(BizException.class,
                () -> documentAppService.upload(50L, "招标正文", file));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(objectStoragePort, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void uploadRejectBadExt() {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);
        when(documentRepository.countActiveByPackageId(50L)).thenReturn(0L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "a.txt", "text/plain", new byte[]{1, 2, 3});

        BizException ex = assertThrows(BizException.class,
                () -> documentAppService.upload(50L, "招标正文", file));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void uploadRejectBlankFileType() {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);

        MockMultipartFile file = new MockMultipartFile(
                "file", "a.pdf", "application/pdf", new byte[]{1, 2, 3});

        BizException ex = assertThrows(BizException.class,
                () -> documentAppService.upload(50L, "  ", file));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        assertEquals("文件类型不能为空", ex.getMessage());
        verify(objectStoragePort, never()).put(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void deleteTenderFileObsoletesChecklistAndReview() {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        pkg.setProjectId(100L);
        pkg.setType("TENDER");
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);

        Document saved = new Document(
                1L, 2L, 100L, 50L, "招标.pdf", "application/pdf", "pdf", "招标正文", 3L,
                "org/2/package/50/x_招标.pdf", "SUCCESS", "ACTIVE",
                10L, Instant.now(), Instant.now());
        when(documentRepository.findById(1L)).thenReturn(java.util.Optional.of(saved));
        when(projectArtifactInvalidationPort.hasActiveChecklistOrReviewWork(100L)).thenReturn(false);

        documentAppService.delete(50L, 1L);

        verify(objectStoragePort).delete("org/2/package/50/x_招标.pdf");
        verify(documentRepository).markDeleted(1L);
        verify(documentIndexStorePort).deleteByDocumentId(1L);
        verify(projectArtifactInvalidationPort).obsoleteChecklistsByProjectId(
                eq(100L), anyString());
        verify(projectArtifactInvalidationPort).obsoleteReviewsByProjectId(
                eq(100L), anyString());
    }

    @Test
    void deleteBidFileObsoletesReviewOnly() {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(60L);
        pkg.setOrgId(2L);
        pkg.setProjectId(100L);
        pkg.setType("BID");
        when(packageLookupPort.findActiveById(60L)).thenReturn(pkg);

        Document saved = new Document(
                2L, 2L, 100L, 60L, "投标.pdf", "application/pdf", "pdf", "投标正文", 3L,
                "org/2/package/60/x_投标.pdf", "SUCCESS", "ACTIVE",
                10L, Instant.now(), Instant.now());
        when(documentRepository.findById(2L)).thenReturn(java.util.Optional.of(saved));
        when(projectArtifactInvalidationPort.hasActiveChecklistOrReviewWork(100L)).thenReturn(false);

        documentAppService.delete(60L, 2L);

        verify(documentRepository).markDeleted(2L);
        verify(documentIndexStorePort).deleteByDocumentId(2L);
        verify(projectArtifactInvalidationPort, never())
                .obsoleteChecklistsByProjectId(anyLong(), anyString());
        verify(projectArtifactInvalidationPort).obsoleteReviewsByProjectId(
                eq(100L), anyString());
    }

    @Test
    void deleteTenderFileRejectWhenReviewInProgress() {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        pkg.setProjectId(100L);
        pkg.setType("TENDER");
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);

        Document saved = new Document(
                1L, 2L, 100L, 50L, "招标.pdf", "application/pdf", "pdf", "招标正文", 3L,
                "org/2/package/50/x_招标.pdf", "SUCCESS", "ACTIVE",
                10L, Instant.now(), Instant.now());
        when(documentRepository.findById(1L)).thenReturn(java.util.Optional.of(saved));
        when(projectArtifactInvalidationPort.hasActiveChecklistOrReviewWork(100L)).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> documentAppService.delete(50L, 1L));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(documentRepository, never()).markDeleted(anyLong());
    }

    @Test
    void uploadSuccess() throws Exception {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        pkg.setProjectId(100L);
        pkg.setType("TENDER");
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);
        when(projectArtifactInvalidationPort.hasActiveChecklistOrReviewWork(100L)).thenReturn(false);
        when(documentRepository.countActiveByPackageId(50L)).thenReturn(0L);
        when(documentRepository.insert(eq(2L), eq(100L), eq(50L), eq("招标.pdf"),
                eq("application/pdf"), eq("pdf"), eq("招标正文"), eq(3L), anyString(),
                eq("PENDING"), eq("ACTIVE"), eq(10L))).thenReturn(1L);

        Document saved = new Document(
                1L, 2L, 100L, 50L, "招标.pdf", "application/pdf", "pdf", "招标正文", 3L,
                "org/2/package/50/x_招标.pdf", "PENDING", "ACTIVE",
                10L, Instant.now(), Instant.now());
        when(documentRepository.findById(1L)).thenReturn(java.util.Optional.of(saved));

        MockMultipartFile file = new MockMultipartFile(
                "file", "招标.pdf", "application/pdf", new byte[]{1, 2, 3});

        DocumentAppService.DocumentSummary summary =
                documentAppService.upload(50L, " 招标正文 ", file);

        assertEquals(1L, summary.getId());
        assertEquals("招标.pdf", summary.getFileName());
        assertEquals("招标正文", summary.getFileType());
        assertEquals("PENDING", summary.getIndexStatus());
        verify(objectStoragePort).put(anyString(), any(), eq(3L), eq("application/pdf"));
        verify(projectArtifactInvalidationPort).obsoleteChecklistsByProjectId(
                eq(100L), anyString());
        verify(projectArtifactInvalidationPort).obsoleteReviewsByProjectId(
                eq(100L), anyString());
    }

    @Test
    void platformAdminForbidden() {
        Set<String> roles = new HashSet<>();
        roles.add("PLATFORM_ADMIN");
        UserContext.set(new CurrentUser(1L, 1L, "admin", roles, 0, false));

        MockMultipartFile file = new MockMultipartFile(
                "file", "a.pdf", "application/pdf", new byte[]{1});

        BizException ex = assertThrows(BizException.class,
                () -> documentAppService.upload(50L, "招标正文", file));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        verify(packageLookupPort, never()).findActiveById(anyLong());
    }

    @Test
    void downloadSuccess() throws Exception {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);

        Document doc = new Document(
                1L, 2L, 100L, 50L, "招标.pdf", "application/pdf", "pdf", "招标正文", 3L,
                "org/2/package/50/x.pdf", "PENDING", "ACTIVE",
                10L, Instant.now(), Instant.now());
        when(documentRepository.findById(1L)).thenReturn(java.util.Optional.of(doc));
        when(objectStoragePort.open("org/2/package/50/x.pdf"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        DocumentAppService.FileContent content = documentAppService.openForDownload(50L, 1L);

        assertEquals("招标.pdf", content.getFileName());
        assertEquals("application/pdf", content.getContentType());
        assertEquals(false, content.isInline());
        assertEquals(3, content.getInputStream().readAllBytes().length);
    }

    @Test
    void previewRejectNonPdf() {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);

        Document doc = new Document(
                2L, 2L, 100L, 50L, "表.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "xlsx", "报价表", 4L,
                "org/2/package/50/y.xlsx", "PENDING", "ACTIVE",
                10L, Instant.now(), Instant.now());
        when(documentRepository.findById(2L)).thenReturn(java.util.Optional.of(doc));
        when(objectStoragePort.open("org/2/package/50/y.xlsx"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));

        BizException ex = assertThrows(BizException.class,
                () -> documentAppService.openForPreview(50L, 2L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void previewPdfInline() throws Exception {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);

        Document doc = new Document(
                1L, 2L, 100L, 50L, "招标.pdf", "application/pdf", "pdf", "招标正文", 3L,
                "org/2/package/50/x.pdf", "PENDING", "ACTIVE",
                10L, Instant.now(), Instant.now());
        when(documentRepository.findById(1L)).thenReturn(java.util.Optional.of(doc));
        when(objectStoragePort.open("org/2/package/50/x.pdf"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        DocumentAppService.FileContent content = documentAppService.openForPreview(50L, 1L);

        assertEquals(true, content.isInline());
        assertEquals("pdf", content.getFileExt());
    }

    @Test
    void allocateCopyFileNameAddsSuffixAndAvoidsClash() {
        Set<String> used = new HashSet<>();
        used.add("招标.pdf");
        used.add("招标_副本.pdf");
        assertEquals("招标_副本2.pdf", DocumentAppService.allocateCopyFileName("招标.pdf", used));
        used.clear();
        assertEquals("招标_副本.pdf", DocumentAppService.allocateCopyFileName("招标.pdf", used));
    }

    @Test
    void listTenderCopySourcesSkipsSelfAndEmpty() {
        ProjectLookupPort.ProjectRef current = new ProjectLookupPort.ProjectRef();
        current.setId(10L);
        current.setOrgId(2L);
        current.setName("当前");
        when(projectLookupPort.findActiveById(10L)).thenReturn(current);

        ProjectLookupPort.ProjectRef other = new ProjectLookupPort.ProjectRef();
        other.setId(20L);
        other.setOrgId(2L);
        other.setName("智慧园区一体化平台");
        ProjectLookupPort.ProjectRef empty = new ProjectLookupPort.ProjectRef();
        empty.setId(30L);
        empty.setOrgId(2L);
        empty.setName("空项目");
        List<ProjectLookupPort.ProjectRef> projects = new ArrayList<>();
        projects.add(current);
        projects.add(other);
        projects.add(empty);
        when(projectLookupPort.searchByOrgId(eq(2L), eq(""), eq(0), eq(200))).thenReturn(projects);

        PackageLookupPort.PackageRef otherTender = new PackageLookupPort.PackageRef();
        otherTender.setId(200L);
        otherTender.setOrgId(2L);
        otherTender.setProjectId(20L);
        when(packageLookupPort.findActiveByProjectIdAndType(20L, "TENDER")).thenReturn(otherTender);
        when(documentRepository.countActiveByPackageId(200L)).thenReturn(2L);

        PackageLookupPort.PackageRef emptyTender = new PackageLookupPort.PackageRef();
        emptyTender.setId(300L);
        emptyTender.setOrgId(2L);
        emptyTender.setProjectId(30L);
        when(packageLookupPort.findActiveByProjectIdAndType(30L, "TENDER")).thenReturn(emptyTender);
        when(documentRepository.countActiveByPackageId(300L)).thenReturn(0L);

        List<DocumentAppService.TenderCopySource> sources =
                documentAppService.listTenderCopySources(10L, "");

        assertEquals(1, sources.size());
        assertEquals(20L, sources.get(0).getProjectId());
        assertEquals("智慧园区一体化平台", sources.get(0).getProjectName());
        assertEquals(2L, sources.get(0).getFileCount());
    }

    @Test
    void copyTenderFilesRejectWhenExceedMax() {
        ProjectLookupPort.ProjectRef target = project(10L, "新项目");
        ProjectLookupPort.ProjectRef source = project(20L, "旧项目");
        when(projectLookupPort.findActiveById(10L)).thenReturn(target);
        when(projectLookupPort.findActiveById(20L)).thenReturn(source);

        PackageLookupPort.PackageRef targetPkg = tenderPkg(50L, 10L);
        PackageLookupPort.PackageRef sourcePkg = tenderPkg(60L, 20L);
        when(packageLookupPort.findActiveByProjectIdAndType(10L, "TENDER")).thenReturn(targetPkg);
        when(packageLookupPort.findActiveByProjectIdAndType(20L, "TENDER")).thenReturn(sourcePkg);

        List<Document> sourceDocs = new ArrayList<>();
        sourceDocs.add(doc(1L, 60L, "a.pdf", "k1"));
        sourceDocs.add(doc(2L, 60L, "b.pdf", "k2"));
        when(documentRepository.listByPackageId(60L)).thenReturn(sourceDocs);
        when(documentRepository.countActiveByPackageId(50L)).thenReturn(4L);

        BizException ex = assertThrows(BizException.class,
                () -> documentAppService.copyTenderFilesFromProject(10L, 20L));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(objectStoragePort, never()).put(anyString(), any(), anyLong(), anyString());
        verify(documentIndexJobPort, never()).enqueueIndex(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void copyTenderFilesSuccessRenamesAndEnqueuesIndex() {
        ProjectLookupPort.ProjectRef target = project(10L, "新项目");
        ProjectLookupPort.ProjectRef source = project(20L, "旧项目");
        when(projectLookupPort.findActiveById(10L)).thenReturn(target);
        when(projectLookupPort.findActiveById(20L)).thenReturn(source);

        PackageLookupPort.PackageRef targetPkg = tenderPkg(50L, 10L);
        PackageLookupPort.PackageRef sourcePkg = tenderPkg(60L, 20L);
        when(packageLookupPort.findActiveByProjectIdAndType(10L, "TENDER")).thenReturn(targetPkg);
        when(packageLookupPort.findActiveByProjectIdAndType(20L, "TENDER")).thenReturn(sourcePkg);

        Document src = doc(1L, 60L, "招标.pdf", "src-key");
        List<Document> sourceDocs = new ArrayList<>();
        sourceDocs.add(src);
        when(documentRepository.listByPackageId(60L)).thenReturn(sourceDocs);
        when(documentRepository.countActiveByPackageId(50L)).thenReturn(0L);
        when(documentRepository.listByPackageId(50L)).thenReturn(new ArrayList<>());
        when(projectArtifactInvalidationPort.hasActiveChecklistOrReviewWork(10L)).thenReturn(false);

        when(objectStoragePort.open("src-key"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(documentRepository.insert(eq(2L), eq(10L), eq(50L), eq("招标_副本.pdf"),
                eq("application/pdf"), eq("pdf"), eq("招标正文"), eq(3L), anyString(),
                eq("PENDING"), eq("ACTIVE"), eq(10L))).thenReturn(99L);
        when(documentIndexJobPort.enqueueIndex(eq(2L), eq(10L), eq(99L), eq(10L))).thenReturn(1001L);

        Document copied = new Document(
                99L, 2L, 10L, 50L, "招标_副本.pdf", "application/pdf", "pdf", "招标正文", 3L,
                "new-key", "PROCESSING", "ACTIVE",
                10L, Instant.now(), Instant.now());
        when(documentRepository.findById(99L)).thenReturn(java.util.Optional.of(copied));

        DocumentAppService.TenderCopyResult result =
                documentAppService.copyTenderFilesFromProject(10L, 20L);

        assertEquals(1, result.getCopiedCount());
        assertEquals("招标_副本.pdf", result.getItems().get(0).getFileName());
        assertEquals(1, result.getJobIds().size());
        assertEquals(1001L, result.getJobIds().get(0).longValue());
        verify(objectStoragePort).put(anyString(), any(), eq(3L), eq("application/pdf"));
        verify(documentIndexJobPort).enqueueIndex(2L, 10L, 99L, 10L);
        verify(projectArtifactInvalidationPort).obsoleteChecklistsByProjectId(
                eq(10L), anyString());
        verify(projectArtifactInvalidationPort).obsoleteReviewsByProjectId(
                eq(10L), anyString());
    }

    @Test
    void listBidCopySourcesGroupsPackagesAndSkipsSelf() {
        ProjectLookupPort.ProjectRef current = project(10L, "当前");
        when(projectLookupPort.findActiveById(10L)).thenReturn(current);

        ProjectLookupPort.ProjectRef other = project(20L, "旧项目");
        List<ProjectLookupPort.ProjectRef> projects = new ArrayList<>();
        projects.add(current);
        projects.add(other);
        when(projectLookupPort.searchByOrgId(eq(2L), eq(""), eq(0), eq(200))).thenReturn(projects);

        PackageLookupPort.PackageRef bid1 = bidPkg(71L, 20L, "华建集团");
        PackageLookupPort.PackageRef bid2 = bidPkg(72L, 20L, "空包");
        List<PackageLookupPort.PackageRef> bids = new ArrayList<>();
        bids.add(bid1);
        bids.add(bid2);
        when(packageLookupPort.listActiveByProjectIdAndType(20L, "BID")).thenReturn(bids);
        when(documentRepository.countActiveByPackageId(71L)).thenReturn(3L);
        when(documentRepository.countActiveByPackageId(72L)).thenReturn(0L);

        List<DocumentAppService.BidCopySourceProject> sources =
                documentAppService.listBidCopySources(10L, "");

        assertEquals(1, sources.size());
        assertEquals(20L, sources.get(0).getProjectId());
        assertEquals(1, sources.get(0).getBidPackages().size());
        assertEquals(71L, sources.get(0).getBidPackages().get(0).getPackageId());
        assertEquals("华建集团", sources.get(0).getBidPackages().get(0).getPackageName());
    }

    @Test
    void copyBidPackageRejectWhenExceedMaxWithExistingFiles() {
        ProjectLookupPort.ProjectRef target = project(10L, "新项目");
        when(projectLookupPort.findActiveById(10L)).thenReturn(target);

        PackageLookupPort.PackageRef sourcePkg = bidPkg(71L, 20L, "华建集团");
        when(packageLookupPort.findActiveById(71L)).thenReturn(sourcePkg);
        when(projectLookupPort.findActiveById(20L)).thenReturn(project(20L, "旧项目"));

        PackageLookupPort.PackageRef targetPkg = bidPkg(80L, 10L, "当前投标包");
        when(packageLookupPort.findActiveById(80L)).thenReturn(targetPkg);

        List<Document> sourceDocs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sourceDocs.add(doc(i + 1L, 71L, "f" + i + ".pdf", "k" + i));
        }
        when(documentRepository.listByPackageId(71L)).thenReturn(sourceDocs);
        when(documentRepository.countActiveByPackageId(80L)).thenReturn(3L);

        BizException ex = assertThrows(BizException.class,
                () -> documentAppService.copyBidPackageFrom(10L, 71L, 80L));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(documentRepository, never()).insert(
                anyLong(), anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void copyBidPackageSuccessIntoExistingPackage() {
        ProjectLookupPort.ProjectRef target = project(10L, "新项目");
        when(projectLookupPort.findActiveById(10L)).thenReturn(target);

        PackageLookupPort.PackageRef sourcePkg = bidPkg(71L, 20L, "华建集团");
        when(packageLookupPort.findActiveById(71L)).thenReturn(sourcePkg);
        when(projectLookupPort.findActiveById(20L)).thenReturn(project(20L, "旧项目"));

        PackageLookupPort.PackageRef targetPkg = bidPkg(80L, 10L, "当前投标包");
        when(packageLookupPort.findActiveById(80L)).thenReturn(targetPkg);

        Document src = doc(1L, 71L, "响应.pdf", "src-key");
        List<Document> sourceDocs = new ArrayList<>();
        sourceDocs.add(src);
        when(documentRepository.listByPackageId(71L)).thenReturn(sourceDocs);
        when(documentRepository.countActiveByPackageId(80L)).thenReturn(1L);
        when(projectArtifactInvalidationPort.hasActiveChecklistOrReviewWork(10L)).thenReturn(false);

        Document existing = doc(50L, 80L, "已有.pdf", "old-key");
        List<Document> targetExisting = new ArrayList<>();
        targetExisting.add(existing);
        when(documentRepository.listByPackageId(80L)).thenReturn(targetExisting);

        when(objectStoragePort.open("src-key"))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(documentRepository.insert(eq(2L), eq(10L), eq(80L), eq("响应_副本.pdf"),
                eq("application/pdf"), eq("pdf"), eq("招标正文"), eq(3L), anyString(),
                eq("PENDING"), eq("ACTIVE"), eq(10L))).thenReturn(99L);
        when(documentIndexJobPort.enqueueIndex(eq(2L), eq(10L), eq(99L), eq(10L))).thenReturn(2001L);

        Document copied = new Document(
                99L, 2L, 10L, 80L, "响应_副本.pdf", "application/pdf", "pdf", "招标正文", 3L,
                "new-key", "PROCESSING", "ACTIVE",
                10L, Instant.now(), Instant.now());
        when(documentRepository.findById(99L)).thenReturn(java.util.Optional.of(copied));

        DocumentAppService.BidCopyResult result =
                documentAppService.copyBidPackageFrom(10L, 71L, 80L);

        assertEquals(80L, result.getTargetPackageId());
        assertEquals("当前投标包", result.getPackageName());
        assertEquals(1, result.getCopiedCount());
        assertEquals(2001L, result.getJobIds().get(0).longValue());
        assertEquals(80L, result.getItems().get(0).getPackageId());
        verify(projectArtifactInvalidationPort, never())
                .obsoleteChecklistsByProjectId(anyLong(), anyString());
        verify(projectArtifactInvalidationPort).obsoleteReviewsByProjectId(
                eq(10L), anyString());
    }

    private static ProjectLookupPort.ProjectRef project(long id, String name) {
        ProjectLookupPort.ProjectRef ref = new ProjectLookupPort.ProjectRef();
        ref.setId(id);
        ref.setOrgId(2L);
        ref.setName(name);
        ref.setStatus("ACTIVE");
        return ref;
    }

    private static PackageLookupPort.PackageRef tenderPkg(long id, long projectId) {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(id);
        pkg.setOrgId(2L);
        pkg.setProjectId(projectId);
        pkg.setType("TENDER");
        pkg.setStatus("ACTIVE");
        return pkg;
    }

    private static PackageLookupPort.PackageRef bidPkg(long id, long projectId, String name) {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(id);
        pkg.setOrgId(2L);
        pkg.setProjectId(projectId);
        pkg.setType("BID");
        pkg.setStatus("ACTIVE");
        pkg.setName(name);
        return pkg;
    }

    private static Document doc(long id, long packageId, String name, String key) {
        return new Document(
                id, 2L, 20L, packageId, name, "application/pdf", "pdf", "招标正文", 3L,
                key, "SUCCESS", "ACTIVE",
                10L, Instant.now(), Instant.now());
    }
}
