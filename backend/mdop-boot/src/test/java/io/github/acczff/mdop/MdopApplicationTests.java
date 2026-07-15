package io.github.acczff.mdop;

import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MdopApplicationTests extends MdopInfrastructureTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @Test
    void testProfileShouldBeActive() {
        assertThat(environment.getActiveProfiles()).contains("test");
    }

    @Test
    void healthEndpointShouldReturnUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components").exists());
    }

    @Test
    void flywayShouldCreateSchemaHistoryOnEmptyDatabase() {
        // I0 尚无业务迁移：历史表必须存在，正式迁移记录必须保持为零。
        Integer tableCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name = 'flyway_schema_history'
            """, Integer.class);

        Integer migrationCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history",
            Integer.class
        );

        assertThat(tableCount).isEqualTo(1);
        assertThat(migrationCount).isZero();
    }

    @Test
    void rabbitMqShouldStartAndRespond() throws IOException, InterruptedException {
        // 只验证真实节点能够响应，不提前发送或消费业务消息。
        var result = RABBITMQ.execInContainer("rabbitmq-diagnostics", "-q", "ping");

        assertThat(result.getExitCode()).isZero();
        assertThat(RABBITMQ.getAmqpPort()).isPositive();
        assertThat(RABBITMQ.getAdminUsername()).startsWith("mdop_");
    }

    @Test
    void redisShouldStartAndRespond() throws IOException, InterruptedException {
        // 基类已通过 REDISCLI_AUTH 提供认证，命令中不需要暴露临时密码。
        var result = REDIS.execInContainer("redis-cli", "ping");

        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout()).contains("PONG");
        assertThat(REDIS.getFirstMappedPort()).isPositive();
    }

    @Test
    void infoEndpointShouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/actuator/info"))
            .andExpect(status().isForbidden());
    }

    @Test
    void infoEndpointShouldAllowAuthenticatedRequest() throws Exception {
        mockMvc.perform(get("/actuator/info").with(user("i0-security-test")))
            .andExpect(status().isOk());
    }
}
