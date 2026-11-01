package com.shangpivot.customerservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** 智能客服 SSE 编排执行器（独立线程池，不占用容器请求线程）。 */
@Configuration
public class CustomerServiceAsyncConfig {

    @Bean("csAgentExecutor")
    public Executor csAgentExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(16);
        ex.setQueueCapacity(64);
        ex.setThreadNamePrefix("cs-agent-");
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }
}
