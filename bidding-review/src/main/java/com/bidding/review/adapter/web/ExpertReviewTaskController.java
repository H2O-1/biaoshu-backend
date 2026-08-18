package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.ExpertReviewAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 专家侧评审任务接口。
 */
@RestController
@RequestMapping("/api/expert-review-tasks")
public class ExpertReviewTaskController {

    private final ExpertReviewAppService expertReviewAppService;

    public ExpertReviewTaskController(ExpertReviewAppService expertReviewAppService) {
        this.expertReviewAppService = expertReviewAppService;
    }

    @GetMapping("/current")
    public ApiResponse<ExpertReviewAppService.CurrentTaskDetail> current() {
        return ApiResponse.ok(expertReviewAppService.getCurrentTask());
    }

    @PostMapping("/{taskId}/items/{itemId}")
    public ApiResponse<ExpertReviewAppService.TaskItem> saveItem(@PathVariable("taskId") long taskId,
                                                                 @PathVariable("itemId") long itemId,
                                                                 @Valid @RequestBody SaveItemRequest request) {
        ExpertReviewAppService.SaveItemCommand command = new ExpertReviewAppService.SaveItemCommand();
        command.setResult(request.getResult());
        command.setScoreValue(request.getScoreValue());
        command.setComment(request.getComment());
        command.setVeto(request.isVeto());
        command.setVetoReason(request.getVetoReason());
        return ApiResponse.ok(expertReviewAppService.saveItem(taskId, itemId, command));
    }

    @PostMapping("/{taskId}/submit")
    public ApiResponse<ExpertReviewAppService.SubmitResult> submit(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(expertReviewAppService.submit(taskId));
    }

    public static class SaveItemRequest {
        private String result;
        private BigDecimal scoreValue;
        private String comment;
        private boolean veto;
        private String vetoReason;

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public BigDecimal getScoreValue() {
            return scoreValue;
        }

        public void setScoreValue(BigDecimal scoreValue) {
            this.scoreValue = scoreValue;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        public boolean isVeto() {
            return veto;
        }

        public void setVeto(boolean veto) {
            this.veto = veto;
        }

        public String getVetoReason() {
            return vetoReason;
        }

        public void setVetoReason(String vetoReason) {
            this.vetoReason = vetoReason;
        }
    }
}
