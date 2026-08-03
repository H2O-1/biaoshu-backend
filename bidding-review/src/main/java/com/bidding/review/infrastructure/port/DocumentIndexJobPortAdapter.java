package com.bidding.review.infrastructure.port;

import com.bidding.common.port.DocumentIndexJobPort;
import com.bidding.review.application.JobAppService;
import org.springframework.stereotype.Component;

/**
 * 文档 INDEX 入队适配：委托 JobAppService.createIndexJob。
 */
@Component
public class DocumentIndexJobPortAdapter implements DocumentIndexJobPort {

    private final JobAppService jobAppService;

    public DocumentIndexJobPortAdapter(JobAppService jobAppService) {
        this.jobAppService = jobAppService;
    }

    @Override
    public long enqueueIndex(long orgId, Long projectId, long documentId, long createdBy) {
        return jobAppService.createIndexJob(orgId, projectId, documentId, createdBy);
    }
}
