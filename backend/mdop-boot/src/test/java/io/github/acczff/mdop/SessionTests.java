package io.github.acczff.mdop;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionTests extends MdopInfrastructureTestBase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static Cookie sessionCookie(MvcResult result, Cookie fallback) {
        Cookie cookie = result.getResponse().getCookie("SESSION");
        if (cookie == null) cookie = result.getResponse().getCookie("JSESSIONID");
        return cookie != null ? cookie : fallback;
    }

    @Test
    void realLoginRotatesCsrfAndLogoutInvalidatesSession() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        var initial = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie session = sessionCookie(initial, null);
        var token = json.readTree(initial.getResponse().getContentAsString());
        var loginWithoutCsrf =
                session != null ? post("/api/auth/login").cookie(session) : post("/api/auth/login");
        mvc.perform(
                        loginWithoutCsrf
                                .param("username", "testadmin")
                                .param("password", "test-only-password-12345"))
                .andExpect(status().isForbidden());
        var wrongPass =
                session != null ? post("/api/auth/login").cookie(session) : post("/api/auth/login");
        var wrongResult =
                mvc.perform(
                                wrongPass
                                        .header(
                                                token.get("headerName").asText(),
                                                token.get("token").asText())
                                        .param("username", "testadmin")
                                        .param("password", "wrong"))
                        .andExpect(status().isUnauthorized())
                        .andReturn();
        session = sessionCookie(wrongResult, session);
        var loginReq =
                session != null ? post("/api/auth/login").cookie(session) : post("/api/auth/login");
        var loginResult =
                mvc.perform(
                                loginReq.header(
                                                token.get("headerName").asText(),
                                                token.get("token").asText())
                                        .param("username", "testadmin")
                                        .param("password", "test-only-password-12345"))
                        .andExpect(status().isNoContent())
                        .andReturn();
        session = sessionCookie(loginResult, session);
        mvc.perform(get("/api/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testadmin"));
        mvc.perform(
                        post("/api/auth/logout")
                                .cookie(session)
                                .header(
                                        token.get("headerName").asText(),
                                        token.get("token").asText()))
                .andExpect(status().isForbidden());
        var renewed = mvc.perform(get("/api/auth/csrf").cookie(session)).andReturn();
        var next = json.readTree(renewed.getResponse().getContentAsString());
        mvc.perform(
                        post("/api/auth/logout")
                                .cookie(session)
                                .header(
                                        next.get("headerName").asText(),
                                        next.get("token").asText()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedLoginFailuresAreThrottled() throws Exception {
        var initial = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie session = sessionCookie(initial, null);
        var token = json.readTree(initial.getResponse().getContentAsString());
        for (int attempt = 1; attempt <= 5; attempt++) {
            var req =
                    session != null
                            ? post("/api/auth/login").cookie(session)
                            : post("/api/auth/login");
            var res =
                    mvc.perform(
                                    req.header(
                                                    token.get("headerName").asText(),
                                                    token.get("token").asText())
                                            .param("username", "throttle-probe")
                                            .param("password", "wrong-" + attempt))
                            .andExpect(status().isUnauthorized())
                            .andReturn();
            session = sessionCookie(res, session);
        }
        var blockedReq =
                session != null ? post("/api/auth/login").cookie(session) : post("/api/auth/login");
        mvc.perform(
                        blockedReq
                                .header(
                                        token.get("headerName").asText(),
                                        token.get("token").asText())
                                .param("username", "throttle-probe")
                                .param("password", "wrong-again"))
                .andExpect(status().isTooManyRequests());
    }
}
