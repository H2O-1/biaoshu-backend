package com.bidding.identity.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentIndexCountPort;
import com.bidding.common.port.ReviewStatusCountPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.port.OrgRepository;
import com.bidding.identity.domain.port.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台管理员首页汇总：组织 / 平台账号 / 审查 / 文档解析计数。
 * 不含提示词展示（后续再做）。
 */
@Service
public class AdminDashboardAppService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String INDEX_SUCCESS = "SUCCESS";
    private static final String INDEX_FAILED = "FAILED";
    private static final String REVIEW_SUCCESS = "SUCCESS";
    private static final String REVIEW_FAILED = "FAILED";

    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final DocumentIndexCountPort documentIndexCountPort;
    private final ReviewStatusCountPort reviewStatusCountPort;

    public AdminDashboardAppService(OrgRepository orgRepository,
                                    UserRepository userRepository,
                                    DocumentIndexCountPort documentIndexCountPort,
                                    ReviewStatusCountPort reviewStatusCountPort) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.documentIndexCountPort = documentIndexCountPort;
        this.reviewStatusCountPort = reviewStatusCountPort;
    }

    @Transactional(readOnly = true)
    public DashboardSummary getDashboard() {
        checkPlatformAdmin();

        DashboardSummary summary = new DashboardSummary();
        summary.setTendererOrgCount(orgRepository.countTendererOrgs());
        summary.setBidderOrgCount(orgRepository.countBidderOrgs());
        summary.setPlatformAdminActiveCount(
                userRepository.countPlatformAdminsByStatus(STATUS_ACTIVE));
        summary.setPlatformAdminDisabledCount(
                userRepository.countPlatformAdminsByStatus(STATUS_DISABLED));
        summary.setReviewSuccessCount(
                reviewStatusCountPort.countByStatus(REVIEW_SUCCESS));
        summary.setReviewFailedCount(
                reviewStatusCountPort.countByStatus(REVIEW_FAILED));
        summary.setDocumentIndexSuccessCount(
                documentIndexCountPort.countActiveByIndexStatus(INDEX_SUCCESS));
        summary.setDocumentIndexFailedCount(
                documentIndexCountPort.countActiveByIndexStatus(INDEX_FAILED));
        return summary;
    }

    private CurrentUser checkPlatformAdmin() {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.isPlatformAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台管理员可操作");
        }
        return user;
    }

    /** 平台首页汇总。 */
    public static class DashboardSummary {
        private long tendererOrgCount;
        private long bidderOrgCount;
        private long platformAdminActiveCount;
        private long platformAdminDisabledCount;
        private long reviewSuccessCount;
        private long reviewFailedCount;
        private long documentIndexSuccessCount;
        private long documentIndexFailedCount;

        public long getTendererOrgCount() {
            return tendererOrgCount;
        }

        public void setTendererOrgCount(long tendererOrgCount) {
            this.tendererOrgCount = tendererOrgCount;
        }

        public long getBidderOrgCount() {
            return bidderOrgCount;
        }

        public void setBidderOrgCount(long bidderOrgCount) {
            this.bidderOrgCount = bidderOrgCount;
        }

        public long getPlatformAdminActiveCount() {
            return platformAdminActiveCount;
        }

        public void setPlatformAdminActiveCount(long platformAdminActiveCount) {
            this.platformAdminActiveCount = platformAdminActiveCount;
        }

        public long getPlatformAdminDisabledCount() {
            return platformAdminDisabledCount;
        }

        public void setPlatformAdminDisabledCount(long platformAdminDisabledCount) {
            this.platformAdminDisabledCount = platformAdminDisabledCount;
        }

        public long getReviewSuccessCount() {
            return reviewSuccessCount;
        }

        public void setReviewSuccessCount(long reviewSuccessCount) {
            this.reviewSuccessCount = reviewSuccessCount;
        }

        public long getReviewFailedCount() {
            return reviewFailedCount;
        }

        public void setReviewFailedCount(long reviewFailedCount) {
            this.reviewFailedCount = reviewFailedCount;
        }

        public long getDocumentIndexSuccessCount() {
            return documentIndexSuccessCount;
        }

        public void setDocumentIndexSuccessCount(long documentIndexSuccessCount) {
            this.documentIndexSuccessCount = documentIndexSuccessCount;
        }

        public long getDocumentIndexFailedCount() {
            return documentIndexFailedCount;
        }

        public void setDocumentIndexFailedCount(long documentIndexFailedCount) {
            this.documentIndexFailedCount = documentIndexFailedCount;
        }
    }
}
