package com.bidding.file.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentIndexJobPort;
import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectArtifactInvalidationPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import com.bidding.file.domain.model.Document;
import com.bidding.file.domain.port.DocumentRepository;
import com.bidding.file.domain.port.ObjectStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 标包下文件：上传 / 列表 / 删除 / 下载 / PDF 预览 / 从历史项目复制招标·投标文件。
 * 仅招标方可操作；单文件 ≤ 配置上限；每包文件数有上限。
 * 预览仅支持 PDF；Word/Excel 请下载。
 */
@Service
public class DocumentAppService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAppService.class);

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String INDEX_PENDING = "PENDING";
    private static final String PACKAGE_TYPE_TENDER = "TENDER";
    private static final String PACKAGE_TYPE_BID = "BID";
    private static final int COPY_SOURCE_PROJECT_LIMIT = 200;

    private static final Set<String> ALLOWED_EXT = new HashSet<>();

    static {
        ALLOWED_EXT.add("pdf");
        ALLOWED_EXT.add("doc");
        ALLOWED_EXT.add("docx");
        ALLOWED_EXT.add("xls");
        ALLOWED_EXT.add("xlsx");
    }

    private final DocumentRepository documentRepository;
    private final ObjectStoragePort objectStoragePort;
    private final PackageLookupPort packageLookupPort;
    private final ProjectLookupPort projectLookupPort;
    private final DocumentIndexJobPort documentIndexJobPort;
    private final DocumentIndexStorePort documentIndexStorePort;
    private final ProjectArtifactInvalidationPort projectArtifactInvalidationPort;
    private final long maxFileBytes;
    private final int maxFilesPerPackage;

    public DocumentAppService(DocumentRepository documentRepository,
                              ObjectStoragePort objectStoragePort,
                              PackageLookupPort packageLookupPort,
                              ProjectLookupPort projectLookupPort,
                              DocumentIndexJobPort documentIndexJobPort,
                              DocumentIndexStorePort documentIndexStorePort,
                              ProjectArtifactInvalidationPort projectArtifactInvalidationPort,
                              @Value("${bidding.file.max-size-bytes:104857600}") long maxFileBytes,
                              @Value("${bidding.file.max-files-per-package:5}") int maxFilesPerPackage) {
        this.documentRepository = documentRepository;
        this.objectStoragePort = objectStoragePort;
        this.packageLookupPort = packageLookupPort;
        this.projectLookupPort = projectLookupPort;
        this.documentIndexJobPort = documentIndexJobPort;
        this.documentIndexStorePort = documentIndexStorePort;
        this.projectArtifactInvalidationPort = projectArtifactInvalidationPort;
        this.maxFileBytes = maxFileBytes;
        this.maxFilesPerPackage = maxFilesPerPackage;
    }

    @Transactional
    public DocumentSummary upload(long packageId, String fileType, MultipartFile file) {
        long t0 = System.currentTimeMillis();
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作文件");
        PackageLookupPort.PackageRef pkg = TendererAuth.requireOwnedPackage(
                packageLookupPort, packageId, operator.getOrgId());
        requireNoActiveChecklistOrReview(pkg.getProjectId(), "项目审查进行中，请稍后再上传文件");

        String safeFileType = normalizeFileType(fileType);

        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }

        long size = file.getSize();
        if (size <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (size > maxFileBytes) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单文件不能超过 " + (maxFileBytes / (1024 * 1024)) + "MB");
        }

        long existing = documentRepository.countActiveByPackageId(packageId);
        if (existing >= maxFilesPerPackage) {
            throw new BizException(ErrorCode.CONFLICT, "每个标包最多上传 " + maxFilesPerPackage + " 个文件");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件名无效");
        }
        String safeName = sanitizeFileName(originalName.trim());
        String ext = extractExt(safeName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 PDF / Word / Excel 文件");
        }

        String contentType = file.getContentType();
        String objectKey = buildObjectKey(operator.getOrgId(), packageId, safeName);

        try (InputStream in = file.getInputStream()) {
            objectStoragePort.put(objectKey, in, size, contentType);
        } catch (IOException e) {
            log.error("上传读流失败 packageId={} fileName={}", packageId, safeName, e);
            throw new BizException(ErrorCode.INTERNAL, "读取上传文件失败");
        } catch (RuntimeException e) {
            log.error("上传写对象存储失败 packageId={} fileName={}", packageId, safeName, e);
            throw new BizException(ErrorCode.INTERNAL, "文件写入对象存储失败");
        }

        long id;
        try {
            id = documentRepository.insert(
                    operator.getOrgId(),
                    pkg.getProjectId(),
                    packageId,
                    safeName,
                    contentType,
                    ext,
                    safeFileType,
                    size,
                    objectKey,
                    INDEX_PENDING,
                    STATUS_ACTIVE,
                    operator.getUserId()
            );
        } catch (RuntimeException e) {
            try {
                objectStoragePort.delete(objectKey);
            } catch (RuntimeException ignored) {
                // ignore
            }
            throw e;
        }

        log.info("上传文件成功 packageId={} projectId={} documentId={} fileName={} sizeBytes={} costMs={}",
                packageId, pkg.getProjectId(), id, safeName, size, System.currentTimeMillis() - t0);
        invalidateArtifactsAfterFileChange(pkg, "项目文件已变更");
        return toSummary(requireOwnedDocument(id, operator.getOrgId()));
    }

    /**
     * 内部分析文件上传：调用方已校验组织与内部标包；上传后自动排队 INDEX。
     */
    @Transactional
    public InternalUploadResult uploadTenderAnalysisFile(long orgId,
                                                        long packageId,
                                                        String fileType,
                                                        MultipartFile file,
                                                        long createdBy) {
        long t0 = System.currentTimeMillis();
        String safeFileType = normalizeFileType(fileType);
        StoredDocument stored = storeUploadedDocument(
                orgId, null, packageId, safeFileType, file, createdBy);
        long jobId = documentIndexJobPort.enqueueIndex(orgId, null, stored.documentId, createdBy);
        InternalUploadResult result = new InternalUploadResult();
        result.setDocumentId(stored.documentId);
        result.setJobId(jobId);
        result.setFileName(stored.fileName);
        result.setFileType(safeFileType);
        log.info("招标解析上传文件成功 packageId={} documentId={} jobId={} fileName={} costMs={}",
                packageId, stored.documentId, jobId, stored.fileName, System.currentTimeMillis() - t0);
        return result;
    }

    /**
     * 历史项目列表（供「从历史项目选择招标文件」）：本组织其它未删除项目中，
     * 招标包下至少有 1 个未删除文件的项目；展示项目名与文件数。
     */
    @Transactional(readOnly = true)
    public List<TenderCopySource> listTenderCopySources(long currentProjectId, String keyword) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作文件");
        TendererAuth.requireOwnedProject(projectLookupPort, currentProjectId, operator.getOrgId());

        String kw = keyword == null ? "" : keyword.trim();
        List<ProjectLookupPort.ProjectRef> projects = projectLookupPort.searchByOrgId(
                operator.getOrgId(), kw, 0, COPY_SOURCE_PROJECT_LIMIT);

        List<TenderCopySource> result = new ArrayList<>();
        if (projects == null) {
            return result;
        }
        for (int i = 0; i < projects.size(); i++) {
            ProjectLookupPort.ProjectRef project = projects.get(i);
            if (project == null || project.getId() == currentProjectId) {
                continue;
            }
            PackageLookupPort.PackageRef tenderPkg = packageLookupPort.findActiveByProjectIdAndType(
                    project.getId(), PACKAGE_TYPE_TENDER);
            if (tenderPkg == null || tenderPkg.getOrgId() != operator.getOrgId()) {
                continue;
            }
            long fileCount = documentRepository.countActiveByPackageId(tenderPkg.getId());
            if (fileCount < 1) {
                continue;
            }
            TenderCopySource item = new TenderCopySource();
            item.setProjectId(project.getId());
            item.setProjectName(project.getName());
            item.setTenderPackageId(tenderPkg.getId());
            item.setFileCount(fileCount);
            result.add(item);
        }
        return result;
    }

    /**
     * 从历史项目一键复制其招标包下全部文件到当前项目招标包。
     * 副本自动改名（原名_副本…）；超每包上限则整单失败；复制后自动排队 INDEX。
     */
    @Transactional
    public TenderCopyResult copyTenderFilesFromProject(long targetProjectId, long sourceProjectId) {
        long t0 = System.currentTimeMillis();
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作文件");
        TendererAuth.requireOwnedProject(projectLookupPort, targetProjectId, operator.getOrgId());
        TendererAuth.requireOwnedProject(projectLookupPort, sourceProjectId, operator.getOrgId());

        if (targetProjectId == sourceProjectId) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能从当前项目复制到自身");
        }
        requireNoActiveChecklistOrReview(targetProjectId, "项目审查进行中，请稍后再复制文件");

        PackageLookupPort.PackageRef targetPkg = packageLookupPort.findActiveByProjectIdAndType(
                targetProjectId, PACKAGE_TYPE_TENDER);
        if (targetPkg == null || targetPkg.getOrgId() != operator.getOrgId()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前项目未绑定招标包，无法复制文件");
        }
        if (targetPkg.getProjectId() == null || targetPkg.getProjectId() != targetProjectId) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前项目未绑定招标包，无法复制文件");
        }

        PackageLookupPort.PackageRef sourcePkg = packageLookupPort.findActiveByProjectIdAndType(
                sourceProjectId, PACKAGE_TYPE_TENDER);
        if (sourcePkg == null || sourcePkg.getOrgId() != operator.getOrgId()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "来源项目没有可用的招标包");
        }

        List<Document> sourceDocs = documentRepository.listByPackageId(sourcePkg.getId());
        if (sourceDocs == null || sourceDocs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "来源项目招标包下没有可复制的文件");
        }

        long existing = documentRepository.countActiveByPackageId(targetPkg.getId());
        if (existing + sourceDocs.size() > maxFilesPerPackage) {
            throw new BizException(ErrorCode.CONFLICT,
                    "复制后将超过每个标包最多 " + maxFilesPerPackage + " 个文件的限制");
        }

        Set<String> usedNames = new HashSet<>();
        List<Document> targetExisting = documentRepository.listByPackageId(targetPkg.getId());
        if (targetExisting != null) {
            for (int i = 0; i < targetExisting.size(); i++) {
                Document d = targetExisting.get(i);
                if (d.getFileName() != null) {
                    usedNames.add(d.getFileName());
                }
            }
        }

        List<String> putKeys = new ArrayList<>();
        List<DocumentSummary> copied = new ArrayList<>();
        List<Long> jobIds = new ArrayList<>();
        try {
            copyDocumentsIntoPackage(
                    operator,
                    targetProjectId,
                    targetPkg.getId(),
                    sourceDocs,
                    usedNames,
                    putKeys,
                    copied,
                    jobIds
            );
        } catch (RuntimeException ex) {
            if (!(ex instanceof BizException)) {
                log.error("复制招标文件失败 targetProjectId={} sourceProjectId={} putCount={}",
                        targetProjectId, sourceProjectId, putKeys.size(), ex);
            }
            for (int k = 0; k < putKeys.size(); k++) {
                try {
                    objectStoragePort.delete(putKeys.get(k));
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            throw ex;
        }

        TenderCopyResult result = new TenderCopyResult();
        result.setItems(copied);
        result.setJobIds(jobIds);
        result.setCopiedCount(copied.size());
        log.info("复制招标文件成功 targetProjectId={} sourceProjectId={} copiedCount={} costMs={}",
                targetProjectId, sourceProjectId, copied.size(), System.currentTimeMillis() - t0);
        invalidateArtifactsAfterFileChange(targetPkg, "招标文件已复制变更");
        return result;
    }

    /**
     * 历史投标包来源：本组织其它项目下、有文件的投标包（按项目分组）。
     * 不含当前项目；空包不返回（前端也会限制）。
     */
    @Transactional(readOnly = true)
    public List<BidCopySourceProject> listBidCopySources(long currentProjectId, String keyword) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作文件");
        TendererAuth.requireOwnedProject(projectLookupPort, currentProjectId, operator.getOrgId());

        String kw = keyword == null ? "" : keyword.trim();
        List<ProjectLookupPort.ProjectRef> projects = projectLookupPort.searchByOrgId(
                operator.getOrgId(), kw, 0, COPY_SOURCE_PROJECT_LIMIT);

        List<BidCopySourceProject> result = new ArrayList<>();
        if (projects == null) {
            return result;
        }
        for (int i = 0; i < projects.size(); i++) {
            ProjectLookupPort.ProjectRef project = projects.get(i);
            if (project == null || project.getId() == currentProjectId) {
                continue;
            }
            List<PackageLookupPort.PackageRef> bidPkgs = packageLookupPort.listActiveByProjectIdAndType(
                    project.getId(), PACKAGE_TYPE_BID);
            if (bidPkgs == null || bidPkgs.isEmpty()) {
                continue;
            }
            List<BidCopySourcePackage> packages = new ArrayList<>();
            for (int p = 0; p < bidPkgs.size(); p++) {
                PackageLookupPort.PackageRef bidPkg = bidPkgs.get(p);
                if (bidPkg == null || bidPkg.getOrgId() != operator.getOrgId()) {
                    continue;
                }
                long fileCount = documentRepository.countActiveByPackageId(bidPkg.getId());
                if (fileCount < 1) {
                    continue;
                }
                BidCopySourcePackage item = new BidCopySourcePackage();
                item.setPackageId(bidPkg.getId());
                item.setPackageName(bidPkg.getName());
                item.setFileCount(fileCount);
                item.setContactName(bidPkg.getContactName());
                item.setContactPhone(bidPkg.getContactPhone());
                item.setCompanyAddress(bidPkg.getCompanyAddress());
                item.setPriceWithTax(bidPkg.getPriceWithTax());
                item.setTaxRate(bidPkg.getTaxRate());
                item.setPriceWithoutTax(bidPkg.getPriceWithoutTax());
                packages.add(item);
            }
            if (packages.isEmpty()) {
                continue;
            }
            BidCopySourceProject source = new BidCopySourceProject();
            source.setProjectId(project.getId());
            source.setProjectName(project.getName());
            source.setBidPackages(packages);
            result.add(source);
        }
        return result;
    }

    /**
     * 从历史项目的某个投标包，把全部文件复制到当前项目「已有」的目标投标包。
     * 不新建包；目标包可已有文件；超每包上限则整单失败；复制后自动 INDEX。
     */
    @Transactional
    public BidCopyResult copyBidPackageFrom(long targetProjectId,
                                            long sourcePackageId,
                                            long targetPackageId) {
        long t0 = System.currentTimeMillis();
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作文件");
        TendererAuth.requireOwnedProject(projectLookupPort, targetProjectId, operator.getOrgId());
        requireNoActiveChecklistOrReview(targetProjectId, "项目审查进行中，请稍后再复制文件");

        PackageLookupPort.PackageRef sourcePkg = packageLookupPort.findActiveById(sourcePackageId);
        if (sourcePkg == null || sourcePkg.getOrgId() != operator.getOrgId()) {
            throw new BizException(ErrorCode.NOT_FOUND, "来源标包不存在");
        }
        if (!PACKAGE_TYPE_BID.equalsIgnoreCase(sourcePkg.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持从投标包复制");
        }
        if (sourcePkg.getProjectId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "来源投标包未绑定项目");
        }
        if (sourcePkg.getProjectId() == targetProjectId) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能从来源为当前项目的投标包复制");
        }
        TendererAuth.requireOwnedProject(
                projectLookupPort, sourcePkg.getProjectId(), operator.getOrgId());

        PackageLookupPort.PackageRef targetPkg = packageLookupPort.findActiveById(targetPackageId);
        if (targetPkg == null || targetPkg.getOrgId() != operator.getOrgId()) {
            throw new BizException(ErrorCode.NOT_FOUND, "目标标包不存在");
        }
        if (!PACKAGE_TYPE_BID.equalsIgnoreCase(targetPkg.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标必须是投标包");
        }
        if (targetPkg.getProjectId() == null || targetPkg.getProjectId() != targetProjectId) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标投标包未绑定到当前项目");
        }

        List<Document> sourceDocs = documentRepository.listByPackageId(sourcePkg.getId());
        if (sourceDocs == null || sourceDocs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "来源投标包下没有可复制的文件");
        }

        long existing = documentRepository.countActiveByPackageId(targetPkg.getId());
        if (existing + sourceDocs.size() > maxFilesPerPackage) {
            throw new BizException(ErrorCode.CONFLICT,
                    "复制后将超过每个标包最多 " + maxFilesPerPackage + " 个文件的限制");
        }

        Set<String> usedNames = new HashSet<>();
        List<Document> targetExisting = documentRepository.listByPackageId(targetPkg.getId());
        if (targetExisting != null) {
            for (int i = 0; i < targetExisting.size(); i++) {
                Document d = targetExisting.get(i);
                if (d.getFileName() != null) {
                    usedNames.add(d.getFileName());
                }
            }
        }

        List<String> putKeys = new ArrayList<>();
        List<DocumentSummary> copied = new ArrayList<>();
        List<Long> jobIds = new ArrayList<>();
        try {
            copyDocumentsIntoPackage(
                    operator,
                    targetProjectId,
                    targetPkg.getId(),
                    sourceDocs,
                    usedNames,
                    putKeys,
                    copied,
                    jobIds
            );
        } catch (RuntimeException ex) {
            if (!(ex instanceof BizException)) {
                log.error("复制投标文件失败 targetProjectId={} sourcePackageId={} targetPackageId={} putCount={}",
                        targetProjectId, sourcePackageId, targetPackageId, putKeys.size(), ex);
            }
            for (int k = 0; k < putKeys.size(); k++) {
                try {
                    objectStoragePort.delete(putKeys.get(k));
                } catch (RuntimeException ignored) {
                    // ignore
                }
            }
            throw ex;
        }

        BidCopyResult result = new BidCopyResult();
        result.setTargetPackageId(targetPkg.getId());
        result.setPackageName(targetPkg.getName());
        result.setItems(copied);
        result.setJobIds(jobIds);
        result.setCopiedCount(copied.size());
        log.info("复制投标文件成功 targetProjectId={} sourcePackageId={} targetPackageId={} copiedCount={} costMs={}",
                targetProjectId, sourcePackageId, targetPackageId, copied.size(),
                System.currentTimeMillis() - t0);
        invalidateArtifactsAfterFileChange(targetPkg, "投标文件已复制变更");
        return result;
    }

    /**
     * 将源文档列表复制进目标标包（自动改名 + 入队 INDEX）。
     * putKeys / copied / jobIds 由调用方传入，失败时由调用方清理对象存储。
     */
    private void copyDocumentsIntoPackage(CurrentUser operator,
                                          long targetProjectId,
                                          long targetPackageId,
                                          List<Document> sourceDocs,
                                          Set<String> usedNames,
                                          List<String> putKeys,
                                          List<DocumentSummary> copied,
                                          List<Long> jobIds) {
        for (int i = 0; i < sourceDocs.size(); i++) {
            Document src = sourceDocs.get(i);
            String copyName = allocateCopyFileName(src.getFileName(), usedNames);
            usedNames.add(copyName);
            String ext = extractExt(copyName);
            String objectKey = buildObjectKey(operator.getOrgId(), targetPackageId, copyName);

            try (InputStream in = objectStoragePort.open(src.getObjectKey())) {
                objectStoragePort.put(objectKey, in, src.getSizeBytes(), src.getContentType());
            } catch (IOException e) {
                log.error("复制文件流失败 sourceDocumentId={} objectKey={}", src.getId(), src.getObjectKey(), e);
                throw new BizException(ErrorCode.INTERNAL, "复制文件流失败");
            } catch (RuntimeException e) {
                log.error("复制对象存储失败 sourceDocumentId={} sourceKey={} targetKey={}",
                        src.getId(), src.getObjectKey(), objectKey, e);
                throw new BizException(ErrorCode.INTERNAL, "复制对象存储文件失败");
            }
            putKeys.add(objectKey);

            String fileType = src.getFileType();
            if (fileType == null || fileType.isBlank()) {
                fileType = "文件";
            }
            long newId = documentRepository.insert(
                    operator.getOrgId(),
                    targetProjectId,
                    targetPackageId,
                    copyName,
                    src.getContentType(),
                    ext,
                    fileType,
                    src.getSizeBytes(),
                    objectKey,
                    INDEX_PENDING,
                    STATUS_ACTIVE,
                    operator.getUserId()
            );

            long jobId = documentIndexJobPort.enqueueIndex(
                    operator.getOrgId(),
                    targetProjectId,
                    newId,
                    operator.getUserId()
            );
            jobIds.add(jobId);
            copied.add(toSummary(requireOwnedDocument(newId, operator.getOrgId())));
        }
    }

    private StoredDocument storeUploadedDocument(long orgId,
                                                 Long projectId,
                                                 long packageId,
                                                 String fileType,
                                                 MultipartFile file,
                                                 long createdBy) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }

        long size = file.getSize();
        if (size <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (size > maxFileBytes) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单文件不能超过 " + (maxFileBytes / (1024 * 1024)) + "MB");
        }

        long existing = documentRepository.countActiveByPackageId(packageId);
        if (existing >= maxFilesPerPackage) {
            throw new BizException(ErrorCode.CONFLICT, "每个标包最多上传 " + maxFilesPerPackage + " 个文件");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件名无效");
        }
        String safeName = sanitizeFileName(originalName.trim());
        String ext = extractExt(safeName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 PDF / Word / Excel 文件");
        }

        String contentType = file.getContentType();
        String objectKey = buildObjectKey(orgId, packageId, safeName);

        try (InputStream in = file.getInputStream()) {
            objectStoragePort.put(objectKey, in, size, contentType);
        } catch (IOException e) {
            log.error("上传读流失败 packageId={} fileName={}", packageId, safeName, e);
            throw new BizException(ErrorCode.INTERNAL, "读取上传文件失败");
        } catch (RuntimeException e) {
            log.error("上传写对象存储失败 packageId={} fileName={}", packageId, safeName, e);
            throw new BizException(ErrorCode.INTERNAL, "文件写入对象存储失败");
        }

        long id;
        try {
            id = documentRepository.insert(
                    orgId,
                    projectId,
                    packageId,
                    safeName,
                    contentType,
                    ext,
                    fileType,
                    size,
                    objectKey,
                    INDEX_PENDING,
                    STATUS_ACTIVE,
                    createdBy
            );
        } catch (RuntimeException e) {
            try {
                objectStoragePort.delete(objectKey);
            } catch (RuntimeException ignored) {
                // ignore
            }
            throw e;
        }

        StoredDocument stored = new StoredDocument();
        stored.documentId = id;
        stored.fileName = safeName;
        return stored;
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> listByPackage(long packageId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作文件");
        TendererAuth.requireOwnedPackage(packageLookupPort, packageId, operator.getOrgId());

        List<Document> docs = documentRepository.listByPackageId(packageId);
        List<DocumentSummary> items = new ArrayList<>();
        for (Document doc : docs) {
            items.add(toSummary(doc));
        }
        return items;
    }

    @Transactional
    public void delete(long packageId, long documentId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作文件");
        PackageLookupPort.PackageRef pkg = TendererAuth.requireOwnedPackage(
                packageLookupPort, packageId, operator.getOrgId());
        Document doc = requireOwnedDocument(documentId, operator.getOrgId());
        if (doc.getPackageId() != packageId) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        if (doc.isDeleted()) {
            return;
        }

        Long projectId = doc.getProjectId();
        if (projectId == null) {
            projectId = pkg.getProjectId();
        }
        requireNoActiveChecklistOrReview(projectId, "项目审查进行中，请稍后再删文件");

        try {
            objectStoragePort.delete(doc.getObjectKey());
        } catch (RuntimeException e) {
            log.error("删除对象存储失败 packageId={} documentId={} objectKey={}",
                    packageId, documentId, doc.getObjectKey(), e);
            throw new BizException(ErrorCode.INTERNAL, "删除对象存储中的文件失败");
        }
        documentRepository.markDeleted(documentId);
        documentIndexStorePort.deleteByDocumentId(documentId);

        invalidateArtifactsAfterFileChange(pkg, "项目文件已删除");
        log.info("删除文件成功 packageId={} documentId={} type={} projectId={}",
                packageId, documentId, pkg.getType(), projectId);
    }

    /**
     * 已绑项目且建清单/审查进行中时禁止改文件（不含 INDEX）。
     */
    private void requireNoActiveChecklistOrReview(Long projectId, String message) {
        if (projectId == null) {
            return;
        }
        if (projectArtifactInvalidationPort.hasActiveChecklistOrReviewWork(projectId)) {
            throw new BizException(ErrorCode.CONFLICT, message);
        }
    }

    /**
     * 文件变更后作废产物：招标 → 清单+审查；投标 → 仅审查。未绑项目跳过。
     * OBSOLETE 不删行，详情仍可按 id 查看。
     */
    private void invalidateArtifactsAfterFileChange(PackageLookupPort.PackageRef pkg, String reason) {
        if (pkg == null || pkg.getProjectId() == null) {
            return;
        }
        long projectId = pkg.getProjectId();
        String msg = reason == null ? "项目文件已变更" : reason.trim();
        if (PACKAGE_TYPE_TENDER.equalsIgnoreCase(pkg.getType())) {
            projectArtifactInvalidationPort.obsoleteChecklistsByProjectId(
                    projectId, msg + "，请重新解析并审查");
            projectArtifactInvalidationPort.obsoleteReviewsByProjectId(
                    projectId, msg + "，请重新审查");
        } else if (PACKAGE_TYPE_BID.equalsIgnoreCase(pkg.getType())) {
            projectArtifactInvalidationPort.obsoleteReviewsByProjectId(
                    projectId, msg + "，请重新审查");
        }
    }

    @Transactional(readOnly = true)
    public FileContent openForDownload(long packageId, long documentId) {
        return openContent(packageId, documentId, false);
    }

    @Transactional(readOnly = true)
    public FileContent openForPreview(long packageId, long documentId) {
        FileContent content = openContent(packageId, documentId, true);
        if (!"pdf".equalsIgnoreCase(content.getFileExt())) {
            try {
                content.getInputStream().close();
            } catch (IOException ignored) {
                // ignore
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持预览 PDF，Word/Excel 请下载后查看");
        }
        return content;
    }

    private FileContent openContent(long packageId, long documentId, boolean inline) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作文件");
        TendererAuth.requireOwnedPackage(packageLookupPort, packageId, operator.getOrgId());
        Document doc = requireOwnedDocument(documentId, operator.getOrgId());
        if (doc.getPackageId() != packageId) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }

        InputStream stream;
        try {
            stream = objectStoragePort.open(doc.getObjectKey());
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取对象存储文件失败");
        }

        String contentType = doc.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = guessContentType(doc.getFileExt());
        }

        FileContent content = new FileContent();
        content.setInputStream(stream);
        content.setFileName(doc.getFileName());
        content.setFileExt(doc.getFileExt());
        content.setContentType(contentType);
        content.setSizeBytes(doc.getSizeBytes());
        content.setInline(inline);
        return content;
    }

    /** 生成副本文件名：原名_副本.ext；冲突则 _副本2、_副本3… */
    static String allocateCopyFileName(String originalName, Set<String> usedNames) {
        String safe = originalName == null ? "未命名.pdf" : originalName.trim();
        if (safe.isEmpty()) {
            safe = "未命名.pdf";
        }
        int dot = safe.lastIndexOf('.');
        String base;
        String ext;
        if (dot > 0 && dot < safe.length() - 1) {
            base = safe.substring(0, dot);
            ext = safe.substring(dot + 1);
        } else {
            base = safe;
            ext = "bin";
        }
        String candidate = base + "_副本." + ext;
        if (usedNames == null || !usedNames.contains(candidate)) {
            return candidate;
        }
        int n = 2;
        while (true) {
            candidate = base + "_副本" + n + "." + ext;
            if (!usedNames.contains(candidate)) {
                return candidate;
            }
            n++;
            if (n > 1000) {
                throw new BizException(ErrorCode.CONFLICT, "无法生成不冲突的副本文件名");
            }
        }
    }

    private String guessContentType(String ext) {
        if ("pdf".equalsIgnoreCase(ext)) {
            return "application/pdf";
        }
        if ("doc".equalsIgnoreCase(ext)) {
            return "application/msword";
        }
        if ("docx".equalsIgnoreCase(ext)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if ("xls".equalsIgnoreCase(ext)) {
            return "application/vnd.ms-excel";
        }
        if ("xlsx".equalsIgnoreCase(ext)) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }

    private DocumentSummary toSummary(Document doc) {
        DocumentSummary summary = new DocumentSummary();
        summary.setId(doc.getId());
        summary.setOrgId(doc.getOrgId());
        summary.setProjectId(doc.getProjectId());
        summary.setPackageId(doc.getPackageId());
        summary.setFileName(doc.getFileName());
        summary.setContentType(doc.getContentType());
        summary.setFileExt(doc.getFileExt());
        summary.setFileType(doc.getFileType());
        summary.setSizeBytes(doc.getSizeBytes());
        summary.setIndexStatus(doc.getIndexStatus());
        summary.setStatus(doc.getStatus());
        summary.setCreatedBy(doc.getCreatedBy());
        summary.setCreatedAt(doc.getCreatedAt());
        summary.setUpdatedAt(doc.getUpdatedAt());
        return summary;
    }

    private Document requireOwnedDocument(long documentId, long orgId) {
        Optional<Document> optional = documentRepository.findById(documentId);
        if (optional.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        Document doc = optional.get();
        if (doc.getOrgId() != orgId || doc.isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return doc;
    }

    private String normalizeFileType(String fileType) {
        if (fileType == null || fileType.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件类型不能为空");
        }
        String trimmed = fileType.trim();
        if (trimmed.length() > 128) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件类型过长");
        }
        return trimmed;
    }

    private String sanitizeFileName(String name) {
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0 && slash < base.length() - 1) {
            base = base.substring(slash + 1);
        }
        if (base.isEmpty() || base.length() > 256) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件名无效或过长");
        }
        for (int i = 0; i < base.length(); i++) {
            if (Character.isISOControl(base.charAt(i))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文件名包含非法字符");
            }
        }
        return base;
    }

    private String extractExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件缺少扩展名");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String buildObjectKey(long orgId, long packageId, String fileName) {
        return "org/" + orgId + "/package/" + packageId + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + "_" + fileName;
    }

    public static class TenderCopySource {
        private long projectId;
        private String projectName;
        private long tenderPackageId;
        private long fileCount;

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public long getTenderPackageId() {
            return tenderPackageId;
        }

        public void setTenderPackageId(long tenderPackageId) {
            this.tenderPackageId = tenderPackageId;
        }

        public long getFileCount() {
            return fileCount;
        }

        public void setFileCount(long fileCount) {
            this.fileCount = fileCount;
        }
    }

    public static class TenderCopyResult {
        private List<DocumentSummary> items = new ArrayList<>();
        private List<Long> jobIds = new ArrayList<>();
        private int copiedCount;

        public List<DocumentSummary> getItems() {
            return items;
        }

        public void setItems(List<DocumentSummary> items) {
            if (items == null) {
                this.items = new ArrayList<>();
            } else {
                this.items = items;
            }
        }

        public List<Long> getJobIds() {
            return jobIds;
        }

        public void setJobIds(List<Long> jobIds) {
            if (jobIds == null) {
                this.jobIds = new ArrayList<>();
            } else {
                this.jobIds = jobIds;
            }
        }

        public int getCopiedCount() {
            return copiedCount;
        }

        public void setCopiedCount(int copiedCount) {
            this.copiedCount = copiedCount;
        }
    }

    /** 历史投标复制：按项目分组的来源。 */
    public static class BidCopySourceProject {
        private long projectId;
        private String projectName;
        private List<BidCopySourcePackage> bidPackages = new ArrayList<>();

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public List<BidCopySourcePackage> getBidPackages() {
            return bidPackages;
        }

        public void setBidPackages(List<BidCopySourcePackage> bidPackages) {
            if (bidPackages == null) {
                this.bidPackages = new ArrayList<>();
            } else {
                this.bidPackages = bidPackages;
            }
        }
    }

    /** 历史投标复制：单个可复制的整包。 */
    public static class BidCopySourcePackage {
        private long packageId;
        private String packageName;
        private long fileCount;
        private String contactName;
        private String contactPhone;
        private String companyAddress;
        private BigDecimal priceWithTax;
        private BigDecimal taxRate;
        private BigDecimal priceWithoutTax;

        public long getPackageId() {
            return packageId;
        }

        public void setPackageId(long packageId) {
            this.packageId = packageId;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public long getFileCount() {
            return fileCount;
        }

        public void setFileCount(long fileCount) {
            this.fileCount = fileCount;
        }

        public String getContactName() {
            return contactName;
        }

        public void setContactName(String contactName) {
            this.contactName = contactName;
        }

        public String getContactPhone() {
            return contactPhone;
        }

        public void setContactPhone(String contactPhone) {
            this.contactPhone = contactPhone;
        }

        public String getCompanyAddress() {
            return companyAddress;
        }

        public void setCompanyAddress(String companyAddress) {
            this.companyAddress = companyAddress;
        }

        public BigDecimal getPriceWithTax() {
            return priceWithTax;
        }

        public void setPriceWithTax(BigDecimal priceWithTax) {
            this.priceWithTax = priceWithTax;
        }

        public BigDecimal getTaxRate() {
            return taxRate;
        }

        public void setTaxRate(BigDecimal taxRate) {
            this.taxRate = taxRate;
        }

        public BigDecimal getPriceWithoutTax() {
            return priceWithoutTax;
        }

        public void setPriceWithoutTax(BigDecimal priceWithoutTax) {
            this.priceWithoutTax = priceWithoutTax;
        }
    }

    /** 投标文件复制结果（拷入已有目标包）。 */
    public static class BidCopyResult {
        /** 目标投标包 ID（当前项目已有包）。 */
        private long targetPackageId;
        private String packageName;
        private List<DocumentSummary> items = new ArrayList<>();
        private List<Long> jobIds = new ArrayList<>();
        private int copiedCount;

        public long getTargetPackageId() {
            return targetPackageId;
        }

        public void setTargetPackageId(long targetPackageId) {
            this.targetPackageId = targetPackageId;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public List<DocumentSummary> getItems() {
            return items;
        }

        public void setItems(List<DocumentSummary> items) {
            if (items == null) {
                this.items = new ArrayList<>();
            } else {
                this.items = items;
            }
        }

        public List<Long> getJobIds() {
            return jobIds;
        }

        public void setJobIds(List<Long> jobIds) {
            if (jobIds == null) {
                this.jobIds = new ArrayList<>();
            } else {
                this.jobIds = jobIds;
            }
        }

        public int getCopiedCount() {
            return copiedCount;
        }

        public void setCopiedCount(int copiedCount) {
            this.copiedCount = copiedCount;
        }
    }

    public static class DocumentSummary {
        private long id;
        private long orgId;
        private Long projectId;
        private long packageId;
        private String fileName;
        private String contentType;
        private String fileExt;
        private String fileType;
        private long sizeBytes;
        private String indexStatus;
        private String status;
        private long createdBy;
        private Instant createdAt;
        private Instant updatedAt;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getOrgId() {
            return orgId;
        }

        public void setOrgId(long orgId) {
            this.orgId = orgId;
        }

        public Long getProjectId() {
            return projectId;
        }

        public void setProjectId(Long projectId) {
            this.projectId = projectId;
        }

        public long getPackageId() {
            return packageId;
        }

        public void setPackageId(long packageId) {
            this.packageId = packageId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public String getFileExt() {
            return fileExt;
        }

        public void setFileExt(String fileExt) {
            this.fileExt = fileExt;
        }

        public String getFileType() {
            return fileType;
        }

        public void setFileType(String fileType) {
            this.fileType = fileType;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public String getIndexStatus() {
            return indexStatus;
        }

        public void setIndexStatus(String indexStatus) {
            this.indexStatus = indexStatus;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(long createdBy) {
            this.createdBy = createdBy;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    public static class InternalUploadResult {
        private long documentId;
        private long jobId;
        private String fileName;
        private String fileType;

        public long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(long documentId) {
            this.documentId = documentId;
        }

        public long getJobId() {
            return jobId;
        }

        public void setJobId(long jobId) {
            this.jobId = jobId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getFileType() {
            return fileType;
        }

        public void setFileType(String fileType) {
            this.fileType = fileType;
        }
    }

    private static class StoredDocument {
        private long documentId;
        private String fileName;
    }

    public static class FileContent {
        private InputStream inputStream;
        private String fileName;
        private String fileExt;
        private String contentType;
        private long sizeBytes;
        private boolean inline;

        public InputStream getInputStream() {
            return inputStream;
        }

        public void setInputStream(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getFileExt() {
            return fileExt;
        }

        public void setFileExt(String fileExt) {
            this.fileExt = fileExt;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public boolean isInline() {
            return inline;
        }

        public void setInline(boolean inline) {
            this.inline = inline;
        }
    }
}
