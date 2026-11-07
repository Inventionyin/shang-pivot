package com.shangpivot.customerservice.agent.graph;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ResilientCheckpointSaverTest {

    private final BaseCheckpointSaver delegate = Mockito.mock(BaseCheckpointSaver.class);
    private final ResilientCheckpointSaver saver = new ResilientCheckpointSaver(delegate);
    private final RunnableConfig config = RunnableConfig.builder().threadId("run-1").build();

    @Test
    void putFailureDoesNotBreakTheGraphRun() throws Exception {
        when(delegate.put(any(), any())).thenThrow(new IllegalStateException("db down"));
        Checkpoint checkpoint = Checkpoint.builder().id("c1").state(java.util.Map.of()).nodeId("route").nextNodeId("execute").build();
        assertEquals(config, saver.put(config, checkpoint));
    }

    @Test
    void readFailuresDegradeToEmpty() {
        when(delegate.get(any())).thenThrow(new IllegalStateException("db down"));
        when(delegate.list(any())).thenThrow(new IllegalStateException("db down"));
        assertTrue(saver.get(config).isEmpty());
        assertTrue(saver.list(config).isEmpty());
    }

    @Test
    void releaseFailureStillReturnsATag() throws Exception {
        when(delegate.release(any())).thenThrow(new IllegalStateException("db down"));
        assertTrue(saver.release(config).checkpoints().isEmpty());
        assertEquals("run-1", saver.release(config).threadId());
    }
}
