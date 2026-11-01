package com.shangpivot.common.security;

public final class SecurityContexts {
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private SecurityContexts() {}

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        return HOLDER.get();
    }

    public static Long requireUserId() {
        LoginUser user = HOLDER.get();
        if (user == null || user.getUserId() == null) {
            throw new com.shangpivot.common.exception.BizException(
                    com.shangpivot.common.result.ErrorCode.UNAUTHORIZED);
        }
        return user.getUserId();
    }

    /** 已登录返回 userId，游客返回 null（不抛异常）。 */
    public static Long optionalUserId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
