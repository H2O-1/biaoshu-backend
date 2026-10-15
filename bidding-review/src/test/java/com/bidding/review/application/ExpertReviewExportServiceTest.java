package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExpertReviewExportServiceTest {

    @Test
    void exportProjectReviewBuildsXlsxWorkbook() throws IOException {
        ExpertReviewAppService appService = mock(ExpertReviewAppService.class);
        when(appService.getSnapshot(100L)).thenReturn(snapshot());
        ExpertReviewExportService exportService = new ExpertReviewExportService(appService);

        ExpertReviewExportService.ExportFile file = exportService.exportProjectReview(100L);

        assertEquals("智慧教育-专家评审报告.xlsx", file.getFileName());
        assertTrue(file.getContent().length > 0);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            assertNotNull(workbook.getSheet("概览"));
            assertNotNull(workbook.getSheet("专家提交"));
            assertNotNull(workbook.getSheet("投标单位排行"));
            assertNotNull(workbook.getSheet("专家评分对比"));
            assertEquals("项目名称", workbook.getSheet("概览").getRow(0).getCell(0).getStringCellValue());
            assertEquals("智慧教育", workbook.getSheet("概览").getRow(0).getCell(1).getStringCellValue());
            assertEquals("专家一", workbook.getSheet("专家提交").getRow(1).getCell(0).getStringCellValue());
            assertEquals("投标单位A", workbook.getSheet("投标单位排行").getRow(1).getCell(1).getStringCellValue());
        }
    }

    @Test
    void exportProjectReviewRejectsMissingReview() {
        ExpertReviewAppService appService = mock(ExpertReviewAppService.class);
        when(appService.getSnapshot(100L)).thenReturn(null);
        ExpertReviewExportService exportService = new ExpertReviewExportService(appService);

        BizException exception = assertThrows(BizException.class,
                () -> exportService.exportProjectReview(100L));

        assertEquals(ErrorCode.NOT_FOUND, exception.errorCode());
    }

    private static ExpertReviewAppService.ReviewSnapshot snapshot() {
        ExpertReviewAppService.ReviewSnapshot snapshot = new ExpertReviewAppService.ReviewSnapshot();
        snapshot.setReviewId(501L);
        snapshot.setProjectId(100L);
        snapshot.setProjectName("智慧教育");
        snapshot.setProjectStatus("ACTIVE");
        snapshot.setStatus("SUCCESS");
        snapshot.setDeadlineAt(Instant.parse("2026-08-18T08:00:00Z"));
        snapshot.setExpertCount(1);
        snapshot.setSubmittedExpertCount(1);
        snapshot.setSubmittedCompanyCount(1);
        snapshot.setHasAnyCompleteSubmission(true);
        snapshot.setLocked(true);
        snapshot.setPushMessage("请按时完成评审");

        ExpertReviewAppService.ExpertMemberSummary member =
                new ExpertReviewAppService.ExpertMemberSummary();
        member.setReviewId(501L);
        member.setMemberId(88L);
        member.setUserId(77L);
        member.setUsername("expert_77");
        member.setDisplayName("专家一");
        member.setStatus("DISABLED");
        member.setSubmitStatus("SUBMITTED");
        member.setSubmittedAt(Instant.parse("2026-08-18T07:00:00Z"));
        member.setSubmitted(true);
        snapshot.setExpertMembers(List.of(member));

        ExpertReviewAppService.CompanyTab company = new ExpertReviewAppService.CompanyTab();
        company.setCompanyId(301L);
        company.setBidPackageId(81L);
        company.setSupplierName("投标单位A");
        company.setCompanyStatus("NORMAL");
        company.setDisplayMode("NORMAL");
        company.setAverageScore(new BigDecimal("88.50"));
        company.setRank(1);
        snapshot.setCompanyTabs(List.of(company));

        ExpertReviewAppService.ScoreCompareItem scoreCompare =
                new ExpertReviewAppService.ScoreCompareItem();
        scoreCompare.setCompanyId(301L);
        scoreCompare.setBidPackageId(81L);
        scoreCompare.setSupplierName("投标单位A");
        scoreCompare.setAverageScore(new BigDecimal("88.50"));
        scoreCompare.setRank(1);
        ExpertReviewAppService.ExpertScore score = new ExpertReviewAppService.ExpertScore();
        score.setMemberId(88L);
        score.setUsername("expert_77");
        score.setDisplayName("专家一");
        score.setScore(new BigDecimal("88.50"));
        scoreCompare.setExpertScores(List.of(score));
        snapshot.setScoreCompareItems(List.of(scoreCompare));

        return snapshot;
    }
}
