package com.bidding.identity.domain.model;

/** 角色字典（对应表 sys_role） */
public class Role {

    private final String code;
    private final String name;

    public Role(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
