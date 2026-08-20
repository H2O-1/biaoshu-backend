package com.bidding.common.port;

/**
 * 项目专家评审进度查询（供项目列表/详情派生 workflowStatus；实现在 review 模块）。
 */
public interface ProjectExpertReviewProgressPort {

    String STATUS_RUNNING = "RUNNING";
    String STATUS_SUCCESS = "SUCCESS";

    /**
     * 查询项目最新已推送的专家评审进度。
     * 仅返回 RUNNING / SUCCESS；未推送或无记录时返回 null。
     */
    Summary findLatestActiveByProject(long orgId, long projectId);

    class Summary {
        private long reviewId;
        private String status;
        private int expertCount;
        private int submittedExpertCount;

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getExpertCount() {
            return expertCount;
        }

        public void setExpertCount(int expertCount) {
            this.expertCount = expertCount;
        }

        public int getSubmittedExpertCount() {
            return submittedExpertCount;
        }

        public void setSubmittedExpertCount(int submittedExpertCount) {
            this.submittedExpertCount = submittedExpertCount;
        }
    }
}
