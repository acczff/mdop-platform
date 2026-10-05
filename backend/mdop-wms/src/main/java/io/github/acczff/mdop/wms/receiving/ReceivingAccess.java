package io.github.acczff.mdop.wms.receiving;

import io.github.acczff.mdop.common.BusinessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class ReceivingAccess {
    public void requireWarehouse(long warehouseId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed =
                authentication != null
                        && authentication.getAuthorities().stream()
                                .anyMatch(
                                        a ->
                                                a.getAuthority().equals("ROLE_ADMIN")
                                                        || a.getAuthority()
                                                                .equals(
                                                                        "wms:warehouse:"
                                                                                + warehouseId));
        if (!allowed) throw new BusinessException(403, "WAREHOUSE_ACCESS_DENIED", "没有目标仓库的数据权限");
    }
}
