package com.bidding.review.infrastructure.persistence;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.ExpertAccountPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectArtifactInvalidationPortAdapterTest {

    @Test
    void deleteExpertReviewsByProjectIdDeletesAccountsAndMarksRowsDeleted() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ExpertAccountPort expertAccountPort = mock(ExpertAccountPort.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(2L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(77L, 78L));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2, 1);
        ProjectArtifactInvalidationPortAdapter adapter =
                new ProjectArtifactInvalidationPortAdapter(jdbcTemplate, expertAccountPort);

        int deletedReviews = adapter.deleteExpertReviewsByProjectId(2L, 100L);

        assertEquals(1, deletedReviews);
        verify(expertAccountPort).deleteTemporaryExpert(77L);
        verify(expertAccountPort).deleteTemporaryExpert(78L);

        ArgumentCaptor<String> updateSqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> updateArgsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(2)).update(updateSqlCaptor.capture(), updateArgsCaptor.capture());
        assertTrue(updateSqlCaptor.getAllValues().get(0).contains("UPDATE biz_expert_review_member"));
        assertEquals("DELETED", updateArgsCaptor.getAllValues().get(0)[0]);
        assertEquals(2L, updateArgsCaptor.getAllValues().get(0)[1]);
        assertEquals(100L, updateArgsCaptor.getAllValues().get(0)[2]);
        assertTrue(updateSqlCaptor.getAllValues().get(1).contains("UPDATE biz_expert_review SET status"));
        assertEquals("DELETED", updateArgsCaptor.getAllValues().get(1)[0]);
    }

    @Test
    void deleteExpertReviewsByProjectIdRejectsMemberStatusChange() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ExpertAccountPort expertAccountPort = mock(ExpertAccountPort.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(2L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(77L, 78L));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        ProjectArtifactInvalidationPortAdapter adapter =
                new ProjectArtifactInvalidationPortAdapter(jdbcTemplate, expertAccountPort);

        BizException exception = assertThrows(BizException.class,
                () -> adapter.deleteExpertReviewsByProjectId(2L, 100L));

        assertEquals(ErrorCode.CONFLICT, exception.errorCode());
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }
}
