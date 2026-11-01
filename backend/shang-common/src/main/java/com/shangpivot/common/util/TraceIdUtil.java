package com.shangpivot.common.util;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import org.slf4j.MDC;

public final class TraceIdUtil {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_USERNAME = "username";

    private TraceIdUtil() {}

    public static String get() {
        String id = MDC.get(MDC_KEY);
        return StrUtil.isBlank(id) ? "" : id;
    }

    public static String getOrCreate(String incoming) {
        if (StrUtil.isNotBlank(incoming)) {
            return incoming;
        }
        return IdUtil.fastSimpleUUID();
    }

    public static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static void setUser(Long userId, String username) {
        if (userId != null) {
            MDC.put(MDC_USER_ID, String.valueOf(userId));
        }
        if (StrUtil.isNotBlank(username)) {
            MDC.put(MDC_USERNAME, username);
        }
    }

    public static String userId() {
        String v = MDC.get(MDC_USER_ID);
        return StrUtil.blankToDefault(v, "-");
    }

    public static String username() {
        String v = MDC.get(MDC_USERNAME);
        return StrUtil.blankToDefault(v, "-");
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_USERNAME);
    }
}
