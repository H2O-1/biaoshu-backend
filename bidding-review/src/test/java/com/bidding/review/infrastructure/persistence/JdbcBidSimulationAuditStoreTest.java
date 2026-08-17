package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.BidSimulationAuditPort;
import com.bidding.common.port.TenderAnalysisPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
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

class JdbcBidSimulationAuditStoreTest {

    @Test
    void listAndCountApplyStatusFilter() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        @SuppressWarnings("unchecked")
        List<BidSimulationAuditPort.AuditSummary> empty = new ArrayList<>();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(empty);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        JdbcBidSimulationAuditStore store = new JdbcBidSimulationAuditStore(jdbcTemplate);

        store.listByOrg(2L, "投标", BidSimulationAuditPort.STATUS_FAILED, 20, 10);
        store.countByOrg(2L, "投标", BidSimulationAuditPort.STATUS_FAILED);

        ArgumentCaptor<String> listSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> listArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(listSql.capture(), any(RowMapper.class), listArgs.capture());
        assertTrue(listSql.getValue().contains("status <> 'OBSOLETE'"));
        assertTrue(listSql.getValue().contains("AND status = ?"));
        assertTrue(listSql.getValue().contains("AND name LIKE ?"));
        assertEquals(2L, listArgs.getValue()[0]);
        assertEquals(BidSimulationAuditPort.STATUS_FAILED, listArgs.getValue()[1]);
        assertEquals("%投标%", listArgs.getValue()[2]);
        assertEquals(10, listArgs.getValue()[3]);
        assertEquals(20, listArgs.getValue()[4]);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());
        assertTrue(countSql.getValue().contains("status <> 'OBSOLETE'"));
        assertTrue(countSql.getValue().contains("AND status = ?"));
        assertTrue(countSql.getValue().contains("AND name LIKE ?"));
        assertEquals(2L, countArgs.getValue()[0]);
        assertEquals(BidSimulationAuditPort.STATUS_FAILED, countArgs.getValue()[1]);
        assertEquals("%投标%", countArgs.getValue()[2]);
    }

    @Test
    void insertItemsStoresQualificationScoringLogicIssueAndTypo() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcBidSimulationAuditStore store = new JdbcBidSimulationAuditStore(jdbcTemplate);

        BidSimulationAuditPort.ItemDraft qualification = new BidSimulationAuditPort.ItemDraft();
        qualification.setItemType(BidSimulationAuditPort.ITEM_QUALIFICATION);
        qualification.setRequirementCode("Q1");
        qualification.setTitle("资质要求");
        qualification.setContent("具备有效资质。");
        qualification.setResult(BidSimulationAuditPort.RESULT_PASS);
        qualification.setReason("投标文件提供了有效资质。");
        qualification.setSourceExcerpt("有效资质证书");
        qualification.setPageNo(12);
        qualification.setChunkIdsJson("[9101]");
        qualification.setEvidenceJson("[{\"chunkId\":9101}]");
        qualification.setRawJson("{\"result\":\"PASS\"}");
        qualification.setSortOrder(0);

        BidSimulationAuditPort.ItemDraft scoring = new BidSimulationAuditPort.ItemDraft();
        scoring.setItemType(BidSimulationAuditPort.ITEM_SCORING);
        scoring.setRequirementCode("S1");
        scoring.setTitle("技术方案");
        scoring.setContent("根据技术方案完整性评分。");
        scoring.setReason("方案较完整。");
        scoring.setSuggestedScore(new BigDecimal("8.50"));
        scoring.setFullScore(new BigDecimal("10.00"));
        scoring.setSubType("TECH_SCORE");
        scoring.setSortOrder(1);

        BidSimulationAuditPort.ItemDraft logic = new BidSimulationAuditPort.ItemDraft();
        logic.setItemType(BidSimulationAuditPort.ITEM_LOGIC_ISSUE);
        logic.setRequirementCode("逻辑问题-1");
        logic.setTitle("服务周期不一致");
        logic.setContent("正文写12个月，计划写10个月。");
        logic.setReason("前后表述不一致。");
        logic.setSubType("TERM_INCONSISTENCY");
        logic.setSortOrder(2);

        BidSimulationAuditPort.ItemDraft typo = new BidSimulationAuditPort.ItemDraft();
        typo.setItemType(BidSimulationAuditPort.ITEM_TYPO);
        typo.setRequirementCode("错别字-1");
        typo.setTitle("按装调试");
        typo.setContent("建议改为安装调试。");
        typo.setSortOrder(3);

        store.insertItems(55L, List.of(qualification, scoring, logic, typo));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(4)).update(sqlCaptor.capture(), argsCaptor.capture());
        assertTrue(sqlCaptor.getAllValues().get(0).contains("biz_bid_simulation_audit_item"));

        Object[] qualificationArgs = argsCaptor.getAllValues().get(0);
        assertEquals(55L, qualificationArgs[0]);
        assertEquals(BidSimulationAuditPort.ITEM_QUALIFICATION, qualificationArgs[1]);
        assertEquals("Q1", qualificationArgs[2]);
        assertEquals(BidSimulationAuditPort.RESULT_PASS, qualificationArgs[5]);
        assertEquals("投标文件提供了有效资质。", qualificationArgs[6]);
        assertEquals("[{\"chunkId\":9101}]", qualificationArgs[13]);
        assertEquals("{\"result\":\"PASS\"}", qualificationArgs[14]);

        Object[] scoringArgs = argsCaptor.getAllValues().get(1);
        assertEquals(BidSimulationAuditPort.ITEM_SCORING, scoringArgs[1]);
        assertEquals("S1", scoringArgs[2]);
        assertEquals(new BigDecimal("8.50"), scoringArgs[7]);
        assertEquals(new BigDecimal("10.00"), scoringArgs[8]);
        assertEquals("TECH_SCORE", scoringArgs[9]);

        assertEquals(BidSimulationAuditPort.ITEM_LOGIC_ISSUE, argsCaptor.getAllValues().get(2)[1]);
        assertEquals(BidSimulationAuditPort.ITEM_TYPO, argsCaptor.getAllValues().get(3)[1]);
    }

    @Test
    void markSuccessUpdatesSummaryAndStatus() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcBidSimulationAuditStore store = new JdbcBidSimulationAuditStore(jdbcTemplate);
        BidSimulationAuditPort.SummaryDraft summary = new BidSimulationAuditPort.SummaryDraft();
        summary.setQualificationTotal(10);
        summary.setQualificationPassCount(7);
        summary.setQualificationFailCount(2);
        summary.setQualificationManualCount(1);
        summary.setQualificationPassRate(new BigDecimal("70.00"));
        summary.setScoreSuggested(new BigDecimal("16.00"));
        summary.setScoreTotal(new BigDecimal("23.00"));
        summary.setLogicIssueCount(2);
        summary.setTypoCount(3);
        TenderAnalysisPort.BasicInfoDraft basicInfo = new TenderAnalysisPort.BasicInfoDraft();
        basicInfo.setTenderer("采购人A");
        basicInfo.setAgency("代理B");
        basicInfo.setBudgetAmountText("预算金额100万元");
        basicInfo.setBidDeadlineText("2026年8月15日17:00");
        basicInfo.setEvidenceJson("[{\"field\":\"tenderer\"}]");
        basicInfo.setRawJson("{\"basicInfo\":true}");

        store.markSuccess(66L, summary, basicInfo);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("status = ?"));
        assertEquals(BidSimulationAuditPort.STATUS_SUCCESS, argsCaptor.getValue()[0]);
        assertEquals(10, argsCaptor.getValue()[1]);
        assertEquals(new BigDecimal("70.00"), argsCaptor.getValue()[5]);
        assertEquals(new BigDecimal("16.00"), argsCaptor.getValue()[6]);
        assertEquals(new BigDecimal("23.00"), argsCaptor.getValue()[7]);
        assertEquals(2, argsCaptor.getValue()[8]);
        assertEquals(3, argsCaptor.getValue()[9]);
        assertEquals("采购人A", argsCaptor.getValue()[10]);
        assertEquals("代理B", argsCaptor.getValue()[11]);
        assertEquals("预算金额100万元", argsCaptor.getValue()[12]);
        assertEquals("2026年8月15日17:00", argsCaptor.getValue()[13]);
        assertEquals("[{\"field\":\"tenderer\"}]", argsCaptor.getValue()[15]);
        assertEquals("{\"basicInfo\":true}", argsCaptor.getValue()[16]);
        assertEquals(66L, argsCaptor.getValue()[17]);
    }

    @Test
    void markFailedAndObsoleteUseExpectedStatus() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcBidSimulationAuditStore store = new JdbcBidSimulationAuditStore(jdbcTemplate);

        store.markFailed(77L, "解析失败");
        store.markObsolete(2L, 77L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).update(sqlCaptor.capture(), argsCaptor.capture());

        assertTrue(sqlCaptor.getAllValues().get(0).contains("error_message = ?"));
        assertEquals(BidSimulationAuditPort.STATUS_FAILED, argsCaptor.getAllValues().get(0)[0]);
        assertEquals("解析失败", argsCaptor.getAllValues().get(0)[1]);
        assertEquals(77L, argsCaptor.getAllValues().get(0)[2]);

        assertTrue(sqlCaptor.getAllValues().get(1).contains("status <> ?"));
        assertEquals(BidSimulationAuditPort.STATUS_OBSOLETE, argsCaptor.getAllValues().get(1)[0]);
        assertEquals(77L, argsCaptor.getAllValues().get(1)[1]);
        assertEquals(2L, argsCaptor.getAllValues().get(1)[2]);
        assertEquals(BidSimulationAuditPort.STATUS_OBSOLETE, argsCaptor.getAllValues().get(1)[3]);
    }
}
