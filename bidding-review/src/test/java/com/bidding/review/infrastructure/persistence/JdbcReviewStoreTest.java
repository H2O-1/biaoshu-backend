package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.ReviewPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcReviewStoreTest {

    @Test
    void insertItemsDefaultsScoringZeroScoreVerdictToFail() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(any(String.class))).thenReturn(ps);
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenAnswer(invocation -> {
            PreparedStatementCreator creator = invocation.getArgument(0);
            creator.createPreparedStatement(connection);
            return 1;
        });

        JdbcReviewStore store = new JdbcReviewStore(jdbcTemplate);
        ReviewPort.ItemDraft item = new ReviewPort.ItemDraft();
        item.setRequirementItemId(128L);
        item.setDimensionCode(ReviewPort.MAIN_SCORING);
        item.setRequirementText("报价得分=基准价/报价x30");
        item.setMust(false);
        item.setVerdict(null);
        item.setMainType(ReviewPort.MAIN_SCORING);
        item.setSuggestedScore(BigDecimal.ZERO);
        item.setFullScore(new BigDecimal("30.00"));
        item.setSortOrder(17);

        store.insertItems(7L, List.of(item));

        verify(ps).setString(6, ReviewPort.VERDICT_FAIL);
    }
}
