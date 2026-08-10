package com.bidding.common.port;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 项目投标包最新成功审查摘要（供项目投标包列表展示）。
 */
public interface BidPackageReviewSummaryPort {

    /**
     * 按项目最新 SUCCESS 审查聚合每个投标包的建议分与资格统计。
     * 无成功审查时返回空 map。
     */
    Map<Long, Summary> findLatestSuccessByProjectId(long projectId);

    class Summary {
        private long packageId;
        private BigDecimal suggestedScoreTotal;
        private BigDecimal fullScoreTotal;
        private int qualificationMatchCount;
        private int qualificationMismatchCount;
        private int qualificationUnknownCount;

        public long getPackageId() {
            return packageId;
        }

        public void setPackageId(long packageId) {
            this.packageId = packageId;
        }

        public BigDecimal getSuggestedScoreTotal() {
            return suggestedScoreTotal;
        }

        public void setSuggestedScoreTotal(BigDecimal suggestedScoreTotal) {
            this.suggestedScoreTotal = suggestedScoreTotal;
        }

        public BigDecimal getFullScoreTotal() {
            return fullScoreTotal;
        }

        public void setFullScoreTotal(BigDecimal fullScoreTotal) {
            this.fullScoreTotal = fullScoreTotal;
        }

        public int getQualificationMatchCount() {
            return qualificationMatchCount;
        }

        public void setQualificationMatchCount(int qualificationMatchCount) {
            this.qualificationMatchCount = qualificationMatchCount;
        }

        public int getQualificationMismatchCount() {
            return qualificationMismatchCount;
        }

        public void setQualificationMismatchCount(int qualificationMismatchCount) {
            this.qualificationMismatchCount = qualificationMismatchCount;
        }

        public int getQualificationUnknownCount() {
            return qualificationUnknownCount;
        }

        public void setQualificationUnknownCount(int qualificationUnknownCount) {
            this.qualificationUnknownCount = qualificationUnknownCount;
        }
    }
}
