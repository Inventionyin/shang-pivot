package com.shangpivot.customerservice.agent.graph;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 图 checkpoint 存储：默认落 PostgreSQL（LG4JThread/LG4JCheckpoint，按 run 建 thread），
 * 建表失败或显式配置 agent.checkpoint=memory 时回退内存，不影响对话可用性。
 */
@Slf4j
@Configuration
public class AgentCheckpointConfig {

    @Bean
    public BaseCheckpointSaver agentCheckpointSaver(ObjectProvider<DataSource> dataSourceProvider,
                                                    @Value("${agent.checkpoint:postgres}") String mode) {
        if (!"postgres".equalsIgnoreCase(mode)) {
            log.info("[cs] agent checkpoint: memory (agent.checkpoint={})", mode);
            return new MemorySaver();
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            log.warn("[cs] agent checkpoint: memory (no DataSource available)");
            return new MemorySaver();
        }
        try {
            StateSerializer<AgentGraphState> serializer = new ObjectStreamStateSerializer<>(AgentGraphState::new);
            BaseCheckpointSaver saver = PostgresSaver.builder()
                    .datasource(dataSource)
                    .createTables(true)
                    .stateSerializer(serializer)
                    .build();
            log.info("[cs] agent checkpoint: postgres (LG4JThread/LG4JCheckpoint)");
            return new ResilientCheckpointSaver(saver);
        } catch (Exception e) {
            log.warn("[cs] agent checkpoint unavailable, fallback to memory: {}", e.toString());
            return new MemorySaver();
        }
    }
}
