package com.bidding.identity.infrastructure.port;

import com.bidding.common.port.OrgLookupPort;
import com.bidding.identity.domain.model.Org;
import com.bidding.identity.domain.port.OrgRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 组织基础信息查询适配器。
 */
@Component
public class OrgLookupPortAdapter implements OrgLookupPort {

    private final OrgRepository orgRepository;

    public OrgLookupPortAdapter(OrgRepository orgRepository) {
        this.orgRepository = orgRepository;
    }

    @Override
    public String findNameById(long orgId) {
        Optional<Org> optionalOrg = orgRepository.findById(orgId);
        if (optionalOrg.isEmpty() || optionalOrg.get().isDeleted()) {
            return null;
        }
        return optionalOrg.get().getName();
    }
}
