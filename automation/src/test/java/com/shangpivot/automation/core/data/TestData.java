package com.shangpivot.automation.core.data;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试数据生成：每个写场景使用独立会员，避免共享账号互相污染。
 */
public final class TestData {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 100_000);

    private TestData() {
    }

    /** 11 位、以 139 开头的随机手机号；首次登录会自动注册为新会员。 */
    public static String uniquePhone() {
        long tail = (SEQ.incrementAndGet() * 997L + ThreadLocalRandom.current().nextInt(1000)) % 100_000_000L;
        return "139" + String.format("%08d", tail);
    }

    public static String uniqueKey(String prefix) {
        return prefix + "-" + Long.toHexString(System.nanoTime()) + "-" + SEQ.incrementAndGet();
    }

    public static String randomKeywordMiss() {
        return "不存在商品" + Long.toHexString(System.nanoTime());
    }
}
