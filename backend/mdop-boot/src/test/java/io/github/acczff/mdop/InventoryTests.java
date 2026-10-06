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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.*;

@SpringBootTest(
        properties = {
            "mdop.auth.operators[0].username=inventory-reader",
            "mdop.auth.operators[0].password=Inventory-Test-Only-2026!",
            "mdop.auth.operators[0].authorities=wms:inventory:read,wms:warehouse:1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InventoryTests extends MdopInfrastructureTestBase {
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
                                                "",
                                                "dateCode",
                                                ""))),
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

    String stockUrl() {
        return "/api/v1/wms/stock?warehouseId=" + warehouse;
    }

    @Test
    void configuredInventoryReaderDoesNotGainAdministrativeOrWritePermissions() {
        var reader = users.loadUserByUsername("inventory-reader");
        assertThat(reader.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactlyInAnyOrder("wms:inventory:read", "wms:warehouse:1");
    }

    JsonNode read(String path) throws Exception {
        return json.readTree(
                mvc.perform(get(path).with(admin()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    @Test
    void preservesDecimalPrecisionAndTracesPagedOriginalReceipts() throws Exception {
        long first = receive(new BigDecimal("0.123456"));
        long second = receive(new BigDecimal("0.000001"));
        var stock = read(stockUrl()).get("items").get(0);
        assertThat(stock.get("on_hand_qty").asText()).isEqualTo("0.123457");
        assertThat(stock.get("available_qty").asText()).isEqualTo("0.000000");
        long balance = stock.get("id").asLong();
        var latest = read("/api/v1/wms/stock/" + balance + "/transactions?size=1");
        assertThat(latest.get("totalElements").asLong()).isEqualTo(2);
        assertThat(latest.get("items").get(0).get("receipt_id").asLong()).isEqualTo(second);
        assertThat(latest.get("items").get(0).get("before_qty").asText()).isEqualTo("0.123456");
        assertThat(latest.get("items").get(0).get("external_notice_no").asText()).isNotBlank();
        var earlier = read("/api/v1/wms/stock/" + balance + "/transactions?size=1&page=1");
        assertThat(earlier.get("items").get(0).get("receipt_id").asLong()).isEqualTo(first);
        assertThat(read("/api/v1/wms/stock/" + balance).get("warehouse_id").asLong())
                .isEqualTo(warehouse);
    }

    @Test
    void filtersQualityLocationAndZeroHistoryWithStablePagination() throws Exception {
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
                        "库存查询验收",
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
        assertThat(read(stockUrl()).get("totalElements").asLong()).isEqualTo(2);
        assertThat(read(stockUrl() + "&includeZero=true").get("totalElements").asLong())
                .isEqualTo(3);
        var rejected = read(stockUrl() + "&quality=REJECTED&locationId=" + location);
        assertThat(rejected.get("items").get(0).get("on_hand_qty").asText()).isEqualTo("20.000000");
        assertThat(read(stockUrl() + "&quality=PENDING_INSPECTION").get("totalElements").asLong())
                .isZero();
        assertThat(read(stockUrl() + "&batch=missing").get("totalElements").asLong()).isZero();
        assertThat(read(stockUrl() + "&keyword=物料").get("totalElements").asLong()).isEqualTo(2);
        assertThat(read(stockUrl() + "&keyword=_").get("totalElements").asLong()).isZero();
        var p0 = read(stockUrl() + "&size=1");
        var p1 = read(stockUrl() + "&size=1&page=1");
        assertThat(p0.get("totalPages").asLong()).isEqualTo(2);
        assertThat(p0.get("items").get(0).get("id").asLong())
                .isGreaterThan(p1.get("items").get(0).get("id").asLong());
        assertThat(read(stockUrl() + "&size=1&page=2").get("items").size()).isZero();
    }

    @Test
    void enforcesInventoryPermissionAndWarehouseScopeOnEveryRead() throws Exception {
        receive(1);
        long balance = read(stockUrl()).get("items").get(0).get("id").asLong();
        var allowed = operator("reader", "wms:inventory:read");
        var forbidden =
                user("other")
                        .authorities(
                                new SimpleGrantedAuthority("wms:inventory:read"),
                                new SimpleGrantedAuthority("wms:warehouse:" + (warehouse + 10000)));
        for (String path :
                List.of(
                        stockUrl(),
                        "/api/v1/wms/stock/" + balance,
                        "/api/v1/wms/stock/" + balance + "/transactions")) {
            mvc.perform(get(path).with(allowed)).andExpect(status().isOk());
            mvc.perform(get(path).with(forbidden)).andExpect(status().isForbidden());
            mvc.perform(get(path).with(operator("receiver", "wms:arrival:read")))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void rejectsInvalidFiltersAndExcessivePageSizes() throws Exception {
        for (String query :
                List.of("&page=-1", "&size=0", "&size=101", "&quality=INVALID", "&locationId=-1")) {
            mvc.perform(get(stockUrl() + query).with(admin())).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/wms/stock/9223372036854775807").with(admin()))
                .andExpect(status().isNotFound());
    }
}
