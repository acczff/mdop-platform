package io.github.acczff.mdop.system.identity;

import io.github.acczff.mdop.common.BusinessException;
import java.util.*;

public final class PermissionCatalog {
    private PermissionCatalog() {}

    public static final Set<String> BUSINESS =
            Set.of(
                    "warehouse:read",
                    "wms:arrival:read",
                    "wms:inventory:read",
                    "wms:freeze:read",
                    "wms:freeze:create",
                    "wms:freeze:review",
                    "wms:cross-transfer:read",
                    "wms:cross-transfer:create",
                    "wms:cross-transfer:review",
                    "wms:cross-transfer:ship",
                    "wms:cross-transfer:receive",
                    "wms:cross-transfer:cancel",
                    "wms:sales:read",
                    "wms:sales:reserve",
                    "wms:sales:pick",
                    "wms:sales:review",
                    "wms:sales:ship",
                    "wms:sales:cancel",
                    "wms:finished:read",
                    "wms:finished:receive",
                    "wms:finished:putaway",
                    "wms:production:read",
                    "wms:production:reverse",
                    "wms:production:review",
                    "wms:production:confirm",
                    "wms:production:cancel",
                    "wms:issue:read",
                    "wms:issue:reserve",
                    "wms:issue:cancel",
                    "wms:issue:confirm",
                    "wms:count:read",
                    "wms:count:create",
                    "wms:count:submit",
                    "wms:count:review",
                    "wms:count:cancel",
                    "wms:transfer:read",
                    "wms:transfer:confirm",
                    "wms:receipt:create",
                    "wms:receipt:submit",
                    "wms:correction:read",
                    "wms:correction:create",
                    "wms:correction:approve",
                    "wms:quality:read",
                    "wms:putaway:confirm",
                    "wms:return:read",
                    "wms:return:create",
                    "wms:return:approve",
                    "wms:return:confirm");

    public record Role(String code, String name, Set<String> permissions) {}

    public static List<Role> roles() {
        var read = new TreeSet<String>();
        var operate = new TreeSet<String>();
        var review = new TreeSet<String>();
        for (var p : BUSINESS) {
            if (p.endsWith(":read")) {
                read.add(p);
                operate.add(p);
                review.add(p);
            } else if (p.endsWith(":approve") || p.endsWith(":review")) review.add(p);
            else operate.add(p);
        }
        read.add("purchasing:read");
        read.add("sales:read");
        return List.of(
                new Role(
                        "SALES_OPERATOR",
                        "销售作业员",
                        Set.of("warehouse:read", "sales:read", "sales:write")),
                new Role(
                        "SALES_REVIEWER",
                        "销售审批员",
                        Set.of("warehouse:read", "sales:read", "sales:review")),
                new Role(
                        "PURCHASE_OPERATOR",
                        "采购作业员",
                        Set.of("warehouse:read", "purchasing:read", "purchasing:write")),
                new Role(
                        "PURCHASE_REVIEWER",
                        "采购审批员",
                        Set.of("warehouse:read", "purchasing:read", "purchasing:review")),
                new Role("SYSTEM_ADMIN", "系统管理员", Set.of("iam:manage")),
                new Role("MASTER_DATA", "基础资料管理员", Set.of("warehouse:read", "masterdata:write")),
                new Role("WAREHOUSE_OPERATOR", "仓库作业员", operate),
                new Role("BUSINESS_REVIEWER", "业务审批员", review),
                new Role("READER", "业务只读", read),
                new Role("INTEGRATION_OPERATOR", "模拟接口与消息管理员", Set.of("integration:simulate")));
    }

    public static Set<String> resolve(Set<String> codes, Set<Long> warehouses) {
        if (codes == null
                || codes.isEmpty()
                || codes.size() > roles().size()
                || warehouses == null
                || warehouses.size() > 1000) throw invalid("请选择角色和仓库范围");
        if (codes.contains("SYSTEM_ADMIN") && (codes.size() != 1 || !warehouses.isEmpty()))
            throw invalid("系统管理与业务职责必须使用不同账号");
        if (codes.contains("BUSINESS_REVIEWER") && codes.contains("WAREHOUSE_OPERATOR"))
            throw invalid("业务审批与作业职责必须使用不同账号");
        if (codes.contains("PURCHASE_REVIEWER") && codes.contains("PURCHASE_OPERATOR"))
            throw invalid("采购审批与作业职责必须使用不同账号");
        if (codes.contains("SALES_REVIEWER") && codes.contains("SALES_OPERATOR"))
            throw invalid("销售审批与作业职责必须使用不同账号");
        var result = new TreeSet<String>();
        for (var code : codes)
            result.addAll(
                    roles().stream()
                            .filter(r -> r.code().equals(code))
                            .findFirst()
                            .orElseThrow(() -> invalid("不支持的角色"))
                            .permissions());
        for (var id : warehouses) {
            if (id == null || id <= 0) throw invalid("仓库范围不合法");
            result.add("wms:warehouse:" + id);
        }
        return result;
    }

    public static void validateLegacy(List<String> permissions) {
        if (permissions == null
                || permissions.isEmpty()
                || permissions.stream()
                        .anyMatch(
                                p ->
                                        p == null
                                                || (!BUSINESS.contains(p)
                                                        && !p.matches(
                                                                "wms:warehouse:[1-9][0-9]*"))))
            throw invalid("操作账号包含不受支持的权限");
        if (permissions.contains("wms:correction:approve")
                && (permissions.contains("wms:correction:create")
                        || permissions.contains("wms:receipt:submit")))
            throw invalid("审批账号不能同时配置申请或收货提交权限");
        if (permissions.contains("wms:return:approve")
                && (permissions.contains("wms:return:create")
                        || permissions.contains("wms:return:confirm")))
            throw invalid("退货审批账号不能同时配置申请或实际退货确认权限");
    }

    static BusinessException invalid(String message) {
        return new BusinessException(400, "INVALID_ACCOUNT", message);
    }
}
