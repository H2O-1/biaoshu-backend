package com.bidding.identity.domain.port;

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

    /**
     * 按组织查用户（排除已删除），支持关键字模糊搜。
     */
    List<UserAccount> searchByOrgId(long orgId, String keyword, int offset, int limit);

    long countSearchByOrgId(long orgId, String keyword);

    void replaceRoles(long userId, Set<String> roleCodes);

    long countUsersWithRole(String roleCode);

    boolean existsByUsername(String username);

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    long insert(long orgId,
                String username,
                String passwordHash,
                String displayName,
                String phone,
                String email,
                String status,
                boolean mustChangePassword);

    void updatePassword(long userId, String passwordHash, boolean mustChangePassword);

    void bumpTokenVersion(long userId);

    /**
     * 吊销某组织下全部未删除用户的会话（token_version + 1）。
     * 用于组织停用时立刻踢下线；不修改用户 status。
     */
    int bumpTokenVersionByOrgId(long orgId);

    void updateStatus(long userId, String status);

    List<Role> listRoles();
}
