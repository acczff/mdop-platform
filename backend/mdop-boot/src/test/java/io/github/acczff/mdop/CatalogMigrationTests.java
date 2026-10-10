package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class CatalogMigrationTests {
    @Container
    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.4.10")
                    .withDatabaseName("catalog_upgrade")
                    .withUsername("upgrade_test")
                    .withPassword(UUID.randomUUID().toString());

    @Test
    void oldUnitsAndOpenDocumentsUpgradeWithoutChangingMeaningOrQuantity() {
        var source =
                new DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var locations =
                new String[] {
                    "classpath:db/migration/system",
                    "classpath:db/migration/masterdata",
                    "classpath:db/migration/wms",
                    "classpath:db/migration/integration"
                };
        Flyway.configure()
                .dataSource(source)
                .locations(locations)
                .target("202610080001")
                .load()
                .migrate();
        var db = new JdbcTemplate(source);
        db.update(
                "INSERT INTO mdm_supplier(id,code,name,created_by,created_at) VALUES(1,'SUP-OLD','旧供应商','upgrade',NOW())");
        db.update(
                "INSERT INTO mdm_warehouse(id,code,name,purpose,form,management_category,created_by,created_at,updated_by,updated_at) VALUES(1,'WH-OLD','旧仓','RAW_MATERIAL','PHYSICAL','GENERAL','upgrade',NOW(),'upgrade',NOW())");
        String[] labels = {"kg", "KG", "件", "kg"};
        for (int i = 0; i < labels.length; i++)
            db.update(
                    "INSERT INTO mdm_material(id,code,name,unit,tracking_mode,require_date_code,require_expiry,created_by,created_at) VALUES(?,?,'旧料',?,'BATCH',FALSE,FALSE,'upgrade',NOW())",
                    i + 1,
                    "MAT-" + (i + 1),
                    labels[i]);
        db.update(
                "INSERT INTO wms_arrival_notice(id,source_system,external_notice_no,purchase_order_no,supplier_id,warehouse_id,created_by,created_at) VALUES(1,'ERP','ARR-OLD','PO-OLD',1,1,'upgrade',NOW())");
        db.update(
                "INSERT INTO wms_arrival_notice_item(arrival_id,material_id,material_code,material_name,unit,notice_qty) VALUES(1,1,'MAT-1','单据旧料','kg',12.345678)");
        Flyway.configure().dataSource(source).locations(locations).load().migrate();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mdm_unit", Long.class)).isEqualTo(3);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM mdm_material m JOIN mdm_unit u ON u.id=m.unit_id WHERE CAST(m.unit AS BINARY)=CAST(u.name AS BINARY)",
                                Long.class))
                .isEqualTo(4);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(DISTINCT unit_id) FROM mdm_material WHERE id IN(1,2)",
                                Long.class))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(DISTINCT unit_id) FROM mdm_material WHERE id IN(1,4)",
                                Long.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT identity_locked FROM mdm_material WHERE id=1",
                                Boolean.class))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT identity_locked FROM mdm_material WHERE id=2",
                                Boolean.class))
                .isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT supplier_name FROM wms_arrival_notice WHERE id=1",
                                String.class))
                .isEqualTo("旧供应商");
        assertThat(
                        db.queryForObject(
                                "SELECT material_name FROM wms_arrival_notice_item WHERE arrival_id=1",
                                String.class))
                .isEqualTo("单据旧料");
        assertThat(
                        db.queryForObject(
                                "SELECT notice_qty FROM wms_arrival_notice_item WHERE arrival_id=1",
                                BigDecimal.class))
                .isEqualByComparingTo("12.345678");
        assertThatThrownBy(() -> db.update("INSERT INTO mdm_unit(code,name) VALUES('BAD',' ')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("ck_unit_name");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mdm_organization", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mdm_catalog_audit", Long.class))
                .isZero();
        assertThat(
                        Flyway.configure()
                                .dataSource(source)
                                .locations(locations)
                                .load()
                                .migrate()
                                .migrationsExecuted)
                .isZero();
    }
}
