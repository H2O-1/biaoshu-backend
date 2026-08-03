package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.RequirementChecklistPort;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 要求清单 JDBC 实现。
 */
@Repository
public class JdbcRequirementChecklistStore implements RequirementChecklistPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRequirementChecklistStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public int nextVersion(long orgId, long projectId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM biz_requirement_checklist "
                        + "WHERE org_id = ? AND project_id = ?",
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
                               int version,
                               String dimensionCodesJson,
                               String tenderDocumentIdsJson,
                               String promptVersion,
                               int recallTopK,
                               int rerankTopN,
                               long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO biz_requirement_checklist ("
                            + "org_id, project_id, version, status, dimension_codes, tender_document_ids, "
                            + "prompt_version, recall_top_k, rerank_top_n, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            ps.setLong(2, projectId);
            ps.setInt(3, version);
            ps.setString(4, STATUS_BUILDING);
            ps.setString(5, dimensionCodesJson);
            ps.setString(6, tenderDocumentIdsJson);
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
            throw new IllegalStateException("插入清单后未返回 id");
        }
        return key.longValue();
    }

    @Override
    @Transactional
    public void insertItems(long checklistId, List<ItemDraft> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            ItemDraft item = items.get(i);
            jdbcTemplate.update(
                    "INSERT INTO biz_requirement_item ("
                            + "checklist_id, dimension_code, text, source_excerpt, page_no, chunk_ids, "
                            + "must_flag, sort_order, title, main_type, sub_type, bid_search_json, "
                            + "evidence_json, score_total, score_parts_json, score_sum_matches, requirement_code) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    checklistId,
                    item.getDimensionCode(),
                    item.getText(),
                    item.getSourceExcerpt(),
                    item.getPageNo(),
                    item.getChunkIdsJson(),
                    item.isMust(),
                    item.getSortOrder(),
                    item.getTitle(),
                    item.getMainType(),
                    item.getSubType(),
                    item.getBidSearchJson(),
                    item.getEvidenceJson(),
                    item.getScoreTotal(),
                    item.getScorePartsJson(),
                    item.getScoreSumMatches(),
                    item.getRequirementCode()
            );
        }
    }

    @Override
    @Transactional
    public void markSuccess(long checklistId, String llmRawJson) {
        jdbcTemplate.update(
                "UPDATE biz_requirement_checklist SET status = ?, error_message = NULL, "
                        + "llm_raw_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                STATUS_SUCCESS,
                llmRawJson,
                checklistId
        );
    }

    @Override
    @Transactional
    public void markFailed(long checklistId, String errorMessage) {
        String msg = errorMessage;
        if (msg != null && msg.length() > 2000) {
            msg = msg.substring(0, 2000);
        }
        jdbcTemplate.update(
                "UPDATE biz_requirement_checklist SET status = ?, error_message = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                STATUS_FAILED,
                msg,
                checklistId
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChecklistSummary> listByProject(long orgId, long projectId) {
        return jdbcTemplate.query(
                "SELECT id, org_id, project_id, version, status, dimension_codes, tender_document_ids, "
                        + "prompt_version, recall_top_k, rerank_top_n, error_message, created_by, "
                        + "created_at, updated_at "
                        + "FROM biz_requirement_checklist "
                        + "WHERE org_id = ? AND project_id = ? "
                        + "ORDER BY version DESC",
                (rs, rowNum) -> mapSummary(rs),
                orgId,
                projectId
        );
    }

    @Override
    @Transactional(readOnly = true)
    public ChecklistDetail findDetail(long orgId, long projectId, long checklistId) {
        List<ChecklistDetail> heads = jdbcTemplate.query(
                "SELECT id, org_id, project_id, version, status, dimension_codes, tender_document_ids, "
                        + "prompt_version, recall_top_k, rerank_top_n, error_message, created_by, "
                        + "created_at, updated_at, llm_raw_json "
                        + "FROM biz_requirement_checklist "
                        + "WHERE id = ? AND org_id = ? AND project_id = ?",
                (rs, rowNum) -> {
                    ChecklistDetail d = new ChecklistDetail();
                    copySummary(mapSummary(rs), d);
                    d.setLlmRawJson(rs.getString("llm_raw_json"));
                    return d;
                },
                checklistId,
                orgId,
                projectId
        );
        if (heads.isEmpty()) {
            return null;
        }
        ChecklistDetail detail = heads.get(0);
        List<ItemView> items = jdbcTemplate.query(
                "SELECT id, checklist_id, dimension_code, text, source_excerpt, page_no, chunk_ids, "
                        + "must_flag, sort_order, title, main_type, sub_type, bid_search_json, "
                        + "evidence_json, score_total, score_parts_json, score_sum_matches, requirement_code "
                        + "FROM biz_requirement_item WHERE checklist_id = ? "
                        + "ORDER BY sort_order ASC, id ASC",
                (rs, rowNum) -> mapItem(rs),
                checklistId
        );
        detail.setItems(items);
        return detail;
    }

    @Override
    @Transactional(readOnly = true)
    public ChecklistDetail findLatestSuccess(long orgId, long projectId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM biz_requirement_checklist "
                        + "WHERE org_id = ? AND project_id = ? AND status = ? "
                        + "ORDER BY version DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                orgId,
                projectId,
                STATUS_SUCCESS
        );
        if (ids.isEmpty()) {
            return null;
        }
        return findDetail(orgId, projectId, ids.get(0));
    }

    private ChecklistSummary mapSummary(ResultSet rs) throws SQLException {
        ChecklistSummary s = new ChecklistSummary();
        s.setId(rs.getLong("id"));
        s.setOrgId(rs.getLong("org_id"));
        s.setProjectId(rs.getLong("project_id"));
        s.setVersion(rs.getInt("version"));
        s.setStatus(rs.getString("status"));
        s.setDimensionCodesJson(rs.getString("dimension_codes"));
        s.setTenderDocumentIdsJson(rs.getString("tender_document_ids"));
        s.setPromptVersion(rs.getString("prompt_version"));
        s.setRecallTopK(rs.getInt("recall_top_k"));
        s.setRerankTopN(rs.getInt("rerank_top_n"));
        s.setErrorMessage(rs.getString("error_message"));
        s.setCreatedBy(rs.getLong("created_by"));
        s.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        s.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        return s;
    }

    private void copySummary(ChecklistSummary from, ChecklistDetail to) {
        to.setId(from.getId());
        to.setOrgId(from.getOrgId());
        to.setProjectId(from.getProjectId());
        to.setVersion(from.getVersion());
        to.setStatus(from.getStatus());
        to.setDimensionCodesJson(from.getDimensionCodesJson());
        to.setTenderDocumentIdsJson(from.getTenderDocumentIdsJson());
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
        item.setChecklistId(rs.getLong("checklist_id"));
        item.setDimensionCode(rs.getString("dimension_code"));
        item.setText(rs.getString("text"));
        item.setSourceExcerpt(rs.getString("source_excerpt"));
        int page = rs.getInt("page_no");
        if (rs.wasNull()) {
            item.setPageNo(null);
        } else {
            item.setPageNo(page);
        }
        item.setChunkIdsJson(rs.getString("chunk_ids"));
        item.setMust(rs.getBoolean("must_flag"));
        item.setSortOrder(rs.getInt("sort_order"));
        item.setTitle(rs.getString("title"));
        item.setMainType(rs.getString("main_type"));
        item.setSubType(rs.getString("sub_type"));
        item.setBidSearchJson(rs.getString("bid_search_json"));
        item.setEvidenceJson(rs.getString("evidence_json"));
        java.math.BigDecimal scoreTotal = rs.getBigDecimal("score_total");
        if (rs.wasNull()) {
            item.setScoreTotal(null);
        } else {
            item.setScoreTotal(scoreTotal);
        }
        item.setScorePartsJson(rs.getString("score_parts_json"));
        boolean sumMatches = rs.getBoolean("score_sum_matches");
        if (rs.wasNull()) {
            item.setScoreSumMatches(null);
        } else {
            item.setScoreSumMatches(sumMatches);
        }
        item.setRequirementCode(rs.getString("requirement_code"));
        return item;
    }

    private Instant toInstant(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        return ts.toInstant();
    }
}
