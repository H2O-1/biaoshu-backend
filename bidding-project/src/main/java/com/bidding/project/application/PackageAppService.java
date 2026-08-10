package com.bidding.project.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentCascadePort;
import com.bidding.common.port.DocumentCountPort;
import com.bidding.common.port.ProjectArtifactInvalidationPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import com.bidding.project.domain.model.BizPackage;
import com.bidding.project.domain.port.PackageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 标包应用服务。
 * 招标包 / 投标包：创建时均不绑项目；项目侧再选绑。
 * 创建时 name / 联系人可空；name 空则自动生成。
 * 仅招标方可操作；平台管理员无 TENDERER 时 403。
 * 删包时级联逻辑删除包内文件；已绑项目的投标包还会作废该包审查并退回待重审。
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
    private static final int MAX_COMPANY_ADDRESS_LENGTH = 256;
    private static final int MAX_PRICE_SCALE = 2;
    private static final int MAX_TAX_RATE_SCALE = 4;

    private static final ZoneId NAME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter AUTO_NAME_MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter AUTO_NAME_SECOND =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final PackageRepository packageRepository;
    private final DocumentCountPort documentCountPort;
    private final DocumentCascadePort documentCascadePort;
    private final ProjectArtifactInvalidationPort projectArtifactInvalidationPort;

    public PackageAppService(PackageRepository packageRepository,
                             DocumentCountPort documentCountPort,
                             DocumentCascadePort documentCascadePort,
                             ProjectArtifactInvalidationPort projectArtifactInvalidationPort) {
        this.packageRepository = packageRepository;
        this.documentCountPort = documentCountPort;
        this.documentCascadePort = documentCascadePort;
        this.projectArtifactInvalidationPort = projectArtifactInvalidationPort;
    }

    /**
     * 创建招标包：挂当前组织，project_id 为空；name 可空，空则自动生成。
     */
    @Transactional
    public PackageSummary createTender(String name) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作标包");
        String safeName = resolveCreateName(operator.getOrgId(), TYPE_TENDER, name, "包名称");

        long id = packageRepository.insert(
                operator.getOrgId(),
                null,
                TYPE_TENDER,
                safeName,
                null,
                null,
                null,
                null,
                null,
                null,
                STATUS_ACTIVE,
                operator.getUserId()
        );
        return toSummary(requireOwnedPackage(id, operator.getOrgId()));
    }

    /**
     * 创建投标包：挂当前组织，project_id 为空；公司名同组织唯一。
     * name / contact / 地址 / 报价均可空；name 空则自动生成。
     * 不含税价由前端计算后传入，后端只做非负与精度校验。
     */
    @Transactional
    public PackageSummary createBid(String name,
                                    String contactName,
                                    String contactPhone,
                                    String companyAddress,
                                    BigDecimal priceWithTax,
                                    BigDecimal taxRate,
                                    BigDecimal priceWithoutTax) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作标包");
        String safeName = resolveCreateName(operator.getOrgId(), TYPE_BID, name, "投标公司名称");
        String safeContact = normalizeOptionalText(contactName, "负责人姓名", MAX_CONTACT_NAME_LENGTH);
        String safePhone = normalizeOptionalText(contactPhone, "负责人电话", MAX_CONTACT_PHONE_LENGTH);
        String safeAddress = normalizeOptionalText(
                companyAddress, "公司地址", MAX_COMPANY_ADDRESS_LENGTH);
        BigDecimal safePriceWithTax = normalizeOptionalMoney(priceWithTax, "含税价格");
        BigDecimal safeTaxRate = normalizeOptionalTaxRate(taxRate);
        BigDecimal safePriceWithoutTax = normalizeOptionalMoney(priceWithoutTax, "不含税价");

        long id = packageRepository.insert(
                operator.getOrgId(),
                null,
                TYPE_BID,
                safeName,
                safeContact,
                safePhone,
                safeAddress,
                safePriceWithTax,
                safeTaxRate,
                safePriceWithoutTax,
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
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作标包");
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
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作标包");
        return toSummary(requireOwnedPackage(packageId, operator.getOrgId()));
    }

    /** 改招标包名称（已绑定仍可改） */
    @Transactional
    public PackageSummary updateTenderName(long packageId, String name) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作标包");
        BizPackage pkg = requireOwnedPackage(packageId, operator.getOrgId());
        if (!pkg.isTender()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该接口仅用于修改招标包");
        }

        String safeName = normalizeRequiredText(name, "包名称", MAX_NAME_LENGTH);
        if (packageRepository.existsByOrgIdAndTypeAndName(
                operator.getOrgId(), TYPE_TENDER, safeName, packageId)) {
            throw new BizException(ErrorCode.CONFLICT, "本组织下招标包名称已存在");
        }

        packageRepository.updateName(packageId, safeName);
        return toSummary(requireOwnedPackage(packageId, operator.getOrgId()));
    }

    /** 改投标包：公司名 / 负责人 / 电话 / 地址 / 报价（已绑定仍可改） */
    @Transactional
    public PackageSummary updateBid(long packageId, String name,
                                    String contactName, String contactPhone,
                                    String companyAddress,
                                    BigDecimal priceWithTax,
                                    BigDecimal taxRate,
                                    BigDecimal priceWithoutTax) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作标包");
        BizPackage pkg = requireOwnedPackage(packageId, operator.getOrgId());
        if (!pkg.isBid()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该接口仅用于修改投标包");
        }

        String safeName = normalizeRequiredText(name, "投标公司名称", MAX_NAME_LENGTH);
        String safeContact = normalizeRequiredText(contactName, "负责人姓名", MAX_CONTACT_NAME_LENGTH);
        String safePhone = normalizeRequiredText(contactPhone, "负责人电话", MAX_CONTACT_PHONE_LENGTH);
        String safeAddress = normalizeOptionalText(
                companyAddress, "公司地址", MAX_COMPANY_ADDRESS_LENGTH);
        BigDecimal safePriceWithTax = normalizeOptionalMoney(priceWithTax, "含税价格");
        BigDecimal safeTaxRate = normalizeOptionalTaxRate(taxRate);
        BigDecimal safePriceWithoutTax = normalizeOptionalMoney(priceWithoutTax, "不含税价");

        if (packageRepository.existsByOrgIdAndTypeAndName(
                operator.getOrgId(), TYPE_BID, safeName, packageId)) {
            throw new BizException(ErrorCode.CONFLICT, "本组织下投标公司名称已存在");
        }

        packageRepository.updateBidInfo(
                packageId, safeName, safeContact, safePhone,
                safeAddress, safePriceWithTax, safeTaxRate, safePriceWithoutTax);
        return toSummary(requireOwnedPackage(packageId, operator.getOrgId()));
    }

    /**
     * 逻辑删除标包。
     * 招标包：已绑定项目不可删；未绑则可删，并级联删包内文件。
     * 投标包：可直接删（已绑则从项目侧消失）；级联删包内文件；
     * 已绑项目且包内有文件时作废该包审查条目并将项目审查标为 OBSOLETE（需重新审查）。
     * 项目存在进行中的 AI 任务时禁止删投标包。
     */
    @Transactional
    public void delete(long packageId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作标包");
        BizPackage pkg = requireOwnedPackage(packageId, operator.getOrgId());
        if (pkg.isTender() && pkg.isBoundToProject()) {
            throw new BizException(ErrorCode.CONFLICT, "招标包已绑定项目，请先删除项目后再删招标包");
        }
        if (pkg.isDeleted()) {
            return;
        }

        Long projectId = pkg.getProjectId();
        if (pkg.isBid() && projectId != null
                && projectArtifactInvalidationPort.hasActiveAiWork(projectId)) {
            throw new BizException(ErrorCode.CONFLICT, "项目审查进行中，请稍后再删投标包");
        }

        boolean bidWithFiles = pkg.isBid()
                && projectId != null
                && documentCountPort.countActiveByPackageId(packageId) > 0;

        documentCascadePort.softDeleteAllActiveByPackageId(packageId);

        if (bidWithFiles) {
            projectArtifactInvalidationPort.deleteReviewItemsByPackageId(projectId, packageId);
            projectArtifactInvalidationPort.obsoleteReviewsByProjectId(
                    projectId, "投标包已删除，请重新审查");
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
        summary.setCompanyAddress(pkg.getCompanyAddress());
        summary.setPriceWithTax(pkg.getPriceWithTax());
        summary.setTaxRate(pkg.getTaxRate());
        summary.setPriceWithoutTax(pkg.getPriceWithoutTax());
        summary.setStatus(pkg.getStatus());
        summary.setCreatedBy(pkg.getCreatedBy());
        summary.setCreatedAt(pkg.getCreatedAt());
        summary.setUpdatedAt(pkg.getUpdatedAt());
        summary.setFileCount(documentCountPort.countActiveByPackageId(pkg.getId()));
        summary.setIndexStatus(documentCountPort.resolveIndexStatusByPackageId(pkg.getId()));
        return summary;
    }

    private String requireSupportedType(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 不能为空");
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
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

    /**
     * 创建时名称：有值则校验唯一；空白则生成「未命名-yyyyMMdd-HHmm」，冲突再加秒/序号。
     */
    private String resolveCreateName(long orgId, String type, String name, String label) {
        if (name != null && !name.trim().isEmpty()) {
            String safeName = normalizeRequiredText(name, label, MAX_NAME_LENGTH);
            if (packageRepository.existsByOrgIdAndTypeAndName(orgId, type, safeName, null)) {
                String conflictLabel = TYPE_BID.equals(type)
                        ? "本组织下投标公司名称已存在"
                        : "本组织下招标包名称已存在";
                throw new BizException(ErrorCode.CONFLICT, conflictLabel);
            }
            return safeName;
        }
        return allocateAutoName(orgId, type);
    }

    private String allocateAutoName(long orgId, String type) {
        ZonedDateTime now = ZonedDateTime.now(NAME_ZONE);
        String minuteName = "未命名-" + AUTO_NAME_MINUTE.format(now);
        if (!packageRepository.existsByOrgIdAndTypeAndName(orgId, type, minuteName, null)) {
            return minuteName;
        }
        String secondName = "未命名-" + AUTO_NAME_SECOND.format(now);
        if (!packageRepository.existsByOrgIdAndTypeAndName(orgId, type, secondName, null)) {
            return secondName;
        }
        for (int i = 2; i <= 99; i++) {
            String candidate = secondName + "-" + i;
            if (candidate.length() > MAX_NAME_LENGTH) {
                candidate = candidate.substring(0, MAX_NAME_LENGTH);
            }
            if (!packageRepository.existsByOrgIdAndTypeAndName(orgId, type, candidate, null)) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.CONFLICT, "自动生成标包名称冲突，请稍后重试或手动填写名称");
    }

    private String normalizeOptionalText(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return normalizeRequiredText(value, label, maxLength);
    }

    /** 可选金额：null 表示不填；须 ≥ 0，小数位 ≤ 2 */
    private BigDecimal normalizeOptionalMoney(BigDecimal value, String label) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为负数");
        }
        if (value.scale() > MAX_PRICE_SCALE) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最多保留两位小数");
        }
        return value;
    }

    /** 可选税率：null 表示不填；须 ≥ 0，小数位 ≤ 4（如 13 表示 13%） */
    private BigDecimal normalizeOptionalTaxRate(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "税率不能为负数");
        }
        if (value.scale() > MAX_TAX_RATE_SCALE) {
            throw new BizException(ErrorCode.BAD_REQUEST, "税率最多保留四位小数");
        }
        return value;
    }

    private String normalizeRequiredText(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        String trimmed = value.trim();
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

    /** 标包摘要 */
    public static class PackageSummary {
        private long id;
        private long orgId;
        private Long projectId;
        private String type;
        private String name;
        private String contactName;
        private String contactPhone;
        private String companyAddress;
        private BigDecimal priceWithTax;
        private BigDecimal taxRate;
        private BigDecimal priceWithoutTax;
        private String status;
        private long createdBy;
        private Instant createdAt;
        private Instant updatedAt;
        private long fileCount;
        /**
         * 包内文件解析汇总状态：PENDING / PROCESSING / SUCCESS / FAILED；无文件为 null。
         * 多文件时按最差：FAILED &gt; PROCESSING &gt; PENDING &gt; SUCCESS。
         */
        private String indexStatus;
        /** 最新成功审查下该投标包 AI 建议分合计；无审查或无评分项为 null。 */
        private BigDecimal suggestedScoreTotal;
        /** 最新成功审查下该投标包评分满分合计；无审查或无评分项为 null。 */
        private BigDecimal fullScoreTotal;
        /** 最新成功审查下该投标包资格项「符合」数量。 */
        private int qualificationMatchCount;
        /** 最新成功审查下该投标包资格项「不符合」数量。 */
        private int qualificationMismatchCount;
        /** 最新成功审查下该投标包资格项「无法判断」数量。 */
        private int qualificationUnknownCount;

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

        public String getIndexStatus() {
            return indexStatus;
        }

        public void setIndexStatus(String indexStatus) {
            this.indexStatus = indexStatus;
        }

        public BigDecimal getSuggestedScoreTotal() {
            return suggestedScoreTotal;
        }

        public void setSuggestedScoreTotal(BigDecimal suggestedScoreTotal) {
            this.suggestedScoreTotal = suggestedScoreTotal;
        }

        public BigDecimal getFullScoreTotal() {
            return fullScoreTotal;
        }

        public void setFullScoreTotal(BigDecimal fullScoreTotal) {
            this.fullScoreTotal = fullScoreTotal;
        }

        public int getQualificationMatchCount() {
            return qualificationMatchCount;
        }

        public void setQualificationMatchCount(int qualificationMatchCount) {
            this.qualificationMatchCount = qualificationMatchCount;
        }

        public int getQualificationMismatchCount() {
            return qualificationMismatchCount;
        }

        public void setQualificationMismatchCount(int qualificationMismatchCount) {
            this.qualificationMismatchCount = qualificationMismatchCount;
        }

        public int getQualificationUnknownCount() {
            return qualificationUnknownCount;
        }

        public void setQualificationUnknownCount(int qualificationUnknownCount) {
            this.qualificationUnknownCount = qualificationUnknownCount;
        }
    }
}
