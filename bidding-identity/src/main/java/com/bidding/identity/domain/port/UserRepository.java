package com.bidding.identity.domain.port;

import com.bidding.identity.domain.model.Org;
import com.bidding.identity.domain.model.Role;
import com.bidding.identity.domain.model.UserAccount;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 用户和角色的数据库操作接口。
 */
public interface UserRepository {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findById(long userId);

    List<UserAccount> search(String keyword, int offset, int limit);

    long countSearch(String keyword);

    void replaceRoles(long userId, Set<String> roleCodes);

    long countUsersWithRole(String roleCode);

    boolean existsByUsername(String username);

    boolean existsOrgById(long orgId);

    /** 按 ID 查组织（登录 / 当前用户展示用） */
    Optional<Org> findOrgById(long orgId);

    long insert(long orgId,
                String username,
                String passwordHash,
                String displayName,
                String status,
                boolean mustChangePassword);

    void updatePassword(long userId, String passwordHash, boolean mustChangePassword);

    void bumpTokenVersion(long userId);

    void updateStatus(long userId, String status);

    Optional<Long> findSystemOrgId();

    List<Role> listRoles();
}
