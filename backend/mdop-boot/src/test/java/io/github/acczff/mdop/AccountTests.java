package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.acczff.mdop.security.OperatorProperties;
import io.github.acczff.mdop.system.identity.AccountService;
import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountTests extends MdopInfrastructureTestBase {
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(
            String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AccountService accounts;
    MockHttpSession admin;
    final String password = "Account-Test-Password-2026!";

    @BeforeEach
    void setup() throws Exception {
        admin = login("testadmin", "test-only-password-12345", 204);
    }

    MockHttpSession login(String username, String password, int status) throws Exception {
        var result =
                mvc.perform(
                                post("/api/auth/login")
                                        .with(csrf())
                                        .param("username", username)
                                        .param("password", password))
                        .andExpect(status().is(status))
                        .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    JsonNode post(String path, Object body, MockHttpSession session, int status) throws Exception {
        var result =
                mvc.perform(
                                post(path)
                                        .session(session)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsBytes(body)))
                        .andExpect(status().is(status))
                        .andReturn();
        return result.getResponse().getContentAsString().isBlank()
                ? null
                : json.readTree(result.getResponse().getContentAsString());
    }

    JsonNode create(String role) throws Exception {
        return create(Set.of(role), Set.of());
    }

    JsonNode create(Set<String> roles, Set<Long> warehouses) throws Exception {
        return post(
                "/api/iam/users",
                Map.of(
                        "username",
                        "u" + UUID.randomUUID().toString().replace("-", ""),
                        "displayName",
                        "验收用户",
                        "password",
                        password,
                        "roles",
                        roles,
                        "warehouseIds",
                        warehouses,
                        "reason",
                        "回归测试新增"),
                admin,
                201);
    }

    String path(JsonNode user, String action) {
        return "/api/iam/users/" + user.get("id").asLong() + "/" + action;
    }

    @Test
    void persistedUsersAndBootstrapDoNotOverwriteChanges() throws Exception {
        var u = create("READER");
        post(path(u, "status"), Map.of("version", 0, "enabled", false, "reason", "停用"), admin, 200);
        accounts.bootstrap("ignored", "bad", new OperatorProperties(List.of()));
        assertThat(accounts.loadUserByUsername(u.get("username").asText()).isEnabled()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM iam_user WHERE username='ignored'",
                                Long.class))
                .isZero();
    }

    @Test
    void systemManagementAndBusinessAreSeparated() throws Exception {
        mvc.perform(get("/api/iam/users").session(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/wms/stock?warehouseId=1").session(admin))
                .andExpect(status().isForbidden());
        var u = create("READER");
        var s = login(u.get("username").asText(), password, 204);
        mvc.perform(get("/api/iam/users").session(s)).andExpect(status().isForbidden());
        post("/api/master-data/suppliers", Map.of("code", "DENIED", "name", "越权"), s, 403);
        mvc.perform(get("/api/iam/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void rolePolicyUnknownWarehouseAndDuplicateNameAreRejected() throws Exception {
        for (var roles :
                List.of(
                        Set.of("SYSTEM_ADMIN", "READER"),
                        Set.of("WAREHOUSE_OPERATOR", "BUSINESS_REVIEWER"),
                        Set.of("SALES_OPERATOR", "SALES_REVIEWER"),
                        Set.of("ROLE_ADMIN")))
            post(
                    "/api/iam/users",
                    Map.of(
                            "username",
                            "invalid",
                            "displayName",
                            "用户",
                            "password",
                            password,
                            "roles",
                            roles,
                            "warehouseIds",
                            Set.of(),
                            "reason",
                            "测试"),
                    admin,
                    400);
        post(
                "/api/iam/users",
                Map.of(
                        "username",
                        "invalid",
                        "displayName",
                        "用户",
                        "password",
                        password,
                        "roles",
                        Set.of("READER"),
                        "warehouseIds",
                        Set.of(Long.MAX_VALUE),
                        "reason",
                        "测试"),
                admin,
                400);
        var u = create("READER");
        post(
                "/api/iam/users",
                Map.of(
                        "username",
                        u.get("username").asText().toUpperCase(Locale.ROOT),
                        "displayName",
                        "重复",
                        "password",
                        password,
                        "roles",
                        Set.of("READER"),
                        "warehouseIds",
                        Set.of(),
                        "reason",
                        "测试"),
                admin,
                409);
    }

    @Test
    void disablingAndReenablingNeverRevivesOldSessions() throws Exception {
        var u = create("READER");
        var name = u.get("username").asText();
        var s = login(name, password, 204);
        var another = login(name, password, 204);
        post(
                path(u, "status"),
                Map.of("version", 0, "enabled", false, "reason", "离职停用"),
                admin,
                200);
        login(name, password, 401);
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isUnauthorized());
        post(
                path(u, "status"),
                Map.of("version", 1, "enabled", true, "reason", "重新启用"),
                admin,
                200);
        mvc.perform(get("/api/auth/me").session(another)).andExpect(status().isUnauthorized());
        login(name, password, 204);
    }

    @Test
    void accessChangeRevokesOldSessionAndTakesEffectAtLogin() throws Exception {
        var u = create("MASTER_DATA");
        var name = u.get("username").asText();
        var s = login(name, password, 204);
        post(
                "/api/master-data/suppliers",
                Map.of("code", "S" + UUID.randomUUID().toString().substring(0, 8), "name", "供应商"),
                s,
                201);
        post(
                path(u, "access"),
                Map.of(
                        "version",
                        0,
                        "roles",
                        Set.of("READER"),
                        "warehouseIds",
                        Set.of(),
                        "reason",
                        "岗位调整"),
                admin,
                200);
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isUnauthorized());
        var next = login(name, password, 204);
        post("/api/master-data/suppliers", Map.of("code", "DENIED2", "name", "越权"), next, 403);
    }

    @Test
    void passwordResetRevokesAllSessionsAndNeverAuditsSecrets() throws Exception {
        var u = create("READER");
        var name = u.get("username").asText();
        var s = login(name, password, 204);
        var s2 = login(name, password, 204);
        var next = "Changed-Secret-2026!";
        post(
                path(u, "password"),
                Map.of("version", 0, "password", next, "reason", "本人申请重置"),
                admin,
                204);
        mvc.perform(get("/api/auth/me").session(s)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(s2)).andExpect(status().isUnauthorized());
        login(name, password, 401);
        login(name, next, 204);
        var audit =
                mvc.perform(get(path(u, "audit")).session(admin))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(java.time.Instant.parse(json.readTree(audit).get(0).get("createdAt").asText()))
                .isBetween(
                        java.time.Instant.now().minusSeconds(60),
                        java.time.Instant.now().plusSeconds(5));
        assertThat(audit)
                .contains("PASSWORD_RESET", "testadmin")
                .doesNotContain(password, next, "password_hash", "{bcrypt}");
    }

    @Test
    void changingOwnPasswordRequiresOldPasswordAndEndsSession() throws Exception {
        var u = create("READER");
        var name = u.get("username").asText();
        var s = login(name, password, 204);
        post(
                "/api/auth/password",
                Map.of("oldPassword", "wrong", "newPassword", "New-Password-2026!"),
                s,
                400);
        post("/api/auth/password", Map.of("oldPassword", password, "newPassword", "short"), s, 400);
        post(
                "/api/auth/password",
                Map.of("oldPassword", password, "newPassword", "New-Password-2026!"),
                s,
                204);
        assertThat(s.isInvalid()).isTrue();
        login(name, password, 401);
        login(name, "New-Password-2026!", 204);
    }

    @Test
    void optimisticConflictAndMissingCsrfCannotChangeAccount() throws Exception {
        var u = create("READER");
        var body = Map.of("version", 0, "enabled", false, "reason", "停用");
        mvc.perform(
                        post(path(u, "status"))
                                .session(admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsBytes(body)))
                .andExpect(status().isForbidden());
        post(path(u, "status"), body, admin, 200);
        post(
                path(u, "access"),
                Map.of(
                        "version",
                        0,
                        "roles",
                        Set.of("MASTER_DATA"),
                        "warehouseIds",
                        Set.of(),
                        "reason",
                        "过时修改"),
                admin,
                409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM iam_audit WHERE user_id=?",
                                Long.class,
                                u.get("id").asLong()))
                .isEqualTo(2);
    }

    @Test
    void lastAdministratorAndOwnRoleAreProtected() throws Exception {
        long id =
                db.queryForObject("SELECT id FROM iam_user WHERE username='testadmin'", Long.class);
        post(
                "/api/iam/users/" + id + "/status",
                Map.of("version", 0, "enabled", false, "reason", "测试最后管理员"),
                admin,
                409);
        post(
                "/api/iam/users/" + id + "/access",
                Map.of(
                        "version",
                        0,
                        "roles",
                        Set.of("READER"),
                        "warehouseIds",
                        Set.of(),
                        "reason",
                        "自改权限"),
                admin,
                409);
        assertThat(accounts.loadUserByUsername("testadmin").getAuthorities())
                .extracting(Object::toString)
                .containsExactly("iam:manage");
    }

    @Test
    void concurrentUpdatesUseOneVersionAndOneAudit() throws Exception {
        var u = create("READER");
        var other = login("testadmin", "test-only-password-12345", 204);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (var session : List.of(admin, other))
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return mvc.perform(
                                                    post(path(u, "status"))
                                                            .session(session)
                                                            .with(csrf())
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(
                                                                    json.writeValueAsBytes(
                                                                            Map.of(
                                                                                    "version", 0,
                                                                                    "enabled",
                                                                                    false, "reason",
                                                                                    "并发停用"))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            start.countDown();
            assertThat(
                            List.of(
                                    futures.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS),
                                    futures.get(1).get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM iam_audit WHERE user_id=?",
                                Long.class,
                                u.get("id").asLong()))
                .isEqualTo(2);
    }
}
