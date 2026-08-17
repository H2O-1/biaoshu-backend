package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.TenderAnalysisPort;
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
 * 招标解析 JDBC 持久化实现。
 */
@Repository
public class JdbcTenderAnalysisStore implements TenderAnalysisPort {

    private static final String SUMMARY_COLUMNS =
            "a.id, a.org_id, a.package_id, a.name, a.status, a.job_id, a.document_ids_json, "
                    + "a.tenderer, a.agency, a.budget_amount_text, a.bid_deadline_text, a.bid_deadline_at, "
                    + "a.project_intro, a.project_background, a.project_overview, a.evidence_json, "
                    + "a.error_message, "
                    + "COALESCE(item_stats.requirement_count, 0) AS requirement_count, "
                    + "COALESCE(item_stats.issue_count, 0) AS issue_count, "
                    + "a.created_by, a.created_at, a.updated_at";

    private static final String ITEM_STATS_JOIN =
            " LEFT JOIN ("
                    + "SELECT analysis_id, "
                    + "SUM(CASE WHEN item_type IN ('QUALIFICATION', 'SCORING') THEN 1 ELSE 0 END) AS requirement_count, "
                    + "SUM(CASE WHEN item_type IN ('LOGIC_ISSUE', 'TYPO') THEN 1 ELSE 0 END) AS issue_count "
                    + "FROM biz_tender_analysis_item GROUP BY analysis_id"
                    + ") item_stats ON item_stats.analysis_id = a.id";

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenderAnalysisStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public long createBuilding(long orgId,
                               long packageId,
                               String name,
                               String documentIdsJson,
                               long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO biz_tender_analysis ("
                            + "org_id, package_id, name, status, document_ids_json, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            ps.setLong(2, packageId);
            ps.setString(3, name);
            ps.setString(4, STATUS_BUILDING);
            ps.setString(5, documentIdsJson);
            ps.setLong(6, createdBy);
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
            throw new IllegalStateException("插入招标解析记录后未返回 id");
        }
        return key.longValue();
    }

    @Override
    @Transactional
    public void bindJob(long analysisId, long jobId) {
        jdbcTemplate.update(
                "UPDATE biz_tender_analysis SET job_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                jobId,
                analysisId
        );
    }

    @Override
    @Transactional
    public void insertItems(long analysisId, List<ItemDraft> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            ItemDraft item = items.get(i);
            jdbcTemplate.update(
                    "INSERT INTO biz_tender_analysis_item ("
                            + "analysis_id, item_type, requirement_code, title, content, sub_type, "
                            + "source_excerpt, page_no, chunk_ids_json, evidence_json, score_total, "
                            + "score_parts_json, score_sum_matches, sort_order) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    analysisId,
                    item.getItemType(),
                    item.getRequirementCode(),
                    item.getTitle(),
                    item.getContent(),
                    item.getSubType(),
                    item.getSourceExcerpt(),
                    item.getPageNo(),
                    item.getChunkIdsJson(),
                    item.getEvidenceJson(),
                    item.getScoreTotal(),
                    item.getScorePartsJson(),
                    item.getScoreSumMatches(),
                    item.getSortOrder()
            );
        }
    }

    @Override
    @Transactional
    public void markSuccess(long analysisId, BasicInfoDraft basicInfo, String checklistRawJson) {
        BasicInfoDraft info = basicInfo == null ? new BasicInfoDraft() : basicInfo;
        jdbcTemplate.update(
                "UPDATE biz_tender_analysis SET status = ?, tenderer = ?, agency = ?, "
                        + "budget_amount_text = ?, bid_deadline_text = ?, bid_deadline_at = ?, "
                        + "project_intro = ?, project_background = ?, project_overview = ?, "
                        + "evidence_json = ?, basic_info_raw_json = ?, checklist_raw_json = ?, "
                        + "error_message = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                STATUS_SUCCESS,
                info.getTenderer(),
                info.getAgency(),
                info.getBudgetAmountText(),
                info.getBidDeadlineText(),
                toTimestamp(info.getBidDeadlineAt()),
                info.getProjectIntro(),
                info.getProjectBackground(),
                info.getProjectOverview(),
                info.getEvidenceJson(),
                info.getRawJson(),
                checklistRawJson,
                analysisId
        );
    }

    @Override
    @Transactional
    public void markFailed(long analysisId, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE biz_tender_analysis SET status = ?, error_message = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                STATUS_FAILED,
                truncate(errorMessage),
                analysisId
        );
    }

    @Override
    @Transactional
    public int markObsolete(long orgId, long analysisId) {
        return jdbcTemplate.update(
                "UPDATE biz_tender_analysis SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND org_id = ? AND status <> ?",
                STATUS_OBSOLETE,
                analysisId,
                orgId,
                STATUS_OBSOLETE
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnalysisSummary> listByOrg(long orgId, String keyword, String status, int offset, int limit) {
        String kw = normalizeKeyword(keyword);
        String safeStatus = normalizeKeyword(status);
        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.max(limit, 1);
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(SUMMARY_COLUMNS)
                .append(" FROM biz_tender_analysis a")
                .append(ITEM_STATS_JOIN)
                .append(" WHERE a.org_id = ? AND a.status <> '")
                .append(STATUS_OBSOLETE)
                .append("'");
        appendStatus(sql, args, safeStatus);
        appendKeyword(sql, args, kw);
        sql.append(" ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?");
        args.add(safeLimit);
        args.add(safeOffset);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapSummary(rs), args.toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public long countByOrg(long orgId, String keyword, String status) {
        String kw = normalizeKeyword(keyword);
        String safeStatus = normalizeKeyword(status);
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(1) FROM biz_tender_analysis a WHERE a.org_id = ? AND a.status <> '")
                .append(STATUS_OBSOLETE)
                .append("'");
        appendStatus(sql, args, safeStatus);
        appendKeyword(sql, args, kw);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    @Transactional(readOnly = true)
    public AnalysisSummary findById(long orgId, long analysisId) {
        List<AnalysisSummary> list = jdbcTemplate.query(
                "SELECT " + SUMMARY_COLUMNS
                        + " FROM biz_tender_analysis a"
                        + ITEM_STATS_JOIN
                        + " WHERE a.id = ? AND a.org_id = ? AND a.status <> ?",
                (rs, rowNum) -> mapSummary(rs),
                analysisId,
                orgId,
                STATUS_OBSOLETE
        );
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public AnalysisSummary findAnyById(long orgId, long analysisId) {
        List<AnalysisSummary> list = jdbcTemplate.query(
                "SELECT " + SUMMARY_COLUMNS
                        + " FROM biz_tender_analysis a"
                        + ITEM_STATS_JOIN
                        + " WHERE a.id = ? AND a.org_id = ?",
                (rs, rowNum) -> mapSummary(rs),
                analysisId,
                orgId
        );
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public AnalysisDetail findDetail(long orgId, long analysisId) {
        List<AnalysisDetail> heads = jdbcTemplate.query(
                "SELECT " + SUMMARY_COLUMNS + ", a.basic_info_raw_json, a.checklist_raw_json "
                        + "FROM biz_tender_analysis a"
                        + ITEM_STATS_JOIN
                        + " WHERE a.id = ? AND a.org_id = ? AND a.status <> ?",
                (rs, rowNum) -> {
                    AnalysisDetail detail = new AnalysisDetail();
                    copySummary(mapSummary(rs), detail);
                    detail.setBasicInfoRawJson(rs.getString("basic_info_raw_json"));
                    detail.setChecklistRawJson(rs.getString("checklist_raw_json"));
                    return detail;
                },
                analysisId,
                orgId,
                STATUS_OBSOLETE
        );
        if (heads.isEmpty()) {
            return null;
        }
        AnalysisDetail detail = heads.get(0);
        List<ItemView> items = jdbcTemplate.query(
                "SELECT id, analysis_id, item_type, requirement_code, title, content, sub_type, "
                        + "source_excerpt, page_no, chunk_ids_json, evidence_json, score_total, "
                        + "score_parts_json, score_sum_matches, sort_order, created_at "
                        + "FROM biz_tender_analysis_item WHERE analysis_id = ? "
                        + "ORDER BY sort_order ASC, id ASC",
                (rs, rowNum) -> mapItem(rs),
                analysisId
        );
        detail.setItems(items);
        return detail;
    }

    private static void appendKeyword(StringBuilder sql, List<Object> args, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return;
        }
        sql.append(" AND (a.name LIKE ? OR a.tenderer LIKE ? OR a.agency LIKE ?)");
        String pattern = "%" + keyword + "%";
        args.add(pattern);
        args.add(pattern);
        args.add(pattern);
    }

    private static void appendStatus(StringBuilder sql, List<Object> args, String status) {
        if (status == null || status.isEmpty()) {
            return;
        }
        sql.append(" AND a.status = ?");
        args.add(status);
    }

    private AnalysisSummary mapSummary(ResultSet rs) throws SQLException {
        AnalysisSummary summary = new AnalysisSummary();
        summary.setId(rs.getLong("id"));
        summary.setOrgId(rs.getLong("org_id"));
        summary.setPackageId(rs.getLong("package_id"));
        summary.setName(rs.getString("name"));
        summary.setStatus(rs.getString("status"));
        long jobId = rs.getLong("job_id");
        summary.setJobId(rs.wasNull() ? null : jobId);
        summary.setDocumentIdsJson(rs.getString("document_ids_json"));
        summary.setTenderer(rs.getString("tenderer"));
        summary.setAgency(rs.getString("agency"));
        summary.setBudgetAmountText(rs.getString("budget_amount_text"));
        summary.setBidDeadlineText(rs.getString("bid_deadline_text"));
        summary.setBidDeadlineAt(toInstant(rs.getTimestamp("bid_deadline_at")));
        summary.setProjectIntro(rs.getString("project_intro"));
        summary.setProjectBackground(rs.getString("project_background"));
        summary.setProjectOverview(rs.getString("project_overview"));
        summary.setEvidenceJson(rs.getString("evidence_json"));
        summary.setErrorMessage(rs.getString("error_message"));
        summary.setRequirementCount(rs.getInt("requirement_count"));
        summary.setIssueCount(rs.getInt("issue_count"));
        summary.setCreatedBy(rs.getLong("created_by"));
        summary.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        summary.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        return summary;
    }

    private void copySummary(AnalysisSummary from, AnalysisDetail to) {
        to.setId(from.getId());
        to.setOrgId(from.getOrgId());
        to.setPackageId(from.getPackageId());
        to.setName(from.getName());
        to.setStatus(from.getStatus());
        to.setJobId(from.getJobId());
        to.setDocumentIdsJson(from.getDocumentIdsJson());
        to.setTenderer(from.getTenderer());
        to.setAgency(from.getAgency());
        to.setBudgetAmountText(from.getBudgetAmountText());
        to.setBidDeadlineText(from.getBidDeadlineText());
        to.setBidDeadlineAt(from.getBidDeadlineAt());
        to.setProjectIntro(from.getProjectIntro());
        to.setProjectBackground(from.getProjectBackground());
        to.setProjectOverview(from.getProjectOverview());
        to.setEvidenceJson(from.getEvidenceJson());
        to.setErrorMessage(from.getErrorMessage());
        to.setRequirementCount(from.getRequirementCount());
        to.setIssueCount(from.getIssueCount());
        to.setCreatedBy(from.getCreatedBy());
        to.setCreatedAt(from.getCreatedAt());
        to.setUpdatedAt(from.getUpdatedAt());
    }

    private ItemView mapItem(ResultSet rs) throws SQLException {
        ItemView item = new ItemView();
        item.setId(rs.getLong("id"));
        item.setAnalysisId(rs.getLong("analysis_id"));
        item.setItemType(rs.getString("item_type"));
        item.setRequirementCode(rs.getString("requirement_code"));
        item.setTitle(rs.getString("title"));
        item.setContent(rs.getString("content"));
        item.setSubType(rs.getString("sub_type"));
        item.setSourceExcerpt(rs.getString("source_excerpt"));
        int page = rs.getInt("page_no");
        item.setPageNo(rs.wasNull() ? null : page);
        item.setChunkIdsJson(rs.getString("chunk_ids_json"));
        item.setEvidenceJson(rs.getString("evidence_json"));
        item.setScoreTotal(rs.getBigDecimal("score_total"));
        item.setScorePartsJson(rs.getString("score_parts_json"));
        boolean sumMatches = rs.getBoolean("score_sum_matches");
        item.setScoreSumMatches(rs.wasNull() ? null : sumMatches);
        item.setSortOrder(rs.getInt("sort_order"));
        item.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        return item;
    }

    private static String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }

    private static String truncate(String text) {
        if (text != null && text.length() > 2000) {
            return text.substring(0, 2000);
        }
        return text;
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
