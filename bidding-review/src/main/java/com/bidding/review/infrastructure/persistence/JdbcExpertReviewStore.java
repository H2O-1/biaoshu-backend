package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ReviewPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 专家评审 JDBC 存储。
 */
@Repository
public class JdbcExpertReviewStore {

    private static final String STATUS_BUILDING = "BUILDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_OBSOLETE = "OBSOLETE";
    private static final String STATUS_DELETED = "DELETED";
    private static final String MEMBER_STATUS_DELETED = "DELETED";
    private static final String MEMBER_STATUS_ACTIVE = "ACTIVE";
    private static final String MEMBER_STATUS_READ_ONLY = "READ_ONLY";
    private static final String MEMBER_STATUS_DISABLED = "DISABLED";
    private static final String SUBMIT_STATUS_NOT_SUBMITTED = "NOT_SUBMITTED";
    private static final String SUBMIT_STATUS_SUBMITTED = "SUBMITTED";
    private static final String SUBMIT_STATUS_OVERDUE_SUBMITTED = "OVERDUE_SUBMITTED";
    private static final String COMPANY_STATUS_NORMAL = "NORMAL";
    private static final String COMPANY_STATUS_PENDING_VETO = "PENDING_VETO";
    private static final String COMPANY_STATUS_VETOED = "VETOED";
    private static final String DISPLAY_MODE_NORMAL = "NORMAL";
    private static final String DISPLAY_MODE_PENDING_CONFIRMATION = "PENDING_CONFIRMATION";
    private static final String DISPLAY_MODE_CONTINUE_DISPLAY = "CONTINUE_DISPLAY";

    private final JdbcTemplate jdbcTemplate;

    public JdbcExpertReviewStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public ReviewRow findLatestByProject(long orgId, long projectId) {
        List<ReviewRow> rows = jdbcTemplate.query(
                "SELECT id, org_id, project_id, version, status, deadline_at, push_message, "
                        + "expert_count, submitted_expert_count, submitted_company_count, "
                        + "has_any_complete_submission, is_locked, created_by, created_at, updated_at "
                        + "FROM biz_expert_review "
                        + "WHERE org_id = ? AND project_id = ? AND status NOT IN (?, ?) "
                        + "ORDER BY version DESC, id DESC LIMIT 1",
                (rs, rowNum) -> mapReview(rs),
                orgId,
                projectId,
                STATUS_OBSOLETE,
                STATUS_DELETED
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public ReviewRow findById(long reviewId) {
        List<ReviewRow> rows = jdbcTemplate.query(
                "SELECT id, org_id, project_id, version, status, deadline_at, push_message, "
                        + "expert_count, submitted_expert_count, submitted_company_count, "
                        + "has_any_complete_submission, is_locked, created_by, created_at, updated_at "
                        + "FROM biz_expert_review WHERE id = ?",
                (rs, rowNum) -> mapReview(rs),
                reviewId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public ReviewRow findFirstExpiredRunningReview(Instant now) {
        List<ReviewRow> rows = jdbcTemplate.query(
                "SELECT id, org_id, project_id, version, status, deadline_at, push_message, "
                        + "expert_count, submitted_expert_count, submitted_company_count, "
                        + "has_any_complete_submission, is_locked, created_by, created_at, updated_at "
                        + "FROM biz_expert_review "
                        + "WHERE status = ? AND deadline_at IS NOT NULL AND deadline_at <= ? "
                        + "ORDER BY deadline_at ASC, id ASC LIMIT 1",
                (rs, rowNum) -> mapReview(rs),
                STATUS_RUNNING,
                toTimestamp(now)
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public MemberRow findCurrentMemberByUserId(long userId) {
        List<MemberRow> rows = jdbcTemplate.query(
                "SELECT m.id, m.review_id, m.project_id, m.user_id, m.username, m.display_name, "
                        + "m.password_hash, m.status, m.submit_status, m.submitted_at, "
                        + "m.first_password_shown, m.created_at, m.updated_at "
                        + "FROM biz_expert_review_member m "
                        + "INNER JOIN biz_expert_review r ON r.id = m.review_id "
                        + "WHERE m.user_id = ? AND m.status IN (?, ?) AND r.status IN (?, ?) "
                        + "ORDER BY r.updated_at DESC, r.id DESC, m.id DESC LIMIT 1",
                (rs, rowNum) -> mapMember(rs),
                userId,
                MEMBER_STATUS_ACTIVE,
                MEMBER_STATUS_READ_ONLY,
                STATUS_RUNNING,
                ReviewPort.STATUS_SUCCESS
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public MemberRow findMemberByIdAndUserId(long memberId, long userId) {
        List<MemberRow> rows = jdbcTemplate.query(
                "SELECT id, review_id, project_id, user_id, username, display_name, "
                        + "password_hash, status, submit_status, submitted_at, "
                        + "first_password_shown, created_at, updated_at "
                        + "FROM biz_expert_review_member "
                        + "WHERE id = ? AND user_id = ? AND status <> ?",
                (rs, rowNum) -> mapMember(rs),
                memberId,
                userId,
                MEMBER_STATUS_DELETED
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public MemberRow findMemberById(long reviewId, long memberId) {
        List<MemberRow> rows = jdbcTemplate.query(
                "SELECT id, review_id, project_id, user_id, username, display_name, "
                        + "password_hash, status, submit_status, submitted_at, "
                        + "first_password_shown, created_at, updated_at "
                        + "FROM biz_expert_review_member "
                        + "WHERE review_id = ? AND id = ? AND status <> ?",
                (rs, rowNum) -> mapMember(rs),
                reviewId,
                memberId,
                MEMBER_STATUS_DELETED
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public int countMembers(long reviewId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_expert_review_member WHERE review_id = ? AND status <> ?",
                Integer.class,
                reviewId,
                STATUS_DELETED
        );
        return count == null ? 0 : count;
    }

    @Transactional(readOnly = true)
    public boolean existsMemberDisplayName(long reviewId, String displayName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_expert_review_member "
                        + "WHERE review_id = ? AND LOWER(display_name) = LOWER(?) AND status <> ?",
                Integer.class,
                reviewId,
                displayName,
                STATUS_DELETED
        );
        return count != null && count > 0;
    }

    @Transactional(readOnly = true)
    public List<MemberRow> listMembers(long reviewId) {
        return jdbcTemplate.query(
                "SELECT id, review_id, project_id, user_id, username, display_name, "
                        + "password_hash, status, submit_status, submitted_at, first_password_shown, "
                        + "created_at, updated_at "
                        + "FROM biz_expert_review_member WHERE review_id = ? AND status <> ? "
                        + "ORDER BY id",
                (rs, rowNum) -> mapMember(rs),
                reviewId,
                STATUS_DELETED
        );
    }

    @Transactional(readOnly = true)
    public List<MemberRow> listActiveMembersByProject(long orgId, long projectId) {
        ReviewRow review = findLatestByProject(orgId, projectId);
        if (review == null) {
            return List.of();
        }
        return listMembers(review.getId());
    }

    @Transactional(readOnly = true)
    public List<ReviewItemSeed> findLatestAiReviewSeeds(long orgId,
                                                        long projectId,
                                                        List<Long> bidPackageIds) {
        if (bidPackageIds == null || bidPackageIds.isEmpty()) {
            return List.of();
        }
        String sql = "WITH latest_review AS ("
                + "SELECT id FROM biz_review WHERE org_id = ? AND project_id = ? AND status = ? "
                + "ORDER BY version DESC, id DESC LIMIT 1"
                + ") "
                + "SELECT ri.id AS source_review_item_id, ri.review_id AS source_review_id, "
                + "ri.requirement_item_id, ri.package_id AS bid_package_id, "
                + "COALESCE(NULLIF(ri.main_type, ''), ri.dimension_code) AS item_type, "
                + "ri.requirement_code, ri.title, ri.requirement_text AS content, "
                + "ri.status AS ai_status, ri.veto_risk AS ai_veto_risk, "
                + "ri.judgement AS ai_judgement, ri.bid_evidence_json AS evidence_json, "
                + "ri.suggested_score AS ai_suggested_score, ri.full_score, "
                + "ri.deduction_reason AS ai_deduction_reason, ri.sort_order "
                + "FROM biz_review_item ri "
                + "INNER JOIN latest_review lr ON lr.id = ri.review_id "
                + "WHERE ri.package_id IN (" + placeholders(bidPackageIds.size()) + ") "
                + "AND ("
                + "(COALESCE(NULLIF(ri.main_type, ''), ri.dimension_code) = ? AND ri.status = ?) "
                + "OR COALESCE(NULLIF(ri.main_type, ''), ri.dimension_code) = ?"
                + ") "
                + "ORDER BY ri.package_id ASC, ri.sort_order ASC, ri.id ASC";

        List<Object> args = new ArrayList<>();
        args.add(orgId);
        args.add(projectId);
        args.add(ReviewPort.STATUS_SUCCESS);
        args.addAll(bidPackageIds);
        args.add(ReviewPort.MAIN_QUALIFICATION);
        args.add(ReviewPort.STATUS_UNKNOWN);
        args.add(ReviewPort.MAIN_SCORING);
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapSeed(rs), args.toArray());
    }

    @Transactional(readOnly = true)
    public List<ReviewItemSeed> listSeedTemplates(long reviewId) {
        List<ReviewItemSeed> rows = jdbcTemplate.query(
                "SELECT source_review_item_id, NULL AS source_review_id, requirement_item_id, "
                        + "bid_package_id, item_type, requirement_code, title, content, "
                        + "NULL AS ai_status, NULL AS ai_veto_risk, NULL AS ai_judgement, "
                        + "evidence_json, NULL AS ai_suggested_score, full_score, "
                        + "NULL AS ai_deduction_reason, sort_order "
                        + "FROM biz_expert_review_item WHERE review_id = ? "
                        + "ORDER BY bid_package_id ASC, sort_order ASC, id ASC",
                (rs, rowNum) -> mapSeed(rs),
                reviewId
        );
        List<ReviewItemSeed> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ReviewItemSeed row : rows) {
            String key = row.getSourceReviewItemId() + ":"
                    + row.getBidPackageId() + ":" + row.getRequirementItemId();
            if (seen.add(key)) {
                unique.add(row);
            }
        }
        return unique;
    }

    @Transactional
    public int insertReviewItemsForMembers(long reviewId,
                                           long projectId,
                                           List<MemberRow> members,
                                           List<ReviewItemSeed> seeds) {
        if (members == null || members.isEmpty() || seeds == null || seeds.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (MemberRow member : members) {
            if (member == null) {
                continue;
            }
            for (ReviewItemSeed seed : seeds) {
                if (seed == null) {
                    continue;
                }
                int row = jdbcTemplate.update(
                        "INSERT INTO biz_expert_review_item ("
                                + "review_id, member_id, source_review_item_id, project_id, "
                                + "bid_package_id, requirement_item_id, item_type, requirement_code, "
                                + "title, content, full_score, evidence_json, sort_order"
                                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (member_id, bid_package_id, requirement_item_id) DO NOTHING",
                        reviewId,
                        member.getId(),
                        seed.getSourceReviewItemId(),
                        projectId,
                        seed.getBidPackageId(),
                        seed.getRequirementItemId(),
                        normalizeItemType(seed),
                        normalizeRequirementCode(seed),
                        seed.getTitle(),
                        normalizeContent(seed),
                        seed.getFullScore(),
                        normalizeEvidenceJson(seed.getEvidenceJson()),
                        seed.getSortOrder()
                );
                affected += Math.max(row, 0);
            }
        }
        return affected;
    }

    @Transactional(readOnly = true)
    public List<CompanyRow> listCompanies(long reviewId) {
        return jdbcTemplate.query(
                "SELECT id, review_id, project_id, bid_package_id, supplier_name, "
                        + "company_status, display_mode, veto_reason_summary, veto_expert_names, "
                        + "veto_item_count, average_score, confirmed_by, confirmed_at, "
                        + "updated_at, created_at "
                        + "FROM biz_expert_review_company WHERE review_id = ? "
                        + "ORDER BY id ASC",
                (rs, rowNum) -> mapCompany(rs),
                reviewId
        );
    }

    @Transactional(readOnly = true)
    public CompanyRow findCompanyById(long reviewId, long companyId) {
        List<CompanyRow> rows = jdbcTemplate.query(
                "SELECT id, review_id, project_id, bid_package_id, supplier_name, "
                        + "company_status, display_mode, veto_reason_summary, veto_expert_names, "
                        + "veto_item_count, average_score, confirmed_by, confirmed_at, "
                        + "updated_at, created_at "
                        + "FROM biz_expert_review_company WHERE review_id = ? AND id = ?",
                (rs, rowNum) -> mapCompany(rs),
                reviewId,
                companyId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional(readOnly = true)
    public List<ItemRow> listItemsForMember(long reviewId, long memberId) {
        return jdbcTemplate.query(
                "SELECT i.id, i.review_id, i.member_id, i.source_review_item_id, "
                        + "i.project_id, i.bid_package_id, i.requirement_item_id, "
                        + "i.item_type, i.requirement_code, i.title, i.content, "
                        + "i.result, i.score_value, i.full_score, i.comment, i.is_veto, "
                        + "i.veto_reason, i.evidence_json, i.sort_order, i.submitted_at, "
                        + "i.updated_at, i.created_at, src.status AS ai_status, "
                        + "src.veto_risk AS ai_veto_risk, src.judgement AS ai_judgement, "
                        + "src.suggested_score AS ai_suggested_score, "
                        + "src.deduction_reason AS ai_deduction_reason "
                        + "FROM biz_expert_review_item i "
                        + "LEFT JOIN biz_review_item src ON src.id = i.source_review_item_id "
                        + "WHERE i.review_id = ? AND i.member_id = ? "
                        + "ORDER BY i.bid_package_id ASC, i.sort_order ASC, i.id ASC",
                (rs, rowNum) -> mapItem(rs),
                reviewId,
                memberId
        );
    }

    @Transactional(readOnly = true)
    public ItemRow findItemByIdForMember(long reviewId, long memberId, long itemId) {
        List<ItemRow> rows = jdbcTemplate.query(
                "SELECT i.id, i.review_id, i.member_id, i.source_review_item_id, "
                        + "i.project_id, i.bid_package_id, i.requirement_item_id, "
                        + "i.item_type, i.requirement_code, i.title, i.content, "
                        + "i.result, i.score_value, i.full_score, i.comment, i.is_veto, "
                        + "i.veto_reason, i.evidence_json, i.sort_order, i.submitted_at, "
                        + "i.updated_at, i.created_at, src.status AS ai_status, "
                        + "src.veto_risk AS ai_veto_risk, src.judgement AS ai_judgement, "
                        + "src.suggested_score AS ai_suggested_score, "
                        + "src.deduction_reason AS ai_deduction_reason "
                        + "FROM biz_expert_review_item i "
                        + "LEFT JOIN biz_review_item src ON src.id = i.source_review_item_id "
                        + "WHERE i.review_id = ? AND i.member_id = ? AND i.id = ?",
                (rs, rowNum) -> mapItem(rs),
                reviewId,
                memberId,
                itemId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public long createBuilding(long orgId, long projectId, long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO biz_expert_review (org_id, project_id, version, status, created_by) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            ps.setLong(2, projectId);
            ps.setInt(3, nextVersion(orgId, projectId));
            ps.setString(4, STATUS_BUILDING);
            ps.setLong(5, createdBy);
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
            throw new IllegalStateException("插入专家评审主表后未返回 id");
        }
        return key.longValue();
    }

    @Transactional
    public int updatePushInfo(long reviewId, Instant deadlineAt, String pushMessage, int expertCount) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review SET status = ?, deadline_at = ?, push_message = ?, "
                        + "expert_count = ?, is_locked = TRUE, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = ?",
                STATUS_RUNNING,
                toTimestamp(deadlineAt),
                pushMessage,
                Math.max(expertCount, 0),
                reviewId,
                STATUS_BUILDING
        );
    }

    @Transactional
    public int updateExpertCount(long reviewId, int expertCount) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review SET expert_count = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status <> ?",
                Math.max(expertCount, 0),
                reviewId,
                STATUS_OBSOLETE
        );
    }

    @Transactional
    public int markMemberDeleted(long reviewId, long memberId) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review_member SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE review_id = ? AND id = ? AND status = ?",
                MEMBER_STATUS_DELETED,
                reviewId,
                memberId,
                MEMBER_STATUS_ACTIVE
        );
    }

    @Transactional
    public int updateItemResult(long reviewId,
                                long memberId,
                                long itemId,
                                String result,
                                BigDecimal scoreValue,
                                String comment,
                                boolean veto,
                                String vetoReason) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review_item SET result = ?, score_value = ?, comment = ?, "
                        + "is_veto = ?, veto_reason = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE review_id = ? AND member_id = ? AND id = ? AND submitted_at IS NULL",
                result,
                scoreValue,
                comment,
                veto,
                vetoReason,
                reviewId,
                memberId,
                itemId
        );
    }

    @Transactional
    public int markItemsSubmitted(long reviewId, long memberId, Instant submittedAt) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review_item SET submitted_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE review_id = ? AND member_id = ? AND submitted_at IS NULL",
                toTimestamp(submittedAt),
                reviewId,
                memberId
        );
    }

    @Transactional
    public int markOpenItemsSubmittedForPendingMembers(long reviewId, Instant submittedAt) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review_item SET submitted_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE review_id = ? AND member_id IN ("
                        + "SELECT id FROM biz_expert_review_member "
                        + "WHERE review_id = ? AND status = ? AND submit_status = ?"
                        + ") AND submitted_at IS NULL",
                toTimestamp(submittedAt),
                reviewId,
                reviewId,
                MEMBER_STATUS_ACTIVE,
                SUBMIT_STATUS_NOT_SUBMITTED
        );
    }

    @Transactional
    public int markMemberSubmitted(long memberId, long userId, Instant submittedAt) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review_member SET status = ?, submit_status = ?, "
                        + "submitted_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND user_id = ? AND status = ? AND submit_status = ?",
                MEMBER_STATUS_READ_ONLY,
                SUBMIT_STATUS_SUBMITTED,
                toTimestamp(submittedAt),
                memberId,
                userId,
                MEMBER_STATUS_ACTIVE,
                SUBMIT_STATUS_NOT_SUBMITTED
        );
    }

    @Transactional
    public int markPendingMembersOverdueSubmitted(long reviewId, Instant submittedAt) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review_member SET status = ?, submit_status = ?, "
                        + "submitted_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE review_id = ? AND status = ? AND submit_status = ?",
                MEMBER_STATUS_READ_ONLY,
                SUBMIT_STATUS_OVERDUE_SUBMITTED,
                toTimestamp(submittedAt),
                reviewId,
                MEMBER_STATUS_ACTIVE,
                SUBMIT_STATUS_NOT_SUBMITTED
        );
    }

    @Transactional
    public int markSubmittedMembersDisabled(long reviewId) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review_member SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE review_id = ? AND status = ? AND submit_status IN (?, ?)",
                MEMBER_STATUS_DISABLED,
                reviewId,
                MEMBER_STATUS_READ_ONLY,
                SUBMIT_STATUS_SUBMITTED,
                SUBMIT_STATUS_OVERDUE_SUBMITTED
        );
    }

    @Transactional
    public int refreshSubmissionStats(long reviewId) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review r SET "
                        + "submitted_expert_count = stats.submitted_count, "
                        + "submitted_company_count = CASE WHEN stats.submitted_count > 0 THEN stats.company_count ELSE 0 END, "
                        + "has_any_complete_submission = stats.submitted_count > 0, "
                        + "status = CASE WHEN stats.total_count > 0 AND stats.submitted_count = stats.total_count "
                        + "THEN ? ELSE r.status END, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "FROM ("
                        + "SELECT "
                        + "(SELECT COUNT(1) FROM biz_expert_review_member "
                        + "WHERE review_id = ? AND status <> ?) AS total_count, "
                        + "(SELECT COUNT(1) FROM biz_expert_review_member "
                        + "WHERE review_id = ? AND status <> ? AND submit_status IN (?, ?)) AS submitted_count, "
                        + "(SELECT COUNT(1) FROM biz_expert_review_company WHERE review_id = ?) AS company_count"
                        + ") stats "
                        + "WHERE r.id = ? AND r.status IN (?, ?)",
                ReviewPort.STATUS_SUCCESS,
                reviewId,
                MEMBER_STATUS_DELETED,
                reviewId,
                MEMBER_STATUS_DELETED,
                SUBMIT_STATUS_SUBMITTED,
                SUBMIT_STATUS_OVERDUE_SUBMITTED,
                reviewId,
                reviewId,
                STATUS_RUNNING,
                ReviewPort.STATUS_SUCCESS
        );
    }

    @Transactional
    public int refreshCompanyVetoStats(long reviewId) {
        return jdbcTemplate.update(
                "WITH veto AS ("
                        + "SELECT i.review_id, i.bid_package_id, i.veto_reason, m.display_name, i.id "
                        + "FROM biz_expert_review_item i "
                        + "INNER JOIN biz_expert_review_member m ON m.id = i.member_id "
                        + "WHERE i.review_id = ? AND i.is_veto = TRUE AND i.submitted_at IS NOT NULL"
                        + "), veto_stats AS ("
                        + "SELECT c.id AS company_id, COUNT(v.id) AS veto_count, "
                        + "STRING_AGG(v.veto_reason, '；' ORDER BY v.id) AS reason_summary "
                        + "FROM biz_expert_review_company c "
                        + "LEFT JOIN veto v ON v.bid_package_id = c.bid_package_id "
                        + "WHERE c.review_id = ? GROUP BY c.id"
                        + "), veto_experts AS ("
                        + "SELECT company_id, STRING_AGG(display_name, '、' ORDER BY display_name) AS expert_names "
                        + "FROM ("
                        + "SELECT DISTINCT c.id AS company_id, v.display_name "
                        + "FROM biz_expert_review_company c "
                        + "INNER JOIN veto v ON v.bid_package_id = c.bid_package_id "
                        + "WHERE c.review_id = ?"
                        + ") names GROUP BY company_id"
                        + ") "
                        + "UPDATE biz_expert_review_company c SET "
                        + "veto_item_count = s.veto_count, "
                        + "veto_reason_summary = CASE WHEN s.veto_count > 0 THEN s.reason_summary ELSE NULL END, "
                        + "veto_expert_names = CASE WHEN s.veto_count > 0 THEN e.expert_names ELSE NULL END, "
                        + "company_status = CASE "
                        + "WHEN c.company_status = ? THEN ? "
                        + "WHEN c.display_mode = ? THEN ? "
                        + "WHEN s.veto_count > 0 THEN ? ELSE ? END, "
                        + "display_mode = CASE "
                        + "WHEN c.company_status = ? THEN c.display_mode "
                        + "WHEN c.display_mode = ? THEN ? "
                        + "WHEN s.veto_count > 0 THEN ? ELSE ? END, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "FROM veto_stats s "
                        + "LEFT JOIN veto_experts e ON e.company_id = s.company_id "
                        + "WHERE c.id = s.company_id AND c.review_id = ?",
                reviewId,
                reviewId,
                reviewId,
                COMPANY_STATUS_VETOED,
                COMPANY_STATUS_VETOED,
                DISPLAY_MODE_CONTINUE_DISPLAY,
                COMPANY_STATUS_NORMAL,
                COMPANY_STATUS_PENDING_VETO,
                COMPANY_STATUS_NORMAL,
                COMPANY_STATUS_VETOED,
                DISPLAY_MODE_CONTINUE_DISPLAY,
                DISPLAY_MODE_CONTINUE_DISPLAY,
                DISPLAY_MODE_PENDING_CONFIRMATION,
                DISPLAY_MODE_NORMAL,
                reviewId
        );
    }

    @Transactional
    public int updateCompanyVetoDecision(long reviewId,
                                         long companyId,
                                         String companyStatus,
                                         String displayMode,
                                         long confirmedBy,
                                         Instant confirmedAt) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review_company SET company_status = ?, display_mode = ?, "
                        + "confirmed_by = ?, confirmed_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE review_id = ? AND id = ? AND company_status = ? AND display_mode = ?",
                companyStatus,
                displayMode,
                confirmedBy,
                toTimestamp(confirmedAt),
                reviewId,
                companyId,
                COMPANY_STATUS_PENDING_VETO,
                DISPLAY_MODE_PENDING_CONFIRMATION
        );
    }

    @Transactional(readOnly = true)
    public int countPendingVetoCompanies(long reviewId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_expert_review_company "
                        + "WHERE review_id = ? AND company_status = ? AND display_mode = ?",
                Integer.class,
                reviewId,
                COMPANY_STATUS_PENDING_VETO,
                DISPLAY_MODE_PENDING_CONFIRMATION
        );
        return count == null ? 0 : count;
    }

    @Transactional
    public long insertMember(long reviewId,
                             long projectId,
                             long userId,
                             String username,
                             String displayName,
                             String passwordHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO biz_expert_review_member (review_id, project_id, user_id, username, "
                            + "display_name, password_hash, status, submit_status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, reviewId);
            ps.setLong(2, projectId);
            ps.setLong(3, userId);
            ps.setString(4, username);
            ps.setString(5, displayName);
            ps.setString(6, passwordHash);
            ps.setString(7, "ACTIVE");
            ps.setString(8, "NOT_SUBMITTED");
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
            throw new IllegalStateException("插入专家成员后未返回 id");
        }
        return key.longValue();
    }

    @Transactional
    public int insertCompanies(long reviewId,
                               long projectId,
                               List<PackageLookupPort.PackageRef> packages) {
        if (packages == null || packages.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (int i = 0; i < packages.size(); i++) {
            PackageLookupPort.PackageRef pkg = packages.get(i);
            if (pkg == null) {
                continue;
            }
            int row = jdbcTemplate.update(
                    "INSERT INTO biz_expert_review_company (review_id, project_id, bid_package_id, supplier_name) "
                            + "VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (review_id, bid_package_id) DO NOTHING",
                    reviewId,
                    projectId,
                    pkg.getId(),
                    pkg.getName()
            );
            affected += Math.max(row, 0);
        }
        return affected;
    }

    @Transactional
    public int markObsoleteByProject(long orgId, long projectId) {
        return jdbcTemplate.update(
                "UPDATE biz_expert_review SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE org_id = ? AND project_id = ? AND status NOT IN (?, ?)",
                STATUS_OBSOLETE,
                orgId,
                projectId,
                STATUS_OBSOLETE,
                STATUS_DELETED
        );
    }

    @Transactional
    public int deleteMembersByReviewId(long reviewId) {
        return jdbcTemplate.update(
                "DELETE FROM biz_expert_review_member WHERE review_id = ?",
                reviewId
        );
    }

    @Transactional
    public int deleteCompaniesByReviewId(long reviewId) {
        return jdbcTemplate.update(
                "DELETE FROM biz_expert_review_company WHERE review_id = ?",
                reviewId
        );
    }

    @Transactional
    public int deleteItemsByReviewId(long reviewId) {
        return jdbcTemplate.update(
                "DELETE FROM biz_expert_review_item WHERE review_id = ?",
                reviewId
        );
    }

    private int nextVersion(long orgId, long projectId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) + 1 FROM biz_expert_review "
                        + "WHERE org_id = ? AND project_id = ?",
                Integer.class,
                orgId,
                projectId
        );
        return version == null ? 1 : version;
    }

    private ReviewRow mapReview(java.sql.ResultSet rs) throws java.sql.SQLException {
        ReviewRow row = new ReviewRow();
        row.setId(rs.getLong("id"));
        row.setOrgId(rs.getLong("org_id"));
        row.setProjectId(rs.getLong("project_id"));
        row.setVersion(rs.getInt("version"));
        row.setStatus(rs.getString("status"));
        row.setDeadlineAt(toInstant(rs.getTimestamp("deadline_at")));
        row.setPushMessage(rs.getString("push_message"));
        row.setExpertCount(rs.getInt("expert_count"));
        row.setSubmittedExpertCount(rs.getInt("submitted_expert_count"));
        row.setSubmittedCompanyCount(rs.getInt("submitted_company_count"));
        row.setHasAnyCompleteSubmission(rs.getBoolean("has_any_complete_submission"));
        row.setLocked(rs.getBoolean("is_locked"));
        row.setCreatedBy(rs.getLong("created_by"));
        row.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        row.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        return row;
    }

    private MemberRow mapMember(java.sql.ResultSet rs) throws java.sql.SQLException {
        MemberRow row = new MemberRow();
        row.setId(rs.getLong("id"));
        row.setReviewId(rs.getLong("review_id"));
        row.setProjectId(rs.getLong("project_id"));
        row.setUserId(rs.getLong("user_id"));
        row.setUsername(rs.getString("username"));
        row.setDisplayName(rs.getString("display_name"));
        row.setPasswordHash(rs.getString("password_hash"));
        row.setStatus(rs.getString("status"));
        row.setSubmitStatus(rs.getString("submit_status"));
        row.setSubmittedAt(toInstant(rs.getTimestamp("submitted_at")));
        row.setFirstPasswordShown(rs.getBoolean("first_password_shown"));
        row.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        row.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        return row;
    }

    private ReviewItemSeed mapSeed(ResultSet rs) throws SQLException {
        ReviewItemSeed row = new ReviewItemSeed();
        row.setSourceReviewItemId(rs.getLong("source_review_item_id"));
        long sourceReviewId = rs.getLong("source_review_id");
        row.setSourceReviewId(rs.wasNull() ? null : sourceReviewId);
        row.setRequirementItemId(rs.getLong("requirement_item_id"));
        row.setBidPackageId(rs.getLong("bid_package_id"));
        row.setItemType(rs.getString("item_type"));
        row.setRequirementCode(rs.getString("requirement_code"));
        row.setTitle(rs.getString("title"));
        row.setContent(rs.getString("content"));
        row.setAiStatus(rs.getString("ai_status"));
        boolean aiVetoRisk = rs.getBoolean("ai_veto_risk");
        row.setAiVetoRisk(rs.wasNull() ? null : aiVetoRisk);
        row.setAiJudgement(rs.getString("ai_judgement"));
        row.setEvidenceJson(rs.getString("evidence_json"));
        row.setAiSuggestedScore(rs.getBigDecimal("ai_suggested_score"));
        row.setFullScore(rs.getBigDecimal("full_score"));
        row.setAiDeductionReason(rs.getString("ai_deduction_reason"));
        row.setSortOrder(rs.getInt("sort_order"));
        return row;
    }

    private CompanyRow mapCompany(ResultSet rs) throws SQLException {
        CompanyRow row = new CompanyRow();
        row.setId(rs.getLong("id"));
        row.setReviewId(rs.getLong("review_id"));
        row.setProjectId(rs.getLong("project_id"));
        row.setBidPackageId(rs.getLong("bid_package_id"));
        row.setSupplierName(rs.getString("supplier_name"));
        row.setCompanyStatus(rs.getString("company_status"));
        row.setDisplayMode(rs.getString("display_mode"));
        row.setVetoReasonSummary(rs.getString("veto_reason_summary"));
        row.setVetoExpertNames(rs.getString("veto_expert_names"));
        row.setVetoItemCount(rs.getInt("veto_item_count"));
        row.setAverageScore(rs.getBigDecimal("average_score"));
        long confirmedBy = rs.getLong("confirmed_by");
        row.setConfirmedBy(rs.wasNull() ? null : confirmedBy);
        row.setConfirmedAt(toInstant(rs.getTimestamp("confirmed_at")));
        row.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        row.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        return row;
    }

    private ItemRow mapItem(ResultSet rs) throws SQLException {
        ItemRow row = new ItemRow();
        row.setId(rs.getLong("id"));
        row.setReviewId(rs.getLong("review_id"));
        row.setMemberId(rs.getLong("member_id"));
        row.setSourceReviewItemId(rs.getLong("source_review_item_id"));
        row.setProjectId(rs.getLong("project_id"));
        row.setBidPackageId(rs.getLong("bid_package_id"));
        row.setRequirementItemId(rs.getLong("requirement_item_id"));
        row.setItemType(rs.getString("item_type"));
        row.setRequirementCode(rs.getString("requirement_code"));
        row.setTitle(rs.getString("title"));
        row.setContent(rs.getString("content"));
        row.setResult(rs.getString("result"));
        row.setScoreValue(rs.getBigDecimal("score_value"));
        row.setFullScore(rs.getBigDecimal("full_score"));
        row.setComment(rs.getString("comment"));
        row.setVeto(rs.getBoolean("is_veto"));
        row.setVetoReason(rs.getString("veto_reason"));
        row.setEvidenceJson(rs.getString("evidence_json"));
        row.setSortOrder(rs.getInt("sort_order"));
        row.setSubmittedAt(toInstant(rs.getTimestamp("submitted_at")));
        row.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        row.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        row.setAiStatus(rs.getString("ai_status"));
        boolean aiVetoRisk = rs.getBoolean("ai_veto_risk");
        row.setAiVetoRisk(rs.wasNull() ? null : aiVetoRisk);
        row.setAiJudgement(rs.getString("ai_judgement"));
        row.setAiSuggestedScore(rs.getBigDecimal("ai_suggested_score"));
        row.setAiDeductionReason(rs.getString("ai_deduction_reason"));
        return row;
    }

    private String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('?');
        }
        return builder.toString();
    }

    private String normalizeItemType(ReviewItemSeed seed) {
        if (seed != null && seed.getItemType() != null && !seed.getItemType().isBlank()) {
            return seed.getItemType().trim();
        }
        return seed != null && seed.getFullScore() != null
                ? ReviewPort.MAIN_SCORING
                : ReviewPort.MAIN_QUALIFICATION;
    }

    private String normalizeRequirementCode(ReviewItemSeed seed) {
        if (seed != null && seed.getRequirementCode() != null
                && !seed.getRequirementCode().isBlank()) {
            return seed.getRequirementCode().trim();
        }
        String prefix = ReviewPort.MAIN_SCORING.equals(normalizeItemType(seed)) ? "S" : "Q";
        long requirementItemId = seed == null ? 0L : seed.getRequirementItemId();
        return prefix + requirementItemId;
    }

    private String normalizeContent(ReviewItemSeed seed) {
        if (seed != null && seed.getContent() != null && !seed.getContent().isBlank()) {
            return seed.getContent();
        }
        if (seed != null && seed.getTitle() != null && !seed.getTitle().isBlank()) {
            return seed.getTitle();
        }
        return "";
    }

    private String normalizeEvidenceJson(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return "[]";
        }
        return evidenceJson;
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static class ReviewRow {
        private long id;
        private long orgId;
        private long projectId;
        private int version;
        private String status;
        private Instant deadlineAt;
        private String pushMessage;
        private int expertCount;
        private int submittedExpertCount;
        private int submittedCompanyCount;
        private boolean hasAnyCompleteSubmission;
        private boolean locked;
        private long createdBy;
        private Instant createdAt;
        private Instant updatedAt;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getOrgId() {
            return orgId;
        }

        public void setOrgId(long orgId) {
            this.orgId = orgId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Instant getDeadlineAt() {
            return deadlineAt;
        }

        public void setDeadlineAt(Instant deadlineAt) {
            this.deadlineAt = deadlineAt;
        }

        public String getPushMessage() {
            return pushMessage;
        }

        public void setPushMessage(String pushMessage) {
            this.pushMessage = pushMessage;
        }

        public int getExpertCount() {
            return expertCount;
        }

        public void setExpertCount(int expertCount) {
            this.expertCount = expertCount;
        }

        public int getSubmittedExpertCount() {
            return submittedExpertCount;
        }

        public void setSubmittedExpertCount(int submittedExpertCount) {
            this.submittedExpertCount = submittedExpertCount;
        }

        public int getSubmittedCompanyCount() {
            return submittedCompanyCount;
        }

        public void setSubmittedCompanyCount(int submittedCompanyCount) {
            this.submittedCompanyCount = submittedCompanyCount;
        }

        public boolean isHasAnyCompleteSubmission() {
            return hasAnyCompleteSubmission;
        }

        public void setHasAnyCompleteSubmission(boolean hasAnyCompleteSubmission) {
            this.hasAnyCompleteSubmission = hasAnyCompleteSubmission;
        }

        public boolean isLocked() {
            return locked;
        }

        public void setLocked(boolean locked) {
            this.locked = locked;
        }

        public long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(long createdBy) {
            this.createdBy = createdBy;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    public static class MemberRow {
        private long id;
        private long reviewId;
        private long projectId;
        private long userId;
        private String username;
        private String displayName;
        private String passwordHash;
        private String status;
        private String submitStatus;
        private Instant submittedAt;
        private boolean firstPasswordShown;
        private Instant createdAt;
        private Instant updatedAt;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getSubmitStatus() {
            return submitStatus;
        }

        public void setSubmitStatus(String submitStatus) {
            this.submitStatus = submitStatus;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public void setSubmittedAt(Instant submittedAt) {
            this.submittedAt = submittedAt;
        }

        public boolean isFirstPasswordShown() {
            return firstPasswordShown;
        }

        public void setFirstPasswordShown(boolean firstPasswordShown) {
            this.firstPasswordShown = firstPasswordShown;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    public static class CompanyRow {
        private long id;
        private long reviewId;
        private long projectId;
        private long bidPackageId;
        private String supplierName;
        private String companyStatus;
        private String displayMode;
        private String vetoReasonSummary;
        private String vetoExpertNames;
        private int vetoItemCount;
        private BigDecimal averageScore;
        private Long confirmedBy;
        private Instant confirmedAt;
        private Instant updatedAt;
        private Instant createdAt;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public String getSupplierName() {
            return supplierName;
        }

        public void setSupplierName(String supplierName) {
            this.supplierName = supplierName;
        }

        public String getCompanyStatus() {
            return companyStatus;
        }

        public void setCompanyStatus(String companyStatus) {
            this.companyStatus = companyStatus;
        }

        public String getDisplayMode() {
            return displayMode;
        }

        public void setDisplayMode(String displayMode) {
            this.displayMode = displayMode;
        }

        public String getVetoReasonSummary() {
            return vetoReasonSummary;
        }

        public void setVetoReasonSummary(String vetoReasonSummary) {
            this.vetoReasonSummary = vetoReasonSummary;
        }

        public String getVetoExpertNames() {
            return vetoExpertNames;
        }

        public void setVetoExpertNames(String vetoExpertNames) {
            this.vetoExpertNames = vetoExpertNames;
        }

        public int getVetoItemCount() {
            return vetoItemCount;
        }

        public void setVetoItemCount(int vetoItemCount) {
            this.vetoItemCount = vetoItemCount;
        }

        public BigDecimal getAverageScore() {
            return averageScore;
        }

        public void setAverageScore(BigDecimal averageScore) {
            this.averageScore = averageScore;
        }

        public Long getConfirmedBy() {
            return confirmedBy;
        }

        public void setConfirmedBy(Long confirmedBy) {
            this.confirmedBy = confirmedBy;
        }

        public Instant getConfirmedAt() {
            return confirmedAt;
        }

        public void setConfirmedAt(Instant confirmedAt) {
            this.confirmedAt = confirmedAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }
    }

    public static class ReviewItemSeed {
        private long sourceReviewItemId;
        private Long sourceReviewId;
        private long requirementItemId;
        private long bidPackageId;
        private String itemType;
        private String requirementCode;
        private String title;
        private String content;
        private String aiStatus;
        private Boolean aiVetoRisk;
        private String aiJudgement;
        private String evidenceJson;
        private BigDecimal aiSuggestedScore;
        private BigDecimal fullScore;
        private String aiDeductionReason;
        private int sortOrder;

        public long getSourceReviewItemId() {
            return sourceReviewItemId;
        }

        public void setSourceReviewItemId(long sourceReviewItemId) {
            this.sourceReviewItemId = sourceReviewItemId;
        }

        public Long getSourceReviewId() {
            return sourceReviewId;
        }

        public void setSourceReviewId(Long sourceReviewId) {
            this.sourceReviewId = sourceReviewId;
        }

        public long getRequirementItemId() {
            return requirementItemId;
        }

        public void setRequirementItemId(long requirementItemId) {
            this.requirementItemId = requirementItemId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public String getItemType() {
            return itemType;
        }

        public void setItemType(String itemType) {
            this.itemType = itemType;
        }

        public String getRequirementCode() {
            return requirementCode;
        }

        public void setRequirementCode(String requirementCode) {
            this.requirementCode = requirementCode;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getAiStatus() {
            return aiStatus;
        }

        public void setAiStatus(String aiStatus) {
            this.aiStatus = aiStatus;
        }

        public Boolean getAiVetoRisk() {
            return aiVetoRisk;
        }

        public void setAiVetoRisk(Boolean aiVetoRisk) {
            this.aiVetoRisk = aiVetoRisk;
        }

        public String getAiJudgement() {
            return aiJudgement;
        }

        public void setAiJudgement(String aiJudgement) {
            this.aiJudgement = aiJudgement;
        }

        public String getEvidenceJson() {
            return evidenceJson;
        }

        public void setEvidenceJson(String evidenceJson) {
            this.evidenceJson = evidenceJson;
        }

        public BigDecimal getAiSuggestedScore() {
            return aiSuggestedScore;
        }

        public void setAiSuggestedScore(BigDecimal aiSuggestedScore) {
            this.aiSuggestedScore = aiSuggestedScore;
        }

        public BigDecimal getFullScore() {
            return fullScore;
        }

        public void setFullScore(BigDecimal fullScore) {
            this.fullScore = fullScore;
        }

        public String getAiDeductionReason() {
            return aiDeductionReason;
        }

        public void setAiDeductionReason(String aiDeductionReason) {
            this.aiDeductionReason = aiDeductionReason;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }
    }

    public static class ItemRow {
        private long id;
        private long reviewId;
        private long memberId;
        private long sourceReviewItemId;
        private long projectId;
        private long bidPackageId;
        private long requirementItemId;
        private String itemType;
        private String requirementCode;
        private String title;
        private String content;
        private String result;
        private BigDecimal scoreValue;
        private BigDecimal fullScore;
        private String comment;
        private boolean veto;
        private String vetoReason;
        private String evidenceJson;
        private int sortOrder;
        private Instant submittedAt;
        private Instant updatedAt;
        private Instant createdAt;
        private String aiStatus;
        private Boolean aiVetoRisk;
        private String aiJudgement;
        private BigDecimal aiSuggestedScore;
        private String aiDeductionReason;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getMemberId() {
            return memberId;
        }

        public void setMemberId(long memberId) {
            this.memberId = memberId;
        }

        public long getSourceReviewItemId() {
            return sourceReviewItemId;
        }

        public void setSourceReviewItemId(long sourceReviewItemId) {
            this.sourceReviewItemId = sourceReviewItemId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public long getRequirementItemId() {
            return requirementItemId;
        }

        public void setRequirementItemId(long requirementItemId) {
            this.requirementItemId = requirementItemId;
        }

        public String getItemType() {
            return itemType;
        }

        public void setItemType(String itemType) {
            this.itemType = itemType;
        }

        public String getRequirementCode() {
            return requirementCode;
        }

        public void setRequirementCode(String requirementCode) {
            this.requirementCode = requirementCode;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public BigDecimal getScoreValue() {
            return scoreValue;
        }

        public void setScoreValue(BigDecimal scoreValue) {
            this.scoreValue = scoreValue;
        }

        public BigDecimal getFullScore() {
            return fullScore;
        }

        public void setFullScore(BigDecimal fullScore) {
            this.fullScore = fullScore;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        public boolean isVeto() {
            return veto;
        }

        public void setVeto(boolean veto) {
            this.veto = veto;
        }

        public String getVetoReason() {
            return vetoReason;
        }

        public void setVetoReason(String vetoReason) {
            this.vetoReason = vetoReason;
        }

        public String getEvidenceJson() {
            return evidenceJson;
        }

        public void setEvidenceJson(String evidenceJson) {
            this.evidenceJson = evidenceJson;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public void setSubmittedAt(Instant submittedAt) {
            this.submittedAt = submittedAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public String getAiStatus() {
            return aiStatus;
        }

        public void setAiStatus(String aiStatus) {
            this.aiStatus = aiStatus;
        }

        public Boolean getAiVetoRisk() {
            return aiVetoRisk;
        }

        public void setAiVetoRisk(Boolean aiVetoRisk) {
            this.aiVetoRisk = aiVetoRisk;
        }

        public String getAiJudgement() {
            return aiJudgement;
        }

        public void setAiJudgement(String aiJudgement) {
            this.aiJudgement = aiJudgement;
        }

        public BigDecimal getAiSuggestedScore() {
            return aiSuggestedScore;
        }

        public void setAiSuggestedScore(BigDecimal aiSuggestedScore) {
            this.aiSuggestedScore = aiSuggestedScore;
        }

        public String getAiDeductionReason() {
            return aiDeductionReason;
        }

        public void setAiDeductionReason(String aiDeductionReason) {
            this.aiDeductionReason = aiDeductionReason;
        }
    }
}
