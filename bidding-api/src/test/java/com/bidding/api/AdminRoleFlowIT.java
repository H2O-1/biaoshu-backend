package com.bidding.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminRoleFlowIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanAssignRoles_nonAdminForbidden() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"admin","password":"ChangeMe@123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false))
                .andExpect(jsonPath("$.data.orgCode").value("SYSTEM"))
                .andExpect(jsonPath("$.data.orgName").value("系统组织"))
                .andReturn();

        String body = login.getResponse().getContentAsString();
        String token = body.replaceAll("(?s).*\"accessToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgName").value("系统组织"))
                .andExpect(jsonPath("$.data.roleCodes[0]").value("PLATFORM_ADMIN"));

        mockMvc.perform(get("/api/admin/roles")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4));

        mockMvc.perform(get("/api/admin/roles"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRequiredForAdminApis() throws Exception {
        mockMvc.perform(put("/api/admin/users/1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleCodes":["BIDDER"]}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanCreateUser() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgId": 1,
                                  "username": "bidder01",
                                  "password": "Passw0rd!",
                                  "displayName": "投标用户01",
                                  "roleCodes": ["BIDDER"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("bidder01"))
                .andExpect(jsonPath("$.data.roleCodes[0]").value("BIDDER"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));
    }

    @Test
    void mustChangePasswordBlocksAdminApis_untilChanged() throws Exception {
        String adminToken = loginAsAdmin();

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgId": 1,
                                  "username": "force_pwd_user",
                                  "password": "InitPass1",
                                  "displayName": "强制改密用户",
                                  "roleCodes": ["BIDDER"]
                                }
                                """))
                .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"force_pwd_user","password":"InitPass1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andReturn();
        String userToken = extractToken(login);

        // 强制改密期间不能访问业务接口
        mockMvc.perform(get("/api/admin/roles")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"NewPass12"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 改密后旧 Token 失效
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"force_pwd_user","password":"NewPass12"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    }

    @Test
    void resetPasswordInvalidatesOldToken_andReturnsTempOnce() throws Exception {
        String adminToken = loginAsAdmin();

        mockMvc.perform(post("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orgId": 1,
                                  "username": "reset_pwd_user",
                                  "password": "InitPass1",
                                  "displayName": "重置用户",
                                  "roleCodes": ["BIDDER"]
                                }
                                """))
                .andExpect(status().isOk());

        // 先改密，拿可用 Token
        MvcResult firstLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"reset_pwd_user","password":"InitPass1"}
                                """))
                .andExpect(status().isOk())
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
                                {"username":"reset_pwd_user","password":"Stable9x"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false))
                .andReturn();
        String activeToken = extractToken(secondLogin);
        long userId = Long.parseLong(secondLogin.getResponse().getContentAsString()
                .replaceAll("(?s).*\"userId\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(post("/api/admin/users/" + userId + "/reset-password")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reset").value(true))
                .andExpect(jsonPath("$.data.userId").value((int) userId))
                .andExpect(jsonPath("$.data.temporaryPassword", notNullValue()))
                .andExpect(jsonPath("$.data.message").value("临时密码已生成，请立即复制保存，关闭后不可再查看"));

        // 旧会话失效
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + activeToken))
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
