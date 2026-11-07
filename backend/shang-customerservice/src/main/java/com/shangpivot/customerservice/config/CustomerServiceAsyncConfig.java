package com.shangpivot.customerservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** Tool Gateway 超时控制用的独立线程池；工具内部禁止再起线程。 */
    @Bean(name = "toolExecutorPool", destroyMethod = "shutdown")
    public ExecutorService toolExecutorPool() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "cs-tool-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(8, factory);
    }
}
