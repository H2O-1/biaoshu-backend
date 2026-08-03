package com.bidding.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标包文件：上传/列表/下载/预览/删除；有文件时不可删包。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DocumentFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadListDelete_andBlockPackageDeleteWhenHasFiles() throws Exception {
        String adminToken = login("admin", "ChangeMe@123");

        MvcResult orgCreated = mockMvc.perform(post("/api/admin/orgs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"文件联调公司","city":"成都市"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long orgId = Long.parseLong(orgCreated.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgId": %d,
                                  "username": "tenderer_file",
                                  "password": "InitPass1",
                                  "displayName": "招标用户",
                                  "phone": "13920000031",
                                  "email": "tenderer_file@example.com",
                                  "roleCodes": ["TENDERER"]
                                }
                                """.formatted(orgId)))
                .andExpect(status().isOk());

        String firstToken = login("tenderer_file", "InitPass1");
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"Stable9x"}
                                """))
                .andExpect(status().isOk());
        String tendererToken = login("tenderer_file", "Stable9x");

        MvcResult pkg = mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"TENDER","name":"带文件的招标包"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long packageId = Long.parseLong(pkg.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "招标文件.pdf", "application/pdf", "hello-pdf".getBytes());

        MvcResult uploaded = mockMvc.perform(multipart("/api/packages/" + packageId + "/files")
                        .file(file)
                        .param("fileType", "招标正文")
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileName").value("招标文件.pdf"))
                .andExpect(jsonPath("$.data.fileType").value("招标正文"))
                .andExpect(jsonPath("$.data.indexStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.fileExt").value("pdf"))
                .andReturn();
        long documentId = Long.parseLong(uploaded.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/packages/" + packageId + "/files")
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/packages/" + packageId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileCount").value(1));

        // 下载：Bearer + 二进制流
        mockMvc.perform(get("/api/packages/" + packageId + "/files/" + documentId + "/download")
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().bytes("hello-pdf".getBytes()));

        // PDF 可预览
        mockMvc.perform(get("/api/packages/" + packageId + "/files/" + documentId + "/preview")
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("inline")))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));

        // Word/Excel 不可预览
        MockMultipartFile xlsx = new MockMultipartFile(
                "file", "报价.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "xlsx-bytes".getBytes());
        MvcResult xlsxUploaded = mockMvc.perform(multipart("/api/packages/" + packageId + "/files")
                        .file(xlsx)
                        .param("fileType", "报价表")
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andReturn();
        long xlsxId = Long.parseLong(xlsxUploaded.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));
        mockMvc.perform(get("/api/packages/" + packageId + "/files/" + xlsxId + "/preview")
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // 有文件不可删包
        mockMvc.perform(delete("/api/packages/" + packageId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        mockMvc.perform(delete("/api/packages/" + packageId + "/files/" + documentId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/packages/" + packageId + "/files/" + xlsxId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/packages/" + packageId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileCount").value(0));

        mockMvc.perform(delete("/api/packages/" + packageId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return login.getResponse().getContentAsString()
                .replaceAll("(?s).*\"accessToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }
}
