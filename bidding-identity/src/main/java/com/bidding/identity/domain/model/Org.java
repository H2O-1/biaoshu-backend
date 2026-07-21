package com.bidding.identity.domain.model;

/** 组织（对应表 sys_org） */
public class Org {

    private final long id;
    private final String code;
    private final String name;
    private final String status;

    public Org(long id, String code, String name, String status) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.status = status;
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

    public String getStatus() {
        return status;
    }
}
