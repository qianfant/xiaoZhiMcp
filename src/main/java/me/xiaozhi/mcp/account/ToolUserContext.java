package me.xiaozhi.mcp.account;

import java.util.function.Supplier;

/** Explicit execution scope; never inherit identity into pooled background threads. */
public final class ToolUserContext {
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();
    private ToolUserContext() {}
    public static Long requireId() {
        Long id = USER.get();
        if (id == null) throw new IllegalStateException("工具调用缺少用户上下文");
        return id;
    }
    public static <T> T call(Long id, Supplier<T> action) {
        Long previous = USER.get();
        USER.set(id);
        try { return action.get(); }
        finally { if (previous == null) USER.remove(); else USER.set(previous); }
    }
}
