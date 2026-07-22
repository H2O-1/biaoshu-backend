package com.bidding.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 招标包 CRUD：招标方可操作；平台管理员 403；未绑可删。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PackageFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void tendererCanCrudTenderPackage_adminForbidden() throws Exception {
        String adminToken = login("admin", "ChangeMe@123");

        MvcResult orgCreated = mockMvc.perform(post("/api/admin/orgs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"标包联调招标公司","city":"成都市"}
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
                                  "username": "tenderer_pkg",
                                  "password": "InitPass1",
                                  "displayName": "招标用户",
                                  "phone": "13920000011",
                                  "email": "tenderer_pkg@example.com",
                                  "roleCodes": ["TENDERER"]
                                }
                                """.formatted(orgId)))
                .andExpect(status().isOk());

        String firstToken = login("tenderer_pkg", "InitPass1");
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"Stable9x"}
                                """))
                .andExpect(status().isOk());
        String tendererToken = login("tenderer_pkg", "Stable9x");

        // 平台管理员不能建招标包
        mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"TENDER","name":"不该成功的包"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        MvcResult created = mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"TENDER","name":"市政招标文件包"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("市政招标文件包"))
                .andExpect(jsonPath("$.data.type").value("TENDER"))
                .andExpect(jsonPath("$.data.orgId").value((int) orgId))
                .andExpect(jsonPath("$.data.projectId").doesNotExist())
                .andExpect(jsonPath("$.data.fileCount").value(0))
                .andReturn();
        long packageId = Long.parseLong(created.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        // 同组织重名
        mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"TENDER","name":"市政招标文件包"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        mockMvc.perform(get("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .param("type", "TENDER")
                        .param("unboundOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)));

        mockMvc.perform(put("/api/packages/" + packageId)
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"市政招标文件包-修订"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("市政招标文件包-修订"));

        mockMvc.perform(delete("/api/packages/" + packageId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/packages/" + packageId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
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
