package com.bidding.identity.infrastructure.port;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.identity.domain.model.Org;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.OrgRepository;
import com.bidding.identity.domain.port.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertAccountPortAdapterTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrgRepository orgRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void createTemporaryExpertGeneratesAccountAndPassword() {
        when(orgRepository.findById(2L)).thenReturn(Optional.of(org(2L)));
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");
        when(userRepository.insert(
                org.mockito.ArgumentMatchers.eq(2L),
                anyString(),
                org.mockito.ArgumentMatchers.eq("hashed-password"),
                org.mockito.ArgumentMatchers.eq("张三"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("ACTIVE"),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq(9L)))
                .thenReturn(55L);

        ExpertAccountPortAdapter adapter = new ExpertAccountPortAdapter(
                userRepository, orgRepository, passwordEncoder);

        var result = adapter.createTemporaryExpert(2L, " 张三 ", 9L);

        assertEquals(55L, result.getUserId());
        assertEquals("张三", result.getDisplayName());
        assertNotNull(result.getUsername());
        assertTrue(result.getUsername().startsWith("expert_"));
        assertNotNull(result.getTemporaryPassword());
        assertEquals("hashed-password", result.getPasswordHash());
        verify(userRepository).replaceRoles(55L, Set.of("EXPERT"));
    }

    @Test
    void resetTemporaryExpertPasswordUpdatesPasswordAndTokenVersion() {
        when(userRepository.findById(55L)).thenReturn(Optional.of(user(55L, Set.of("EXPERT"), "ACTIVE")));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");
        ExpertAccountPortAdapter adapter = new ExpertAccountPortAdapter(
                userRepository, orgRepository, passwordEncoder);

        var result = adapter.resetTemporaryExpertPassword(55L);

        assertEquals(55L, result.getUserId());
        assertNotNull(result.getTemporaryPassword());
        verify(userRepository).updatePassword(55L, "new-hash", false);
        verify(userRepository).bumpTokenVersion(55L);
    }

    @Test
    void resetTemporaryExpertPasswordRejectsDisabledExpert() {
        when(userRepository.findById(55L)).thenReturn(Optional.of(user(55L, Set.of("EXPERT"), "DISABLED")));
        ExpertAccountPortAdapter adapter = new ExpertAccountPortAdapter(
                userRepository, orgRepository, passwordEncoder);

        BizException exception = assertThrows(BizException.class,
                () -> adapter.resetTemporaryExpertPassword(55L));

        assertEquals(ErrorCode.CONFLICT, exception.errorCode());
        verify(userRepository, never()).updatePassword(anyLong(), anyString(), anyBoolean());
        verify(userRepository, never()).bumpTokenVersion(anyLong());
    }

    @Test
    void disableTemporaryExpertDisablesAccountAndTokenVersion() {
        when(userRepository.findById(55L)).thenReturn(Optional.of(user(55L, Set.of("EXPERT"), "ACTIVE")));
        ExpertAccountPortAdapter adapter = new ExpertAccountPortAdapter(
                userRepository, orgRepository, passwordEncoder);

        adapter.disableTemporaryExpert(55L);

        verify(userRepository).updateStatus(55L, "DISABLED");
        verify(userRepository).bumpTokenVersion(55L);
    }

    @Test
    void deleteTemporaryExpertDeletesDisabledExpertAccount() {
        when(userRepository.findById(55L)).thenReturn(Optional.of(user(55L, Set.of("EXPERT"), "DISABLED")));
        ExpertAccountPortAdapter adapter = new ExpertAccountPortAdapter(
                userRepository, orgRepository, passwordEncoder);

        adapter.deleteTemporaryExpert(55L);

        verify(userRepository).deleteById(55L);
    }

    @Test
    void deleteTemporaryExpertRejectsNonExpertAccount() {
        when(userRepository.findById(55L)).thenReturn(Optional.of(user(55L, Set.of("TENDERER"), "ACTIVE")));
        ExpertAccountPortAdapter adapter = new ExpertAccountPortAdapter(
                userRepository, orgRepository, passwordEncoder);

        BizException exception = assertThrows(BizException.class,
                () -> adapter.deleteTemporaryExpert(55L));

        assertEquals(ErrorCode.CONFLICT, exception.errorCode());
        verify(userRepository, never()).deleteById(anyLong());
    }

    private static Org org(long id) {
        return new Org(id, "ORG_" + id, "组织" + id, "北京市", "TENDERER", "ACTIVE",
                1L, Instant.now(), Instant.now());
    }

    private static UserAccount user(long id, Set<String> roles, String status) {
        return new UserAccount(
                id,
                2L,
                "expert_" + id,
                "hash",
                "专家" + id,
                null,
                null,
                status,
                false,
                0,
                Instant.now(),
                Instant.now(),
                9L,
                roles
        );
    }
}
