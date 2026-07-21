package com.bidding.project.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.project.application.ProjectAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目接口（招标方）。平台管理员无招标方角色时 403。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectAppService projectAppService;

    public ProjectController(ProjectAppService projectAppService) {
        this.projectAppService = projectAppService;
    }

    @PostMapping
    public ApiResponse<ProjectAppService.ProjectSummary> create(
            @Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(projectAppService.create(
                request.getName(),
                request.getAgency(),
                request.getProjectType(),
                request.getLeaderName()
        ));
    }

    @GetMapping
    public ApiResponse<PageResult<ProjectAppService.ProjectSummary>> list(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(projectAppService.list(keyword, status, page, size));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectAppService.ProjectSummary> get(
            @PathVariable("projectId") long projectId) {
        return ApiResponse.ok(projectAppService.get(projectId));
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ProjectAppService.ProjectSummary> update(
            @PathVariable("projectId") long projectId,
            @Valid @RequestBody UpdateProjectRequest request) {
        return ApiResponse.ok(projectAppService.update(
                projectId,
                request.getName(),
                request.getAgency(),
                request.getProjectType(),
                request.getLeaderName()
        ));
    }

    @PutMapping("/{projectId}/status")
    public ApiResponse<ProjectAppService.ProjectSummary> updateStatus(
            @PathVariable("projectId") long projectId,
            @Valid @RequestBody UpdateProjectStatusRequest request) {
        return ApiResponse.ok(projectAppService.updateStatus(projectId, request.getStatus()));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> delete(@PathVariable("projectId") long projectId) {
        projectAppService.delete(projectId);
        return ApiResponse.ok();
    }

    public static class CreateProjectRequest {
        @NotBlank
        @Size(max = 128)
        private String name;

        @NotBlank
        @Size(max = 128)
        private String agency;

        @NotBlank
        @Size(max = 64)
        private String projectType;

        @NotBlank
        @Size(max = 64)
        private String leaderName;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAgency() {
            return agency;
        }

        public void setAgency(String agency) {
            this.agency = agency;
        }

        public String getProjectType() {
            return projectType;
        }

        public void setProjectType(String projectType) {
            this.projectType = projectType;
        }

        public String getLeaderName() {
            return leaderName;
        }

        public void setLeaderName(String leaderName) {
            this.leaderName = leaderName;
        }
    }

    public static class UpdateProjectRequest {
        @NotBlank
        @Size(max = 128)
        private String name;

        @NotBlank
        @Size(max = 128)
        private String agency;

        @NotBlank
        @Size(max = 64)
        private String projectType;

        @NotBlank
        @Size(max = 64)
        private String leaderName;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAgency() {
            return agency;
        }

        public void setAgency(String agency) {
            this.agency = agency;
        }

        public String getProjectType() {
            return projectType;
        }

        public void setProjectType(String projectType) {
            this.projectType = projectType;
        }

        public String getLeaderName() {
            return leaderName;
        }

        public void setLeaderName(String leaderName) {
            this.leaderName = leaderName;
        }
    }

    public static class UpdateProjectStatusRequest {
        @NotBlank
        private String status;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
