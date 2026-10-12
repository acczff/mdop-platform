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
class FinanceTests extends PurchaseScenarioSupport {
    static final String FIN = "/api/v1/finance";

    @BeforeEach
    void resetFinance() {
        for (String table : List.of("fin_command", "fin_audit", "fin_open_item", "fin_basis"))
            db.update("DELETE FROM " + table);
        db.update(
                "UPDATE fin_baseline SET status='UNINITIALIZED',version=0,organization=NULL,start_date=NULL,end_date=NULL,created_by=NULL,reviewed_by=NULL WHERE id=1");
    }

    RequestPostProcessor finance(String name, String role) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority("finance:read"),
                        new SimpleGrantedAuthority("finance:" + role),
                        new SimpleGrantedAuthority("wms:warehouse:" + warehouse));
    }

    JsonNode read(String path, RequestPostProcessor who, int status) throws Exception {
        return json.readTree(
                mvc.perform(get(FIN + path).with(who))
                        .andExpect(status().is(status))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    Map<String, Object> setupInput() {
        return Map.of(
                "idempotencyKey",
                key(),
                "version",
                0,
                "organization",
                "演示组织",
                "startDate",
                "2026-01-01",
                "endDate",
                "2026-12-31",
                "reason",
                "独立演示基线");
    }

    JsonNode initialize() throws Exception {
        var d = postAs(FIN + "/baseline", setupInput(), finance("fin-maker", "write"), 200);
        return postAs(FIN + "/baseline/approve", command(d), finance("fin-checker", "review"), 200);
    }

    JsonNode sourceOrder(boolean stored) throws Exception {
        var d = arrange(order(), "60", 200);
        d = perform(d, d.get("arrangements").get(0).get("id").asLong(), "deliver", 200);
        var receipt = draft(d.get("arrangements").get(0), "60", 201);
        receipt =
                postAs(
                        "/api/v1/wms/receipts/"
                                + receipt.get("receipt").get("id").asLong()
                                + "/submit",
                        Map.of("idempotencyKey", key(), "version", 0),
                        operator("warehouse", "wms:receipt:submit"),
                        200);
        long rid = receipt.get("receipt").get("id").asLong();
        long item =
                db.queryForObject(
                        "SELECT id FROM wms_receipt_item WHERE receipt_id=?", Long.class, rid);
        postAs(
                "/api/local/qms-results",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "receiptId",
                        rid,
                        "version",
                        1,
                        "referenceNo",
                        key(),
                        "reason",
                        "验收",
                        "items",
                        List.of(
                                Map.of(
                                        "receiptItemId",
                                        item,
                                        "qualifiedQty",
                                        60,
                                        "rejectedQty",
                                        0))),
                admin(),
                200);
        if (stored) {
            long loc =
                    postAs(
                                    "/api/master-data/locations",
                                    Map.of(
                                            "warehouseId",
                                            warehouse,
                                            "code",
                                            "S" + key().substring(0, 8),
                                            "name",
                                            "正式库位",
                                            "areaType",
                                            "STORAGE"),
                                    admin(),
                                    201)
                            .get("id")
                            .asLong();
            postAs(
                    "/api/v1/wms/quality/items/" + item + "/putaway",
                    Map.of("idempotencyKey", key(), "locationId", loc),
                    operator("warehouse", "wms:putaway:confirm"),
                    200);
        }
        return d;
    }

    String sourcePath(JsonNode order) {
        return "/sources?warehouseId="
                + warehouse
                + "&direction=PAYABLE&orderNumber="
                + order.get("document_no").asText();
    }

    Map<String, Object> draftFinance(JsonNode o, JsonNode f) {
        var in = new HashMap<String, Object>();
        in.put("idempotencyKey", key());
        in.put("direction", "PAYABLE");
        in.put("warehouseId", warehouse);
        in.put("orderNumber", o.get("document_no").asText());
        in.put("factId", f.get("factId").asLong());
        in.put("unitPrice", "0.333333");
        in.put("amount", "20.00");
        in.put("reference", "演示结算依据-" + key());
        in.put("businessDate", "2026-10-12");
        in.put("reason", "按已上架实物确认，未计税");
        return in;
    }

    JsonNode createBasis() throws Exception {
        var o = sourceOrder(true);
        var facts = read(sourcePath(o), finance("fin-maker", "read"), 200);
        assertThat(facts.size()).isEqualTo(1);
        return postAs(
                FIN + "/bases", draftFinance(o, facts.get(0)), finance("fin-maker", "write"), 200);
    }

    JsonNode act(JsonNode d, String action, String person, String role, int expected)
            throws Exception {
        return postAs(
                FIN + "/bases/" + d.get("id").asLong() + "/" + action,
                command(d),
                finance(person, role),
                expected);
    }

    @Test
    void initializationRequiresIndependentApprovalAndDoesNotCreateHistoricalAmounts()
            throws Exception {
        var in = setupInput();
        var d = postAs(FIN + "/baseline", in, finance("maker", "write"), 200);
        postAs(FIN + "/baseline", in, finance("maker", "write"), 200);
        postAs(FIN + "/baseline/approve", command(d), finance("maker", "review"), 409);
        d = postAs(FIN + "/baseline/reject", command(d), finance("checker", "review"), 200);
        var changed = new HashMap<>(setupInput());
        changed.put("version", d.get("version").asLong());
        d = postAs(FIN + "/baseline", changed, finance("maker", "write"), 200);
        postAs(FIN + "/baseline/approve", command(d), finance("checker", "review"), 200);
        postAs(FIN + "/baseline", setupInput(), finance("maker", "write"), 409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM fin_basis", Long.class)).isZero();
        assertThatThrownBy(
                        () ->
                                io.github.acczff.mdop.system.identity.PermissionCatalog.resolve(
                                        Set.of("FINANCE_OPERATOR", "FINANCE_REVIEWER"),
                                        Set.of(warehouse)))
                .hasMessageContaining("不同账号");
    }

    @Test
    void payableRoundingReplayDuplicateSourceAndImmutableConfirmation() throws Exception {
        initialize();
        var o = sourceOrder(true);
        var f = read(sourcePath(o), finance("fin-maker", "read"), 200).get(0);
        var in = draftFinance(o, f);
        long stock =
                db.queryForObject("SELECT COUNT(*) FROM wms_inventory_transaction", Long.class);
        var d = postAs(FIN + "/bases", in, finance("fin-maker", "write"), 200);
        assertThat(d.get("amount").asText()).isEqualTo("20.00");
        assertThat(d.get("open_amount").isNull()).isTrue();
        assertThat(d.get("sourceMatches").asBoolean()).isTrue();
        assertThat(postAs(FIN + "/bases", in, finance("fin-maker", "write"), 200).get("id"))
                .isEqualTo(d.get("id"));
        var duplicate = new HashMap<>(in);
        duplicate.put("idempotencyKey", key());
        postAs(FIN + "/bases", duplicate, finance("fin-maker", "write"), 409);
        var wrong = new HashMap<>(in);
        wrong.put("amount", "21.00");
        postAs(FIN + "/bases", wrong, finance("fin-maker", "write"), 409);
        d = act(d, "submit", "fin-maker", "write", 200);
        act(d, "approve", "fin-maker", "review", 409);
        var approval = command(d);
        String path = FIN + "/bases/" + d.get("id").asLong() + "/approve";
        d = postAs(path, approval, finance("checker", "review"), 200);
        postAs(path, approval, finance("checker", "review"), 200);
        assertThat(d.get("open_amount").asText()).isEqualTo("20.00");
        assertThat(d.get("balanceMatches").asBoolean()).isTrue();
        act(d, "cancel", "fin-maker", "write", 409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM fin_open_item", Long.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM wms_inventory_transaction", Long.class))
                .isEqualTo(stock);
    }

    @Test
    void invalidPeriodPriceAmountsMissingSourceAndWarehousePermissionsAreRejected()
            throws Exception {
        var o = sourceOrder(true);
        var f = read(sourcePath(o), finance("fin-maker", "read"), 200).get(0);
        var in = draftFinance(o, f);
        postAs(FIN + "/bases", in, finance("fin-maker", "write"), 409);
        initialize();
        for (var entry :
                Map.of(
                                "businessDate",
                                "2027-01-01",
                                "unitPrice",
                                "1.1234567",
                                "amount",
                                "20.001",
                                "factId",
                                Long.MAX_VALUE)
                        .entrySet()) {
            var bad = new HashMap<>(in);
            bad.put("idempotencyKey", key());
            bad.put(entry.getKey(), entry.getValue());
            postAs(
                    FIN + "/bases",
                    bad,
                    finance("fin-maker", "write"),
                    entry.getKey().equals("factId") ? 409 : 400);
        }
        var wrong = new HashMap<>(in);
        wrong.put("amount", "19.99");
        postAs(FIN + "/bases", wrong, finance("fin-maker", "write"), 400);
        postAs(FIN + "/bases", in, operator("buyer", "purchasing:write"), 403);
        var d = postAs(FIN + "/bases", in, finance("fin-maker", "write"), 200);
        read(
                "/bases/" + d.get("id").asLong(),
                user("outsider").authorities(new SimpleGrantedAuthority("finance:read")),
                403);
        mvc.perform(
                        post(FIN + "/bases")
                                .with(finance("fin-maker", "write"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(in)))
                .andExpect(status().isForbidden());
        var waiting = sourceOrder(false);
        assertThat(read(sourcePath(waiting), finance("fin-maker", "read"), 200).isEmpty()).isTrue();
    }

    @Test
    void rejectionReleasesSourceAndConcurrentReviewCannotDoubleConfirm() throws Exception {
        initialize();
        var d = createBasis();
        long id = d.get("id").asLong();
        d = act(d, "submit", "fin-maker", "write", 200);
        var approval = command(d);
        var rejection = command(d);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var approve =
                    pool.submit(
                            () -> {
                                start.await();
                                return mvc.perform(
                                                post(FIN + "/bases/" + id + "/approve")
                                                        .with(finance("checker", "review"))
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(json.writeValueAsString(approval)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var reject =
                    pool.submit(
                            () -> {
                                start.await();
                                return mvc.perform(
                                                post(FIN + "/bases/" + id + "/reject")
                                                        .with(finance("checker2", "review"))
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json.writeValueAsString(rejection)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            start.countDown();
            assertThat(List.of(approve.get(30, TimeUnit.SECONDS), reject.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        d = read("/bases/" + id, finance("reader", "read"), 200);
        assertThat(d.get("balanceMatches").asBoolean()).isTrue();
        var another = createBasis();
        another = act(another, "submit", "fin-maker", "write", 200);
        act(another, "reject", "checker", "review", 200);
        assertThat(
                        db.queryForObject(
                                "SELECT active_source FROM fin_basis WHERE id=?",
                                String.class,
                                another.get("id").asLong()))
                .isNull();
    }

    @Test
    void submitterCannotReviewAfterRoleChangeAndChangedSourceBlocksApproval() throws Exception {
        initialize();
        var d = createBasis();
        d = act(d, "submit", "submitter", "write", 200);
        act(d, "approve", "submitter", "review", 409);
        long fact = d.get("fact_id").asLong();
        var original =
                db.queryForObject(
                        "SELECT qualified_qty FROM wms_quality_item WHERE receipt_item_id=?",
                        java.math.BigDecimal.class,
                        fact);
        try {
            db.update(
                    "UPDATE wms_quality_item SET qualified_qty=qualified_qty-1,rejected_qty=rejected_qty+1 WHERE receipt_item_id=?",
                    fact);
            act(d, "approve", "checker", "review", 409);
            assertThat(
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM fin_open_item WHERE basis_id=?",
                                    Long.class,
                                    d.get("id").asLong()))
                    .isZero();
        } finally {
            db.update(
                    "UPDATE wms_quality_item SET qualified_qty=?,rejected_qty=0 WHERE receipt_item_id=?",
                    original,
                    fact);
        }
    }
}
