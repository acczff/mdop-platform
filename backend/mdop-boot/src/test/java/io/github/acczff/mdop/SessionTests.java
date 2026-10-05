package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionTests extends MdopInfrastructureTestBase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void realLoginRotatesCsrfAndLogoutInvalidatesSession() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        var initial = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) initial.getRequest().getSession();
        var token = json.readTree(initial.getResponse().getContentAsString());
        mvc.perform(
                        post("/api/auth/login")
                                .session(session)
                                .param("username", "testadmin")
                                .param("password", "test-only-password-12345"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/auth/login")
                                .session(session)
                                .header(
                                        token.get("headerName").asText(),
                                        token.get("token").asText())
                                .param("username", "testadmin")
                                .param("password", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/auth/login")
                                .session(session)
                                .header(
                                        token.get("headerName").asText(),
                                        token.get("token").asText())
                                .param("username", "testadmin")
                                .param("password", "test-only-password-12345"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testadmin"));
        mvc.perform(
                        post("/api/auth/logout")
                                .session(session)
                                .header(
                                        token.get("headerName").asText(),
                                        token.get("token").asText()))
                .andExpect(status().isForbidden());
        var renewed = mvc.perform(get("/api/auth/csrf").session(session)).andReturn();
        var next = json.readTree(renewed.getResponse().getContentAsString());
        mvc.perform(
                        post("/api/auth/logout")
                                .session(session)
                                .header(
                                        next.get("headerName").asText(),
                                        next.get("token").asText()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }
}
