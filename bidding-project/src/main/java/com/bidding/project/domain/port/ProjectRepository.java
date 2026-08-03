package com.bidding.project.domain.port;

import com.bidding.project.domain.model.Project;

import java.util.List;
import java.util.Optional;

/**
 * 项目表数据库操作。
 */
public interface ProjectRepository {

    Optional<Project> findById(long projectId);

    List<Project> searchByOrgId(long orgId, String keyword, String status, int offset, int limit);

    long countSearchByOrgId(long orgId, String keyword, String status);

    boolean existsByOrgIdAndName(long orgId, String name, Long excludeProjectId);

    long insert(long orgId,
                String name,
                String agency,
                String projectType,
                String leaderName,
                String status,
                long createdBy);

    void updateBasicInfo(long projectId,
                         String name,
                         String agency,
                         String projectType,
                         String leaderName);

    void updateStatus(long projectId, String status);

    long countByOrgId(long orgId);
}
