package com.bidding.common.security;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TendererAuthTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void requireTendererRejectsAnonymous() {
        BizException ex = assertThrows(BizException.class, TendererAuth::requireTenderer);
        assertEquals(ErrorCode.UNAUTHORIZED, ex.errorCode());
    }

    @Test
    void requireTendererRejectsNonTenderer() {
        Set<String> roles = new HashSet<>();
        roles.add("PLATFORM_ADMIN");
        UserContext.set(new CurrentUser(1L, 2L, "admin", roles, 0, false));
        BizException ex = assertThrows(BizException.class,
                () -> TendererAuth.requireTenderer("仅招标方可操作项目"));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
        assertEquals("仅招标方可操作项目", ex.getMessage());
    }

    @Test
    void requireOwnedProjectRejectsOtherOrg() {
        ProjectLookupPort port = mock(ProjectLookupPort.class);
        ProjectLookupPort.ProjectRef ref = new ProjectLookupPort.ProjectRef();
        ref.setId(100L);
        ref.setOrgId(9L);
        when(port.findActiveById(100L)).thenReturn(ref);
        BizException ex = assertThrows(BizException.class,
                () -> TendererAuth.requireOwnedProject(port, 100L, 2L));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    void requireOwnedPackageOk() {
        PackageLookupPort port = mock(PackageLookupPort.class);
        PackageLookupPort.PackageRef ref = new PackageLookupPort.PackageRef();
        ref.setId(50L);
        ref.setOrgId(2L);
        when(port.findActiveById(50L)).thenReturn(ref);
        PackageLookupPort.PackageRef got = TendererAuth.requireOwnedPackage(port, 50L, 2L);
        assertEquals(50L, got.getId());
    }
}
