package com.bidding.project.infrastructure.persistence;

import com.bidding.common.port.PackageLookupPort;
import com.bidding.project.domain.model.BizPackage;
import com.bidding.project.domain.port.PackageRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
        return toRefIfActive(optional.get());
    }

    @Override
    public PackageRef findActiveByProjectIdAndType(long projectId, String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        Optional<BizPackage> optional = packageRepository.findByProjectIdAndType(projectId, type.trim());
        if (optional.isEmpty()) {
            return null;
        }
        return toRefIfActive(optional.get());
    }

    @Override
    public List<PackageRef> listActiveByProjectIdAndType(long projectId, String type) {
        List<PackageRef> result = new ArrayList<>();
        if (type == null || type.isBlank()) {
            return result;
        }
        List<BizPackage> packages = packageRepository.searchByProjectIdAndType(
                projectId, type.trim(), 0, 1000);
        if (packages == null) {
            return result;
        }
        for (int i = 0; i < packages.size(); i++) {
            PackageRef ref = toRefIfActive(packages.get(i));
            if (ref != null) {
                result.add(ref);
            }
        }
        return result;
    }

    private PackageRef toRefIfActive(BizPackage pkg) {
        if (pkg == null || pkg.isDeleted()) {
            return null;
        }
        PackageRef ref = new PackageRef();
        ref.setId(pkg.getId());
        ref.setOrgId(pkg.getOrgId());
        ref.setProjectId(pkg.getProjectId());
        ref.setType(pkg.getType());
        ref.setStatus(pkg.getStatus());
        ref.setName(pkg.getName());
        ref.setContactName(pkg.getContactName());
        ref.setContactPhone(pkg.getContactPhone());
        ref.setCompanyAddress(pkg.getCompanyAddress());
        ref.setPriceWithTax(pkg.getPriceWithTax());
        ref.setTaxRate(pkg.getTaxRate());
        ref.setPriceWithoutTax(pkg.getPriceWithoutTax());
        return ref;
    }
}
