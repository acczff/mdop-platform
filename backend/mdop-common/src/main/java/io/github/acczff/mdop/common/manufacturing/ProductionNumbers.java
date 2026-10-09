package io.github.acczff.mdop.common.manufacturing;

import io.github.acczff.mdop.common.BusinessException;
import java.util.Locale;

public final class ProductionNumbers {
    private ProductionNumbers() {}

    public static void requireLegacy(String number) {
        if (number != null && number.trim().toUpperCase(Locale.ROOT).startsWith("MFG-"))
            throw new BusinessException(
                    409,
                    "ERP_PRODUCTION_NOT_CONNECTED",
                    "MFG- 为 ERP 生产订单保留编号，领料和完工关联尚未开放，不能从模拟入口绕过授权");
    }
}
