package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
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

/**
 * 专家评审报告导出。
 */
@Service
public class ExpertReviewExportService {

    private static final ZoneId EXPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(EXPORT_ZONE);

    private final ExpertReviewAppService expertReviewAppService;

    public ExpertReviewExportService(ExpertReviewAppService expertReviewAppService) {
        this.expertReviewAppService = expertReviewAppService;
    }

    @Transactional(readOnly = true)
    public ExportFile exportProjectReview(long projectId) {
        ExpertReviewAppService.ReviewSnapshot snapshot = expertReviewAppService.getSnapshot(projectId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审不存在");
        }

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(workbook);
            writeOverview(workbook, headerStyle, snapshot);
            writeMembers(workbook, headerStyle, snapshot);
            writeCompanies(workbook, headerStyle, snapshot);
            writeScoreCompare(workbook, headerStyle, snapshot);
            writeVetoPending(workbook, headerStyle, snapshot);
            workbook.write(out);
            return new ExportFile(fileName(snapshot), out.toByteArray());
        } catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL, "生成专家评审报告失败");
        }
    }

    private void writeOverview(Workbook workbook,
                               CellStyle headerStyle,
                               ExpertReviewAppService.ReviewSnapshot snapshot) {
        Sheet sheet = workbook.createSheet("概览");
        int rowIndex = 0;
        rowIndex = keyValue(sheet, rowIndex, "项目名称", text(snapshot.getProjectName()));
        rowIndex = keyValue(sheet, rowIndex, "项目状态", text(snapshot.getProjectStatus()));
        rowIndex = keyValue(sheet, rowIndex, "评审状态", text(snapshot.getStatus()));
        rowIndex = keyValue(sheet, rowIndex, "截止时间", formatTime(snapshot.getDeadlineAt()));
        rowIndex = keyValue(sheet, rowIndex, "专家总数", snapshot.getExpertCount());
        rowIndex = keyValue(sheet, rowIndex, "已提交专家数", snapshot.getSubmittedExpertCount());
        rowIndex = keyValue(sheet, rowIndex, "已完成投标单位数", snapshot.getSubmittedCompanyCount());
        rowIndex = keyValue(sheet, rowIndex, "已有完整提交", snapshot.isHasAnyCompleteSubmission() ? "是" : "否");
        rowIndex = keyValue(sheet, rowIndex, "是否锁定", snapshot.isLocked() ? "是" : "否");
        keyValue(sheet, rowIndex, "推送留言", text(snapshot.getPushMessage()));
        applyHeader(sheet.getRow(0), headerStyle);
        autoSize(sheet, 2);
    }

    private void writeMembers(Workbook workbook,
                              CellStyle headerStyle,
                              ExpertReviewAppService.ReviewSnapshot snapshot) {
        Sheet sheet = workbook.createSheet("专家提交");
        header(sheet, headerStyle, "专家姓名", "账号", "成员状态", "提交状态", "提交时间", "是否已提交");
        int rowIndex = 1;
        for (ExpertReviewAppService.ExpertMemberSummary member : snapshot.getExpertMembers()) {
            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, member.getDisplayName());
            cell(row, 1, member.getUsername());
            cell(row, 2, member.getStatus());
            cell(row, 3, member.getSubmitStatus());
            cell(row, 4, formatTime(member.getSubmittedAt()));
            cell(row, 5, member.isSubmitted() ? "是" : "否");
        }
        autoSize(sheet, 6);
    }

    private void writeCompanies(Workbook workbook,
                                CellStyle headerStyle,
                                ExpertReviewAppService.ReviewSnapshot snapshot) {
        Sheet sheet = workbook.createSheet("投标单位排行");
        header(sheet, headerStyle, "排名", "投标单位", "投标包ID", "平均分", "公司状态", "展示模式", "废标建议数",
                "废标专家", "废标原因", "确认人ID", "确认时间");
        int rowIndex = 1;
        for (ExpertReviewAppService.CompanyTab company : snapshot.getCompanyTabs()) {
            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, company.getRank());
            cell(row, 1, company.getSupplierName());
            cell(row, 2, company.getBidPackageId());
            cell(row, 3, company.getAverageScore());
            cell(row, 4, company.getCompanyStatus());
            cell(row, 5, company.getDisplayMode());
            cell(row, 6, company.getVetoItemCount());
            cell(row, 7, company.getVetoExpertNames());
            cell(row, 8, company.getVetoReasonSummary());
            cell(row, 9, company.getConfirmedBy());
            cell(row, 10, formatTime(company.getConfirmedAt()));
        }
        autoSize(sheet, 11);
    }

    private void writeScoreCompare(Workbook workbook,
                                   CellStyle headerStyle,
                                   ExpertReviewAppService.ReviewSnapshot snapshot) {
        Sheet sheet = workbook.createSheet("专家评分对比");
        header(sheet, headerStyle, "投标单位", "投标包ID", "平均分", "排名", "专家姓名", "专家账号", "专家评分");
        int rowIndex = 1;
        for (ExpertReviewAppService.ScoreCompareItem company : snapshot.getScoreCompareItems()) {
            if (company.getExpertScores().isEmpty()) {
                Row row = sheet.createRow(rowIndex++);
                cell(row, 0, company.getSupplierName());
                cell(row, 1, company.getBidPackageId());
                cell(row, 2, company.getAverageScore());
                cell(row, 3, company.getRank());
                continue;
            }
            for (ExpertReviewAppService.ExpertScore score : company.getExpertScores()) {
                Row row = sheet.createRow(rowIndex++);
                cell(row, 0, company.getSupplierName());
                cell(row, 1, company.getBidPackageId());
                cell(row, 2, company.getAverageScore());
                cell(row, 3, company.getRank());
                cell(row, 4, score.getDisplayName());
                cell(row, 5, score.getUsername());
                cell(row, 6, score.getScore());
            }
        }
        autoSize(sheet, 7);
    }

    private void writeVetoPending(Workbook workbook,
                                  CellStyle headerStyle,
                                  ExpertReviewAppService.ReviewSnapshot snapshot) {
        Sheet sheet = workbook.createSheet("待确认废标");
        header(sheet, headerStyle, "投标单位", "投标包ID", "废标建议数", "废标专家", "废标原因");
        int rowIndex = 1;
        for (ExpertReviewAppService.VetoPendingItem item : snapshot.getVetoPendingItems()) {
            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, item.getSupplierName());
            cell(row, 1, item.getBidPackageId());
            cell(row, 2, item.getVetoItemCount());
            cell(row, 3, item.getVetoExpertNames());
            cell(row, 4, item.getVetoReasonSummary());
        }
        autoSize(sheet, 5);
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
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
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

    private String fileName(ExpertReviewAppService.ReviewSnapshot snapshot) {
        String projectName = text(snapshot.getProjectName()).replaceAll("[\\\\/:*?\"<>|]", "_");
        if (projectName.isEmpty()) {
            projectName = "project-" + snapshot.getProjectId();
        }
        return projectName + "-专家评审报告.xlsx";
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
