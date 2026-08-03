package com.bidding.identity.domain.model;

import java.time.Instant;

/**
 * 组织（对应表 sys_org）。
 * 编码 code 创建后不可修改；名称、城市可改。
 */
public class Org {

    private final long id;
    private final String code;
    private final String name;
    private final String city;
    private final String status;
    private final Long createdBy;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Org(long id,
               String code,
               String name,
               String city,
               String status,
               Long createdBy,
               Instant createdAt,
               Instant updatedAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.city = city;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }

    public String getStatus() {
        return status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 是否正常可用 */
    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }

    /** 是否已逻辑删除 */
    public boolean isDeleted() {
        return "DELETED".equalsIgnoreCase(status);
    }

    /** 是否系统种子组织 */
    public boolean isSystem() {
        return "SYSTEM".equalsIgnoreCase(code);
    }
}
