package io.github.acczff.mdop.common.security;

import java.util.List;

public interface WarehouseDirectory {
    record Entry(long id, String code, String name) {}

    List<Entry> warehouses();
}
