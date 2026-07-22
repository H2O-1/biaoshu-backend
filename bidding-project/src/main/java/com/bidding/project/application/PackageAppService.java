package com.bidding.project.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.project.domain.model.BizPackage;
import com.bidding.project.domain.port.PackageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 标包应用服务。
 * 招标包 / 投标包：创建时均不绑项目；项目侧再选绑。
 * 仅招标方可操作；平台管理员无 TENDERER 时 403。
 */
@Service
public class PackageAppService {

    public static final String TYPE_TENDER = "TENDER";
    public static final String TYPE_BID = "BID";

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DELETED = "DELETED";

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_CONTACT_NAME_LENGTH = 64;
    private static final int MAX_CONTACT_PHONE_LENGTH = 32;

    private final PackageRepository packageRepository;

    public PackageAppService(PackageRepository packageRepository) {
        this.packageRepository = packageRepository;
    }

    /**
     * 创建招标包：挂当前组织，project_id 为空。
     */
    @Transactional
    public PackageSummary createTender(String name) {
        CurrentUser operator = checkTenderer();
        String safeName = normalizeName(name, "包名称");

        if (packageRepository.existsByOrgIdAndTypeAndName(
                operator.getOrgId(), TYPE_TENDER, safeName, null)) {
            throw new BizException(ErrorCode.CONFLICT, "本组织下招标包名称已存在");
        }

        long id = packageRepository.insert(
                operator.getOrgId(),
                null,
                TYPE_TENDER,
                safeName,
                null,
                null,
                STATUS_ACTIVE,
                operator.getUserId()
        );
        return toSummary(requireOwnedPackage(id, operator.getOrgId()));
    }

    /**
     * 创建投标包：挂当前组织，project_id 为空；公司名同组织唯一。
     * name = 投标公司名称。
     */
    @Transactional
    public PackageSummary createBid(String name, String contactName, String contactPhone) {
        CurrentUser operator = checkTenderer();
        String safeName = normalizeName(name, "投标公司名称");
        String safeContact = normalizeName(contactName, "负责人姓名", MAX_CONTACT_NAME_LENGTH);
        String safePhone = normalizeName(contactPhone, "负责人电话", MAX_CONTACT_PHONE_LENGTH);

        if (packageRepository.existsByOrgIdAndTypeAndName(
                operator.getOrgId(), TYPE_BID, safeName, null)) {
            throw new BizException(ErrorCode.CONFLICT, "本组织下投标公司名称已存在");
        }

        long id = packageRepository.insert(
                operator.getOrgId(),
                null,
                TYPE_BID,
                safeName,
                safeContact,
                safePhone,
                STATUS_ACTIVE,
                operator.getUserId()
        );
        return toSummary(requireOwnedPackage(id, operator.getOrgId()));
    }

    /**
     * 标包分页列表（按 type）。
     *
     * @param unboundOnly true=只看未绑定项目的
     */
    @Transactional(readOnly = true)
    public PageResult<PackageSummary> list(String type, String keyword, boolean unboundOnly,
                                           int page, int size) {
        CurrentUser operator = checkTenderer();
        String safeType = requireSupportedType(type);

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;

        String kw = keyword == null ? "" : keyword.trim();

        List<BizPackage> packages = packageRepository.searchByOrgId(
                operator.getOrgId(), safeType, kw, unboundOnly, offset, safeSize);
        List<PackageSummary> items = new ArrayList<>();
        for (BizPackage pkg : packages) {
            items.add(toSummary(pkg));
        }
        long total = packageRepository.countSearchByOrgId(
                operator.getOrgId(), safeType, kw, unboundOnly);
        return new PageResult<>(items, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public PackageSummary get(long packageId) {
        CurrentUser operator = checkTenderer();
        return toSummary(requireOwnedPackage(packageId, operator.getOrgId()));
    }

    /** 改招标包名（已绑定仍可改） */
    @Transactional
    public PackageSummary updateTenderName(long packageId, String name) {
        CurrentUser operator = checkTenderer();
        BizPackage pkg = requireOwnedPackage(packageId, operator.getOrgId());
        if (!pkg.isTender()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该接口仅用于修改招标包");
        }

        String safeName = normalizeName(name, "包名称");
        if (packageRepository.existsByOrgIdAndTypeAndName(
                operator.getOrgId(), TYPE_TENDER, safeName, packageId)) {
            throw new BizException(ErrorCode.CONFLICT, "本组织下招标包名称已存在");
        }

        packageRepository.updateName(packageId, safeName);
        return toSummary(requireOwnedPackage(packageId, operator.getOrgId()));
    }

    /** 改投标包：公司名 / 负责人 / 电话（已绑定仍可改） */
    @Transactional
    public PackageSummary updateBid(long packageId, String name,
                                    String contactName, String contactPhone) {
        CurrentUser operator = checkTenderer();
        BizPackage pkg = requireOwnedPackage(packageId, operator.getOrgId());
        if (!pkg.isBid()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该接口仅用于修改投标包");
        }

        String safeName = normalizeName(name, "投标公司名称");
        String safeContact = normalizeName(contactName, "负责人姓名", MAX_CONTACT_NAME_LENGTH);
        String safePhone = normalizeName(contactPhone, "负责人电话", MAX_CONTACT_PHONE_LENGTH);

        if (packageRepository.existsByOrgIdAndTypeAndName(
                operator.getOrgId(), TYPE_BID, safeName, packageId)) {
            throw new BizException(ErrorCode.CONFLICT, "本组织下投标公司名称已存在");
        }

        packageRepository.updateBidInfo(packageId, safeName, safeContact, safePhone);
        return toSummary(requireOwnedPackage(packageId, operator.getOrgId()));
    }

    /**
     * 逻辑删除标包。
     * 招标包：已绑定项目不可删。
     * 投标包：可直接删（已绑则从项目侧消失）。
     */
    @Transactional
    public void delete(long packageId) {
        CurrentUser operator = checkTenderer();
        BizPackage pkg = requireOwnedPackage(packageId, operator.getOrgId());
        if (pkg.isTender() && pkg.isBoundToProject()) {
            throw new BizException(ErrorCode.CONFLICT, "招标包已绑定项目，请先删除项目后再删招标包");
        }
        if (pkg.isDeleted()) {
            return;
        }
        packageRepository.updateStatus(packageId, STATUS_DELETED);
    }

    private PackageSummary toSummary(BizPackage pkg) {
        PackageSummary summary = new PackageSummary();
        summary.setId(pkg.getId());
        summary.setOrgId(pkg.getOrgId());
        summary.setProjectId(pkg.getProjectId());
        summary.setType(pkg.getType());
        summary.setName(pkg.getName());
        summary.setContactName(pkg.getContactName());
        summary.setContactPhone(pkg.getContactPhone());
        summary.setStatus(pkg.getStatus());
        summary.setCreatedBy(pkg.getCreatedBy());
        summary.setCreatedAt(pkg.getCreatedAt());
        summary.setUpdatedAt(pkg.getUpdatedAt());
        // 文件模块未建，占位
        summary.setFileCount(0);
        return summary;
    }

    private String requireSupportedType(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 不能为空");
        }
        String normalized = type.trim().toUpperCase(java.util.Locale.ROOT);
        if (!TYPE_TENDER.equals(normalized) && !TYPE_BID.equals(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 仅支持 TENDER 或 BID");
        }
        return normalized;
    }

    private BizPackage requireOwnedPackage(long packageId, long orgId) {
        Optional<BizPackage> optional = packageRepository.findById(packageId);
        if (optional.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "标包不存在");
        }
        BizPackage pkg = optional.get();
        if (pkg.getOrgId() != orgId || pkg.isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "标包不存在");
        }
        return pkg;
    }

    private CurrentUser checkTenderer() {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.isTenderer()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅招标方可操作标包");
        }
        return user;
    }

    private String normalizeName(String name, String label) {
        return normalizeName(name, label, MAX_NAME_LENGTH);
    }

    private String normalizeName(String name, String label, int maxLength) {
        if (name == null || name.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > maxLength) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "过长");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new BizException(ErrorCode.BAD_REQUEST, label + "包含非法字符");
            }
        }
        return trimmed;
    }

    /** 标包摘要（含 fileCount 占位） */
    public static class PackageSummary {
        private long id;
        private long orgId;
        private Long projectId;
        private String type;
        private String name;
        private String contactName;
        private String contactPhone;
        private String status;
        private long createdBy;
        private Instant createdAt;
        private Instant updatedAt;
        private long fileCount;

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

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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

        public long getFileCount() {
            return fileCount;
        }

        public void setFileCount(long fileCount) {
            this.fileCount = fileCount;
        }
    }
}
