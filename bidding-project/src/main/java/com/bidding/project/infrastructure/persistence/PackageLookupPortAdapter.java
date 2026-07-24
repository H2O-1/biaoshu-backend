package com.bidding.project.infrastructure.persistence;

import com.bidding.common.port.PackageLookupPort;
import com.bidding.project.domain.model.BizPackage;
import com.bidding.project.domain.port.PackageRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 标包查询适配（供 file 模块校验包归属）。
 */
@Component
public class PackageLookupPortAdapter implements PackageLookupPort {

    private final PackageRepository packageRepository;

    public PackageLookupPortAdapter(PackageRepository packageRepository) {
        this.packageRepository = packageRepository;
    }

    @Override
    public PackageRef findActiveById(long packageId) {
        Optional<BizPackage> optional = packageRepository.findById(packageId);
        if (optional.isEmpty()) {
            return null;
        }
        BizPackage pkg = optional.get();
        if (pkg.isDeleted()) {
            return null;
        }
        PackageRef ref = new PackageRef();
        ref.setId(pkg.getId());
        ref.setOrgId(pkg.getOrgId());
        ref.setProjectId(pkg.getProjectId());
        ref.setType(pkg.getType());
        ref.setStatus(pkg.getStatus());
        return ref;
    }
}
