package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import java.util.Map;

/** Shared outbound guard; inbound credits and reservation releases retain zero availability. */
public final class StockFreeze {
    private StockFreeze() {}

    public static void requireUnfrozen(Map<String, Object> balance) {
        if (balance.get("active_freeze_id") != null)
            throw new BusinessException(409, "STOCK_FROZEN", "库存已冻结，请由另一人审批解冻后再操作");
    }
}
