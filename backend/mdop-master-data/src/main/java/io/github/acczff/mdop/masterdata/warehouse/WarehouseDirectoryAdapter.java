package io.github.acczff.mdop.masterdata.warehouse;

import io.github.acczff.mdop.common.security.WarehouseDirectory;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class WarehouseDirectoryAdapter implements WarehouseDirectory {
    private final JdbcTemplate db;

    public WarehouseDirectoryAdapter(JdbcTemplate db) {
        this.db = db;
    }

    public List<Entry> warehouses() {
        return db.query(
                "SELECT id, code, name FROM mdm_warehouse ORDER BY id",
                (rs, n) -> new Entry(rs.getLong("id"), rs.getString("code"), rs.getString("name")));
    }
}
