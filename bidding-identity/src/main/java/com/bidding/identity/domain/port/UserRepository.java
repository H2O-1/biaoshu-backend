package com.bidding.identity.domain.port;

import com.bidding.identity.domain.model.Role;
import com.bidding.identity.domain.model.UserAccount;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface UserRepository {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findById(long userId);

    List<UserAccount> search(String keyword, int offset, int limit);

    long countSearch(String keyword);

    void replaceRoles(long userId, Set<String> roleCodes);

    long countUsersWithRole(String roleCode);

    boolean existsByUsername(String username);

    long insert(long orgId, String username, String passwordHash, String displayName, String status);

    Optional<Long> findSystemOrgId();

    List<Role> listRoles();
}
