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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<UserAccount> userRowMapper = (rs, rowNum) -> new UserAccount(
            rs.getLong("id"),
            rs.getLong("org_id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at")),
            Set.of()
    );

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> list = jdbcTemplate.query(
                """
                        SELECT id, org_id, username, password_hash, display_name, status, created_at
                        FROM sys_user WHERE username = ?
                        """,
                userRowMapper,
                username
        );
        return list.stream().findFirst().map(this::withRoles);
    }

    @Override
    public Optional<UserAccount> findById(long userId) {
        List<UserAccount> list = jdbcTemplate.query(
                """
                        SELECT id, org_id, username, password_hash, display_name, status, created_at
                        FROM sys_user WHERE id = ?
                        """,
                userRowMapper,
                userId
        );
        return list.stream().findFirst().map(this::withRoles);
    }

    @Override
    public List<UserAccount> search(String keyword, int offset, int limit) {
        String like = "%" + keyword + "%";
        List<UserAccount> users = jdbcTemplate.query(
                """
                        SELECT id, org_id, username, password_hash, display_name, status, created_at
                        FROM sys_user
                        WHERE (? = '' OR username LIKE ? OR display_name LIKE ?)
                        ORDER BY id
                        LIMIT ? OFFSET ?
                        """,
                userRowMapper,
                keyword, like, like, limit, offset
        );
        return users.stream().map(this::withRoles).toList();
    }

    @Override
    public long countSearch(String keyword) {
        String like = "%" + keyword + "%";
        Long count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(1) FROM sys_user
                        WHERE (? = '' OR username LIKE ? OR display_name LIKE ?)
                        """,
                Long.class,
                keyword, like, like
        );
        return count == null ? 0 : count;
    }

    @Override
    public void replaceRoles(long userId, Set<String> roleCodes) {
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        for (String roleCode : roleCodes) {
            jdbcTemplate.update(
                    "INSERT INTO sys_user_role(user_id, role_code) VALUES (?, ?)",
                    userId, roleCode
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
    public boolean existsByUsername(String username) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE username = ?",
                Long.class,
                username
        );
        return count != null && count > 0;
    }

    @Override
    public long insert(long orgId, String username, String passwordHash, String displayName, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    """
                            INSERT INTO sys_user(org_id, username, password_hash, display_name, status, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """,
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            ps.setString(4, displayName);
            ps.setString(5, status);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKeys() == null ? null : (Number) keyHolder.getKeys().get("id");
        if (key == null) {
            key = keyHolder.getKey();
        }
        if (key == null) {
            throw new IllegalStateException("插入用户未返回主键");
        }
        return key.longValue();
    }

    @Override
    public Optional<Long> findSystemOrgId() {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE code = 'SYSTEM' LIMIT 1",
                (rs, rowNum) -> rs.getLong(1)
        );
        return ids.stream().findFirst();
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
                user.id()
        );
        return new UserAccount(
                user.id(),
                user.orgId(),
                user.username(),
                user.passwordHash(),
                user.displayName(),
                user.status(),
                user.createdAt(),
                new HashSet<>(roles)
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
