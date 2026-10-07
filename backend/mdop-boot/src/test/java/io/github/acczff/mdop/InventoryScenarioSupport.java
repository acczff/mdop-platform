package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.*;

abstract class InventoryScenarioSupport extends MdopInfrastructureTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.security.core.userdetails.UserDetailsService users;

    long warehouse, location, material, supplier, arrival, line;

    @BeforeEach
    void setup() throws Exception {
        String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        warehouse =
                postAs(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "W" + code,
                                        "name",
                                        "冲正验收仓",
                                        "purpose",
                                        "RAW_MATERIAL",
                                        "form",
                                        "PHYSICAL",
                                        "managementCategory",
                                        "GENERAL"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        supplier =
                postAs(
                                "/api/master-data/suppliers",
                                Map.of("code", "S" + code, "name", "供应商"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        material =
                postAs(
                                "/api/master-data/materials",
                                Map.of(
                                        "code",
                                        "M" + code,
                                        "name",
                                        "物料",
                                        "unit",
                                        "件",
                                        "trackingMode",
                                        "QUANTITY",
                                        "requireDateCode",
                                        false,
                                        "requireExpiry",
                                        false),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        location =
                postAs(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        warehouse,
                                        "code",
                                        "L" + code,
                                        "name",
                                        "待检",
                                        "areaType",
                                        "INSPECTION"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        var notice =
                postAs(
                        "/api/local/erp-arrivals",
                        Map.of(
                                "externalNoticeNo",
                                code,
                                "purchaseOrderNo",
                                "P" + code,
                                "supplierId",
                                supplier,
                                "warehouseId",
                                warehouse,
                                "items",
                                List.of(Map.of("materialId", material, "quantity", 100))),
                        admin(),
                        201);
        arrival = notice.get("arrival").get("id").asLong();
        line = notice.get("items").get(0).get("id").asLong();
    }

    RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    RequestPostProcessor operator(String name, String permission) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority(permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + warehouse));
    }

    JsonNode postAs(String path, Object body, RequestPostProcessor principal, int expected)
            throws Exception {
        String content =
                mvc.perform(
                                post(path)
                                        .with(principal)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return content.isBlank() ? json.nullNode() : json.readTree(content);
    }

    String key() {
        return UUID.randomUUID().toString();
    }

    long receive(Number quantity) throws Exception {
        var draft =
                postAs(
                        "/api/v1/wms/arrival-notices/" + arrival + "/receipts",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "items",
                                List.of(
                                        Map.of(
                                                "arrivalItemId",
                                                line,
                                                "locationId",
                                                location,
                                                "quantity",
                                                quantity,
                                                "batchNo",
                                                "TRANSFER-BATCH",
                                                "dateCode",
                                                "DC-01",
                                                "productionDate",
                                                "2026-01-01",
                                                "expiryDate",
                                                "2099-12-31"))),
                        admin(),
                        201);
        long id = draft.get("receipt").get("id").asLong();
        postAs(
                "/api/v1/wms/receipts/" + id + "/submit",
                Map.of("idempotencyKey", key(), "version", 0),
                admin(),
                200);
        return id;
    }

    long storage() throws Exception {
        return postAs(
                        "/api/master-data/locations",
                        Map.of(
                                "warehouseId",
                                warehouse,
                                "code",
                                "S" + key().substring(0, 8),
                                "name",
                                "存储位",
                                "areaType",
                                "STORAGE"),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    long[] ready() throws Exception {
        long receipt = receive(100);
        long item =
                db.queryForObject(
                        "SELECT id FROM wms_receipt_item WHERE receipt_id=?", Long.class, receipt);
        postAs(
                "/api/local/qms-results",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "receiptId",
                        receipt,
                        "version",
                        1,
                        "referenceNo",
                        key(),
                        "reason",
                        "移库测试",
                        "items",
                        List.of(
                                Map.of(
                                        "receiptItemId",
                                        item,
                                        "qualifiedQty",
                                        80,
                                        "rejectedQty",
                                        20))),
                admin(),
                200);
        long a = storage(), b = storage();
        postAs(
                "/api/v1/wms/quality/items/" + item + "/putaway",
                Map.of("idempotencyKey", key(), "locationId", a),
                admin(),
                200);
        long balance =
                db.queryForObject(
                        "SELECT id FROM wms_inventory_balance WHERE location_id=? AND quality_status='QUALIFIED'",
                        Long.class,
                        a);
        return new long[] {balance, a, b};
    }

    Map<String, Object> input(long source, long target, String quantity, String key) {
        return Map.of(
                "sourceBalanceId",
                source,
                "targetLocationId",
                target,
                "quantity",
                quantity,
                "idempotencyKey",
                key,
                "reason",
                "实际移库");
    }

    BigDecimal qty(long id) {
        return db.queryForObject(
                "SELECT on_hand_qty FROM wms_inventory_balance WHERE id=?", BigDecimal.class, id);
    }

    JsonNode read(String path) throws Exception {
        return json.readTree(
                mvc.perform(get(path).with(admin()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    long count(long balance) throws Exception {
        return postAs(
                        "/api/v1/wms/counts",
                        Map.of("balanceId", balance, "idempotencyKey", key()),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    void submitCount(long id, String quantity) throws Exception {
        postAs(
                "/api/v1/wms/counts/" + id + "/submit",
                Map.of("quantity", quantity, "reason", "实盘差异"),
                admin(),
                200);
    }

    void approve(long id, int status) throws Exception {
        postAs(
                "/api/v1/wms/counts/" + id + "/approve",
                Map.of(),
                operator("reviewer", "wms:count:review"),
                status);
    }

    long targetWarehouse, targetLocation;

    void lineSide() throws Exception {
        targetWarehouse =
                postAs(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "LS" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                        "name",
                                        "线边仓",
                                        "purpose",
                                        "LINE_SIDE",
                                        "form",
                                        "PHYSICAL",
                                        "managementCategory",
                                        "GENERAL"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        targetLocation =
                postAs(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        targetWarehouse,
                                        "code",
                                        "ST",
                                        "name",
                                        "线边存储位",
                                        "areaType",
                                        "STORAGE"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
    }

    Map<String, Object> demandBody(String no, String quantity) {
        return Map.of(
                "demandNo",
                no,
                "workOrderNo",
                "MO-001",
                "warehouseId",
                warehouse,
                "targetWarehouseId",
                targetWarehouse,
                "materialId",
                material,
                "quantity",
                quantity);
    }

    long demand(String quantity) throws Exception {
        return postAs("/api/local/mes-demands", demandBody(key(), quantity), admin(), 201)
                .get("id")
                .asLong();
    }

    JsonNode reserve(long id, long balance, int status) throws Exception {
        return postAs(
                "/api/v1/wms/issues/" + id + "/reserve",
                Map.of("balanceId", balance, "targetLocationId", targetLocation),
                admin(),
                status);
    }

    void confirmIssue(long id, int status) throws Exception {
        postAs("/api/v1/wms/issues/" + id + "/confirm", Map.of(), admin(), status);
    }

    void cancelIssue(long id, int status) throws Exception {
        postAs("/api/v1/wms/issues/" + id + "/cancel", Map.of("reason", "需求取消"), admin(), status);
    }

    BigDecimal stock(long id, String column) {
        return db.queryForObject(
                "SELECT " + column + " FROM wms_inventory_balance WHERE id=?",
                BigDecimal.class,
                id);
    }

    RequestPostProcessor both(String name, String permission) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority(permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + warehouse),
                        new SimpleGrantedAuthority("wms:warehouse:" + targetWarehouse));
    }
}
