package com.bidding.project.infrastructure.persistence;

import com.bidding.common.port.ProjectLookupPort;
import com.bidding.project.domain.model.Project;
import com.bidding.project.domain.port.ProjectRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 项目查询适配（供 review / file 等模块校验项目归属）。
 */
@Component
public class ProjectLookupPortAdapter implements ProjectLookupPort {

    private final ProjectRepository projectRepository;

    public ProjectLookupPortAdapter(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Override
    public ProjectRef findActiveById(long projectId) {
        Optional<Project> optional = projectRepository.findById(projectId);
        if (optional.isEmpty()) {
            return null;
        }
        Project project = optional.get();
        if (project.isDeleted()) {
            return null;
        }
        return toRef(project);
    }

    @Override
    public List<ProjectRef> searchByOrgId(long orgId, String keyword, int offset, int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        // status 空串 = 全部未删除（Repository 约定）
        List<Project> projects = projectRepository.searchByOrgId(orgId, kw, "", safeOffset, safeLimit);
        List<ProjectRef> result = new ArrayList<>();
        if (projects == null) {
            return result;
        }
        for (int i = 0; i < projects.size(); i++) {
            Project project = projects.get(i);
            if (project == null || project.isDeleted()) {
                continue;
            }
            result.add(toRef(project));
        }
        return result;
    }

    private ProjectRef toRef(Project project) {
        ProjectRef ref = new ProjectRef();
        ref.setId(project.getId());
        ref.setOrgId(project.getOrgId());
        ref.setName(project.getName());
        ref.setStatus(project.getStatus());
        return ref;
    }
}
