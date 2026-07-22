package com.bidding.project.domain.model;

import java.time.Instant;

/**
 * 标包（对应表 biz_package）。
 * 招标包 / 投标包合表；本期 P1 先做招标包。
 * name：招标为包名，投标为公司名。
 */
public class BizPackage {

    private final long id;
    private final long orgId;
    /** 未绑定项目时为 null */
    private final Long projectId;
    private final String type;
    private final String name;
    private final String contactName;
    private final String contactPhone;
    private final String status;
    private final long createdBy;
    private final Instant createdAt;
    private final Instant updatedAt;

    public BizPackage(long id,
                      long orgId,
                      Long projectId,
                      String type,
                      String name,
                      String contactName,
                      String contactPhone,
                      String status,
                      long createdBy,
                      Instant createdAt,
                      Instant updatedAt) {
        this.id = id;
        this.orgId = orgId;
        this.projectId = projectId;
        this.type = type;
        this.name = name;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
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

    public Long getProjectId() {
        return projectId;
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getContactName() {
        return contactName;
    }

    public String getContactPhone() {
        return contactPhone;
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

    public boolean isTender() {
        return "TENDER".equalsIgnoreCase(type);
    }

    public boolean isBid() {
        return "BID".equalsIgnoreCase(type);
    }

    public boolean isDeleted() {
        return "DELETED".equalsIgnoreCase(status);
    }

    /** 是否已绑定项目（已绑的招标包不可删） */
    public boolean isBoundToProject() {
        return projectId != null;
    }
}
