package com.bidding.project.infrastructure.port;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.PackageCommandPort;
import com.bidding.project.application.PackageAppService;
import com.bidding.project.domain.port.PackageRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 标包写操作适配：复制投标包时在目标项目下新建并绑定。
 */
@Component
public class PackageCommandPortAdapter implements PackageCommandPort {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final PackageRepository packageRepository;

    public PackageCommandPortAdapter(PackageRepository packageRepository) {
        this.packageRepository = packageRepository;
    }

    @Override
    public long createBoundBidPackage(long orgId,
                                      long projectId,
                                      String preferredName,
                                      String contactName,
                                      String contactPhone,
                                      String companyAddress,
                                      BigDecimal priceWithTax,
                                      BigDecimal taxRate,
                                      BigDecimal priceWithoutTax,
                                      long createdBy) {
        String uniqueName = allocateUniqueBidName(orgId, preferredName);
        return packageRepository.insert(
                orgId,
                projectId,
                PackageAppService.TYPE_BID,
                uniqueName,
                blankToNull(contactName),
                blankToNull(contactPhone),
                blankToNull(companyAddress),
                priceWithTax,
                taxRate,
                priceWithoutTax,
                STATUS_ACTIVE,
                createdBy
        );
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }

    /**
     * 投标公司名唯一：可用则原名；否则 原名_副本 / 原名_副本2…
     */
    static String allocateUniqueBidName(PackageRepository packageRepository, long orgId, String preferredName) {
        String base = preferredName == null ? "" : preferredName.trim();
        if (base.isEmpty()) {
            base = "未命名投标公司";
        }
        if (base.length() > 128) {
            base = base.substring(0, 128);
        }
        if (!packageRepository.existsByOrgIdAndTypeAndName(
                orgId, PackageAppService.TYPE_BID, base, null)) {
            return base;
        }
        String candidate = base + "_副本";
        if (candidate.length() > 128) {
            candidate = candidate.substring(0, 128);
        }
        if (!packageRepository.existsByOrgIdAndTypeAndName(
                orgId, PackageAppService.TYPE_BID, candidate, null)) {
            return candidate;
        }
        int n = 2;
        while (n <= 1000) {
            candidate = base + "_副本" + n;
            if (candidate.length() > 128) {
                candidate = candidate.substring(0, 128);
            }
            if (!packageRepository.existsByOrgIdAndTypeAndName(
                    orgId, PackageAppService.TYPE_BID, candidate, null)) {
                return candidate;
            }
            n++;
        }
        throw new BizException(ErrorCode.CONFLICT, "无法生成不冲突的投标包名称");
    }

    private String allocateUniqueBidName(long orgId, String preferredName) {
        return allocateUniqueBidName(packageRepository, orgId, preferredName);
    }
}
