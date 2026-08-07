package com.bidding.review.application;

import com.bidding.common.port.ChunkingPort;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.DocumentContentPort;
import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.common.port.DocumentTextExtractPort;
import com.bidding.common.port.EmbeddingPort;
import com.bidding.common.port.JobPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * INDEX 任务执行：切块 + 向量落库，并回写任务/文档状态。
 * <p>
 * mode=fake：不读文件，假文本切块。<br>
 * mode=real：读对象存储 → PDF/Word 抽文本（含 Outline）→ 章节切块 → 向量。
 */
@Service
public class IndexJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(IndexJobExecutor.class);

    private final JobAppService jobAppService;
    private final DocumentAccessPort documentAccessPort;
    private final DocumentContentPort documentContentPort;
    private final DocumentTextExtractPort documentTextExtractPort;
    private final ChunkingPort chunkingPort;
    private final EmbeddingPort embeddingPort;
    private final DocumentIndexStorePort documentIndexStorePort;
    private final String indexMode;

    public IndexJobExecutor(JobAppService jobAppService,
                            DocumentAccessPort documentAccessPort,
                            DocumentContentPort documentContentPort,
                            DocumentTextExtractPort documentTextExtractPort,
                            ChunkingPort chunkingPort,
                            EmbeddingPort embeddingPort,
                            DocumentIndexStorePort documentIndexStorePort,
                            @Value("${bidding.index.mode:fake}") String indexMode) {
        this.jobAppService = jobAppService;
        this.documentAccessPort = documentAccessPort;
        this.documentContentPort = documentContentPort;
        this.documentTextExtractPort = documentTextExtractPort;
        this.chunkingPort = chunkingPort;
        this.embeddingPort = embeddingPort;
        this.documentIndexStorePort = documentIndexStorePort;
        this.indexMode = indexMode == null ? "fake" : indexMode.trim();
    }

    /**
     * 执行一条已认领的 INDEX 任务。
     */
    @Transactional
    public void execute(JobPort.JobRef job) {
        long jobId = job.getId();
        Long documentId = parseDocumentId(job.getPayloadJson());
        if (documentId == null) {
            jobAppService.markJobFailed(jobId, "INDEX payload 缺少 documentId");
            log.error("INDEX job {} payload 无效: {}", jobId, job.getPayloadJson());
            return;
        }

        try {
            long tAll = System.currentTimeMillis();
            DocumentAccessPort.DocumentRef doc = documentAccessPort.findActiveById(documentId);
            if (doc == null) {
                jobAppService.markIndexFailed(jobId, documentId, "文档不存在或已删除");
                return;
            }

            DocumentTextExtractPort.ExtractResult source;
            long t0 = System.currentTimeMillis();
            if (isRealMode()) {
                jobAppService.markRunningProgress(jobId, 15, "读取文件并抽取文本");
                source = loadRealExtractResult(documentId);
            } else {
                jobAppService.markRunningProgress(jobId, 20, "Fake 切块中");
                source = new DocumentTextExtractPort.ExtractResult();
                source.setFullText("FAKE_SOURCE fileName=" + safe(doc.getFileName())
                        + " documentId=" + doc.getId());
            }
            long extractMs = System.currentTimeMillis() - t0;

            jobAppService.markRunningProgress(jobId, 35, isRealMode() ? "章节切块中" : "Fake 切块中");
            t0 = System.currentTimeMillis();
            List<ChunkingPort.ChunkDraft> drafts = chunkingPort.chunk(source);
            long chunkMs = System.currentTimeMillis() - t0;
            if (drafts == null || drafts.isEmpty()) {
                jobAppService.markIndexFailed(jobId, documentId, "切块结果为空");
                return;
            }

            jobAppService.markRunningProgress(jobId, 55, "向量化中");
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                texts.add(drafts.get(i).getContent());
            }
            t0 = System.currentTimeMillis();
            List<float[]> vectors = embeddingPort.embed(texts);
            long embedMs = System.currentTimeMillis() - t0;
            if (vectors == null || vectors.size() != drafts.size()) {
                jobAppService.markIndexFailed(jobId, documentId, "向量数量与切块不一致");
                return;
            }

            List<DocumentIndexStorePort.IndexedChunk> indexed = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                ChunkingPort.ChunkDraft draft = drafts.get(i);
                DocumentIndexStorePort.IndexedChunk item = new DocumentIndexStorePort.IndexedChunk();
                item.setChunkIndex(draft.getChunkIndex());
                item.setPageNo(draft.getPageNo());
                item.setContent(draft.getContent());
                item.setTokenEstimate(draft.getTokenEstimate());
                item.setEmbedding(vectors.get(i));
                indexed.add(item);
            }

            t0 = System.currentTimeMillis();
            documentIndexStorePort.replaceAll(
                    doc.getOrgId(),
                    doc.getProjectId(),
                    doc.getPackageId(),
                    doc.getId(),
                    embeddingPort.modelName(),
                    indexed
            );
            long storeMs = System.currentTimeMillis() - t0;

            String modeLabel = isRealMode() ? "真文本" : "Fake";
            jobAppService.markIndexSuccess(
                    jobId,
                    documentId,
                    modeLabel + "索引完成：chunks=" + indexed.size()
                            + ", dim=" + embeddingPort.dimension()
                            + ", model=" + embeddingPort.modelName()
            );
            log.info("INDEX成功 jobId={} documentId={} fileName={} mode={} chunks={} "
                            + "extractMs={} chunkMs={} embedMs={} storeMs={} totalMs={}",
                    jobId, documentId, doc.getFileName(), modeLabel, indexed.size(),
                    extractMs, chunkMs, embedMs, storeMs, System.currentTimeMillis() - tAll);
        } catch (Exception ex) {
            log.error("INDEX job {} document {} 执行失败", jobId, documentId, ex);
            String message = ex.getMessage();
            if (message == null || message.isEmpty()) {
                message = ex.getClass().getSimpleName();
            }
            jobAppService.markIndexFailed(jobId, documentId, message);
        }
    }

    /**
     * 认领并执行下一条；无任务返回 false。
     */
    public boolean claimAndRunOnce(String workerId) {
        Optional<JobPort.JobRef> optional = jobAppService.claimNextIndex(workerId);
        if (optional.isEmpty()) {
            return false;
        }
        execute(optional.get());
        return true;
    }

    private DocumentTextExtractPort.ExtractResult loadRealExtractResult(long documentId) {
        DocumentContentPort.DocumentContent content = documentContentPort.openActive(documentId);
        if (content == null) {
            throw new IllegalStateException("文档不存在或对象存储不可用");
        }
        try {
            DocumentTextExtractPort.ExtractResult extracted =
                    documentTextExtractPort.extract(content.getFileExt(), content.getInputStream());
            if (extracted == null || extracted.isBlank()) {
                String warning = extracted == null ? null : extracted.getWarning();
                if (warning == null || warning.isEmpty()) {
                    warning = "未能从文件抽取到文本（可能是扫描件，本期不支持 OCR）";
                }
                throw new IllegalStateException(warning);
            }
            return extracted;
        } finally {
            closeQuietly(content);
        }
    }

    private boolean isRealMode() {
        return "real".equalsIgnoreCase(indexMode);
    }

    private Long parseDocumentId(String payloadJson) {
        if (payloadJson == null || payloadJson.isEmpty()) {
            return null;
        }
        String key = "\"documentId\"";
        int keyPos = payloadJson.indexOf(key);
        if (keyPos < 0) {
            return null;
        }
        int colon = payloadJson.indexOf(':', keyPos + key.length());
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < payloadJson.length() && Character.isWhitespace(payloadJson.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < payloadJson.length() && Character.isDigit(payloadJson.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return null;
        }
        try {
            return Long.parseLong(payloadJson.substring(start, end));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String safe(String name) {
        if (name == null || name.isEmpty()) {
            return "unknown";
        }
        return name;
    }

    private static void closeQuietly(DocumentContentPort.DocumentContent content) {
        if (content == null || content.getInputStream() == null) {
            return;
        }
        try {
            content.getInputStream().close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }
}
