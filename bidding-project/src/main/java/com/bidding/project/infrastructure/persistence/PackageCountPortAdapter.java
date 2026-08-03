package com.bidding.project.infrastructure.persistence;

import com.bidding.common.port.PackageCountPort;
import com.bidding.project.domain.port.PackageRepository;
import org.springframework.stereotype.Component;

/**
 * 组织详情用的标包计数实现。
 */
@Component
public class PackageCountPortAdapter implements PackageCountPort {

    private final PackageRepository packageRepository;

    public PackageCountPortAdapter(PackageRepository packageRepository) {
        this.packageRepository = packageRepository;
    }

    @Override
    public long countActiveByOrgIdAndType(long orgId, String type) {
        String safeType = type == null ? "" : type.trim();
        return packageRepository.countSearchByOrgId(orgId, safeType, "", false);
    }
}
