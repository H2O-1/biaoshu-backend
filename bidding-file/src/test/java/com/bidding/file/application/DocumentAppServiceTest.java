package com.bidding.file.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.file.domain.port.DocumentRepository;
import com.bidding.file.domain.port.ObjectStoragePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    private DocumentAppService documentAppService;

    @BeforeEach
    void setUp() {
        documentAppService = new DocumentAppService(
                documentRepository, objectStoragePort, packageLookupPort,
                52428800L, 5);
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
                () -> documentAppService.upload(50L, file));
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
                () -> documentAppService.upload(50L, file));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void uploadSuccess() throws Exception {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        pkg.setProjectId(100L);
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);
        when(documentRepository.countActiveByPackageId(50L)).thenReturn(0L);
        when(documentRepository.insert(eq(2L), eq(100L), eq(50L), eq("招标.pdf"),
                eq("application/pdf"), eq("pdf"), eq(3L), anyString(),
                eq("PENDING"), eq("ACTIVE"), eq(10L))).thenReturn(1L);

        com.bidding.file.domain.model.Document saved =
                new com.bidding.file.domain.model.Document(
                        1L, 2L, 100L, 50L, "招标.pdf", "application/pdf", "pdf", 3L,
                        "org/2/package/50/x_招标.pdf", "PENDING", "ACTIVE",
                        10L, java.time.Instant.now(), java.time.Instant.now());
        when(documentRepository.findById(1L)).thenReturn(java.util.Optional.of(saved));

        MockMultipartFile file = new MockMultipartFile(
                "file", "招标.pdf", "application/pdf", new byte[]{1, 2, 3});

        DocumentAppService.DocumentSummary summary = documentAppService.upload(50L, file);

        assertEquals(1L, summary.getId());
        assertEquals("招标.pdf", summary.getFileName());
        assertEquals("PENDING", summary.getIndexStatus());
        verify(objectStoragePort).put(anyString(), any(), eq(3L), eq("application/pdf"));
    }

    @Test
    void platformAdminForbidden() {
        Set<String> roles = new HashSet<>();
        roles.add("PLATFORM_ADMIN");
        UserContext.set(new CurrentUser(1L, 1L, "admin", roles, 0, false));

        MockMultipartFile file = new MockMultipartFile(
                "file", "a.pdf", "application/pdf", new byte[]{1});

        BizException ex = assertThrows(BizException.class,
                () -> documentAppService.upload(50L, file));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        verify(packageLookupPort, never()).findActiveById(anyLong());
    }

    @Test
    void downloadSuccess() throws Exception {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);

        com.bidding.file.domain.model.Document doc =
                new com.bidding.file.domain.model.Document(
                        1L, 2L, 100L, 50L, "招标.pdf", "application/pdf", "pdf", 3L,
                        "org/2/package/50/x.pdf", "PENDING", "ACTIVE",
                        10L, java.time.Instant.now(), java.time.Instant.now());
        when(documentRepository.findById(1L)).thenReturn(java.util.Optional.of(doc));
        when(objectStoragePort.open("org/2/package/50/x.pdf"))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));

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

        com.bidding.file.domain.model.Document doc =
                new com.bidding.file.domain.model.Document(
                        2L, 2L, 100L, 50L, "表.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "xlsx", 4L,
                        "org/2/package/50/y.xlsx", "PENDING", "ACTIVE",
                        10L, java.time.Instant.now(), java.time.Instant.now());
        when(documentRepository.findById(2L)).thenReturn(java.util.Optional.of(doc));
        when(objectStoragePort.open("org/2/package/50/y.xlsx"))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3, 4}));

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

        com.bidding.file.domain.model.Document doc =
                new com.bidding.file.domain.model.Document(
                        1L, 2L, 100L, 50L, "招标.pdf", "application/pdf", "pdf", 3L,
                        "org/2/package/50/x.pdf", "PENDING", "ACTIVE",
                        10L, java.time.Instant.now(), java.time.Instant.now());
        when(documentRepository.findById(1L)).thenReturn(java.util.Optional.of(doc));
        when(objectStoragePort.open("org/2/package/50/x.pdf"))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));

        DocumentAppService.FileContent content = documentAppService.openForPreview(50L, 1L);

        assertEquals(true, content.isInline());
        assertEquals("pdf", content.getFileExt());
    }
}
