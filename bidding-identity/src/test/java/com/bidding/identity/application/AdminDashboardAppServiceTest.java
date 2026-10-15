package com.bidding.identity.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentIndexCountPort;
import com.bidding.common.port.ReviewStatusCountPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.model.RoleCode;
import com.bidding.identity.domain.port.OrgRepository;
import com.bidding.identity.domain.port.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDashboardAppServiceTest {

    @Mock
    private OrgRepository orgRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DocumentIndexCountPort documentIndexCountPort;

    @Mock
    private ReviewStatusCountPort reviewStatusCountPort;

    @InjectMocks
    private AdminDashboardAppService adminDashboardAppService;

    @BeforeEach
    void setUp() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());
        UserContext.set(new CurrentUser(1L, 1L, "admin", roles, 0, false));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void getDashboardReturnsCounts() {
        when(orgRepository.countTendererOrgs()).thenReturn(12L);
        when(orgRepository.countBidderOrgs()).thenReturn(5L);
        when(userRepository.countPlatformAdminsByStatus("ACTIVE")).thenReturn(3L);
        when(userRepository.countPlatformAdminsByStatus("DISABLED")).thenReturn(1L);
        when(reviewStatusCountPort.countByStatus("SUCCESS")).thenReturn(8L);
        when(reviewStatusCountPort.countByStatus("FAILED")).thenReturn(2L);
        when(documentIndexCountPort.countActiveByIndexStatus("SUCCESS")).thenReturn(40L);
        when(documentIndexCountPort.countActiveByIndexStatus("FAILED")).thenReturn(5L);

        AdminDashboardAppService.DashboardSummary summary = adminDashboardAppService.getDashboard();

        assertEquals(12L, summary.getTendererOrgCount());
        assertEquals(5L, summary.getBidderOrgCount());
        assertEquals(3L, summary.getPlatformAdminActiveCount());
        assertEquals(1L, summary.getPlatformAdminDisabledCount());
        assertEquals(8L, summary.getReviewSuccessCount());
        assertEquals(2L, summary.getReviewFailedCount());
        assertEquals(40L, summary.getDocumentIndexSuccessCount());
        assertEquals(5L, summary.getDocumentIndexFailedCount());

        verify(orgRepository).countTendererOrgs();
        verify(orgRepository).countBidderOrgs();
        verify(userRepository).countPlatformAdminsByStatus("ACTIVE");
        verify(userRepository).countPlatformAdminsByStatus("DISABLED");
        verify(reviewStatusCountPort).countByStatus("SUCCESS");
        verify(reviewStatusCountPort).countByStatus("FAILED");
        verify(documentIndexCountPort).countActiveByIndexStatus("SUCCESS");
        verify(documentIndexCountPort).countActiveByIndexStatus("FAILED");
    }

    @Test
    void getDashboardForbiddenForTenderer() {
        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.TENDERER.name());
        UserContext.set(new CurrentUser(10L, 2L, "tenderer01", roles, 0, false));

        BizException ex = assertThrows(BizException.class, () -> adminDashboardAppService.getDashboard());
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        verifyNoInteractions(orgRepository, userRepository, documentIndexCountPort, reviewStatusCountPort);
    }
}
