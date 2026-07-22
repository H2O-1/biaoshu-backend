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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 投标包：先建后选绑到项目；同组织公司名唯一；已绑也可删。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BidPackageFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createThenBindBidPackageToProject() throws Exception {
        String adminToken = login("admin", "ChangeMe@123");

        MvcResult orgCreated = mockMvc.perform(post("/api/admin/orgs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"投标包联调公司","city":"成都市"}
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
                                  "username": "tenderer_bid",
                                  "password": "InitPass1",
                                  "displayName": "招标用户",
                                  "phone": "13920000021",
                                  "email": "tenderer_bid@example.com",
                                  "roleCodes": ["TENDERER"]
                                }
                                """.formatted(orgId)))
                .andExpect(status().isOk());

        String firstToken = login("tenderer_bid", "InitPass1");
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"Stable9x"}
                                """))
                .andExpect(status().isOk());
        String tendererToken = login("tenderer_bid", "Stable9x");

        // 招标包 + 项目
        MvcResult tenderPkg = mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"TENDER","name":"招标包-A"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long tenderPackageId = Long.parseLong(tenderPkg.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        MvcResult projectCreated = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"道路改造",
                                  "agency":"代理",
                                  "projectType":"公开招标",
                                  "leaderName":"张三",
                                  "tenderPackageId": %d
                                }
                                """.formatted(tenderPackageId)))
                .andExpect(status().isOk())
                .andReturn();
        long projectId = Long.parseLong(projectCreated.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        // 先建未绑投标包
        MvcResult bidCreated = mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"BID",
                                  "name":"华建集团",
                                  "contactName":"王五",
                                  "contactPhone":"13800001111"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("BID"))
                .andExpect(jsonPath("$.data.name").value("华建集团"))
                .andExpect(jsonPath("$.data.contactName").value("王五"))
                .andReturn();
        long bidPackageId = Long.parseLong(bidCreated.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .param("type", "BID")
                        .param("unboundOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)));

        // 项目里添加（绑定）
        mockMvc.perform(post("/api/projects/" + projectId + "/bid-packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bidPackageId": %d}
                                """.formatted(bidPackageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value((int) projectId));

        mockMvc.perform(get("/api/projects/" + projectId + "/bid-packages")
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        // 已绑投标包不能再绑别的项目：再建一项目，再绑应 409
        MvcResult tenderPkg2 = mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"TENDER","name":"招标包-B"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long tenderPackageId2 = Long.parseLong(tenderPkg2.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        MvcResult project2 = mockMvc.perform(post("/api/projects")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"另一项目",
                                  "agency":"代理",
                                  "projectType":"公开招标",
                                  "leaderName":"李四",
                                  "tenderPackageId": %d
                                }
                                """.formatted(tenderPackageId2)))
                .andExpect(status().isOk())
                .andReturn();
        long projectId2 = Long.parseLong(project2.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/projects/" + projectId2 + "/bid-packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bidPackageId": %d}
                                """.formatted(bidPackageId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        // 已绑也可直接删投标包
        mockMvc.perform(delete("/api/packages/" + bidPackageId)
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/" + projectId + "/bid-packages")
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
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
