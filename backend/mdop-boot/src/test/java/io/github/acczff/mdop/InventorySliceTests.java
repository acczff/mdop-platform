package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.wms.receiving.ReceivingAccess;
import io.github.acczff.mdop.wms.receiving.application.ReceivingModels.Balance;
import io.github.acczff.mdop.wms.receiving.application.ReceivingQueries;
import io.github.acczff.mdop.wms.receiving.infrastructure.JdbcArrivalLock;
import io.github.acczff.mdop.wms.receiving.infrastructure.JdbcReceivingQueries;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 库存切片测试：只启动 MySQL 与 Flyway，不加载 Web、消息与安全过滤器。
 *
 * <p>覆盖 P0 收口前必须守住的四类不变量：余额查询按仓库隔离与限量、余额维度唯一、
 * 到货加锁的仓库范围校验、以及“通知不存在时静默返回”的契约。
 * 这些用例不依赖应用上下文，单类运行通常在 20 秒内完成。
 */
@Testcontainers
class InventorySliceTests {

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer("mysql:8.4.10")
                    .withDatabaseName("mdop_slice")
                    .withUsername("slice_user")
                    .withPassword("slice_only_password");

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static HikariDataSource dataSource;
    private static JdbcClient db;
    private static ReceivingQueries queries;
    private static JdbcArrivalLock arrivalLock;

    @BeforeAll
    static void migrate() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        db = JdbcClient.create(dataSource);
        queries = new JdbcReceivingQueries(db);
        arrivalLock = new JdbcArrivalLock(db, new ReceivingAccess());
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void balanceQueryIsScopedToWarehouseAndRespectsLimit() {
        long warehouseA = warehouse("W-A");
        long warehouseB = warehouse("W-B");
        long locationA = location(warehouseA, "L-A");
        long locationB = location(warehouseB, "L-B");
        long material = material("M-1");
        long supplier = supplier("S-1");

        balance("K-A1", warehouseA, locationA, material, supplier, "10");
        balance("K-A2", warehouseA, locationA, material, supplier, "5");
        balance("K-B1", warehouseB, locationB, material, supplier, "7");

        List<Balance> scoped = queries.balances(warehouseA, 10);
        assertThat(scoped).hasSize(2);
        assertThat(scoped).allSatisfy(b -> assertThat(b.warehouseId()).isEqualTo(warehouseA));
        assertThat(scoped.getFirst().id()).isGreaterThan(scoped.getLast().id());

        assertThat(queries.balances(warehouseA, 1)).hasSize(1);
        assertThat(queries.balances(warehouseB, 10))
                .singleElement()
                .satisfies(b -> assertThat(b.onHandQty()).isEqualByComparingTo("7"));
    }

    @Test
    void balanceDimensionIsUnique() {
        long warehouse = warehouse("W-U");
        long location = location(warehouse, "L-U");
        long material = material("M-U");
        long supplier = supplier("S-U");
        String stockKey = key("K-U");

        balance(stockKey, warehouse, location, material, supplier, "1");

        assertThatThrownBy(() -> balance(stockKey, warehouse, location, material, supplier, "2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void arrivalLockRejectsWarehouseOutsideScope() {
        long allowed = warehouse("W-OK");
        long denied = warehouse("W-NO");
        long supplier = supplier("S-LOCK");
        long arrival = arrival("MDOP_PURCHASING", supplier, allowed);

        authenticate("wms:warehouse:" + denied);

        assertThatThrownBy(() -> arrivalLock.lockForArrival(arrival))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        error ->
                                assertThat(((BusinessException) error).getStatus()).isEqualTo(403));
    }

    @Test
    void arrivalLockAllowsScopedWarehouseAndIgnoresMissingArrival() {
        long warehouse = warehouse("W-SCOPE");
        long supplier = supplier("S-SCOPE");
        long arrival = arrival("MDOP_PURCHASING", supplier, warehouse);

        authenticate("wms:warehouse:" + warehouse);
        assertThatCode(() -> arrivalLock.lockForArrival(arrival)).doesNotThrowAnyException();

        SecurityContextHolder.clearContext();
        assertThatCode(() -> arrivalLock.lockForArrival(999_999_999L)).doesNotThrowAnyException();
    }

    private static void authenticate(String authority) {
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        "slice", null, List.of(new SimpleGrantedAuthority(authority)));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private static String key(String prefix) {
        return (prefix + "-" + SEQ.incrementAndGet() + "-" + "0".repeat(64)).substring(0, 64);
    }

    private static long warehouse(String code) {
        String unique = code + "-" + SEQ.incrementAndGet();
        db.sql(
                        "INSERT INTO mdm_warehouse(code,name,purpose,form,management_category,status,version,created_by,created_at,updated_by,updated_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        unique,
                        unique,
                        "RAW_MATERIAL",
                        "PHYSICAL",
                        "GENERAL",
                        "ENABLED",
                        0,
                        "slice",
                        LocalDateTime.now(),
                        "slice",
                        LocalDateTime.now())
                .update();
        return db.sql("SELECT id FROM mdm_warehouse WHERE code=?")
                .param(unique)
                .query(Long.class)
                .single();
    }

    private static long location(long warehouseId, String code) {
        String unique = code + "-" + SEQ.incrementAndGet();
        db.sql(
                        "INSERT INTO mdm_location(warehouse_id,code,name,area_type,created_by,created_at) VALUES(?,?,?,?,?,?)")
                .params(warehouseId, unique, unique, "STORAGE", "slice", LocalDateTime.now())
                .update();
        return db.sql("SELECT id FROM mdm_location WHERE warehouse_id=? AND code=?")
                .params(warehouseId, unique)
                .query(Long.class)
                .single();
    }

    private static long material(String code) {
        String unique = code + "-" + SEQ.incrementAndGet();
        db.sql(
                        "INSERT INTO mdm_material(code,name,unit,tracking_mode,require_date_code,require_expiry,created_by,created_at,unit_id) "
                                + "VALUES(?,?,?,?,?,?,?,?,(SELECT id FROM mdm_unit WHERE code='UNIT-PIECE'))")
                .params(
                        unique,
                        unique,
                        "PCS",
                        "QUANTITY",
                        false,
                        false,
                        "slice",
                        LocalDateTime.now())
                .update();
        return db.sql("SELECT id FROM mdm_material WHERE code=?")
                .param(unique)
                .query(Long.class)
                .single();
    }

    private static long supplier(String code) {
        String unique = code + "-" + SEQ.incrementAndGet();
        db.sql("INSERT INTO mdm_supplier(code,name,created_by,created_at) VALUES(?,?,?,?)")
                .params(unique, unique, "slice", LocalDateTime.now())
                .update();
        return db.sql("SELECT id FROM mdm_supplier WHERE code=?")
                .param(unique)
                .query(Long.class)
                .single();
    }

    private static void balance(
            String stockKey,
            long warehouseId,
            long locationId,
            long materialId,
            long supplierId,
            String quantity) {
        db.sql(
                        "INSERT INTO wms_inventory_balance(stock_key,warehouse_id,location_id,material_id,supplier_id,batch_no,date_code,quality_status,owner_type,on_hand_qty,available_qty) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        stockKey,
                        warehouseId,
                        locationId,
                        materialId,
                        supplierId,
                        "BATCH-1",
                        "DATE-1",
                        "QUALIFIED",
                        "ENTERPRISE",
                        new BigDecimal(quantity),
                        new BigDecimal(quantity))
                .update();
    }

    private static long arrival(String sourceSystem, long supplierId, long warehouseId) {
        String noticeNo = "N-" + SEQ.incrementAndGet();
        db.sql(
                        "INSERT INTO wms_arrival_notice(source_system,external_notice_no,purchase_order_no,supplier_id,supplier_code,supplier_name,warehouse_id,status,version,created_by,created_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        sourceSystem,
                        noticeNo,
                        "PO-" + noticeNo,
                        supplierId,
                        "SUP-" + noticeNo,
                        "供应商 " + noticeNo,
                        warehouseId,
                        "PENDING_RECEIPT",
                        0,
                        "slice",
                        LocalDateTime.now())
                .update();
        return db.sql("SELECT id FROM wms_arrival_notice WHERE external_notice_no=?")
                .param(noticeNo)
                .query(Long.class)
                .single();
    }
}
