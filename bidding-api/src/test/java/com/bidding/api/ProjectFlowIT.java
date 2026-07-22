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
 * 项目 CRUD：招标方可操作；平台管理员 403。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ProjectFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void tendererCanCrudProject_adminForbidden() throws Exception {
        String adminToken = login("admin", "ChangeMe@123");

        // 建组织 + 招标方账号
        MvcResult orgCreated = mockMvc.perform(post("/api/admin/orgs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"项目联调招标公司","city":"成都市"}
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
                                  "username": "tenderer_proj",
                                  "password": "InitPass1",
                                  "displayName": "招标用户",
                                  "phone": "13920000001",
                                  "email": "tenderer_proj@example.com",
                                  "roleCodes": ["TENDERER"]
                                }
                                """.formatted(orgId)))
                .andExpect(status().isOk());

        // 招标方首次改密
        String firstToken = login("tenderer_proj", "InitPass1");
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"Stable9x"}
                                """))
                .andExpect(status().isOk());
        String tendererToken = login("tenderer_proj", "Stable9x");

        // 先建未绑定招标包
        MvcResult pkgCreated = mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"TENDER","name":"市政招标文件包"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long tenderPackageId = Long.parseLong(pkgCreated.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        // 平台管理员不能建项目
        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"不该成功的项目",
                                  "agency":"代理A",
                                  "projectType":"公开招标",
                                  "leaderName":"张三",
                                  "tenderPackageId": %d
                                }
                                """.formatted(tenderPackageId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        MvcResult created = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"市政道路改造工程",
                                  "agency":"某某招标代理",
                                  "projectType":"公开招标",
                                  "leaderName":"张三",
                                  "tenderPackageId": %d
                                }
                                """.formatted(tenderPackageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("市政道路改造工程"))
                .andExpect(jsonPath("$.data.orgId").value((int) orgId))
                .andExpect(jsonPath("$.data.tenderPackageId").value((int) tenderPackageId))
                .andExpect(jsonPath("$.data.tenderPackageCount").value(1))
                .andReturn();
        long projectId = Long.parseLong(created.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        // 已绑定的招标包不可再删
        mockMvc.perform(delete("/api/packages/" + tenderPackageId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        // 同组织重名（再准备一个未绑包）
        MvcResult pkg2 = mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"TENDER","name":"第二招标包"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long tenderPackageId2 = Long.parseLong(pkg2.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"市政道路改造工程",
                                  "agency":"代理B",
                                  "projectType":"邀请招标",
                                  "leaderName":"李四",
                                  "tenderPackageId": %d
                                }
                                """.formatted(tenderPackageId2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        // 已占用的招标包不能再绑新项目
        mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"另一个项目",
                                  "agency":"代理B",
                                  "projectType":"邀请招标",
                                  "leaderName":"李四",
                                  "tenderPackageId": %d
                                }
                                """.formatted(tenderPackageId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)));

        mockMvc.perform(put("/api/projects/" + projectId + "/status")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        // 停用后仍可改名
        mockMvc.perform(put("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"市政道路改造工程-修订",
                                  "agency":"某某招标代理",
                                  "projectType":"公开招标",
                                  "leaderName":"张三"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("市政道路改造工程-修订"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        // 停用后列表仍可见
        mockMvc.perform(get("/api/projects")
                        .header("Authorization", "Bearer " + tendererToken)
                        .param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)));

        mockMvc.perform(delete("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/" + projectId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        // 组织 projectCount：删后为 0
        mockMvc.perform(get("/api/admin/orgs/" + orgId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectCount").value(0));
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
