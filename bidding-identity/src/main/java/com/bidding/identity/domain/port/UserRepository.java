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

    /**
     * 平台方账号分页（角色含 PLATFORM_ADMIN 或 PLATFORM_SUPER_ADMIN，排除已删除）。
     *
     * @param status 空=全部未删除；ACTIVE / DISABLED
     */
    List<UserAccount> searchPlatformAdmins(String keyword, String status, int offset, int limit);

    long countPlatformAdmins(String keyword, String status);

    /**
     * 平台方账号按状态计数（角色含 PLATFORM_ADMIN 或 PLATFORM_SUPER_ADMIN）。
     *
     * @param status ACTIVE / DISABLED 等
     */
    long countPlatformAdminsByStatus(String status);

    void replaceRoles(long userId, Set<String> roleCodes);

    long countUsersWithRole(String roleCode);

    /**
     * 拥有指定角色的用户 ID 列表（含已删除账号上的残留关联，调用方自行过滤）。
     */
    List<Long> listUserIdsWithRole(String roleCode);

    /**
     * 未删除用户中，拥有任一给定角色的去重人数。
     */
    long countDistinctActiveUsersWithAnyRole(List<String> roleCodes);

    boolean existsByUsername(String username);

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    /** 手机号是否已被其他用户占用（排除指定用户）。 */
    boolean existsByPhoneExceptUserId(String phone, long userId);

    /** 邮箱是否已被其他用户占用（排除指定用户）。 */
    boolean existsByEmailExceptUserId(String email, long userId);

    long insert(long orgId,
                String username,
                String passwordHash,
                String displayName,
                String phone,
                String email,
                String status,
                boolean mustChangePassword,
                Long createdBy);

    void updatePassword(long userId, String passwordHash, boolean mustChangePassword);

    /** 更新负责人姓名、手机号、邮箱。 */
    void updateProfile(long userId, String displayName, String phone, String email);

    void bumpTokenVersion(long userId);

    /**
     * 吊销某组织下全部未删除用户的会话（token_version + 1）。
     * 用于组织停用时立刻踢下线；不修改用户 status。
     */
    int bumpTokenVersionByOrgId(long orgId);

    void updateStatus(long userId, String status);

    List<Role> listRoles();
}
