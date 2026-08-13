package com.bidding.common.port;

import java.math.BigDecimal;

/**
 * 标包写操作（实现在 project；供 file 模块复制投标包时新建并绑定）。
 */
public interface PackageCommandPort {

    /**
     * 创建未绑定项目的招标包。
     * preferredName 若与同组织未删除招标包重名，则自动改为 {@code 原名_副本} / {@code 原名_副本2}…
     *
     * @return 新标包 id
     */
    long createUnboundTenderPackage(long orgId,
                                    String preferredName,
                                    long createdBy);

    /**
     * 在指定项目下新建已绑定的投标包。
     * preferredName 若与同组织未删除投标包重名，则自动改为 {@code 原名_副本} / {@code 原名_副本2}…
     *
     * @return 新标包 id
     */
    long createBoundBidPackage(long orgId,
                               long projectId,
                               String preferredName,
                               String contactName,
                               String contactPhone,
                               String companyAddress,
                               BigDecimal priceWithTax,
                               BigDecimal taxRate,
                               BigDecimal priceWithoutTax,
                               long createdBy);
}
