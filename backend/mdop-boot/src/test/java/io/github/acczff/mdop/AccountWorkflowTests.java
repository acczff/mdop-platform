package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountWorkflowTests extends InventoryScenarioSupport {
    final String password = "Workflow-Password-2026!";

    JsonNode account(String role, Set<Long> scope) throws Exception {
        return postAs(
                "/api/iam/users",
                Map.of(
                        "username",
                        "w" + key().replace("-", ""),
                        "displayName",
                        "流程验收",
                        "password",
                        password,
                        "roles",
                        Set.of(role),
                        "warehouseIds",
                        scope,
                        "reason",
                        "流程测试"),
                user("manager").authorities(new SimpleGrantedAuthority("iam:manage")),
                201);
    }

    MockHttpSession login(JsonNode account) throws Exception {
        return (MockHttpSession)
                mvc.perform(
                                post("/api/auth/login")
                                        .with(csrf())
                                        .param(
                                                "username",
                                                account.get("username")
                                                        .asText()
                                                        .toUpperCase(Locale.ROOT))
                                        .param("password", password))
                        .andExpect(status().isNoContent())
                        .andReturn()
                        .getRequest()
                        .getSession(false);
    }

    JsonNode send(String path, Object body, MockHttpSession session, int expected)
            throws Exception {
        var result =
                mvc.perform(
                                post(path)
                                        .session(session)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsBytes(body)))
                        .andExpect(status().is(expected))
                        .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void realAccountsKeepWarehouseScopeAndSeparateReviewerEvenAfterRoleChange() throws Exception {
        long balance = ready()[0];
        var operator = account("WAREHOUSE_OPERATOR", Set.of(warehouse));
        var reviewer = account("BUSINESS_REVIEWER", Set.of(warehouse));
        var outsider = account("BUSINESS_REVIEWER", Set.of());
        var opSession = login(operator);
        var freeze =
                send(
                        "/api/v1/wms/freezes",
                        Map.of("balanceId", balance, "idempotencyKey", key(), "reason", "待复核"),
                        opSession,
                        201);
        long id = freeze.get("id").asLong();
        send(
                "/api/v1/wms/freezes/" + id + "/release",
                Map.of("idempotencyKey", key(), "reason", "越权"),
                login(outsider),
                403);
        postAs(
                "/api/iam/users/" + operator.get("id").asLong() + "/access",
                Map.of(
                        "version",
                        0,
                        "roles",
                        Set.of("BUSINESS_REVIEWER"),
                        "warehouseIds",
                        Set.of(warehouse),
                        "reason",
                        "变更角色仍不能自审"),
                user("manager").authorities(new SimpleGrantedAuthority("iam:manage")),
                200);
        mvc.perform(get("/api/auth/me").session(opSession)).andExpect(status().isUnauthorized());
        send(
                "/api/v1/wms/freezes/" + id + "/release",
                Map.of("idempotencyKey", key(), "reason", "禁止本人自审"),
                login(operator),
                409);
        var released =
                send(
                        "/api/v1/wms/freezes/" + id + "/release",
                        Map.of("idempotencyKey", key(), "reason", "另一审批人复核"),
                        login(reviewer),
                        200);
        assertThat(released.get("status").asText()).isEqualTo("RELEASED");
        assertThat(stock(balance, "available_qty")).isEqualByComparingTo("80");
    }
}
