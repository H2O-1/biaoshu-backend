package com.bidding.project.infrastructure.persistence;

import com.bidding.common.port.ProjectCountPort;
import com.bidding.project.domain.port.ProjectRepository;
import org.springframework.stereotype.Component;

/**
 * 组织管理用的项目计数实现。
 */
@Component
public class ProjectCountPortAdapter implements ProjectCountPort {

    private final ProjectRepository projectRepository;

    public ProjectCountPortAdapter(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Override
    public long countByOrgId(long orgId) {
        return projectRepository.countByOrgId(orgId);
    }
}
