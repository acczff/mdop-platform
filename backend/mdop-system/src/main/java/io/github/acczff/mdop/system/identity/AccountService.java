package io.github.acczff.mdop.system.identity;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.common.security.WarehouseDirectory;
import io.github.acczff.mdop.security.AccountDirectory;
import io.github.acczff.mdop.security.AccountPrincipal;
import io.github.acczff.mdop.security.OperatorProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService implements AccountDirectory {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final CurrentActorProvider actors;
    private final WarehouseDirectory warehouses;

    public AccountService(
            JdbcTemplate db,
            PasswordEncoder encoder,
            CurrentActorProvider actors,
            WarehouseDirectory warehouses) {
        this.db = db;
        this.encoder = encoder;
        this.actors = actors;
        this.warehouses = warehouses;
    }

    public record Account(
            long id,
            String username,
            String displayName,
            boolean enabled,
            long version,
            Set<String> roles,
            Set<Long> warehouseIds,
            Set<String> authorities) {}

    public record Page(List<Account> items, int page, long totalElements) {}

    public record Audit(
            long id,
            String action,
            String actor,
            String reason,
            String beforeState,
            String afterState,
            java.time.Instant createdAt) {}

    private Set<String> permissions(long id) {
        return new TreeSet<>(
                db.queryForList(
                        "SELECT permission FROM iam_user_permission WHERE user_id=?",
                        String.class,
                        id));
    }

    private Set<String> roles(long id) {
        return new TreeSet<>(
                db.queryForList(
                        "SELECT role_code FROM iam_user_role WHERE user_id=?", String.class, id));
    }

    private Account account(long id) {
        var rows =
                db.query(
                        "SELECT id,username,display_name,enabled,version FROM iam_user WHERE id=?",
                        (rs, n) -> {
                            var permissions = permissions(id);
                            var ids = new TreeSet<Long>();
                            permissions.stream()
                                    .filter(p -> p.startsWith("wms:warehouse:"))
                                    .forEach(p -> ids.add(Long.valueOf(p.substring(14))));
                            return new Account(
                                    id,
                                    rs.getString("username"),
                                    rs.getString("display_name"),
                                    rs.getBoolean("enabled"),
                                    rs.getLong("version"),
                                    roles(id),
                                    ids,
                                    permissions);
                        },
                        id);
        if (rows.isEmpty()) throw new BusinessException(404, "ACCOUNT_NOT_FOUND", "账号不存在");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public Page list(int page) {
        if (page < 0 || page > 100000) throw PermissionCatalog.invalid("页码不合法");
        var ids =
                db.queryForList(
                        "SELECT id FROM iam_user ORDER BY id LIMIT 50 OFFSET ?",
                        Long.class,
                        page * 50);
        return new Page(
                ids.stream().map(this::account).toList(),
                page,
                db.queryForObject("SELECT COUNT(*) FROM iam_user", Long.class));
    }

    @Transactional(readOnly = true)
    public List<Audit> audits(long id) {
        account(id);
        return db.query(
                "SELECT *, UNIX_TIMESTAMP(created_at) AS created_epoch FROM iam_audit WHERE user_id=? ORDER BY id DESC LIMIT 100",
                (rs, n) ->
                        new Audit(
                                rs.getLong("id"),
                                rs.getString("action"),
                                rs.getString("actor"),
                                rs.getString("reason"),
                                rs.getString("before_state"),
                                rs.getString("after_state"),
                                java.time.Instant.ofEpochMilli(
                                        rs.getBigDecimal("created_epoch")
                                                .movePointRight(3)
                                                .longValue())),
                id);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        var rows =
                db.query(
                        "SELECT * FROM iam_user WHERE username=?",
                        (rs, n) ->
                                new AccountPrincipal(
                                        rs.getString("username"),
                                        rs.getString("password_hash"),
                                        rs.getBoolean("enabled"),
                                        rs.getLong("version"),
                                        permissions(rs.getLong("id")).stream()
                                                .map(SimpleGrantedAuthority::new)
                                                .toList()),
                        username);
        if (rows.isEmpty()) throw new UsernameNotFoundException("账号不存在");
        return rows.getFirst();
    }

    public boolean sessionValid(AccountPrincipal user) {
        return Boolean.TRUE.equals(
                db.queryForObject(
                        "SELECT COUNT(*)=1 FROM iam_user WHERE username=? AND enabled=TRUE AND version=?",
                        Boolean.class,
                        user.getUsername(),
                        user.version()));
    }

    private void lock() {
        db.queryForObject("SELECT initialized FROM iam_guard WHERE id=1 FOR UPDATE", Boolean.class);
    }

    private void managerLock() {
        lock();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AccountPrincipal user
                && !sessionValid(user))
            throw new BusinessException(401, "SESSION_CHANGED", "账号权限已变化，请重新登录");
    }

    private static void validatePassword(String password) {
        if (password == null
                || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw PermissionCatalog.invalid("密码至少12位，UTF-8编码不超过72字节");
    }

    private static void validateUsername(String username) {
        if (username == null || !username.matches("[A-Za-z][A-Za-z0-9._-]{0,63}"))
            throw PermissionCatalog.invalid("账号须以字母开头，仅限字母、数字、点、下划线和横线，最多64位");
    }

    private static void reason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 300)
            throw PermissionCatalog.invalid("请填写不超过300字的变更原因");
    }

    private void grants(long id, Set<String> roles, Set<String> permissions) {
        db.update("DELETE FROM iam_user_role WHERE user_id=?", id);
        db.update("DELETE FROM iam_user_permission WHERE user_id=?", id);
        for (var role : roles)
            db.update("INSERT INTO iam_user_role(user_id,role_code) VALUES (?,?)", id, role);
        for (var permission : permissions)
            db.update(
                    "INSERT INTO iam_user_permission(user_id,permission) VALUES (?,?)",
                    id,
                    permission);
    }

    private Set<String> resolve(Set<String> roles, Set<Long> ids) {
        var result = PermissionCatalog.resolve(roles, ids);
        var known = new HashSet<Long>();
        warehouses.warehouses().forEach(w -> known.add(w.id()));
        if (!known.containsAll(ids)) throw PermissionCatalog.invalid("仓库不存在，请刷新仓库列表");
        return result;
    }

    private void audit(long id, String action, String actor, String reason, Account before) {
        db.update(
                "INSERT INTO iam_audit(user_id,action,actor,reason,before_state,after_state) VALUES (?,?,?,?,?,?)",
                id,
                action,
                actor,
                reason,
                before == null ? null : before.toString(),
                account(id).toString());
    }

    private long insert(
            String username,
            String name,
            String password,
            Set<String> roles,
            Set<String> permissions,
            String actor,
            String reason) {
        validateUsername(username);
        validatePassword(password);
        if (name == null || name.isBlank() || name.length() > 80)
            throw PermissionCatalog.invalid("姓名不能为空且最多80字");
        if (db.queryForObject(
                        "SELECT COUNT(*) FROM iam_user WHERE username=?", Long.class, username)
                > 0) throw new BusinessException(409, "USERNAME_EXISTS", "账号已存在，不能重用历史账号");
        db.update(
                "INSERT INTO iam_user(username,display_name,password_hash) VALUES (?,?,?)",
                username,
                name.trim(),
                encoder.encode(password));
        long id =
                db.queryForObject("SELECT id FROM iam_user WHERE username=?", Long.class, username);
        grants(id, roles, permissions);
        audit(id, "CREATE", actor, reason, null);
        return id;
    }

    @Transactional
    public void bootstrap(String username, String password, OperatorProperties operators) {
        lock();
        if (Boolean.TRUE.equals(
                db.queryForObject("SELECT initialized FROM iam_guard WHERE id=1", Boolean.class)))
            return;
        insert(
                username,
                username,
                password,
                Set.of("SYSTEM_ADMIN"),
                Set.of("iam:manage"),
                "BOOTSTRAP",
                "首次初始化系统管理员");
        if (operators.operators() != null)
            for (var op : operators.operators()) {
                PermissionCatalog.validateLegacy(op.authorities());
                insert(
                        op.username(),
                        op.username(),
                        op.password(),
                        Set.of("IMPORTED"),
                        new TreeSet<>(op.authorities()),
                        "BOOTSTRAP",
                        "一次性导入原配置业务账号");
            }
        db.update("UPDATE iam_guard SET initialized=TRUE WHERE id=1");
    }

    @Transactional
    public Account create(
            String username,
            String name,
            String password,
            Set<String> roles,
            Set<Long> ids,
            String reason) {
        managerLock();
        reason(reason);
        long id =
                insert(
                        username,
                        name,
                        password,
                        roles,
                        resolve(roles, ids),
                        actors.currentActor(),
                        reason);
        return account(id);
    }

    private Account current(long id, long version) {
        var before = account(id);
        if (before.version() != version)
            throw new BusinessException(409, "ACCOUNT_CHANGED", "账号已被修改，请刷新后重试");
        return before;
    }

    private void protectLastAdmin(Account before, boolean enabled, Set<String> roles) {
        if (before.enabled()
                && before.roles().contains("SYSTEM_ADMIN")
                && (!enabled || !roles.contains("SYSTEM_ADMIN"))
                && db.queryForObject(
                                "SELECT COUNT(*) FROM iam_user u JOIN iam_user_role r ON r.user_id=u.id WHERE u.enabled=TRUE AND r.role_code='SYSTEM_ADMIN'",
                                Long.class)
                        <= 1) throw new BusinessException(409, "LAST_ADMIN", "至少保留一个启用的系统管理员");
    }

    @Transactional
    public Account access(long id, long version, Set<String> roles, Set<Long> ids, String reason) {
        managerLock();
        reason(reason);
        var before = current(id, version);
        var permissions = resolve(roles, ids);
        if (before.username().equals(actors.currentActor()))
            throw new BusinessException(409, "SELF_ACCESS_CHANGE", "不能修改自己的角色或仓库范围，请由另一系统管理员操作");
        protectLastAdmin(before, before.enabled(), roles);
        grants(id, roles, permissions);
        db.update("UPDATE iam_user SET version=version+1 WHERE id=?", id);
        audit(id, "ACCESS_CHANGE", actors.currentActor(), reason, before);
        return account(id);
    }

    @Transactional
    public Account status(long id, long version, boolean enabled, String reason) {
        managerLock();
        reason(reason);
        var before = current(id, version);
        protectLastAdmin(before, enabled, before.roles());
        if (before.enabled() == enabled)
            throw new BusinessException(409, "UNCHANGED_STATUS", "账号状态未变化");
        db.update("UPDATE iam_user SET enabled=?,version=version+1 WHERE id=?", enabled, id);
        audit(id, enabled ? "ENABLE" : "DISABLE", actors.currentActor(), reason, before);
        return account(id);
    }

    @Transactional
    public void reset(long id, long version, String password, String reason) {
        managerLock();
        reason(reason);
        var before = current(id, version);
        validatePassword(password);
        db.update(
                "UPDATE iam_user SET password_hash=?,version=version+1 WHERE id=?",
                encoder.encode(password),
                id);
        audit(id, "PASSWORD_RESET", actors.currentActor(), reason, before);
    }

    @Transactional
    public void changePassword(String oldPassword, String password) {
        lock();
        validatePassword(password);
        var user = (AccountPrincipal) loadUserByUsername(actors.currentActor());
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth.getPrincipal() instanceof AccountPrincipal principal && !sessionValid(principal))
            throw new BusinessException(401, "SESSION_CHANGED", "登录已失效");
        if (oldPassword == null || !encoder.matches(oldPassword, user.getPassword()))
            throw PermissionCatalog.invalid("原密码不正确");
        if (encoder.matches(password, user.getPassword()))
            throw PermissionCatalog.invalid("新密码不能与原密码相同");
        long id =
                db.queryForObject(
                        "SELECT id FROM iam_user WHERE username=?", Long.class, user.getUsername());
        var before = account(id);
        db.update(
                "UPDATE iam_user SET password_hash=?,version=version+1 WHERE id=?",
                encoder.encode(password),
                id);
        audit(id, "PASSWORD_CHANGE", actors.currentActor(), "本人修改密码", before);
    }
}
