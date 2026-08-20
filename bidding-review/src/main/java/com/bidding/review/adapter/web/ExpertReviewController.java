package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.ExpertReviewAppService;
import com.bidding.review.application.ExpertReviewExportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 项目专家评审接口：添加专家、推送、追加。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/expert-reviews")
public class ExpertReviewController {

    private static final MediaType XLSX_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ExpertReviewAppService expertReviewAppService;
    private final ExpertReviewExportService expertReviewExportService;

    public ExpertReviewController(ExpertReviewAppService expertReviewAppService,
                                  ExpertReviewExportService expertReviewExportService) {
        this.expertReviewAppService = expertReviewAppService;
        this.expertReviewExportService = expertReviewExportService;
    }

    @GetMapping
    public ApiResponse<ExpertReviewAppService.ReviewSnapshot> getSnapshot(
            @PathVariable("projectId") long projectId) {
        return ApiResponse.ok(expertReviewAppService.getSnapshot(projectId));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable("projectId") long projectId) {
        ExpertReviewExportService.ExportFile file =
                expertReviewExportService.exportProjectReview(projectId);
        String encodedFileName = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFileName)
                .body(file.getContent());
    }

    @PostMapping("/members")
    public ApiResponse<MemberResponse> addMember(@PathVariable("projectId") long projectId,
                                                 @Valid @RequestBody DisplayNameRequest request) {
        return ApiResponse.ok(toMemberResponse(
                expertReviewAppService.addMember(projectId, request.getDisplayName())));
    }

    @DeleteMapping("/members/{memberId}")
    public ApiResponse<Void> removeMember(@PathVariable("projectId") long projectId,
                                          @PathVariable("memberId") long memberId) {
        expertReviewAppService.removeMember(projectId, memberId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/push")
    public ApiResponse<PushResponse> push(@PathVariable("projectId") long projectId,
                                          @Valid @RequestBody PushRequest request) {
        return ApiResponse.ok(toPushResponse(
                expertReviewAppService.push(projectId, request.getDeadlineAt(), request.getPushMessage())));
    }

    @PostMapping("/append")
    public ApiResponse<MemberResponse> append(@PathVariable("projectId") long projectId,
                                              @Valid @RequestBody DisplayNameRequest request) {
        return ApiResponse.ok(toMemberResponse(
                expertReviewAppService.append(projectId, request.getDisplayName())));
    }

    @PostMapping("/append/push")
    public ApiResponse<ExpertReviewAppService.AppendPushResult> pushAppend(
            @PathVariable("projectId") long projectId) {
        return ApiResponse.ok(expertReviewAppService.pushAppend(projectId));
    }

    @PostMapping("/members/{memberId}/reset-password")
    public ApiResponse<ExpertReviewAppService.MemberPasswordResetResult> resetMemberPassword(
            @PathVariable("projectId") long projectId,
            @PathVariable("memberId") long memberId) {
        return ApiResponse.ok(expertReviewAppService.resetMemberPassword(projectId, memberId));
    }

    @PostMapping("/companies/{companyId}/veto/confirm")
    public ApiResponse<ExpertReviewAppService.VetoDecisionResult> confirmCompanyVeto(
            @PathVariable("projectId") long projectId,
            @PathVariable("companyId") long companyId) {
        return ApiResponse.ok(expertReviewAppService.confirmCompanyVeto(projectId, companyId));
    }

    @PostMapping("/companies/{companyId}/veto/continue")
    public ApiResponse<ExpertReviewAppService.VetoDecisionResult> continueDisplayCompany(
            @PathVariable("projectId") long projectId,
            @PathVariable("companyId") long companyId) {
        return ApiResponse.ok(expertReviewAppService.continueDisplayCompany(projectId, companyId));
    }

    private MemberResponse toMemberResponse(ExpertReviewAppService.MemberResult result) {
        MemberResponse response = new MemberResponse();
        response.setReviewId(result.getReviewId());
        response.setMemberId(result.getMemberId());
        response.setUserId(result.getUserId());
        response.setUsername(result.getUsername());
        response.setDisplayName(result.getDisplayName());
        response.setTemporaryPassword(result.getTemporaryPassword());
        response.setAppended(result.isAppended());
        return response;
    }

    private PushResponse toPushResponse(ExpertReviewAppService.PushResult result) {
        PushResponse response = new PushResponse();
        response.setReviewId(result.getReviewId());
        response.setProjectId(result.getProjectId());
        response.setProjectName(result.getProjectName());
        response.setStatus(result.getStatus());
        response.setDeadlineAt(result.getDeadlineAt());
        response.setPushMessage(result.getPushMessage());
        response.setExpertCount(result.getExpertCount());
        response.setReviewItemCount(result.getReviewItemCount());
        return response;
    }

    public static class DisplayNameRequest {
        @NotBlank
        private String displayName;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }
    }

    public static class PushRequest {
        @NotNull
        private Instant deadlineAt;

        private String pushMessage;

        public Instant getDeadlineAt() {
            return deadlineAt;
        }

        public void setDeadlineAt(Instant deadlineAt) {
            this.deadlineAt = deadlineAt;
        }

        public String getPushMessage() {
            return pushMessage;
        }

        public void setPushMessage(String pushMessage) {
            this.pushMessage = pushMessage;
        }
    }

    public static class MemberResponse {
        private long reviewId;
        private long memberId;
        private long userId;
        private String username;
        private String displayName;
        private String temporaryPassword;
        private boolean appended;

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getMemberId() {
            return memberId;
        }

        public void setMemberId(long memberId) {
            this.memberId = memberId;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }

        public boolean isAppended() {
            return appended;
        }

        public void setAppended(boolean appended) {
            this.appended = appended;
        }
    }

    public static class PushResponse {
        private long reviewId;
        private long projectId;
        private String projectName;
        private String status;
        private Instant deadlineAt;
        private String pushMessage;
        private int expertCount;
        private int reviewItemCount;

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Instant getDeadlineAt() {
            return deadlineAt;
        }

        public void setDeadlineAt(Instant deadlineAt) {
            this.deadlineAt = deadlineAt;
        }

        public String getPushMessage() {
            return pushMessage;
        }

        public void setPushMessage(String pushMessage) {
            this.pushMessage = pushMessage;
        }

        public int getExpertCount() {
            return expertCount;
        }

        public void setExpertCount(int expertCount) {
            this.expertCount = expertCount;
        }

        public int getReviewItemCount() {
            return reviewItemCount;
        }

        public void setReviewItemCount(int reviewItemCount) {
            this.reviewItemCount = reviewItemCount;
        }
    }
}
