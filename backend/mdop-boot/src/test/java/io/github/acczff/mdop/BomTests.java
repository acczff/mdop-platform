package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BomTests extends InventoryScenarioSupport {
    static final String URL = "/api/v1/manufacturing/boms";
    long product;

    @BeforeEach
    void product() {
        product = material("产品");
    }

    long material(String name) {
        try {
            return postAs(
                            "/api/master-data/materials",
                            Map.of(
                                    "code",
                                    "B" + key().substring(0, 8),
                                    "name",
                                    name,
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
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    RequestPostProcessor editor() {
        return user("bom-editor")
                .authorities(
                        new SimpleGrantedAuthority("bom:read"),
                        new SimpleGrantedAuthority("bom:write"));
    }

    RequestPostProcessor reader() {
        return user("bom-reader").authorities(new SimpleGrantedAuthority("bom:read"));
    }

    Map<String, Object> draft(long product, long component, String label) {
        var m = new HashMap<String, Object>();
        m.put("idempotencyKey", key());
        m.put("productId", product);
        m.put("versionLabel", label);
        m.put("baseQuantity", "1");
        m.put("description", "单层产品依据");
        m.put("reason", "维护测试版本");
        m.put("components", List.of(Map.of("materialId", component, "quantity", "2")));
        return m;
    }

    JsonNode getBom(long id) throws Exception {
        return json.readTree(
                mvc.perform(get(URL + "/" + id).with(reader()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    Map<String, Object> command(JsonNode d) {
        return Map.of(
                "idempotencyKey", key(), "version", d.get("version").asLong(), "reason", "版本依据核对");
    }

    JsonNode action(JsonNode d, String action, int expected) throws Exception {
        return postAs(
                URL + "/" + d.get("id").asLong() + "/actions/" + action,
                command(d),
                editor(),
                expected);
    }

    JsonNode create(long p, long c) throws Exception {
        return postAs(URL, draft(p, c, "V1"), editor(), 201);
    }

    JsonNode edit(JsonNode d, String qty, int expected) throws Exception {
        var body =
                draft(
                        d.get("product_id").asLong(),
                        d.get("components").get(0).get("material_id").asLong(),
                        d.get("version_label").asText());
        body.put("version", d.get("version").asLong());
        body.put(
                "components",
                List.of(
                        Map.of(
                                "materialId",
                                d.get("components").get(0).get("material_id").asLong(),
                                "quantity",
                                qty)));
        return json.readTree(
                mvc.perform(
                                put(URL + "/" + d.get("id").asLong())
                                        .with(editor())
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    @Test
    void publishesImmutableVersionsCopiesAndDisablesWithAudit() throws Exception {
        var input = draft(product, material, "V1");
        var d = postAs(URL, input, editor(), 201);
        long id = d.get("id").asLong();
        assertThat(postAs(URL, input, editor(), 201).get("id").asLong()).isEqualTo(id);
        d = edit(d, "2.123456", 200);
        var pub = command(d);
        d = postAs(URL + "/" + id + "/actions/publish", pub, editor(), 200);
        assertThat(d.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(
                        postAs(URL + "/" + id + "/actions/publish", pub, editor(), 200)
                                .get("history")
                                .size())
                .isEqualTo(3);
        edit(d, "9", 409);
        String old = d.get("components").toString();
        var copy =
                Map.of(
                        "idempotencyKey",
                        key(),
                        "version",
                        d.get("version").asLong(),
                        "versionLabel",
                        "V2",
                        "reason",
                        "新用量版本");
        var v2 = postAs(URL + "/" + id + "/copy", copy, editor(), 201);
        assertThat(v2.get("copied_from_id").asLong()).isEqualTo(id);
        assertThat(postAs(URL + "/" + id + "/copy", copy, editor(), 201).get("id"))
                .isEqualTo(v2.get("id"));
        v2 = action(edit(v2, "3", 200), "publish", 200);
        assertThat(getBom(id).get("components").toString()).isEqualTo(old);
        d = action(getBom(id), "disable", 200);
        assertThat(d.get("status").asText()).isEqualTo("DISABLED");
        assertThat(d.get("components").toString()).isEqualTo(old);
        assertThat(d.get("published_at").isNull()).isFalse();
        action(d, "publish", 409);
        edit(d, "10", 409);
        assertThat(getBom(v2.get("id").asLong()).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(
                        db.queryForObject(
                                "SELECT identity_locked FROM mdm_material WHERE id=?",
                                Boolean.class,
                                product))
                .isTrue();
        db.update("UPDATE mdm_material SET name='新版名称' WHERE id=?", product);
        assertThat(getBom(id).get("product_name").asText()).isEqualTo("产品");
    }

    @Test
    void rejectsInvalidDraftsWithoutPartialRows() throws Exception {
        long count = db.queryForObject("SELECT COUNT(*) FROM mfg_bom", Long.class);
        var in = draft(product, product, "V1");
        postAs(URL, in, editor(), 400);
        in = draft(product, material, "V1");
        in.put("components", List.of(Map.of("materialId", material, "quantity", "0")));
        postAs(URL, in, editor(), 400);
        in.put("components", List.of(Map.of("materialId", material, "quantity", "1.0000001")));
        postAs(URL, in, editor(), 400);
        in.put(
                "components",
                List.of(
                        Map.of("materialId", material, "quantity", "1"),
                        Map.of("materialId", material, "quantity", "2")));
        postAs(URL, in, editor(), 400);
        in.put("components", List.of(Map.of("materialId", 99999999, "quantity", "1")));
        postAs(URL, in, editor(), 404);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mfg_bom", Long.class)).isEqualTo(count);
    }

    @Test
    void detectsIndirectCyclesAndAllowsExplicitRetirement() throws Exception {
        long b = material("B"), c = material("C");
        var ab = action(create(product, b), "publish", 200);
        action(create(b, c), "publish", 200);
        var ca = create(c, product);
        action(ca, "publish", 409);
        assertThat(getBom(ca.get("id").asLong()).get("status").asText()).isEqualTo("DRAFT");
        action(ab, "disable", 200);
        assertThat(action(ca, "publish", 200).get("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void concurrentOppositePublicationsCannotIntroduceCycle() throws Exception {
        long b = material("B");
        var a = create(product, b);
        var other = create(b, product);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Integer>>();
            for (var d : List.of(a, other))
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return mvc.perform(
                                                    post(URL
                                                                    + "/"
                                                                    + d.get("id").asLong()
                                                                    + "/actions/publish")
                                                            .with(editor())
                                                            .with(csrf())
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(
                                                                    json.writeValueAsString(
                                                                            command(d))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            start.countDown();
            assertThat(
                            List.of(
                                    futures.get(0).get(20, TimeUnit.SECONDS),
                                    futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    @Test
    void concurrentReplayAndVersionUniquenessArePreserved() throws Exception {
        var in = draft(product, material, "V1");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Long> run =
                    () -> {
                        start.await();
                        return postAs(URL, in, editor(), 201).get("id").asLong();
                    };
            var a = pool.submit(run);
            var b = pool.submit(run);
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        postAs(URL, draft(product, material, "v1"), editor(), 409);
        in.put("description", "改变载荷");
        postAs(URL, in, editor(), 409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM mfg_bom WHERE product_id=?",
                                Long.class,
                                product))
                .isEqualTo(1);
    }

    @Test
    void revalidatesMaterialStatusAndUnitBeforePublishing() throws Exception {
        long c = material("可改单位材料");
        var d = create(product, c);
        db.update("UPDATE mdm_material SET status='DISABLED' WHERE id=?", c);
        action(d, "publish", 409);
        assertThat(
                        db.queryForObject(
                                "SELECT identity_locked FROM mdm_material WHERE id=?",
                                Boolean.class,
                                product))
                .isFalse();
        db.update("UPDATE mdm_material SET status='ENABLED' WHERE id=?", c);
        long unit = db.queryForObject("SELECT id FROM mdm_unit WHERE name='件'", Long.class);
        db.update(
                "INSERT INTO mdm_unit(code,name,status) VALUES(?,?,'ENABLED')",
                "TEST-" + key().substring(0, 8),
                "U" + key().substring(0, 8));
        long next = db.queryForObject("SELECT MAX(id) FROM mdm_unit", Long.class);
        db.update(
                "UPDATE mdm_material SET unit_id=?,unit=(SELECT name FROM mdm_unit WHERE id=?) WHERE id=?",
                next,
                next,
                c);
        action(d, "publish", 409);
        assertThat(
                        db.queryForObject(
                                "SELECT identity_locked FROM mdm_material WHERE id=?",
                                Boolean.class,
                                c))
                .isFalse();
        postAs(
                URL + "/" + d.get("id").asLong() + "/copy",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "version",
                        d.get("version").asLong(),
                        "versionLabel",
                        "V2",
                        "reason",
                        "单位变化不能静默复制"),
                editor(),
                409);
        d = edit(d, "2", 200);
        d = action(d, "publish", 200);
        assertThat(d.get("components").get(0).get("unit_id").asLong()).isEqualTo(next);
        assertThat(unit).isNotEqualTo(next);
    }

    @Test
    void permissionCsrfVersionAndActorAreEnforced() throws Exception {
        var in = draft(product, material, "V1");
        postAs(URL, in, admin(), 403);
        postAs(URL, in, reader(), 403);
        mvc.perform(
                        post(URL)
                                .with(editor())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(in)))
                .andExpect(status().isForbidden());
        var d = postAs(URL, in, editor(), 201);
        postAs(URL, in, user("other").authorities(new SimpleGrantedAuthority("bom:write")), 409);
        var old = command(d);
        d = edit(d, "4", 200);
        postAs(URL + "/" + d.get("id").asLong() + "/actions/publish", old, editor(), 409);
        mvc.perform(get(URL + "/" + d.get("id").asLong()).with(admin()))
                .andExpect(status().isForbidden());
        mvc.perform(get(URL + "?page=-1").with(reader())).andExpect(status().isBadRequest());
        assertThat(
                        io.github.acczff.mdop.system.identity.PermissionCatalog.resolve(
                                Set.of("BOM_MAINTAINER"), Set.of()))
                .contains("bom:read", "bom:write")
                .doesNotContain("iam:manage", "wms:issue:confirm");
    }
}
