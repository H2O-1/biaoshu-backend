package com.bidding.file.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.file.application.DocumentAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目维度：从历史项目复制招标 / 投标文件。
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ProjectTenderFileController {

    private final DocumentAppService documentAppService;

    public ProjectTenderFileController(DocumentAppService documentAppService) {
        this.documentAppService = documentAppService;
    }

    /** 可复制招标来源：本组织其它项目（有招标文件）。 */
    @GetMapping("/tender-copy-sources")
    public ApiResponse<List<DocumentAppService.TenderCopySource>> listTenderCopySources(
            @PathVariable("projectId") long projectId,
            @RequestParam(required = false, defaultValue = "") String keyword) {
        return ApiResponse.ok(documentAppService.listTenderCopySources(projectId, keyword));
    }

    /** 一键从来源项目招标包复制全部文件到当前项目招标包。 */
    @PostMapping("/tender-files/copy-from")
    public ApiResponse<DocumentAppService.TenderCopyResult> copyTenderFilesFrom(
            @PathVariable("projectId") long projectId,
            @Valid @RequestBody CopyFromProjectRequest request) {
        return ApiResponse.ok(documentAppService.copyTenderFilesFromProject(
                projectId, request.getSourceProjectId()));
    }

    /** 可复制投标包来源：其它项目下有文件的整包（按项目分组）。 */
    @GetMapping("/bid-copy-sources")
    public ApiResponse<List<DocumentAppService.BidCopySourceProject>> listBidCopySources(
            @PathVariable("projectId") long projectId,
            @RequestParam(required = false, defaultValue = "") String keyword) {
        return ApiResponse.ok(documentAppService.listBidCopySources(projectId, keyword));
    }

    /** 复制历史投标包全部文件到当前项目已有的目标投标包（不新建包）。 */
    @PostMapping("/bid-files/copy-from")
    public ApiResponse<DocumentAppService.BidCopyResult> copyBidFilesFrom(
            @PathVariable("projectId") long projectId,
            @Valid @RequestBody CopyFromPackageRequest request) {
        return ApiResponse.ok(documentAppService.copyBidPackageFrom(
                projectId, request.getSourcePackageId(), request.getTargetPackageId()));
    }

    public static class CopyFromProjectRequest {
        @NotNull
        private Long sourceProjectId;

        public Long getSourceProjectId() {
            return sourceProjectId;
        }

        public void setSourceProjectId(Long sourceProjectId) {
            this.sourceProjectId = sourceProjectId;
        }
    }

    public static class CopyFromPackageRequest {
        @NotNull
        private Long sourcePackageId;
        @NotNull
        private Long targetPackageId;

        public Long getSourcePackageId() {
            return sourcePackageId;
        }

        public void setSourcePackageId(Long sourcePackageId) {
            this.sourcePackageId = sourcePackageId;
        }

        public Long getTargetPackageId() {
            return targetPackageId;
        }

        public void setTargetPackageId(Long targetPackageId) {
            this.targetPackageId = targetPackageId;
        }
    }
}
