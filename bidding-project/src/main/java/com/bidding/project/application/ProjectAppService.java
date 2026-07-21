package com.bidding.project.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.project.domain.model.Project;
import com.bidding.project.domain.port.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 招标方：本组织下的项目 CRUD。
 * 平台管理员无 TENDERER 角色时不可调用（403）。
 * 停用后仍可改基本信息（含名称）；后续标包/文件等业务另拦。
 */
@Service
public class ProjectAppService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_DELETED = "DELETED";

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_AGENCY_LENGTH = 128;
    private static final int MAX_PROJECT_TYPE_LENGTH = 64;
    private static final int MAX_LEADER_NAME_LENGTH = 64;

    private final ProjectRepository projectRepository;

    public ProjectAppService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional
    public ProjectSummary create(String name, String agency, String projectType, String leaderName) {
        CurrentUser operator = checkTenderer();

        String safeName = normalizeName(name);
        String safeAgency = normalizeAgency(agency);
        String safeType = normalizeProjectType(projectType);
        String safeLeader = normalizeLeaderName(leaderName);

        if (projectRepository.existsByOrgIdAndName(operator.getOrgId(), safeName, null)) {
            throw new BizException(ErrorCode.CONFLICT, "本组织下项目名称已存在");
        }

        long id = projectRepository.insert(
                operator.getOrgId(),
                safeName,
                safeAgency,
                safeType,
                safeLeader,
                STATUS_ACTIVE,
                operator.getUserId()
        );
        return toSummary(requireOwnedProject(id, operator.getOrgId()));
    }

    @Transactional(readOnly = true)
    public PageResult<ProjectSummary> list(String keyword, String status, int page, int size) {
        CurrentUser operator = checkTenderer();

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;

        String kw = keyword == null ? "" : keyword.trim();
        String statusKw = normalizeStatusFilter(status);

        List<Project> projects = projectRepository.searchByOrgId(
                operator.getOrgId(), kw, statusKw, offset, safeSize);
        List<ProjectSummary> items = new ArrayList<>();
        for (Project project : projects) {
            items.add(toSummary(project));
        }
        long total = projectRepository.countSearchByOrgId(operator.getOrgId(), kw, statusKw);
        return new PageResult<>(items, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public ProjectSummary get(long projectId) {
        CurrentUser operator = checkTenderer();
        return toSummary(requireOwnedProject(projectId, operator.getOrgId()));
    }

    /**
     * 更新基本信息（名称、代理机构、项目类型、负责人）。
     * 停用项目仍允许改基本信息；已删除不可改。
     */
    @Transactional
    public ProjectSummary update(long projectId,
                                 String name,
                                 String agency,
                                 String projectType,
                                 String leaderName) {
        CurrentUser operator = checkTenderer();
        Project project = requireOwnedProject(projectId, operator.getOrgId());
        if (project.isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        String safeName = normalizeName(name);
        String safeAgency = normalizeAgency(agency);
        String safeType = normalizeProjectType(projectType);
        String safeLeader = normalizeLeaderName(leaderName);

        if (projectRepository.existsByOrgIdAndName(operator.getOrgId(), safeName, projectId)) {
            throw new BizException(ErrorCode.CONFLICT, "本组织下项目名称已存在");
        }

        projectRepository.updateBasicInfo(projectId, safeName, safeAgency, safeType, safeLeader);
        return toSummary(requireOwnedProject(projectId, operator.getOrgId()));
    }

    @Transactional
    public ProjectSummary updateStatus(long projectId, String status) {
        CurrentUser operator = checkTenderer();
        Project project = requireOwnedProject(projectId, operator.getOrgId());
        if (project.isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        String next = normalizeActiveOrDisabled(status);
        projectRepository.updateStatus(projectId, next);
        return toSummary(requireOwnedProject(projectId, operator.getOrgId()));
    }

    /** 逻辑删除 */
    @Transactional
    public void delete(long projectId) {
        CurrentUser operator = checkTenderer();
        Project project = requireOwnedProject(projectId, operator.getOrgId());
        if (project.isDeleted()) {
            return;
        }
        projectRepository.updateStatus(projectId, STATUS_DELETED);
    }

    private ProjectSummary toSummary(Project project) {
        ProjectSummary summary = new ProjectSummary();
        summary.setId(project.getId());
        summary.setOrgId(project.getOrgId());
        summary.setName(project.getName());
        summary.setAgency(project.getAgency());
        summary.setProjectType(project.getProjectType());
        summary.setLeaderName(project.getLeaderName());
        summary.setStatus(project.getStatus());
        summary.setCreatedBy(project.getCreatedBy());
        summary.setCreatedAt(project.getCreatedAt());
        summary.setUpdatedAt(project.getUpdatedAt());
        // 标包 / 文件模块未建，占位
        summary.setTenderPackageCount(0);
        summary.setTenderFileCount(0);
        return summary;
    }

    private Project requireOwnedProject(long projectId, long orgId) {
        Optional<Project> optionalProject = projectRepository.findById(projectId);
        if (optionalProject.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        Project project = optionalProject.get();
        // 跨组织一律当不存在，避免探 ID
        if (project.getOrgId() != orgId || project.isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }

    private CurrentUser checkTenderer() {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        // 仅招标方；平台管理员无 TENDERER 时 403
        if (!user.isTenderer()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅招标方可操作项目");
        }
        return user;
    }

    private String normalizeName(String name) {
        return normalizeRequiredText(name, "项目名称", MAX_NAME_LENGTH);
    }

    private String normalizeAgency(String agency) {
        return normalizeRequiredText(agency, "代理机构", MAX_AGENCY_LENGTH);
    }

    private String normalizeProjectType(String projectType) {
        return normalizeRequiredText(projectType, "项目类型", MAX_PROJECT_TYPE_LENGTH);
    }

    private String normalizeLeaderName(String leaderName) {
        return normalizeRequiredText(leaderName, "项目负责人", MAX_LEADER_NAME_LENGTH);
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

    private String normalizeStatusFilter(String status) {
        if (status == null || status.trim().isEmpty()) {
            return "";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_ACTIVE.equals(normalized) && !STATUS_DISABLED.equals(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 仅支持 ACTIVE 或 DISABLED");
        }
        return normalized;
    }

    private String normalizeActiveOrDisabled(String status) {
        if (status == null || status.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 不能为空");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_ACTIVE.equals(normalized) && !STATUS_DISABLED.equals(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 仅支持 ACTIVE 或 DISABLED");
        }
        return normalized;
    }

    /** 项目摘要 */
    public static class ProjectSummary {
        private long id;
        private long orgId;
        private String name;
        private String agency;
        private String projectType;
        private String leaderName;
        private String status;
        private long createdBy;
        private Instant createdAt;
        private Instant updatedAt;
        private long tenderPackageCount;
        private long tenderFileCount;

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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAgency() {
            return agency;
        }

        public void setAgency(String agency) {
            this.agency = agency;
        }

        public String getProjectType() {
            return projectType;
        }

        public void setProjectType(String projectType) {
            this.projectType = projectType;
        }

        public String getLeaderName() {
            return leaderName;
        }

        public void setLeaderName(String leaderName) {
            this.leaderName = leaderName;
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

        public long getTenderPackageCount() {
            return tenderPackageCount;
        }

        public void setTenderPackageCount(long tenderPackageCount) {
            this.tenderPackageCount = tenderPackageCount;
        }

        public long getTenderFileCount() {
            return tenderFileCount;
        }

        public void setTenderFileCount(long tenderFileCount) {
            this.tenderFileCount = tenderFileCount;
        }
    }
}
