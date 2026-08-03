package com.bidding.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 投标包：创建项目时一并绑定；同组织公司名唯一；已绑也可删。
 * 单独 POST .../bid-packages 仍可用（已标废弃）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BidPackageFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createProjectBindsBidPackages_andDeprecatedBindStillWorks() throws Exception {
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
                .andReturn();
        long bidPackageId = Long.parseLong(bidCreated.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        MvcResult bidCreated2 = mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"BID",
                                  "name":"中建一局",
                                  "contactName":"赵六",
                                  "contactPhone":"13800002222"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long bidPackageId2 = Long.parseLong(bidCreated2.getResponse().getContentAsString()
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
                                  "tenderPackageId": %d,
                                  "bidPackageIds": [%d, %d]
                                }
                                """.formatted(tenderPackageId, bidPackageId, bidPackageId2)))
                .andExpect(status().isOk())
                .andReturn();
        long projectId = Long.parseLong(projectCreated.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/projects/" + projectId + "/bid-packages")
                        .header("Authorization", "Bearer " + tendererToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        // 已绑投标包不能再绑别的项目：再建一项目（需新投标包），再绑应 409
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

        MvcResult bid3 = mockMvc.perform(post("/api/packages")
                        .header("Authorization", "Bearer " + tendererToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type":"BID",
                                  "name":"补绑投标公司",
                                  "contactName":"钱七",
                                  "contactPhone":"13800003333"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long bidPackageId3 = Long.parseLong(bid3.getResponse().getContentAsString()
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
                                  "tenderPackageId": %d,
                                  "bidPackageIds": [%d]
                                }
                                """.formatted(tenderPackageId2, bidPackageId3)))
                .andExpect(status().isOk())
                .andReturn();
        long projectId2 = Long.parseLong(project2.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        // 废弃接口仍可用：把已绑包再绑到别的项目 → 409
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
                .andExpect(jsonPath("$.data.total").value(1));
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
