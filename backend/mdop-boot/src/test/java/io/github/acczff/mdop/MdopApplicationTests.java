package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MdopApplicationTests extends MdopInfrastructureTestBase {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private MockMvc mockMvc;

    @Autowired private Environment environment;

    @BeforeEach
    void clearWarehouseData() {
        jdbcTemplate.update("DELETE FROM mdm_warehouse");
    }

    @Test
    void warehouseSearchShouldApplyFiltersAndExcludeSoftDeletedRows() throws Exception {
        insertWarehouse("WH-RAW-01", "常规原料仓", "RAW_MATERIAL", "GENERAL", "ENABLED");

        insertWarehouse("WH-RAW-02", "危险原料仓", "RAW_MATERIAL", "HAZARDOUS_CHEMICAL", "DISABLED");

        insertWarehouse("WH-FG-01", "成品仓", "FINISHED_GOODS", "GENERAL", "ENABLED");

        long deletedId =
                insertWarehouse("WH-RAW-03", "已删除原料仓", "RAW_MATERIAL", "GENERAL", "ENABLED");
        markWarehouseDeleted(deletedId);

        mockMvc.perform(
                        get("/api/master-data/warehouses")
                                .with(user("i1-api-test"))
                                .param("keyword", "原料")
                                .param("purpose", "RAW_MATERIAL")
                                .param("status", "ENABLED")
                                .param("page", "1")
                                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].code").value("WH-RAW-01"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(
                        get("/api/master-data/warehouses")
                                .with(user("i1-api-test"))
                                .param("keyword", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void warehouseSearchShouldApplyFormAndManagementCategoryFilters() throws Exception {
        insertWarehouse(
                "WH-PHY-HZ-01",
                "实体危险物料仓",
                "RAW_MATERIAL",
                "PHYSICAL",
                "HAZARDOUS_CHEMICAL",
                "ENABLED");
        insertWarehouse("WH-LOG-GEN-01", "通用逻辑仓", "SEMI_FINISHED", "LOGICAL", "GENERAL", "ENABLED");
        insertWarehouse(
                "WH-LOG-HZ-01",
                "危险物料逻辑仓",
                "SEMI_FINISHED",
                "LOGICAL",
                "HAZARDOUS_CHEMICAL",
                "DISABLED");

        mockMvc.perform(
                        get("/api/master-data/warehouses")
                                .with(user("i1-api-test"))
                                .param("form", "LOGICAL")
                                .param("managementCategory", "HAZARDOUS_CHEMICAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].code").value("WH-LOG-HZ-01"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void warehouseDetailShouldReturnActiveWarehouse() throws Exception {
        long id = insertWarehouse("WH-RAW-01", "常规原料仓", "RAW_MATERIAL", "GENERAL", "ENABLED");

        mockMvc.perform(get("/api/master-data/warehouses/{id}", id).with(user("i1-api-test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.code").value("WH-RAW-01"))
                .andExpect(jsonPath("$.name").value("常规原料仓"))
                .andExpect(jsonPath("$.purpose").value("RAW_MATERIAL"))
                .andExpect(jsonPath("$.form").value("PHYSICAL"))
                .andExpect(jsonPath("$.managementCategory").value("GENERAL"))
                .andExpect(jsonPath("$.status").value("ENABLED"))
                .andExpect(jsonPath("$.version").value(0));
    }

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
    void flywayShouldApplyWarehouseMigrationOnEmptyDatabase() {
        Integer warehouseTableCount =
                jdbcTemplate.queryForObject(
                        """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name = 'mdm_warehouse'
                    """,
                        Integer.class);

        Integer successfulMigrationCount =
                jdbcTemplate.queryForObject(
                        """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = '202607210001'
                      AND success = 1
                    """,
                        Integer.class);

        assertThat(warehouseTableCount).isEqualTo(1);
        assertThat(successfulMigrationCount).isEqualTo(1);
    }

    @Test
    void warehouseDatabaseShouldEnforceCodeUniqueness() {
        insertWarehouse(
                "WH-DB-DUP-01", "数据库唯一约束仓", "RAW_MATERIAL", "PHYSICAL", "GENERAL", "ENABLED");

        assertThatThrownBy(
                        () ->
                                insertWarehouse(
                                        "WH-DB-DUP-01",
                                        "重复编码仓",
                                        "RAW_MATERIAL",
                                        "PHYSICAL",
                                        "GENERAL",
                                        "ENABLED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void warehouseDatabaseShouldEnforceEnumChecks() {
        assertWarehouseInsertRejected(
                "WH-DB-PURPOSE",
                "UNKNOWN",
                "PHYSICAL",
                "GENERAL",
                "ENABLED",
                "ck_mdm_warehouse_purpose");
        assertWarehouseInsertRejected(
                "WH-DB-FORM",
                "RAW_MATERIAL",
                "UNKNOWN",
                "GENERAL",
                "ENABLED",
                "ck_mdm_warehouse_form");
        assertWarehouseInsertRejected(
                "WH-DB-CATEGORY",
                "RAW_MATERIAL",
                "PHYSICAL",
                "UNKNOWN",
                "ENABLED",
                "ck_mdm_warehouse_management_category");
        assertWarehouseInsertRejected(
                "WH-DB-STATUS",
                "RAW_MATERIAL",
                "PHYSICAL",
                "GENERAL",
                "UNKNOWN",
                "ck_mdm_warehouse_status");
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
        mockMvc.perform(get("/actuator/info")).andExpect(status().isForbidden());
    }

    @Test
    void infoEndpointShouldAllowAuthenticatedRequest() throws Exception {
        mockMvc.perform(get("/actuator/info").with(user("i0-security-test")))
                .andExpect(status().isOk());
    }

    @Test
    void warehouseCreateShouldRequireCsrfAndPersistAuthenticatedActor() throws Exception {
        String requestBody =
                """
            {
              "code": "WH-RAW-10",
              "name": "电子元件原料仓",
              "purpose": "RAW_MATERIAL",
              "form": "PHYSICAL",
              "managementCategory": "GENERAL",
              "remark": "常规电子元件储存"
            }
            """;

        mockMvc.perform(
                        post("/api/master-data/warehouses")
                                .with(user("i1-api-test"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isForbidden());

        var result =
                mockMvc.perform(
                                post("/api/master-data/warehouses")
                                        .with(user("i1-api-test"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(requestBody))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.code").value("WH-RAW-10"))
                        .andExpect(jsonPath("$.status").value("ENABLED"))
                        .andExpect(jsonPath("$.version").value(0))
                        .andExpect(jsonPath("$.createdBy").value("i1-api-test"))
                        .andReturn();

        Long id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM mdm_warehouse WHERE code = ?", Long.class, "WH-RAW-10");

        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/master-data/warehouses/" + id);

        String createdBy =
                jdbcTemplate.queryForObject(
                        "SELECT created_by FROM mdm_warehouse WHERE id = ?", String.class, id);

        assertThat(createdBy).isEqualTo("i1-api-test");
    }

    @Test
    void warehouseUpdateShouldChangeBasicInformationAndIncrementVersion() throws Exception {
        long id = insertWarehouse("WH-RAW-11", "原料仓", "RAW_MATERIAL", "GENERAL", "ENABLED");

        mockMvc.perform(
                        put("/api/master-data/warehouses/{id}", id)
                                .with(user("i1-api-test"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "name": "电子元件常规原料仓",
                          "purpose": "RAW_MATERIAL",
                          "form": "PHYSICAL",
                          "managementCategory": "GENERAL",
                          "remark": "名称与备注调整",
                          "version": 0
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("WH-RAW-11"))
                .andExpect(jsonPath("$.name").value("电子元件常规原料仓"))
                .andExpect(jsonPath("$.remark").value("名称与备注调整"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedBy").value("i1-api-test"));

        Long storedVersion =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM mdm_warehouse WHERE id = ?", Long.class, id);

        assertThat(storedVersion).isEqualTo(1L);
    }

    @Test
    void warehouseStructureUpdateShouldSucceedAfterDisable() throws Exception {
        long id = insertWarehouse("WH-RAW-12", "待调整原料仓", "RAW_MATERIAL", "GENERAL", "ENABLED");

        mockMvc.perform(
                        put("/api/master-data/warehouses/{id}/status", id)
                                .with(user("i1-api-test"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "status": "DISABLED",
                          "version": 0
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(
                        put("/api/master-data/warehouses/{id}", id)
                                .with(user("i1-api-test"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "name": "逻辑半成品仓",
                          "purpose": "SEMI_FINISHED",
                          "form": "LOGICAL",
                          "managementCategory": "GENERAL",
                          "remark": "停用后调整结构属性",
                          "version": 1
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("SEMI_FINISHED"))
                .andExpect(jsonPath("$.form").value("LOGICAL"))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void structureUpdateWhileEnabledShouldReturnConflictWithoutPartialUpdate() throws Exception {
        long id = insertWarehouse("WH-CONFLICT-01", "启用仓库", "RAW_MATERIAL", "GENERAL", "ENABLED");

        mockMvc.perform(
                        put("/api/master-data/warehouses/{id}", id)
                                .with(user("i1-api-test"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "name": "不应保存的新名称",
                          "purpose": "SEMI_FINISHED",
                          "form": "PHYSICAL",
                          "managementCategory": "GENERAL",
                          "version": 0
                        }
                        """))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code").value("WAREHOUSE_STRUCTURE_CHANGE_REQUIRES_DISABLED"));

        String storedName =
                jdbcTemplate.queryForObject(
                        "SELECT name FROM mdm_warehouse WHERE id = ?", String.class, id);
        String storedPurpose =
                jdbcTemplate.queryForObject(
                        "SELECT purpose FROM mdm_warehouse WHERE id = ?", String.class, id);
        Long storedVersion =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM mdm_warehouse WHERE id = ?", Long.class, id);

        assertThat(storedName).isEqualTo("启用仓库");
        assertThat(storedPurpose).isEqualTo("RAW_MATERIAL");
        assertThat(storedVersion).isZero();
    }

    @Test
    void staleWarehouseVersionShouldReturnConflictWithoutModification() throws Exception {
        long id = insertWarehouse("WH-CONFLICT-02", "版本冲突仓库", "RAW_MATERIAL", "GENERAL", "ENABLED");

        mockMvc.perform(
                        put("/api/master-data/warehouses/{id}", id)
                                .with(user("i1-api-test"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "name": "不应保存的名称",
                          "purpose": "RAW_MATERIAL",
                          "form": "PHYSICAL",
                          "managementCategory": "GENERAL",
                          "version": 1
                        }
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_VERSION_CONFLICT"));

        String storedName =
                jdbcTemplate.queryForObject(
                        "SELECT name FROM mdm_warehouse WHERE id = ?", String.class, id);
        Long storedVersion =
                jdbcTemplate.queryForObject(
                        "SELECT version FROM mdm_warehouse WHERE id = ?", Long.class, id);

        assertThat(storedName).isEqualTo("版本冲突仓库");
        assertThat(storedVersion).isZero();
    }

    @Test
    void invalidEnumAndPaginationShouldReturnValidationProblem() throws Exception {
        mockMvc.perform(
                        get("/api/master-data/warehouses")
                                .with(user("i1-api-test"))
                                .param("purpose", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(
                        get("/api/master-data/warehouses")
                                .with(user("i1-api-test"))
                                .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        long id = insertWarehouse("WH-ENUM-01", "枚举校验仓库", "RAW_MATERIAL", "GENERAL", "ENABLED");

        mockMvc.perform(
                        put("/api/master-data/warehouses/{id}/status", id)
                                .with(user("i1-api-test"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "status": "UNKNOWN",
                          "version": 0
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void duplicateWarehouseCodeShouldReturnConflictProblem() throws Exception {
        insertWarehouse("WH-DUP-01", "已有仓库", "RAW_MATERIAL", "GENERAL", "ENABLED");

        mockMvc.perform(
                        post("/api/master-data/warehouses")
                                .with(user("i1-api-test"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "code": "WH-DUP-01",
                          "name": "重复编码仓库",
                          "purpose": "RAW_MATERIAL",
                          "form": "PHYSICAL",
                          "managementCategory": "GENERAL"
                        }
                        """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("仓库编码冲突"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("WAREHOUSE_CODE_DUPLICATE"));
    }

    @Test
    void warehouseMissingOrSoftDeletedShouldReturnNotFoundProblem() throws Exception {
        mockMvc.perform(get("/api/master-data/warehouses/{id}", 999999L).with(user("i1-api-test")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));

        long deletedId =
                insertWarehouse("WH-DELETED-01", "已删除仓库", "RAW_MATERIAL", "GENERAL", "ENABLED");
        markWarehouseDeleted(deletedId);

        mockMvc.perform(
                        get("/api/master-data/warehouses/{id}", deletedId)
                                .with(user("i1-api-test")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_NOT_FOUND"));
    }

    @Test
    void warehouseValidationShouldReturnProblemDetail() throws Exception {
        mockMvc.perform(
                        post("/api/master-data/warehouses")
                                .with(user("i1-api-test"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                        {
                          "code": "wh",
                          "name": " ",
                          "purpose": "RAW_MATERIAL",
                          "form": "PHYSICAL",
                          "managementCategory": "GENERAL"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("请求参数校验失败"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.instance").value("/api/master-data/warehouses"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    private long insertWarehouse(
            String code, String name, String purpose, String managementCategory, String status) {
        return insertWarehouse(code, name, purpose, "PHYSICAL", managementCategory, status);
    }

    private long insertWarehouse(
            String code,
            String name,
            String purpose,
            String form,
            String managementCategory,
            String status) {
        jdbcTemplate.update(
                """
                INSERT INTO mdm_warehouse
                  (
                    code,
                    name,
                    purpose,
                    form,
                    management_category,
                    status,
                    remark,
                    version,
                    created_by,
                    created_at,
                    updated_by,
                    updated_at,
                    deleted_by,
                    deleted_at
                  )
                VALUES
                  (?, ?, ?, ?, ?, ?, NULL, 0,
                   'integration-test', UTC_TIMESTAMP(6),
                   'integration-test', UTC_TIMESTAMP(6),
                   NULL, NULL)
                """,
                code,
                name,
                purpose,
                form,
                managementCategory,
                status);

        return jdbcTemplate.queryForObject(
                "SELECT id FROM mdm_warehouse WHERE code = ?", Long.class, code);
    }

    private void assertWarehouseInsertRejected(
            String code,
            String purpose,
            String form,
            String managementCategory,
            String status,
            String expectedConstraint) {
        assertThatThrownBy(
                        () ->
                                insertWarehouse(
                                        code,
                                        "数据库枚举约束仓",
                                        purpose,
                                        form,
                                        managementCategory,
                                        status))
                .isInstanceOfSatisfying(
                        UncategorizedSQLException.class,
                        exception ->
                                assertThat(exception.getSQLException().getMessage())
                                        .contains(expectedConstraint));
    }

    private void markWarehouseDeleted(long id) {
        jdbcTemplate.update(
                """
            UPDATE mdm_warehouse
            SET deleted_by = 'integration-test',
                deleted_at = UTC_TIMESTAMP(6)
            WHERE id = ?
            """,
                id);
    }
}
