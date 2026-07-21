package com.bidding.project.domain.model;

import java.time.Instant;

/**
 * 项目（对应表 biz_project）。
 * 归属组织；由招标方创建与管理；名称同组织内唯一且可改。
 */
public class Project {

    private final long id;
    private final long orgId;
    private final String name;
    private final String agency;
    private final String projectType;
    private final String leaderName;
    private final String status;
    private final long createdBy;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Project(long id,
                   long orgId,
                   String name,
                   String agency,
                   String projectType,
                   String leaderName,
                   String status,
                   long createdBy,
                   Instant createdAt,
                   Instant updatedAt) {
        this.id = id;
        this.orgId = orgId;
        this.name = name;
        this.agency = agency;
        this.projectType = projectType;
        this.leaderName = leaderName;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getId() {
        return id;
    }

    public long getOrgId() {
        return orgId;
    }

    public String getName() {
        return name;
    }

    public String getAgency() {
        return agency;
    }

    public String getProjectType() {
        return projectType;
    }

    public String getLeaderName() {
        return leaderName;
    }

    public String getStatus() {
        return status;
    }

    public long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }

    public boolean isDisabled() {
        return "DISABLED".equalsIgnoreCase(status);
    }

    public boolean isDeleted() {
        return "DELETED".equalsIgnoreCase(status);
    }
}
