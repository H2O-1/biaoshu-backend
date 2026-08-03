package com.bidding.review.application;

import com.bidding.common.port.RerankPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RerankAppServiceTest {

    @Mock
    private RecallAppService recallAppService;
    @Mock
    private RerankPort rerankPort;

    @Test
    void recallThenRerankMapsByIndex() {
        RerankAppService service = new RerankAppService(
                recallAppService, rerankPort, 40, 8, 6,
                "tender-instruct", "bid-instruct");

        RecallAppService.HitSummary h0 = new RecallAppService.HitSummary();
        h0.setChunkId(10L);
        h0.setContent("段A");
        h0.setDocumentId(1L);
        h0.setFileName("a.pdf");
        h0.setPackageId(2L);
        h0.setPackageType("TENDER");
        h0.setPackageName("招标包");
        h0.setScore(0.1f);

        RecallAppService.HitSummary h1 = new RecallAppService.HitSummary();
        h1.setChunkId(11L);
        h1.setContent("段B");
        h1.setDocumentId(1L);
        h1.setFileName("a.pdf");
        h1.setPackageId(2L);
        h1.setPackageType("TENDER");
        h1.setPackageName("招标包");
        h1.setScore(0.2f);

        List<RecallAppService.HitSummary> recalled = new ArrayList<>();
        recalled.add(h0);
        recalled.add(h1);
        when(recallAppService.recall(eq(100L), eq("资格"), eq(40), isNull(), isNull()))
                .thenReturn(recalled);

        RerankPort.RerankHit r0 = new RerankPort.RerankHit();
        r0.setIndex(1);
        r0.setScore(0.95f);
        List<RerankPort.RerankHit> ranked = new ArrayList<>();
        ranked.add(r0);
        when(rerankPort.rerank(eq("资格"), anyList(), eq(8), eq("tender-instruct")))
                .thenReturn(ranked);

        List<RerankAppService.RerankedHit> result =
                service.recallThenRerank(100L, "资格", 0, 0, "TENDER", null, null);

        assertEquals(1, result.size());
        assertEquals(11L, result.get(0).getChunkId());
        assertEquals(0.2f, result.get(0).getRecallScore(), 0.0001f);
        assertEquals(0.95f, result.get(0).getRerankScore(), 0.0001f);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> docsCaptor = ArgumentCaptor.forClass(List.class);
        verify(rerankPort).rerank(eq("资格"), docsCaptor.capture(), eq(8), eq("tender-instruct"));
        assertEquals(2, docsCaptor.getValue().size());
        assertEquals("段A", docsCaptor.getValue().get(0));
    }
}
