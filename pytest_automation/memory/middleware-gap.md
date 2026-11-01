# 中间件现状（2026-10-30 已补 MQ）

后端已引入 RabbitMQ 3.13（Spring AMQP）：订单支付成功事件 `ORDER_PAID` 经 topic 交换机投递，消费者按 (order_id, event_type) 幂等写入 `commerce.order_event_log`（Flyway V21），失败重试 3 次进 DLQ。
Redis 仍是四类键：SMS 验证码/冷却、登录失败计数、JWT 黑名单；无业务缓存与分布式锁。
测试：`tests/api/test_mq_order_events.py`（落库/重复投递/DLQ）、`tests/api/test_redis_state.py`（TTL/锁定/黑名单，全部 serial）。
本地 RabbitMQ 镜像是 `docker.m.daocloud.io/library/rabbitmq:3-management`（Docker Hub 直连超时），需 tag 成 `rabbitmq:3-management` 供 compose 使用。
