package com.bidding.review.application;

import com.bidding.common.port.ChunkingPort;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.DocumentContentPort;
import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.common.port.DocumentTextExtractPort;
import com.bidding.common.port.EmbeddingPort;
import com.bidding.common.port.JobPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexJobExecutorTest {

    @Mock
    private JobAppService jobAppService;

    @Mock
    private DocumentAccessPort documentAccessPort;

    @Mock
    private DocumentContentPort documentContentPort;

    @Mock
    private DocumentTextExtractPort documentTextExtractPort;

    @Mock
    private ChunkingPort chunkingPort;

    @Mock
    private EmbeddingPort embeddingPort;

    @Mock
    private DocumentIndexStorePort documentIndexStorePort;

    @Test
    void executeSuccessWritesChunksAndMarksSuccess() {
        IndexJobExecutor indexJobExecutor = newExecutor("fake");

        JobPort.JobRef job = new JobPort.JobRef();
        job.setId(55L);
        job.setPayloadJson("{\"documentId\":9}");

        DocumentAccessPort.DocumentRef doc = new DocumentAccessPort.DocumentRef();
        doc.setId(9L);
        doc.setOrgId(2L);
        doc.setProjectId(100L);
        doc.setPackageId(50L);
        doc.setFileName("a.pdf");
        when(documentAccessPort.findActiveById(9L)).thenReturn(doc);

        ChunkingPort.ChunkDraft draft = new ChunkingPort.ChunkDraft();
        draft.setChunkIndex(0);
        draft.setPageNo(1);
        draft.setContent("c0");
        draft.setTokenEstimate(2);
        List<ChunkingPort.ChunkDraft> drafts = new ArrayList<>();
        drafts.add(draft);
        when(chunkingPort.chunk(any(DocumentTextExtractPort.ExtractResult.class))).thenReturn(drafts);

        List<float[]> vectors = new ArrayList<>();
        vectors.add(new float[]{0.1f, 0.2f});
        when(embeddingPort.embed(anyList())).thenReturn(vectors);
        when(embeddingPort.dimension()).thenReturn(2);
        when(embeddingPort.modelName()).thenReturn("fake-embedding");

        indexJobExecutor.execute(job);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentIndexStorePort.IndexedChunk>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(documentIndexStorePort).replaceAll(
                eq(2L), eq(100L), eq(50L), eq(9L), eq("fake-embedding"), captor.capture());
        assertEquals(1, captor.getValue().size());
        verify(jobAppService).markIndexSuccess(eq(55L), eq(9L), anyString());
        verify(documentContentPort, never()).openActive(anyLong());
    }

    @Test
    void executeRealModeExtractsTextFromStorage() {
        IndexJobExecutor indexJobExecutor = newExecutor("real");

        JobPort.JobRef job = new JobPort.JobRef();
        job.setId(55L);
        job.setPayloadJson("{\"documentId\":9}");

        DocumentAccessPort.DocumentRef doc = new DocumentAccessPort.DocumentRef();
        doc.setId(9L);
        doc.setOrgId(2L);
        doc.setProjectId(100L);
        doc.setPackageId(50L);
        doc.setFileName("a.pdf");
        doc.setFileExt("pdf");
        when(documentAccessPort.findActiveById(9L)).thenReturn(doc);

        DocumentContentPort.DocumentContent content = new DocumentContentPort.DocumentContent();
        content.setDocumentId(9L);
        content.setFileExt("pdf");
        content.setInputStream(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        when(documentContentPort.openActive(9L)).thenReturn(content);

        DocumentTextExtractPort.ExtractResult extracted = new DocumentTextExtractPort.ExtractResult();
        extracted.setFullText("真实招标正文资格条件");
        when(documentTextExtractPort.extract(eq("pdf"), any())).thenReturn(extracted);

        ChunkingPort.ChunkDraft draft = new ChunkingPort.ChunkDraft();
        draft.setChunkIndex(0);
        draft.setPageNo(1);
        draft.setContent("chunk");
        draft.setTokenEstimate(1);
        List<ChunkingPort.ChunkDraft> drafts = new ArrayList<>();
        drafts.add(draft);
        when(chunkingPort.chunk(any(DocumentTextExtractPort.ExtractResult.class))).thenReturn(drafts);

        List<float[]> vectors = new ArrayList<>();
        vectors.add(new float[]{0.1f});
        when(embeddingPort.embed(anyList())).thenReturn(vectors);
        when(embeddingPort.dimension()).thenReturn(1);
        when(embeddingPort.modelName()).thenReturn("BGE-M3");

        indexJobExecutor.execute(job);

        verify(documentContentPort).openActive(9L);
        verify(documentTextExtractPort).extract(eq("pdf"), any());
        verify(jobAppService).markIndexSuccess(eq(55L), eq(9L), anyString());
    }

    @Test
    void executeFailsWhenDocumentMissing() {
        IndexJobExecutor indexJobExecutor = newExecutor("fake");

        JobPort.JobRef job = new JobPort.JobRef();
        job.setId(55L);
        job.setPayloadJson("{\"documentId\":9}");
        when(documentAccessPort.findActiveById(9L)).thenReturn(null);

        indexJobExecutor.execute(job);

        verify(jobAppService).markIndexFailed(55L, 9L, "文档不存在或已删除");
        verify(documentIndexStorePort, never()).replaceAll(
                anyLong(), any(), anyLong(), anyLong(), anyString(), anyList());
    }

    @Test
    void claimAndRunOnceReturnsFalseWhenNoJob() {
        IndexJobExecutor indexJobExecutor = newExecutor("fake");
        when(jobAppService.claimNextIndex("w1")).thenReturn(Optional.empty());
        boolean ran = indexJobExecutor.claimAndRunOnce("w1");
        assertEquals(false, ran);
    }

    @Test
    void executeRealModeFailsWhenExtractBlank() {
        IndexJobExecutor indexJobExecutor = newExecutor("real");

        JobPort.JobRef job = new JobPort.JobRef();
        job.setId(55L);
        job.setPayloadJson("{\"documentId\":9}");

        DocumentAccessPort.DocumentRef doc = new DocumentAccessPort.DocumentRef();
        doc.setId(9L);
        doc.setOrgId(2L);
        doc.setPackageId(50L);
        doc.setFileName("scan.pdf");
        when(documentAccessPort.findActiveById(9L)).thenReturn(doc);

        DocumentContentPort.DocumentContent content = new DocumentContentPort.DocumentContent();
        content.setFileExt("pdf");
        content.setInputStream(new ByteArrayInputStream(new byte[]{1}));
        when(documentContentPort.openActive(9L)).thenReturn(content);

        DocumentTextExtractPort.ExtractResult extracted = new DocumentTextExtractPort.ExtractResult();
        extracted.setFullText("   ");
        extracted.setWarning("未能抽取到文本，可能是扫描件（本期不支持 OCR）");
        when(documentTextExtractPort.extract(eq("pdf"), any())).thenReturn(extracted);

        indexJobExecutor.execute(job);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(jobAppService).markIndexFailed(eq(55L), eq(9L), msgCaptor.capture());
        assertTrue(msgCaptor.getValue().contains("扫描件") || msgCaptor.getValue().contains("文本"));
    }

    private IndexJobExecutor newExecutor(String mode) {
        return new IndexJobExecutor(
                jobAppService,
                documentAccessPort,
                documentContentPort,
                documentTextExtractPort,
                chunkingPort,
                embeddingPort,
                documentIndexStorePort,
                mode
        );
    }
}
