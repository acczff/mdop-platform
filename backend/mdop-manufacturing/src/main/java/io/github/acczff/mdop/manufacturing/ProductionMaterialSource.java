package io.github.acczff.mdop.manufacturing;

import io.github.acczff.mdop.common.manufacturing.ProductionMaterialGuard;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ProductionMaterialSource implements ProductionMaterialGuard {
    private final JdbcClient db;

    public ProductionMaterialSource(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void lock() {
        db.sql("SELECT id FROM mfg_bom_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }

    @Override
    public void requireOpen(long orderId) {
        if (!"APPROVED"
                .equals(
                        db.sql("SELECT status FROM mfg_order WHERE id=?")
                                .param(orderId)
                                .query(String.class)
                                .single()))
            throw new io.github.acczff.mdop.common.BusinessException(
                    409, "PRODUCTION_ORDER_CLOSED", "工单已结束或未获批准，不能新增或冲正材料事实");
    }
}
