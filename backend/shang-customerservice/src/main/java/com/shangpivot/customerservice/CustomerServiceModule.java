package com.shangpivot.customerservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Customer service bounded context.
 * <p>Boot 4 默认自动配置的是 Jackson 3（tools.jackson）的 ObjectMapper；
 * 本模块 LLM 网关解析使用经典 fasterxml ObjectMapper（随 minio 传递依赖提供），在此显式注册。
 */
@Configuration
public class CustomerServiceModule {

    @Bean
    public ObjectMapper csObjectMapper() {
        return new ObjectMapper();
    }
}
