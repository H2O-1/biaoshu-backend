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

    /** 名称是否已被其他未删除组织占用。 */
    boolean existsByNameExceptOrgId(String name, long orgId);

    boolean existsByCode(String code);

    /** keyword 搜组织名称、城市、组织下未删除账号的负责人、手机号、账号、邮箱。 */
    List<Org> search(String nameKeyword, String city, String status, int offset, int limit);

    /** keyword 搜索口径同 search。 */
    long countSearch(String nameKeyword, String city, String status);

    /** 按状态统计未删除组织数（通常传 ACTIVE / DISABLED）。 */
    long countByStatus(String status);

    /**
     * 待配置：未删除且下属无未删除账号的组织数。
     * 不受列表 keyword / status 筛选影响。
     */
    long countPendingConfig();

    /** 招标方组织数：排除系统组织与已删除。 */
    long countTendererOrgs();

    /** 投标方组织数：排除系统组织与已删除。 */
    long countBidderOrgs();

    long insert(String code, String name, String city, String orgType, String status, Long createdBy);

    default long insert(String code, String name, String city, String status, Long createdBy) {
        return insert(code, name, city, "TENDERER", status, createdBy);
    }

    void updateStatus(long orgId, String status);

    /** 更新公司名称与所在城市。 */
    void updateNameAndCity(long orgId, String name, String city);

    /** 组织下用户数量（二级列表 / 展示态会用到） */
    long countUsersByOrgId(long orgId);
}
