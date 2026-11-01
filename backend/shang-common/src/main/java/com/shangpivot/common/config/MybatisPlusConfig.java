package com.shangpivot.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.shangpivot.common.util.TraceIdUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 注意：3.5.17 的 PaginationInnerInterceptor 已无 optimizeCountSql 开关，
        // count 查询优化是固定行为——会自动剥掉 ORDER BY，因此业务里用
        // wrapper.last("ORDER BY …") 做排序是安全的（见 StockService 库存预警查询）。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        // 乐观锁拦截器：Stock 实体用 @Version 做并发扣减，没有这个拦截器时
        // updateById 生成的 SQL 会带 AND version=?，但参数 MP_OPTLOCK_VERSION_ORIGINAL
        // 无人绑定 → BindingException → 下单锁库存直接 500。
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                OffsetDateTime now = OffsetDateTime.now();
                strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
                strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
                strictInsertFill(metaObject, "deleted", Boolean.class, false);
                if (getFieldValByName("traceId", metaObject) == null) {
                    strictInsertFill(metaObject, "traceId", String.class, TraceIdUtil.get());
                }
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, OffsetDateTime.now());
            }
        };
    }
}
