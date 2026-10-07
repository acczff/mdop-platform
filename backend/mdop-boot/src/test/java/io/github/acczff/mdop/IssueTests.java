package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;

@SpringBootTest(
        properties = {
            "mdop.auth.operators[0].username=issue-operator",
            "mdop.auth.operators[0].password=Inventory-Test-Only-2026!",
            "mdop.auth.operators[0].authorities=wms:inventory:read,wms:issue:read,wms:issue:reserve,wms:issue:cancel,wms:issue:confirm,wms:warehouse:1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueTests extends InventoryScenarioSupport {
    @Test
    void reservationIssueAndRetriesPreserveTotalAndDimensions() throws Exception {
        long balance = ready()[0];
        lineSide();
        String no = key();
        var input = demandBody(no, "12.123456");
        long id = postAs("/api/local/mes-demands", input, admin(), 201).get("id").asLong();
        assertThat(postAs("/api/local/mes-demands", input, admin(), 201).get("id").asLong())
                .isEqualTo(id);
        postAs("/api/local/mes-demands", demandBody(no, "13"), admin(), 409);
        confirmIssue(id, 409);
        reserve(id, balance, 200);
        reserve(id, balance, 200);
        assertThat(qty(balance)).isEqualByComparingTo("80");
        assertThat(stock(balance, "available_qty")).isEqualByComparingTo("67.876544");
        assertThat(stock(balance, "reserved_qty")).isEqualByComparingTo("12.123456");
        confirmIssue(id, 200);
        confirmIssue(id, 200);
        reserve(id, balance, 200);
        cancelIssue(id, 409);
        var row = read("/api/v1/wms/issues/" + id);
        long target = row.get("target_balance_id").asLong();
        assertThat(qty(balance).add(qty(target))).isEqualByComparingTo("80");
        assertThat(stock(balance, "reserved_qty")).isZero();
        assertThat(qty(target)).isEqualByComparingTo("12.123456");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE issue_id=?",
                                Long.class,
                                id))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(change_qty) FROM wms_inventory_transaction WHERE issue_id=?",
                                BigDecimal.class,
                                id))
                .isZero();
        assertThat(
                        db.queryForMap(
                                "SELECT material_id,supplier_id,batch_no,date_code,production_date,expiry_date,owner_type,owner_id FROM wms_inventory_balance WHERE id=?",
                                target))
                .isEqualTo(
                        db.queryForMap(
                                "SELECT material_id,supplier_id,batch_no,date_code,production_date,expiry_date,owner_type,owner_id FROM wms_inventory_balance WHERE id=?",
                                balance));
        assertThat(read("/api/v1/wms/issues/" + id + "/events").toString())
                .contains("RESERVE", "CONSUME");
        assertThat(read("/api/v1/wms/stock/" + balance + "/transactions").get("items").toString())
                .contains("ISSUE_OUT", "issue_id");
    }

    @Test
    void cancellationReleasesOnceAndInvalidatesCountSnapshot() throws Exception {
        long balance = ready()[0];
        lineSide();
        long count = count(balance);
        submitCount(count, "79");
        long id = demand("20");
        reserve(id, balance, 200);
        postAs(
                "/api/v1/wms/counts",
                Map.of("balanceId", balance, "idempotencyKey", key()),
                admin(),
                409);
        cancelIssue(id, 200);
        cancelIssue(id, 200);
        confirmIssue(id, 409);
        reserve(id, balance, 409);
        assertThat(qty(balance)).isEqualByComparingTo("80");
        assertThat(stock(balance, "available_qty")).isEqualByComparingTo("80");
        assertThat(stock(balance, "reserved_qty")).isZero();
        approve(count, 409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_reservation_event WHERE issue_id=?",
                                Long.class,
                                id))
                .isEqualTo(2);
        postAs("/api/v1/wms/issues/" + id + "/cancel", Map.of("reason", "变更原因"), admin(), 409);
        long open = demand("10");
        cancelIssue(open, 200);
        assertThat(read("/api/v1/wms/issues/" + open + "/events").size()).isZero();
    }

    @Test
    void concurrentReservationsAndOrdinaryTransferCannotSpendReservedStock() throws Exception {
        var b = ready();
        lineSide();
        long a = demand("50"), c = demand("50");
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks =
                    List.of(() -> reserveStatus(a, b[0]), () -> reserveStatus(c, b[0]));
            var results = pool.invokeAll(tasks);
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stock(b[0], "reserved_qty")).isEqualByComparingTo("50");
        postAs("/api/v1/wms/transfers", input(b[0], b[2], "31", key()), admin(), 409);
        postAs("/api/v1/wms/transfers", input(b[0], b[2], "30", key()), admin(), 201);
        long winner =
                db.queryForObject(
                        "SELECT id FROM wms_material_issue WHERE warehouse_id=? AND status='RESERVED'",
                        Long.class,
                        warehouse);
        confirmIssue(winner, 200);
        assertThat(qty(b[0])).isZero();
    }

    int reserveStatus(long id, long balance) throws Exception {
        return mvc.perform(
                        post("/api/v1/wms/issues/" + id + "/reserve")
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "balanceId",
                                                        balance,
                                                        "targetLocationId",
                                                        targetLocation))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void permissionsScopesValidationAndTargetRules() throws Exception {
        long balance = ready()[0];
        lineSide();
        long id = demand("10");
        assertThat(users.loadUserByUsername("issue-operator").getAuthorities())
                .extracting("authority")
                .contains("wms:issue:confirm");
        mvc.perform(get("/api/v1/wms/issues/" + id).with(operator("source-only", "wms:issue:read")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/v1/wms/issues")
                                .param("warehouseId", "" + warehouse)
                                .param("targetWarehouseId", "" + targetWarehouse)
                                .with(operator("source-only", "wms:issue:read")))
                .andExpect(status().isForbidden());
        postAs(
                "/api/local/mes-demands",
                demandBody(key(), "10"),
                both("operator", "wms:issue:reserve"),
                403);
        postAs(
                "/api/v1/wms/issues/" + id + "/reserve",
                Map.of("balanceId", balance, "targetLocationId", targetLocation),
                operator("source-only", "wms:issue:reserve"),
                403);
        postAs(
                "/api/v1/wms/issues/" + id + "/reserve",
                Map.of("balanceId", balance, "targetLocationId", location),
                admin(),
                409);
        postAs("/api/local/mes-demands", demandBody(key(), "0"), admin(), 400);
        postAs("/api/local/mes-demands", demandBody(key(), "1.1234567"), admin(), 400);
        mvc.perform(post("/api/v1/wms/issues/" + id + "/confirm").with(admin()))
                .andExpect(status().isForbidden());
        postAs(
                "/api/v1/wms/issues/" + id + "/reserve",
                Map.of("balanceId", balance, "targetLocationId", targetLocation),
                both("storekeeper", "wms:issue:reserve"),
                200);
        postAs(
                "/api/v1/wms/issues/" + id + "/confirm",
                Map.of(),
                both("reader", "wms:issue:read"),
                403);
        postAs(
                "/api/v1/wms/issues/" + id + "/confirm",
                Map.of(),
                both("storekeeper", "wms:issue:confirm"),
                200);
    }

    @Test
    void expiredRejectedAndDisabledStockCannotIssueButCanRelease() throws Exception {
        long balance = ready()[0];
        lineSide();
        long id = demand("10");
        long rejected =
                db.queryForObject(
                        "SELECT id FROM wms_inventory_balance WHERE warehouse_id=? AND quality_status='REJECTED'",
                        Long.class,
                        warehouse);
        reserve(id, rejected, 409);
        reserve(id, balance, 200);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", balance);
        confirmIssue(id, 409);
        db.update("UPDATE mdm_warehouse SET status='DISABLED' WHERE id=?", warehouse);
        cancelIssue(id, 200);
        assertThat(stock(balance, "reserved_qty")).isZero();
        assertThat(stock(balance, "available_qty")).isEqualByComparingTo("80");
    }

    @Test
    void secondLedgerFailureRollsBackBothBalancesAndReservation() throws Exception {
        long balance = ready()[0];
        lineSide();
        long id = demand("20");
        reserve(id, balance, 200);
        db.execute(
                "ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_test_issue_failure CHECK(transaction_type<>'ISSUE_IN' OR issue_id<>"
                        + id
                        + ")");
        try {
            confirmIssue(id, 503);
        } finally {
            db.execute("ALTER TABLE wms_inventory_transaction DROP CHECK ck_test_issue_failure");
        }
        assertThat(qty(balance)).isEqualByComparingTo("80");
        assertThat(stock(balance, "reserved_qty")).isEqualByComparingTo("20");
        assertThat(read("/api/v1/wms/issues/" + id).get("status").asText()).isEqualTo("RESERVED");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE issue_id=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_balance WHERE warehouse_id=?",
                                Long.class,
                                targetWarehouse))
                .isZero();
        assertThat(read("/api/v1/wms/issues/" + id + "/events").size()).isEqualTo(1);
        confirmIssue(id, 200);
    }

    @Test
    void multipleReservationsShareBalanceAndReuseLineSideDimension() throws Exception {
        long balance = ready()[0];
        lineSide();
        long a = demand("10"), b = demand("20"), c = demand("5");
        reserve(a, balance, 200);
        reserve(b, balance, 200);
        reserve(c, balance, 200);
        assertThat(stock(balance, "reserved_qty")).isEqualByComparingTo("35");
        confirmIssue(a, 200);
        cancelIssue(b, 200);
        confirmIssue(c, 200);
        assertThat(qty(balance)).isEqualByComparingTo("65");
        assertThat(stock(balance, "available_qty")).isEqualByComparingTo("65");
        assertThat(stock(balance, "reserved_qty")).isZero();
        long target = read("/api/v1/wms/issues/" + a).get("target_balance_id").asLong();
        assertThat(read("/api/v1/wms/issues/" + c).get("target_balance_id").asLong())
                .isEqualTo(target);
        assertThat(qty(target)).isEqualByComparingTo("15");
    }

    @Test
    void cancelRacingConfirmHasExactlyOneTerminalOutcome() throws Exception {
        long balance = ready()[0];
        lineSide();
        long id = demand("20");
        reserve(id, balance, 200);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks =
                    List.of(
                            () -> terminalStatus(id, "cancel"),
                            () -> terminalStatus(id, "confirm"));
            var results = pool.invokeAll(tasks);
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stock(balance, "reserved_qty")).isZero();
        String status = read("/api/v1/wms/issues/" + id).get("status").asText();
        assertThat(qty(balance)).isEqualByComparingTo(status.equals("ISSUED") ? "60" : "80");
        assertThat(read("/api/v1/wms/issues/" + id + "/events").size()).isEqualTo(2);
    }

    int terminalStatus(long id, String action) throws Exception {
        return mvc.perform(
                        post("/api/v1/wms/issues/" + id + "/" + action)
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("reason", "需求取消"))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void demandStorageFailureCanRetryOriginalNumber() throws Exception {
        lineSide();
        String no = "FAIL-" + key();
        var body = demandBody(no, "10");
        db.execute(
                "ALTER TABLE wms_material_issue ADD CONSTRAINT ck_test_demand_failure CHECK(demand_no<>'"
                        + no
                        + "')");
        try {
            postAs("/api/local/mes-demands", body, admin(), 503);
        } finally {
            db.execute("ALTER TABLE wms_material_issue DROP CHECK ck_test_demand_failure");
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_material_issue WHERE demand_no=?",
                                Long.class,
                                no))
                .isZero();
        postAs("/api/local/mes-demands", body, admin(), 201);
        assertThat(read("/api/local/mes-demands/capabilities").get("enabled").asBoolean()).isTrue();
    }
}
