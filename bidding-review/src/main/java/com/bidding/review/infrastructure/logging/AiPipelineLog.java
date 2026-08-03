package com.bidding.review.infrastructure.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一键审查总流程专用日志（写入 logs/ai.log）。
 * 只记关键节点，便于按项目排查。
 */
public final class AiPipelineLog {

    private static final Logger LOG = LoggerFactory.getLogger("ai.pipeline");

    private AiPipelineLog() {
    }

    public static void info(long projectId, long pipelineJobId, String phase, String detail) {
        LOG.info("projectId={} pipelineJobId={} phase={} | {}",
                projectId, pipelineJobId, phase, detail == null ? "" : detail);
    }

    public static void warn(long projectId, long pipelineJobId, String phase, String detail) {
        LOG.warn("projectId={} pipelineJobId={} phase={} | {}",
                projectId, pipelineJobId, phase, detail == null ? "" : detail);
    }

    public static void error(long projectId, long pipelineJobId, String phase, String detail) {
        LOG.error("projectId={} pipelineJobId={} phase={} | {}",
                projectId, pipelineJobId, phase, detail == null ? "" : detail);
    }
}
