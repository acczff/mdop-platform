package io.github.acczff.mdop.test.support;

import java.util.UUID;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * I0 阶段共享的真实基础设施测试基类。
 *
 * <p>所有容器使用固定镜像版本、随机宿主机端口和运行时临时凭据，
 * 不依赖本地 Compose、固定容器或 .env.local。同一测试类中的测试方法
 * 共享静态容器，并由 Testcontainers 在测试结束后自动清理。</p>
 */
@Testcontainers
public abstract class MdopInfrastructureTestBase {

    private static final int REDIS_PORT = 6379;
    private static final String REDIS_PASSWORD = randomCredential();

    /**
     * MySQL 参与 Spring Boot 服务连接，以便自动配置 DataSource 和 Flyway。
     */
    @Container @ServiceConnection
    protected static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.10")
                    .withDatabaseName("mdop_test")
                    .withUsername("mdop_" + randomCredential().substring(0, 12))
                    .withPassword(randomCredential());

    /**
     * 当前只验证 RabbitMQ 节点可用性，尚未接入消息生产、消费或业务事件。
     */
    @Container
    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.3.2-management")
                    .withAdminUser("mdop_" + randomCredential().substring(0, 12))
                    .withAdminPassword(randomCredential());

    /**
     * 当前只验证 Redis 可用性。REDISCLI_AUTH 使容器内 redis-cli 自动认证，
     * 测试断言无需读取或输出临时密码。
     */
    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.2.7")
                    .withExposedPorts(REDIS_PORT)
                    .withEnv("REDISCLI_AUTH", REDIS_PASSWORD)
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    /**
     * 生成仅在当前测试进程中使用的临时凭据，不读取本地环境配置。
     */
    private static String randomCredential() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
