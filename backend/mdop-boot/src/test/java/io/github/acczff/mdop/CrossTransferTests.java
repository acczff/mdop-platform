package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrossTransferTests extends InventoryScenarioSupport {
    Map<String, Object> receipt(String amount, String requestKey) {
        return Map.of("idempotencyKey", requestKey, "quantity", amount, "reason", "分批实收");
    }

    long shipped(String amount) throws Exception {
        long stock = ready()[0];
        destination();
        long id = create(stock, amount);
        act(id, "approve", reviewer(), 200);
        act(id, "ship", admin(), 200);
        return id;
    }

    void audit() throws Exception {
        for (var check :
                db.queryForList(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of("../../scripts/audit-inventory.sql"))))
            assertThat(((Number) check.get("mismatch_count")).longValue())
                    .as(check.get("check_name").toString())
                    .isZero();
    }

    @Test
    void partialReceiptsKeepRemainingInTransitAndFrozenDestinationUnavailable() throws Exception {
        long id = shipped("12.123456");
        var first =
                postAs(
                        URL + "/" + id + "/receipts",
                        receipt("2.123456", key()),
                        target("receive"),
                        200);
        long dest = first.get("target_balance_id").asLong();
        assertThat(first.get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(first.get("received_qty").asText()).isEqualTo("2.123456");
        assertThat(first.get("in_transit_qty").asText()).isEqualTo("10.000000");
        assertThat(first.get("received_by").isNull()).isTrue();
        assertThat(qty(dest)).isEqualByComparingTo("2.123456");
        audit();
        act(id, "cancel", admin(), 409);
        act(id, "ship", admin(), 409);
        act(id, "receive", target("receive"), 409);
        postAs(
                "/api/v1/wms/freezes",
                Map.of("balanceId", dest, "idempotencyKey", key(), "reason", "目标批次复核"),
                admin(),
                201);
        var last =
                postAs(URL + "/" + id + "/receipts", receipt("10", key()), target("receive"), 200);
        assertThat(last.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(last.get("in_transit_qty").asText()).isEqualTo("0.000000");
        assertThat(last.get("received_by").asText()).isEqualTo("receiver");
        assertThat(qty(dest)).isEqualByComparingTo("12.123456");
        assertThat(stock(dest, "available_qty")).isZero();
        var history = read(URL + "/" + id + "/history");
        assertThat(history).hasSize(5);
        assertThat(history.get(3).get("quantity").asText()).isEqualTo("2.123456");
        assertThat(history.get(4).get("remaining_qty").asText()).isEqualTo("0.000000");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE cross_transfer_id=?",
                                Long.class,
                                id))
                .isEqualTo(3);
        audit();
    }

    @Test
    void partialReceiptRejectsOverReceiptInvalidQuantityAndWrongScope() throws Exception {
        long id = shipped("1.123456");
        for (String amount : List.of("0", "-1", "0.0000001", "1000000000000"))
            postAs(URL + "/" + id + "/receipts", receipt(amount, key()), target("receive"), 400);
        postAs(
                URL + "/" + id + "/receipts",
                Map.of("idempotencyKey", key(), "reason", "缺少实收数量"),
                admin(),
                400);
        postAs(URL + "/" + id + "/receipts", receipt("1.123457", key()), target("receive"), 409);
        postAs(
                URL + "/" + id + "/receipts",
                receipt("1", key()),
                operator("source", "wms:cross-transfer:receive"),
                403);
        postAs(URL + "/" + id + "/receipts", receipt("1", key()), target("read"), 403);
        mvc.perform(
                        post(URL + "/" + id + "/receipts")
                                .with(admin())
                                .contentType("application/json")
                                .content(json.writeValueAsString(receipt("1", key()))))
                .andExpect(status().isForbidden());
        postAs(URL + "/" + id + "/receipts", receipt("1", key()), target("receive"), 200);
        postAs(URL + "/" + id + "/receipts", receipt("0.123457", key()), target("receive"), 409);
        postAs(URL + "/" + id + "/receipts", receipt("0.123456", key()), target("receive"), 200);
        postAs(URL + "/" + id + "/receipts", receipt("0.000001", key()), target("receive"), 409);
        audit();
    }

    @Test
    void receiptReplayBindsQuantityActorAndReasonAfterLaterReceipts() throws Exception {
        long id = shipped("10");
        String requestKey = key();
        var first = receipt("2", requestKey);
        postAs(URL + "/" + id + "/receipts", first, target("receive"), 200);
        postAs(
                URL + "/" + id + "/receipts",
                receipt("2.000000", requestKey),
                target("receive"),
                200);
        postAs(URL + "/" + id + "/receipts", receipt("3", requestKey), target("receive"), 409);
        postAs(URL + "/" + id + "/receipts", first, admin(), 409);
        postAs(
                URL + "/" + id + "/receipts",
                Map.of("quantity", "2", "idempotencyKey", requestKey, "reason", "其他原因"),
                target("receive"),
                409);
        postAs(URL + "/" + id + "/receipts", receipt("8", key()), target("receive"), 200);
        assertThat(
                        postAs(URL + "/" + id + "/receipts", first, target("receive"), 200)
                                .get("received_qty")
                                .asText())
                .isEqualTo("10.000000");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_cross_transfer_receipt WHERE cross_transfer_id=?",
                                Long.class,
                                id))
                .isEqualTo(2);
        audit();
    }

    @Test
    void concurrentDifferentReceiptsCannotOverReceiveAndDuplicatesApplyOnce() throws Exception {
        long id = shipped("10");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var jobs = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return mvc.perform(
                                                    post(URL + "/" + id + "/receipts")
                                                            .with(target("receive"))
                                                            .with(csrf())
                                                            .contentType("application/json")
                                                            .content(
                                                                    json.writeValueAsString(
                                                                            receipt("6", key()))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            gate.countDown();
            assertThat(
                            List.of(
                                    jobs.get(0).get(20, TimeUnit.SECONDS),
                                    jobs.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            var body = receipt("4", key());
            var repeats = new ArrayList<Future<JsonNode>>();
            for (int i = 0; i < 2; i++)
                repeats.add(
                        pool.submit(
                                () ->
                                        postAs(
                                                URL + "/" + id + "/receipts",
                                                body,
                                                target("receive"),
                                                200)));
            for (var result : repeats)
                assertThat(result.get(20, TimeUnit.SECONDS).get("status").asText())
                        .isEqualTo("RECEIVED");
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_cross_transfer_receipt WHERE cross_transfer_id=?",
                                Long.class,
                                id))
                .isEqualTo(2);
        audit();
    }

    @Test
    void receiptPersistenceFailureRollsBackBalanceActionAndProgress() throws Exception {
        long id = shipped("10");
        var body = receipt("3", key());
        db.execute(
                "ALTER TABLE wms_cross_transfer_receipt ADD CONSTRAINT ck_partial_failure CHECK(cross_transfer_id<>"
                        + id
                        + ")");
        try {
            postAs(URL + "/" + id + "/receipts", body, target("receive"), 503);
        } finally {
            db.execute("ALTER TABLE wms_cross_transfer_receipt DROP CHECK ck_partial_failure");
        }
        assertThat(read(URL + "/" + id).get("received_qty").asText()).isEqualTo("0.000000");
        assertThat(read(URL + "/" + id + "/history")).hasSize(3);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_balance WHERE warehouse_id=?",
                                Long.class,
                                targetWarehouse))
                .isZero();
        postAs(URL + "/" + id + "/receipts", body, target("receive"), 200);
        audit();
    }

    @Test
    void laterReceiptRechecksExpiryAndDestinationButAllowsDisabledSource() throws Exception {
        long id = shipped("10");
        postAs(URL + "/" + id + "/receipts", receipt("3", key()), target("receive"), 200);
        long source = read(URL + "/" + id).get("source_balance_id").asLong();
        var body = receipt("7", key());
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", source);
        postAs(URL + "/" + id + "/receipts", body, target("receive"), 409);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2099-12-31' WHERE id=?", source);
        db.update("UPDATE mdm_warehouse SET status='DISABLED' WHERE id=?", targetWarehouse);
        postAs(URL + "/" + id + "/receipts", body, target("receive"), 409);
        db.update("UPDATE mdm_warehouse SET status='ENABLED' WHERE id=?", targetWarehouse);
        db.update("UPDATE mdm_warehouse SET status='DISABLED' WHERE id=?", warehouse);
        postAs(URL + "/" + id + "/receipts", body, target("receive"), 200);
        audit();
    }

    @Test
    void freezeBlocksApprovedDispatchAndReleaseOfReservationStaysFrozen() throws Exception {
        long b = ready()[0];
        destination();
        long id = create(b, "10");
        act(id, "approve", reviewer(), 200);
        long f =
                postAs(
                                "/api/v1/wms/freezes",
                                Map.of("balanceId", b, "idempotencyKey", key(), "reason", "调拨批次调查"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        act(id, "ship", admin(), 409);
        act(id, "cancel", admin(), 200);
        assertThat(stock(b, "available_qty")).isZero();
        assertThat(stock(b, "reserved_qty")).isZero();
        assertThat(qty(b)).isEqualByComparingTo("80");
        postAs(
                "/api/v1/wms/freezes/" + f + "/release",
                Map.of("idempotencyKey", key(), "reason", "复核完成"),
                operator("reviewer", "wms:freeze:review"),
                200);
        long first = create(b, "5");
        act(first, "approve", reviewer(), 200);
        act(first, "ship", admin(), 200);
        long dest = act(first, "receive", admin(), 200).get("target_balance_id").asLong();
        postAs(
                "/api/v1/wms/freezes",
                Map.of("balanceId", dest, "idempotencyKey", key(), "reason", "目标批次调查"),
                admin(),
                201);
        long second = create(b, "2");
        act(second, "approve", reviewer(), 200);
        act(second, "ship", admin(), 200);
        act(second, "receive", admin(), 200);
        assertThat(qty(dest)).isEqualByComparingTo("7");
        assertThat(stock(dest, "available_qty")).isZero();
    }

    static final String URL = "/api/v1/wms/cross-transfers";

    void destination() throws Exception {
        targetWarehouse =
                postAs(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "CW" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                        "name",
                                        "调入仓",
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
        targetLocation =
                postAs(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        targetWarehouse,
                                        "code",
                                        "ST",
                                        "name",
                                        "正式位",
                                        "areaType",
                                        "STORAGE"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
    }

    long create(long stock, String qty) throws Exception {
        return postAs(URL, input(stock, targetLocation, qty, key()), admin(), 201)
                .get("id")
                .asLong();
    }

    JsonNode act(long id, String action, RequestPostProcessor who, int status) throws Exception {
        return postAs(
                URL + "/" + id + "/" + action,
                Map.of("idempotencyKey", key(), "reason", "调拨验收"),
                who,
                status);
    }

    RequestPostProcessor reviewer() {
        return operator("reviewer", "wms:cross-transfer:review");
    }

    RequestPostProcessor target(String permission) {
        return user("receiver")
                .authorities(
                        new SimpleGrantedAuthority("wms:cross-transfer:" + permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + targetWarehouse));
    }

    @Test
    void fullFlowPreservesSixDecimalsAndTransit() throws Exception {
        long stock = ready()[0];
        destination();
        long id = create(stock, "12.123456");
        assertThat(stock(stock, "available_qty")).isEqualByComparingTo("80");
        act(id, "approve", admin(), 409);
        act(id, "approve", reviewer(), 200);
        assertThat(stock(stock, "reserved_qty")).isEqualByComparingTo("12.123456");
        assertThat(stock(stock, "available_qty")).isEqualByComparingTo("67.876544");
        act(id, "receive", target("receive"), 409);
        act(id, "ship", target("ship"), 403);
        act(id, "ship", admin(), 200);
        assertThat(qty(stock)).isEqualByComparingTo("67.876544");
        assertThat(stock(stock, "reserved_qty")).isZero();
        assertThat(read(URL + "/" + id).get("in_transit_qty").asText()).isEqualTo("12.123456");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_balance WHERE warehouse_id=?",
                                Long.class,
                                targetWarehouse))
                .isZero();
        act(id, "cancel", admin(), 409);
        var payload = Map.of("idempotencyKey", key(), "reason", "全部实收");
        var result = postAs(URL + "/" + id + "/receive", payload, target("receive"), 200);
        long dest = result.get("target_balance_id").asLong();
        postAs(URL + "/" + id + "/receive", payload, target("receive"), 200);
        assertThat(qty(dest)).isEqualByComparingTo("12.123456");
        assertThat(qty(stock).add(qty(dest))).isEqualByComparingTo("80");
        assertThat(result.get("in_transit_qty").asText()).isEqualTo("0.000000");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE cross_transfer_id=?",
                                Long.class,
                                id))
                .isEqualTo(2);
        var s =
                db.queryForMap(
                        "SELECT material_id,supplier_id,origin_type,batch_no,date_code,production_date,expiry_date,quality_status,owner_type,owner_id FROM wms_inventory_balance WHERE id=?",
                        stock);
        assertThat(
                        db.queryForMap(
                                "SELECT material_id,supplier_id,origin_type,batch_no,date_code,production_date,expiry_date,quality_status,owner_type,owner_id FROM wms_inventory_balance WHERE id=?",
                                dest))
                .isEqualTo(s);
        assertThat(read(URL + "/" + id + "/history")).hasSize(4);
    }

    @Test
    void cancelReleasesAndInvalidatesCountEvenAfterRelease() throws Exception {
        long stock = ready()[0];
        destination();
        long count = count(stock);
        submitCount(count, "80");
        long id = create(stock, "10");
        act(id, "approve", reviewer(), 200);
        act(id, "cancel", admin(), 200);
        assertThat(stock(stock, "reserved_qty")).isZero();
        assertThat(stock(stock, "available_qty")).isEqualByComparingTo("80");
        approve(count, 409);
        act(id, "ship", admin(), 409);
        long rejected = create(stock, "10");
        act(rejected, "reject", reviewer(), 200);
        act(rejected, "approve", reviewer(), 409);
        long pending = create(stock, "10");
        act(pending, "cancel", admin(), 200);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_reservation_event WHERE cross_transfer_id IN (?,?)",
                                Long.class,
                                pending,
                                rejected))
                .isZero();
    }

    @Test
    void permissionsAndPayloadBoundReplay() throws Exception {
        long stock = ready()[0];
        destination();
        var body = input(stock, targetLocation, "10", key());
        postAs(URL, body, operator("creator", "wms:cross-transfer:create"), 403);
        long id = postAs(URL, body, admin(), 201).get("id").asLong();
        assertThat(postAs(URL, body, admin(), 201).get("id").asLong()).isEqualTo(id);
        postAs(
                URL,
                input(stock, targetLocation, "11", body.get("idempotencyKey").toString()),
                admin(),
                409);
        var a = Map.of("idempotencyKey", key(), "reason", "审批");
        postAs(URL + "/" + id + "/approve", a, reviewer(), 200);
        act(id, "ship", admin(), 200);
        assertThat(postAs(URL + "/" + id + "/approve", a, reviewer(), 200).get("status").asText())
                .isEqualTo("IN_TRANSIT");
        postAs(
                URL + "/" + id + "/approve",
                a,
                operator("another", "wms:cross-transfer:review"),
                409);
        postAs(URL + "/" + id + "/ship", a, admin(), 409);
        act(id, "receive", operator("source", "wms:cross-transfer:receive"), 403);
        mvc.perform(get(URL + "/" + id).with(target("read"))).andExpect(status().isOk());
        mvc.perform(
                        get(URL + "/" + id)
                                .with(
                                        user("outsider")
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "wms:cross-transfer:read"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(URL).param("warehouseId", String.valueOf(targetWarehouse))
                                .with(operator("source", "wms:cross-transfer:read")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(URL + "/" + id + "/receive")
                                .with(admin())
                                .contentType("application/json")
                                .content(json.writeValueAsString(a)))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentApprovalCannotOverReserve() throws Exception {
        long stock = ready()[0];
        destination();
        long first = create(stock, "60"), second = create(stock, "60");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (long id : List.of(first, second))
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return mvc.perform(
                                                    post(URL + "/" + id + "/approve")
                                                            .with(reviewer())
                                                            .with(csrf())
                                                            .contentType("application/json")
                                                            .content(
                                                                    json.writeValueAsString(
                                                                            Map.of(
                                                                                    "idempotencyKey",
                                                                                    key(),
                                                                                    "reason",
                                                                                    "并发审批"))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            gate.countDown();
            assertThat(
                            List.of(
                                    futures.get(0).get(20, TimeUnit.SECONDS),
                                    futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(stock(stock, "reserved_qty")).isEqualByComparingTo("60");
        assertThat(stock(stock, "available_qty")).isEqualByComparingTo("20");
    }

    @Test
    void shipmentFailureRollsBackReservationLedgerAndState() throws Exception {
        long stock = ready()[0];
        destination();
        long id = create(stock, "10");
        act(id, "approve", reviewer(), 200);
        db.execute(
                "ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_cross_test_failure CHECK(cross_transfer_id IS NULL OR cross_transfer_id<>"
                        + id
                        + ")");
        var body = Map.of("idempotencyKey", key(), "reason", "实际发出");
        try {
            postAs(URL + "/" + id + "/ship", body, admin(), 503);
        } finally {
            db.execute("ALTER TABLE wms_inventory_transaction DROP CHECK ck_cross_test_failure");
        }
        assertThat(qty(stock)).isEqualByComparingTo("80");
        assertThat(stock(stock, "reserved_qty")).isEqualByComparingTo("10");
        assertThat(read(URL + "/" + id).get("status").asText()).isEqualTo("APPROVED");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_reservation_event WHERE cross_transfer_id=?",
                                Long.class,
                                id))
                .isEqualTo(1);
        postAs(URL + "/" + id + "/ship", body, admin(), 200);
    }

    @Test
    void rejectsSameWarehouseLineSideExpiredAndExcessPrecision() throws Exception {
        var stocks = ready();
        long stock = stocks[0];
        destination();
        postAs(URL, input(stock, stocks[2], "1", key()), admin(), 409);
        postAs(URL, input(stock, targetLocation, "1.0000001", key()), admin(), 400);
        postAs(URL, input(stock, targetLocation, "0", key()), admin(), 400);
        long id = create(stock, "1");
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", stock);
        act(id, "approve", reviewer(), 409);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2099-12-31' WHERE id=?", stock);
        act(id, "approve", reviewer(), 200);
        act(id, "ship", admin(), 200);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", stock);
        act(id, "receive", admin(), 409);
        assertThat(read(URL + "/" + id).get("status").asText()).isEqualTo("IN_TRANSIT");
        db.update("UPDATE wms_inventory_balance SET expiry_date='2099-12-31' WHERE id=?", stock);
        lineSide();
        postAs(URL, input(stock, targetLocation, "1", key()), admin(), 409);
    }

    @Test
    void receivingFailureRollsBackThenConcurrentReplayReceivesOnce() throws Exception {
        long stock = ready()[0];
        destination();
        long id = create(stock, "0.123456");
        act(id, "approve", reviewer(), 200);
        act(id, "ship", admin(), 200);
        var payload = Map.of("idempotencyKey", key(), "reason", "目标仓实收");
        db.execute(
                "ALTER TABLE wms_cross_transfer_action ADD CONSTRAINT ck_cross_receive_failure CHECK(cross_transfer_id<>"
                        + id
                        + " OR action_type<>'RECEIVE')");
        try {
            postAs(URL + "/" + id + "/receive", payload, target("receive"), 503);
        } finally {
            db.execute("ALTER TABLE wms_cross_transfer_action DROP CHECK ck_cross_receive_failure");
        }
        assertThat(read(URL + "/" + id).get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_balance WHERE warehouse_id=?",
                                Long.class,
                                targetWarehouse))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE cross_transfer_id=?",
                                Long.class,
                                id))
                .isEqualTo(1);
        // A source warehouse may close after dispatch without blocking destination receipt.
        db.update("UPDATE mdm_warehouse SET status='DISABLED' WHERE id=?", warehouse);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<JsonNode>>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return postAs(
                                            URL + "/" + id + "/receive",
                                            payload,
                                            target("receive"),
                                            200);
                                }));
            gate.countDown();
            for (var f : futures)
                assertThat(f.get(20, TimeUnit.SECONDS).get("status").asText())
                        .isEqualTo("RECEIVED");
        }
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(on_hand_qty) FROM wms_inventory_balance WHERE warehouse_id=?",
                                java.math.BigDecimal.class,
                                targetWarehouse))
                .isEqualByComparingTo("0.123456");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_cross_transfer_action WHERE cross_transfer_id=?",
                                Long.class,
                                id))
                .isEqualTo(4);
        String audit =
                java.nio.file.Files.readString(
                        java.nio.file.Path.of("../../scripts/audit-inventory.sql"));
        for (var check : db.queryForList(audit))
            assertThat(((Number) check.get("mismatch_count")).longValue())
                    .as(check.get("check_name").toString())
                    .isZero();
    }
}
