package com.bidding.identity.infrastructure.persistence;

import com.bidding.identity.domain.model.Role;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 用 JDBC 操作用户、角色相关表。
 */
@Repository
public class JdbcUserRepository implements UserRepository {

    private static final String USER_COLUMNS =
            "id, org_id, username, password_hash, display_name, phone, email, status, "
                    + "must_change_password, token_version, created_at, updated_at, created_by";

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<UserAccount> userRowMapper = (rs, rowNum) -> {
        long createdByRaw = rs.getLong("created_by");
        Long createdBy = rs.wasNull() ? null : createdByRaw;
        return new UserAccount(
                rs.getLong("id"),
                rs.getLong("org_id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("status"),
                rs.getBoolean("must_change_password"),
                rs.getInt("token_version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                createdBy,
                new HashSet<>()
        );
    };

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> list = jdbcTemplate.query(
                "SELECT " + USER_COLUMNS + " FROM sys_user WHERE username = ?",
                userRowMapper,
                username
        );
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withRoles(list.get(0)));
    }

    @Override
    public Optional<UserAccount> findById(long userId) {
        List<UserAccount> list = jdbcTemplate.query(
                "SELECT " + USER_COLUMNS + " FROM sys_user WHERE id = ?",
                userRowMapper,
                userId
        );
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withRoles(list.get(0)));
    }

    @Override
    public List<UserAccount> search(String keyword, int offset, int limit) {
        String like = "%" + keyword + "%";
        List<UserAccount> users = jdbcTemplate.query(
                "SELECT " + USER_COLUMNS + " FROM sys_user "
                        + "WHERE (? = '' OR username LIKE ? OR display_name LIKE ? "
                        + "OR phone LIKE ? OR email LIKE ?) "
                        + "ORDER BY id LIMIT ? OFFSET ?",
                userRowMapper,
                keyword, like, like, like, like, limit, offset
        );
        List<UserAccount> result = new ArrayList<>();
        for (UserAccount user : users) {
            result.add(withRoles(user));
        }
        return result;
    }

    @Override
    public long countSearch(String keyword) {
        String like = "%" + keyword + "%";
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user "
                        + "WHERE (? = '' OR username LIKE ? OR display_name LIKE ? "
                        + "OR phone LIKE ? OR email LIKE ?)",
                Long.class,
                keyword, like, like, like, like
        );
        return count == null ? 0 : count;
    }

    @Override
    public List<UserAccount> searchByOrgId(long orgId, String keyword, int offset, int limit) {
        String like = "%" + keyword + "%";
        // 二级列表：只看该组织下、未删除的账号
        List<UserAccount> users = jdbcTemplate.query(
                "SELECT " + USER_COLUMNS + " FROM sys_user "
                        + "WHERE org_id = ? AND status <> 'DELETED' "
                        + "AND (? = '' OR username LIKE ? OR display_name LIKE ? "
                        + "OR phone LIKE ? OR email LIKE ?) "
                        + "ORDER BY id LIMIT ? OFFSET ?",
                userRowMapper,
                orgId, keyword, like, like, like, like, limit, offset
        );
        List<UserAccount> result = new ArrayList<>();
        for (UserAccount user : users) {
            result.add(withRoles(user));
        }
        return result;
    }

    @Override
    public long countSearchByOrgId(long orgId, String keyword) {
        String like = "%" + keyword + "%";
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user "
                        + "WHERE org_id = ? AND status <> 'DELETED' "
                        + "AND (? = '' OR username LIKE ? OR display_name LIKE ? "
                        + "OR phone LIKE ? OR email LIKE ?)",
                Long.class,
                orgId, keyword, like, like, like, like
        );
        return count == null ? 0 : count;
    }

    @Override
    public List<UserAccount> searchPlatformAdmins(String keyword, String status, int offset, int limit) {
        String like = "%" + keyword + "%";
        String statusKw = status == null ? "" : status;
        List<UserAccount> users = jdbcTemplate.query(
                "SELECT DISTINCT u.id, u.org_id, u.username, u.password_hash, u.display_name, "
                        + "u.phone, u.email, u.status, u.must_change_password, u.token_version, "
                        + "u.created_at, u.updated_at, u.created_by "
                        + "FROM sys_user u "
                        + "INNER JOIN sys_user_role ur ON ur.user_id = u.id "
                        + "WHERE u.status <> 'DELETED' "
                        + "AND ur.role_code IN ('PLATFORM_ADMIN', 'PLATFORM_SUPER_ADMIN') "
                        + "AND (? = '' OR u.status = ?) "
                        + "AND (? = '' OR u.username LIKE ? OR u.display_name LIKE ?) "
                        + "ORDER BY u.id LIMIT ? OFFSET ?",
                userRowMapper,
                statusKw, statusKw,
                keyword, like, like, limit, offset
        );
        List<UserAccount> result = new ArrayList<>();
        for (UserAccount user : users) {
            result.add(withRoles(user));
        }
        return result;
    }

    @Override
    public long countPlatformAdmins(String keyword, String status) {
        String like = "%" + keyword + "%";
        String statusKw = status == null ? "" : status;
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT u.id) FROM sys_user u "
                        + "INNER JOIN sys_user_role ur ON ur.user_id = u.id "
                        + "WHERE u.status <> 'DELETED' "
                        + "AND ur.role_code IN ('PLATFORM_ADMIN', 'PLATFORM_SUPER_ADMIN') "
                        + "AND (? = '' OR u.status = ?) "
                        + "AND (? = '' OR u.username LIKE ? OR u.display_name LIKE ?)",
                Long.class,
                statusKw, statusKw,
                keyword, like, like
        );
        return count == null ? 0 : count;
    }

    @Override
    public long countPlatformAdminsByStatus(String status) {
        String safeStatus = status == null ? "" : status.trim();
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT u.id) FROM sys_user u "
                        + "INNER JOIN sys_user_role ur ON ur.user_id = u.id "
                        + "WHERE u.status = ? "
                        + "AND ur.role_code IN ('PLATFORM_ADMIN', 'PLATFORM_SUPER_ADMIN')",
                Long.class,
                safeStatus
        );
        return count == null ? 0 : count;
    }

    @Override
    public void replaceRoles(long userId, Set<String> roleCodes) {
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        for (String roleCode : roleCodes) {
            jdbcTemplate.update(
                    "INSERT INTO sys_user_role(user_id, role_code) VALUES (?, ?)",
                    userId,
                    roleCode
            );
        }
    }

    @Override
    public long countUsersWithRole(String roleCode) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user_role WHERE role_code = ?",
                Long.class,
                roleCode
        );
        return count == null ? 0 : count;
    }

    @Override
    public List<Long> listUserIdsWithRole(String roleCode) {
        return jdbcTemplate.query(
                "SELECT user_id FROM sys_user_role WHERE role_code = ? ORDER BY user_id",
                (rs, rowNum) -> rs.getLong("user_id"),
                roleCode
        );
    }

    @Override
    public long countDistinctActiveUsersWithAnyRole(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return 0L;
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < roleCodes.size(); i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }
        List<Object> args = new ArrayList<>();
        for (String roleCode : roleCodes) {
            args.add(roleCode);
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT ur.user_id) FROM sys_user_role ur "
                        + "INNER JOIN sys_user u ON u.id = ur.user_id AND u.status <> 'DELETED' "
                        + "WHERE ur.role_code IN (" + placeholders + ")",
                Long.class,
                args.toArray()
        );
        return count == null ? 0 : count;
    }

    @Override
    public boolean existsByUsername(String username) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE username = ?",
                Long.class,
                username
        );
        return count != null && count > 0;
    }

    @Override
    public boolean existsByPhone(String phone) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE phone = ?",
                Long.class,
                phone
        );
        return count != null && count > 0;
    }

    @Override
    public boolean existsByEmail(String email) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE email = ?",
                Long.class,
                email
        );
        return count != null && count > 0;
    }

    @Override
    public boolean existsByPhoneExceptUserId(String phone, long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE phone = ? AND id <> ?",
                Long.class,
                phone,
                userId
        );
        return count != null && count > 0;
    }

    @Override
    public boolean existsByEmailExceptUserId(String email, long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE email = ? AND id <> ?",
                Long.class,
                email,
                userId
        );
        return count != null && count > 0;
    }

    @Override
    public long insert(long orgId,
                       String username,
                       String passwordHash,
                       String displayName,
                       String phone,
                       String email,
                       String status,
                       boolean mustChangePassword,
                       Long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sys_user(org_id, username, password_hash, display_name, phone, email, status, "
                            + "must_change_password, token_version, created_by, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            ps.setString(4, displayName);
            ps.setString(5, phone);
            ps.setString(6, email);
            ps.setString(7, status);
            ps.setBoolean(8, mustChangePassword);
            if (createdBy == null) {
                ps.setObject(9, null);
            } else {
                ps.setLong(9, createdBy);
            }
            return ps;
        }, keyHolder);

        Number key = null;
        if (keyHolder.getKeys() != null && keyHolder.getKeys().get("id") != null) {
            key = (Number) keyHolder.getKeys().get("id");
        }
        if (key == null) {
            key = keyHolder.getKey();
        }
        if (key == null) {
            throw new IllegalStateException("插入用户未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void updatePassword(long userId, String passwordHash, boolean mustChangePassword) {
        jdbcTemplate.update(
                "UPDATE sys_user SET password_hash = ?, must_change_password = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                passwordHash,
                mustChangePassword,
                userId
        );
    }

    @Override
    public void updateProfile(long userId, String displayName, String phone, String email) {
        jdbcTemplate.update(
                "UPDATE sys_user SET display_name = ?, phone = ?, email = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                displayName,
                phone,
                email,
                userId
        );
    }

    @Override
    public void bumpTokenVersion(long userId) {
        jdbcTemplate.update(
                "UPDATE sys_user SET token_version = token_version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                userId
        );
    }

    @Override
    public int bumpTokenVersionByOrgId(long orgId) {
        return jdbcTemplate.update(
                "UPDATE sys_user SET token_version = token_version + 1, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE org_id = ? AND status <> 'DELETED'",
                orgId
        );
    }

    @Override
    public void updateStatus(long userId, String status) {
        jdbcTemplate.update(
                "UPDATE sys_user SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status,
                userId
        );
    }

    @Override
    public void deleteById(long userId) {
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
    }

    @Override
    public List<Role> listRoles() {
        return jdbcTemplate.query(
                "SELECT code, name FROM sys_role ORDER BY code",
                (rs, rowNum) -> new Role(rs.getString("code"), rs.getString("name"))
        );
    }

    private UserAccount withRoles(UserAccount user) {
        List<String> roles = jdbcTemplate.query(
                "SELECT role_code FROM sys_user_role WHERE user_id = ? ORDER BY role_code",
                (rs, rowNum) -> rs.getString(1),
                user.getId()
        );
        return new UserAccount(
                user.getId(),
                user.getOrgId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getDisplayName(),
                user.getPhone(),
                user.getEmail(),
                user.getStatus(),
                user.isMustChangePassword(),
                user.getTokenVersion(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getCreatedBy(),
                new HashSet<>(roles)
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
