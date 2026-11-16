package io.github.acczff.mdop.wms.inventory.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;

class InventoryLedgerTest {
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

    private JdbcClient db;
    private JdbcClient.StatementSpec stmt;
    private JdbcClient.ResultQuerySpec querySpec;
    private InventoryLedger ledger;

    @BeforeEach
    void setUp() {
        db = mock(JdbcClient.class);
        stmt = mock(JdbcClient.StatementSpec.class);
        querySpec = mock(JdbcClient.ResultQuerySpec.class);
        when(db.sql(anyString())).thenReturn(stmt);
        when(stmt.param(any())).thenReturn(stmt);
        when(stmt.params(any(Object[].class))).thenReturn(stmt);
        when(stmt.query()).thenReturn(querySpec);
        when(stmt.update()).thenReturn(1);

        CurrentActorProvider actor = () -> "tester";
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ledger = new InventoryLedger(db, actor, clock);
    }

    @Test
    void postMovementRejectsFrozenBalanceWhenRequired() {
        when(querySpec.listOfRows())
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id",
                                        10L,
                                        "on_hand_qty",
                                        new BigDecimal("5.000000"),
                                        "available_qty",
                                        BigDecimal.ZERO,
                                        "active_freeze_id",
                                        99L)));

        var cmd =
                InventoryLedger.MovementCommand.of(
                        10L,
                        new BigDecimal("-1.000000"),
                        new BigDecimal("-1.000000"),
                        true,
                        true,
                        "TRANSFER_OUT",
                        InventoryLedger.LedgerRef.transfer(1L),
                        "TRANSFER_CONFLICT",
                        "库存不足");

        assertThatThrownBy(() -> ledger.postMovement(cmd))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("STOCK_FROZEN");
        verify(stmt, never()).update();
    }

    @Test
    void postMovementRejectsNegativeResultingOnHand() {
        when(querySpec.listOfRows())
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", 10L,
                                        "on_hand_qty", new BigDecimal("2.000000"),
                                        "available_qty", new BigDecimal("2.000000"))));

        var cmd =
                InventoryLedger.MovementCommand.of(
                        10L,
                        new BigDecimal("-3.000000"),
                        new BigDecimal("-3.000000"),
                        true,
                        false,
                        "TRANSFER_OUT",
                        InventoryLedger.LedgerRef.transfer(1L),
                        "TRANSFER_CONFLICT",
                        "库存不足");

        assertThatThrownBy(() -> ledger.postMovement(cmd))
                .isInstanceOf(BusinessException.class)
                .hasMessage("库存不足")
                .extracting("code")
                .isEqualTo("TRANSFER_CONFLICT");
        verify(stmt, never()).update();
    }

    @Test
    void postMovementRejectsOverflowBeyondDecimal18Scale6() {
        when(querySpec.listOfRows())
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", 10L,
                                        "on_hand_qty", InventoryLedger.MAX_QUANTITY,
                                        "available_qty", BigDecimal.ZERO)));

        var cmd =
                InventoryLedger.MovementCommand.of(
                        10L,
                        new BigDecimal("0.000001"),
                        BigDecimal.ZERO,
                        false,
                        false,
                        null,
                        InventoryLedger.LedgerRef.receipt(1L, 2L),
                        "CONFLICT",
                        "库存不足");

        assertThatThrownBy(() -> ledger.postMovement(cmd))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void postMovementUpdatesBalanceAndWritesTransaction() {
        when(querySpec.listOfRows())
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", 10L,
                                        "on_hand_qty", new BigDecimal("10.000000"),
                                        "available_qty", new BigDecimal("10.000000"))));

        var cmd =
                InventoryLedger.MovementCommand.of(
                        10L,
                        new BigDecimal("2.500000"),
                        new BigDecimal("2.500000"),
                        true,
                        false,
                        "TRANSFER_IN",
                        InventoryLedger.LedgerRef.transfer(42L),
                        "TRANSFER_CONFLICT",
                        "库存不足");

        ledger.postMovement(cmd);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(db, org.mockito.Mockito.times(3)).sql(sqlCaptor.capture());
        List<String> sqls = new ArrayList<>(sqlCaptor.getAllValues());
        assertThat(sqls.get(0))
                .contains("SELECT * FROM wms_inventory_balance WHERE id=? FOR UPDATE");
        assertThat(sqls.get(1))
                .contains(
                        "UPDATE wms_inventory_balance SET on_hand_qty=?,available_qty=IF(active_freeze_id IS NULL,available_qty+?,0) WHERE id=?");
        assertThat(sqls.get(2)).contains("INSERT INTO wms_inventory_transaction(");
        assertThat(sqls.get(2)).contains("transfer_id");
    }

    @Test
    void postCountSkipsWriteWhenDeltaIsZeroAndWritesGainWhenPositive() {
        when(querySpec.listOfRows())
                .thenReturn(
                        List.of(Map.of("id", 5L, "on_hand_qty", new BigDecimal("8.000000"))),
                        List.of(Map.of("id", 5L, "on_hand_qty", new BigDecimal("8.000000"))));

        ledger.postCount(5L, new BigDecimal("8.000000"), 100L);
        verify(stmt, never()).update();

        ledger.postCount(5L, new BigDecimal("9.500000"), 100L);
        verify(stmt, org.mockito.Mockito.times(2)).update();
    }

    @Test
    void applyReservationValidatesReservedQuantity() {
        when(querySpec.listOfRows())
                .thenReturn(
                        List.of(
                                Map.of(
                                        "id", 7L,
                                        "reserved_qty", new BigDecimal("1.000000"),
                                        "on_hand_qty", new BigDecimal("5.000000"),
                                        "available_qty", new BigDecimal("4.000000"))));

        var cmd =
                new InventoryLedger.ReservationCommand(
                        7L,
                        new BigDecimal("-2.000000"),
                        new BigDecimal("2.000000"),
                        BigDecimal.ZERO,
                        true,
                        "SALES_CONFLICT");

        assertThatThrownBy(() -> ledger.applyReservation(cmd))
                .isInstanceOf(BusinessException.class)
                .hasMessage("预占库存不足")
                .extracting("code")
                .isEqualTo("SALES_CONFLICT");
        verify(stmt, never()).update();
    }
}
