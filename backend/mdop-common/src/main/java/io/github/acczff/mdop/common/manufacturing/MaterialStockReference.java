package io.github.acczff.mdop.common.manufacturing;

import java.util.List;
import java.util.Map;

/** Read-only WMS facts under the caller's warehouse transaction lock. Never reserves stock. */
public interface MaterialStockReference {
    List<Map<String, Object>> read(long warehouse, List<Long> materials);
}
