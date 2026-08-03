package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.ReviewDimensionPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewDimensionAppServiceTest {

    @Mock
    private ReviewDimensionPort reviewDimensionPort;

    private ReviewDimensionAppService appService;

    @BeforeEach
    void setUp() {
        appService = new ReviewDimensionAppService(reviewDimensionPort);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listEnabledReturnsOnlyEnabledForLoginUser() {
        setUser(false);
        ReviewDimensionPort.Dimension dim = new ReviewDimensionPort.Dimension();
        dim.setId(1L);
        dim.setCode("QUALIFICATION");
        dim.setName("资格与资质");
        dim.setDefaultSelected(true);
        dim.setEnabled(true);
        dim.setSortOrder(10);
        List<String> queries = new ArrayList<>();
        queries.add("投标人资格条件与资质等级要求");
        dim.setQueries(queries);
        List<ReviewDimensionPort.Dimension> source = new ArrayList<>();
        source.add(dim);
        when(reviewDimensionPort.listAll(true)).thenReturn(source);

        List<ReviewDimensionAppService.DimensionSummary> result = appService.listEnabled();
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals("QUALIFICATION", result.get(0).getCode());
        assertEquals(1, result.get(0).getQueries().size());
        verify(reviewDimensionPort).listAll(true);
    }

    @Test
    void updateRequiresPlatformAdmin() {
        setUser(false);
        BizException ex = assertThrows(BizException.class,
                () -> appService.update(1L, "资格", true, true, 10, new ArrayList<>()));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
    }

    @Test
    void updateReplacesQueriesById() {
        setUser(true);
        ReviewDimensionPort.Dimension existing = new ReviewDimensionPort.Dimension();
        existing.setId(1L);
        existing.setCode("QUALIFICATION");
        existing.setName("旧名");
        existing.setDefaultSelected(true);
        existing.setEnabled(true);
        existing.setSortOrder(10);

        ReviewDimensionPort.Dimension updated = new ReviewDimensionPort.Dimension();
        updated.setId(1L);
        updated.setCode("QUALIFICATION");
        updated.setName("资格与资质");
        updated.setDefaultSelected(false);
        updated.setEnabled(true);
        updated.setSortOrder(15);
        List<String> newQueries = new ArrayList<>();
        newQueries.add("新检索词A");
        newQueries.add("新检索词B");
        updated.setQueries(newQueries);

        when(reviewDimensionPort.findById(1L)).thenReturn(existing, updated);

        List<String> bodyQueries = new ArrayList<>();
        bodyQueries.add(" 新检索词A ");
        bodyQueries.add("新检索词B");
        ReviewDimensionAppService.DimensionSummary summary = appService.update(
                1L, "资格与资质", false, true, 15, bodyQueries);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> queriesCaptor = ArgumentCaptor.forClass(List.class);
        verify(reviewDimensionPort).update(
                eq(1L),
                eq("资格与资质"),
                eq(false),
                eq(true),
                eq(15),
                queriesCaptor.capture()
        );
        assertEquals(2, queriesCaptor.getValue().size());
        assertEquals("新检索词A", queriesCaptor.getValue().get(0));
        assertEquals(1L, summary.getId());
        assertTrue(summary.getQueries().contains("新检索词A"));
    }

    @Test
    void listAllForAdminRejectsNonAdmin() {
        setUser(false);
        assertThrows(BizException.class, () -> appService.listAllForAdmin());
    }

    private void setUser(boolean admin) {
        Set<String> roles = new HashSet<>();
        if (admin) {
            roles.add("PLATFORM_ADMIN");
        } else {
            roles.add("TENDERER");
        }
        UserContext.set(new CurrentUser(1L, 1L, "u", roles, 0, false));
    }
}
