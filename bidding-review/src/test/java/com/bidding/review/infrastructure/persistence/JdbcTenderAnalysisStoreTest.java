package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.TenderAnalysisPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcTenderAnalysisStoreTest {

    @Test
    void listAndCountApplyStatusFilter() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        List<TenderAnalysisPort.AnalysisSummary> empty = new ArrayList<>();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(empty);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        JdbcTenderAnalysisStore store = new JdbcTenderAnalysisStore(jdbcTemplate);

        store.listByOrg(2L, "", TenderAnalysisPort.STATUS_FAILED, 20, 10);
        store.countByOrg(2L, "", TenderAnalysisPort.STATUS_FAILED);

        ArgumentCaptor<String> listSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> listArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(listSql.capture(), any(RowMapper.class), listArgs.capture());
        assertTrue(listSql.getValue().contains("status <> 'OBSOLETE'"));
        assertTrue(listSql.getValue().contains("biz_tender_analysis_item"));
        assertTrue(listSql.getValue().contains("requirement_count"));
        assertTrue(listSql.getValue().contains("issue_count"));
        assertTrue(listSql.getValue().contains("AND a.status = ?"));
        assertEquals(2L, listArgs.getValue()[0]);
        assertEquals(TenderAnalysisPort.STATUS_FAILED, listArgs.getValue()[1]);
        assertEquals(10, listArgs.getValue()[2]);
        assertEquals(20, listArgs.getValue()[3]);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());
        assertTrue(countSql.getValue().contains("status <> 'OBSOLETE'"));
        assertTrue(countSql.getValue().contains("AND a.status = ?"));
        assertEquals(2L, countArgs.getValue()[0]);
        assertEquals(TenderAnalysisPort.STATUS_FAILED, countArgs.getValue()[1]);
    }

    @Test
    void insertItemsStoresLogicIssueAndTypoItemTypes() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcTenderAnalysisStore store = new JdbcTenderAnalysisStore(jdbcTemplate);

        TenderAnalysisPort.ItemDraft logic = new TenderAnalysisPort.ItemDraft();
        logic.setItemType(TenderAnalysisPort.ITEM_LOGIC_ISSUE);
        logic.setRequirementCode("逻辑问题-1");
        logic.setTitle("业绩门槛与预算不匹配");
        logic.setContent("预算100万元，业绩要求500万元。");
        logic.setSubType("THRESHOLD_INCONSISTENCY");
        logic.setSourceExcerpt("预算100万元；业绩要求500万元");
        logic.setPageNo(5);
        logic.setChunkIdsJson("[9001,9002]");
        logic.setEvidenceJson("[{\"chunkId\":9001}]");
        logic.setSortOrder(2);

        TenderAnalysisPort.ItemDraft typo = new TenderAnalysisPort.ItemDraft();
        typo.setItemType(TenderAnalysisPort.ITEM_TYPO);
        typo.setRequirementCode("错别字-1");
        typo.setTitle("疑似错别字：解折");
        typo.setContent("建议改为解析。");
        typo.setSourceExcerpt("解折失败，请重新解析");
        typo.setPageNo(6);
        typo.setChunkIdsJson("[9003]");
        typo.setEvidenceJson("[{\"chunkId\":9003}]");
        typo.setSortOrder(3);

        store.insertItems(55L, List.of(logic, typo));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), argsCaptor.capture());

        assertTrue(sqlCaptor.getAllValues().get(0).contains("biz_tender_analysis_item"));
        Object[] logicArgs = argsCaptor.getAllValues().get(0);
        assertEquals(55L, logicArgs[0]);
        assertEquals(TenderAnalysisPort.ITEM_LOGIC_ISSUE, logicArgs[1]);
        assertEquals("逻辑问题-1", logicArgs[2]);
        assertEquals("业绩门槛与预算不匹配", logicArgs[3]);
        assertEquals("预算100万元，业绩要求500万元。", logicArgs[4]);
        assertEquals("THRESHOLD_INCONSISTENCY", logicArgs[5]);
        assertEquals("[{\"chunkId\":9001}]", logicArgs[9]);

        Object[] typoArgs = argsCaptor.getAllValues().get(1);
        assertEquals(55L, typoArgs[0]);
        assertEquals(TenderAnalysisPort.ITEM_TYPO, typoArgs[1]);
        assertEquals("错别字-1", typoArgs[2]);
        assertEquals("疑似错别字：解折", typoArgs[3]);
        assertEquals("建议改为解析。", typoArgs[4]);
        assertEquals("[{\"chunkId\":9003}]", typoArgs[9]);
    }
}
