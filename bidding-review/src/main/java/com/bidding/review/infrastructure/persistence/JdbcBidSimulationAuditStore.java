package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.BidSimulationAuditPort;
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
 * 投标模拟审核 JDBC 持久化实现。
 */
@Repository
public class JdbcBidSimulationAuditStore implements BidSimulationAuditPort {

    private static final String SUMMARY_COLUMNS =
            "id, org_id, name, status, job_id, tender_package_id, bid_package_id, "
                    + "tender_document_ids_json, bid_document_ids_json, qualification_total, "
                    + "qualification_pass_count, qualification_fail_count, qualification_manual_count, "
                    + "qualification_pass_rate, score_suggested, score_total, logic_issue_count, "
                    + "typo_count, tenderer, agency, budget_amount_text, bid_deadline_text, "
                    + "bid_deadline_at, basic_info_evidence_json, basic_info_raw_json, "
                    + "error_message, created_by, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcBidSimulationAuditStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public long createBuilding(long orgId,
                               String name,
                               long tenderPackageId,
                               long bidPackageId,
                               String tenderDocumentIdsJson,
                               String bidDocumentIdsJson,
                               long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO biz_bid_simulation_audit ("
                            + "org_id, name, status, tender_package_id, bid_package_id, "
                            + "tender_document_ids_json, bid_document_ids_json, created_by) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            ps.setString(2, name);
            ps.setString(3, STATUS_BUILDING);
            ps.setLong(4, tenderPackageId);
            ps.setLong(5, bidPackageId);
            ps.setString(6, tenderDocumentIdsJson);
            ps.setString(7, bidDocumentIdsJson);
            ps.setLong(8, createdBy);
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
            throw new IllegalStateException("插入投标模拟审核记录后未返回 id");
        }
        return key.longValue();
    }

    @Override
    @Transactional
    public void bindJob(long auditId, long jobId) {
        jdbcTemplate.update(
                "UPDATE biz_bid_simulation_audit SET job_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                jobId,
                auditId
        );
    }

    @Override
    @Transactional
    public void insertItems(long auditId, List<ItemDraft> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (ItemDraft item : items) {
            jdbcTemplate.update(
                    "INSERT INTO biz_bid_simulation_audit_item ("
                            + "audit_id, item_type, requirement_code, title, content, result, reason, "
                            + "suggested_score, full_score, sub_type, source_excerpt, page_no, "
                            + "chunk_ids_json, evidence_json, raw_json, sort_order) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    auditId,
                    item.getItemType(),
                    item.getRequirementCode(),
                    item.getTitle(),
                    item.getContent(),
                    item.getResult(),
                    item.getReason(),
                    item.getSuggestedScore(),
                    item.getFullScore(),
                    item.getSubType(),
                    item.getSourceExcerpt(),
                    item.getPageNo(),
                    item.getChunkIdsJson(),
                    item.getEvidenceJson(),
                    item.getRawJson(),
                    item.getSortOrder()
            );
        }
    }

    @Override
    @Transactional
    public void markSuccess(long auditId, SummaryDraft summary,
                            com.bidding.common.port.TenderAnalysisPort.BasicInfoDraft basicInfo) {
        SummaryDraft safeSummary = summary == null ? new SummaryDraft() : summary;
        com.bidding.common.port.TenderAnalysisPort.BasicInfoDraft safeBasic =
                basicInfo == null ? new com.bidding.common.port.TenderAnalysisPort.BasicInfoDraft() : basicInfo;
        jdbcTemplate.update(
                "UPDATE biz_bid_simulation_audit SET status = ?, qualification_total = ?, "
                        + "qualification_pass_count = ?, qualification_fail_count = ?, "
                        + "qualification_manual_count = ?, qualification_pass_rate = ?, "
                        + "score_suggested = ?, score_total = ?, logic_issue_count = ?, typo_count = ?, "
                        + "tenderer = ?, agency = ?, budget_amount_text = ?, bid_deadline_text = ?, "
                        + "bid_deadline_at = ?, basic_info_evidence_json = ?, basic_info_raw_json = ?, "
                        + "error_message = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                STATUS_SUCCESS,
                safeSummary.getQualificationTotal(),
                safeSummary.getQualificationPassCount(),
                safeSummary.getQualificationFailCount(),
                safeSummary.getQualificationManualCount(),
                zeroIfNull(safeSummary.getQualificationPassRate()),
                zeroIfNull(safeSummary.getScoreSuggested()),
                zeroIfNull(safeSummary.getScoreTotal()),
                safeSummary.getLogicIssueCount(),
                safeSummary.getTypoCount(),
                safeBasic.getTenderer(),
                safeBasic.getAgency(),
                safeBasic.getBudgetAmountText(),
                safeBasic.getBidDeadlineText(),
                toTimestamp(safeBasic.getBidDeadlineAt()),
                safeBasic.getEvidenceJson(),
                safeBasic.getRawJson(),
                auditId
        );
    }

    @Override
    @Transactional
    public void markFailed(long auditId, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE biz_bid_simulation_audit SET status = ?, error_message = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                STATUS_FAILED,
                truncate(errorMessage),
                auditId
        );
    }

    @Override
    @Transactional
    public int markObsolete(long orgId, long auditId) {
        return jdbcTemplate.update(
                "UPDATE biz_bid_simulation_audit SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND org_id = ? AND status <> ?",
                STATUS_OBSOLETE,
                auditId,
                orgId,
                STATUS_OBSOLETE
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditSummary> listByOrg(long orgId, String keyword, String status, int offset, int limit) {
        String kw = normalizeKeyword(keyword);
        String safeStatus = normalizeKeyword(status);
        int safeOffset = Math.max(offset, 0);
        int safeLimit = Math.max(limit, 1);
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(SUMMARY_COLUMNS)
                .append(" FROM biz_bid_simulation_audit WHERE org_id = ? AND status <> '")
                .append(STATUS_OBSOLETE)
                .append("'");
        appendStatus(sql, args, safeStatus);
        appendKeyword(sql, args, kw);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
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
        sql.append("SELECT COUNT(1) FROM biz_bid_simulation_audit WHERE org_id = ? AND status <> '")
                .append(STATUS_OBSOLETE)
                .append("'");
        appendStatus(sql, args, safeStatus);
        appendKeyword(sql, args, kw);
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    @Transactional(readOnly = true)
    public AuditSummary findById(long orgId, long auditId) {
        List<AuditSummary> list = jdbcTemplate.query(
                "SELECT " + SUMMARY_COLUMNS
                        + " FROM biz_bid_simulation_audit WHERE id = ? AND org_id = ? AND status <> ?",
                (rs, rowNum) -> mapSummary(rs),
                auditId,
                orgId,
                STATUS_OBSOLETE
        );
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditDetail findDetail(long orgId, long auditId) {
        List<AuditDetail> heads = jdbcTemplate.query(
                "SELECT " + SUMMARY_COLUMNS
                        + " FROM biz_bid_simulation_audit WHERE id = ? AND org_id = ? AND status <> ?",
                (rs, rowNum) -> {
                    AuditDetail detail = new AuditDetail();
                    copySummary(mapSummary(rs), detail);
                    return detail;
                },
                auditId,
                orgId,
                STATUS_OBSOLETE
        );
        if (heads.isEmpty()) {
            return null;
        }
        AuditDetail detail = heads.get(0);
        List<ItemView> items = jdbcTemplate.query(
                "SELECT id, audit_id, item_type, requirement_code, title, content, result, reason, "
                        + "suggested_score, full_score, sub_type, source_excerpt, page_no, "
                        + "chunk_ids_json, evidence_json, raw_json, sort_order, created_at "
                        + "FROM biz_bid_simulation_audit_item WHERE audit_id = ? "
                        + "ORDER BY sort_order ASC, id ASC",
                (rs, rowNum) -> mapItem(rs),
                auditId
        );
        detail.setItems(items);
        return detail;
    }

    private static void appendKeyword(StringBuilder sql, List<Object> args, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return;
        }
        sql.append(" AND name LIKE ?");
        args.add("%" + keyword + "%");
    }

    private static void appendStatus(StringBuilder sql, List<Object> args, String status) {
        if (status == null || status.isEmpty()) {
            return;
        }
        sql.append(" AND status = ?");
        args.add(status);
    }

    private AuditSummary mapSummary(ResultSet rs) throws SQLException {
        AuditSummary summary = new AuditSummary();
        summary.setId(rs.getLong("id"));
        summary.setOrgId(rs.getLong("org_id"));
        summary.setName(rs.getString("name"));
        summary.setStatus(rs.getString("status"));
        long jobId = rs.getLong("job_id");
        summary.setJobId(rs.wasNull() ? null : jobId);
        summary.setTenderPackageId(rs.getLong("tender_package_id"));
        summary.setBidPackageId(rs.getLong("bid_package_id"));
        summary.setTenderDocumentIdsJson(rs.getString("tender_document_ids_json"));
        summary.setBidDocumentIdsJson(rs.getString("bid_document_ids_json"));
        summary.setTenderer(rs.getString("tenderer"));
        summary.setAgency(rs.getString("agency"));
        summary.setBudgetAmountText(rs.getString("budget_amount_text"));
        summary.setBidDeadlineText(rs.getString("bid_deadline_text"));
        summary.setBidDeadlineAt(toInstant(rs.getTimestamp("bid_deadline_at")));
        summary.setBasicInfoEvidenceJson(rs.getString("basic_info_evidence_json"));
        summary.setBasicInfoRawJson(rs.getString("basic_info_raw_json"));
        summary.setQualificationTotal(rs.getInt("qualification_total"));
        summary.setQualificationPassCount(rs.getInt("qualification_pass_count"));
        summary.setQualificationFailCount(rs.getInt("qualification_fail_count"));
        summary.setQualificationManualCount(rs.getInt("qualification_manual_count"));
        summary.setQualificationPassRate(rs.getBigDecimal("qualification_pass_rate"));
        summary.setScoreSuggested(rs.getBigDecimal("score_suggested"));
        summary.setScoreTotal(rs.getBigDecimal("score_total"));
        summary.setLogicIssueCount(rs.getInt("logic_issue_count"));
        summary.setTypoCount(rs.getInt("typo_count"));
        summary.setErrorMessage(rs.getString("error_message"));
        summary.setCreatedBy(rs.getLong("created_by"));
        summary.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        summary.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        return summary;
    }

    private void copySummary(AuditSummary from, AuditDetail to) {
        to.setId(from.getId());
        to.setOrgId(from.getOrgId());
        to.setName(from.getName());
        to.setStatus(from.getStatus());
        to.setJobId(from.getJobId());
        to.setTenderPackageId(from.getTenderPackageId());
        to.setBidPackageId(from.getBidPackageId());
        to.setTenderDocumentIdsJson(from.getTenderDocumentIdsJson());
        to.setBidDocumentIdsJson(from.getBidDocumentIdsJson());
        to.setTenderer(from.getTenderer());
        to.setAgency(from.getAgency());
        to.setBudgetAmountText(from.getBudgetAmountText());
        to.setBidDeadlineText(from.getBidDeadlineText());
        to.setBidDeadlineAt(from.getBidDeadlineAt());
        to.setBasicInfoEvidenceJson(from.getBasicInfoEvidenceJson());
        to.setBasicInfoRawJson(from.getBasicInfoRawJson());
        to.setQualificationTotal(from.getQualificationTotal());
        to.setQualificationPassCount(from.getQualificationPassCount());
        to.setQualificationFailCount(from.getQualificationFailCount());
        to.setQualificationManualCount(from.getQualificationManualCount());
        to.setQualificationPassRate(from.getQualificationPassRate());
        to.setScoreSuggested(from.getScoreSuggested());
        to.setScoreTotal(from.getScoreTotal());
        to.setLogicIssueCount(from.getLogicIssueCount());
        to.setTypoCount(from.getTypoCount());
        to.setErrorMessage(from.getErrorMessage());
        to.setCreatedBy(from.getCreatedBy());
        to.setCreatedAt(from.getCreatedAt());
        to.setUpdatedAt(from.getUpdatedAt());
    }

    private ItemView mapItem(ResultSet rs) throws SQLException {
        ItemView item = new ItemView();
        item.setId(rs.getLong("id"));
        item.setAuditId(rs.getLong("audit_id"));
        item.setItemType(rs.getString("item_type"));
        item.setRequirementCode(rs.getString("requirement_code"));
        item.setTitle(rs.getString("title"));
        item.setContent(rs.getString("content"));
        item.setResult(rs.getString("result"));
        item.setReason(rs.getString("reason"));
        item.setSuggestedScore(rs.getBigDecimal("suggested_score"));
        item.setFullScore(rs.getBigDecimal("full_score"));
        item.setSubType(rs.getString("sub_type"));
        item.setSourceExcerpt(rs.getString("source_excerpt"));
        int page = rs.getInt("page_no");
        item.setPageNo(rs.wasNull() ? null : page);
        item.setChunkIdsJson(rs.getString("chunk_ids_json"));
        item.setEvidenceJson(rs.getString("evidence_json"));
        item.setRawJson(rs.getString("raw_json"));
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

    private static java.math.BigDecimal zeroIfNull(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO : value;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
