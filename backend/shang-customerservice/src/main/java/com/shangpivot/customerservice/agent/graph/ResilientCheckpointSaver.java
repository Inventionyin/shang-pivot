package com.shangpivot.customerservice.agent.graph;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * checkpoint 保底：Postgres 不可用时只丢 checkpoint，不能让整轮对话失败。
 * 首次失败和之后每 50 次失败打一条 warn，避免数据库抖动时刷爆日志。
 */
@Slf4j
public class ResilientCheckpointSaver implements BaseCheckpointSaver {

    private final BaseCheckpointSaver delegate;
    private final AtomicLong failures = new AtomicLong();

    public ResilientCheckpointSaver(BaseCheckpointSaver delegate) {
        this.delegate = delegate;
    }

    private void warn(String op, Exception e) {
        long n = failures.incrementAndGet();
        if (n == 1 || n % 50 == 0) {
            log.warn("[cs] checkpoint {} failed (n={}), continue without checkpoint: {}", op, n, e.toString());
        }
    }

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        try {
            return delegate.list(config);
        } catch (Exception e) {
            warn("list", e);
            return List.of();
        }
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        try {
            return delegate.get(config);
        } catch (Exception e) {
            warn("get", e);
            return Optional.empty();
        }
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        try {
            return delegate.put(config, checkpoint);
        } catch (Exception e) {
            warn("put", e);
            return config;
        }
    }

    @Override
    public Tag release(RunnableConfig config) {
        try {
            return delegate.release(config);
        } catch (Exception e) {
            warn("release", e);
            return emptyTag(config);
        }
    }

    @Override
    public Tag release(RunnableConfig config, String tag) {
        try {
            return delegate.release(config, tag);
        } catch (Exception e) {
            warn("release", e);
            return emptyTag(config);
        }
    }

    @Override
    public Tag releaseOnError(RunnableConfig config, Throwable error) {
        try {
            return delegate.releaseOnError(config, error);
        } catch (Exception e) {
            warn("releaseOnError", e);
            return emptyTag(config);
        }
    }

    @Override
    public <State extends org.bsc.langgraph4j.state.AgentState>
    CompletableFuture<org.bsc.langgraph4j.action.InterruptionMetadata<State>> registerInterruption(
            RunnableConfig config, org.bsc.langgraph4j.action.InterruptionMetadata<State> metadata) {
        try {
            return delegate.registerInterruption(config, metadata);
        } catch (Exception e) {
            warn("registerInterruption", e);
            return CompletableFuture.completedFuture(metadata);
        }
    }

    @Override
    public Optional<Tag> tag(RunnableConfig config, Integer version) {
        try {
            return delegate.tag(config, version);
        } catch (Exception e) {
            warn("tag", e);
            return Optional.empty();
        }
    }

    @Override
    public void putSubGraphSaver(RunnableConfig parent, RunnableConfig child, BaseCheckpointSaver saver) {
        try {
            delegate.putSubGraphSaver(parent, child, saver);
        } catch (Exception e) {
            warn("putSubGraphSaver", e);
        }
    }

    @Override
    public Collection<SubGraphSaver> listSubGraphSaver(RunnableConfig config) {
        try {
            return delegate.listSubGraphSaver(config);
        } catch (Exception e) {
            warn("listSubGraphSaver", e);
            return List.of();
        }
    }

    private Tag emptyTag(RunnableConfig config) {
        return new Tag(threadId(config), List.of());
    }
}
