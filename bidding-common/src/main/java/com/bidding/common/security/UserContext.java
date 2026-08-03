package com.bidding.common.security;

/**
 * 用 ThreadLocal 保存「当前请求」的登录用户。
 * <p>
 * 由 JwtAuthenticationFilter 在请求开始时 set，结束时 clear，避免线程复用串数据。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new IllegalStateException("CurrentUser not bound");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
