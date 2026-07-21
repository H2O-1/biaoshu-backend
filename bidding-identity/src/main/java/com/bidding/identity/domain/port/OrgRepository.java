package com.bidding.identity.domain.port;

import com.bidding.identity.domain.model.Org;

import java.util.List;
import java.util.Optional;

/**
 * 组织相关数据库操作。
 */
public interface OrgRepository {

    Optional<Org> findById(long orgId);

    Optional<Long> findSystemOrgId();

    boolean existsById(long orgId);

    boolean existsByName(String name);

    boolean existsByCode(String code);

    List<Org> search(String nameKeyword, String city, String status, int offset, int limit);

    long countSearch(String nameKeyword, String city, String status);

    long insert(String code, String name, String city, String status, Long createdBy);

    void updateStatus(long orgId, String status);

    /** 组织下用户数量（二级列表 / 展示态会用到） */
    long countUsersByOrgId(long orgId);
}
