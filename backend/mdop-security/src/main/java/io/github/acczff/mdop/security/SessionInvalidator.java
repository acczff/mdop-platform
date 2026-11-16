package io.github.acczff.mdop.security;

/** 按用户名主动清理外部化会话（如权限变更、账号停用或重置密码时）。 */
public interface SessionInvalidator {
    void invalidateByUsername(String username);
}
