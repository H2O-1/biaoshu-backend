package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.BidSimulationAuditPort;
import com.bidding.common.port.DocumentAccessPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 投标模拟审核结果导出。
 */
@Service
public class BidSimulationAuditExportService {

    private static final ZoneId EXPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(EXPORT_ZONE);

    private final BidSimulationAuditAppService bidSimulationAuditAppService;
    private final DocumentAccessPort documentAccessPort;
    private final ObjectMapper objectMapper;

    public BidSimulationAuditExportService(BidSimulationAuditAppService bidSimulationAuditAppService,
                                           DocumentAccessPort documentAccessPort,
                                           ObjectMapper objectMapper) {
        this.bidSimulationAuditAppService = bidSimulationAuditAppService;
        this.documentAccessPort = documentAccessPort;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ExportFile exportAudit(long auditId) {
        BidSimulationAuditPort.AuditDetail detail = bidSimulationAuditAppService.get(auditId);
        if (!BidSimulationAuditPort.STATUS_SUCCESS.equals(detail.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "投标模拟审核未成功，不能导出");
        }

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            writeOverview(workbook, headerStyle, detail);
            writeDocuments(workbook, headerStyle, "招标文件", detail.getOrgId(), detail.getTenderPackageId());
            writeDocuments(workbook, headerStyle, "投标文件", detail.getOrgId(), detail.getBidPackageId());
            writeBasicInfo(workbook, headerStyle, detail);
            writeItems(workbook, headerStyle, "资格响应", BidSimulationAuditPort.ITEM_QUALIFICATION, detail);
            writeItems(workbook, headerStyle, "评分响应", BidSimulationAuditPort.ITEM_SCORING, detail);
            writeItems(workbook, headerStyle, "投标逻辑问题", BidSimulationAuditPort.ITEM_LOGIC_ISSUE, detail);
            writeItems(workbook, headerStyle, "投标错别字", BidSimulationAuditPort.ITEM_TYPO, detail);
            workbook.write(out);
            return new ExportFile(fileName(detail), out.toByteArray());
        } catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL, "生成投标模拟审核结果失败");
        }
    }

    private void writeOverview(Workbook workbook,
                               CellStyle headerStyle,
                               BidSimulationAuditPort.AuditDetail detail) {
        Sheet sheet = workbook.createSheet("概览");
        int rowIndex = 0;
        rowIndex = keyValue(sheet, rowIndex, "审核ID", detail.getId());
        rowIndex = keyValue(sheet, rowIndex, "任务名称", text(detail.getName()));
        rowIndex = keyValue(sheet, rowIndex, "状态", text(detail.getStatus()));
        rowIndex = keyValue(sheet, rowIndex, "招标包ID", detail.getTenderPackageId());
        rowIndex = keyValue(sheet, rowIndex, "投标包ID", detail.getBidPackageId());
        rowIndex = keyValue(sheet, rowIndex, "招标文件数量", parseLongArray(detail.getTenderDocumentIdsJson()).size());
        rowIndex = keyValue(sheet, rowIndex, "投标文件数量", parseLongArray(detail.getBidDocumentIdsJson()).size());
        rowIndex = keyValue(sheet, rowIndex, "资格项总数", detail.getQualificationTotal());
        rowIndex = keyValue(sheet, rowIndex, "资格通过数", detail.getQualificationPassCount());
        rowIndex = keyValue(sheet, rowIndex, "资格不通过数", detail.getQualificationFailCount());
        rowIndex = keyValue(sheet, rowIndex, "资格待确认数", detail.getQualificationManualCount());
        rowIndex = keyValue(sheet, rowIndex, "资格通过率", detail.getQualificationPassRate());
        rowIndex = keyValue(sheet, rowIndex, "建议得分", detail.getScoreSuggested());
        rowIndex = keyValue(sheet, rowIndex, "总分", detail.getScoreTotal());
        rowIndex = keyValue(sheet, rowIndex, "逻辑问题数量", detail.getLogicIssueCount());
        rowIndex = keyValue(sheet, rowIndex, "错别字数量", detail.getTypoCount());
        rowIndex = keyValue(sheet, rowIndex, "创建时间", formatTime(detail.getCreatedAt()));
        keyValue(sheet, rowIndex, "更新时间", formatTime(detail.getUpdatedAt()));
        applyHeader(sheet.getRow(0), headerStyle);
        autoSize(sheet, 2);
    }

    private void writeDocuments(Workbook workbook,
                                CellStyle headerStyle,
                                String sheetName,
                                long orgId,
                                long packageId) {
        Sheet sheet = workbook.createSheet(sheetName);
        header(sheet, headerStyle, "文档ID", "标包ID", "文件名", "扩展名", "索引状态");
        int rowIndex = 1;
        for (DocumentAccessPort.DocumentRef doc : documentAccessPort.listActiveByPackageId(packageId)) {
            if (doc.getOrgId() != orgId) {
                continue;
            }
            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, doc.getId());
            cell(row, 1, doc.getPackageId());
            cell(row, 2, doc.getFileName());
            cell(row, 3, doc.getFileExt());
            cell(row, 4, doc.getIndexStatus());
        }
        autoSize(sheet, 5);
    }

    private void writeBasicInfo(Workbook workbook,
                                CellStyle headerStyle,
                                BidSimulationAuditPort.AuditDetail detail) {
        Sheet sheet = workbook.createSheet("招标基础信息");
        header(sheet, headerStyle, "字段", "值", "页码", "chunkId", "原文片段");
        int rowIndex = 1;
        rowIndex = basicInfoRow(sheet, rowIndex, "招标人", detail.getTenderer(),
                detail.getBasicInfoEvidenceJson(), "tenderer");
        rowIndex = basicInfoRow(sheet, rowIndex, "招标代理", detail.getAgency(),
                detail.getBasicInfoEvidenceJson(), "agency");
        rowIndex = basicInfoRow(sheet, rowIndex, "预算金额", detail.getBudgetAmountText(),
                detail.getBasicInfoEvidenceJson(), "budgetAmountText");
        basicInfoRow(sheet, rowIndex, "投标截止时间", detail.getBidDeadlineText(),
                detail.getBasicInfoEvidenceJson(), "bidDeadlineText");
        autoSize(sheet, 5);
    }

    private int basicInfoRow(Sheet sheet,
                             int rowIndex,
                             String label,
                             String value,
                             String evidenceJson,
                             String evidenceField) {
        JsonNode evidence = findEvidenceByField(evidenceJson, evidenceField);
        Row row = sheet.createRow(rowIndex);
        cell(row, 0, label);
        cell(row, 1, value);
        cell(row, 2, textOf(evidence, "pageNo"));
        cell(row, 3, textOf(evidence, "chunkId"));
        cell(row, 4, textOf(evidence, "quote"));
        return rowIndex + 1;
    }

    private void writeItems(Workbook workbook,
                            CellStyle headerStyle,
                            String sheetName,
                            String itemType,
                            BidSimulationAuditPort.AuditDetail detail) {
        Sheet sheet = workbook.createSheet(sheetName);
        if (BidSimulationAuditPort.ITEM_SCORING.equals(itemType)) {
            header(sheet, headerStyle, "编号", "评分项", "满分", "建议得分", "得分理由",
                    "分类", "页码", "chunkIds", "原文片段");
        } else if (BidSimulationAuditPort.ITEM_LOGIC_ISSUE.equals(itemType)) {
            header(sheet, headerStyle, "编号", "问题标题", "问题内容", "严重程度", "问题类型",
                    "修改建议", "页码", "chunkIds", "原文片段");
        } else if (BidSimulationAuditPort.ITEM_TYPO.equals(itemType)) {
            header(sheet, headerStyle, "编号", "错误文本", "建议文本", "置信度", "修改建议",
                    "页码", "chunkIds", "原文片段");
        } else {
            header(sheet, headerStyle, "编号", "资格要求", "响应结果", "判断原因", "分类",
                    "页码", "chunkIds", "原文片段");
        }

        int rowIndex = 1;
        for (BidSimulationAuditPort.ItemView item : detail.getItems()) {
            if (!itemType.equals(item.getItemType())) {
                continue;
            }
            JsonNode evidence = firstEvidence(item.getEvidenceJson());
            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, item.getRequirementCode());
            if (BidSimulationAuditPort.ITEM_SCORING.equals(itemType)) {
                cell(row, 1, item.getTitle());
                cell(row, 2, item.getFullScore());
                cell(row, 3, item.getSuggestedScore());
                cell(row, 4, item.getReason());
                cell(row, 5, item.getSubType());
                cell(row, 6, pageNo(item, evidence));
                cell(row, 7, joinLongs(parseLongArray(item.getChunkIdsJson())));
                cell(row, 8, excerpt(item, evidence));
            } else if (BidSimulationAuditPort.ITEM_LOGIC_ISSUE.equals(itemType)) {
                cell(row, 1, item.getTitle());
                cell(row, 2, item.getContent());
                cell(row, 3, textOf(evidence, "severity"));
                cell(row, 4, textOf(evidence, "issueType"));
                cell(row, 5, textOf(evidence, "suggestion"));
                cell(row, 6, pageNo(item, evidence));
                cell(row, 7, joinLongs(parseLongArray(item.getChunkIdsJson())));
                cell(row, 8, excerpt(item, evidence));
            } else if (BidSimulationAuditPort.ITEM_TYPO.equals(itemType)) {
                cell(row, 1, textOf(evidence, "wrongText"));
                cell(row, 2, textOf(evidence, "suggestedText"));
                cell(row, 3, textOf(evidence, "confidence"));
                cell(row, 4, textOf(evidence, "suggestion"));
                cell(row, 5, pageNo(item, evidence));
                cell(row, 6, joinLongs(parseLongArray(item.getChunkIdsJson())));
                cell(row, 7, excerpt(item, evidence));
            } else {
                cell(row, 1, item.getTitle());
                cell(row, 2, resultLabel(item.getResult()));
                cell(row, 3, item.getReason());
                cell(row, 4, item.getSubType());
                cell(row, 5, pageNo(item, evidence));
                cell(row, 6, joinLongs(parseLongArray(item.getChunkIdsJson())));
                cell(row, 7, excerpt(item, evidence));
            }
        }
        autoSize(sheet, BidSimulationAuditPort.ITEM_TYPO.equals(itemType) || BidSimulationAuditPort.ITEM_QUALIFICATION.equals(itemType)
                ? 8 : 9);
    }

    private JsonNode findEvidenceByField(String evidenceJson, String field) {
        for (JsonNode node : parseArray(evidenceJson, "模拟审核基础信息证据 JSON 不合法")) {
            if (field.equals(textOf(node, "field"))) {
                return node;
            }
        }
        return null;
    }

    private JsonNode firstEvidence(String evidenceJson) {
        JsonNode array = parseArray(evidenceJson, "模拟审核条目证据 JSON 不合法");
        if (array.isEmpty()) {
            return null;
        }
        JsonNode first = array.get(0);
        return first != null && first.isObject() ? first : null;
    }

    private JsonNode parseArray(String json, String errorMessage) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isArray()) {
                return node;
            }
        } catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL, errorMessage);
        }
        throw new BizException(ErrorCode.INTERNAL, errorMessage);
    }

    private List<Long> parseLongArray(String json) {
        JsonNode array = parseArray(json, "模拟审核文档 ID JSON 不合法");
        List<Long> result = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.canConvertToLong()) {
                throw new BizException(ErrorCode.INTERNAL, "模拟审核文档 ID JSON 不合法");
            }
            result.add(node.asLong());
        }
        return result;
    }

    private String pageNo(BidSimulationAuditPort.ItemView item, JsonNode evidence) {
        if (item.getPageNo() != null) {
            return item.getPageNo().toString();
        }
        return textOf(evidence, "pageNo");
    }

    private String excerpt(BidSimulationAuditPort.ItemView item, JsonNode evidence) {
        String excerpt = text(item.getSourceExcerpt());
        return excerpt.isEmpty() ? textOf(evidence, "quote") : excerpt;
    }

    private String resultLabel(String result) {
        if (BidSimulationAuditPort.RESULT_PASS.equals(result)) {
            return "符合";
        }
        if (BidSimulationAuditPort.RESULT_FAIL.equals(result)) {
            return "不符合";
        }
        if (BidSimulationAuditPort.RESULT_MANUAL.equals(result)) {
            return "待人工确认";
        }
        return result;
    }

    private String textOf(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private String joinLongs(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private int keyValue(Sheet sheet, int rowIndex, String key, Object value) {
        Row row = sheet.createRow(rowIndex);
        cell(row, 0, key);
        cell(row, 1, value);
        return rowIndex + 1;
    }

    private void header(Sheet sheet, CellStyle headerStyle, String... values) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < values.length; i++) {
            cell(row, i, values[i]);
        }
        applyHeader(row, headerStyle);
    }

    private void applyHeader(Row row, CellStyle headerStyle) {
        for (Cell cell : row) {
            cell.setCellStyle(headerStyle);
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        return style;
    }

    private void cell(Row row, int index, Object value) {
        Cell cell = row.createCell(index);
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private void autoSize(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private String formatTime(Instant instant) {
        return instant == null ? "" : TIME_FORMATTER.format(instant);
    }

    private String fileName(BidSimulationAuditPort.AuditDetail detail) {
        String name = text(detail.getName()).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty()) {
            name = "bid-simulation-audit-" + detail.getId();
        }
        return name + "-投标模拟审核结果.xlsx";
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    public static class ExportFile {
        private final String fileName;
        private final byte[] content;

        public ExportFile(String fileName, byte[] content) {
            this.fileName = fileName;
            this.content = content;
        }

        public String getFileName() {
            return fileName;
        }

        public byte[] getContent() {
            return content;
        }
    }
}
