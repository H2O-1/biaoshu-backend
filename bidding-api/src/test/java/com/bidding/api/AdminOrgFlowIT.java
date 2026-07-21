package com.bidding.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 组织 M1：创建 / 列表 / 详情 / 启停；城市必须从地级市词典选择。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminOrgFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanManageOrgs() throws Exception {
        String token = loginAsAdmin();

        // 城市选项接口
        mockMvc.perform(get("/api/admin/cities/provinces")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThan(20)))
                .andExpect(jsonPath("$.data.items[0]").isString());

        mockMvc.perform(get("/api/admin/cities")
                        .header("Authorization", "Bearer " + token)
                        .param("province", "广东省")
                        .param("keyword", "深圳"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThan(0)))
                .andExpect(jsonPath("$.data.items[0].name").value("深圳市"));

        // 短名「深圳」不在词典中，应拒绝
        mockMvc.perform(post("/api/admin/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"非法城市公司","city":"深圳"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        MvcResult created = mockMvc.perform(post("/api/admin/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"联调测试招标公司","city":"深圳市"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("联调测试招标公司"))
                .andExpect(jsonPath("$.data.city").value("深圳市"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.userCount").value(0))
                .andExpect(jsonPath("$.data.code", startsWith("ORG_")))
                .andReturn();

        long orgId = Long.parseLong(created.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/admin/orgs/" + orgId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("联调测试招标公司"));

        mockMvc.perform(get("/api/admin/orgs")
                        .header("Authorization", "Bearer " + token)
                        .param("name", "联调测试")
                        .param("city", "深圳市"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value((int) orgId));

        mockMvc.perform(put("/api/admin/orgs/" + orgId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        // 停用组织后不能再创建用户
        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgId": %d,
                                  "username": "org_disabled_user",
                                  "password": "Passw0rd!",
                                  "displayName": "停用组织用户",
                                  "phone": "13800138999",
                                  "email": "org_disabled@example.com",
                                  "roleCodes": ["TENDERER"]
                                }
                                """.formatted(orgId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // 系统组织不可停用
        mockMvc.perform(put("/api/admin/orgs/1/status")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        // 系统组织二级列表可见（至少有引导 admin）
        mockMvc.perform(get("/api/admin/orgs/1/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThan(0)))
                .andExpect(jsonPath("$.data.items[0].username").value("admin"));
    }

    @Test
    void orgUsersSecondLevelAndExcludeDeleted() throws Exception {
        String token = loginAsAdmin();

        MvcResult created = mockMvc.perform(post("/api/admin/orgs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"二级列表测试公司","city":"广州市"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long orgId = Long.parseLong(created.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgId": %d,
                                  "username": "org_user_a",
                                  "password": "Passw0rd!",
                                  "displayName": "组织用户A",
                                  "phone": "13910000001",
                                  "email": "org_user_a@example.com",
                                  "roleCodes": ["TENDERER"]
                                }
                                """.formatted(orgId)))
                .andExpect(status().isOk());

        MvcResult createdUser = mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgId": %d,
                                  "username": "org_user_b",
                                  "password": "Passw0rd!",
                                  "displayName": "组织用户B",
                                  "phone": "13910000002",
                                  "email": "org_user_b@example.com",
                                  "roleCodes": ["BIDDER"]
                                }
                                """.formatted(orgId)))
                .andExpect(status().isOk())
                .andReturn();
        long userIdB = Long.parseLong(createdUser.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/admin/orgs/" + orgId + "/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(get("/api/admin/orgs/" + orgId + "/users")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", "组织用户A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value("org_user_a"));

        // 删除后二级列表不再出现
        mockMvc.perform(delete("/api/admin/users/" + userIdB)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/orgs/" + orgId + "/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value("org_user_a"));
    }

    @Test
    void disableOrgRevokesUserSessionImmediately() throws Exception {
        String adminToken = loginAsAdmin();

        MvcResult created = mockMvc.perform(post("/api/admin/orgs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"停用吊销会话公司","city":"杭州市"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long orgId = Long.parseLong(created.getResponse().getContentAsString()
                .replaceAll("(?s).*\"id\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgId": %d,
                                  "username": "org_session_user",
                                  "password": "InitPass1",
                                  "displayName": "会话用户",
                                  "phone": "13910000011",
                                  "email": "org_session@example.com",
                                  "roleCodes": ["TENDERER"]
                                }
                                """.formatted(orgId)))
                .andExpect(status().isOk());

        // 首次登录后改密，拿到可用 Token
        MvcResult firstLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"org_session_user","password":"InitPass1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andReturn();
        String firstToken = extractToken(firstLogin);

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"Stable9x"}
                                """))
                .andExpect(status().isOk());

        MvcResult secondLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"org_session_user","password":"Stable9x"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false))
                .andReturn();
        String userToken = extractToken(secondLogin);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        // 停用组织 → 旧会话立即失效
        mockMvc.perform(put("/api/admin/orgs/" + orgId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized());

        // 用户 status 仍为 ACTIVE（组织启停不改账号状态）；登录因组织停用被拒
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"org_session_user","password":"Stable9x"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void createOrgRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/admin/orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"未登录公司","city":"北京市"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private String loginAsAdmin() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"ChangeMe@123"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return extractToken(login);
    }

    private static String extractToken(MvcResult login) throws Exception {
        return login.getResponse().getContentAsString()
                .replaceAll("(?s).*\"accessToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }
}
