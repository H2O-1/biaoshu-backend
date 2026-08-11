package com.bidding.project.domain.port;

import com.bidding.project.domain.model.BizPackage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 标包表数据库操作。
 */
public interface PackageRepository {

    Optional<BizPackage> findById(long packageId);

    /**
     * 按组织查标包（不含已删除）。
     *
     * @param type        TENDER / BID；空串表示不限
     * @param unboundOnly 为 true 时只返回未绑定项目的包（project_id IS NULL）
     */
    List<BizPackage> searchByOrgId(long orgId,
                                   String type,
                                   String keyword,
                                   boolean unboundOnly,
                                   int offset,
                                   int limit);

    long countSearchByOrgId(long orgId, String type, String keyword, boolean unboundOnly);

    /**
     * 按包级解析状态统计未删除标包数量。
     *
     * @param type        TENDER / BID
     * @param indexStatus SUCCESS / FAILED
     */
    long countByOrgIdAndTypeAndIndexStatus(long orgId, String type, String indexStatus);

    /**
     * 同组织、同类型下名称是否已存在（未删除）。
     * excludePackageId 非空时排除自身（改名场景）。
     */
    boolean existsByOrgIdAndTypeAndName(long orgId, String type, String name, Long excludePackageId);

    long insert(long orgId,
                Long projectId,
                String type,
                String name,
                String contactName,
                String contactPhone,
                String companyAddress,
                BigDecimal priceWithTax,
                BigDecimal taxRate,
                BigDecimal priceWithoutTax,
                String status,
                long createdBy);

    void updateName(long packageId, String name);

    /** 更新投标包：公司名 / 联系人 / 地址 / 报价 */
    void updateBidInfo(long packageId,
                       String name,
                       String contactName,
                       String contactPhone,
                       String companyAddress,
                       BigDecimal priceWithTax,
                       BigDecimal taxRate,
                       BigDecimal priceWithoutTax);

    void updateStatus(long packageId, String status);

    /**
     * 将未绑定的标包绑到项目（按 type 限制招标/投标）。
     *
     * @return 更新行数；0 表示已被占用或不存在
     */
    int bindToProjectIfUnbound(long packageId, long projectId, String type);

    /** 查某项目下指定类型的未删除标包（招标包至多一条；投标包可多条） */
    Optional<BizPackage> findByProjectIdAndType(long projectId, String type);

    List<BizPackage> searchByProjectIdAndType(long projectId, String type, int offset, int limit);

    long countByProjectIdAndType(long projectId, String type);
}
