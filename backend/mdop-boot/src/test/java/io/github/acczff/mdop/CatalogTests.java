package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogTests extends MdopInfrastructureTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired CatalogService catalog;
    long unit, material, supplier, warehouse;
    String unitName;

    @BeforeEach
    void setup() throws Exception {
        unitName = "件" + key().substring(0, 7);
        unit = create("units", Map.of("code", code(), "name", unitName)).get("id").asLong();
        supplier = create("suppliers", Map.of("code", code(), "name", "原供应商")).get("id").asLong();
        material =
                create(
                                "materials",
                                Map.of(
                                        "code",
                                        code(),
                                        "name",
                                        "原物料",
                                        "unitId",
                                        unit,
                                        "trackingMode",
                                        "BATCH"))
                        .get("id")
                        .asLong();
        warehouse = warehouse("RAW_MATERIAL");
    }

    @Test
    void concurrentCreationAndEditingHaveOneWinnerAndOneAudit() throws Exception {
        var body = Map.of("code", code(), "name", "客户");
        assertThat(
                        race(
                                () ->
                                        mvc.perform(
                                                        admin(post("/api/master-data/customers"))
                                                                .content(
                                                                        json.writeValueAsString(
                                                                                body)))
                                                .andReturn()
                                                .getResponse()
                                                .getStatus()))
                .containsExactlyInAnyOrder(201, 409);
        long id =
                db.queryForObject(
                        "SELECT id FROM mdm_customer WHERE code=?", Long.class, body.get("code"));
        var edit = Map.of("version", 0, "name", "客户改名", "status", "DISABLED", "reason", "合并业务");
        assertThat(
                        race(
                                () ->
                                        mvc.perform(
                                                        admin(
                                                                        put(
                                                                                "/api/master-data/customers/"
                                                                                        + id))
                                                                .content(
                                                                        json.writeValueAsString(
                                                                                edit)))
                                                .andReturn()
                                                .getResponse()
                                                .getStatus()))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(read("/api/master-data/customers/" + id + "/history").size()).isEqualTo(2);
        assertThatThrownBy(() -> catalog.referenceCustomer(id)).hasMessageContaining("停用");
        assertThat(db.queryForObject("SELECT version FROM mdm_customer WHERE id=?", Long.class, id))
                .isEqualTo(1);
    }

    @Test
    void missingVersionReasonAndPermissionsCannotChangeData() throws Exception {
        edit(
                "suppliers",
                supplier,
                Map.of("name", "错误", "status", "DISABLED", "reason", "说明"),
                400);
        edit(
                "suppliers",
                supplier,
                Map.of("version", 0, "name", "错误", "status", "DISABLED", "reason", " "),
                400);
        for (String permission : List.of("warehouse:read", "iam:manage")) {
            mvc.perform(
                            put("/api/master-data/suppliers/" + supplier)
                                    .with(
                                            user("reader")
                                                    .authorities(
                                                            new SimpleGrantedAuthority(permission)))
                                    .with(csrf())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            json.writeValueAsString(
                                                    editBody("suppliers", supplier, "DISABLED"))))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            get("/api/master-data/suppliers/" + supplier + "/history")
                                    .with(
                                            user("reader")
                                                    .authorities(
                                                            new SimpleGrantedAuthority(
                                                                    permission))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(
                        put("/api/master-data/suppliers/" + supplier)
                                .with(user("writer").roles("ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        assertThat(catalog.supplier(supplier).version()).isZero();
        assertThat(catalog.supplier(supplier).name()).isEqualTo("原供应商");
    }

    @Test
    void unusedMaterialCanChangePolicyButReferencedIdentityCannot() throws Exception {
        var body = editBody("materials", material, "ENABLED");
        body.put("trackingMode", "QUANTITY");
        edit("materials", material, body, 200);
        body.put("trackingMode", "BATCH");
        body.put("version", 1);
        edit("materials", material, body, 200);
        arrival();
        assertThat(catalog.material(material).identityLocked()).isTrue();
        body.put("version", 2);
        assertThat(edit("materials", material, body, 409).get("code").asText())
                .isEqualTo("CATALOG_VERSION_CONFLICT");
        body.put("version", 3);
        body.put("requireExpiry", true);
        assertThat(edit("materials", material, body, 409).get("code").asText())
                .isEqualTo("MATERIAL_IDENTITY_LOCKED");
        assertThat(catalog.material(material).requireExpiry()).isFalse();
    }

    @Test
    void disabledDirectoriesBlockNewAuthorizationButOldReceiptKeepsSnapshotsAndCompletes()
            throws Exception {
        var arrival = arrival();
        long id = arrival.get("arrival").get("id").asLong(),
                line = arrival.get("items").get(0).get("id").asLong();
        for (String kind : List.of("suppliers", "materials", "units")) {
            long item =
                    kind.equals("suppliers")
                            ? supplier
                            : kind.equals("materials") ? material : unit;
            var body = editBody(kind, item, "DISABLED");
            if (!kind.equals("units")) body.put("name", "当前名称已修改");
            edit(kind, item, body, 200);
        }
        postJson("/api/local/erp-arrivals", arrivalBody(), 409);
        var draft =
                postJson(
                        "/api/v1/wms/arrival-notices/" + id + "/receipts",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "items",
                                List.of(
                                        Map.of(
                                                "arrivalItemId",
                                                line,
                                                "locationId",
                                                location(),
                                                "quantity",
                                                "2.000001",
                                                "batchNo",
                                                "B1"))),
                        201);
        long receipt = draft.get("receipt").get("id").asLong();
        var submit = Map.of("version", 0, "idempotencyKey", key());
        postJson("/api/v1/wms/receipts/" + receipt + "/submit", submit, 200);
        postJson("/api/v1/wms/receipts/" + receipt + "/submit", submit, 200);
        var historical = read("/api/v1/wms/arrival-notices/" + id);
        assertThat(historical.get("arrival").get("supplierName").asText()).isEqualTo("原供应商");
        assertThat(historical.get("items").get(0).get("materialName").asText()).isEqualTo("原物料");
        assertThat(
                        read("/api/v1/wms/quality/" + receipt + "/items")
                                .get(0)
                                .get("material_name")
                                .asText())
                .isEqualTo("原物料");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE receipt_id=?",
                                Long.class,
                                receipt))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(on_hand_qty) FROM wms_inventory_balance WHERE material_id=?",
                                java.math.BigDecimal.class,
                                material))
                .isEqualByComparingTo("2.000001");
        long receiptItem =
                read("/api/v1/wms/quality/" + receipt + "/items").get(0).get("id").asLong();
        postJson(
                "/api/local/qms-results",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "receiptId",
                        receipt,
                        "version",
                        1,
                        "referenceNo",
                        "QC-OLD",
                        "reason",
                        "旧授权质量收尾",
                        "items",
                        List.of(
                                Map.of(
                                        "receiptItemId",
                                        receiptItem,
                                        "qualifiedQty",
                                        0,
                                        "rejectedQty",
                                        "2.000001"))),
                200);
        var candidate =
                read("/api/v1/wms/purchase-returns/candidates?warehouseId=" + warehouse).get(0);
        assertThat(candidate.get("material_name").asText()).isEqualTo("原物料");
        assertThat(candidate.get("supplier_name").asText()).isEqualTo("原供应商");
    }

    @Test
    void unitsRequireExactRegisteredLabelsAndCannotBeRenamedWhenUsed() throws Exception {
        var body = editBody("units", unit, "ENABLED");
        body.put("name", "另一个单位");
        assertThat(edit("units", unit, body, 409).get("code").asText()).isEqualTo("UNIT_IN_USE");
        postJson(
                "/api/master-data/materials",
                Map.of("code", code(), "name", "新料", "unit", "未登记单位", "trackingMode", "BATCH"),
                409);
        create(
                "materials",
                Map.of(
                        "code",
                        code(),
                        "name",
                        "兼容旧单位参数",
                        "unit",
                        unitName,
                        "trackingMode",
                        "QUANTITY"));
        edit("units", unit, editBody("units", unit, "DISABLED"), 200);
        postJson(
                "/api/master-data/materials",
                Map.of("code", code(), "name", "新料", "unitId", unit, "trackingMode", "BATCH"),
                409);
        postJson("/api/local/erp-arrivals", arrivalBody(), 409);
        assertThat(catalog.material(material).identityLocked()).isFalse();
    }

    @Test
    void allNewWmsDemandPathsCheckActiveMaterialAndKeepOriginalSnapshotsOnRetry() throws Exception {
        long finished = warehouse("FINISHED_GOODS"), lineSide = warehouse("LINE_SIDE");
        var demands =
                List.of(
                        Map.<String, Object>of(
                                "demandNo",
                                key(),
                                "workOrderNo",
                                "WO1",
                                "warehouseId",
                                warehouse,
                                "targetWarehouseId",
                                lineSide,
                                "materialId",
                                material,
                                "quantity",
                                2),
                        Map.<String, Object>of(
                                "demandNo",
                                key(),
                                "workOrderNo",
                                "WO1",
                                "warehouseId",
                                finished,
                                "materialId",
                                material,
                                "quantity",
                                2,
                                "batchNo",
                                "B1",
                                "productionDate",
                                "2026-01-01"),
                        Map.<String, Object>of(
                                "demandNo",
                                key(),
                                "salesOrderNo",
                                "SO1",
                                "customerReference",
                                "模拟客户",
                                "warehouseId",
                                finished,
                                "materialId",
                                material,
                                "quantity",
                                2));
        var urls =
                List.of(
                        "/api/local/mes-demands",
                        "/api/local/finished-receipts",
                        "/api/local/sales-orders");
        for (int i = 0; i < 3; i++) postJson(urls.get(i), demands.get(i), 201);
        var edit = editBody("materials", material, "DISABLED");
        edit.put("name", "新名称");
        edit("materials", material, edit, 200);
        for (int i = 0; i < 3; i++) {
            assertThat(postJson(urls.get(i), demands.get(i), 201).get("material_name").asText())
                    .isEqualTo("原物料");
            var fresh = new HashMap<>(demands.get(i));
            fresh.put("demandNo", key());
            assertThat(postJson(urls.get(i), fresh, 409).get("code").asText())
                    .isEqualTo("MASTER_DATA_DISABLED");
        }
    }

    @Test
    void invalidArrivalRollsBackNoticeAndMaterialReferenceLock() throws Exception {
        var body = new HashMap<>(arrivalBody());
        body.put(
                "items",
                List.of(
                        Map.of("materialId", material, "quantity", 2),
                        Map.of("materialId", Long.MAX_VALUE, "quantity", 2)));
        postJson("/api/local/erp-arrivals", body, 404);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_arrival_notice WHERE external_notice_no=?",
                                Long.class,
                                body.get("externalNoticeNo")))
                .isZero();
        assertThat(catalog.material(material).identityLocked()).isFalse();
        assertThat(catalog.material(material).version()).isZero();
    }

    @Test
    void auditFailureRollsBackDirectoryChange() {
        db.execute(
                "ALTER TABLE mdm_catalog_audit ADD CONSTRAINT catalog_test_fail CHECK(reason <> 'CATALOG_TEST_FAIL')");
        try {
            assertThatThrownBy(
                            () ->
                                    catalog.edit(
                                            "suppliers",
                                            supplier,
                                            new CatalogService.Edit(
                                                    0L,
                                                    "不应保存",
                                                    "DISABLED",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    "CATALOG_TEST_FAIL")))
                    .isInstanceOf(RuntimeException.class);
            assertThat(catalog.supplier(supplier).name()).isEqualTo("原供应商");
            assertThat(catalog.supplier(supplier).status()).isEqualTo("ENABLED");
            assertThat(catalog.supplier(supplier).version()).isZero();
        } finally {
            db.execute("ALTER TABLE mdm_catalog_audit DROP CHECK catalog_test_fail");
        }
    }

    @Test
    void singletonOrganizationDoesNotGrantWarehouseAccess() throws Exception {
        var org = create("organizations", Map.of("code", code(), "name", "演示工厂"));
        postJson("/api/master-data/organizations", Map.of("code", code(), "name", "第二工厂"), 409);
        edit(
                "organizations",
                org.get("id").asLong(),
                Map.of("version", 0, "name", "工厂更名", "reason", "核对标识"),
                200);
        mvc.perform(
                        get("/api/v1/wms/arrival-notices")
                                .param("warehouseId", Long.toString(warehouse))
                                .with(
                                        user("reader")
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "wms:receiving:read"))))
                .andExpect(status().isForbidden());
        assertThat(read("/api/master-data/organizations").size()).isEqualTo(1);
    }

    private Map<String, Object> editBody(String kind, long id, String state) {
        String table =
                switch (kind) {
                    case "materials" -> "mdm_material";
                    case "units" -> "mdm_unit";
                    default -> "mdm_supplier";
                };
        var row = db.queryForMap("SELECT * FROM " + table + " WHERE id=?", id);
        var body =
                new HashMap<String, Object>(
                        Map.of(
                                "version",
                                row.get("version"),
                                "name",
                                row.get("name"),
                                "status",
                                state,
                                "reason",
                                "资料核对"));
        if (kind.equals("materials")) {
            body.put("unitId", unit);
            body.put("trackingMode", "BATCH");
            body.put("requireDateCode", false);
            body.put("requireExpiry", false);
        }
        return body;
    }

    private long warehouse(String purpose) throws Exception {
        return create(
                        "warehouses",
                        Map.of(
                                "code",
                                code(),
                                "name",
                                "资料验收仓",
                                "purpose",
                                purpose,
                                "form",
                                "PHYSICAL",
                                "managementCategory",
                                "GENERAL"))
                .get("id")
                .asLong();
    }

    private long location() throws Exception {
        return create(
                        "locations",
                        Map.of(
                                "code",
                                code(),
                                "name",
                                "待检区",
                                "warehouseId",
                                warehouse,
                                "areaType",
                                "INSPECTION"))
                .get("id")
                .asLong();
    }

    private Map<String, Object> arrivalBody() {
        return Map.of(
                "externalNoticeNo",
                key(),
                "purchaseOrderNo",
                "PO1",
                "supplierId",
                supplier,
                "warehouseId",
                warehouse,
                "items",
                List.of(Map.of("materialId", material, "quantity", 3)));
    }

    private JsonNode arrival() throws Exception {
        return postJson("/api/local/erp-arrivals", arrivalBody(), 201);
    }

    private JsonNode create(String kind, Object body) throws Exception {
        return postJson("/api/master-data/" + kind, body, 201);
    }

    private JsonNode edit(String kind, long id, Object body, int expected) throws Exception {
        return json.readTree(
                mvc.perform(
                                admin(put("/api/master-data/" + kind + "/" + id))
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private JsonNode postJson(String url, Object body, int expected) throws Exception {
        if (url.equals("/api/master-data/materials")) {
            var complete = new HashMap<String, Object>();
            complete.putAll((Map<String, Object>) body);
            complete.putIfAbsent("requireDateCode", false);
            complete.putIfAbsent("requireExpiry", false);
            body = complete;
        }
        return json.readTree(
                mvc.perform(admin(post(url)).content(json.writeValueAsString(body)))
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private JsonNode read(String url) throws Exception {
        return json.readTree(
                mvc.perform(admin(get(url)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.with(user("catalog-writer").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private List<Integer> race(Callable<Integer> operation) throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return operation.call();
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return operation.call();
                            });
            gate.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String code() {
        return "C" + key().substring(0, 16).toUpperCase(Locale.ROOT);
    }
}
