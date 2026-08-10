package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.ReviewPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 审查结果 JDBC 实现。
 */
@Repository
public class JdbcReviewStore implements ReviewPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcReviewStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public int nextVersion(long orgId, long projectId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM biz_review WHERE org_id = ? AND project_id = ?",
                Integer.class,
                orgId,
                projectId
        );
        if (max == null) {
            return 1;
        }
        return max + 1;
    }

    @Override
    @Transactional
    public long createBuilding(long orgId,
                               long projectId,
                               long checklistId,
                               int version,
                               String packageIdsJson,
                               String promptVersion,
                               int recallTopK,
                               int rerankTopN,
                               long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO biz_review ("
                            + "org_id, project_id, checklist_id, version, status, package_ids, "
                            + "need_human_review, prompt_version, recall_top_k, rerank_top_n, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            ps.setLong(2, projectId);
            ps.setLong(3, checklistId);
            ps.setInt(4, version);
            ps.setString(5, STATUS_BUILDING);
            ps.setString(6, packageIdsJson);
            ps.setString(7, promptVersion);
            ps.setInt(8, recallTopK);
            ps.setInt(9, rerankTopN);
            ps.setLong(10, createdBy);
            return ps;
        }, keyHolder);
        Number key = null;
        if (keyHolder.getKeys() != null && keyHolder.getKeys().get("id") != null) {
            key = (Number) keyHolder.getKeys().get("id");
        }
        if (key == null) {
            throw new IllegalStateException("插入审查后未返回 id");
        }
        return key.longValue();
    }

    @Override
    @Transactional
    public void insertItems(long reviewId, List<ItemDraft> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            ItemDraft item = items.get(i);
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO biz_review_item ("
                                + "review_id, requirement_item_id, dimension_code, requirement_text, must_flag, "
                                + "verdict, reason, bid_excerpt, page_no, chunk_id, document_id, file_name, "
                                + "package_id, sort_order, llm_raw_json, requirement_code, title, main_type, "
                                + "sub_type, review_type, status, veto_risk, judgement, bid_evidence_json, note, "
                                + "suggested_score, full_score, deduction_reason) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                );
                ps.setLong(1, reviewId);
                ps.setLong(2, item.getRequirementItemId());
                ps.setString(3, item.getDimensionCode());
                ps.setString(4, item.getRequirementText());
                ps.setBoolean(5, item.isMust());
                ps.setString(6, normalizeVerdict(item));
                ps.setString(7, item.getReason());
                ps.setString(8, item.getBidExcerpt());
                if (item.getPageNo() == null) {
                    ps.setNull(9, Types.INTEGER);
                } else {
                    ps.setInt(9, item.getPageNo());
                }
                if (item.getChunkId() == null) {
                    ps.setNull(10, Types.BIGINT);
                } else {
                    ps.setLong(10, item.getChunkId());
                }
                if (item.getDocumentId() == null) {
                    ps.setNull(11, Types.BIGINT);
                } else {
                    ps.setLong(11, item.getDocumentId());
                }
                ps.setString(12, item.getFileName());
                if (item.getPackageId() == null) {
                    ps.setNull(13, Types.BIGINT);
                } else {
                    ps.setLong(13, item.getPackageId());
                }
                ps.setInt(14, item.getSortOrder());
                ps.setString(15, item.getLlmRawJson());
                ps.setString(16, item.getRequirementCode());
                ps.setString(17, item.getTitle());
                ps.setString(18, item.getMainType());
                ps.setString(19, item.getSubType());
                ps.setString(20, item.getReviewType());
                ps.setString(21, item.getStatus());
                if (item.getVetoRisk() == null) {
                    ps.setNull(22, Types.BOOLEAN);
                } else {
                    ps.setBoolean(22, item.getVetoRisk());
                }
                ps.setString(23, item.getJudgement());
                ps.setString(24, item.getBidEvidenceJson());
                ps.setString(25, item.getNote());
                if (item.getSuggestedScore() == null) {
                    ps.setNull(26, Types.NUMERIC);
                } else {
                    ps.setBigDecimal(26, item.getSuggestedScore());
                }
                if (item.getFullScore() == null) {
                    ps.setNull(27, Types.NUMERIC);
                } else {
                    ps.setBigDecimal(27, item.getFullScore());
                }
                ps.setString(28, item.getDeductionReason());
                return ps;
            });
        }
    }

    @Override
    @Transactional
    public void markSuccess(long reviewId, String overallVerdict) {
        jdbcTemplate.update(
                "UPDATE biz_review SET status = ?, overall_verdict = ?, error_message = NULL, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                STATUS_SUCCESS,
                overallVerdict,
                reviewId
        );
    }

    @Override
    @Transactional
    public void markFailed(long reviewId, String errorMessage) {
        String msg = errorMessage;
        if (msg != null && msg.length() > 2000) {
            msg = msg.substring(0, 2000);
        }
        jdbcTemplate.update(
                "UPDATE biz_review SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                STATUS_FAILED,
                msg,
                reviewId
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewSummary> listByProject(long orgId, long projectId) {
        return jdbcTemplate.query(
                "SELECT id, org_id, project_id, checklist_id, version, status, package_ids, "
                        + "overall_verdict, need_human_review, prompt_version, recall_top_k, rerank_top_n, "
                        + "error_message, created_by, created_at, updated_at "
                        + "FROM biz_review WHERE org_id = ? AND project_id = ? ORDER BY version DESC",
                (rs, rowNum) -> mapSummary(rs),
                orgId,
                projectId
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewDetail findDetail(long orgId, long projectId, long reviewId) {
        List<ReviewDetail> heads = jdbcTemplate.query(
                "SELECT id, org_id, project_id, checklist_id, version, status, package_ids, "
                        + "overall_verdict, need_human_review, prompt_version, recall_top_k, rerank_top_n, "
                        + "error_message, created_by, created_at, updated_at "
                        + "FROM biz_review WHERE id = ? AND org_id = ? AND project_id = ?",
                (rs, rowNum) -> {
                    ReviewDetail d = new ReviewDetail();
                    copySummary(mapSummary(rs), d);
                    return d;
                },
                reviewId,
                orgId,
                projectId
        );
        if (heads.isEmpty()) {
            return null;
        }
        ReviewDetail detail = heads.get(0);
        List<ItemView> items = jdbcTemplate.query(
                "SELECT id, review_id, requirement_item_id, dimension_code, requirement_text, must_flag, "
                        + "verdict, reason, bid_excerpt, page_no, chunk_id, document_id, file_name, "
                        + "package_id, sort_order, llm_raw_json, requirement_code, title, main_type, "
                        + "sub_type, review_type, status, veto_risk, judgement, bid_evidence_json, note, "
                        + "suggested_score, full_score, deduction_reason "
                        + "FROM biz_review_item WHERE review_id = ? ORDER BY sort_order ASC, id ASC",
                (rs, rowNum) -> mapItem(rs),
                reviewId
        );
        detail.setItems(items);
        return detail;
    }

    private ReviewSummary mapSummary(ResultSet rs) throws SQLException {
        ReviewSummary s = new ReviewSummary();
        s.setId(rs.getLong("id"));
        s.setOrgId(rs.getLong("org_id"));
        s.setProjectId(rs.getLong("project_id"));
        s.setChecklistId(rs.getLong("checklist_id"));
        s.setVersion(rs.getInt("version"));
        s.setStatus(rs.getString("status"));
        s.setPackageIdsJson(rs.getString("package_ids"));
        s.setOverallVerdict(rs.getString("overall_verdict"));
        s.setNeedHumanReview(rs.getBoolean("need_human_review"));
        s.setPromptVersion(rs.getString("prompt_version"));
        s.setRecallTopK(rs.getInt("recall_top_k"));
        s.setRerankTopN(rs.getInt("rerank_top_n"));
        s.setErrorMessage(rs.getString("error_message"));
        s.setCreatedBy(rs.getLong("created_by"));
        s.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        s.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        return s;
    }

    private void copySummary(ReviewSummary from, ReviewDetail to) {
        to.setId(from.getId());
        to.setOrgId(from.getOrgId());
        to.setProjectId(from.getProjectId());
        to.setChecklistId(from.getChecklistId());
        to.setVersion(from.getVersion());
        to.setStatus(from.getStatus());
        to.setPackageIdsJson(from.getPackageIdsJson());
        to.setOverallVerdict(from.getOverallVerdict());
        to.setNeedHumanReview(from.isNeedHumanReview());
        to.setPromptVersion(from.getPromptVersion());
        to.setRecallTopK(from.getRecallTopK());
        to.setRerankTopN(from.getRerankTopN());
        to.setErrorMessage(from.getErrorMessage());
        to.setCreatedBy(from.getCreatedBy());
        to.setCreatedAt(from.getCreatedAt());
        to.setUpdatedAt(from.getUpdatedAt());
    }

    private ItemView mapItem(ResultSet rs) throws SQLException {
        ItemView item = new ItemView();
        item.setId(rs.getLong("id"));
        item.setReviewId(rs.getLong("review_id"));
        item.setRequirementItemId(rs.getLong("requirement_item_id"));
        item.setDimensionCode(rs.getString("dimension_code"));
        item.setRequirementText(rs.getString("requirement_text"));
        item.setMust(rs.getBoolean("must_flag"));
        item.setVerdict(rs.getString("verdict"));
        item.setReason(rs.getString("reason"));
        item.setBidExcerpt(rs.getString("bid_excerpt"));
        int page = rs.getInt("page_no");
        if (rs.wasNull()) {
            item.setPageNo(null);
        } else {
            item.setPageNo(page);
        }
        long chunkId = rs.getLong("chunk_id");
        if (rs.wasNull()) {
            item.setChunkId(null);
        } else {
            item.setChunkId(chunkId);
        }
        long documentId = rs.getLong("document_id");
        if (rs.wasNull()) {
            item.setDocumentId(null);
        } else {
            item.setDocumentId(documentId);
        }
        item.setFileName(rs.getString("file_name"));
        long packageId = rs.getLong("package_id");
        if (rs.wasNull()) {
            item.setPackageId(null);
        } else {
            item.setPackageId(packageId);
        }
        item.setSortOrder(rs.getInt("sort_order"));
        item.setLlmRawJson(rs.getString("llm_raw_json"));
        item.setRequirementCode(rs.getString("requirement_code"));
        item.setTitle(rs.getString("title"));
        item.setMainType(rs.getString("main_type"));
        item.setSubType(rs.getString("sub_type"));
        item.setReviewType(rs.getString("review_type"));
        item.setStatus(rs.getString("status"));
        boolean veto = rs.getBoolean("veto_risk");
        if (rs.wasNull()) {
            item.setVetoRisk(null);
        } else {
            item.setVetoRisk(veto);
        }
        item.setJudgement(rs.getString("judgement"));
        item.setBidEvidenceJson(rs.getString("bid_evidence_json"));
        item.setNote(rs.getString("note"));
        java.math.BigDecimal suggested = rs.getBigDecimal("suggested_score");
        if (rs.wasNull()) {
            item.setSuggestedScore(null);
        } else {
            item.setSuggestedScore(suggested);
        }
        java.math.BigDecimal full = rs.getBigDecimal("full_score");
        if (rs.wasNull()) {
            item.setFullScore(null);
        } else {
            item.setFullScore(full);
        }
        item.setDeductionReason(rs.getString("deduction_reason"));
        return item;
    }

    private Instant toInstant(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        return ts.toInstant();
    }

    private String normalizeVerdict(ItemDraft item) {
        if (item == null) {
            return ReviewPort.VERDICT_UNCLEAR;
        }
        String verdict = item.getVerdict();
        if (verdict != null && !verdict.isBlank()) {
            return verdict;
        }
        if (ReviewPort.MAIN_SCORING.equals(item.getMainType())) {
            return verdictForScoring(item.getSuggestedScore(), item.getFullScore());
        }
        return mapStatusToVerdict(item.getStatus());
    }

    private String verdictForScoring(BigDecimal suggested, BigDecimal full) {
        if (suggested == null || full == null) {
            return ReviewPort.VERDICT_UNCLEAR;
        }
        if (suggested.compareTo(BigDecimal.ZERO) <= 0) {
            return ReviewPort.VERDICT_FAIL;
        }
        if (suggested.compareTo(full) >= 0) {
            return ReviewPort.VERDICT_PASS;
        }
        return ReviewPort.VERDICT_UNCLEAR;
    }

    private String mapStatusToVerdict(String status) {
        if (ReviewPort.STATUS_MATCH.equals(status)) {
            return ReviewPort.VERDICT_PASS;
        }
        if (ReviewPort.STATUS_MISMATCH.equals(status)) {
            return ReviewPort.VERDICT_FAIL;
        }
        return ReviewPort.VERDICT_UNCLEAR;
    }
}
